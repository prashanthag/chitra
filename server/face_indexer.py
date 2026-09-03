"""Detect faces in all indexed media and cluster them into 'people'.

Run:  PHOTO_ROOT=/path/to/photos .venv/bin/python face_indexer.py

Schema added/used (created idempotently):
  faces(id INTEGER PK AUTOINC, media_id TEXT, bbox TEXT, embedding BLOB, cluster_id INT NULL)
  clusters(id INTEGER PK AUTOINC, name TEXT NULL, count INT, rep_face_id INT NULL)
  face_state(media_id TEXT PK, processed_at REAL)

This is a CLI tool. The Flask server reads the faces/clusters tables but does
not run detection — keep this script invoked manually or as a cron job to keep
the index fresh.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

try:
    from dotenv import load_dotenv

    load_dotenv(Path(__file__).resolve().parent / ".env")
except ImportError:
    pass

import cv2
import numpy as np
from insightface.app import FaceAnalysis

import gpu
from PIL import Image, ImageOps
from sklearn.cluster import DBSCAN, AgglomerativeClustering

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
except Exception:
    pass


APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("CACHE_DIR", APP_DIR / "cache")) / "index.db"

# Detection scale: insightface expects RGB images; we downscale large ones
# to ~1280px max edge to keep speed reasonable on CPU.
MAX_EDGE = 1280


def ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS faces (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            media_id TEXT NOT NULL,
            bbox TEXT NOT NULL,            -- "x1,y1,x2,y2"
            score REAL,
            embedding BLOB NOT NULL,
            cluster_id INTEGER NULL,
            FOREIGN KEY (media_id) REFERENCES media(id)
        );
        CREATE INDEX IF NOT EXISTS idx_faces_media ON faces(media_id);
        CREATE INDEX IF NOT EXISTS idx_faces_cluster ON faces(cluster_id);

        CREATE TABLE IF NOT EXISTS clusters (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT,
            count INTEGER DEFAULT 0,
            rep_face_id INTEGER NULL,
            centroid BLOB               -- mean embedding, for incremental assign
        );

        -- Embeddings from different recognition models are not comparable,
        -- so remember which model produced the ones on disk.
        CREATE TABLE IF NOT EXISTS face_meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS face_state (
            media_id TEXT PRIMARY KEY,
            processed_at REAL NOT NULL,
            n_faces INTEGER NOT NULL
        );
        """
    )
    cols = {r[1] for r in conn.execute("PRAGMA table_info(clusters)")}
    if "centroid" not in cols:
        conn.execute("ALTER TABLE clusters ADD COLUMN centroid BLOB")
    conn.commit()


def load_image(path: Path) -> np.ndarray | None:
    """Return an HxWx3 BGR numpy array (what insightface wants) or None."""
    try:
        with Image.open(path) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            if max(im.size) > MAX_EDGE:
                im.thumbnail((MAX_EDGE, MAX_EDGE), Image.LANCZOS)
            arr = np.asarray(im)
        return cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
    except Exception as e:
        print(f"  load failed: {e}", file=sys.stderr)
        return None


def _prefetch(todo: list, workers: int, lookahead: int):
    """Yield (media_id, path, image) with decoding overlapped across threads.

    Detection is GPU work but decoding a JPEG is not, and doing them in lockstep
    left the GPU idle most of the time — the card sat at ~29% while one thread
    walked the library. PIL and OpenCV drop the GIL inside their C loops, so a
    small pool of decoders keeps the next frames ready while the current one is
    being detected. Only decoding is parallel: insightface sessions are not
    thread-safe, so inference stays on the calling thread.

    lookahead bounds how many decoded frames are held at once, which caps
    memory at roughly lookahead x 5MB rather than the whole library.
    """
    with ThreadPoolExecutor(max_workers=workers) as pool:
        it = iter(todo)
        pending: deque = deque()

        def submit_next() -> bool:
            for mid, mpath in it:
                path = Path(mpath)
                if not path.exists():
                    continue          # leave unprocessed, same as before
                pending.append((mid, path, pool.submit(load_image, path)))
                return True
            return False

        for _ in range(lookahead):
            if not submit_next():
                break
        while pending:
            mid, path, fut = pending.popleft()
            submit_next()
            try:
                img = fut.result()
            except Exception as e:
                print(f"  load failed {path.name}: {e}", file=sys.stderr)
                img = None
            yield mid, path, img


