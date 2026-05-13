"""One-shot: read EXIF GPS for already-indexed photos and fill media.lat/lng.

Run:  .venv/bin/python gps_backfill.py
"""

import sqlite3
import sys
import time
from pathlib import Path

from app import DB_PATH, extract_exif


def main() -> None:
    if not DB_PATH.exists():
        print("no db"); sys.exit(1)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT id, path, kind FROM media WHERE kind='photo' AND lat IS NULL"
    ).fetchall()
    total = len(rows)
    print(f"[gps] checking {total} photos for GPS exif")
    found = 0
    start = time.time()
    for i, r in enumerate(rows, 1):
        p = Path(r["path"])
        if not p.exists():
            continue
        exif = extract_exif(p, "photo")
        lat = exif.get("lat")
        lng = exif.get("lng")
        if lat is not None and lng is not None:
            conn.execute(
                "UPDATE media SET lat = ?, lng = ? WHERE id = ?",
                (lat, lng, r["id"]),
            )
            found += 1
        if i % 200 == 0:
            conn.commit()
            print(f"[gps] {i}/{total}  found={found}", flush=True)
    conn.commit()
    elapsed = time.time() - start
    print(f"[gps] done in {elapsed:.1f}s — found={found}/{total}")


if __name__ == "__main__":
    main()
