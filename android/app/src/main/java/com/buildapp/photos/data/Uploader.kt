package com.buildapp.photos.data

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    fun upload(context: Context, serverUrl: String, uris: List<Uri>): Result<Int> = runCatching {
        val resolver = context.contentResolver
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        var added = 0
        for (uri in uris) {
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            // Real filename + size from MediaStore (lastPathSegment is just a
            // numeric row id). Skip sub-10KB files: launcher icons and other
            // system images that live in MediaStore but aren't camera media.
            var displayName: String? = null
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { displayName = c.getString(0); size = c.getLong(1) }
                }
            }
            if (size in 0..10_000) continue
            val name = displayName
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "upload-${System.currentTimeMillis()}"
            val body = object : RequestBody() {
                override fun contentType() = mime.toMediaTypeOrNull()
                override fun writeTo(sink: BufferedSink) {
                    resolver.openInputStream(uri)?.use { input ->
                        sink.writeAll(input.source())
                    }
                }
            }
            builder.addFormDataPart("file_$added", name, body)
            added += 1
        }
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val req = Request.Builder().url(base + "api/upload").post(builder.build()).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("upload failed: HTTP ${resp.code}")
        }
        added
    }
}