def detect(app: FaceAnalysis, conn: sqlite3.Connection, batch_limit: int | None = None) -> None:
    cur = conn.cursor()
    cur.execute(
        """SELECT m.id, m.path FROM media m
           LEFT JOIN face_state fs ON fs.media_id = m.id
           WHERE m.kind = 'photo' AND fs.media_id IS NULL"""
    )
    todo = cur.fetchall()
    if batch_limit:
        todo = todo[:batch_limit]
    total = len(todo)
    print(f"[faces] {total} unprocessed photos")

    workers = int(os.environ.get("FACE_WORKERS", "8"))
    start = time.time()
    processed = 0
    inserted_faces = 0
    for mid, path, img in _prefetch(todo, workers, workers * 2):
        n_faces = 0
        if img is not None:
            try:
                results = app.get(img)
                for face in results:
                    bbox = face.bbox.astype(int).tolist()  # [x1,y1,x2,y2]
                    emb = face.normed_embedding.astype(np.float32).tobytes()
                    score = float(face.det_score)
                    cur.execute(
                        "INSERT INTO faces (media_id, bbox, score, embedding) VALUES (?,?,?,?)",
                        (mid, ",".join(map(str, bbox)), score, emb),
                    )
                    n_faces += 1
                    inserted_faces += 1
            except Exception as e:
                print(f"  detect failed {path.name}: {e}", file=sys.stderr)
        cur.execute(
            "INSERT OR REPLACE INTO face_state (media_id, processed_at, n_faces) VALUES (?,?,?)",
            (mid, time.time(), n_faces),
        )
        processed += 1
        if processed % 20 == 0:
            conn.commit()
            elapsed = time.time() - start
            rate = processed / elapsed if elapsed else 0
            eta = (total - processed) / rate if rate else 0
            print(
                f"[faces] {processed}/{total}  faces={inserted_faces}  "
                f"rate={rate:.1f}/s  eta={eta/60:.1f}m",
                flush=True,
            )
    conn.commit()
    print(f"[faces] done. processed={processed} inserted_faces={inserted_faces}")


def _centroids(embs: np.ndarray, labels: np.ndarray, keys: list) -> np.ndarray:
    """L2-normalized mean embedding per label, in the order given by keys."""
    out = np.stack([embs[labels == k].mean(axis=0) for k in keys])
    return out / np.linalg.norm(out, axis=1, keepdims=True)


