"""API regression tests for the chitra server.

Covers the behaviors (and past regressions) of the media API:
  - dated/undated filters and the Unknown section
  - uploads-album exclusion from plain feeds ONLY (camera/search/favorites/
    album/year must still see uploads — regression: Fold5 camera showed 0)
  - batch trash/restore/delete semantics (rowcounts; delete only from trash)
  - read-only mode: blocks destructive, allows favorite/upload/rescan
  - /api/memories without the clip_embedding column (regression: app 500)

Run:  cd server && ./.venv-mac/bin/python -m unittest discover tests -v
"""
import io
import os
import shutil
import sys
import tempfile
import time
import unittest

TMP = tempfile.mkdtemp(prefix="chitra-test-")
MEDIA = os.path.join(TMP, "media")
CACHE = os.path.join(TMP, "cache")
os.makedirs(os.path.join(MEDIA, "CameraX"))
os.makedirs(os.path.join(MEDIA, "uploads", "2026-08-08"))

os.environ["PHOTO_ROOT"] = MEDIA
os.environ["CACHE_DIR"] = CACHE
os.environ["CHITRA_READONLY"] = "0"
os.environ["LOG_REQUESTS"] = "0"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image  # noqa: E402

import app as chitra  # noqa: E402


def make_jpeg(path, color=(120, 40, 40)):
    Image.new("RGB", (64, 64), color).save(path, "JPEG")


# Fixture library:
#   CameraX/one.jpg   - normal dated photo
#   CameraX/two.jpg   - will get taken_at NULL (undated)
#   uploads/2026-08-08/up.jpg - phone-backup photo, camera 'TestFold'
make_jpeg(os.path.join(MEDIA, "CameraX", "one.jpg"))
make_jpeg(os.path.join(MEDIA, "CameraX", "two.jpg"), (10, 90, 30))
make_jpeg(os.path.join(MEDIA, "uploads", "2026-08-08", "up.jpg"), (10, 30, 120))

chitra.init_db()
chitra.scan_once()

conn = chitra.sqlite3.connect(chitra.DB_PATH)
conn.execute("UPDATE media SET taken_at=NULL WHERE name='two.jpg'")
conn.execute("UPDATE media SET camera_model='TestFold' WHERE name='up.jpg'")
conn.commit()
conn.close()

client = chitra.app.test_client()


def totals(**params):
    r = client.get("/api/media", query_string={"per_page": 200, **params})
    assert r.status_code == 200, r.status_code
    d = r.get_json()
    return d["total"], [i["name"] for i in d["items"]]


def id_of(name):
    conn = chitra.sqlite3.connect(chitra.DB_PATH)
    row = conn.execute("SELECT id FROM media WHERE name=?", (name,)).fetchone()
    conn.close()
    return row[0]


def _drop_clusters_table():
    conn = chitra.sqlite3.connect(chitra.DB_PATH)
    conn.execute("DROP TABLE IF EXISTS clusters")
    conn.commit()
    conn.close()


def _drop_clip_embedding():
    conn = chitra.sqlite3.connect(chitra.DB_PATH)
    conn.execute("ALTER TABLE media DROP COLUMN clip_embedding")
    conn.commit()
    conn.close()


class InfoPanelTests(unittest.TestCase):
    def test_detail_carries_exposure_settings_from_exif(self):
        path = os.path.join(MEDIA, "CameraX", "exposed.jpg")
        exif = Image.Exif()
        exif[271] = "Nikon"
        sub = exif.get_ifd(0x8769)
        sub[33437] = 1.8          # FNumber
        sub[33434] = 1 / 125      # ExposureTime
        sub[34855] = 200          # ISO
        sub[37386] = 26.0         # FocalLength
        sub[41989] = 39           # FocalLengthIn35mmFilm
        sub[42036] = "Nikkor 26mm"
        sub[37380] = 0.3          # ExposureBiasValue
        sub[37385] = 16           # Flash: did not fire
        Image.new("RGB", (64, 64), (9, 9, 9)).save(path, "JPEG", exif=exif.tobytes())

        def cleanup():
            os.remove(path)
            chitra.scan_once()
        self.addCleanup(cleanup)
        chitra.scan_once()

        d = client.get(f"/api/media/{id_of('exposed.jpg')}").get_json()
        self.assertEqual(d["exposure"], {
            "iso": "ISO 200", "aperture": "f/1.8", "shutter": "1/125 s",
            "focal_length": "26 mm (39 mm equiv.)", "lens": "Nikkor 26mm",
            "exposure_bias": "+0.3 EV", "flash": "Did not fire",
        })
        # A photo without exposure tags gets an empty block, not an error.
        self.assertEqual(client.get(f"/api/media/{id_of('one.jpg')}").get_json()["exposure"], {})


