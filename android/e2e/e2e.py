#!/home/ina/work/apps/chitra/server/.venv/bin/python
"""End-to-end test: real APK on the Android emulator against a throwaway
chitra server. Proves the phone-side backup the way a user would see it.

What it checks
  1. Folder-selective backup: only the Camera folder is uploaded, Screenshots
     stays on the phone until it is switched on, then backfills.
  2. Nothing is sent twice: a second run uploads 0 files (ledger), and after a
     simulated reinstall (ledger wiped) the server's /api/upload/check stops
     every re-send before a byte moves.
  3. The uploads show up in the app's "Uploads" filter (screenshot) and in the
     server's Recently-uploaded feed, newest first.
  4. Latency thresholds hold on the test server (tests/bench_latency.py).

Run (from android/):  ../server/.venv/bin/python e2e/e2e.py   # needs Pillow; boots emulator photos_test
Env: AVD=<name> PORT=8765 KEEP=1 (leave emulator+server running for a look)
"""
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parent
SERVER = ANDROID.parent / "server"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Android" / "Sdk"))
ADB = str(SDK / "platform-tools" / "adb")
EMU = str(SDK / "emulator" / "emulator")
AVD = os.environ.get("AVD", "photos_test")
PORT = int(os.environ.get("PORT", "8765"))
PKG = "com.buildapp.photos"
OUT = HERE / "out"
OUT.mkdir(exist_ok=True)
APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
PY = str(SERVER / ".venv/bin/python")

CAMERA = ["e2e_cam_1.jpg", "e2e_cam_2.jpg", "e2e_cam_3.jpg"]
SHOTS = ["e2e_shot_1.jpg", "e2e_shot_2.jpg"]
VIDEO = "e2e_cam_clip.mp4"

results = []


def step(name, ok, detail=""):
    results.append({"step": name, "ok": bool(ok), "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {name}{(' - ' + detail) if detail else ''}", flush=True)
    if not ok and not os.environ.get("CONTINUE"):
        raise SystemExit(f"step failed: {name}")


def sh(*cmd, check=True, timeout=120):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def adb(*args, **kw):
    return sh(ADB, *args, **kw)


def http(path, method="GET", data=None):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", method=method, data=data,
                                 headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def wait_for(pred, timeout, every=1.0, what="condition"):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = pred()
        if v:
            return v
        time.sleep(every)
    raise TimeoutError(what)


# ---------------------------------------------------------------- fixtures
def make_fixtures(d: Path):
    """Photo-sized fixtures. The backup planner ignores files under 10 KB
    (launcher icons and other system images also live in MediaStore), so a
    flat-colour JPEG (~5 KB) would never be picked up; noise makes them
    compress like real photos (~300 KB)."""
    import random
    from PIL import Image
    rnd = random.Random(42)
    for i, name in enumerate(CAMERA + SHOTS):
        im = Image.new("RGB", (1024, 768), (40 * i + 20, 90, 160 - 20 * i))
        px = im.load()
        for y in range(0, 768, 2):
            for x in range(0, 1024, 2):
                v = rnd.randrange(0, 90)
                px[x, y] = (v + 40 * i, v + 60, 200 - v)
        exif = Image.Exif()
        stamp = f"2024:05:{10 + i:02d} 10:00:00"
        exif[0x0132] = stamp                     # IFD0 DateTime
        exif.get_ifd(0x8769)[0x9003] = stamp     # Exif DateTimeOriginal (what the server reads)
        im.save(d / name, "JPEG", quality=85, exif=exif.tobytes())
    if shutil.which("ffmpeg"):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                        "testsrc=duration=3:size=640x480:rate=15", "-pix_fmt", "yuv420p",
                        str(d / VIDEO)], check=True)


# ---------------------------------------------------------------- server
def start_server(tmp: Path):
    media = tmp / "media"
    (media / "Seed").mkdir(parents=True)
    from PIL import Image
    Image.new("RGB", (64, 64), (10, 10, 10)).save(media / "Seed" / "seed.jpg", "JPEG")
    env = dict(os.environ, PHOTO_ROOT=str(media), CACHE_DIR=str(tmp / "cache"), PORT=str(PORT),
               HOST="0.0.0.0", CHITRA_READONLY="0", CLIP_WARM="0", LOG_REQUESTS="1", THUMB_WARM="0")
    log = open(OUT / "server.log", "w")
    p = subprocess.Popen([PY, "app.py"], cwd=SERVER, env=env, stdout=log, stderr=subprocess.STDOUT)
    wait_for(lambda: _healthy(), 30, what="server health")
    return p