def cluster(conn: sqlite3.Connection, eps: float = 0.45, min_samples: int = 3,
            min_score: float = 0.65, min_face_edge: int = 60,
            core_eps: float | None = None) -> None:
    """Group faces into people, in two stages so distinct people stay distinct.

    DBSCAN alone links faces transitively: A joins B, B joins C, and two
    different people end up in one group because a chain of in-between faces
    connects them — which is how a 4700-face blob holding two siblings forms.

    Stage 1 runs DBSCAN at a deliberately tight radius (core_eps), which
    yields many small but *pure* cores rather than one merged blob.
    Stage 2 merges those cores with average-linkage agglomerative clustering,
    which compares whole groups instead of individual faces and so cannot
    chain. Faces DBSCAN called noise are then attached to the nearest final
    group if they are close enough to earn it.

    Tunables (env): FACE_CORE_EPS stage-1 radius, FACE_EPS stage-2 distance
    below which two cores are the same person, FACE_ASSIGN_MAX how close a
    leftover face must be to join a group.

    Rep face per cluster = the face with highest det_score.
    """
    if core_eps is None:
        core_eps = float(os.environ.get("FACE_CORE_EPS", "0.25"))
    assign_max = float(os.environ.get("FACE_ASSIGN_MAX", "0.40"))

    cur = conn.cursor()
    rows = cur.execute(
        "SELECT id, embedding, score, bbox FROM faces WHERE embedding IS NOT NULL AND score >= ?",
        (min_score,),
    ).fetchall()
    filtered = []
    for r in rows:
        try:
            x1, y1, x2, y2 = (int(v) for v in r[3].split(","))
            if min(x2 - x1, y2 - y1) >= min_face_edge:
                filtered.append(r)
        except Exception:
            pass
    rows = filtered
    if not rows:
        print("[cluster] no faces above score threshold")
        return

    ids = np.array([r[0] for r in rows], dtype=np.int64)
    scores = np.array([r[2] for r in rows], dtype=np.float32)
    embs = np.stack([np.frombuffer(r[1], dtype=np.float32) for r in rows])
    embs = embs / np.linalg.norm(embs, axis=1, keepdims=True)

    print(f"[cluster] stage 1: DBSCAN on {len(ids)} embeddings "
          f"(score>={min_score}, edge>={min_face_edge}, core_eps={core_eps})...")
    core = DBSCAN(eps=core_eps, min_samples=min_samples,
                  metric="cosine", n_jobs=-1).fit(embs).labels_
    core_keys = sorted(k for k in set(core.tolist()) if k != -1)
    if not core_keys:
        print("[cluster] no dense cores found; try a larger FACE_CORE_EPS")
        return
    print(f"[cluster] stage 1: {len(core_keys)} cores, "
          f"{int((core == -1).sum())} loose faces")

    cents = _centroids(embs, core, core_keys)
    if len(core_keys) == 1:
        merged = np.zeros(1, dtype=np.int64)
    else:
        print(f"[cluster] stage 2: average-linkage merge of {len(cents)} cores "
              f"(eps={eps})...")
        merged = AgglomerativeClustering(
            n_clusters=None, distance_threshold=eps,
            metric="cosine", linkage="average").fit(cents).labels_
    # core label -> final group
    core_to_final = {k: int(merged[i]) for i, k in enumerate(core_keys)}
    final = np.full(len(ids), -1, dtype=np.int64)
    for i, c in enumerate(core.tolist()):
        if c != -1:
            final[i] = core_to_final[c]
    n_groups = len(set(merged.tolist()))
    print(f"[cluster] stage 2: {len(core_keys)} cores -> {n_groups} people")

    # Stage 3: attach leftover faces to the nearest group they clearly belong to.
    group_keys = sorted(set(final[final != -1].tolist()))
    gcents = _centroids(embs, final, group_keys)
    loose = np.flatnonzero(final == -1)
    if len(loose):
        sims = embs[loose] @ gcents.T
        best = sims.argmax(axis=1)
        bestd = 1.0 - sims[np.arange(len(loose)), best]
        take = bestd <= assign_max
        for i, gi, ok in zip(loose, best, take):
            if ok:
                final[i] = group_keys[gi]
        print(f"[cluster] stage 3: attached {int(take.sum())}/{len(loose)} "
              f"loose faces (<= {assign_max})")

    # Names are the one thing here a human typed; reclustering must not eat
    # them. Remember which faces carried each name, then re-attach the name to
    # whichever new cluster inherits most of those faces. A name lands on at
    # most one cluster, so a split person leaves the other half unnamed and
    # free to be named separately rather than silently duplicating a label.
    # Faces per name. Two groups with the same name are the same person (the
    # server merges them on naming), so their members are pooled: that is
    # what lets a merge survive re-clustering below.
    named_faces: dict[str, set[int]] = {}
    for cid, cname in cur.execute(
        "SELECT id, name FROM clusters WHERE name IS NOT NULL AND name != ''"
    ).fetchall():
        members = {r[0] for r in cur.execute(
            "SELECT id FROM faces WHERE cluster_id = ?", (cid,)).fetchall()}
        if members:
            named_faces.setdefault(cname, set()).update(members)

    cur.execute("DELETE FROM clusters")
    cur.execute("UPDATE faces SET cluster_id = NULL")
    best_for_name: dict[str, tuple[int, int]] = {}

    for g in sorted(set(final[final != -1].tolist())):
        mask = final == g
        member_face_ids = ids[mask]
        member_scores = scores[mask]
        rep_idx = int(np.argmax(member_scores))
        rep_face_id = int(member_face_ids[rep_idx])
        cent = embs[mask].mean(axis=0)
        cent = cent / np.linalg.norm(cent)
        cur.execute(
            "INSERT INTO clusters (count, rep_face_id, centroid) VALUES (?, ?, ?)",
            (len(member_face_ids), rep_face_id, cent.astype(np.float32).tobytes()),
        )
        cluster_id = cur.lastrowid
        cur.executemany(
            "UPDATE faces SET cluster_id = ? WHERE id = ?",
            [(cluster_id, int(fid)) for fid in member_face_ids.tolist()],
        )
        if named_faces:
            members = set(member_face_ids.tolist())
            for cname, old_members in named_faces.items():
                overlap = len(members & old_members)
                if overlap and overlap > best_for_name.get(cname, (0, 0))[0]:
                    best_for_name[cname] = (overlap, cluster_id)

    for cname, (_overlap, cluster_id) in best_for_name.items():
        cur.execute("UPDATE clusters SET name = ? WHERE id = ?", (cname, cluster_id))
    # Keep earlier merges: a new group made mostly of faces the user had
    # already filed under a name goes back into that person's group instead
    # of reappearing as a second, unnamed copy.
    remerged = 0
    for cname, (_overlap, keep_id) in best_for_name.items():
        old_members = named_faces[cname]
        for (other_id,) in cur.execute(
                "SELECT id FROM clusters WHERE id != ? AND name IS NULL", (keep_id,)).fetchall():
            members = {r[0] for r in cur.execute(
                "SELECT id FROM faces WHERE cluster_id = ?", (other_id,)).fetchall()}
            if members and len(members & old_members) * 2 > len(members):
                merge_clusters(conn, other_id, keep_id, commit=False)
                remerged += 1
    if named_faces:
        print(f"[cluster] carried over {len(best_for_name)}/{len(named_faces)} names, "
              f"re-merged {remerged} groups")
    conn.commit()