class EditVersionTests(unittest.TestCase):
    """Thumb URLs are ?v=<edit_version> and immutable, so every mutation must
    bump the version and every list that feeds a grid must carry it."""

    def test_rotate_bumps_edit_version_and_returns_it(self):
        path = os.path.join(MEDIA, "CameraX", "rot.jpg")
        Image.new("RGB", (80, 40), (5, 5, 5)).save(path, "JPEG")

        def cleanup():
            os.remove(path)
            chitra.scan_once()
        self.addCleanup(cleanup)
        chitra.scan_once()
        mid = id_of("rot.jpg")

        r = client.post(f"/api/media/{mid}/rotate?degrees=90")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["edit_version"], 1)
        self.assertEqual((r.get_json()["width"], r.get_json()["height"]), (40, 80))
        self.assertEqual(client.get(f"/api/media/{mid}").get_json()["edit_version"], 1)
        r2 = client.post(f"/api/media/{mid}/rotate?degrees=90")
        self.assertEqual(r2.get_json()["edit_version"], 2)

    def test_collection_feeds_carry_edit_version(self):
        mid = id_of("one.jpg")
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("CREATE TABLE IF NOT EXISTS clusters "
                     "(id INTEGER PRIMARY KEY, name TEXT, count INTEGER, rep_face_id INTEGER)")
        conn.execute("CREATE TABLE IF NOT EXISTS faces (id INTEGER PRIMARY KEY,"
                     " media_id TEXT, bbox TEXT, score REAL, embedding BLOB,"
                     " cluster_id INTEGER)")
        conn.execute("INSERT OR REPLACE INTO clusters (id, name, count) VALUES (7, 'V', 1)")
        conn.execute("INSERT INTO faces (media_id, bbox, score, cluster_id) VALUES (?,?,?,7)",
                     (mid, "1,1,10,10", 0.9))
        conn.execute("UPDATE media SET lat=12.9, lng=77.6 WHERE id=?", (mid,))
        conn.commit()
        conn.close()

        def cleanup():
            c = chitra.sqlite3.connect(chitra.DB_PATH)
            c.execute("DELETE FROM faces WHERE cluster_id=7")
            c.execute("UPDATE media SET lat=NULL, lng=NULL WHERE id=?", (mid,))
            c.commit()
            c.close()
            _drop_clusters_table()
        self.addCleanup(cleanup)

        pid = client.post("/api/persons", json={"name": "Version Carrier"}).get_json()["id"]
        self.assertEqual(client.post(f"/api/persons/{pid}/tag/{mid}").status_code, 200)

        for url in (f"/api/persons/{pid}/media", "/api/clusters/7/media",
                    "/api/media_near?lat=12.9&lng=77.6&radius_km=1"):
            d = client.get(url).get_json()
            items = d["items"] if isinstance(d, dict) else d
            self.assertTrue(items, url)
            self.assertTrue(all("edit_version" in i for i in items), url)


