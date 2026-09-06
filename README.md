# Chitra — self-hosted Google Photos

A Plex-style photo + video server on your own machine, with Android and iOS clients.

- **Server** (`server/`): Python/Flask. Scans a media directory, builds a SQLite index, generates JPEG thumbnails on demand (hardware-accelerated video decode/transcode via auto-detected GPU — NVIDIA NVENC, Intel Quick Sync, Apple VideoToolbox, AMD AMF, or VAAPI, with CPU fallback), serves originals with HTTP Range, on-the-fly HEIC → JPEG, and on-the-fly video transcode.
- **Android client** (`android/`): Kotlin + Jetpack Compose. Thumbnail grid, full-screen viewer, ExoPlayer for video, configurable server URL.
- **iOS client** (`ios/`): SwiftUI, feature-matched to the Android app — same grid, viewer, albums, people, map, editor and camera-roll backup. See `ios/README.md`.

## Run the server

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
PHOTO_ROOT=/path/to/photos .venv/bin/python app.py
# Listens on 0.0.0.0:8000
```

## Build the Android APK

```bash
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Install on a phone (USB debugging enabled):

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

In the app, open Settings (gear icon) and set the server URL to `http://<server-lan-ip>:8000`.

## Build the iOS app

```bash
cd ios
brew install xcodegen        # once
xcodegen generate
open Chitra.xcodeproj        # pick a simulator or your phone and run
```

Set the server URL the same way, under Settings → Server.

## API

| Endpoint | Purpose |
| --- | --- |
| `GET  /api/health` | Status + scan progress |
| `POST /api/rescan` | Trigger re-scan |
| `GET  /api/media?page=N&per_page=60&kind=&album=` | Paginated media list |
| `GET  /api/media/{id}` | Item metadata |
| `GET  /api/media/{id}/thumb` | 480px JPEG thumbnail |
| `GET  /api/media/{id}/full?as=jpeg` | Original (HEIC supports `?as=jpeg`) |
| `GET  /api/media/{id}/stream.mp4` | Hardware-transcoded H.264 MP4 (GPU auto-detected) |
| `GET  /api/albums` | Album list (top-level subdirs) |
| `GET  /api/persons` | Person tags |
