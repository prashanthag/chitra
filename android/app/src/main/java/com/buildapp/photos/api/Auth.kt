package com.buildapp.photos.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The session token, held in memory for every HTTP client in the app (API,
 * images, video, uploads) and persisted in Settings. Null while the server
 * is open (no accounts yet) or after signing out.
 */
object Auth {
    @Volatile var token: String? = null

    /** Adds `Authorization: Bearer <token>` to every request when signed in. */
    val interceptor = Interceptor { chain ->
        val t = token
        val req = if (t != null) chain.request().newBuilder().header("Authorization", "Bearer $t").build()
        else chain.request()
        chain.proceed(req)
    }

    fun headers(): Map<String, String> = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
}
