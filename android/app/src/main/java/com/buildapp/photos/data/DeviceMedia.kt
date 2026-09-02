package com.buildapp.photos.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** A folder on the device as MediaStore sees it (DCIM/Camera, Screenshots, WhatsApp Images…). */
data class DeviceBucket(
    val id: Long,
    val name: String,
    val count: Int,
    /** content:// URI of the newest item, for the folder thumbnail. */
    val coverUri: String?,
)

/** One photo or video row from MediaStore. Plain data so it can be unit-tested off-device. */
data class DeviceItem(
    val id: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val bucketId: Long,
    val bucketName: String,
    val isVideo: Boolean,
    /** MediaStore DATE_ADDED, epoch seconds. */
    val dateAdded: Long,
)

/**
 * Reads the device camera roll through the unified Files collection so one
 * cursor covers images and videos, grouped by the folder they live in.
 */
object DeviceMedia {
    private val FILES: Uri = MediaStore.Files.getContentUri("external")
    private const val TYPE_IMAGE = 1   // MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private const val TYPE_VIDEO = 3   // MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
    // String names: the BUCKET_* constants moved to MediaColumns in API 29 but
    // the columns themselves exist on every supported version.
    private const val BUCKET_ID = "bucket_id"
    private const val BUCKET_NAME = "bucket_display_name"

    private val PROJECTION = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        BUCKET_ID,
        BUCKET_NAME,
    )

    fun itemUri(id: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        id,
    )

    /** Every image/video, newest first. [bucketIds] null = all folders. */
    fun items(cr: ContentResolver, bucketIds: Set<Long>? = null, includeVideos: Boolean = true): List<DeviceItem> {
        val types = if (includeVideos) "($TYPE_IMAGE, $TYPE_VIDEO)" else "($TYPE_IMAGE)"
        var sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN $types"
        val args = mutableListOf<String>()
        if (bucketIds != null) {
            if (bucketIds.isEmpty()) return emptyList()
            sel += " AND $BUCKET_ID IN (${bucketIds.joinToString(",") { "?" }})"
            args += bucketIds.map { it.toString() }
        }
        val out = mutableListOf<DeviceItem>()
        cr.query(FILES, PROJECTION, sel, args.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val iBucket = c.getColumnIndexOrThrow(BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(BUCKET_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == TYPE_VIDEO
                out += DeviceItem(
                    id = id,
                    uri = itemUri(id, isVideo).toString(),
                    name = c.getString(iName) ?: "item-$id",
                    size = c.getLong(iSize),
                    bucketId = c.getLong(iBucket),
                    bucketName = c.getString(iBucketName) ?: "Unknown",
                    isVideo = isVideo,
                    dateAdded = c.getLong(iAdded),
                )
            }
        }
        return out
    }

    /** Folders with counts, biggest first; the newest item is the cover. */
    fun buckets(cr: ContentResolver): List<DeviceBucket> {
        val all = items(cr)
        return all.groupBy { it.bucketId }.map { (id, list) ->
            val newest = list.maxByOrNull { it.dateAdded }
            DeviceBucket(id = id, name = list.first().bucketName, count = list.size, coverUri = newest?.uri)
        }.sortedWith(compareByDescending<DeviceBucket> { it.name.equals("Camera", true) }.thenByDescending { it.count })
    }
}
