package com.buildapp.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.Urls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosApp(vm: GalleryViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Photos", fontSize = 18.sp)
                        Text(
                            text = "${state.items.size}/${state.total} loaded · ${state.itemsIndexed} indexed",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.rescan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null && state.items.isEmpty() -> ErrorView(
                    error = state.error!!,
                    serverUrl = state.serverUrl,
                    onRetry = { vm.refresh() },
                    onSettings = { showSettings = true },
                )
                state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> Gallery(
                    items = state.items,
                    serverUrl = state.serverUrl,
                    onItemClick = { selected = it },
                    onLoadMore = { vm.loadNext() },
                    loadingMore = state.loading,
                )
            }
        }
    }

    selected?.let { item ->
        ViewerDialog(
            item = item,
            serverUrl = state.serverUrl,
            onDismiss = { selected = null },
        )
    }

    if (showSettings) {
        SettingsDialog(
            current = state.serverUrl,
            onDismiss = { showSettings = false },
            onSave = { vm.setServerUrl(it); showSettings = false },
        )
    }
}

@Composable
private fun Gallery(
    items: List<MediaItem>,
    serverUrl: String,
    onItemClick: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(items.size, gridState) {
        // Trigger load-more when we get near the end
        snapshotLastVisible(gridState)?.let { last ->
            if (last >= items.size - 20) onLoadMore()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, m -> m.id }) { idx, m ->
            Tile(item = m, serverUrl = serverUrl, onClick = { onItemClick(m) })
            if (idx >= items.size - 12 && !loadingMore) {
                LaunchedEffect(idx) { onLoadMore() }
            }
        }
    }
}

private fun snapshotLastVisible(state: androidx.compose.foundation.lazy.grid.LazyGridState): Int? {
    return state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
}

@Composable
private fun Tile(item: MediaItem, serverUrl: String, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Color(0xFF1A1A1C))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(Urls.thumb(serverUrl, item.id))
                .crossfade(true)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.kind == "video") {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0xAA000000), shape = androidx.compose.foundation.shape.CircleShape)
                    .padding(2.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorView(error: String, serverUrl: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't reach server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(serverUrl, color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Text(error, color = Color(0xFFEF5350), fontSize = 12.sp)
        Spacer(Modifier.size(16.dp))
        Row {
            TextButton(onClick = onSettings) { Text("Server settings") }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun SettingsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server URL") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("http://host:port") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
