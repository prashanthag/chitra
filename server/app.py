"""Photo/video server. Scans a media root, indexes files in SQLite,
generates JPEG thumbnails on demand, streams originals + range-served video."""

from __future__ import annotations

import calendar
import hashlib
import struct
import io
from html import escape
import json
import mimetypes
import os
import re
import sqlite3
import subprocess
import threading
import time
from pathlib import Path
from typing import Iterator

try:
    from dotenv import load_dotenv

    load_dotenv(Path(__file__).resolve().parent / ".env")
except ImportError:
    pass

import gpu

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
CACHE_DIR = Path(os.environ.get("CACHE_DIR", APP_DIR / "cache"))
THUMB_DIR = CACHE_DIR / "thumbs"
PREVIEW_DIR = CACHE_DIR / "previews"
DB_PATH = CACHE_DIR / "index.db"

CACHE_DIR.mkdir(parents=True, exist_ok=True)
THUMB_DIR.mkdir(exist_ok=True)
PREVIEW_DIR.mkdir(exist_ok=True)

# CHITRA_READONLY=1 serves the library for browsing only: every mutating
# endpoint (upload, trash, delete, edit, tagging) returns 403. Rescan stays
# allowed so a growing library can be re-indexed.
READ_ONLY = os.environ.get("CHITRA_READONLY", "0").lower() in ("1", "true", "yes")

PHOTO_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".webm", ".3gp"}
ALL_EXTS = PHOTO_EXTS | VIDEO_EXTS

THUMB_SIZE = 480  # px max edge of the default grid thumbnail
# Every size a client may ask for with ?w=. 32 is the ~1 KB placeholder tier
# shown while a tile's real thumb is still on its way; 160 covers small
# cards; 1024 is for large desktop tiles on high-DPI screens. Requests snap
# up to the next size, so only these files ever exist in the cache.
THUMB_SIZES = (32, 160, THUMB_SIZE, 1024)
# Viewer-sized JPEG: fits any screen, so the viewer never has to download
# and decode a 12 MP original (or transcode a HEIC) just to look at it.
PREVIEW_SIZE = 2048


# ---------- DB ----------


def db() -> sqlite3.Connection:
    if "db" not in g:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        g.db = conn
    return g.db


