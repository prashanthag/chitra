"""Photo/video server. Scans a media root, indexes files in SQLite,
generates JPEG thumbnails on demand, streams originals + range-served video."""

from __future__ import annotations

import hashlib
import io
import mimetypes
import os
import re
import sqlite3
import subprocess
import threading
import time
from pathlib import Path
from typing import Iterator

from flask import (
    Flask,
    Response,
    abort,
    g,
    jsonify,
    request,
    send_file,
    send_from_directory,
)
from PIL import Image, ImageOps

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
    HEIC_OK = True
except Exception:
    HEIC_OK = False


# ---------- Config ----------

MEDIA_ROOT = Path(
    os.environ.get(
        "PHOTO_ROOT", "/media/ina/6f947802-92c2-49d4-b05f-8bf122b0b5a7/photos"
    )
).resolve()

APP_DIR = Path(__file__).resolve().parent
CACHE_DIR = APP_DIR / "cache"
THUMB_DIR = CACHE_DIR / "thumbs"
DB_PATH = CACHE_DIR / "index.db"

CACHE_DIR.mkdir(exist_ok=True)
THUMB_DIR.mkdir(exist_ok=True)

PHOTO_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".webm", ".3gp"}
ALL_EXTS = PHOTO_EXTS | VIDEO_EXTS

THUMB_SIZE = 480  # px max edge


# ---------- DB ----------


def db() -> sqlite3.Connection:
    if "db" not in g:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        g.db = conn
    return g.db


def init_db() -> None:
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS media (
            id TEXT PRIMARY KEY,
            path TEXT UNIQUE NOT NULL,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,            -- 'photo' | 'video'
            ext TEXT NOT NULL,
            mime TEXT,
            size INTEGER,
            mtime REAL,
            taken_at REAL,                  -- best-effort epoch seconds
            width INTEGER,
            height INTEGER,
            album TEXT                      -- top-level subdir under MEDIA_ROOT
        );
        CREATE INDEX IF NOT EXISTS idx_media_taken ON media(taken_at DESC);
        CREATE INDEX IF NOT EXISTS idx_media_kind ON media(kind);
        CREATE INDEX IF NOT EXISTS idx_media_album ON media(album);

        CREATE TABLE IF NOT EXISTS favorites (
            media_id TEXT PRIMARY KEY,
            created_at REAL NOT NULL,
            FOREIGN KEY (media_id) REFERENCES media(id)
        );

        CREATE TABLE IF NOT EXISTS persons (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT UNIQUE NOT NULL
        );
        CREATE TABLE IF NOT EXISTS person_media (
            person_id INTEGER NOT NULL,
            media_id TEXT NOT NULL,
            PRIMARY KEY (person_id, media_id),
            FOREIGN KEY (person_id) REFERENCES persons(id),
            FOREIGN KEY (media_id) REFERENCES media(id)
        );

        CREATE TABLE IF NOT EXISTS scan_state (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        """
    )
    conn.commit()
    conn.close()


# ---------- Indexing ----------


def make_id(path: Path) -> str:
    return hashlib.sha1(str(path).encode("utf-8")).hexdigest()[:16]


def classify(ext: str) -> str | None:
    ext = ext.lower()
    if ext in PHOTO_EXTS:
        return "photo"
    if ext in VIDEO_EXTS:
        return "video"
    return None


def extract_taken_at(path: Path, kind: str) -> float | None:
    """Best-effort timestamp. Reads EXIF for photos; falls back to mtime."""
    if kind == "photo":
        try:
            with Image.open(path) as im:
                exif = im.getexif()
                # 36867 = DateTimeOriginal
                dt = exif.get(36867) or exif.get(306)
                if dt:
                    # "YYYY:MM:DD HH:MM:SS"
                    return time.mktime(time.strptime(dt, "%Y:%m:%d %H:%M:%S"))
        except Exception:
            pass
    try:
        return path.stat().st_mtime
    except OSError:
        return None


_scan_lock = threading.Lock()
_scan_state = {"running": False, "scanned": 0, "total_seen": 0, "started_at": 0.0}


def scan_once() -> None:
    if not MEDIA_ROOT.exists():
        print(f"[scan] media root does not exist: {MEDIA_ROOT}")
        return
    with _scan_lock:
        if _scan_state["running"]:
            return
        _scan_state.update(
            {"running": True, "scanned": 0, "total_seen": 0, "started_at": time.time()}
        )

    print(f"[scan] starting at {MEDIA_ROOT}")
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    # Build set of known paths so we can skip unchanged & detect deletes
    cur.execute("SELECT path, mtime FROM media")
    known: dict[str, float] = {r["path"]: r["mtime"] for r in cur.fetchall()}
    seen: set[str] = set()

    inserted = 0
    updated = 0
    batch: list[tuple] = []
    BATCH_SIZE = 200

    def flush():
        nonlocal batch
        if not batch:
            return
        cur.executemany(
            """INSERT OR REPLACE INTO media
               (id, path, name, kind, ext, mime, size, mtime, taken_at, width, height, album)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            batch,
        )
        conn.commit()
        batch = []

    for root, dirs, files in os.walk(MEDIA_ROOT):
        # Skip hidden / system folders
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for fname in files:
            if fname.startswith(".") or fname.startswith("._"):
                continue
            ext = Path(fname).suffix.lower()
            kind = classify(ext)
            if not kind:
                continue
            full = Path(root) / fname
            try:
                st = full.stat()
            except OSError:
                continue
            spath = str(full)
            seen.add(spath)
            _scan_state["total_seen"] += 1
            if spath in known and abs(known[spath] - st.st_mtime) < 1e-6:
                _scan_state["scanned"] += 1
                continue
            mid = make_id(full)
            mime = mimetypes.guess_type(fname)[0]
            rel = full.relative_to(MEDIA_ROOT)
            album = rel.parts[0] if len(rel.parts) > 1 else "_root"
            taken = extract_taken_at(full, kind)
            width = height = None
            if kind == "photo":
                try:
                    with Image.open(full) as im:
                        width, height = im.size
                except Exception:
                    pass
            batch.append(
                (
                    mid,
                    spath,
                    fname,
                    kind,
                    ext,
                    mime,
                    st.st_size,
                    st.st_mtime,
                    taken,
                    width,
                    height,
                    album,
                )
            )
            _scan_state["scanned"] += 1
            if spath in known:
                updated += 1
            else:
                inserted += 1
            if len(batch) >= BATCH_SIZE:
                flush()
    flush()

    # Prune deletes
    deletes = [p for p in known.keys() if p not in seen]
    if deletes:
        cur.executemany("DELETE FROM media WHERE path = ?", [(p,) for p in deletes])
        conn.commit()

    cur.execute(
        "INSERT OR REPLACE INTO scan_state (key, value) VALUES ('last_scan', ?)",
        (str(time.time()),),
    )
    conn.commit()
    conn.close()
    with _scan_lock:
        _scan_state["running"] = False
    print(
        f"[scan] done: inserted={inserted} updated={updated} "
        f"deleted={len(deletes)} total={_scan_state['total_seen']}"
    )