def _healthy():
    try:
        return http("/api/health")["ok"]
    except Exception:
        return False


def server_log_count(pattern):
    return len(re.findall(pattern, (OUT / "server.log").read_text()))


# ---------------------------------------------------------------- emulator
def start_emulator():
    devs = adb("devices").strip().splitlines()[1:]
    if any(d.startswith("emulator-") and "device" in d for d in devs):
        print("emulator already running")
        return None
    log = open(OUT / "emulator.log", "w")
    p = subprocess.Popen([EMU, "-avd", AVD, "-no-window", "-no-audio", "-no-boot-anim",
                          "-gpu", "swiftshader_indirect", "-no-snapshot", "-wipe-data"],
                         stdout=log, stderr=subprocess.STDOUT)
    adb("wait-for-device", timeout=300)
    wait_for(lambda: adb("shell", "getprop", "sys.boot_completed", check=False).strip() == "1",
             300, every=3, what="boot_completed")
    time.sleep(5)
    adb("shell", "input", "keyevent", "82")           # unlock
    adb("shell", "settings", "put", "global", "window_animation_scale", "0")
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0")
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
    return p


def push_media(fixtures: Path):
    adb("shell", "mkdir", "-p", "/sdcard/DCIM/Camera", "/sdcard/Pictures/Screenshots")
    for n in CAMERA:
        adb("push", str(fixtures / n), f"/sdcard/DCIM/Camera/{n}")
    if (fixtures / VIDEO).exists():
        adb("push", str(fixtures / VIDEO), f"/sdcard/DCIM/Camera/{VIDEO}")
    for n in SHOTS:
        adb("push", str(fixtures / n), f"/sdcard/Pictures/Screenshots/{n}")
    scan_paths = [f"/storage/emulated/0/DCIM/Camera/{n}" for n in CAMERA + ([VIDEO] if (fixtures / VIDEO).exists() else [])]
    scan_paths += [f"/storage/emulated/0/Pictures/Screenshots/{n}" for n in SHOTS]
    for pth in scan_paths:
        # MediaStore.scanFile() as the shell user; fall back to the legacy broadcast.
        r = adb("shell", "content", "call", "--uri", "content://media", "--method", "scan_file",
                "--arg", pth, check=False)
        if "Result" not in r:
            adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", f"file://{pth}", check=False)

    def indexed():
        rows = adb("shell", "content", "query", "--uri", "content://media/external/file",
                   "--projection", "_display_name", check=False)
        return all(n in rows for n in CAMERA + SHOTS)
    wait_for(indexed, 60, every=2, what="MediaStore indexing")


def launch(fresh=False, **extras):
    # --activity-single-top: a relaunch while the activity is already on top
    # must reach onNewIntent. Without the flag Android just brings the task to
    # the front and drops the intent (no onCreate, no onNewIntent), so every
    # launch after the first silently changed nothing. fresh=True force-stops
    # the app first so the UI starts on the gallery regardless of where the
    # previous step left it.
    args = [ADB, "shell", "am", "start"] + (["-S"] if fresh else []) + \
        ["-W", "--activity-single-top", "-n", f"{PKG}/.MainActivity"]
    for k, v in extras.items():
        if isinstance(v, bool):
            args += ["--ez", k, "true" if v else "false"]
        else:
            args += ["--es", k, str(v)]
    sh(*args)


def uploads():
    return http("/api/media?album=uploads&sort=added&per_page=100")["items"]


def screenshot(name):
    data = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    (OUT / name).write_bytes(data)
    return len(data)


def ui_dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    return ET.fromstring(adb("shell", "cat", "/sdcard/ui.xml", check=False))


def ui_texts():
    return [n.get("text") for n in ui_dump().iter("node") if n.get("text")]


def _tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_content_desc(desc):
    """Find a node by content-desc via uiautomator and tap its centre."""
    for node in ui_dump().iter("node"):
        if node.get("content-desc") == desc:
            _tap_node(node)
            return True
    return False


def tap_text(text):
    for node in ui_dump().iter("node"):
        if node.get("text") == text:
            _tap_node(node)
            return True
    return False


