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
from pathlib import Path

import cv2
import numpy as np
from insightface.app import FaceAnalysis
from PIL import Image, ImageOps
from sklearn.cluster import DBSCAN

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
except Exception:
    pass


APP_DIR = Path(__file__).resolve().parent
DB_PATH = APP_DIR / "cache" / "index.db"

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
            rep_face_id INTEGER NULL
        );

        CREATE TABLE IF NOT EXISTS face_state (
            media_id TEXT PRIMARY KEY,
            processed_at REAL NOT NULL,
            n_faces INTEGER NOT NULL
        );
        """
    )
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

    start = time.time()
    processed = 0
    inserted_faces = 0
    for mid, mpath in todo:
        path = Path(mpath)
        if not path.exists():
            continue
        img = load_image(path)
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


def cluster(conn: sqlite3.Connection, eps: float = 0.45, min_samples: int = 3,
            min_score: float = 0.65, min_face_edge: int = 60) -> None:
    """Cluster face embeddings using DBSCAN over cosine distance.
    Embeddings are already L2-normalized (normed_embedding from insightface),
    so cosine distance = 1 - dot product.

    Filters out: low det_score (likely false positives) and tiny faces
    (bbox edge < min_face_edge px on the downscaled-for-detection frame).

    Rep face per cluster = the face with highest det_score."""
    cur = conn.cursor()
    rows = cur.execute(
        "SELECT id, embedding, score, bbox FROM faces WHERE embedding IS NOT NULL AND score >= ?",
        (min_score,),
    ).fetchall()
    # Filter by face size
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
    print(f"[cluster] running DBSCAN on {len(ids)} embeddings (score>={min_score}, edge>={min_face_edge}, eps={eps})...")
    db = DBSCAN(eps=eps, min_samples=min_samples, metric="cosine", n_jobs=-1).fit(embs)
    labels = db.labels_
    n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
    n_noise = int((labels == -1).sum())
    print(f"[cluster] {n_clusters} clusters, {n_noise} unclustered")

    cur.execute("DELETE FROM clusters")
    cur.execute("UPDATE faces SET cluster_id = NULL")

    for lbl in sorted(set(labels)):
        if lbl == -1:
            continue
        mask = labels == lbl
        member_face_ids = ids[mask]
        member_scores = scores[mask]
        # Pick highest-score face as representative
        rep_idx = int(np.argmax(member_scores))
        rep_face_id = int(member_face_ids[rep_idx])
        cur.execute(
            "INSERT INTO clusters (count, rep_face_id) VALUES (?, ?)",
            (len(member_face_ids), rep_face_id),
        )
        cluster_id = cur.lastrowid
        cur.executemany(
            "UPDATE faces SET cluster_id = ? WHERE id = ?",
            [(cluster_id, int(fid)) for fid in member_face_ids.tolist()],
        )
    conn.commit()


def main() -> None:
    if not DB_PATH.exists():
        print(f"index db not found: {DB_PATH}")
        sys.exit(1)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode=WAL;")
    ensure_schema(conn)

    print("[init] loading face model (insightface buffalo_l)...")
    app = FaceAnalysis(name="buffalo_l", providers=["CPUExecutionProvider"])
    app.prepare(ctx_id=-1, det_size=(640, 640))

    limit = int(os.environ.get("FACE_LIMIT", "0")) or None
    detect(app, conn, batch_limit=limit)
    cluster(conn)


if __name__ == "__main__":
    main()
