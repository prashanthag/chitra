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
    val items: List<MediaItem>,
)

@Serializable
data class Memories(
    @SerialName("month_day") val monthDay: String,
    val groups: List<MemoryGroup>,
)

@Serializable
data class MediaPage(
    val page: Int,
    @SerialName("per_page") val perPage: Int,
    val total: Int,
    val items: List<MediaItem>,
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
