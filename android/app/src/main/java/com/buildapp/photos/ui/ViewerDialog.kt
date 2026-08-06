package com.buildapp.photos.ui

import android.content.Intent
import android.view.ViewGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    item: MediaItem,
    serverUrl: String,
    onDismiss: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onTrash: (MediaItem) -> Unit = {},
    onArchive: (MediaItem) -> Unit = {},
    onRestore: (MediaItem) -> Unit = {},
    onRotate: (MediaItem) -> Unit = {},
    onEdit: (MediaItem) -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xEE000000))) {
            if (item.kind == "video") {
                VideoPlayer(url = Urls.stream(serverUrl, item.id))
            } else {
                val needsTranscode = item.ext.equals(".heic", true) || item.ext.equals(".heif", true)
                AsyncImage(
                    model = Urls.full(serverUrl, item.id, asJpeg = needsTranscode),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val context = LocalContext.current
            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                IconButton(onClick = {
                    // Download the actual file and hand it to the share sheet,
                    // so WhatsApp & co. receive the photo/video itself (a LAN
                    // URL would be dead outside this network).
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
        }
    }
}

@Composable
private fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
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
        modifier = Modifier.fillMaxSize(),
    )
}