def merge_clusters(conn: sqlite3.Connection, src: int, dst: int, commit: bool = True) -> None:
    """Move every face of src into dst, recompute dst's count, cover face and
    centroid, and drop src. app.py has the same logic for the merge endpoint."""
    cur = conn.cursor()
    cur.execute("UPDATE faces SET cluster_id = ? WHERE cluster_id = ?", (dst, src))
    rows = cur.execute(
        "SELECT id, score, embedding FROM faces WHERE cluster_id = ?", (dst,)).fetchall()
    if rows:
        embs = np.stack([np.frombuffer(r[2], dtype=np.float32) for r in rows])
        cent = embs.mean(axis=0)
        cent = cent / (np.linalg.norm(cent) or 1.0)
        rep = max(rows, key=lambda r: r[1] or 0)[0]
        cur.execute(
            "UPDATE clusters SET count = ?, rep_face_id = ?, centroid = ? WHERE id = ?",
            (len(rows), rep, cent.astype(np.float32).tobytes(), dst))
    cur.execute("DELETE FROM clusters WHERE id = ?", (src,))
    if commit:
        conn.commit()


def assign_new(conn: sqlite3.Connection, min_score: float = 0.65,
               min_face_edge: int = 60) -> int:
    """Attach newly detected faces to existing people without reclustering.

    Keeps the People tab current as photos are added: each unassigned face
    joins the nearest cluster centroid within FACE_ASSIGN_MAX. Faces too far
    from every known person are left unassigned for the next full cluster()
    run to turn into new people, so a stranger never lands in someone's group.
    """
    assign_max = float(os.environ.get("FACE_ASSIGN_MAX", "0.40"))
    cur = conn.cursor()
    cents = cur.execute(
        "SELECT id, centroid FROM clusters WHERE centroid IS NOT NULL").fetchall()
    if not cents:
        return 0
    rows = cur.execute(
        "SELECT id, embedding, bbox FROM faces "
        "WHERE cluster_id IS NULL AND embedding IS NOT NULL AND score >= ?",
        (min_score,),
    ).fetchall()
    keep = []
    for r in rows:
        try:
            x1, y1, x2, y2 = (int(v) for v in r[2].split(","))
            if min(x2 - x1, y2 - y1) >= min_face_edge:
                keep.append(r)
        except Exception:
            pass
    if not keep:
        return 0
    cids = [c[0] for c in cents]
    C = np.stack([np.frombuffer(c[1], dtype=np.float32) for c in cents])
    C = C / np.linalg.norm(C, axis=1, keepdims=True)
    E = np.stack([np.frombuffer(r[1], dtype=np.float32) for r in keep])
    E = E / np.linalg.norm(E, axis=1, keepdims=True)
    sims = E @ C.T
    best = sims.argmax(axis=1)
    bestd = 1.0 - sims[np.arange(len(keep)), best]
    updates = [(cids[int(b)], keep[i][0])
               for i, (b, d) in enumerate(zip(best, bestd)) if d <= assign_max]
    if updates:
        cur.executemany("UPDATE faces SET cluster_id = ? WHERE id = ?", updates)
        cur.execute(
            "UPDATE clusters SET count = "
            "(SELECT COUNT(*) FROM faces WHERE faces.cluster_id = clusters.id)")
        conn.commit()
    print(f"[assign] {len(updates)}/{len(keep)} new faces joined existing people")
    return len(updates)


