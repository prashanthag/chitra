"""Pre-generate all missing thumbnails in parallel so browsing is instant."""
import os, sqlite3, time, sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import app  # reuse the server's thumb logic (no Flask server started)

APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("CACHE_DIR", APP_DIR / "cache")) / "index.db"

# sqlite3.connect() would silently create an empty DB and report 0 items.
if not DB_PATH.exists():
    sys.exit(f"index db not found: {DB_PATH}")

conn = sqlite3.connect(DB_PATH)
conn.row_factory = sqlite3.Row
rows = conn.execute(
    "SELECT id, path, kind FROM media WHERE trashed_at IS NULL"
).fetchall()

todo = []
for r in rows:
    p = app.thumb_path_for(r["id"])
    if not (p.exists() and p.stat().st_size > 0):
        todo.append((r["id"], r["path"], r["kind"]))

total = len(todo)
print(f"{len(rows)} items, {total} missing thumbnails", flush=True)
done = ok = 0
start = time.time()

def work(item):
    mid, path, kind = item
    src = Path(path)
    if not src.exists():
        return False
    dst = app.thumb_path_for(mid)
    try:
        if kind == "photo":
            return app.make_photo_thumb(src, dst)
        return app.make_video_thumb(src, dst)
    except Exception:
        return False

with ThreadPoolExecutor(max_workers=24) as ex:
    for res in ex.map(work, todo):
        done += 1
        if res: ok += 1
        if done % 200 == 0:
            rate = done / (time.time() - start)
            eta = (total - done) / rate / 60 if rate else 0
            print(f"  {done}/{total}  ok={ok}  {rate:.0f}/s  eta={eta:.1f}m", flush=True)

print(f"done: {done} processed, {ok} thumbnails generated, {time.time()-start:.0f}s", flush=True)
