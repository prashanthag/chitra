package com.buildapp.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.buildapp.photos.api.Album
import com.buildapp.photos.api.IdsBody
import com.buildapp.photos.api.NewAlbumBody
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.api.UserAlbum
import com.buildapp.photos.api.Cluster
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.Urls
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private sealed interface Route {
    data object Gallery : Route
    data object People : Route
    data class ClusterMedia(val cluster: Cluster) : Route
    data object Albums : Route
    data class AlbumMedia(val album: Album) : Route
    data class UserAlbumMedia(val album: UserAlbum) : Route
    data object Map : Route
    data class Editor(val item: MediaItem) : Route
    data object Settings : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosApp(vm: GalleryViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var liveViewerIndex by remember { mutableStateOf<Int?>(null) }
    var staticViewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<Route>(Route.Gallery) }
    // "Add to album" from any viewer opens one picker; the album screen
    // reloads when the picker changes something.
    var albumPickFor by remember { mutableStateOf<MediaItem?>(null) }
    var albumsChanged by remember { mutableStateOf(0) }
    albumPickFor?.let { item ->
        AddToAlbumDialog(serverUrl = state.serverUrl, item = item,
            onDismiss = { albumPickFor = null }, onChanged = { albumsChanged++ })
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // System back walks the UI hierarchy (viewer → overlays → sub-screen →
    // gallery) instead of killing the app; only exits from the home gallery.
    androidx.activity.compose.BackHandler(
        enabled = liveViewerIndex != null || staticViewer != null || showSearch || route != Route.Gallery
    ) {
        when {
            liveViewerIndex != null -> liveViewerIndex = null
            staticViewer != null -> staticViewer = null
            // Sub-screens first: the search bar belongs to the gallery, so a
            // search left open must not swallow the first back press elsewhere.
            route is Route.Editor -> route = Route.Gallery
            route is Route.ClusterMedia -> route = Route.People
            route is Route.AlbumMedia -> route = Route.Albums
            route is Route.UserAlbumMedia -> route = Route.Albums
            route != Route.Gallery -> route = Route.Gallery
            showSearch -> showSearch = false
        }
    }
    // The photo picker caps multi-select at MediaStore.getPickImagesMaxLimit()
    // (100 on Android 13+); 50 keeps one manual batch reasonable.
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50),
    ) { uris ->
        if (uris.isNotEmpty()) vm.uploadPicked(uris)
    }
    // When a manual upload finishes, summarize it once and clear the bar.
    LaunchedEffect(state.upload?.running) {
        val u = state.upload
        if (u != null && !u.running) {
            snackbar.showSnackbar(u.summary)
            vm.clearUpload()
        }
    }

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
                onItemClick = { l, i -> staticViewer = l to i },
            )
            staticViewer?.let { (list, idx) ->
                ViewerDialog(
                    items = list,
                    initialIndex = idx,
                    serverUrl = state.serverUrl,
                    onDismiss = { staticViewer = null },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                    onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) },
                    onRestore = { vm.restore(it) },
                )
            }
            return
        }
        is Route.Albums -> {
            AlbumsScreen(
                serverUrl = state.serverUrl,
                onBack = { route = Route.Gallery },
                onAlbumSelected = { route = Route.AlbumMedia(it) },
                onUserAlbumSelected = { route = Route.UserAlbumMedia(it) },
            )
            return
        }
        is Route.UserAlbumMedia -> {
            UserAlbumScreen(
                serverUrl = state.serverUrl,
                album = r.album,
                onBack = { route = Route.Albums },
                onDeleted = { route = Route.Albums },
                onItemClick = { l, i -> staticViewer = l to i },
                reloadKey = albumsChanged,
            )
            staticViewer?.let { (list, idx) ->
                ViewerDialog(
                    items = list,
                    initialIndex = idx,
                    serverUrl = state.serverUrl,
                    onDismiss = { staticViewer = null },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                    onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) },
                    onRestore = { vm.restore(it) },
                    onAddToAlbum = { albumPickFor = it },
                )
            }
            return
        }
        is Route.AlbumMedia -> {
            AlbumMediaScreen(
                serverUrl = state.serverUrl,
                album = r.album,
                onBack = { route = Route.Albums },
                onItemClick = { l, i -> staticViewer = l to i },
            )
            staticViewer?.let { (list, idx) ->
                ViewerDialog(
                    items = list,
                    initialIndex = idx,
                    serverUrl = state.serverUrl,
                    onDismiss = { staticViewer = null },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                    onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) },
                    onRestore = { vm.restore(it) },
                    onAddToAlbum = { albumPickFor = it },
                )
            }
            return
        }
        is Route.Map -> {
            MapScreen(
                serverUrl = state.serverUrl,
                onBack = { route = Route.Gallery },
                onMarkerClick = { loc ->
                    val hit = state.items.indexOfFirst { it.id == loc.id }
                    if (hit >= 0) liveViewerIndex = hit
                },
            )
            liveViewerIndex?.let { idx ->
                ViewerDialog(
                    items = state.items,
                    initialIndex = idx,
                    serverUrl = state.serverUrl,
                    onDismiss = { liveViewerIndex = null },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                )
            }
            return
        }
        is Route.Editor -> {
            EditorScreen(
                item = r.item,
                serverUrl = state.serverUrl,
                onBack = { route = Route.Gallery },
                onSaved = { route = Route.Gallery; vm.refresh() },
            )
            return
        }
        is Route.Settings -> {
            SettingsScreen(
                serverUrl = state.serverUrl,
                onBack = { route = Route.Gallery },
                onServerUrlSaved = { vm.setServerUrl(it) },
            )
            return
        }
        else -> Unit
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // One batch at a time: a pick made mid-upload used to be
                // silently discarded.
                if (state.upload?.running == true) {
                    scope.launch { snackbar.showSnackbar("Still uploading the last batch") }
                } else pickMedia.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    ),
                )
            }) {
                Icon(Icons.Default.Add, contentDescription = "Upload")
            }
        },
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
                    IconButton(onClick = { route = Route.Map }) {
                        Icon(Icons.Default.Map, contentDescription = "Map")
                    }
                    IconButton(onClick = { route = Route.Albums }) {
                        Icon(Icons.Default.PhotoAlbum, contentDescription = "Albums")
                    }
                    IconButton(onClick = { route = Route.People }) {
                        Icon(Icons.Default.Face, contentDescription = "People")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { route = Route.Settings }) {
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
                    semantic = state.semantic,
                    onApply = { vm.setQuery(it) },
                    onSemanticChange = { vm.setSemantic(it) },
                    onClose = { showSearch = false; vm.setQuery("") },
                )
            }
            FilterRow(current = state.filter, onSelect = { vm.setFilter(it) })
            state.upload?.let { u ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        if (u.running) "Uploading ${u.done}/${u.total}…" else u.summary,
                        fontSize = 12.sp, color = Color.Gray,
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (u.total == 0) 0f else u.done.toFloat() / u.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            state.memories?.takeIf { it.groups.isNotEmpty() }?.let { mem ->
                MemoriesRow(memories = mem, serverUrl = state.serverUrl, onClick = { staticViewer = listOf(it) to 0 })
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.error != null && state.items.isEmpty() -> ErrorView(
                        error = state.error!!,
                        serverUrl = state.serverUrl,
                        onRetry = { vm.refresh() },
                        onSettings = { route = Route.Settings },
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
                        uploadsFeed = state.filter == Filter.UPLOADS,
                        onItemClick = { m -> liveViewerIndex = state.items.indexOfFirst { it.id == m.id }.coerceAtLeast(0) },
                        onLoadMore = { vm.loadNext() },
                    )
                }
            }
        }
    }

    liveViewerIndex?.let { idx ->
        ViewerDialog(
            items = state.items,
            initialIndex = idx,
            serverUrl = state.serverUrl,
            onDismiss = { liveViewerIndex = null },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onTrash = { vm.trash(it) },
            onArchive = { vm.archive(it) },
            onRestore = { vm.restore(it) },
            onRotate = { vm.rotate(it) },
            onEdit = { route = Route.Editor(it); liveViewerIndex = null },
            onAddToAlbum = { albumPickFor = it },
        )
    }
    staticViewer?.let { (list, idx) ->
        ViewerDialog(
            items = list,
            initialIndex = idx,
            serverUrl = state.serverUrl,
            onDismiss = { staticViewer = null },
            onToggleFavorite = { vm.toggleFavorite(it) },
        )
    }

}

