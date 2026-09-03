package com.buildapp.photos.ui

import android.content.Intent
import android.view.ViewGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToPhotos
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.Urls

@Composable
fun ViewerDialog(
    items: List<MediaItem>,
    initialIndex: Int,
    serverUrl: String,
    onDismiss: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onTrash: (MediaItem) -> Unit = {},
    onArchive: (MediaItem) -> Unit = {},
    onRestore: (MediaItem) -> Unit = {},
    onRotate: (MediaItem) -> Unit = {},
    onEdit: (MediaItem) -> Unit = {},
    onAddToAlbum: ((MediaItem) -> Unit)? = null,
) {
    if (items.isEmpty()) { onDismiss(); return }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, items.size - 1),
        ) { items.size }
        val item = items[pagerState.currentPage.coerceIn(0, items.size - 1)]

        Box(Modifier.fillMaxSize().background(Color(0xEE000000))) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val m = items[page]
                if (m.kind == "video") {
                    // Only the settled page gets a live player, so swiping
                    // never spins up multiple decoders.
                    if (pagerState.settledPage == page) {
                        VideoPlayer(url = Urls.stream(serverUrl, m.id))
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            AsyncImage(
                                model = Urls.thumb(serverUrl, m.id, m.editVersion, w = 1024),
                                contentDescription = m.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                } else {
                    // The cached 2048px preview: EXIF-rotated, HEIC/TIFF
                    // flattened, immutable-cached. The original (full?as=jpeg)
                    // was re-encoded by the server on every swipe.
                    AsyncImage(
                        model = Urls.preview(serverUrl, m.id, m.editVersion),
                        contentDescription = m.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            val context = LocalContext.current
            var showInfo by remember(item.id) { mutableStateOf(false) }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                val scope = rememberCoroutineScope()
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White)
                }
                if (onAddToAlbum != null && item.trashedAt == null) {
                    IconButton(onClick = { onAddToAlbum(item) }) {
                        Icon(Icons.Default.AddToPhotos, contentDescription = "Add to album", tint = Color.White)
                    }
                }
                IconButton(onClick = {
                    // Download the actual file and hand it to the share sheet.
                    scope.launch {
                        try {
                            val heic = item.ext.equals(".heic", true) || item.ext.equals(".heif", true)
                            val url = Urls.full(serverUrl, item.id, asJpeg = heic)
                            val name = if (heic) item.name.replace(Regex("\\.hei[cf]$", RegexOption.IGNORE_CASE), ".jpg") else item.name
                            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                val f = java.io.File(dir, name)
                                java.net.URL(url).openStream().use { input ->
                                    f.outputStream().use { input.copyTo(it) }
                                }
                                f
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "com.buildapp.photos.fileprovider", file)
                            val mime = item.mime
                                ?: if (item.kind == "video") "video/*" else "image/*"
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = if (heic) "image/jpeg" else mime
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri("", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share via").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(onClick = { onToggleFavorite(item) }) {
                    Icon(
                        if (item.favorite == 1) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (item.favorite == 1) Color(0xFFE91E63) else Color.White,
                    )
                }
                if (item.trashedAt != null) {
                    IconButton(onClick = { onRestore(item); onDismiss() }) {
                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = Color.White)
                    }
                } else {
                    if (item.kind == "photo") {
                        IconButton(onClick = { onEdit(item) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White)
                        }
                        IconButton(onClick = { onRotate(item) }) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { onArchive(item); onDismiss() }) {
                        Icon(
                            if (item.archived == 1) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = "Archive",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { onTrash(item); onDismiss() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Trash", tint = Color.White)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            if (showInfo) {
                // The list API omits GPS/camera columns — fetch the full record.
                var detail by remember(item.id) { mutableStateOf(item) }
                androidx.compose.runtime.LaunchedEffect(item.id) {
                    try {
                        detail = com.buildapp.photos.api.PhotoApi.create(serverUrl).meta(item.id)
                            .copy(favorite = item.favorite)
                    } catch (_: Exception) {}
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .background(Color(0xDD16161A), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    val rows = buildList {
                        add("Name" to detail.name)
                        detail.takenAt?.takeIf { it > 0 }?.let {
                            add("Taken" to java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date((it * 1000).toLong())))
                        } ?: add("Taken" to "Date unknown")
                        val cam = listOfNotNull(detail.cameraMake, detail.cameraModel).joinToString(" ")
                        if (cam.isNotBlank()) add("Camera" to cam)
                        val ex = detail.exposure.orEmpty()
                        ex["lens"]?.let { add("Lens" to it) }
                        ex["aperture"]?.let { add("Aperture" to it) }
                        ex["shutter"]?.let { add("Shutter" to it) }
                        ex["iso"]?.let { add("ISO" to it) }
                        ex["focal_length"]?.let { add("Focal length" to it) }
                        ex["exposure_bias"]?.let { add("Exposure bias" to it) }
                        ex["flash"]?.let { add("Flash" to it) }
                        val vi = detail.video.orEmpty()
                        vi["duration"]?.let { add("Duration" to it) }
                        vi["codec"]?.let { add("Codec" to it) }
                        vi["frame_rate"]?.let { add("Frame rate" to it) }
                        vi["bitrate"]?.let { add("Bitrate" to it) }
                        if (detail.width != null && detail.height != null) add("Resolution" to "${detail.width} × ${detail.height}")
                        detail.size?.let { add("Size" to if (it >= 1_000_000) "%.1f MB".format(it / 1e6) else "${it / 1000} KB") }
                        add("Type" to "${detail.kind} · ${detail.ext.removePrefix(".").uppercase()}")
                        detail.album?.let { add("Folder" to it) }
                        detail.place?.let { add("Place" to it) }
                        if (detail.lat != null && detail.lng != null)
                            add("Location" to "%.5f, %.5f".format(detail.lat, detail.lng))
                    }
                    rows.forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(k, color = Color(0xFF9A9AA2), fontSize = 13.sp,
                                modifier = Modifier.widthIn(min = 90.dp))
                            Text(v, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(url) { onDispose { exoPlayer.release() } }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { it.player = exoPlayer },
        modifier = Modifier.fillMaxSize(),
    )
}