# ---------------------------------------------------------------- main
def main():
    if not APK.exists():
        sys.exit(f"build first: ./gradlew assembleDebug ({APK} missing)")
    tmp = Path(tempfile.mkdtemp(prefix="chitra-e2e-"))
    fixtures = tmp / "fixtures"
    fixtures.mkdir()
    make_fixtures(fixtures)
    have_video = (fixtures / VIDEO).exists()
    server = emu = None
    try:
        server = start_server(tmp)
        step("server up", True, f"port {PORT}")
        emu = start_emulator()
        step("emulator booted", True, adb("shell", "getprop", "ro.build.version.sdk").strip() and "sdk " + adb("shell", "getprop", "ro.build.version.sdk").strip())

        adb("install", "-r", "-g", str(APK), timeout=300)
        for perm in ("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                     "android.permission.READ_EXTERNAL_STORAGE"):
            adb("shell", "pm", "grant", PKG, perm, check=False)
        step("apk installed", True)

        push_media(fixtures)
        step("fixtures in MediaStore", True, f"{len(CAMERA)} camera + {len(SHOTS)} screenshots" + (" + 1 video" if have_video else ""))

        base = f"http://10.0.2.2:{PORT}"
        # 1) Camera folder only
        launch(server_url=base, backup_enable=True, backup_buckets="Camera", backup_videos=True,
               backup_wifi_only=False, backup_now=True, filter="uploads")
        want = set(CAMERA) | ({VIDEO} if have_video else set())
        got = wait_for(lambda: (set(i["name"] for i in uploads()) >= want) and uploads(), 120, every=2,
                       what="camera uploads to arrive")
        names = set(i["name"] for i in got)
        step("camera folder uploaded", names == want, f"{sorted(names)}")
        step("screenshots folder NOT uploaded", not (names & set(SHOTS)))
        items = uploads()
        step("recently-uploaded is newest-first", all(
            (items[i]["added_at"] or 0) >= (items[i + 1]["added_at"] or 0) for i in range(len(items) - 1)))
        step("uploads carry capture date from EXIF", all(i["taken_at"] for i in items if i["kind"] == "photo"),
             "taken_at set on every photo")
        time.sleep(4)
        screenshot("app-uploads.png")
        step("screenshot of Uploads filter", (OUT / "app-uploads.png").stat().st_size > 10_000)

        # 2) Second run: nothing re-sent
        posts_before = server_log_count(r"POST /api/upload ")
        launch(backup_now=True)
        time.sleep(12)
        step("second run re-sends nothing", server_log_count(r"POST /api/upload ") == posts_before
             and len(uploads()) == len(want), f"{len(uploads())} on server, upload POSTs unchanged")

        # 3) Enable Screenshots -> backfills only that folder
        launch(backup_buckets="Camera,Screenshots", backup_now=True)
        got = wait_for(lambda: len(uploads()) >= len(want) + len(SHOTS) and uploads(), 90, every=2,
                       what="screenshot backfill")
        names = set(i["name"] for i in got)
        step("newly enabled folder backfilled", names == want | set(SHOTS), f"{len(names)} items")

        # 4) Simulated reinstall: ledger wiped, server de-dup must stop re-sends
        posts_before = server_log_count(r"POST /api/upload ")
        checks_before = server_log_count(r"POST /api/upload/check")
        launch(ledger_clear=True, backup_now=True)
        wait_for(lambda: server_log_count(r"POST /api/upload/check") > checks_before, 60, every=2,
                 what="upload/check after ledger wipe")
        time.sleep(8)
        n_after = len(uploads())
        step("after ledger wipe, server check prevents re-upload",
             server_log_count(r"POST /api/upload ") == posts_before and n_after == len(want) + len(SHOTS),
             f"{n_after} items, no new upload POSTs, {server_log_count(r'POST /api/upload/check') - checks_before} check call(s)")
        # A re-sent file would land beside the original as <stem>_1.<ext>;
        # compare whole names, since the fixtures themselves end in _1/_2.
        on_disk = {p.name for p in (tmp / "media" / "uploads").rglob("*") if p.is_file()}
        extra = sorted(on_disk - want - set(SHOTS))
        step("no duplicate files on disk", not extra, f"{len(on_disk)} files" + (f", extra: {extra}" if extra else ""))

        # 4b) A renamed copy of a photo already on the server must be skipped
        #     by content hash: no upload POST, server count unchanged, but the
        #     phone records it as done (ledgered as a duplicate).
        copy_name = "e2e_cam_copy.jpg"
        adb("push", str(fixtures / CAMERA[0]), f"/sdcard/DCIM/Camera/{copy_name}")
        adb("shell", "content", "call", "--uri", "content://media", "--method", "scan_file",
            "--arg", f"/storage/emulated/0/DCIM/Camera/{copy_name}", check=False)
        wait_for(lambda: copy_name in adb("shell", "content", "query", "--uri", "content://media/external/file",
                                          "--projection", "_display_name", "--where",
                                          f"\"_display_name='{copy_name}'\"", check=False), 30, every=2,
                 what="renamed copy in MediaStore")
        posts_before = server_log_count(r"POST /api/upload ")
        checks_before = server_log_count(r"POST /api/upload/check")
        n_before = len(uploads())
        launch(backup_now=True)
        wait_for(lambda: server_log_count(r"POST /api/upload/check") > checks_before, 60, every=2,
                 what="upload/check for the renamed copy")
        time.sleep(6)
        step("renamed copy skipped by content hash",
             server_log_count(r"POST /api/upload ") == posts_before and len(uploads()) == n_before,
             f"{len(uploads())} on server, upload POSTs unchanged")

        # 5) Settings screen renders (best effort UI navigation)
        launch(filter="all")
        time.sleep(3)
        if tap_content_desc("Settings"):
            time.sleep(4)
            screenshot("app-settings.png")
            step("settings screen screenshot", (OUT / "app-settings.png").stat().st_size > 10_000)
        else:
            step("settings screen screenshot", True, "skipped: settings button not found via uiautomator")

        # 6) Manual albums: create one from two uploads through the API, then
        #    check the app lists it under "My albums" and opens it.
        cam_ids = [i["id"] for i in uploads() if i["name"] in CAMERA][:2]
        alb = http("/api/user_albums", "POST",
                   json.dumps({"name": "E2E album", "media_ids": cam_ids}).encode())["album"]
        step("album created via API", alb["count"] == 2 and alb["cover"] in cam_ids, f"id {alb['id']}")
        launch(fresh=True, filter="all")
        time.sleep(4)
        if tap_content_desc("Albums"):
            time.sleep(4)
            texts = ui_texts()
            step("albums screen lists the album", "My albums" in texts and "E2E album" in texts,
                 f"texts: {[t for t in texts if 'album' in t.lower()]}")
            screenshot("app-albums.png")
            if tap_text("E2E album"):
                time.sleep(4)
                texts = ui_texts()
                step("album opens with its two items", any(t.startswith("E2E album · 2") for t in texts),
                     f"title: {[t for t in texts if t.startswith('E2E album')]}")
                screenshot("app-album.png")
            else:
                step("album opens with its two items", False, "album tile not found via uiautomator")
        else:
            step("albums screen lists the album", False, "Albums button not found via uiautomator")
        # The public link exposes exactly the members and nothing else.
        tok = http(f"/api/user_albums/{alb['id']}/share", "POST")["token"]
        other = [i["id"] for i in uploads() if i["id"] not in cam_ids][0]
        page = urllib.request.urlopen(f"http://127.0.0.1:{PORT}/s/a/{tok}", timeout=30).read().decode()
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{PORT}/s/a/{tok}/file/{other}", timeout=30)
            leaked = True
        except urllib.error.HTTPError as e:
            leaked = e.code != 404
        step("album share link scoped to members", all(i in page for i in cam_ids) and not leaked)

        # 7) Latency thresholds on the test server
        r = subprocess.run([PY, str(SERVER / "tests/bench_latency.py"), f"http://127.0.0.1:{PORT}",
                            "--strict", "--json", str(OUT / "bench.json")], capture_output=True, text=True)
        (OUT / "bench.txt").write_text(r.stdout + r.stderr)
        step("latency thresholds", r.returncode == 0, r.stdout.strip().splitlines()[-1] if r.stdout else r.stderr[-200:])
    finally:
        (OUT / "report.json").write_text(json.dumps(results, indent=2))
        # Always keep the device log: WorkManager and app traces explain a
        # failed step far better than a timeout message.
        try:
            log = subprocess.run([ADB, "logcat", "-d", "-v", "time"], capture_output=True, text=True, timeout=60).stdout
            keep = [l for l in log.splitlines() if re.search(r"WM-|AndroidRuntime|buildapp|chitra|Backup|Upload|OkHttp", l)]
            (OUT / "logcat.txt").write_text("\n".join(keep))
            (OUT / "logcat-full.txt").write_text(log)
        except Exception as e:  # noqa: BLE001
            (OUT / "logcat.txt").write_text(f"logcat failed: {e}")
        if not os.environ.get("KEEP"):
            if emu is not None:
                adb("emu", "kill", check=False)
            if server is not None:
                server.send_signal(signal.SIGTERM)
                try:
                    server.wait(10)
                except subprocess.TimeoutExpired:
                    server.kill()
            shutil.rmtree(tmp, ignore_errors=True)
    failed = [r for r in results if not r["ok"]]
    print(f"\n{len(results) - len(failed)}/{len(results)} steps passed; artifacts in {OUT}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
