"""GPU-accelerated CLIP image embedding indexer for semantic search.

Walks all indexed photos, computes a 512-d ViT-B-32 (openai) image embedding
for each, stores it in a new column. The Flask server then ranks by cosine
similarity to a CLIP text embedding for free-text queries like 'dog at beach'.

Run:  .venv/bin/python clip_indexer.py
"""

from __future__ import annotations

import os
import sqlite3
import sys
import time
from pathlib import Path

import numpy as np
import open_clip
import torch
from PIL import Image, ImageOps

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
except Exception:
    pass


APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("CACHE_DIR", APP_DIR / "cache")) / "index.db"
MODEL_NAME = "ViT-B-32"
PRETRAINED = "openai"
BATCH = 32


def ensure_schema(conn: sqlite3.Connection) -> None:
    rows = conn.execute("PRAGMA table_info(media)").fetchall()
    if not any(r[1] == "clip_embedding" for r in rows):
        conn.execute("ALTER TABLE media ADD COLUMN clip_embedding BLOB")
    conn.commit()


def load_image(path: Path, preprocess) -> torch.Tensor | None:
    try:
        with Image.open(path) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            return preprocess(im)
    except Exception as e:
        print(f"  load failed: {e}", file=sys.stderr)
        return None


def index_all(conn: sqlite3.Connection) -> None:
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[clip] device={device}")
    model, _, preprocess = open_clip.create_model_and_transforms(MODEL_NAME, pretrained=PRETRAINED)
    model = model.to(device).eval()
    # Mixed precision for speed
    autocast = torch.amp.autocast(device_type="cuda") if device == "cuda" else None

    cur = conn.cursor()
    rows = cur.execute(
        "SELECT id, path FROM media WHERE kind='photo' AND clip_embedding IS NULL"
    ).fetchall()
    total = len(rows)
    print(f"[clip] {total} photos to embed")
    start = time.time()
    processed = 0

    batch_tensors: list[torch.Tensor] = []
    batch_ids: list[str] = []

    def flush_batch():
        nonlocal processed, batch_tensors, batch_ids
        if not batch_tensors:
            return
        x = torch.stack(batch_tensors).to(device, non_blocking=True)
        with torch.no_grad():
            if autocast:
                with autocast:
                    feats = model.encode_image(x)
            else:
                feats = model.encode_image(x)
            feats = feats / feats.norm(dim=-1, keepdim=True)
        feats_np = feats.cpu().to(torch.float32).numpy()
        cur.executemany(
            "UPDATE media SET clip_embedding = ? WHERE id = ?",
            [(feats_np[i].tobytes(), batch_ids[i]) for i in range(len(batch_ids))],
        )
        conn.commit()
        processed += len(batch_ids)
        batch_tensors.clear()
        batch_ids.clear()

    for mid, mpath in rows:
        t = load_image(Path(mpath), preprocess)
        if t is None:
            cur.execute("UPDATE media SET clip_embedding = ? WHERE id = ?", (b"", mid))
            continue
        batch_tensors.append(t)
        batch_ids.append(mid)
        if len(batch_tensors) >= BATCH:
            flush_batch()
            elapsed = time.time() - start
            rate = processed / elapsed if elapsed else 0
            eta = (total - processed) / rate if rate else 0
            print(
                f"[clip] {processed}/{total} rate={rate:.1f}/s eta={eta/60:.1f}m",
                flush=True,
            )
    flush_batch()
    print(f"[clip] done in {time.time()-start:.1f}s")


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
    index_all(conn)


if __name__ == "__main__":
    main()
