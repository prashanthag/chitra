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


class FeedFilterTests(unittest.TestCase):
    def test_plain_feed_excludes_uploads(self):
        total, names = totals()
        self.assertNotIn("up.jpg", names)
        self.assertEqual(total, 2)

    def test_camera_filter_sees_uploads(self):
        # Regression: Cameras said 477, click-through showed 0.
        total, names = totals(camera="TestFold")
        self.assertEqual(names, ["up.jpg"])

    def test_search_sees_uploads(self):
        _, names = totals(q="up")
        self.assertIn("up.jpg", names)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