class QueryPlanTests(unittest.TestCase):
    """The latency work only holds if SQLite actually uses the indexes."""

    def _plan(self, sql, args=()):
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        rows = conn.execute("EXPLAIN QUERY PLAN " + sql, args).fetchall()
        conn.close()
        return " | ".join(r[3] for r in rows)

    def test_duplicate_lookup_uses_name_size_index(self):
        plan = self._plan("SELECT id FROM media WHERE name = ? AND size = ? AND trashed_at IS NULL LIMIT 1",
                          ("x.jpg", 1))
        self.assertIn("idx_media_name_size", plan)

    def test_recently_uploaded_sort_has_no_temp_btree(self):
        plan = self._plan(
            "SELECT id FROM media m WHERE m.trashed_at IS NULL AND m.archived = 0 AND m.album = ? "
            "ORDER BY m.added_at DESC LIMIT 80 OFFSET 0", ("uploads",))
        self.assertNotIn("TEMP B-TREE", plan)

    def test_search_inside_uploads_album_can_match(self):
        # q used to add `album != 'uploads'` even when album=uploads was asked
        # for, so the Uploads view searched for anything returned nothing.
        total, names = totals(album="uploads", q="up")
        self.assertEqual(names, ["up.jpg"])
        total_all, names_all = totals(q="up")
        self.assertNotIn("up.jpg", names_all)   # plain search still skips uploads


class FeedFilterTests(unittest.TestCase):
    def test_plain_feed_excludes_uploads(self):
        total, names = totals()
        self.assertNotIn("up.jpg", names)
        self.assertEqual(total, 2)

    def test_camera_filter_sees_uploads(self):
        # Regression: Cameras said 477, click-through showed 0.
        total, names = totals(camera="TestFold")
        self.assertEqual(names, ["up.jpg"])

    def test_search_excludes_uploads_and_undated(self):
        # Search is scoped to the curated library: no uploads album, no
        # unknown-date items (user decision 2026-08-09).
        _, names = totals(q="up")
        self.assertNotIn("up.jpg", names)
        _, names = totals(q="two")     # two.jpg is the undated fixture
        self.assertNotIn("two.jpg", names)
        _, names = totals(q="one")     # dated curated item still findable
        self.assertIn("one.jpg", names)

    def test_album_uploads_browsable(self):
        _, names = totals(album="uploads")
        self.assertEqual(names, ["up.jpg"])

    def test_dated_filter_excludes_undated(self):
        _, names = totals(dated=1)
        self.assertNotIn("two.jpg", names)
        self.assertIn("one.jpg", names)

    def test_undated_filter_only_undated(self):
        _, names = totals(undated=1)
        self.assertEqual(names, ["two.jpg"])

    def test_favorites_sees_uploads(self):
        client.post(f"/api/media/{id_of('up.jpg')}/favorite")
        _, names = totals(favorites=1)
        self.assertIn("up.jpg", names)
        client.post(f"/api/media/{id_of('up.jpg')}/favorite")  # untoggle


class BatchTests(unittest.TestCase):
    def test_batch_rowcounts_and_delete_safety(self):
        mid = id_of("one.jpg")
        # delete of a non-trashed item must refuse
        r = client.post("/api/media/batch_delete", json={"ids": [mid]})
        self.assertEqual(r.get_json()["count"], 0)
        # trash reports rows actually changed, bogus ids ignored
        r = client.post("/api/media/batch_trash", json={"ids": [mid, "nope"]})
        self.assertEqual(r.get_json()["count"], 1)
        # restore
        r = client.post("/api/media/batch_restore", json={"ids": [mid]})
        self.assertEqual(r.get_json()["count"], 1)

    def test_batch_delete_removes_trashed_file(self):
        p = os.path.join(MEDIA, "CameraX", "doomed.jpg")
        make_jpeg(p)
        chitra.scan_once()
        mid = id_of("doomed.jpg")
        client.post("/api/media/batch_trash", json={"ids": [mid]})
        r = client.post("/api/media/batch_delete", json={"ids": [mid]})
        self.assertEqual(r.get_json()["count"], 1)
        self.assertFalse(os.path.exists(p))
        self.assertEqual(client.get(f"/api/media/{mid}").status_code, 404)


