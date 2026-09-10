package com.buildapp.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlannerTest {
    private val camera = 100L
    private val shots = 200L
    private val wa = 300L

    private fun item(id: Long, bucket: Long, video: Boolean = false, size: Long = 2_000_000, added: Long = id) =
        DeviceItem(
            id = id, uri = "content://media/external/${if (video) "video" else "images"}/media/$id",
            name = if (video) "VID_$id.mp4" else "IMG_$id.jpg", size = size,
            bucketId = bucket, bucketName = when (bucket) { camera -> "Camera"; shots -> "Screenshots"; else -> "WhatsApp Images" },
            isVideo = video, dateAdded = added,
        )

    private val roll = listOf(
        item(1, camera, added = 10),
        item(2, camera, added = 50),
        item(3, camera, video = true, added = 30),
        item(4, shots, added = 40),
        item(5, wa, added = 60),
        item(6, camera, size = 4_000, added = 70),  // launcher-icon sized
    )

    @Test
    fun `only selected folders are uploaded`() {
        val plan = BackupPlanner.plan(roll, emptySet(), BackupOptions(setOf(camera)))
        assertEquals(listOf(2L, 3L, 1L), plan.map { it.id })
    }

    @Test
    fun `adding a folder later backfills it`() {
        val plan = BackupPlanner.plan(roll, emptySet(), BackupOptions(setOf(camera, shots)))
        assertTrue(plan.any { it.id == 4L })
        assertTrue(plan.none { it.bucketId == wa })
    }

    @Test
    fun `already uploaded items are skipped`() {
        val plan = BackupPlanner.plan(roll, setOf(2L, 3L), BackupOptions(setOf(camera)))
        assertEquals(listOf(1L), plan.map { it.id })
    }

    @Test
    fun `videos can be excluded`() {
        val plan = BackupPlanner.plan(roll, emptySet(), BackupOptions(setOf(camera), includeVideos = false))
        assertEquals(listOf(2L, 1L), plan.map { it.id })
    }

    @Test
    fun `tiny files are ignored`() {
        val plan = BackupPlanner.plan(roll, emptySet(), BackupOptions(setOf(camera)))
        assertTrue(plan.none { it.id == 6L })
        val lenient = BackupPlanner.plan(roll, emptySet(), BackupOptions(setOf(camera), minBytes = 0))
        assertTrue(lenient.any { it.id == 6L })
    }

    @Test
    fun `newest first so fresh photos land before the backlog`() {
        val plan = BackupPlanner.plan(roll, emptySet(), BackupOptions(setOf(camera, shots, wa)))
        assertEquals(listOf(5L, 2L, 4L, 3L, 1L), plan.map { it.id })
    }

    @Test
    fun `no folders selected means nothing is uploaded`() {
        assertEquals(emptyList<DeviceItem>(), BackupPlanner.plan(roll, emptySet(), BackupOptions(emptySet())))
    }

    @Test
    fun `default selection is the Camera folder, else the first folder`() {
        val buckets = listOf(
            DeviceBucket(shots, "Screenshots", 9, null),
            DeviceBucket(camera, "camera", 3, null),
        )
        assertEquals(setOf(camera), BackupPlanner.defaultBuckets(buckets))
        assertEquals(setOf(shots), BackupPlanner.defaultBuckets(buckets.take(1)))
        assertEquals(emptySet<Long>(), BackupPlanner.defaultBuckets(emptyList()))
    }
}