def _add_column_if_missing(conn: sqlite3.Connection, table: str, col_def: str) -> None:
    col_name = col_def.split()[0]
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    if any(r[1] == col_name for r in rows):
        return
    conn.execute(f"ALTER TABLE {table} ADD COLUMN {col_def}")


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

        -- Manual albums: a named set of media ids, independent of folders.
        CREATE TABLE IF NOT EXISTS albums (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            cover_id TEXT,
            share_token TEXT,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS album_media (
            album_id INTEGER NOT NULL,
            media_id TEXT NOT NULL,
            added_at REAL NOT NULL,
            PRIMARY KEY (album_id, media_id)
        );
        CREATE INDEX IF NOT EXISTS idx_album_media_media ON album_media(media_id);
        CREATE INDEX IF NOT EXISTS idx_albums_share ON albums(share_token);

        CREATE TABLE IF NOT EXISTS scan_state (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        """
    )
    # Idempotent migrations
    _add_column_if_missing(conn, "media", "trashed_at REAL")
    _add_column_if_missing(conn, "media", "archived INTEGER NOT NULL DEFAULT 0")
    _add_column_if_missing(conn, "media", "lat REAL")
    _add_column_if_missing(conn, "media", "lng REAL")
    _add_column_if_missing(conn, "media", "share_token TEXT")
    _add_column_if_missing(conn, "media", "edit_version INTEGER NOT NULL DEFAULT 0")
    _add_column_if_missing(conn, "media", "camera_make TEXT")
    _add_column_if_missing(conn, "media", "camera_model TEXT")
    # When the row first entered the index (upload or scan). Drives the
    # "Recently uploaded" view; taken_at says when it was shot, not added.
    _add_column_if_missing(conn, "media", "added_at REAL")
    # Quick content hash (see quick_hash): exact-copy detection that does not
    # depend on the file name. NULL until the backfill thread gets to a row.
    _add_column_if_missing(conn, "media", "content_hash TEXT")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_media_hash ON media(content_hash)")
    conn.execute("UPDATE media SET added_at = mtime WHERE added_at IS NULL")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_media_share ON media(share_token)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_media_latlng ON media(lat, lng)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_media_camera ON media(camera_model)")
    # Upload de-duplication looks rows up by (name, size); without this the
    # phone's 200-entry pre-flight check was 200 full-table scans (~10 s).
    conn.execute("CREATE INDEX IF NOT EXISTS idx_media_name_size ON media(name, size)")
    # Browse indexes: every feed filters on (trashed_at, archived) and then
    # orders by capture date (dated views), file date (undated views) or
    # added_at (recent uploads). Each is a prefix walk with no sort step, so
    # a page costs ~1 ms instead of a 40k-row scan + temp b-tree sort.
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_media_dated "
        "ON media(trashed_at, archived, taken_at DESC, album)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_media_mtime "
        "ON media(trashed_at, archived, mtime DESC)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_media_added "
        "ON media(album, trashed_at, added_at DESC)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_media_undated "
        "ON media(trashed_at, archived, taken_at, kind, mtime DESC)"
    )
    # clip_embedding belongs to clip_indexer.py and may not exist yet; once it
    # does, a partial index turns /api/health's "how many are CLIP-indexed"
    # from a scan over 40k BLOB rows into a count over a tiny index.
    cols = {r[1] for r in conn.execute("PRAGMA table_info(media)")}
    if "clip_embedding" in cols:
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_media_clip ON media(id) "
            "WHERE clip_embedding IS NOT NULL AND length(clip_embedding) > 0"
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


def _dms_to_dd(dms, ref) -> float | None:
    """Convert EXIF GPS DMS (degrees, minutes, seconds) tuple to decimal degrees."""
    try:
        d = float(dms[0])
        m = float(dms[1])
        s = float(dms[2])
        dd = d + m / 60.0 + s / 3600.0
        if ref in ("S", "W"):
            dd = -dd
        return dd
    except Exception:
        return None


def _clean_exif_str(v) -> str | None:
    """Normalize an EXIF string tag: bytes->str, trim, drop NULs/empties."""
    if v is None:
        return None
    if isinstance(v, bytes):
        v = v.decode("utf-8", "ignore")
    v = str(v).replace("\x00", "").strip()
    return v or None


# Content hash used for de-duplication on upload and scan. Not a full-file
# digest: SHA-256 of the byte size, the first 1 MiB and the last 64 KiB.
# That is enough to tell exact copies from everything else (two different
# photos never share size plus their first megabyte), costs one small read
# per file on a phone, and the Android client computes the identical value
# (data/ContentHash.kt) so the pre-flight check can match by content.
HASH_HEAD = 1 << 20
HASH_TAIL = 64 << 10


def quick_hash(f, size: int) -> str:
    """f is a seekable binary stream; its position is restored afterwards."""
    pos = f.tell()
    h = hashlib.sha256()
    h.update(struct.pack(">Q", size))
    f.seek(0)
    h.update(f.read(HASH_HEAD))
    if size > HASH_HEAD:
        f.seek(max(HASH_HEAD, size - HASH_TAIL))
        h.update(f.read(HASH_TAIL))
    f.seek(pos)
    return h.hexdigest()


def quick_hash_file(path: Path) -> str | None:
    try:
        with path.open("rb") as f:
            return quick_hash(f, path.stat().st_size)
    except OSError:
        return None


def extract_exif(path: Path, kind: str) -> dict:
    """Returns {taken_at, lat, lng, make, model}, all optional."""
    out: dict = {}
    if kind == "photo":
        try:
            with Image.open(path) as im:
                exif = im.getexif()
                # DateTimeOriginal (36867) lives in the Exif sub-IFD, not the
                # top-level directory; fall back to top-level DateTime (306).
                try:
                    sub = exif.get_ifd(34665)
                except Exception:
                    sub = {}
                dt = sub.get(36867) or exif.get(36867) or exif.get(306)
                if dt:
                    try:
                        out["taken_at"] = time.mktime(time.strptime(dt, "%Y:%m:%d %H:%M:%S"))
                    except Exception:
                        pass
                make = _clean_exif_str(exif.get(271))   # Make, e.g. "Apple", "Canon"
                model = _clean_exif_str(exif.get(272))  # Model, e.g. "iPhone 14 Pro"
                if make:
                    out["make"] = make
                if model:
                    out["model"] = model
                gps_ifd = exif.get_ifd(34853) if hasattr(exif, "get_ifd") else None
                if gps_ifd:
                    # tag IDs: 1=LatRef, 2=Lat, 3=LngRef, 4=Lng
                    lat = _dms_to_dd(gps_ifd.get(2), gps_ifd.get(1))
                    lng = _dms_to_dd(gps_ifd.get(4), gps_ifd.get(3))
                    if lat is not None and lng is not None:
                        out["lat"] = lat
                        out["lng"] = lng
        except Exception:
            pass
    elif kind == "video":
        out.update(_video_meta(path))
    if "taken_at" not in out:
        try:
            out["taken_at"] = path.stat().st_mtime
        except OSError:
            pass
    return out


def _fmt_shutter(t: float) -> str:
    if t <= 0:
        return ""
    if t < 1:
        return f"1/{round(1 / t)} s"
    return f"{t:g} s"


def _exposure_info(path: Path) -> dict:
    """ISO, aperture, shutter, focal length, lens, bias and flash from the
    Exif sub-IFD, read on demand for the info panel (a header read, not
    indexed: it is one file open per panel, and works for every existing row
    without a rescan). Values are display strings; missing tags are absent."""
    out: dict = {}
    try:
        with Image.open(path) as im:
            exif = im.getexif()
            try:
                sub = exif.get_ifd(34665)
            except Exception:
                sub = {}
    except Exception:
        return out

    def num(tag):
        v = sub.get(tag)
        if isinstance(v, (tuple, list)):
            v = v[0] if v else None
        try:
            return float(v) if v is not None else None
        except (TypeError, ValueError):
            return None

    iso = num(34855)
    if iso:
        out["iso"] = f"ISO {int(iso)}"
    f = num(33437)
    if f:
        out["aperture"] = f"f/{f:g}"
    t = num(33434)
    if t:
        out["shutter"] = _fmt_shutter(t)
    fl = num(37386)
    if fl:
        fl35 = num(41989)
        out["focal_length"] = f"{fl:g} mm" + (
            f" ({int(fl35)} mm equiv.)" if fl35 and abs(fl35 - fl) >= 1 else "")
    lens = _clean_exif_str(sub.get(42036))
    if lens:
        out["lens"] = lens
    bias = num(37380)
    if bias:
        out["exposure_bias"] = f"{bias:+g} EV"
    flash = num(37385)
    if flash is not None:
        out["flash"] = "Fired" if int(flash) & 1 else "Did not fire"
    return out


def _video_info(path: Path) -> dict:
    """Duration, codec/resolution and frame rate for the info panel (ffprobe)."""
    out: dict = {}
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json",
             "-show_format", "-show_streams", str(path)],
            capture_output=True, text=True, timeout=15)
        j = json.loads(r.stdout or "{}")
    except Exception:
        return out
    try:
        dur = float(j.get("format", {}).get("duration") or 0)
        if dur > 0:
            m, s = divmod(int(round(dur)), 60)
            h, m = divmod(m, 60)
            out["duration"] = f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"
        br = float(j.get("format", {}).get("bit_rate") or 0)
        if br > 0:
            out["bitrate"] = f"{br / 1e6:.1f} Mbit/s"
    except (TypeError, ValueError):
        pass
    for s in j.get("streams", []):
        if s.get("codec_type") == "video":
            codec = s.get("codec_name", "")
            w, h = s.get("width"), s.get("height")
            if codec:
                out["codec"] = codec.upper() + (f" · {w} × {h}" if w and h else "")
            fps = s.get("avg_frame_rate") or s.get("r_frame_rate") or ""
            try:
                n, d = fps.split("/")
                if float(d) > 0:
                    out["frame_rate"] = f"{float(n) / float(d):.4g} fps"
            except (ValueError, AttributeError):
                pass
            break
    return out


def _video_meta(path: Path) -> dict:
    """Date / camera / GPS from a video container (ffprobe)."""
    out: dict = {}
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json",
             "-show_format", "-show_streams", str(path)],
            capture_output=True, text=True, timeout=15)
        j = json.loads(r.stdout or "{}")
    except Exception:
        return out
    tags = dict(j.get("format", {}).get("tags", {}))
    for s in j.get("streams", []):
        tags.update(s.get("tags") or {})
    make = _clean_exif_str(tags.get("com.apple.quicktime.make") or tags.get("make"))
    model = _clean_exif_str(tags.get("com.apple.quicktime.model") or tags.get("model"))
    if make:
        out["make"] = make
    if model:
        out["model"] = model
    dt = tags.get("com.apple.quicktime.creationdate") or tags.get("creation_time")
    if dt:
        m = re.match(r"(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})", str(dt))
        if m and 1980 <= int(m.group(1)) <= 2035:
            out["taken_at"] = calendar.timegm(
                tuple(int(x) for x in m.groups()) + (0, 0, 0))
    loc = tags.get("com.apple.quicktime.location.ISO6709") or tags.get("location")
    if loc:
        m = re.match(r"([+-]\d+\.?\d*)([+-]\d+\.?\d*)", str(loc))
        if m:
            out["lat"], out["lng"] = float(m.group(1)), float(m.group(2))
    return out


def extract_taken_at(path: Path, kind: str) -> float | None:
    """Back-compat shim for callers that just want the timestamp."""
    return extract_exif(path, kind).get("taken_at")


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
               (id, path, name, kind, ext, mime, size, mtime, taken_at, width, height, album, lat, lng,
                camera_make, camera_model, content_hash, added_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                       COALESCE((SELECT added_at FROM media WHERE id = ?), ?))""",
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
            exif = extract_exif(full, kind)
            taken = exif.get("taken_at")
            lat = exif.get("lat")
            lng = exif.get("lng")
            cam_make = exif.get("make")
            cam_model = exif.get("model")
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
                    lat,
                    lng,
                    cam_make,
                    cam_model,
                    quick_hash_file(full),
                    mid,
                    time.time(),
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
        cur.execute("DELETE FROM album_media WHERE media_id NOT IN (SELECT id FROM media)")
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


def auto_purge_trash(age_days: int = 60) -> None:
    """Permanently delete media items trashed more than `age_days` ago.
    Removes the file on disk, the index row, the thumbnail, and any cluster
    membership rows. Disabled entirely in read-only/safe mode — the trash
    must never empty itself there."""
    if READ_ONLY:
        return
    cutoff = time.time() - age_days * 86400
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT id, path FROM media WHERE trashed_at IS NOT NULL AND trashed_at < ?",
        (cutoff,),
    ).fetchall()
    if not rows:
        conn.close()
        return
    print(f"[purge] {len(rows)} items past {age_days}-day retention")
    for r in rows:
        try:
            p = Path(r["path"])
            if p.exists():
                p.unlink()
        except Exception as e:
            print(f"[purge] file delete failed {r['path']}: {e}")
        invalidate_thumbs(r["id"])
        conn.execute("DELETE FROM album_media WHERE media_id = ?", (r["id"],))
        conn.execute("DELETE FROM media WHERE id = ?", (r["id"],))
    conn.commit()
    conn.close()


def start_purge_loop(interval_hours: int = 24) -> None:
    def loop():
        while True:
            try:
                auto_purge_trash()
            except Exception as e:
                print(f"[purge] loop error: {e}")
            time.sleep(interval_hours * 3600)

    t = threading.Thread(target=loop, daemon=True, name="purge")
    t.start()


# ---------- Thumbnails ----------


def thumb_path_for(mid: str, size: int = THUMB_SIZE) -> Path:
    # The default size keeps its historical name so existing caches stay valid.
    return THUMB_DIR / (f"{mid}.jpg" if size == THUMB_SIZE else f"{mid}_{size}.jpg")


def preview_path_for(mid: str) -> Path:
    return PREVIEW_DIR / f"{mid}.jpg"


def snap_thumb_size(w) -> int:
    """The smallest allowed size that is at least w (largest if w is bigger)."""
    try:
        w = int(w)
    except (TypeError, ValueError):
        return THUMB_SIZE
    for s in THUMB_SIZES:
        if s >= w:
            return s
    return THUMB_SIZES[-1]


def invalidate_thumbs(mid: str) -> None:
    """Drop every cached rendition of an item (all sizes and the preview);
    called after the file changed or was removed."""
    for p in list(THUMB_DIR.glob(f"{mid}.jpg")) + list(THUMB_DIR.glob(f"{mid}_*.jpg")) \
            + [preview_path_for(mid)]:
        try:
            p.unlink()
        except FileNotFoundError:
            pass
        except Exception as e:
            print(f"[thumb] could not remove {p}: {e}")


def make_photo_thumb(src: Path, dst: Path, size: int = THUMB_SIZE, quality: int = 82) -> bool:
    try:
        with Image.open(src) as im:
            im = ImageOps.exif_transpose(im)
            im.thumbnail((size, size), Image.LANCZOS)
            if im.mode not in ("RGB", "L"):
                im = im.convert("RGB")
            im.save(dst, "JPEG", quality=quality, optimize=True)
        return True
    except Exception as e:
        print(f"[thumb] photo failed {src}: {e}")
        return False


VIDEO_BACKEND = gpu.detect_backend()


def _ffmpeg_thumb_cmd(src: Path, dst: Path, seek: str, backend: str,
                      size: int = THUMB_SIZE) -> list[str]:
    """Build ffmpeg cmd to grab one frame as a JPEG, hardware-decoding when the
    backend supports it (the frame is downloaded to CPU before JPEG encode)."""
    return [
        "ffmpeg", "-loglevel", "error",
        *gpu.thumb_decode_args(backend),
        "-ss", seek, "-i", str(src),
        "-frames:v", "1",
        "-vf", gpu.thumb_scale_vf(backend, size),
        "-y", str(dst),
    ]


def _thumb_brightness(p: Path) -> float:
    try:
        with Image.open(p) as im:
            im = im.convert("L")
            im.thumbnail((64, 64))
            hist = im.histogram()
            total = sum(hist) or 1
            return sum(i * n for i, n in enumerate(hist)) / total
    except Exception:
        return 255.0


def make_video_thumb(src: Path, dst: Path, size: int = THUMB_SIZE) -> bool:
    """Extract a frame, seeking deeper if the early frames are black (common
    at the start of clips). Tries the GPU backend, falls back to CPU."""
    backends = [VIDEO_BACKEND] + ([gpu.CPU] if VIDEO_BACKEND != gpu.CPU else [])
    got = False
    for seek in ("1", "5", "15", "0"):
        for backend in backends:
            try:
                cmd = _ffmpeg_thumb_cmd(src, dst, seek, backend, size)
                r = subprocess.run(cmd, capture_output=True, timeout=12)
                if r.returncode == 0 and dst.exists() and dst.stat().st_size > 0:
                    got = True
                    break
            except Exception as e:
                print(f"[thumb] video {src} ({backend}): {e}")
        if got and _thumb_brightness(dst) > 16:
            return True  # bright enough — keep it
    return got


_placeholder_bytes: bytes | None = None


def _placeholder_thumb() -> bytes:
    """A small gray JPEG shown when a thumbnail can't be generated. Built once."""
    global _placeholder_bytes
    if _placeholder_bytes is None:
        im = Image.new("RGB", (THUMB_SIZE, THUMB_SIZE), (40, 40, 44))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=70)
        _placeholder_bytes = buf.getvalue()
    return _placeholder_bytes


def _is_placeholder_file(p: Path) -> bool:
    """True when the cached file is the negative-cache placeholder, not a thumb."""
    ph = _placeholder_thumb()
    try:
        return p.stat().st_size == len(ph) and p.read_bytes() == ph
    except OSError:
        return False


def _ensure_base_thumb(row: sqlite3.Row) -> Path | None:
    """The default-size thumbnail (the one the warmer pre-generates)."""
    p = thumb_path_for(row["id"])
    if p.exists() and p.stat().st_size > 0:
        # A cached placeholder means an earlier attempt failed. Keep the
        # negative cache (no retry per view) but report "no thumb" so the
        # caller serves it short-lived: the web client always asks with ?v=,
        # and a real file on this path would be stamped immutable for a year.
        return None if _is_placeholder_file(p) else p
    src = Path(row["path"])
    if not src.exists():
        return None
    if row["kind"] == "photo":
        ok = make_photo_thumb(src, p)
    else:
        ok = make_video_thumb(src, p)
    if not ok:
        # Negative-cache the failure as the placeholder so unreadable files
        # (e.g. partially recovered videos) cost one attempt, not one per view.
        try:
            p.write_bytes(_placeholder_thumb())
        except Exception:
            pass
    return p if ok else None