class ReadOnlyTests(unittest.TestCase):
    def setUp(self):
        chitra.READ_ONLY = True

    def tearDown(self):
        chitra.READ_ONLY = False

    def test_trash_and_restore_allowed_but_permanent_delete_blocked(self):
        # Safe mode: users can trash (reversible) but never destroy files.
        mid = id_of("one.jpg")
        self.assertEqual(client.post(f"/api/media/{mid}/trash").status_code, 200)
        self.assertEqual(
            client.post("/api/media/batch_delete", json={"ids": [mid]}).status_code, 403)
        r = client.post("/api/media/batch_restore", json={"ids": [mid]})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["count"], 1)

    def test_file_modifying_endpoints_blocked(self):
        mid = id_of("one.jpg")
        self.assertEqual(client.post(f"/api/media/{mid}/rotate").status_code, 403)
        self.assertEqual(client.post(f"/api/media/{mid}/edit").status_code, 403)

    def test_people_labelling_allowed_in_safe_mode(self):
        """Regression: safe mode 403'd cluster naming and person tagging, so
        renaming a face group silently did nothing on a read-only library.
        Labels are metadata about the library, not edits to any media file."""
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("CREATE TABLE IF NOT EXISTS clusters "
                     "(id INTEGER PRIMARY KEY, name TEXT, count INTEGER, rep_face_id INTEGER)")
        conn.execute("INSERT OR REPLACE INTO clusters (id, name, count) VALUES (9, NULL, 1)")
        conn.commit()
        conn.close()
        self.addCleanup(_drop_clusters_table)

        r = client.post("/api/clusters/9/name", json={"name": "Sister A"})
        self.assertEqual(r.status_code, 200)

        r = client.post("/api/persons", json={"name": "Sister B"})
        self.assertEqual(r.status_code, 200)
        pid = r.get_json()["id"]
        self.assertEqual(
            client.post(f"/api/persons/{pid}/tag/{id_of('one.jpg')}").status_code, 200)

        # The name must actually persist, not just return 200.
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        got = conn.execute("SELECT name FROM clusters WHERE id=9").fetchone()[0]
        conn.close()
        self.assertEqual(got, "Sister A")

    def test_auto_purge_never_destroys_in_safe_mode(self):
        # Even ancient trashed items must survive: purge is a no-op.
        p = os.path.join(MEDIA, "CameraX", "old_trashed.jpg")
        make_jpeg(p)
        chitra.scan_once()
        mid = id_of("old_trashed.jpg")
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("UPDATE media SET trashed_at=? WHERE id=?",
                     (time.time() - 90 * 86400, mid))
        conn.commit()
        conn.close()
        chitra.auto_purge_trash(age_days=60)
        self.assertTrue(os.path.exists(p), "purge deleted a file in safe mode")
        client.post("/api/media/batch_restore", json={"ids": [mid]})

    def test_favorite_allowed(self):
        mid = id_of("one.jpg")
        self.assertEqual(client.post(f"/api/media/{mid}/favorite").status_code, 200)
        client.post(f"/api/media/{mid}/favorite")

    def test_upload_check_allowed_in_safe_mode(self):
        # The phone pre-flights every backup batch through this POST; it reads
        # the index and writes nothing, so safe mode must let it through.
        r = client.post("/api/upload/check", json={"files": [{"name": "x.jpg", "size": 1}]})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["exists"], [False])

    def test_upload_allowed_and_lands_in_uploads_album(self):
        buf = io.BytesIO()
        Image.new("RGB", (32, 32), (200, 200, 0)).save(buf, "JPEG")
        buf.seek(0)
        r = client.post("/api/upload",
                        data={"file": (buf, "roundtrip.jpg")},
                        content_type="multipart/form-data")
        self.assertEqual(r.status_code, 200)
        self.assertTrue(r.get_json()["items"][0]["indexed"])
        _, names = totals(album="uploads")
        self.assertIn("roundtrip.jpg", names)
        # and it must NOT leak into the plain feed
        _, plain = totals()
        self.assertNotIn("roundtrip.jpg", plain)


class CamerasTests(unittest.TestCase):
    def test_trashed_items_leave_camera_counts_and_empty_cameras_vanish(self):
        # up.jpg is TestFold's only item: trashing it must remove the camera
        # from /api/cameras entirely; restoring brings it back with count 1.
        mid = id_of("up.jpg")

        def cams():
            return {c["key"]: c["count"] for c in client.get("/api/cameras").get_json()}

        self.assertEqual(cams().get("TestFold"), 1)
        client.post("/api/media/batch_trash", json={"ids": [mid]})
        self.assertNotIn("TestFold", cams(), "empty camera should disappear")
        client.post("/api/media/batch_restore", json={"ids": [mid]})
        self.assertEqual(cams().get("TestFold"), 1)


