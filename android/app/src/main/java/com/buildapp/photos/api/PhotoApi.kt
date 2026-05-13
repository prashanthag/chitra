package com.buildapp.photos.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface PhotoApi {
    @GET("api/health")
    suspend fun health(): Health

    @GET("api/media")
    suspend fun media(
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = 60,
        @Query("kind") kind: String? = null,
        @Query("album") album: String? = null,
        @Query("q") q: String? = null,
        @Query("favorites") favorites: Int? = null,
        @Query("year") year: Int? = null,
        @Query("month") month: Int? = null,
        @Query("trashed") trashed: Int? = null,
        @Query("archived") archived: Int? = null,
    ): MediaPage

    @GET("api/albums")
    suspend fun albums(): List<Album>

    @GET("api/timeline")
    suspend fun timeline(): List<TimelineBucket>

    @GET("api/memories")
    suspend fun memories(): Memories

    @GET("api/search_semantic")
    suspend fun searchSemantic(
        @Query("q") q: String,
        @Query("top_k") topK: Int = 80,
    ): MediaPage

    @GET("api/clusters")
    suspend fun clusters(): List<Cluster>

    @GET("api/clusters/{id}/media")
    suspend fun clusterMedia(@Path("id") id: Int): List<MediaItem>

    @GET("api/faces/status")
    suspend fun facesStatus(): FacesStatus

    @GET("api/persons")
    suspend fun persons(): List<Person>

    @POST("api/rescan")
    suspend fun rescan(): Map<String, String>

    @POST("api/media/{id}/favorite")
    suspend fun toggleFavorite(@Path("id") id: String): FavoriteResp

    @POST("api/media/{id}/trash")
    suspend fun trash(@Path("id") id: String): Map<String, kotlinx.serialization.json.JsonElement>

    @POST("api/media/{id}/restore")
    suspend fun restore(@Path("id") id: String): Map<String, kotlinx.serialization.json.JsonElement>

    @POST("api/media/{id}/archive")
    suspend fun archive(@Path("id") id: String): Map<String, kotlinx.serialization.json.JsonElement>

    @POST("api/media/{id}/rotate")
    suspend fun rotate(
        @Path("id") id: String,
        @Query("degrees") degrees: Int = 90,
    ): Map<String, kotlinx.serialization.json.JsonElement>

    companion object {
        fun create(baseUrl: String): PhotoApi {
            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                coerceInputValues = true
            }
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val baseNormalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return Retrofit.Builder()
                .baseUrl(baseNormalized)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PhotoApi::class.java)
        }
    }
}

object Urls {
    fun thumb(baseUrl: String, id: String): String =
        (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/") + "api/media/$id/thumb"

    fun full(baseUrl: String, id: String, asJpeg: Boolean = false): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return base + "api/media/$id/full" + if (asJpeg) "?as=jpeg" else ""
    }

    fun stream(baseUrl: String, id: String): String =
        (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/") + "api/media/$id/stream.mp4"

    fun clusterThumb(baseUrl: String, id: Int): String =
        (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/") + "api/clusters/$id/thumb"
}