def ensure_thumb(row: sqlite3.Row, size: int = THUMB_SIZE) -> Path | None:
    """Thumbnail at one of THUMB_SIZES. Sizes below the default are cut from
    the default thumb (cheap, no source decode); 1024 comes from the source."""
    if size == THUMB_SIZE:
        return _ensure_base_thumb(row)
    p = thumb_path_for(row["id"], size)
    if p.exists() and p.stat().st_size > 0:
        return p
    if size < THUMB_SIZE:
        base = _ensure_base_thumb(row)
        if base is None:
            return None
        # The 32px tier is a colour-and-shape hint, not a picture: quality 40
        # keeps it around 1 KB so a page of them costs less than one thumb.
        ok = make_photo_thumb(base, p, size, quality=40 if size <= 32 else 78)
    else:
        src = Path(row["path"])
        if not src.exists():
            return None
        if row["kind"] == "photo":
            ok = make_photo_thumb(src, p, size)
        else:
            ok = make_video_thumb(src, p, size)
    return p if ok else None


def ensure_preview(row: sqlite3.Row) -> Path | None:
    """Viewer-sized JPEG of a photo (max edge PREVIEW_SIZE, EXIF-rotated,
    HEIC/TIFF flattened). Videos use their largest frame thumbnail."""
    if row["kind"] != "photo":
        return ensure_thumb(row, THUMB_SIZES[-1])
    p = preview_path_for(row["id"])
    if p.exists() and p.stat().st_size > 0:
        return p
    src = Path(row["path"])
    if not src.exists():
        return None
    return p if make_photo_thumb(src, p, PREVIEW_SIZE, quality=86) else None


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


@app.before_request
def _readonly_guard():
    """Safe mode: reversible and additive actions pass; nothing that would
    modify or destroy an existing media file is allowed. Trash is fine —
    it's a flag — but the trash can never be emptied."""
    if not READ_ONLY or request.method in ("GET", "HEAD", "OPTIONS"):
        return None
    allowed = (
        request.path in ("/api/rescan", "/api/upload", "/api/upload/check",
                         "/api/media/batch_trash", "/api/media/batch_restore")
        or request.path.endswith(("/favorite", "/trash", "/restore", "/name"))
        # Face/person labels and manual albums describe the library rather
        # than the files in it: neither writes a byte to any media.
        or request.path.startswith(("/api/persons", "/api/user_albums"))
    )
    if not allowed:
        return jsonify({"ok": False, "error": "read-only library"}), 403


@app.before_request
def _start_timer():
    g.t0 = time.perf_counter()


LOG_REQUESTS = os.environ.get("LOG_REQUESTS", "1").lower() in ("1", "true", "yes")


@app.after_request
def _timing(resp):
    """Server-Timing lets a browser's devtools (and tests/bench_latency.py)
    see how long the app itself took, separate from network time."""
    t0 = g.pop("t0", None)
    if t0 is not None:
        ms = (time.perf_counter() - t0) * 1000
        resp.headers["Server-Timing"] = f"app;dur={ms:.1f}"
        if LOG_REQUESTS:
            print(f"[req] {request.method} {request.full_path.rstrip('?')} "
                  f"{resp.status_code} {ms:.1f}ms", flush=True)
    return resp


@app.teardown_appcontext
def _close_db(_exc):
    conn = g.pop("db", None)
    if conn is not None:
        conn.close()