class PassportTests(unittest.TestCase):
    def test_crop_box_puts_head_in_passport_range(self):
        # 4000x3000 photo, face box 300px tall centered-ish
        box = chitra.passport_crop_box((1800, 1000, 2100, 1300), 4000, 3000)
        x1, y1, x2, y2 = box
        self.assertEqual(x2 - x1, y2 - y1, "crop must be square")
        head_pct = 300 / (y2 - y1) * 100
        self.assertGreaterEqual(head_pct, 50)
        self.assertLessEqual(head_pct, 69)
        # face horizontally centered in crop
        self.assertAlmostEqual((x1 + x2) / 2, (1800 + 2100) / 2, delta=2)

    def test_crop_box_clamps_inside_image(self):
        # face near top-left corner: crop must stay within bounds
        x1, y1, x2, y2 = chitra.passport_crop_box((10, 10, 110, 110), 800, 600)
        self.assertGreaterEqual(x1, 0)
        self.assertGreaterEqual(y1, 0)
        self.assertLessEqual(x2, 800)
        self.assertLessEqual(y2, 600)

    def test_passport_endpoint_returns_square_jpeg_with_white_corners(self):
        # Stub the segmenter: fully-opaque person = whole image kept; the
        # endpoint must still composite, crop square, and return JPEG.
        mid = id_of("one.jpg")
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("CREATE TABLE IF NOT EXISTS faces (id INTEGER PRIMARY KEY,"
                     " media_id TEXT, bbox TEXT, score REAL, embedding BLOB,"
                     " cluster_id INTEGER)")
        conn.execute("INSERT INTO faces (media_id, bbox, score) VALUES (?,?,?)",
                     (mid, "20,16,44,48", 0.9))
        conn.commit()
        conn.close()

        def fake_matte(im):
            from PIL import Image as I
            return I.new("L", im.size, 255)  # keep everything

        old = chitra._person_matte
        chitra._person_matte = fake_matte
        try:
            r = client.get(f"/api/media/{mid}/passport")
        finally:
            chitra._person_matte = old
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.mimetype, "image/jpeg")
        from PIL import Image as I
        im = I.open(io.BytesIO(r.data))
        self.assertEqual(im.size[0], im.size[1], "output must be square")

    def test_passport_404_without_face(self):
        mid = id_of("two.jpg")
        r = client.get(f"/api/media/{mid}/passport")
        self.assertEqual(r.status_code, 404)


class MemoriesTests(unittest.TestCase):
    def test_memories_survives_missing_clip_column(self):
        # Regression: fresh DBs (no clip_indexer) 500'd -> app "failed to connect".
        r = client.get("/api/memories")
        self.assertEqual(r.status_code, 200)


class DetailTests(unittest.TestCase):
    def test_detail_has_no_place_without_gps(self):
        r = client.get(f"/api/media/{id_of('one.jpg')}")
        d = r.get_json()
        self.assertIsNone(d.get("lat"))
        self.assertNotIn("place", d)

    def test_full_as_jpeg_converts_any_photo(self):
        r = client.get(f"/api/media/{id_of('one.jpg')}/full?as=jpeg")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.mimetype, "image/jpeg")

    def test_detail_survives_clip_embedding_blob(self):
        """Regression: media_meta does SELECT *, so once clip_indexer.py adds
        the clip_embedding BLOB every detail request 500'd on jsonify with
        'Object of type bytes is not JSON serializable' — the metadata panel
        (date, camera, size) went blank for every CLIP-indexed photo."""
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        chitra._add_column_if_missing(conn, "media", "clip_embedding BLOB")
        conn.execute("UPDATE media SET clip_embedding=? WHERE name='one.jpg'",
                     (b"\x00\x01\x02\x03" * 128,))
        conn.commit()
        conn.close()
        # The fixture DB is shared module-wide, and MemoriesTests asserts the
        # no-clip_embedding path — put the schema back however this test ends.
        self.addCleanup(_drop_clip_embedding)

        r = client.get(f"/api/media/{id_of('one.jpg')}")
        self.assertEqual(r.status_code, 200)
        d = r.get_json()
        self.assertNotIn("clip_embedding", d)
        self.assertEqual(d["name"], "one.jpg")
        self.assertIsNotNone(d["taken_at"])


