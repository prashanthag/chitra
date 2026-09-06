package com.buildapp.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import com.buildapp.photos.api.Album
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.NewAlbumBody
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.api.Urls
import com.buildapp.photos.api.UserAlbum
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    serverUrl: String,
    onBack: () -> Unit,
    onAlbumSelected: (Album) -> Unit,
    onUserAlbumSelected: (UserAlbum) -> Unit = {},
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var mine by remember { mutableStateOf<List<UserAlbum>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var newAlbum by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverUrl) {
        try {
            mine = api.userAlbums()
            albums = api.albums()
        } catch (e: Exception) { error = e.message }
    }
    if (newAlbum) {
        NewAlbumDialog(onDismiss = { newAlbum = false }) { name ->
            scope.launch {
                try {
                    val a = api.createUserAlbum(NewAlbumBody(name)).album
                    newAlbum = false
                    onUserAlbumSelected(a)
                } catch (_: Exception) {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albums", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Text("Error: $error", color = Color(0xFFEF5350))
                albums == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Manual albums first (any photo, any folder), then the
                    // read-only folder albums derived from the library root.
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("My albums") }
                    item(key = "new") {
                        Column(Modifier.clickable { newAlbum = true }) {
                            Box(
                                Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1A1A1C)),
                                contentAlignment = Alignment.Center,
                            ) { Text("+", fontSize = 36.sp, color = Color.Gray) }
                            Text("New album", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(mine, key = { "u${it.id}" }) { a ->
                        UserAlbumTile(a, serverUrl, onClick = { onUserAlbumSelected(a) })
                    }
                    val phone = albums!!.filter { it.folder != null }
                    if (phone.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Phone folders") }
                        items(phone, key = { it.key }) { a ->
                            AlbumTile(a, serverUrl, api, onClick = { onAlbumSelected(a) })
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Folders") }
                    items(albums!!.filter { it.folder == null }, key = { it.key }) { a ->
                        AlbumTile(a, serverUrl, api, onClick = { onAlbumSelected(a) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.Gray, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun NewAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UserAlbumTile(album: UserAlbum, serverUrl: String, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1C)),
        ) {
            album.cover?.let { id ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(Urls.thumb(serverUrl, id)).crossfade(true).build(),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(album.name, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall)
        Text(
            "${album.count} items" + (if (album.locked) " · locked" else if (album.shareToken != null) " · shared" else ""),
            color = Color.Gray, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A manual album: static list from /api/user_albums/<id>/media, with share link and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserAlbumScreen(
    serverUrl: String,
    album: UserAlbum,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onItemClick: (List<MediaItem>, Int) -> Unit,
    reloadKey: Int = 0,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(album.id) { mutableStateOf<List<MediaItem>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(album.id, reloadKey) {
        try { items = api.userAlbumMedia(album.id) } catch (_: Exception) { items = emptyList() }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete album?") },
            text = { Text("The photos stay in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { api.deleteUserAlbum(album.id); onDeleted() } catch (_: Exception) {}
                        confirmDelete = false
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${album.name} · ${items?.size ?: album.count}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = {
                        // Mint the public link and hand it to the share sheet.
                        scope.launch {
                            try {
                                val r = api.shareUserAlbum(album.id)
                                val url = (if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl) + r.url
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Share album link"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Icon(Icons.Default.Share, contentDescription = "Share link") }
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                if (album.locked) api.unlockUserAlbum(album.id) else api.lockUserAlbum(album.id)
                                onDeleted()   // leave the screen: the album has moved in or out of the Locked folder
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, if (album.locked) "Unlock the Locked folder first" else "Sign in to lock", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Icon(if (album.locked) Icons.Default.LockOpen else Icons.Default.Lock, contentDescription = if (album.locked) "Unlock album" else "Lock album") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete album") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Empty album. Open a photo and tap “Add to album”.", color = Color.Gray)
                }
                else -> Gallery(
                    items = list,
                    serverUrl = serverUrl,
                    onItemClick = { m -> onItemClick(list, list.indexOfFirst { it.id == m.id }.coerceAtLeast(0)) },
                    onLoadMore = {},
                )
            }
        }
    }
}

@Composable
private fun AlbumTile(album: Album, serverUrl: String, api: PhotoApi, onClick: () -> Unit) {
    var coverId by remember(album.key) { mutableStateOf<String?>(album.cover) }
    LaunchedEffect(album.key) {
        if (coverId != null) return@LaunchedEffect
        try {
            val resp = api.media(page = 1, perPage = 1, album = album.album, folder = album.folder)
            coverId = resp.items.firstOrNull()?.id
        } catch (_: Exception) {}
    }
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1C)),
        ) {
            coverId?.let { id ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Urls.thumb(serverUrl, id))
                        .crossfade(true)
                        .build(),
                    contentDescription = album.album,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            album.label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "${album.count} items" + (album.device?.let { " · $it" } ?: ""),
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumMediaScreen(
    serverUrl: String,
    album: Album,
    onBack: () -> Unit,
    onItemClick: (List<MediaItem>, Int) -> Unit,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var items by remember(album.key) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember(album.key) { mutableStateOf(0) }
    var loading by remember(album.key) { mutableStateOf(false) }
    var endReached by remember(album.key) { mutableStateOf(false) }
    var loadTick by remember(album.key) { mutableStateOf(0) }

    LaunchedEffect(album.key, loadTick) {
        if (loading || endReached) return@LaunchedEffect
        loading = true
        try {
            val resp = api.media(page = page + 1, perPage = 80, album = album.album, folder = album.folder)
            items = items + resp.items
            page += 1
            if (resp.items.size < resp.perPage) endReached = true
        } catch (_: Exception) {}
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${album.label} · ${album.count}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Same sectioned grid as the main gallery: month/year headers and
            // infinite scroll through every page of the album.
            Gallery(
                items = items,
                serverUrl = serverUrl,
                onItemClick = { m ->
                    onItemClick(items, items.indexOfFirst { it.id == m.id }.coerceAtLeast(0))
                },
                onLoadMore = { if (!loading && !endReached) loadTick++ },
            )
        }
    }
}
