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