def start_background_scan() -> None:
    t = threading.Thread(target=scan_once, daemon=True, name="scan")
    t.start()


# ---------- Thumbnails ----------


def thumb_path_for(mid: str) -> Path:
    return THUMB_DIR / f"{mid}.jpg"


def make_photo_thumb(src: Path, dst: Path) -> bool:
    try:
        with Image.open(src) as im:
            im = ImageOps.exif_transpose(im)
            im.thumbnail((THUMB_SIZE, THUMB_SIZE), Image.LANCZOS)
            if im.mode not in ("RGB", "L"):
                im = im.convert("RGB")
            im.save(dst, "JPEG", quality=82, optimize=True)
        return True
    except Exception as e:
        print(f"[thumb] photo failed {src}: {e}")
        return False


USE_CUDA = os.environ.get("USE_CUDA", "1") == "1"


def _ffmpeg_thumb_cmd(src: Path, dst: Path, seek: str, cuda: bool) -> list[str]:
    """Build ffmpeg cmd. With CUDA we decode on GPU, then download to CPU
    (JPEG isn't an NVENC codec) and scale via the scale filter."""
    if cuda:
        return [
            "ffmpeg", "-loglevel", "error",
            "-hwaccel", "cuda", "-hwaccel_output_format", "cuda",
            "-ss", seek, "-i", str(src),
            "-frames:v", "1",
            "-vf", f"scale_cuda='min({THUMB_SIZE},iw)':-2,hwdownload,format=nv12",
            "-y", str(dst),
        ]
    return [
        "ffmpeg", "-loglevel", "error",
        "-ss", seek, "-i", str(src),
        "-frames:v", "1",
        "-vf", f"scale='min({THUMB_SIZE},iw)':-2",
        "-y", str(dst),
    ]