if __name__ == "__main__":
    unittest.main(verbosity=2)


# ---------------------------------------------------------------------------
# Uploads: multi-file, de-duplication, pre-flight check, recently uploaded
# ---------------------------------------------------------------------------

from pathlib import Path  # noqa: E402


def _jpeg(color, size=(40, 40)):
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "JPEG")
    buf.seek(0)
    return buf


def _upload(parts):
    r = client.post("/api/upload", data=parts, content_type="multipart/form-data")
    assert r.status_code == 200, (r.status_code, r.data[:200])
    return r.get_json()


def _row(name, col):
    conn = chitra.sqlite3.connect(chitra.DB_PATH)
    v = conn.execute(f"SELECT {col} FROM media WHERE name=?", (name,)).fetchone()
    conn.close()
    return v[0] if v else None


class UploadTests(unittest.TestCase):
    def test_multi_file_upload_saves_every_part(self):
        # The phone sends file_0, file_1, ...; the web client repeats 'file'.
        d = _upload({
            "file_0": (_jpeg((1, 2, 3)), "multi_a.jpg"),
            "file_1": (_jpeg((4, 5, 6)), "multi_b.jpg"),
            "file": [(_jpeg((7, 8, 9)), "multi_c.jpg"), (_jpeg((9, 9, 9), (48, 48)), "multi_d.jpg")],
        })
        self.assertEqual(d["count"], 4)
        self.assertEqual(sorted(i["name"] for i in d["items"]),
                         ["multi_a.jpg", "multi_b.jpg", "multi_c.jpg", "multi_d.jpg"])
        self.assertTrue(all(i["indexed"] and not i.get("duplicate") for i in d["items"]))
        _, names = totals(album="uploads")
        for n in ("multi_a.jpg", "multi_b.jpg", "multi_c.jpg", "multi_d.jpg"):
            self.assertIn(n, names)

    def test_duplicate_upload_is_skipped_not_copied(self):
        raw = _jpeg((50, 60, 70)).getvalue()
        first = _upload({"file": (io.BytesIO(raw), "dup.jpg")})["items"][0]
        second = _upload({"file": (io.BytesIO(raw), "dup.jpg")})["items"][0]
        self.assertTrue(second["duplicate"])
        self.assertEqual(second["id"], first["id"])
        on_disk = [p.name for p in Path(MEDIA, "uploads").rglob("dup*")]
        self.assertEqual(on_disk, ["dup.jpg"])
        # Same name, different bytes (different size) is NOT a duplicate:
        # it lands beside the original under a suffixed name.
        third = _upload({"file": (_jpeg((50, 60, 70), (100, 100)), "dup.jpg")})["items"][0]
        self.assertFalse(third.get("duplicate"))
        self.assertEqual(third["name"], "dup_1.jpg")

    def test_upload_check_reports_existing_pairs(self):
        _upload({"file": (_jpeg((11, 22, 33)), "chk.jpg")})
        size = _row("chk.jpg", "size")
        r = client.post("/api/upload/check", json={"files": [
            {"name": "chk.jpg", "size": size},
            {"name": "chk.jpg", "size": size + 1},
            {"name": "/sdcard/DCIM/chk.jpg", "size": size},   # path stripped
            {"name": "nope.jpg", "size": 10},
            {"name": "bad", "size": "x"},
        ]})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["exists"], [True, False, True, False, False])
        self.assertEqual(client.post("/api/upload/check", json={"files": "x"}).status_code, 400)

    def test_recently_uploaded_orders_by_added_at(self):
        _upload({"file": (_jpeg((1, 1, 1)), "older_up.jpg")})
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("UPDATE media SET added_at = added_at - 1000 WHERE name='older_up.jpg'")
        conn.commit()
        conn.close()
        _upload({"file": (_jpeg((2, 2, 2)), "newer_up.jpg")})
        r = client.get("/api/media", query_string={"album": "uploads", "sort": "added", "per_page": 200})
        items = r.get_json()["items"]
        names = [i["name"] for i in items]
        self.assertLess(names.index("newer_up.jpg"), names.index("older_up.jpg"))
        self.assertIsNotNone(items[0]["added_at"])
        self.assertIn("edit_version", items[0])

    def test_rescan_keeps_added_at(self):
        _upload({"file": (_jpeg((3, 3, 3)), "keep_added.jpg")})
        before = _row("keep_added.jpg", "added_at")
        path = _row("keep_added.jpg", "path")
        os.utime(path, (time.time() + 5, time.time() + 5))   # file "changed"
        chitra.scan_once()
        self.assertEqual(_row("keep_added.jpg", "added_at"), before)
        self.assertNotEqual(_row("keep_added.jpg", "mtime"), None)