@app.get("/api/health")
def health():
    cur = db().execute("SELECT COUNT(*) AS n FROM media")
    n = cur.fetchone()["n"]
    # Cheap capability check: semantic search only works once CLIP embeddings exist.
    # The clip_embedding column itself only appears after the CLIP indexer runs.
    try:
        clip_n = db().execute(
            "SELECT COUNT(*) AS n FROM media "
            "WHERE clip_embedding IS NOT NULL AND length(clip_embedding) > 0"
        ).fetchone()["n"]
    except sqlite3.OperationalError:
        clip_n = 0
    return jsonify(
        {
            "ok": True,
            "media_root": str(MEDIA_ROOT),
            "media_root_exists": MEDIA_ROOT.exists(),
            "heic_supported": HEIC_OK,
            "items_indexed": n,
            "clip_indexed": clip_n,
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
    camera = request.args.get("camera")
    q = request.args.get("q")
    favorites_only = request.args.get("favorites") in ("1", "true")
    year = request.args.get("year")
    month = request.args.get("month")

    trashed_only = request.args.get("trashed") in ("1", "true")
    archived_only = request.args.get("archived") in ("1", "true")

    sort = request.args.get("sort", "taken")

    sql_select = (
        "SELECT m.id,m.name,m.kind,m.ext,m.mime,m.size,m.taken_at,"
        "m.width,m.height,m.album,m.trashed_at,m.archived,m.edit_version,m.added_at, "
        "CASE WHEN f.media_id IS NULL THEN 0 ELSE 1 END AS favorite "
        "FROM media m LEFT JOIN favorites f ON f.media_id = m.id"
    )
    where: list[str] = []
    args: list = []
    if trashed_only:
        where.append("m.trashed_at IS NOT NULL")
    elif archived_only:
        where.append("m.archived = 1 AND m.trashed_at IS NULL")
    else:
        where.append("m.trashed_at IS NULL AND m.archived = 0")
    if kind in ("photo", "video"):
        where.append("m.kind = ?")
        args.append(kind)
    if request.args.get("dated") in ("1", "true"):
        where.append("m.taken_at IS NOT NULL")
    if request.args.get("undated") in ("1", "true"):
        where.append("m.taken_at IS NULL")
    if album:
        where.append("m.album = ?")
        args.append(album)
    elif not (trashed_only or camera or favorites_only or year):
        # Phone-backup uploads stay out of the plain browse feeds; explicit
        # camera/favorites/album/year filters see them (search does not).
        where.append("m.album != 'uploads'")
    if camera:
        # Match by model when set, else by make (the friendly label can be either).
        where.append("COALESCE(m.camera_model, m.camera_make) = ?")
        args.append(camera)
    if favorites_only:
        where.append("f.media_id IS NOT NULL")
    if q:
        where.append("LOWER(m.name) LIKE ?")
        args.append(f"%{q.lower()}%")
        # Search is scoped to the curated library: skip phone-backup uploads
        # and unknown-date items. An explicit album (the Uploads view itself)
        # is the exception, otherwise searching there could never match.
        if not album:
            where.append("m.album != 'uploads'")
        where.append("m.taken_at IS NOT NULL")
    if year:
        try:
            y = int(year)
            start = time.mktime(time.struct_time((y, 1, 1, 0, 0, 0, 0, 1, -1)))
            end = time.mktime(time.struct_time((y + 1, 1, 1, 0, 0, 0, 0, 1, -1)))
            where.append("COALESCE(m.taken_at, m.mtime) >= ?")
            where.append("COALESCE(m.taken_at, m.mtime) < ?")
            args.extend([start, end])
        except ValueError:
            pass
    if month and year:
        try:
            y, mo = int(year), int(month)
            start = time.mktime(time.struct_time((y, mo, 1, 0, 0, 0, 0, 1, -1)))
            ny, nm = (y + 1, 1) if mo == 12 else (y, mo + 1)
            end = time.mktime(time.struct_time((ny, nm, 1, 0, 0, 0, 0, 1, -1)))
            where.append("COALESCE(m.taken_at, m.mtime) >= ?")
            where.append("COALESCE(m.taken_at, m.mtime) < ?")
            args.extend([start, end])
        except ValueError:
            pass

    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    # Pick the ORDER BY that matches an index prefix (see init_db). A dated
    # feed is ordered by taken_at alone (identical to the COALESCE when
    # taken_at is never NULL), an undated feed by mtime alone.
    if sort == "added":
        # added_at is backfilled from mtime at every startup (init_db), so the
        # column alone is enough and idx_media_added serves the sort; a
        # COALESCE here would force a temp B-tree over the whole album.
        order = "m.added_at DESC"
    elif request.args.get("dated") in ("1", "true"):
        order = "m.taken_at DESC"
    elif request.args.get("undated") in ("1", "true"):
        order = "m.mtime DESC"
    else:
        order = "COALESCE(m.taken_at, m.mtime) DESC"
    sql = sql_select + where_sql + f" ORDER BY {order} LIMIT ? OFFSET ?"
    args_full = args + [per_page, (page - 1) * per_page]
    rows = [dict(r) for r in db().execute(sql, args_full).fetchall()]
    # The favorites join only matters when filtering on it; skipping it lets
    # the count run as a covering-index walk.
    count_from = (
        "FROM media m LEFT JOIN favorites f ON f.media_id = m.id"
        if favorites_only else "FROM media m"
    )
    total = db().execute(
        "SELECT COUNT(*) AS n " + count_from + where_sql, args,
    ).fetchone()["n"]
    return jsonify(
        {"page": page, "per_page": per_page, "total": total, "items": rows}
    )


MEMORY_LABELS = [
    "beach trip", "family gathering", "birthday party", "wedding",
    "hiking outdoors", "food and dining", "city travel", "sunset",
    "concert or event", "kids playing", "pets", "celebration",
    "graduation", "holiday lights", "snow day", "everyday moments",
]


def _memory_title_for(item_ids: list[str]) -> str | None:
    """Zero-shot label a group of memory items using their CLIP embeddings."""
    if not item_ids:
        return None
    if _clip_state["embs"] is None:
        if not _load_clip_index():
            return None
    try:
        import numpy as np
        import torch
    except Exception:
        return None
    id_to_idx = {mid: i for i, mid in enumerate(_clip_state["ids"])}
    idxs = [id_to_idx[i] for i in item_ids if i in id_to_idx]
    if not idxs:
        return None
    img_mean = _clip_state["embs"][idxs].mean(axis=0)
    img_mean = img_mean / (np.linalg.norm(img_mean) + 1e-8)
    with torch.no_grad():
        tok = _clip_state["tok"]([f"a photo of {x}" for x in MEMORY_LABELS]).to(_clip_state["device"])
        tf = _clip_state["model"].encode_text(tok)
        tf = tf / tf.norm(dim=-1, keepdim=True)
        tf_np = tf.cpu().to(torch.float32).numpy()
    sims = tf_np @ img_mean
    best = int(sims.argmax())
    return MEMORY_LABELS[best].title()


@app.get("/api/memories")
def memories():
    """Media from the same month+day as today, across all past years.
    Google-Photos-style 'On this day' / 'Memories'. Group titles are auto-
    generated via CLIP zero-shot classification when embeddings are available."""
    days_window = max(0, int(request.args.get("days", 0)))
    now = time.localtime()
    target_md = (now.tm_mon, now.tm_mday)
    rows = db().execute(
        """SELECT id, name, kind, ext, mime, taken_at, album, edit_version,
                  strftime('%Y', datetime(COALESCE(taken_at, mtime), 'unixepoch')) AS year,
                  strftime('%m-%d', datetime(COALESCE(taken_at, mtime), 'unixepoch')) AS md
           FROM media
           WHERE strftime('%m-%d', datetime(COALESCE(taken_at, mtime), 'unixepoch')) = ?
             AND trashed_at IS NULL
           ORDER BY taken_at DESC""",
        (f"{target_md[0]:02d}-{target_md[1]:02d}",),
    ).fetchall()
    result = [dict(r) for r in rows]
    by_year: dict[str, list] = {}
    for r in result:
        by_year.setdefault(r["year"], []).append(r)
    grouped = []
    for y, its in sorted(by_year.items(), reverse=True):
        title = _memory_title_for([i["id"] for i in its]) or "Memories"
        grouped.append({"year": y, "title": title, "items": its})
    return jsonify({"month_day": f"{target_md[0]:02d}-{target_md[1]:02d}", "groups": grouped})


_clip_state: dict = {"model": None, "tok": None, "embs": None, "ids": None}
_clip_lock = threading.Lock()


def _load_clip_index():
    """Lazy-load CLIP model + image embedding matrix on first semantic search.
    Single-flight: concurrent first callers (the app fires /memories and
    /search together) wait for one load instead of each loading a model."""
    if _clip_state["embs"] is not None:
        return True
    with _clip_lock:
        if _clip_state["embs"] is not None:
            return True
        return _load_clip_index_locked()


def _load_clip_index_locked():
    try:
        import numpy as np
        import open_clip
        import torch
    except Exception:
        return False
    try:
        cur = db().execute(
            "SELECT id, clip_embedding FROM media "
            "WHERE clip_embedding IS NOT NULL AND length(clip_embedding) > 0 "
            "AND trashed_at IS NULL "
            # search scope: curated library only (no uploads, no unknown-date)
            "AND album != 'uploads' AND taken_at IS NOT NULL"
        )
    except sqlite3.OperationalError:
        # clip_embedding column only exists once clip_indexer.py has run.
        return False
    ids, embs = [], []
    for r in cur.fetchall():
        ids.append(r["id"])
        embs.append(np.frombuffer(r["clip_embedding"], dtype=np.float32))
    if not embs:
        return False
    _clip_state["embs"] = np.stack(embs)
    _clip_state["ids"] = ids
    device = gpu.torch_device(torch)
    model, _, _ = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
    model = model.to(device).eval()
    _clip_state["model"] = model
    _clip_state["tok"] = open_clip.get_tokenizer("ViT-B-32")
    _clip_state["device"] = device
    return True


@app.get("/api/search_semantic")
def search_semantic():
    """Free-text image search backed by CLIP. q=<query>&top_k=80"""
    q = request.args.get("q", "").strip()
    if not q:
        abort(400, "q required")
    top_k = min(200, max(1, int(request.args.get("top_k", 80))))
    if not _load_clip_index():
        abort(503, "clip index not built yet; run clip_indexer.py")
    import numpy as np
    import torch

    with torch.no_grad():
        text = _clip_state["tok"]([q]).to(_clip_state["device"])
        tf = _clip_state["model"].encode_text(text)
        tf = tf / tf.norm(dim=-1, keepdim=True)
        tf_np = tf.cpu().to(torch.float32).numpy()[0]
    sims = _clip_state["embs"] @ tf_np  # (N,)
    top_idx = np.argpartition(-sims, top_k)[:top_k]
    top_idx = top_idx[np.argsort(-sims[top_idx])]
    top_ids = [_clip_state["ids"][i] for i in top_idx]
    # Hydrate full media rows in order
    placeholders = ",".join(["?"] * len(top_ids))
    rows = db().execute(
        f"SELECT id,name,kind,ext,mime,size,taken_at,width,height,album, m.edit_version, "
        f"CASE WHEN f.media_id IS NULL THEN 0 ELSE 1 END AS favorite, "
        f"m.trashed_at, m.archived "
        f"FROM media m LEFT JOIN favorites f ON f.media_id = m.id "
        f"WHERE m.id IN ({placeholders}) "
        f"AND m.album != 'uploads' AND m.taken_at IS NOT NULL",
        top_ids,
    ).fetchall()
    by_id = {r["id"]: dict(r) for r in rows}
    ordered = [by_id[i] for i in top_ids if i in by_id]
    return jsonify({"q": q, "total": len(ordered), "items": ordered})


@app.get("/api/timeline")
def timeline():
    """Return counts grouped by year, then month — for Google-Photos-style scrubber."""
    rows = db().execute(
        """SELECT strftime('%Y', datetime(COALESCE(taken_at, mtime), 'unixepoch')) AS y,
                  strftime('%m', datetime(COALESCE(taken_at, mtime), 'unixepoch')) AS m,
                  COUNT(*) AS n
           FROM media
           GROUP BY y, m
           ORDER BY y DESC, m DESC"""
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.post("/api/media/<mid>/trash")
def trash_media(mid: str):
    db().execute("UPDATE media SET trashed_at = ? WHERE id = ?", (time.time(), mid))
    db().commit()
    return jsonify({"ok": True, "trashed": True})


@app.post("/api/media/<mid>/restore")
def restore_media(mid: str):
    db().execute("UPDATE media SET trashed_at = NULL WHERE id = ?", (mid,))
    db().commit()
    return jsonify({"ok": True, "trashed": False})


def _batch_ids() -> list[str]:
    ids = (request.get_json(silent=True) or {}).get("ids")
    if not isinstance(ids, list):
        abort(400)
    return [str(i) for i in ids]


@app.post("/api/media/batch_trash")
def batch_trash():
    ids = _batch_ids()
    now = time.time()
    cur = db().executemany(
        "UPDATE media SET trashed_at = ? WHERE id = ?", [(now, i) for i in ids]
    )
    db().commit()
    return jsonify({"ok": True, "count": cur.rowcount})


@app.post("/api/media/batch_restore")
def batch_restore():
    ids = _batch_ids()
    cur = db().executemany(
        "UPDATE media SET trashed_at = NULL WHERE id = ?", [(i,) for i in ids]
    )
    db().commit()
    return jsonify({"ok": True, "count": cur.rowcount})


@app.post("/api/media/batch_delete")
def batch_delete():
    """Permanently delete items (file on disk + index row + thumbnail).
    Only items already in the trash are eligible, so a stray call can't
    destroy live library files."""
    ids = _batch_ids()
    deleted = 0
    for mid in ids:
        r = db().execute(
            "SELECT id, path FROM media WHERE id = ? AND trashed_at IS NOT NULL",
            (mid,),
        ).fetchone()
        if not r:
            continue
        try:
            p = Path(r["path"])
            if p.exists():
                p.unlink()
        except Exception as e:
            print(f"[delete] file delete failed {r['path']}: {e}")
            continue
        invalidate_thumbs(r["id"])
        db().execute("DELETE FROM album_media WHERE media_id = ?", (r["id"],))
        db().execute("DELETE FROM media WHERE id = ?", (r["id"],))
        deleted += 1
    db().commit()
    return jsonify({"ok": True, "count": deleted})


@app.post("/api/media/<mid>/archive")
def archive_media(mid: str):
    cur = db().execute("SELECT archived FROM media WHERE id = ?", (mid,)).fetchone()
    if not cur:
        abort(404)
    new = 0 if cur["archived"] else 1
    db().execute("UPDATE media SET archived = ? WHERE id = ?", (new, mid))
    db().commit()
    return jsonify({"ok": True, "archived": bool(new)})


@app.post("/api/upload")
def upload_media():
    """Multipart upload from clients. Saves to MEDIA_ROOT/uploads/YYYY-MM-DD/
    and immediately indexes the new files."""
    upload_dir = MEDIA_ROOT / "uploads" / time.strftime("%Y-%m-%d")
    upload_dir.mkdir(parents=True, exist_ok=True)
    saved = []
    # Collect every uploaded file, including multiple parts that share a field
    # name (MultiDict.values() would yield only the first per key).
    all_files = [f for key in request.files for f in request.files.getlist(key)]
    for f in all_files:
        if not f.filename:
            continue
        # Strip path components from filename
        safe_name = Path(f.filename).name
        # Same name + byte size as something already in the library means the
        # phone is re-sending a file we have (reinstall, cleared app data,
        # second device with the same camera roll). Skip it instead of
        # minting IMG_0001_1.jpg copies.
        size_hint = _stream_size(f.stream)
        try:
            chash = quick_hash(f.stream, size_hint) if size_hint is not None else None
        except Exception:
            chash = None
        dup = _find_duplicate(safe_name, size_hint, chash)
        if dup is not None:
            saved.append({"id": dup, "name": safe_name, "indexed": True,
                          "duplicate": True})
            continue
        dest = upload_dir / safe_name
        # Avoid clobber
        i = 1
        while dest.exists():
            stem, suf = dest.stem, dest.suffix
            dest = upload_dir / f"{stem}_{i}{suf}"
            i += 1
        f.save(dest)
        # Add to index immediately so it shows up without waiting for rescan
        ext = dest.suffix.lower()
        kind = classify(ext)
        if not kind:
            saved.append({"name": safe_name, "indexed": False, "reason": "unsupported_ext"})
            continue
        st = dest.stat()
        mid = make_id(dest)
        mime = mimetypes.guess_type(safe_name)[0]
        rel = dest.relative_to(MEDIA_ROOT)
        album = rel.parts[0] if len(rel.parts) > 1 else "_root"
        exif = extract_exif(dest, kind)
        taken = exif.get("taken_at")
        width = height = None
        if kind == "photo":
            try:
                with Image.open(dest) as im:
                    width, height = im.size
            except Exception:
                pass
        db().execute(
            """INSERT OR REPLACE INTO media
               (id, path, name, kind, ext, mime, size, mtime, taken_at, width, height, album,
                lat, lng, camera_make, camera_model, content_hash, added_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                mid, str(dest), safe_name, kind, ext, mime,
                st.st_size, st.st_mtime, taken, width, height, album,
                exif.get("lat"), exif.get("lng"), exif.get("make"), exif.get("model"),
                chash or quick_hash_file(dest),
                time.time(),
            ),
        )
        db().commit()
        # dest.name is the stored name (may carry a _1 suffix on a name clash).
        saved.append({"id": mid, "name": dest.name, "indexed": True, "kind": kind})
    return jsonify({"ok": True, "count": len(saved), "items": saved})


def _stream_size(stream) -> int | None:
    """Byte length of an uploaded part without consuming it (werkzeug spools
    parts to a seekable temp file / BytesIO)."""
    try:
        pos = stream.tell()
        stream.seek(0, 2)
        size = stream.tell()
        stream.seek(pos)
        return size
    except Exception:
        return None


def _find_duplicate(name: str, size: int | None, content_hash: str | None = None) -> str | None:
    """An existing item that is the same file. By content hash when we have
    one (any name, any folder); by name + size otherwise, which is also the
    fallback for rows the hash backfill has not reached yet. A name + size
    match whose stored hash differs from the incoming one is a different
    file that merely shares a name, and is not a duplicate."""
    if content_hash:
        r = db().execute(
            "SELECT id FROM media WHERE content_hash = ? AND trashed_at IS NULL LIMIT 1",
            (content_hash,)).fetchone()
        if r:
            return r["id"]
    if size is None:
        return None
    r = db().execute(
        "SELECT id, content_hash FROM media WHERE name = ? AND size = ? AND trashed_at IS NULL "
        "LIMIT 1", (name, size),
    ).fetchone()
    if not r:
        return None
    if content_hash and r["content_hash"] and r["content_hash"] != content_hash:
        return None
    return r["id"]


@app.post("/api/upload/check")
def upload_check():
    """Pre-flight for backup clients: which of these files are already in
    the library? Each entry is {name, size, hash?}; with a hash (quick_hash,
    computed on the phone) the match is by content regardless of name, else
    by name + size. Lets a phone skip files it has sent before without
    moving a byte, so a reinstall doesn't re-send the camera roll."""
    body = request.get_json(silent=True) or {}
    files = body.get("files") or []
    if not isinstance(files, list) or len(files) > 500:
        return jsonify({"ok": False, "error": "files must be a list of <=500"}), 400
    out = []
    for f in files:
        try:
            h = f.get("hash")
            h = str(h) if h else None
            dup = _find_duplicate(Path(str(f.get("name", ""))).name, int(f.get("size")), h)
        except (TypeError, ValueError, AttributeError):
            dup = None
        out.append(dup)
    return jsonify({"ok": True, "ids": out, "exists": [d is not None for d in out]})


# ---------- Locations / Map ----------


@app.get("/api/locations")
def list_locations():
    """All media that have GPS, with id+thumb-friendly fields. Used by map view."""
    rows = db().execute(
        """SELECT id, name, kind, lat, lng, taken_at, album
           FROM media
           WHERE lat IS NOT NULL AND lng IS NOT NULL
             AND trashed_at IS NULL"""
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.get("/api/media_near")
def media_near():
    """Photos within `radius_km` of (lat, lng)."""
    lat = float(request.args.get("lat"))
    lng = float(request.args.get("lng"))
    radius_km = float(request.args.get("radius_km", 1.0))
    # Bounding box quick-filter, then Haversine inside Python for accuracy.
    deg = radius_km / 111.0  # ~111 km / degree
    rows = db().execute(
        """SELECT id,name,kind,ext,mime,size,taken_at,width,height,album,lat,lng,edit_version
           FROM media
           WHERE trashed_at IS NULL
             AND lat BETWEEN ? AND ?
             AND lng BETWEEN ? AND ?""",
        (lat - deg, lat + deg, lng - deg, lng + deg),
    ).fetchall()
    import math

    def hav(a, b, c, d):
        r = 6371.0
        a1, a2 = math.radians(a), math.radians(c)
        d1 = math.radians(c - a)
        d2 = math.radians(d - b)
        h = math.sin(d1 / 2) ** 2 + math.cos(a1) * math.cos(a2) * math.sin(d2 / 2) ** 2
        return 2 * r * math.asin(math.sqrt(h))

    out = []
    for r in rows:
        d = hav(lat, lng, r["lat"], r["lng"])
        if d <= radius_km:
            row = dict(r)
            row["distance_km"] = round(d, 3)
            out.append(row)
    out.sort(key=lambda x: x["distance_km"])
    return jsonify({"items": out, "total": len(out)})


# ---------- Share tokens ----------


@app.post("/api/media/<mid>/share")
def create_share(mid: str):
    """Generate (or return existing) public share token for a media item."""
    import secrets

    r = db().execute("SELECT share_token FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    token = r["share_token"] or secrets.token_urlsafe(12)
    if not r["share_token"]:
        db().execute("UPDATE media SET share_token = ? WHERE id = ?", (token, mid))
        db().commit()
    return jsonify({"ok": True, "token": token})


@app.delete("/api/media/<mid>/share")
def revoke_share(mid: str):
    db().execute("UPDATE media SET share_token = NULL WHERE id = ?", (mid,))
    db().commit()
    return jsonify({"ok": True})


@app.get("/s/<token>")
def share_view(token: str):
    """Public viewer for a shared media item — no auth, by token only."""
    r = db().execute(
        "SELECT id, name, kind, ext, mime FROM media WHERE share_token = ?",
        (token,),
    ).fetchone()
    if not r:
        abort(404)
    if r["kind"] == "video":
        body_html = f'<video controls autoplay src="/s/{token}/file"></video>'
    else:
        body_html = f'<img src="/s/{token}/file" />'
    return (
        f"""<!doctype html><meta charset=utf-8>
<title>{r['name']}</title>
<style>body{{margin:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh}}
img,video{{max-width:100vw;max-height:100vh;object-fit:contain}}</style>
{body_html}""",
        200,
        {"Content-Type": "text/html"},
    )


@app.get("/s/<token>/file")
def share_file(token: str):
    r = db().execute(
        "SELECT id, path, kind, ext, mime FROM media WHERE share_token = ?",
        (token,),
    ).fetchone()
    if not r:
        abort(404)
    p = Path(r["path"])
    if not p.exists():
        abort(404)
    if r["ext"] in (".heic", ".heif"):
        with Image.open(p) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=90)
            buf.seek(0)
            return send_file(buf, mimetype="image/jpeg")
    if r["kind"] == "video":
        return serve_with_range(p, r["mime"])
    return send_file(p, mimetype=r["mime"], conditional=True)


def _shared_album(token: str) -> sqlite3.Row:
    r = db().execute("SELECT id, name FROM albums WHERE share_token = ?", (token,)).fetchone()
    if not r:
        abort(404)
    return r


def _shared_album_item(token: str, mid: str) -> sqlite3.Row:
    """The media row only if it belongs to the album behind this token, so a
    link to one album never exposes anything outside it."""
    a = _shared_album(token)
    r = db().execute(
        """SELECT m.* FROM media m JOIN album_media am ON am.media_id = m.id
           WHERE am.album_id = ? AND m.id = ? AND m.trashed_at IS NULL""",
        (a["id"], mid)).fetchone()
    if not r:
        abort(404)
    return r


@app.get("/s/a/<token>")
def share_album_view(token: str):
    """Public album page — no auth, by token only. Thumbs open the original."""
    a = _shared_album(token)
    rows = db().execute(
        """SELECT m.id, m.name, m.kind FROM album_media am JOIN media m ON m.id = am.media_id
           WHERE am.album_id = ? AND m.trashed_at IS NULL
           ORDER BY COALESCE(m.taken_at, m.mtime) DESC""", (a["id"],)).fetchall()
    tiles = "".join(
        f'<a href="/s/a/{token}/file/{r["id"]}" target="_blank" title="{escape(r["name"])}">'
        f'<img loading="lazy" src="/s/a/{token}/thumb/{r["id"]}" alt="{escape(r["name"])}">'
        f'{"<span>▷</span>" if r["kind"] == "video" else ""}</a>'
        for r in rows)
    return (
        f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>{escape(a['name'])}</title>
<style>body{{margin:0;background:#0f0f11;color:#eee;font:15px system-ui,sans-serif}}
h1{{font-size:20px;margin:18px 16px 4px}} p{{margin:0 16px 14px;color:#9a9aa2}}
.g{{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:4px;padding:0 4px 24px}}
.g a{{position:relative;display:block;aspect-ratio:1;background:#1a1a1c;overflow:hidden}}
.g img{{width:100%;height:100%;object-fit:cover;display:block}}
.g span{{position:absolute;right:6px;bottom:4px;font-size:14px;text-shadow:0 0 4px #000}}</style>
<h1>{escape(a['name'])}</h1><p>{len(rows)} items</p><div class=g>{tiles}</div>""",
        200, {"Content-Type": "text/html"})


@app.get("/s/a/<token>/thumb/<mid>")
def share_album_thumb(token: str, mid: str):
    r = _shared_album_item(token, mid)
    p = ensure_thumb(r)
    if not p:
        return send_file(io.BytesIO(_placeholder_thumb()), mimetype="image/jpeg", max_age=THUMB_MAX_AGE_PLAIN)
    return send_file(p, mimetype="image/jpeg", conditional=True, max_age=THUMB_MAX_AGE_PLAIN)


@app.get("/s/a/<token>/file/<mid>")
def share_album_file(token: str, mid: str):
    r = _shared_album_item(token, mid)
    p = Path(r["path"])
    if not p.exists():
        abort(404)
    if r["ext"] in (".heic", ".heif"):
        with Image.open(p) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=90)
            buf.seek(0)
            return send_file(buf, mimetype="image/jpeg")
    if r["kind"] == "video":
        return serve_with_range(p, r["mime"])
    return send_file(p, mimetype=r["mime"], conditional=True)


# ---------- Editor ----------



def _edit_version(mid: str) -> int:
    """Current edit_version of a row, so mutating endpoints can hand the
    client the exact value instead of making it guess with +1."""
    r = db().execute("SELECT edit_version FROM media WHERE id = ?", (mid,)).fetchone()
    return int(r[0] or 0) if r else 0

@app.post("/api/media/<mid>/edit")
def edit_media(mid: str):
    """Apply brightness/contrast/saturation/sharpness/auto + optional crop box.
    Body JSON params:
      brightness: float 0..2 (1=no change)
      contrast:   float 0..2
      saturation: float 0..2
      sharpness:  float 0..2
      auto_enhance: bool
      crop: [x1,y1,x2,y2] (in image pixels) — optional
    """
    from PIL import ImageEnhance, ImageOps as PIO

    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    if r["kind"] != "photo":
        abort(400, "edit only supports photos")
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    body = request.json or {}
    try:
        with Image.open(src) as im:
            exif = im.info.get("exif")
            im = PIO.exif_transpose(im)
            if im.mode != "RGB":
                im = im.convert("RGB")
            crop = body.get("crop")
            if crop and len(crop) == 4:
                x1, y1, x2, y2 = (int(v) for v in crop)
                im = im.crop((x1, y1, x2, y2))
            if body.get("auto_enhance"):
                im = PIO.autocontrast(im, cutoff=1)
                im = ImageEnhance.Sharpness(im).enhance(1.2)
            for key, factor_cls in (
                ("brightness", ImageEnhance.Brightness),
                ("contrast", ImageEnhance.Contrast),
                ("saturation", ImageEnhance.Color),
                ("sharpness", ImageEnhance.Sharpness),
            ):
                v = body.get(key)
                if v is not None and float(v) != 1.0:
                    im = factor_cls(im).enhance(float(v))
            save_kwargs: dict = {}
            if exif and src.suffix.lower() in (".jpg", ".jpeg"):
                save_kwargs["exif"] = exif
            if src.suffix.lower() in (".jpg", ".jpeg"):
                save_kwargs["quality"] = 92
                save_kwargs["subsampling"] = 0
            im.save(src, **save_kwargs)
            new_w, new_h = im.size
        invalidate_thumbs(mid)
        # Bump edit_version so clients can bust their cached image
        db().execute(
            "UPDATE media SET width = ?, height = ?, mtime = ?, edit_version = edit_version + 1 WHERE id = ?",
            (new_w, new_h, src.stat().st_mtime, mid),
        )
        db().commit()
        return jsonify({"ok": True, "width": new_w, "height": new_h,
                        "edit_version": _edit_version(mid)})
    except Exception as e:
        print(f"[edit] failed: {e}")
        abort(500, str(e))


@app.post("/api/media/<mid>/rotate")
def rotate_media(mid: str):
    """Lossless-ish rotation: PIL re-encode with EXIF preserved. Updates the
    original file, invalidates cached thumb, updates width/height in index."""
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    if r["kind"] != "photo":
        abort(400, "rotation only supported for photos")
    deg = int(request.args.get("degrees") or (request.json or {}).get("degrees", 90))
    if deg not in (90, 180, 270, -90):
        abort(400, "degrees must be 90, 180, 270, or -90")
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    try:
        with Image.open(src) as im:
            exif = im.info.get("exif")
            rotated = im.rotate(-deg, expand=True)  # PIL rotates CCW; negate for clockwise
            save_kwargs: dict = {}
            if exif:
                save_kwargs["exif"] = exif
            if src.suffix.lower() in (".jpg", ".jpeg"):
                save_kwargs["quality"] = 95
                save_kwargs["subsampling"] = 0
            rotated.save(src, **save_kwargs)
            new_w, new_h = rotated.size
        invalidate_thumbs(mid)
        # Bump edit_version exactly like edit_media: clients key the immutably
        # cached thumb URL on it, so without the bump every grid would keep
        # showing the pre-rotation tile until the browser cache expired.
        db().execute(
            "UPDATE media SET width = ?, height = ?, mtime = ?, edit_version = edit_version + 1 WHERE id = ?",
            (new_w, new_h, src.stat().st_mtime, mid),
        )
        db().commit()
        return jsonify({"ok": True, "width": new_w, "height": new_h,
                        "edit_version": _edit_version(mid)})
    except Exception as e:
        print(f"[rotate] failed: {e}")
        abort(500, str(e))


@app.post("/api/media/<mid>/favorite")
def toggle_favorite(mid: str):
    r = db().execute("SELECT 1 FROM favorites WHERE media_id = ?", (mid,)).fetchone()
    if r:
        db().execute("DELETE FROM favorites WHERE media_id = ?", (mid,))
        favorited = False
    else:
        db().execute(
            "INSERT INTO favorites (media_id, created_at) VALUES (?, ?)",
            (mid, time.time()),
        )
        favorited = True
    db().commit()
    return jsonify({"ok": True, "favorite": favorited})


@app.get("/api/albums")
def list_albums():
    # Most-recent item per album doubles as the album cover thumbnail.
    # One pass with window functions; a correlated "newest per group"
    # subquery re-scanned the table once per album.
    rows = db().execute(
        """WITH t AS (
             SELECT id, album,
                    ROW_NUMBER() OVER (PARTITION BY album
                                       ORDER BY COALESCE(taken_at, mtime) DESC) AS rn,
                    COUNT(*) OVER (PARTITION BY album) AS count
             FROM media WHERE trashed_at IS NULL)
           SELECT album, count, id AS cover FROM t WHERE rn = 1 ORDER BY album"""
    ).fetchall()
    return jsonify([dict(r) for r in rows])


# ---------- Manual albums ----------
# A named set of media ids, independent of the folder an item lives in
# (folder albums above are derived from MEDIA_ROOT and read-only).

_ALBUM_ITEM_COLS = (
    "m.id, m.name, m.kind, m.ext, m.mime, m.size, m.taken_at, m.width, m.height, "
    "m.album, m.edit_version, am.added_at AS album_added_at, "
    "CASE WHEN f.media_id IS NULL THEN 0 ELSE 1 END AS favorite"
)


def _album_row(aid: int) -> dict | None:
    r = db().execute("SELECT * FROM albums WHERE id = ?", (aid,)).fetchone()
    if not r:
        return None
    d = dict(r)
    d["count"] = db().execute(
        """SELECT COUNT(*) FROM album_media am JOIN media m ON m.id = am.media_id
           WHERE am.album_id = ? AND m.trashed_at IS NULL""", (aid,)).fetchone()[0]
    # The chosen cover, if it is still a live member; else the newest member.
    cover = None
    if d.get("cover_id"):
        cover = db().execute(
            """SELECT m.id FROM album_media am JOIN media m ON m.id = am.media_id
               WHERE am.album_id = ? AND am.media_id = ? AND m.trashed_at IS NULL""",
            (aid, d["cover_id"])).fetchone()
    if cover is None:
        cover = db().execute(
            """SELECT m.id FROM album_media am JOIN media m ON m.id = am.media_id
               WHERE am.album_id = ? AND m.trashed_at IS NULL
               ORDER BY COALESCE(m.taken_at, m.mtime) DESC LIMIT 1""", (aid,)).fetchone()
    d["cover"] = cover["id"] if cover else None
    return d


def _touch_album(aid: int) -> None:
    db().execute("UPDATE albums SET updated_at = ? WHERE id = ?", (time.time(), aid))


@app.get("/api/user_albums")
def list_user_albums():
    """All manual albums, most recently changed first. With ?media_id=<id>
    each album also says whether that item is in it (for an add-to-album
    picker)."""
    mid = request.args.get("media_id")
    out = []
    for r in db().execute("SELECT id FROM albums ORDER BY updated_at DESC").fetchall():
        d = _album_row(r["id"])
        if mid:
            d["contains"] = db().execute(
                "SELECT 1 FROM album_media WHERE album_id = ? AND media_id = ?",
                (r["id"], mid)).fetchone() is not None
        out.append(d)
    return jsonify(out)


def _album_name_from_body(body: dict) -> str:
    name = str(body.get("name") or "").strip()
    if not name:
        abort(400, "name required")
    return name[:120]


@app.post("/api/user_albums")
def create_user_album():
    body = request.get_json(silent=True) or {}
    name = _album_name_from_body(body)
    now = time.time()
    cur = db().execute(
        "INSERT INTO albums (name, created_at, updated_at) VALUES (?, ?, ?)", (name, now, now))
    aid = cur.lastrowid
    ids = [str(i) for i in (body.get("media_ids") or []) if i]
    if ids:
        db().executemany(
            "INSERT OR IGNORE INTO album_media (album_id, media_id, added_at) "
            "SELECT ?, id, ? FROM media WHERE id = ?", [(aid, now, i) for i in ids])
    db().commit()
    return jsonify({"ok": True, "album": _album_row(aid)})


@app.get("/api/user_albums/<int:aid>")
def get_user_album(aid: int):
    d = _album_row(aid)
    if not d:
        abort(404)
    return jsonify(d)


@app.post("/api/user_albums/<int:aid>")
def update_user_album(aid: int):
    """Rename and/or pick a cover (which must be a member)."""
    if not _album_row(aid):
        abort(404)
    body = request.get_json(silent=True) or {}
    if "name" in body:
        db().execute("UPDATE albums SET name = ? WHERE id = ?", (_album_name_from_body(body), aid))
    if "cover_id" in body:
        cid = body.get("cover_id")
        if cid is not None and db().execute(
                "SELECT 1 FROM album_media WHERE album_id = ? AND media_id = ?",
                (aid, cid)).fetchone() is None:
            abort(400, "cover must be an item in the album")
        db().execute("UPDATE albums SET cover_id = ? WHERE id = ?", (cid, aid))
    _touch_album(aid)
    db().commit()
    return jsonify({"ok": True, "album": _album_row(aid)})


@app.delete("/api/user_albums/<int:aid>")
def delete_user_album(aid: int):
    """Deletes the album only; the photos stay where they are."""
    db().execute("DELETE FROM album_media WHERE album_id = ?", (aid,))
    n = db().execute("DELETE FROM albums WHERE id = ?", (aid,)).rowcount
    db().commit()
    if not n:
        abort(404)
    return jsonify({"ok": True})


@app.get("/api/user_albums/<int:aid>/media")
def user_album_media(aid: int):
    if not _album_row(aid):
        abort(404)
    rows = db().execute(
        f"""SELECT {_ALBUM_ITEM_COLS}
            FROM album_media am
            JOIN media m ON m.id = am.media_id
            LEFT JOIN favorites f ON f.media_id = m.id
            WHERE am.album_id = ? AND m.trashed_at IS NULL
            ORDER BY COALESCE(m.taken_at, m.mtime) DESC""", (aid,)).fetchall()
    return jsonify([dict(r) for r in rows])


@app.post("/api/user_albums/<int:aid>/items")
def add_album_items(aid: int):
    if not _album_row(aid):
        abort(404)
    ids = _batch_ids()
    now = time.time()
    before = db().execute("SELECT COUNT(*) FROM album_media WHERE album_id = ?", (aid,)).fetchone()[0]
    db().executemany(
        "INSERT OR IGNORE INTO album_media (album_id, media_id, added_at) "
        "SELECT ?, id, ? FROM media WHERE id = ?", [(aid, now, i) for i in ids])
    after = db().execute("SELECT COUNT(*) FROM album_media WHERE album_id = ?", (aid,)).fetchone()[0]
    _touch_album(aid)
    db().commit()
    return jsonify({"ok": True, "added": after - before, "album": _album_row(aid)})


@app.delete("/api/user_albums/<int:aid>/items")
def remove_album_items(aid: int):
    if not _album_row(aid):
        abort(404)
    ids = _batch_ids()
    cur = db().executemany(
        "DELETE FROM album_media WHERE album_id = ? AND media_id = ?", [(aid, i) for i in ids])
    _touch_album(aid)
    db().commit()
    return jsonify({"ok": True, "removed": cur.rowcount, "album": _album_row(aid)})


@app.post("/api/user_albums/<int:aid>/share")
def share_user_album(aid: int):
    """Mint (or return) the public link token for an album: /s/a/<token>."""
    import secrets

    r = db().execute("SELECT share_token FROM albums WHERE id = ?", (aid,)).fetchone()
    if not r:
        abort(404)
    token = r["share_token"] or secrets.token_urlsafe(12)
    if not r["share_token"]:
        db().execute("UPDATE albums SET share_token = ? WHERE id = ?", (token, aid))
        db().commit()
    return jsonify({"ok": True, "token": token, "url": f"/s/a/{token}"})


@app.delete("/api/user_albums/<int:aid>/share")
def revoke_album_share(aid: int):
    db().execute("UPDATE albums SET share_token = NULL WHERE id = ?", (aid,))
    db().commit()
    return jsonify({"ok": True})


@app.get("/api/cameras")
def list_cameras():
    """Group library by the device that took each photo (EXIF make/model),
    so the UI can categorize by iPhone / Galaxy / Canon / etc."""
    # Single pass (window functions) instead of a per-camera correlated
    # subquery: 1.3 s -> ~70 ms on a 42k-row library.
    rows = db().execute(
        """WITH t AS (
             SELECT id, camera_make, camera_model,
                    ROW_NUMBER() OVER (
                        PARTITION BY COALESCE(camera_model, camera_make)
                        ORDER BY COALESCE(taken_at, mtime) DESC) AS rn,
                    COUNT(*) OVER (
                        PARTITION BY COALESCE(camera_model, camera_make)) AS count
             FROM media
             WHERE (camera_model IS NOT NULL OR camera_make IS NOT NULL)
               AND trashed_at IS NULL)
           SELECT camera_make AS make, camera_model AS model, count, id AS cover
           FROM t WHERE rn = 1 ORDER BY count DESC"""
    ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        # key is what the client passes back as ?camera=… ; label is friendly.
        d["key"] = d["model"] or d["make"]
        d["label"] = _camera_label(d["make"], d["model"])
        out.append(d)
    return jsonify(out)


# Map common EXIF make/model strings to a friendly device family name.
def _camera_label(make: str | None, model: str | None) -> str:
    make_l = (make or "").lower()
    model = model or ""
    model_l = model.lower()
    if "apple" in make_l or model_l.startswith("iphone") or model_l.startswith("ipad"):
        return model or "iPhone"
    if "samsung" in make_l:
        return f"Samsung Galaxy ({model})" if model else "Samsung Galaxy"
    if "google" in make_l or model_l.startswith("pixel"):
        return model or "Google Pixel"
    if make and model:
        # Avoid "Canon Canon EOS…" duplication.
        return model if model_l.startswith(make_l) else f"{make} {model}"
    return model or make or "Unknown device"


# ---------- Passport / ID photo ----------


def passport_crop_box(face_bbox, img_w: int, img_h: int,
                      head_frac: float = 0.58) -> tuple[int, int, int, int]:
    """Square crop around a face so the head is ~head_frac of the crop height
    (US passport spec wants 50-69%). Face centered horizontally; headroom of
    ~55% of head height above the box (hair). Clamped to image bounds."""
    x1, y1, x2, y2 = face_bbox
    head_h = y2 - y1
    side = head_h / head_frac
    cx = (x1 + x2) / 2
    top = y1 - 0.55 * head_h * (1 - head_frac) / 0.42  # headroom above bbox
    left = cx - side / 2
    # clamp, keeping the square inside the image
    side = min(side, img_w, img_h)
    left = max(0, min(left, img_w - side))
    top = max(0, min(top, img_h - side))
    return (int(left), int(top), int(left + side), int(top + side))


def _person_matte(im):
    """Alpha matte of the person via rembg (AI segmentation). Removes the
    background INCLUDING cast shadows, which color-threshold whitening can't.
    Returns a PIL 'L' image, or raises if rembg isn't installed."""
    from rembg import remove
    rgba = remove(im.convert("RGB"))
    return rgba.split()[-1]


@app.get("/api/media/<mid>/passport")
def media_passport(mid: str):
    """2x2-style ID photo: person segmented onto pure white, square crop with
    the head at ~58% height. ?size=600 controls output pixels."""
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r or r["kind"] != "photo":
        abort(404)
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    try:
        face = db().execute(
            "SELECT bbox FROM faces WHERE media_id = ? "
            "ORDER BY (score) DESC LIMIT 1", (mid,)).fetchone()
    except sqlite3.OperationalError:
        face = None
    if not face:
        abort(404, "no face detected in this photo")
    size = min(1200, max(200, int(request.args.get("size", 600))))
    with Image.open(src) as im:
        im = ImageOps.exif_transpose(im).convert("RGB")
        try:
            matte = _person_matte(im)
        except Exception:
            abort(503, "background removal unavailable (pip install rembg)")
        white = Image.new("RGB", im.size, (255, 255, 255))
        white.paste(im, mask=matte)
        bbox = tuple(int(float(v)) for v in face["bbox"].split(","))
        crop = white.crop(passport_crop_box(bbox, im.width, im.height))
        crop = crop.resize((size, size), Image.LANCZOS)
        buf = io.BytesIO()
        crop.save(buf, "JPEG", quality=95)
        buf.seek(0)
        return send_file(buf, mimetype="image/jpeg")


_place_cache: dict = {}


def _place_for(lat: float, lng: float) -> str | None:
    """Offline reverse geocode to 'City, Region, CC' (reverse_geocoder pkg)."""
    key = (round(lat, 3), round(lng, 3))
    if key in _place_cache:
        return _place_cache[key]
    place = None
    try:
        import reverse_geocoder as rg
        hit = rg.search((lat, lng), mode=1)[0]
        place = ", ".join(
            x for x in (hit.get("name"), hit.get("admin1"), hit.get("cc")) if x)
    except Exception:
        pass
    _place_cache[key] = place
    return place


@app.get("/api/media/<mid>")
def media_meta(mid: str):
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    d = dict(r)
    # clip_indexer.py owns media.clip_embedding; the BLOB is not JSON
    # serializable and has no business in the client payload.
    d.pop("clip_embedding", None)
    if d.get("lat") is not None and d.get("lng") is not None:
        d["place"] = _place_for(d["lat"], d["lng"])
    # Exposure settings (photos) / container facts (videos) are read from the
    # file on demand rather than indexed: cheap, and no rescan for old rows.
    p = Path(d.get("path") or "")
    if p.is_file():
        if d.get("kind") == "photo":
            d["exposure"] = _exposure_info(p)
        elif d.get("kind") == "video":
            d["video"] = _video_info(p)
    return jsonify(d)


# Thumbnails only change when the photo is edited/rotated, which bumps
# edit_version; clients put that in ?v= so a versioned URL can be cached
# forever. An unversioned URL still gets a short max-age so a page reload
# is served from the browser/Coil cache instead of 80 round trips.
THUMB_MAX_AGE_VERSIONED = 365 * 24 * 3600
THUMB_MAX_AGE_PLAIN = 600


def _thumb_max_age() -> int:
    return THUMB_MAX_AGE_VERSIONED if request.args.get("v") else THUMB_MAX_AGE_PLAIN


@app.get("/api/media/<mid>/thumb")
def media_thumb(mid: str):
    # Only the columns ensure_thumb needs: SELECT * would drag the CLIP
    # embedding BLOB through for every tile.
    r = db().execute(
        "SELECT id, path, kind FROM media WHERE id = ?", (mid,)
    ).fetchone()
    if not r:
        abort(404)
    return _send_rendition(ensure_thumb(r, snap_thumb_size(request.args.get("w"))))


def _send_rendition(p: Path | None):
    if not p:
        # Unreadable/corrupt source — serve a neutral placeholder so the grid
        # shows a tile instead of a broken image + noisy 500.
        return send_file(
            io.BytesIO(_placeholder_thumb()), mimetype="image/jpeg",
            max_age=THUMB_MAX_AGE_PLAIN,
        )
    resp = send_file(p, mimetype="image/jpeg", conditional=True,
                     max_age=_thumb_max_age())
    if request.args.get("v"):
        resp.headers["Cache-Control"] += ", immutable"
    return resp


@app.get("/api/media/<mid>/preview")
def media_preview(mid: str):
    """Viewer-sized JPEG (max edge 2048), cached like a thumbnail: the same
    ?v=edit_version immutable scheme, invalidated by edit/rotate."""
    r = db().execute(
        "SELECT id, path, kind FROM media WHERE id = ?", (mid,)
    ).fetchone()
    if not r:
        abort(404)
    return _send_rendition(ensure_preview(r))


@app.get("/api/media/<mid>/stream.mp4")
def media_stream_mp4(mid: str):
    """Transcode any video to fragmented H.264 MP4 on the fly, hardware-accelerated
    via the detected GPU backend (NVENC / QSV / VideoToolbox / VAAPI), CPU otherwise.
    Lets Android play formats it doesn't natively support (e.g. some MKV/MOV codecs)."""
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r or r["kind"] != "video":
        abort(404)
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    in_args, out_args = gpu.transcode_args(VIDEO_BACKEND)
    cmd = [
        "ffmpeg", "-loglevel", "error",
        *in_args,
        "-i", str(src),
        *out_args,
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "frag_keyframe+empty_moov+faststart",
        "-f", "mp4", "pipe:1",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def gen():
        streamed = False
        try:
            while True:
                chunk = proc.stdout.read(1024 * 64)
                if not chunk:
                    break
                streamed = True
                yield chunk
        finally:
            try:
                proc.kill()
            except Exception:
                pass
            if not streamed:
                try:
                    err = (proc.stderr.read() or b"").decode(errors="replace")
                except Exception:
                    err = ""
                app.logger.error(
                    "stream transcode produced no output for %s: %s",
                    mid, err.strip()[-500:],
                )

    return Response(gen(), mimetype="video/mp4")


@app.get("/api/media/<mid>/full")
def media_full(mid: str):
    r = db().execute("SELECT * FROM media WHERE id = ?", (mid,)).fetchone()
    if not r:
        abort(404)
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    # Convert non-browser-native formats (HEIC, TIFF, BMP…) to JPEG on the fly
    if r["kind"] == "photo" and request.args.get("as") == "jpeg":
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


# ---------- Face clusters (populated by face_indexer.py) ----------


def cluster_thumb_path(cid: int) -> Path:
    d = CACHE_DIR / "clusters"
    d.mkdir(exist_ok=True)
    return d / f"{cid}.jpg"


@app.get("/api/clusters")
def list_clusters():
    try:
        rows = db().execute(
            """SELECT c.id, c.name, c.count, c.rep_face_id,
                      f.media_id AS rep_media_id
               FROM clusters c
               LEFT JOIN faces f ON f.id = c.rep_face_id
               ORDER BY c.count DESC"""
        ).fetchall()
    except sqlite3.OperationalError:
        # Face indexer hasn't run yet, so faces/clusters tables don't exist.
        return jsonify([])
    return jsonify([dict(r) for r in rows])


@app.post("/api/clusters/<int:cid>/name")
def name_cluster(cid: int):
    name = (request.json or {}).get("name", "").strip()
    db().execute("UPDATE clusters SET name = ? WHERE id = ?", (name or None, cid))
    db().commit()
    return jsonify({"ok": True})


@app.get("/api/clusters/<int:cid>/media")
def cluster_media(cid: int):
    rows = db().execute(
        """SELECT DISTINCT m.id, m.name, m.kind, m.ext, m.taken_at, m.album, m.edit_version
           FROM media m JOIN faces f ON f.media_id = m.id
           WHERE f.cluster_id = ?
           ORDER BY COALESCE(m.taken_at, m.mtime) DESC""",
        (cid,),
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@app.get("/api/clusters/<int:cid>/thumb")
def cluster_thumb(cid: int):
    p = cluster_thumb_path(cid)
    if p.exists() and p.stat().st_size > 0:
        return send_file(p, mimetype="image/jpeg", conditional=True, max_age=3600)
    r = db().execute(
        """SELECT f.bbox, m.path FROM clusters c
           JOIN faces f ON f.id = c.rep_face_id
           JOIN media m ON m.id = f.media_id
           WHERE c.id = ?""",
        (cid,),
    ).fetchone()
    if not r:
        abort(404)
    src = Path(r["path"])
    if not src.exists():
        abort(404)
    try:
        x1, y1, x2, y2 = (int(v) for v in r["bbox"].split(","))
        with Image.open(src) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            w, h = im.size
            dx = int((x2 - x1) * 0.2)
            dy = int((y2 - y1) * 0.2)
            x1 = max(0, x1 - dx)
            y1 = max(0, y1 - dy)
            x2 = min(w, x2 + dx)
            y2 = min(h, y2 + dy)
            crop = im.crop((x1, y1, x2, y2))
            crop.thumbnail((256, 256), Image.LANCZOS)
            crop.save(p, "JPEG", quality=82)
        return send_file(p, mimetype="image/jpeg", conditional=True, max_age=3600)
    except Exception as e:
        print(f"[cluster thumb] failed: {e}")
        abort(500)


@app.get("/api/faces/status")
def faces_status():
    cur = db()
    try:
        n_processed = cur.execute("SELECT COUNT(*) AS n FROM face_state").fetchone()["n"]
    except sqlite3.OperationalError:
        n_processed = 0
    try:
        n_faces = cur.execute("SELECT COUNT(*) AS n FROM faces").fetchone()["n"]
        n_clusters = cur.execute("SELECT COUNT(*) AS n FROM clusters").fetchone()["n"]
    except sqlite3.OperationalError:
        n_faces = n_clusters = 0
    n_total = cur.execute("SELECT COUNT(*) AS n FROM media WHERE kind='photo'").fetchone()["n"]
    return jsonify(
        {
            "processed": n_processed,
            "total_photos": n_total,
            "faces": n_faces,
            "clusters": n_clusters,
        }
    )


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
        """SELECT m.id, m.name, m.kind, m.taken_at, m.album, m.edit_version
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


@app.get("/favicon.ico")
def favicon():
    return ("", 204)


# ---------- Entrypoint ----------


def _lower_priority() -> None:
    """Drop the calling thread's CPU priority (Linux nice is per-thread, and
    threads spawned afterwards inherit it). Background work must lose to
    request handling, never the other way round."""
    try:
        os.nice(10)
    except (OSError, AttributeError):
        pass


def start_thumb_warmer() -> None:
    """Pre-generate missing thumbnails in the background (THUMB_WARM=1) so
    grids don't pay the ffmpeg/decode cost on first view."""
    def loop():
        from concurrent.futures import ThreadPoolExecutor
        while _scan_state.get("running"):
            time.sleep(5)
        _lower_priority()
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        # Photos first: they cost ~100ms each vs seconds per video, so the
        # grid becomes browsable long before the slow tail of videos.
        rows = conn.execute(
            "SELECT * FROM media WHERE trashed_at IS NULL "
            "ORDER BY (kind != 'photo'), taken_at DESC"
        ).fetchall()
        conn.close()
        todo = [r for r in rows
                if not (p := thumb_path_for(r["id"])).exists() or p.stat().st_size == 0]
        made = 0
        with ThreadPoolExecutor(max_workers=4) as pool:
            for _ in pool.map(ensure_thumb, todo):
                made += 1
                if made % 500 == 0:
                    print(f"[thumbwarm] {made}/{len(todo)}")
        print(f"[thumbwarm] done, attempted {made} thumbnails")
    threading.Thread(target=loop, daemon=True, name="thumbwarm").start()


def start_hash_backfill() -> None:
    """Fill media.content_hash for rows indexed before hashing existed, in
    the background after the startup scan: one small read per file, so a
    42k-item library takes minutes, not hours."""
    def loop():
        while _scan_state.get("running"):
            time.sleep(5)
        _lower_priority()
        conn = sqlite3.connect(DB_PATH)
        done = 0
        while True:
            rows = conn.execute(
                "SELECT id, path FROM media WHERE content_hash IS NULL LIMIT 200").fetchall()
            if not rows:
                break
            updates = []
            for mid, path in rows:
                h = quick_hash_file(Path(path))
                # A missing file gets a sentinel so the loop cannot spin on it.
                updates.append((h or "-", mid))
            conn.executemany("UPDATE media SET content_hash = ? WHERE id = ?", updates)
            conn.commit()
            done += len(rows)
            if done % 2000 == 0:
                print(f"[hash] {done} rows hashed")
        conn.close()
        if done:
            print(f"[hash] backfill done, {done} rows")
    threading.Thread(target=loop, daemon=True, name="hashfill").start()


def start_clip_warmer() -> None:
    """Load the CLIP model + embedding matrix in the background after the
    startup scan, so the first /api/memories (which the phone app calls on
    every refresh) doesn't pay the multi-second model load."""
    def loop():
        while _scan_state.get("running"):
            time.sleep(2)
        t0 = time.time()
        with app.app_context():
            ok = _load_clip_index()
        print(f"[clipwarm] {'loaded' if ok else 'unavailable'} in {time.time() - t0:.1f}s")
    threading.Thread(target=loop, daemon=True, name="clipwarm").start()


def _serve(host: str, port: int) -> None:
    """Waitress when available: a fixed thread pool with HTTP keep-alive, so a
    grid of 80 thumbnails rides 6 connections instead of 80 TCP handshakes
    (werkzeug's dev server answers every request with Connection: close).
    CHITRA_SERVER=werkzeug forces the dev server."""
    want = os.environ.get("CHITRA_SERVER", "waitress").lower()
    if want != "werkzeug":
        try:
            from waitress import serve
        except ImportError:
            serve = None
        if serve is not None:
            threads = int(os.environ.get("THREADS", "16"))
            # waitress hands bodies to its single I/O thread in send_bytes
            # chunks (18 KB by default), and a 25 KB thumbnail then costs two
            # select() round trips: 80 tiles took 125 ms vs 65 ms on werkzeug.
            # A 1 MiB chunk sends any thumb in one go (50 ms for the same grid).
            send_bytes = int(os.environ.get("SEND_BYTES", str(1 << 20)))
            print(f"[server] waitress, {threads} threads, keep-alive on")
            serve(
                app, host=host, port=port, threads=threads,
                max_request_body_size=app.config["MAX_CONTENT_LENGTH"],
                channel_timeout=300, ident="chitra", send_bytes=send_bytes,
            )
            return
        print("[server] waitress not installed; falling back to werkzeug")
    app.run(host=host, port=port, threaded=True)


def main() -> None:
    init_db()
    start_background_scan()
    start_purge_loop()
    if os.environ.get("THUMB_WARM", "0").lower() in ("1", "true", "yes"):
        start_thumb_warmer()
    start_hash_backfill()
    if os.environ.get("CLIP_WARM", "1").lower() in ("1", "true", "yes"):
        start_clip_warmer()
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", 8000))
    print(f"[server] listening on http://{host}:{port}")
    print(f"[server] media root: {MEDIA_ROOT}")
    print(f"[server] video backend: {gpu.describe(VIDEO_BACKEND)}")
    _serve(host, port)


if __name__ == "__main__":
    main()
