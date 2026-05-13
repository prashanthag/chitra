package com.buildapp.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.buildapp.photos.api.Cluster
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.Urls
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private sealed interface Route {
    data object Gallery : Route
    data object People : Route
    data class ClusterMedia(val cluster: Cluster) : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosApp(vm: GalleryViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<Route>(Route.Gallery) }

    when (val r = route) {
        is Route.People -> {
            PeopleScreen(
                serverUrl = state.serverUrl,
                onBack = { route = Route.Gallery },
                onClusterSelected = { route = Route.ClusterMedia(it) },
            )
            return
        }
        is Route.ClusterMedia -> {
            ClusterMediaScreen(
                serverUrl = state.serverUrl,
                cluster = r.cluster,
                onBack = { route = Route.People },
                onItemClick = { selected = it },
            )
            selected?.let { item ->
                ViewerDialog(
                    item = item,
                    serverUrl = state.serverUrl,
                    onDismiss = { selected = null },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                )
            }
            return
        }
        else -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Photos", fontSize = 18.sp)
                        Text(
                            text = subtitleFor(state),
                            fontSize = 11.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { route = Route.People }) {
                        Icon(Icons.Default.Face, contentDescription = "People")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showSearch) {
                SearchBar(
                    initial = state.query,
                    onApply = { vm.setQuery(it) },
                    onClose = { showSearch = false; vm.setQuery("") },
                )
            }
            FilterRow(current = state.filter, onSelect = { vm.setFilter(it) })
            Box(Modifier.fillMaxSize()) {
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
                    state.items.isEmpty() && !state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("No items", color = Color.Gray)
                    }
                    else -> Gallery(
                        items = state.items,
                        serverUrl = state.serverUrl,
                        onItemClick = { selected = it },
                        onLoadMore = { vm.loadNext() },
                    )
                }
            }
        }
    }

    selected?.let { item ->
        val live = state.items.firstOrNull { it.id == item.id } ?: item
        ViewerDialog(
            item = live,
            serverUrl = state.serverUrl,
            onDismiss = { selected = null },
            onToggleFavorite = { vm.toggleFavorite(it) },
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

private fun subtitleFor(state: GalleryState): String {
    val parts = mutableListOf<String>()
    parts += "${state.items.size}/${state.total}"
    if (state.itemsIndexed > 0) parts += "${state.itemsIndexed} indexed"
    if (state.query.isNotBlank()) parts += "q=\"${state.query}\""
    return parts.joinToString(" · ")
}

@Composable
private fun SearchBar(initial: String, onApply: (String) -> Unit, onClose: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onApply(it) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search filename…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
        )
        TextButton(onClick = onClose) { Text("Done") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(current: Filter, onSelect: (Filter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Filter.values().forEach { f ->
            FilterChip(
                selected = current == f,
                onClick = { onSelect(f) },
                label = { Text(f.name.lowercase().replaceFirstChar { it.titlecase() }) },
            )
        }
    }
}

@Composable
private fun Gallery(
    items: List<MediaItem>,
    serverUrl: String,
    onItemClick: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= items.size - 20 && items.isNotEmpty() }
            .collect { onLoadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        contentPadding = PaddingValues(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, m -> m.id }) { _, m ->
            Tile(item = m, serverUrl = serverUrl, onClick = { onItemClick(m) })
        }
    }
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
            model = ImageRequest.Builder(LocalContext.current)
                .data(Urls.thumb(serverUrl, item.id))
                .crossfade(true)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.kind == "video") {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
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
        if (item.favorite == 1) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "Favorite",
                tint = Color(0xFFE91E63),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(14.dp),
            )
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