# ---------------------------------------------------------------------------
# Feed ordering and the indexes behind it (shift-left perf checks)
# ---------------------------------------------------------------------------

def _add_album(album, names):
    """Create a throwaway album dir + files and index them."""
    d = os.path.join(MEDIA, album)
    os.makedirs(d, exist_ok=True)
    for name in names:
        make_jpeg(os.path.join(d, name), (len(name), 9, 9))
    chitra.scan_once()


def _remove_album(album):
    """Delete the dir and let the scanner prune its rows (shared fixture DB)."""
    shutil.rmtree(os.path.join(MEDIA, album), ignore_errors=True)
    chitra.scan_once()


class SortIndexTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _add_album("SortAlbum", ["s1.jpg", "s2.jpg", "s3.jpg"])
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("UPDATE media SET taken_at=100 WHERE name='s1.jpg'")
        conn.execute("UPDATE media SET taken_at=300 WHERE name='s2.jpg'")
        conn.execute("UPDATE media SET taken_at=200 WHERE name='s3.jpg'")
        conn.commit()
        conn.close()

    @classmethod
    def tearDownClass(cls):
        _remove_album("SortAlbum")

    def test_dated_feed_is_newest_first(self):
        _, names = totals(dated=1)
        sub = [n for n in names if n in ("s1.jpg", "s2.jpg", "s3.jpg")]
        self.assertEqual(sub, ["s2.jpg", "s3.jpg", "s1.jpg"])

    def test_dated_feed_needs_no_sort_step(self):
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        plan = conn.execute(
            "EXPLAIN QUERY PLAN SELECT m.id FROM media m WHERE m.trashed_at IS NULL "
            "AND m.archived = 0 AND m.taken_at IS NOT NULL AND m.album != 'uploads' "
            "ORDER BY m.taken_at DESC LIMIT 80").fetchall()
        conn.close()
        detail = " | ".join(r[3] for r in plan)
        self.assertNotIn("TEMP B-TREE", detail, detail)
        # Either browse index has taken_at right after (trashed_at, archived).
        self.assertRegex(detail, r"idx_media_(dated|undated)")

    def test_indexes_exist_after_init(self):
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        names = {r[1] for r in conn.execute("PRAGMA index_list(media)")}
        conn.close()
        for idx in ("idx_media_dated", "idx_media_mtime", "idx_media_added"):
            self.assertIn(idx, names)

    def test_undated_feed_orders_by_file_date(self):
        os.makedirs(os.path.join(MEDIA, "UndatedAlbum"), exist_ok=True)
        self.addCleanup(_remove_album, "UndatedAlbum")
        for name in ("u_old.jpg", "u_new.jpg"):
            make_jpeg(os.path.join(MEDIA, "UndatedAlbum", name), (5, 5, 5))
        now = time.time()
        os.utime(os.path.join(MEDIA, "UndatedAlbum", "u_old.jpg"), (now - 5000, now - 5000))
        os.utime(os.path.join(MEDIA, "UndatedAlbum", "u_new.jpg"), (now - 10, now - 10))
        chitra.scan_once()
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        conn.execute("UPDATE media SET taken_at=NULL WHERE name IN ('u_old.jpg','u_new.jpg')")
        conn.commit()
        conn.close()
        _, names = totals(undated=1, kind="photo")
        sub = [n for n in names if n in ("u_old.jpg", "u_new.jpg")]
        self.assertEqual(sub, ["u_new.jpg", "u_old.jpg"])


