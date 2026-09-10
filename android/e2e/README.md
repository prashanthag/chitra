# Android end-to-end test

Runs the debug APK on the `photos_test` emulator against a throwaway server
and checks folder-selective backup, de-duplication, the Uploads views and
latency thresholds. See the docstring in `e2e.py` for the step list.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest      # unit tests: BackupPlanner, Urls, UploadProgress
../server/.venv/bin/python e2e/e2e.py           # ~3-5 min; artifacts land in e2e/out/
```

The app accepts launch extras in **debug builds only** (see
`data/DebugHooks.kt`) so the script can set the server URL, enable backup,
choose folders and trigger a run without driving the UI.
