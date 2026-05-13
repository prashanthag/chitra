"""Re-run just the clustering step on existing face embeddings.
Use after tweaking thresholds without re-detecting."""

import sqlite3
import sys
from pathlib import Path

# Reuse functions from face_indexer
sys.path.insert(0, str(Path(__file__).resolve().parent))
from face_indexer import cluster, ensure_schema, DB_PATH


def main() -> None:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    ensure_schema(conn)
    cluster(conn)
    # Wipe cluster thumb cache so they regenerate with new rep_face
    thumb_dir = Path(__file__).resolve().parent / "cache" / "clusters"
    if thumb_dir.exists():
        for p in thumb_dir.glob("*.jpg"):
            p.unlink()
        print(f"[recluster] cleared cluster thumb cache: {thumb_dir}")


if __name__ == "__main__":
    main()