def make_video_thumb(src: Path, dst: Path) -> bool:
    """Extract a frame ~1s in. Tries CUDA-accelerated decode first, falls back to CPU."""
    for seek in ("1", "0"):
        for cuda in ((True, False) if USE_CUDA else (False,)):
            try:
                cmd = _ffmpeg_thumb_cmd(src, dst, seek, cuda)
                r = subprocess.run(cmd, capture_output=True, timeout=30)
                if r.returncode == 0 and dst.exists() and dst.stat().st_size > 0:
                    return True
            except Exception as e:
                print(f"[thumb] video {src} (cuda={cuda}): {e}")
    return False


def ensure_thumb(row: sqlite3.Row) -> Path | None:
    p = thumb_path_for(row["id"])
    if p.exists() and p.stat().st_size > 0:
        return p
    src = Path(row["path"])
    if not src.exists():
        return None
    if row["kind"] == "photo":
        ok = make_photo_thumb(src, p)
    else:
        ok = make_video_thumb(src, p)
    return p if ok else None


# ---------- Range responses (video) ----------

_RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")


def _range_stream(path: Path, start: int, end: int, chunk: int = 1024 * 256) -> Iterator[bytes]:
    with path.open("rb") as f:
        f.seek(start)
        remaining = end - start + 1
        while remaining > 0:
            data = f.read(min(chunk, remaining))
            if not data:
                break
            remaining -= len(data)
            yield data


def serve_with_range(path: Path, mime: str | None) -> Response:
    size = path.stat().st_size
    rng = request.headers.get("Range")
    if not rng:
        return send_file(path, mimetype=mime, conditional=True)
    m = _RANGE_RE.match(rng)
    if not m:
        return send_file(path, mimetype=mime, conditional=True)
    start = int(m.group(1)) if m.group(1) else 0
    end = int(m.group(2)) if m.group(2) else size - 1
    end = min(end, size - 1)
    if start > end:
        return Response(status=416)
    length = end - start + 1
    resp = Response(
        _range_stream(path, start, end),
        status=206,
        mimetype=mime or "application/octet-stream",
        direct_passthrough=True,
    )
    resp.headers["Content-Range"] = f"bytes {start}-{end}/{size}"
    resp.headers["Accept-Ranges"] = "bytes"
    resp.headers["Content-Length"] = str(length)
    return resp


# ---------- App ----------

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024 * 1024  # 8 GiB upload cap


@app.teardown_appcontext
def _close_db(_exc):
    conn = g.pop("db", None)
    if conn is not None:
        conn.close()


@app.get("/api/health")
def health():
    cur = db().execute("SELECT COUNT(*) AS n FROM media")
    n = cur.fetchone()["n"]
    return jsonify(
        {
            "ok": True,
            "media_root": str(MEDIA_ROOT),
            "media_root_exists": MEDIA_ROOT.exists(),
            "heic_supported": HEIC_OK,
            "items_indexed": n,
            "scan": dict(_scan_state),
        }
    )


@app.post("/api/rescan")
def rescan():
    start_background_scan()
    return jsonify({"ok": True, "scan": dict(_scan_state)})


@app.get("/api/media")
def list_media():
    page = max(1, int(request.args.get("page", 1)))
    per_page = min(200, max(1, int(request.args.get("per_page", 60))))
    kind = request.args.get("kind")
    album = request.args.get("album")
    q = "SELECT id,name,kind,ext,mime,size,taken_at,width,height,album FROM media"
    where = []
    args: list = []
    if kind in ("photo", "video"):
        where.append("kind = ?")
        args.append(kind)
    if album:
        where.append("album = ?")
        args.append(album)
    if where:
        q += " WHERE " + " AND ".join(where)
    q += " ORDER BY COALESCE(taken_at, mtime) DESC LIMIT ? OFFSET ?"
    args += [per_page, (page - 1) * per_page]
    rows = [dict(r) for r in db().execute(q, args).fetchall()]
    total = db().execute(
        "SELECT COUNT(*) AS n FROM media"
        + (" WHERE " + " AND ".join(where) if where else ""),
        args[:-2],
    ).fetchone()["n"]
    return jsonify(
        {"page": page, "per_page": per_page, "total": total, "items": rows}
    )