# ---------------------------------------------------------------------------
# HTTP caching and timing headers
# ---------------------------------------------------------------------------

class CacheHeaderTests(unittest.TestCase):
    def test_thumb_cache_headers(self):
        mid = id_of("one.jpg")
        r = client.get(f"/api/media/{mid}/thumb")
        self.assertEqual(r.status_code, 200)
        cc = r.headers["Cache-Control"]
        self.assertIn("max-age=600", cc)
        self.assertNotIn("immutable", cc)
        self.assertIn("ETag", r.headers)
        # Versioned URL: cache for a year, immutable.
        r2 = client.get(f"/api/media/{mid}/thumb?v=3")
        cc2 = r2.headers["Cache-Control"]
        self.assertIn("max-age=31536000", cc2)
        self.assertIn("immutable", cc2)
        # Conditional revalidation still works for clients that do it.
        r3 = client.get(f"/api/media/{mid}/thumb", headers={"If-None-Match": r.headers["ETag"]})
        self.assertEqual(r3.status_code, 304)

    def test_placeholder_thumb_is_never_immutable(self):
        # ensure_thumb negative-caches a failed thumb as the placeholder file.
        # The web client always asks with ?v=, so serving that file through
        # the normal path would pin a grey tile in the browser for a year.
        mid = id_of("two.jpg")
        p = chitra.thumb_path_for(mid)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(chitra._placeholder_thumb())
        self.addCleanup(lambda: p.unlink(missing_ok=True))
        r = client.get(f"/api/media/{mid}/thumb?v=0")
        self.assertEqual(r.status_code, 200)
        self.assertIn("max-age=600", r.headers["Cache-Control"])
        self.assertNotIn("immutable", r.headers["Cache-Control"])
        # ...and the negative cache is kept: no regeneration behind our back.
        self.assertEqual(p.read_bytes(), chitra._placeholder_thumb())

    def test_server_timing_header_on_every_response(self):
        r = client.get("/api/health")
        self.assertRegex(r.headers.get("Server-Timing", ""), r"app;dur=\d")
        r = client.get("/api/media", query_string={"per_page": 5})
        self.assertRegex(r.headers.get("Server-Timing", ""), r"app;dur=\d")


class AlbumCoverTests(unittest.TestCase):
    def test_album_count_and_cover_is_newest_non_trashed(self):
        _add_album("CoverAlbum", ["alb_a.jpg", "alb_b.jpg", "alb_c.jpg"])
        self.addCleanup(_remove_album, "CoverAlbum")
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        for name, ts in (("alb_a.jpg", 1000), ("alb_b.jpg", 3000), ("alb_c.jpg", 2000)):
            conn.execute("UPDATE media SET taken_at=? WHERE name=?", (ts, name))
        # Newest one trashed: cover must fall back to the next newest.
        conn.execute("UPDATE media SET trashed_at=1 WHERE name='alb_b.jpg'")
        conn.commit()
        conn.close()
        albums = {a["album"]: a for a in client.get("/api/albums").get_json()}
        self.assertEqual(albums["CoverAlbum"]["count"], 2)
        self.assertEqual(albums["CoverAlbum"]["cover"], id_of("alb_c.jpg"))

    def test_undated_feed_needs_no_sort_step(self):
        conn = chitra.sqlite3.connect(chitra.DB_PATH)
        plan = conn.execute(
            "EXPLAIN QUERY PLAN SELECT m.id FROM media m WHERE m.trashed_at IS NULL "
            "AND m.archived = 0 AND m.kind = 'photo' AND m.taken_at IS NULL "
            "AND m.album != 'uploads' ORDER BY m.mtime DESC LIMIT 80").fetchall()
        conn.close()
        detail = " | ".join(r[3] for r in plan)
        self.assertNotIn("TEMP B-TREE", detail, detail)
