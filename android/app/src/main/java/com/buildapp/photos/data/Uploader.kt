package com.buildapp.photos.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

/**
 * Talks to /api/upload and /api/upload/check. One request per file: a lost
 * Wi-Fi packet mid-batch used to void the whole batch (the server only saves
 * once the entire multipart body has arrived), and per-file requests give
 * real progress.
 */
object Uploader {
    data class FileResult(
        val name: String,
        val ok: Boolean,
        val duplicate: Boolean = false,
        val serverId: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class CheckFile(val name: String, val size: Long, val hash: String? = null)

    @Serializable
    private data class CheckRequest(val files: List<CheckFile>)

    @Serializable
    private data class CheckResponse(val ok: Boolean, val exists: List<Boolean>, val ids: List<String?>)

    @Serializable
    private data class UploadItem(
        val id: String? = null,
        val name: String,
        val indexed: Boolean = false,
        val duplicate: Boolean = false,
        val reason: String? = null,
    )

    @Serializable
    private data class UploadResponse(val ok: Boolean, val count: Int, val items: List<UploadItem>)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = OkHttpClient.Builder()
        .addInterceptor(com.buildapp.photos.api.Auth.interceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    fun base(serverUrl: String) = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"

    /**
     * Which of these files does the server already have? With a content
     * hash the match is by bytes regardless of name; without one the server
     * falls back to name + size.
     */
    fun check(serverUrl: String, files: List<CheckFile>): Result<List<Boolean>> = runCatching {
        if (files.isEmpty()) return@runCatching emptyList()
        val body = json.encodeToString(CheckRequest.serializer(), CheckRequest(files))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(base(serverUrl) + "api/upload/check").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("check failed: HTTP ${resp.code}")
            json.decodeFromString(CheckResponse.serializer(), resp.body!!.string()).exists
        }
    }

    /**
     * Upload a single content:// item. Never throws; the result says what
     * happened. `folder` is the phone folder the file lives in (Camera,
     * WhatsApp Images...) and `device` this phone's name: the server files
     * uploads by them, and uses the phone as the camera for EXIF-less files.
     */
    fun uploadOne(
        context: Context, serverUrl: String, uri: Uri, name: String, size: Long, mime: String?,
        folder: String? = null, device: String? = DeviceInfo.name(context), deviceMake: String? = DeviceInfo.make(),
    ): FileResult {
        val src = Source(folder, device, deviceMake)
        val first = uploadOnce(context, serverUrl, uri, name, size, mime, src)
        // MediaStore's SIZE can lag behind the file (a still-being-written
        // camera burst, an edited-in-place image); OkHttp then aborts with a
        // length mismatch. Send again with chunked encoding, which doesn't
        // need to know the length up front.
        val lengthMismatch = !first.ok && size > 0 &&
            (first.error?.contains("expected", ignoreCase = true) == true ||
                first.error?.contains("Content-Length", ignoreCase = true) == true)
        return if (lengthMismatch) uploadOnce(context, serverUrl, uri, name, -1, mime, src) else first
    }

    private data class Source(val folder: String?, val device: String?, val deviceMake: String?)

    private fun uploadOnce(context: Context, serverUrl: String, uri: Uri, name: String, size: Long, mime: String?, src: Source): FileResult {
        val resolver = context.contentResolver
        val body = object : RequestBody() {
            override fun contentType() = (mime ?: "application/octet-stream").toMediaTypeOrNull()
            // Known length => Content-Length instead of chunked encoding, which
            // lets the server reject oversize early and proxies buffer less.
            override fun contentLength(): Long = if (size > 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: error("cannot open $uri")
                input.use { sink.writeAll(it.source()) }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            src.device?.let { addFormDataPart("device", it) }
            src.deviceMake?.let { addFormDataPart("device_make", it) }
            src.folder?.let { addFormDataPart("folder", it) }
            addFormDataPart("file", name, body)
        }.build()
        val req = Request.Builder().url(base(serverUrl) + "api/upload").post(multipart).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return FileResult(name, ok = false, error = "HTTP ${resp.code}")
                val parsed = json.decodeFromString(UploadResponse.serializer(), resp.body!!.string())
                val item = parsed.items.firstOrNull()
                    ?: return FileResult(name, ok = false, error = "empty response")
                if (!item.indexed) FileResult(name, ok = false, error = item.reason ?: "not indexed")
                else FileResult(item.name, ok = true, duplicate = item.duplicate, serverId = item.id)
            }
        } catch (e: Exception) {
            FileResult(name, ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Resolve display name, size and mime for any content:// URI (photo picker or MediaStore). */
    fun describe(context: Context, uri: Uri): Triple<String, Long, String?> {
        val resolver = context.contentResolver
        var displayName: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0)
                    size = c.getLong(1)
                }
            }
        }
        val name = displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "upload-${System.currentTimeMillis()}"
        return Triple(name, size, resolver.getType(uri))
    }

    /**
     * Manual "pick and upload" path. Uploads one file at a time and reports
     * progress after each; returns every per-file result.
     */
    fun uploadAll(
        context: Context,
        serverUrl: String,
        uris: List<Uri>,
        onProgress: (done: Int, total: Int, last: FileResult) -> Unit = { _, _, _ -> },
    ): List<FileResult> {
        val results = mutableListOf<FileResult>()
        uris.forEachIndexed { i, uri ->
            val (name, size, mime) = describe(context, uri)
            val r = uploadOne(context, serverUrl, uri, name, size, mime)
            results += r
            onProgress(i + 1, uris.size, r)
        }
        return results
    }
}
