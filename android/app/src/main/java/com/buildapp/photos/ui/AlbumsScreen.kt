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
import com.buildapp.photos.api.Album
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.api.Urls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    serverUrl: String,
    onBack: () -> Unit,
    onAlbumSelected: (Album) -> Unit,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl) {
        try { albums = api.albums() } catch (e: Exception) { error = e.message }
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
                    items(albums!!, key = { it.album }) { a ->
                        AlbumTile(a, serverUrl, api, onClick = { onAlbumSelected(a) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(album: Album, serverUrl: String, api: PhotoApi, onClick: () -> Unit) {
    var coverId by remember(album.album) { mutableStateOf<String?>(null) }
    LaunchedEffect(album.album) {
        try {
            val resp = api.media(page = 1, perPage = 1, album = album.album)
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
            album.album,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "${album.count} items",
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
    onItemClick: (MediaItem) -> Unit,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var items by remember(album.album) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember(album.album) { mutableStateOf(0) }
    var loading by remember(album.album) { mutableStateOf(false) }
    var endReached by remember(album.album) { mutableStateOf(false) }
    var loadTick by remember(album.album) { mutableStateOf(0) }

    LaunchedEffect(album.album, loadTick) {
        if (loading || endReached) return@LaunchedEffect
        loading = true
        try {
            val resp = api.media(page = page + 1, perPage = 80, album = album.album)
            items = items + resp.items
            page += 1
            if (resp.items.size < resp.perPage) endReached = true
        } catch (_: Exception) {}
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${album.album} · ${album.count}") },
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
                onItemClick = onItemClick,
                onLoadMore = { if (!loading && !endReached) loadTick++ },
            )
        }
    }
}
