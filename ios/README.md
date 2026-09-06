# Chitra for iOS

SwiftUI client for the Chitra media server, feature-matched to the Kotlin
client in `../android`. Same server, same API, same wording — the two apps are
meant to look like one product.

## Build and run

```bash
brew install xcodegen        # once
cd ios
xcodegen generate            # writes Chitra.xcodeproj from project.yml
open Chitra.xcodeproj        # then pick a simulator or your phone and run
```

From the command line:

```bash
xcodebuild -project Chitra.xcodeproj -target Chitra -sdk iphonesimulator \
  -arch arm64 -configuration Debug CODE_SIGNING_ALLOWED=NO build
xcrun simctl install booted build/Debug-iphonesimulator/Chitra.app
xcrun simctl launch booted com.buildapp.photos
```

Point it at your server on first launch: **Settings → Server**. The default is
the same LAN address the Android client ships with
(`http://192.168.68.74:8000`). Plain HTTP to a LAN box is allowed via
`NSAllowsArbitraryLoads`, the counterpart of Android's `usesCleartextTraffic`.

## Tests

```bash
xcodebuild test -project Chitra.xcodeproj -scheme Chitra \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

`ChitraTests/` mirrors the Android unit tests: URL building, the content hash,
the backup planner, upload-progress wording, gallery sectioning and JSON
decoding.

If `xcodebuild` reports *"Unable to find a destination matching the provided
destination specifier"* and lists the iOS platform as not installed, this
machine has no simulator runtime matching the SDK (Xcode 26.6 wants an iOS 26
runtime; only iOS 18.6 is installed). Install one from **Xcode → Settings →
Components**, or run the same assertions without a simulator:

```bash
./Tools/logic-check.sh
```

## App icon

`AppIcon.xcassets` is deliberately outside the compiled sources. `actool`
refuses to compile *any* asset catalog when the newest installed simulator
runtime is older than the SDK, which would make the whole project unbuildable
on this machine. Once a matching runtime is installed, uncomment the three
lines flagged in `project.yml` and the icon ships.

## Layout

| Path | What it holds |
| --- | --- |
| `Chitra/API` | `Models.swift`, `PhotoAPI.swift` (one method per Retrofit endpoint), `Urls.swift` |
| `Chitra/Data` | Settings, PhotoKit access, content hash, upload ledger, uploader, backup service |
| `Chitra/UI` | `RootView` (tabs), `LibraryView`, `MemoriesView`, `AlbumsView`, `SearchView`, `ViewerView`, plus people, map, editor and settings |
| `Tools` | Simulator-free logic check |

## Shape of the app

Four tabs, the way Photos arranges itself — not the Android client's single
screen with everything hung off one top bar:

- **Library** — the whole roll under pinned month headers. Filtering is a
  toolbar menu, multi-select a Select button with a bottom action bar (trash
  and restore go through the server's batch endpoints), and long-pressing a
  tile opens a context menu.
- **Memories** — a full-bleed card per year that has photos from this day.
- **Albums** — manual albums, phone folders and library folders as cover
  grids, then People, Places, Media Types and Utilities as rows.
- **Search** — the system search field with Filename / Smart scopes, Smart
  being the server's CLIP index.

The viewer follows Photos too: a translucent bar with the capture date, actions
on a bottom toolbar, both hidden by a tap, a downward drag to dismiss, pinch
and double-tap to zoom, and metadata in a half-height sheet.

The Android client's Material affordances — floating action button, chip rail,
circular overlay buttons, inline info card — are deliberately absent. Every
feature is still there; it is reached the way an iOS user expects.

### How it maps to the Android client

| Android | iOS |
| --- | --- |
| Retrofit + kotlinx.serialization | `URLSession` + `Codable` |
| Coil `AsyncImage` | `RemoteImage` (NSCache in front of `URLCache`) |
| ExoPlayer | `AVPlayer` / `VideoPlayer` |
| osmdroid | MapKit |
| MediaStore buckets (folders) | PhotoKit albums |
| DataStore preferences | `UserDefaults` behind `SettingsStore` |
| WorkManager periodic + content-URI trigger | `BGProcessingTask` + `PHPhotoLibraryChangeObserver` + a sweep on foreground |
| `UploadLedger` (SQLite) | same, plus a cache of computed content hashes |
| `DebugHooks` launch extras | `DebugHooks` launch environment |
| Top-bar icon row + FAB + chip rail | Tab bar, toolbar menus, `.searchable` |

Two differences are the platform's, not choices:

- **Background scheduling.** Android gets a guaranteed 15-minute sweep and a
  content-URI trigger. iOS runs a `BGProcessingTask` only when it feels like
  it, so the app also sweeps on every foreground and whenever PhotoKit reports
  a library change. Settings says so on screen.
- **Hashing cost.** Android reads only the head and tail of a file to compute
  the server's `quick_hash`. PhotoKit hands out asset bytes as a
  forward-only stream, so the hash costs one full read; results are cached in
  the ledger per (asset, modification date), so it is paid once per photo.

## Debug launch hooks

Debug builds read the launch environment, the counterpart of the Android
client's `am start --es` extras:

```bash
SIMCTL_CHILD_CHITRA_SERVER_URL=http://127.0.0.1:8765 \
SIMCTL_CHILD_CHITRA_ROUTE=settings \
SIMCTL_CHILD_CHITRA_FILTER=uploads \
SIMCTL_CHILD_CHITRA_BACKUP_ENABLE=1 \
SIMCTL_CHILD_CHITRA_BACKUP_WIFI_ONLY=0 \
SIMCTL_CHILD_CHITRA_BACKUP_ALBUMS=Recents,Screenshots \
SIMCTL_CHILD_CHITRA_LEDGER_CLEAR=1 \
  xcrun simctl launch --terminate-running-process booted com.buildapp.photos
```

`CHITRA_ROUTE` picks where to start: a tab (`memories`, `albums`, `search`), a
screen pushed inside one (`settings`, `people`, `map`), or `viewer` to open the
first library item full-screen. A release build ignores all of it.
