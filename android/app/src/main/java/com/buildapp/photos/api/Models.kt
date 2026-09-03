package com.buildapp.photos.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaItem(
    val id: String,
    val name: String,
    val kind: String,           // "photo" or "video"
    val ext: String,
    val mime: String? = null,
    val size: Long? = null,
    @SerialName("taken_at") val takenAt: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val album: String? = null,
    val favorite: Int = 0,
    @SerialName("trashed_at") val trashedAt: Double? = null,
    val archived: Int = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val place: String? = null,
    @SerialName("camera_make") val cameraMake: String? = null,
    @SerialName("camera_model") val cameraModel: String? = null,
    /** Bumped by the server on edit/rotate; goes into the thumb URL as ?v= for cache-busting. */
    @SerialName("edit_version") val editVersion: Int = 0,
    /** When the item entered the library (upload time), epoch seconds. */
    @SerialName("added_at") val addedAt: Double? = null,
    /** Detail endpoint only: preformatted exposure settings (iso, aperture, shutter, focal_length, lens, exposure_bias, flash). */
    val exposure: Map<String, String>? = null,
    /** Detail endpoint only, videos: duration, codec, frame_rate, bitrate. */
    val video: Map<String, String>? = null,
)

@Serializable
data class TimelineBucket(
    val y: String,
    val m: String,
    val n: Int,
)

@Serializable
data class FavoriteResp(
    val ok: Boolean,
    val favorite: Boolean,
)

@Serializable
data class MemoryGroup(
    val year: String,
    val title: String? = null,
    val items: List<MediaItem>,
)

@Serializable
data class LocationItem(
    val id: String,
    val name: String,
    val kind: String,
    val lat: Double,
    val lng: Double,
    @SerialName("taken_at") val takenAt: Double? = null,
    val album: String? = null,
)

@Serializable
data class ShareResp(
    val ok: Boolean,
    val token: String,
)

@Serializable
data class Memories(
    @SerialName("month_day") val monthDay: String,
    val groups: List<MemoryGroup>,
)

@Serializable
data class MediaPage(
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 0,
    val total: Int,
    val items: List<MediaItem>,
    val q: String? = null,
)

@Serializable
data class Album(
    val album: String,
    val count: Int,
)

@Serializable
data class Cluster(
    val id: Int,
    val name: String? = null,
    val count: Int,
    @SerialName("rep_face_id") val repFaceId: Int? = null,
    @SerialName("rep_media_id") val repMediaId: String? = null,
)

@Serializable
data class FacesStatus(
    val processed: Int,
    @SerialName("total_photos") val totalPhotos: Int,
    val faces: Int,
    val clusters: Int,
)

@Serializable
data class Person(
    val id: Int,
    val name: String,
    val count: Int = 0,
)

@Serializable
data class Health(
    val ok: Boolean,
    @SerialName("items_indexed") val itemsIndexed: Int,
    @SerialName("heic_supported") val heicSupported: Boolean,
    @SerialName("media_root") val mediaRoot: String,
)