def _fix_nested_model_dir(model: str) -> None:
    """Undo insightface's double-nested model unzip.

    The antelopev2 archive expands to <root>/antelopev2/antelopev2/*.onnx while
    the loader only looks one level down, so a fresh download fails with a bare
    "assert 'detection' in self.models". Lift the files up once if we find them
    stranded there.
    """
    root = Path.home() / ".insightface" / "models" / model
    nested = root / model
    if not nested.is_dir() or any(root.glob("*.onnx")):
        return
    moved = 0
    for f in nested.glob("*.onnx"):
        f.rename(root / f.name)
        moved += 1
    if moved:
        try:
            nested.rmdir()
        except OSError:
            pass
        print(f"[init] flattened {moved} model files out of {nested}")


def main() -> None:
    # Indexing must never starve the web server: drop CPU priority (threads
    # spawned later inherit it).
    try:
        os.nice(10)
    except (OSError, AttributeError):
        pass
    if not DB_PATH.exists():
        print(f"index db not found: {DB_PATH}")
        sys.exit(1)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode=WAL;")
    ensure_schema(conn)

    # antelopev2 (glint360k R100) separates faces markedly better than the
    # older buffalo_l (webface600k R50); FACE_MODEL falls back to any pack
    # insightface can fetch.
    model = os.environ.get("FACE_MODEL", "antelopev2")
    providers, ctx_id = gpu.onnx_providers()

    prev = conn.execute(
        "SELECT value FROM face_meta WHERE key = 'model'").fetchone()
    # No recorded model but embeddings already exist means they predate this
    # bookkeeping, i.e. they came from the old buffalo_l default. Treating that
    # as "nothing to compare" would leave old vectors in the table and quietly
    # mix two embedding spaces, so name it explicitly.
    if prev is None and conn.execute(
            "SELECT 1 FROM faces LIMIT 1").fetchone() is not None:
        prev = "buffalo_l"
        print("[init] found embeddings with no recorded model; assuming buffalo_l")
    else:
        prev = prev[0] if prev else None
    if prev and prev != model:
        # Old embeddings live in a different vector space; mixing them would
        # scramble every cluster, so start the face table over.
        n = conn.execute("SELECT COUNT(*) FROM faces").fetchone()[0]
        print(f"[init] model changed {prev} -> {model}: dropping {n} embeddings "
              f"from the old model and re-detecting")
        conn.execute("DELETE FROM faces")
        conn.execute("DELETE FROM face_state")
        conn.execute("DELETE FROM clusters")
        conn.commit()
    conn.execute("INSERT OR REPLACE INTO face_meta (key, value) VALUES ('model', ?)",
                 (model,))
    conn.commit()

    print(f"[init] loading face model (insightface {model})...")
    _fix_nested_model_dir(model)
    print(f"[init] accelerator: {gpu.describe_onnx(providers)} "
          f"(providers={providers}, ctx_id={ctx_id})")
    app = FaceAnalysis(name=model, providers=providers)
    app.prepare(ctx_id=ctx_id, det_size=(640, 640))

    limit = int(os.environ.get("FACE_LIMIT", "0")) or None
    detect(app, conn, batch_limit=limit)

    # A full recluster rebuilds every group; incremental only files new faces
    # into the people already known. FACE_INCREMENTAL=1 is the cheap path for
    # a routine run after new photos land.
    if os.environ.get("FACE_INCREMENTAL", "0") in ("1", "true", "yes"):
        assign_new(conn)
    else:
        cluster(conn)


if __name__ == "__main__":
    main()
