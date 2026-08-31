# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Two-component monorepo for a self-hosted Google Photos clone:

- `server/` — Python/Flask media server. Single big module `app.py` plus three CLI scripts (`clip_indexer.py`, `face_indexer.py`, `recluster.py`, `gps_backfill.py`) that share its SQLite index.
- `android/` — Kotlin + Jetpack Compose client, package `com.buildapp.photos`.

Note: `server/bin/`, `server/lib/`, `server/lib64`, `server/include/`, `server/pyvenv.cfg` are gitignored stray venv artifacts. The real venv lives in `server/.venv/`.

## Common commands

### Server

```bash
cd server
.venv/bin/pip install -r requirements.txt              # Flask, Pillow, pillow-heif only — ML deps installed ad-hoc
PHOTO_ROOT=/path/to/photos .venv/bin/python app.py     # foreground, binds 0.0.0.0:8000 (override with HOST/PORT)
```

Optional CLI workers (each reads `server/cache/index.db`; run after the server has scanned at least once):

```bash
.venv/bin/python clip_indexer.py                       # GPU CLIP image embeddings → enables /api/search_semantic
.venv/bin/python face_indexer.py                       # InsightFace detect + DBSCAN cluster → /api/clusters
FACE_LIMIT=500 .venv/bin/python face_indexer.py        # cap detection batch
.venv/bin/python recluster.py                          # re-cluster existing face embeddings after tweaking thresholds (no re-detection)
.venv/bin/python gps_backfill.py                       # backfill lat/lng for already-indexed photos
```

CUDA is used opportunistically: `ffmpeg -hwaccel cuda` for video thumbs/transcode, `torch.cuda` for CLIP. Set `USE_CUDA=0` to force CPU ffmpeg.

### Android

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

No automated test suites are wired up.

## Architecture

### Data model — everything lives in one SQLite DB

`server/cache/index.db` is the single source of truth. `app.py:init_db` creates the base schema; new columns are added via the `_add_column_if_missing` migration helper rather than versioned migration files — when adding a column, follow that pattern. The CLI workers (`face_indexer.py`, `clip_indexer.py`) own their own tables (`faces`, `clusters`, `face_state`, plus the `media.clip_embedding` BLOB column) and call their own `ensure_schema` idempotently. The Flask server reads these tables but never writes to them.

Key tables: `media` (one row per file, identified by 16-hex sha1 of absolute path), `favorites`, `persons`/`person_media` (manual tagging), `faces`/`clusters`/`face_state` (ML face pipeline), `scan_state` (last-scan timestamp).

Soft-delete: `media.trashed_at` (epoch) and `media.archived` (int). The `auto_purge_trash` background loop in `app.py` permanently deletes trashed items > 60 days old (file + index row + thumb + face memberships).

### Scanning

`scan_once()` walks `MEDIA_ROOT` (env `PHOTO_ROOT`, defaults to a hardcoded path — always pass `PHOTO_ROOT`), classifies by extension (`PHOTO_EXTS`/`VIDEO_EXTS`), skips unchanged files by `(path, mtime)`, and uses top-level subdir name as `album` (root files get `_root`). A background thread runs one scan at startup; `POST /api/rescan` triggers another. `_scan_state` is in-memory only.

### Thumbnails and streaming

- Photos: PIL with `ImageOps.exif_transpose`, max edge 480, cached under `cache/thumbs/<id>.jpg`.
- Videos: ffmpeg one-frame extraction, CUDA decode → `hwdownload` → JPEG (JPEG isn't an NVENC codec, so the download is required). Falls back to CPU on failure.
- Cluster thumbnails: face bbox crop + 20% padding, cached under `cache/clusters/<id>.jpg`.
- Video playback: `/api/media/{id}/stream.mp4` pipes a live `h264_nvenc` transcode (`frag_keyframe+empty_moov+faststart`) so Android can play any source codec via ExoPlayer. Originals served via `serve_with_range` for HTTP Range.
- HEIC: served as-is unless `?as=jpeg` query is set; the `pillow_heif` opener is registered globally at import (graceful no-op if missing).

### CLIP search and Memories

`_load_clip_index` lazy-loads the model + the full embedding matrix into process memory on first `/api/search_semantic` request — ranking is a single `(N,512)·(512,)` matmul in NumPy. `/api/memories` re-uses the same loaded matrix to zero-shot-label each year's group of "On this day" photos against `MEMORY_LABELS`. If the embedding column is missing, both endpoints degrade gracefully (search returns 503, memories returns "Memories" title).

### Sharing

`POST /api/media/{id}/share` mints a `secrets.token_urlsafe(12)` stored on `media.share_token`. `/s/{token}` and `/s/{token}/file` are public, unauthenticated viewer routes — keep this in mind when changing share-related code.

### Android client

- `api/PhotoApi.kt` — Retrofit interface, base URL from `SettingsRepository` DataStore preference (`server_url`, default hardcoded to `http://192.168.68.74:8000`). All thumbnail/full/stream URLs are built via `Urls` helpers, not Retrofit calls.
- `ui/PhotosApp.kt` — single-activity Compose nav using a sealed `Route` interface (no Nav library). `GalleryViewModel` drives state.
- `data/BackupWorker.kt` — `PeriodicWorkRequest` (15 min, unmetered + battery-not-low). Tracks last-seen `MediaStore._ID` in DataStore and uploads new images/videos in chunks of 10 via `Uploader`.
- `MapScreen.kt` uses osmdroid; cleartext HTTP is allowed (`usesCleartextTraffic="true"`) for LAN access to the server.
- Min SDK 24, target/compile SDK 34, JVM target 17, Kotlin 2.0.20, Compose BOM 2024.09.

### Things to watch when modifying

- `app.py` is ~1400 lines and intentionally module-scoped global (`_clip_state`, `_scan_state`, `_scan_lock`). Don't refactor into classes without a reason.
- When adding `media` columns, write the `ALTER TABLE` via `_add_column_if_missing` in `init_db` — old DBs in the wild won't have a fresh schema.
- After mutating a photo file in place (`edit`, `rotate`), the code deletes the cached thumb and bumps `media.edit_version` so clients can cache-bust. Preserve that pattern for new mutating endpoints.
- `MEDIA_ROOT` defaults to a hardcoded user-specific path; treat the env var as required in any new entrypoints.