@app.get("/api/albums")
def list_albums():
    rows = db().execute(
        "SELECT album, COUNT(*) AS count FROM media GROUP BY album ORDER BY album"
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.get("/api/media/<mid>")
def media_meta(mid: str):
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    return jsonify(dict(r))


@app.get("/api/media/<mid>/thumb")
def media_thumb(mid: str):
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    p = ensure_thumb(r)
    if not p:
        abort(500, "thumb generation failed")
    return send_file(p, mimetype="image/jpeg", conditional=True)


@app.get("/api/media/<mid>/stream.mp4")
def media_stream_mp4(mid: str):
    """Transcode any video to fragmented H.264 MP4 on the fly using NVENC.
    Lets Android play formats it doesn't natively support (e.g. some MKV/MOV codecs)."""
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r or r["kind"] != "video":
        abort(404)
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    cmd = [
        "ffmpeg", "-loglevel", "error",
        "-hwaccel", "cuda",
        "-i", str(src),
        "-c:v", "h264_nvenc", "-preset", "p3", "-tune", "hq",
        "-b:v", "4M", "-maxrate", "6M", "-bufsize", "8M",
        "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "frag_keyframe+empty_moov+faststart",
        "-f", "mp4", "pipe:1",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def gen():
        try:
            while True:
                chunk = proc.stdout.read(1024 * 64)
                if not chunk:
                    break
                yield chunk
        finally:
            try:
                proc.kill()
            except Exception:
                pass

    return Response(gen(), mimetype="video/mp4")


@app.get("/api/media/<mid>/full")
def media_full(mid: str):
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    # For HEIC, convert on the fly to JPEG for clients that can't render it
    if r["ext"] in (".heic", ".heif") and request.args.get("as") == "jpeg":
        try:
            with Image.open(src) as im:
                im = ImageOps.exif_transpose(im).convert("RGB")
                buf = io.BytesIO()
                im.save(buf, "JPEG", quality=90)
                buf.seek(0)
                return send_file(buf, mimetype="image/jpeg")
        except Exception:
            abort(500)
    mime = r["mime"]
    if r["kind"] == "video":
        return serve_with_range(src, mime)
    return send_file(src, mimetype=mime, conditional=True)


# ---------- Person tagging (manual stub; ready for ML later) ----------


@app.get("/api/persons")
def list_persons():
    rows = db().execute(
        """SELECT p.id, p.name, COUNT(pm.media_id) AS count
           FROM persons p LEFT JOIN person_media pm ON pm.person_id = p.id
           GROUP BY p.id, p.name ORDER BY p.name"""
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.post("/api/persons")
def create_person():
    name = (request.json or {}).get("name", "").strip()
    if not name:
        abort(400, "name required")
    try:
        db().execute("INSERT INTO persons (name) VALUES (?)", (name,))
        db().commit()
    except sqlite3.IntegrityError:
        pass
    r = db().execute("SELECT id, name FROM persons WHERE name = ?", (name,)).fetchone()
    return jsonify(dict(r))


@app.post("/api/persons/<int:pid>/tag/<mid>")
def tag_person(pid: int, mid: str):
    db().execute(
        "INSERT OR IGNORE INTO person_media (person_id, media_id) VALUES (?, ?)",
        (pid, mid),
    )
    db().commit()
    return jsonify({"ok": True})


@app.delete("/api/persons/<int:pid>/tag/<mid>")
def untag_person(pid: int, mid: str):
    db().execute(
        "DELETE FROM person_media WHERE person_id = ? AND media_id = ?",
        (pid, mid),
    )
    db().commit()
    return jsonify({"ok": True})


@app.get("/api/persons/<int:pid>/media")
def media_of_person(pid: int):
    rows = db().execute(
        """SELECT m.id, m.name, m.kind, m.taken_at, m.album
           FROM media m JOIN person_media pm ON pm.media_id = m.id
           WHERE pm.person_id = ? ORDER BY COALESCE(m.taken_at, m.mtime) DESC""",
        (pid,),
    ).fetchall()
    return jsonify([dict(r) for r in rows])


# ---------- Simple web UI ----------


@app.get("/")
def index():
    return send_from_directory(APP_DIR / "static", "index.html")


@app.get("/static/<path:p>")
def static_file(p):
    return send_from_directory(APP_DIR / "static", p)


# ---------- Entrypoint ----------


def main() -> None:
    init_db()
    start_background_scan()
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", 8000))
    print(f"[server] listening on http://{host}:{port}")
    print(f"[server] media root: {MEDIA_ROOT}")
    app.run(host=host, port=port, threaded=True)


if __name__ == "__main__":
    main()