/** Picker listing every manual album with a check for the ones this item is in; tap toggles. */
@Composable
private fun AddToAlbumDialog(serverUrl: String, item: MediaItem, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<UserAlbum>?>(null) }
    var newName by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(item.id, tick) {
        albums = try { api.userAlbums(mediaId = item.id) } catch (_: Exception) { emptyList() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                val list = albums
                when {
                    list == null -> CircularProgressIndicator()
                    list.isEmpty() -> Text("No albums yet. Create one below.", color = Color.Gray)
                    else -> list.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    try {
                                        if (a.contains == true) api.removeFromUserAlbum(a.id, IdsBody(listOf(item.id)))
                                        else api.addToUserAlbum(a.id, IdsBody(listOf(item.id)))
                                        onChanged(); tick++
                                    } catch (_: Exception) {}
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.name, modifier = Modifier.weight(1f))
                            Text("${a.count}", color = Color.Gray, fontSize = 12.sp)
                            if (a.contains == true) Text("  ✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text("New album") }, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val n = newName.trim(); if (n.isEmpty()) return@TextButton
                        scope.launch {
                            try {
                                api.createUserAlbum(NewAlbumBody(n, listOf(item.id)))
                                newName = ""; onChanged(); tick++
                            } catch (_: Exception) {}
                        }
                    }) { Text("Create") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun subtitleFor(state: GalleryState): String {
    val parts = mutableListOf<String>()
    parts += "${state.items.size}/${state.total}"
    if (state.itemsIndexed > 0) parts += "${state.itemsIndexed} indexed"
    if (state.query.isNotBlank()) parts += "q=\"${state.query}\""
    return parts.joinToString(" · ")
}

@Composable
private fun SearchBar(
    initial: String,
    semantic: Boolean,
    onApply: (String) -> Unit,
    onSemanticChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onApply(it) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (semantic) "Try: dog, beach, sunset…" else "Search filename…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
            TextButton(onClick = onClose) { Text("Done") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(
                selected = !semantic,
                onClick = { onSemanticChange(false) },
                label = { Text("Filename") },
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = semantic,
                onClick = { onSemanticChange(true) },
                label = { Text("Smart (CLIP)") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(current: Filter, onSelect: (Filter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Filter.values().forEach { f ->
            FilterChip(
                selected = current == f,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
            )
        }
    }
}

/** "Uploaded 2 September 2026": the same wording the web client's Recently uploaded view uses. */
internal fun uploadDayLabel(epoch: Double?): String {
    if (epoch == null || epoch <= 0) return "Upload date unknown"
    val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
    return "Uploaded " + fmt.format(java.util.Date((epoch * 1000).toLong()))
}

internal fun monthLabel(epoch: Double?): String {
    if (epoch == null || epoch <= 0) return "Undated"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = (epoch * 1000).toLong() }
    val months = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    return "${months[cal.get(java.util.Calendar.MONTH)]} ${cal.get(java.util.Calendar.YEAR)}"
}

@Composable
internal fun Gallery(
    items: List<MediaItem>,
    serverUrl: String,
    onItemClick: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
    uploadsFeed: Boolean = false,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= items.size - 20 && items.isNotEmpty() }
            .collect { onLoadMore() }
    }
    // Pre-compute section boundaries so we can insert full-width headers.
    // The Uploads feed is ordered by upload time, so it sections by upload
    // day; sectioning it by capture month would fragment into one header per
    // run of items.
    val sectioned = remember(items, uploadsFeed) {
        val out = mutableListOf<Pair<String?, MediaItem>>()
        var lastLabel: String? = null
        for (m in items) {
            val lbl = if (uploadsFeed) uploadDayLabel(m.addedAt) else monthLabel(m.takenAt)
            if (lbl != lastLabel) {
                out += lbl to m  // first item of new section also carries the label
                lastLabel = lbl
            } else {
                out += null to m
            }
        }
        out
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        contentPadding = PaddingValues(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sectioned.forEachIndexed { idx, (label, m) ->
            if (label != null) {
                item(
                    key = "h-$label-${m.id}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = "t-${m.id}") {
                Tile(item = m, serverUrl = serverUrl, onClick = { onItemClick(m) })
            }
        }
    }
}

@Composable
private fun MemoriesRow(
    memories: com.buildapp.photos.api.Memories,
    serverUrl: String,
    onClick: (MediaItem) -> Unit,
) {
    val nowYear = remember {
        try { java.time.Year.now().value } catch (_: Throwable) { 2026 }
    }
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(end = 4.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    "Memories",
                    color = Color.White,
                    fontSize = 14.sp,
                )
                Text(
                    memories.monthDay,
                    color = Color.Gray,
                    fontSize = 10.sp,
                )
            }
        }
        for (group in memories.groups) {
            val years = nowYear - (group.year.toIntOrNull() ?: nowYear)
            val item0 = group.items.firstOrNull()
            if (item0 != null) {
                item(key = "mem-${group.year}") {
                    Box(
                        Modifier
                            .size(width = 84.dp, height = 110.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A1A1C))
                            .clickable { onClick(item0) },
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(Urls.thumb(serverUrl, item0.id, item0.editVersion))
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Color(0xAA000000))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        ) {
                            Text(
                                if (years > 0) "$years yr ago" else group.year,
                                color = Color.White,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
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
                .data(Urls.thumb(serverUrl, item.id, item.editVersion))
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
