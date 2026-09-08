package com.buildapp.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.buildapp.photos.data.DebugHooks
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
    /** One of the library views that used to be a filter chip (Favorites, Videos, Trash...). */
    data class Collection(val filter: Filter) : Route
}

/** Bottom navigation destinations of the home screen. */
private enum class Tab(val label: String) { PHOTOS("Photos"), SEARCH("Search"), COLLECTIONS("Collections") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosApp(vm: GalleryViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var liveViewerIndex by remember { mutableStateOf<Int?>(null) }
    var staticViewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    // A debug launch extra (filter=uploads) opens that collection directly.
    var route by remember {
        val initial = Filter.byName(DebugHooks.initialFilter)
        mutableStateOf<Route>(if (initial != null && initial != Filter.ALL) Route.Collection(initial) else Route.Gallery)
    }
    // "Add to album" from any viewer opens one picker; the album screen
    // reloads when the picker changes something.
    var albumPickFor by remember { mutableStateOf<MediaItem?>(null) }
    var albumsChanged by remember { mutableStateOf(0) }
    albumPickFor?.let { item ->
        AddToAlbumDialog(serverUrl = state.serverUrl, item = item,
            fromAlbum = (route as? Route.UserAlbumMedia)?.album,
            onDismiss = { albumPickFor = null }, onChanged = { albumsChanged++ })
    }
    // "Move to folder" from any viewer: one chooser of the folder albums.
    var moveFor by remember { mutableStateOf<MediaItem?>(null) }
    moveFor?.let { item ->
        MoveToFolderDialog(serverUrl = state.serverUrl, item = item,
            onDismiss = { moveFor = null },
            onMoved = { vm.dropItem(item.id); albumsChanged++; staticViewer = null; liveViewerIndex = null; moveFor = null })
    }
    // Accounts: a 401 anywhere asks to sign in; the Locked folder asks for
    // the password again. Both come from the view model's flags.
    if (state.needLogin) LoginDialog(vm = vm, onDismiss = { vm.clearAuthPrompts() })
    var unlockThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (state.needUnlock || unlockThen != null) UnlockDialog(
        vm = vm,
        onDismiss = { vm.clearAuthPrompts(); unlockThen = null; if (route is Route.Collection && (route as Route.Collection).filter == Filter.LOCKED) { vm.setFilter(Filter.ALL); route = Route.Gallery } },
        onUnlocked = { vm.clearAuthPrompts(); unlockThen?.invoke(); unlockThen = null },
    )
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // System back walks the UI hierarchy (viewer → overlays → sub-screen →
    // gallery) instead of killing the app; only exits from the home gallery.
    androidx.activity.compose.BackHandler(
        enabled = liveViewerIndex != null || staticViewer != null || route != Route.Gallery || tab != Tab.PHOTOS
    ) {
        when {
            liveViewerIndex != null -> liveViewerIndex = null
            staticViewer != null -> staticViewer = null
            route is Route.Editor -> route = Route.Gallery
            route is Route.ClusterMedia -> route = Route.People
            route is Route.AlbumMedia -> route = Route.Albums
            route is Route.UserAlbumMedia -> route = Route.Albums
            route is Route.Collection -> { vm.setFilter(Filter.ALL); route = Route.Gallery; tab = Tab.COLLECTIONS }
            route != Route.Gallery -> route = Route.Gallery
            tab != Tab.PHOTOS -> { if (tab == Tab.SEARCH) vm.setQuery(""); tab = Tab.PHOTOS }
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
                    onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit,
                    onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) },
                    onRestore = { vm.restore(it) },
                    onAddToAlbum = if (vm.canEdit) { m -> albumPickFor = m } else null,
                    onLock = if (vm.isAdmin) { m -> vm.setLocked(m, locked = true); staticViewer = null } else null,
                    onMove = if (vm.canEdit) { m -> moveFor = m } else null,
                )
            }
            return
        }
        is Route.Albums -> {
            AlbumsScreen(
                canEdit = vm.canEdit,
                serverUrl = state.serverUrl,
                onBack = { route = Route.Gallery },
                onAlbumSelected = { route = Route.AlbumMedia(it) },
                // A locked album asks for the password every time it is opened.
                onUserAlbumSelected = { a -> if (a.locked) unlockThen = { route = Route.UserAlbumMedia(a) } else route = Route.UserAlbumMedia(a) },
            )
            return
        }
        is Route.UserAlbumMedia -> {
            IdleRelock(enabled = r.album.locked && staticViewer == null, onIdle = {
                vm.relock(); route = Route.Albums
                android.widget.Toast.makeText(context, "Locked album closed after a minute idle", android.widget.Toast.LENGTH_SHORT).show()
            }) {
            UserAlbumScreen(
                canEdit = vm.canEdit,
                serverUrl = state.serverUrl,
                album = r.album,
                onBack = { route = Route.Albums },
                onDeleted = { route = Route.Albums },
                onItemClick = { l, i -> staticViewer = l to i },
                reloadKey = albumsChanged,
                canLock = vm.isAdmin,
            )
            }
            staticViewer?.let { (list, idx) ->
                ViewerDialog(
                    items = list,
                    initialIndex = idx,
                    serverUrl = state.serverUrl,
                    onDismiss = { staticViewer = null },
                    onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit,
                    onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) },
                    onRestore = { vm.restore(it) },
                    onAddToAlbum = if (vm.canEdit) { m -> albumPickFor = m } else null,
                    onLock = if (vm.isAdmin) { m -> vm.setLocked(m, locked = true); staticViewer = null } else null,
                    onMove = if (vm.canEdit) { m -> moveFor = m } else null,
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
                signedIn = vm.isAdmin,
            )
            staticViewer?.let { (list, idx) ->
                ViewerDialog(
                    items = list,
                    initialIndex = idx,
                    serverUrl = state.serverUrl,
                    onDismiss = { staticViewer = null },
                    onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit,
                    onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) },
                    onRestore = { vm.restore(it) },
                    onAddToAlbum = if (vm.canEdit) { m -> albumPickFor = m } else null,
                    onLock = if (vm.isAdmin) { m -> vm.setLocked(m, locked = true); staticViewer = null } else null,
                    onMove = if (vm.canEdit) { m -> moveFor = m } else null,
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
                    onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit,
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
        is Route.Collection -> {
            LaunchedEffect(r.filter) { vm.setFilter(r.filter) }
            IdleRelock(enabled = r.filter == Filter.LOCKED && liveViewerIndex == null, onIdle = {
                vm.relock(); vm.setFilter(Filter.ALL); route = Route.Gallery; tab = Tab.COLLECTIONS
                android.widget.Toast.makeText(context, "Locked folder closed after a minute idle", android.widget.Toast.LENGTH_SHORT).show()
            }) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(r.filter.label) },
                        navigationIcon = {
                            IconButton(onClick = { vm.setFilter(Filter.ALL); route = Route.Gallery; tab = Tab.COLLECTIONS }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    GalleryBody(state = state, uploadsFeed = r.filter == Filter.UPLOADS,
                        onItemClick = { m -> liveViewerIndex = state.items.indexOfFirst { it.id == m.id }.coerceAtLeast(0) },
                        onLoadMore = { vm.loadNext() }, onRetry = { vm.refresh() }, onSettings = { route = Route.Settings })
                }
            }
            }
            liveViewerIndex?.let { idx ->
                ViewerDialog(
                    items = state.items, initialIndex = idx, serverUrl = state.serverUrl,
                    onDismiss = { liveViewerIndex = null },
                    onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit, onTrash = { vm.trash(it) },
                    onArchive = { vm.archive(it) }, onRestore = { vm.restore(it) }, onRotate = { vm.rotate(it) },
                    onEdit = { route = Route.Editor(it); liveViewerIndex = null },
                    onAddToAlbum = if (vm.canEdit) { m -> albumPickFor = m } else null,
                    onLock = if (vm.isAdmin) { m -> vm.setLocked(m, locked = r.filter != Filter.LOCKED) } else null,
                    lockedView = r.filter == Filter.LOCKED,
                    onMove = if (vm.canEdit && r.filter != Filter.LOCKED) { m -> moveFor = m } else null,
                )
            }
            return
        }
        else -> Unit
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == Tab.PHOTOS) FloatingActionButton(onClick = {
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
            // One title and one icon. Everything else that used to crowd the
            // top bar lives in the bottom navigation and under Collections.
            if (tab != Tab.SEARCH) TopAppBar(
                title = {
                    Column {
                        Text(tab.label, fontSize = 20.sp)
                        if (tab == Tab.PHOTOS) Text(
                            text = subtitleFor(state), fontSize = 11.sp, color = Color.Gray,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { route = Route.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (tab == Tab.SEARCH && t != Tab.SEARCH) vm.setQuery("")
                            tab = t
                        },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.PHOTOS -> Icons.Default.Photo
                                    Tab.SEARCH -> Icons.Default.Search
                                    Tab.COLLECTIONS -> Icons.Default.Collections
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.PHOTOS -> {
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
                    GalleryBody(state = state, uploadsFeed = false,
                        onItemClick = { m -> liveViewerIndex = state.items.indexOfFirst { it.id == m.id }.coerceAtLeast(0) },
                        onLoadMore = { vm.loadNext() }, onRetry = { vm.refresh() }, onSettings = { route = Route.Settings })
                }
                Tab.SEARCH -> {
                    SearchBar(
                        initial = state.query,
                        semantic = state.semantic,
                        onApply = { vm.setQuery(it) },
                        onSemanticChange = { vm.setSemantic(it) },
                        onClose = { vm.setQuery(""); tab = Tab.PHOTOS },
                    )
                    if (state.query.isBlank()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("Search by file name, or turn on Smart for \"beach\", \"birthday cake\"…", color = Color.Gray,
                                modifier = Modifier.padding(32.dp))
                        }
                    } else GalleryBody(state = state, uploadsFeed = false,
                        onItemClick = { m -> liveViewerIndex = state.items.indexOfFirst { it.id == m.id }.coerceAtLeast(0) },
                        onLoadMore = { vm.loadNext() }, onRetry = { vm.refresh() }, onSettings = { route = Route.Settings })
                }
                Tab.COLLECTIONS -> CollectionsTab(
                    serverUrl = state.serverUrl,
                    onAlbums = { route = Route.Albums },
                    onAlbum = { route = Route.AlbumMedia(it) },
                    onUserAlbum = { a -> if (a.locked) unlockThen = { route = Route.UserAlbumMedia(a) } else route = Route.UserAlbumMedia(a) },
                    onPeople = { route = Route.People },
                    onCluster = { route = Route.ClusterMedia(it) },
                    onMap = { route = Route.Map },
                    onCollection = { route = Route.Collection(it) },
                    signedIn = vm.isAdmin,
                    onLocked = { unlockThen = { route = Route.Collection(Filter.LOCKED) } },
                )
            }
        }
    }

    liveViewerIndex?.let { idx ->
        ViewerDialog(
            items = state.items,
            initialIndex = idx,
            serverUrl = state.serverUrl,
            onDismiss = { liveViewerIndex = null },
            onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit,
            onTrash = { vm.trash(it) },
            onArchive = { vm.archive(it) },
            onRestore = { vm.restore(it) },
            onRotate = { vm.rotate(it) },
            onEdit = { route = Route.Editor(it); liveViewerIndex = null },
            onAddToAlbum = if (vm.canEdit) { m -> albumPickFor = m } else null,
            onLock = if (vm.isAdmin) { m -> vm.setLocked(m, locked = true) } else null,
            onMove = if (vm.canEdit) { m -> moveFor = m } else null,
        )
    }
    staticViewer?.let { (list, idx) ->
        ViewerDialog(
            items = list,
            initialIndex = idx,
            serverUrl = state.serverUrl,
            onDismiss = { staticViewer = null },
            onToggleFavorite = { vm.toggleFavorite(it) }, readOnly = !vm.canEdit,
        )
    }

}

/**
 * While locked content is on screen, a minute without a touch closes it:
 * the session is re-locked on the server and the screen is left. A viewer
 * dialog on top counts as activity (its touches never reach this layer), so
 * the countdown only runs while no viewer is open.
 */
@Composable
private fun IdleRelock(enabled: Boolean, onIdle: () -> Unit, content: @Composable () -> Unit) {
    var last by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled, last) {
        if (enabled) { kotlinx.coroutines.delay(60_000); onIdle() }
    }
    Box(Modifier.fillMaxSize().pointerInput(enabled) {
        if (enabled) awaitPointerEventScope {
            while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); last = System.currentTimeMillis() }
        }
    }) { content() }
}

/** Password entry: masked by default, an eye reveals it. */
@Composable
internal fun PasswordField(value: String, onChange: (String) -> Unit, label: String = "Password", modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, modifier = modifier,
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (show) "Hide password" else "Show password")
            }
        },
    )
}

@Composable
private fun LoginDialog(vm: GalleryViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in") },
        text = {
            Column {
                Text("This library needs an account.", color = Color.Gray, fontSize = 13.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.padding(top = 8.dp))
                PasswordField(value = pw, onChange = { pw = it }, modifier = Modifier.padding(top = 8.dp))
                err?.let { Text(it, color = Color(0xFFEF5350), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.login(name.trim(), pw) { e -> err = e } }) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnlockDialog(vm: GalleryViewModel, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Locked folder") },
        text = {
            Column {
                Text("Enter your password to open it.", color = Color.Gray, fontSize = 13.sp)
                PasswordField(value = pw, onChange = { pw = it; err = false }, modifier = Modifier.padding(top = 8.dp))
                if (err) Text("Wrong password", color = Color(0xFFEF5350), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { vm.unlockLocked(pw) { ok -> if (ok) onUnlocked() else err = true } }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Chooser of folder albums (library folders); the file moves on disk, keeping its id. */
@Composable
private fun MoveToFolderDialog(serverUrl: String, item: MediaItem, onDismiss: () -> Unit, onMoved: () -> Unit) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<Album>?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(serverUrl) {
        folders = runCatching { api.albums() }.getOrDefault(emptyList()).filter { it.folder == null && it.album != "uploads" && it.album != "_root" && it.album != item.album }
    }
    fun move(target: String) {
        scope.launch {
            val r = runCatching { api.moveMedia(com.buildapp.photos.api.MoveBody(listOf(item.id), target)) }
            android.widget.Toast.makeText(context,
                r.map { if (it.moved > 0) "Moved to ${it.album}" else "Not moved" }.getOrElse { "Could not move: ${it.message}" },
                android.widget.Toast.LENGTH_SHORT).show()
            if (r.isSuccess && r.getOrThrow().moved > 0) onMoved() else onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Currently in “${item.album ?: "?"}”. The file moves into the folder's year/month; favorites, albums and people stay.", color = Color.Gray, fontSize = 12.sp)
                val list = folders
                when {
                    list == null -> CircularProgressIndicator()
                    else -> list.forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable { move(a.album) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.album, modifier = Modifier.weight(1f))
                            Text("${a.count}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text("New folder") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { val n = newName.trim(); if (n.isNotEmpty()) move(n) }) { Text("Move") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picker listing every manual album with a check for the ones this item is in; tap toggles. */
@Composable
private fun AddToAlbumDialog(serverUrl: String, item: MediaItem, onDismiss: () -> Unit, onChanged: () -> Unit, fromAlbum: UserAlbum? = null) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<UserAlbum>?>(null) }
    var newName by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    // Opened from inside an album: move (remove from it) rather than copy.
    var moveOut by remember { mutableStateOf(fromAlbum != null) }
    suspend fun leaveSource(targetId: Int) {
        if (moveOut && fromAlbum != null && fromAlbum.id != targetId) {
            runCatching { api.removeFromUserAlbum(fromAlbum.id, IdsBody(listOf(item.id))) }
        }
    }
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
                                        else { api.addToUserAlbum(a.id, IdsBody(listOf(item.id))); leaveSource(a.id) }
                                        onChanged(); tick++
                                        if (moveOut && fromAlbum != null && a.contains != true && a.id != fromAlbum.id) onDismiss()
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
                if (fromAlbum != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = moveOut, onCheckedChange = { moveOut = it })
                    Text("Move: remove from “${fromAlbum.name}”", fontSize = 13.sp)
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
                                val created = api.createUserAlbum(NewAlbumBody(n, listOf(item.id))).album
                                leaveSource(created.id)
                                newName = ""; onChanged(); tick++
                                if (moveOut && fromAlbum != null) onDismiss()
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
/** Loading / error / empty states around the sectioned grid, shared by every list view. */
@Composable
private fun GalleryBody(
    state: GalleryState,
    uploadsFeed: Boolean,
    onItemClick: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.error != null && state.items.isEmpty() -> ErrorView(
                error = state.error, serverUrl = state.serverUrl, onRetry = onRetry, onSettings = onSettings)
            state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.items.isEmpty() && !state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No items", color = Color.Gray)
            }
            else -> Gallery(
                items = state.items, serverUrl = state.serverUrl, uploadsFeed = uploadsFeed,
                onItemClick = onItemClick, onLoadMore = onLoadMore,
            )
        }
    }
}

/**
 * The Collections tab: album and people previews with "See all", Places,
 * and the library views that used to be filter chips.
 */
@Composable
private fun CollectionsTab(
    serverUrl: String,
    onAlbums: () -> Unit,
    onAlbum: (Album) -> Unit,
    onUserAlbum: (UserAlbum) -> Unit,
    onPeople: () -> Unit,
    onCluster: (Cluster) -> Unit,
    onMap: () -> Unit,
    onCollection: (Filter) -> Unit,
    signedIn: Boolean = false,
    onLocked: () -> Unit = {},
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var userAlbums by remember(serverUrl) { mutableStateOf<List<UserAlbum>>(emptyList()) }
    var albums by remember(serverUrl) { mutableStateOf<List<Album>>(emptyList()) }
    var clusters by remember(serverUrl) { mutableStateOf<List<Cluster>>(emptyList()) }
    LaunchedEffect(serverUrl) {
        userAlbums = runCatching { api.userAlbums() }.getOrDefault(emptyList())
        albums = runCatching { api.albums() }.getOrDefault(emptyList())
        clusters = runCatching { api.clusters() }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Albums", onSeeAll = onAlbums)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(userAlbums.take(6), key = { "u${it.id}" }) { a ->
                CollectionCard(a.name, if (a.locked) "Locked · ${a.count}" else "${a.count}",
                    if (a.locked) null else a.cover?.let { Urls.thumb(serverUrl, it) }, locked = a.locked) { onUserAlbum(a) }
            }
            items(albums.take(8), key = { it.key }) { a ->
                CollectionCard(a.label, "${a.count}", a.cover?.let { Urls.thumb(serverUrl, it) }) { onAlbum(a) }
            }
        }
        SectionHeader("People", onSeeAll = onPeople)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(clusters.take(12), key = { it.id }) { c ->
                Column(Modifier.width(72.dp).clickable { onCluster(c) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(Urls.clusterThumb(serverUrl, c.id)).crossfade(true).build(),
                        contentDescription = c.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF1A1A1C)),
                    )
                    Text(c.name ?: "Unnamed", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp), color = if (c.name == null) Color.Gray else Color.Unspecified)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CollectionRow(Icons.Default.Map, "Places", "Photos on a map", onMap)
        androidx.compose.material3.HorizontalDivider(color = Color(0xFF26262A), modifier = Modifier.padding(horizontal = 16.dp))
        CollectionRow(Icons.Default.Favorite, "Favorites") { onCollection(Filter.FAVORITES) }
        CollectionRow(Icons.Default.PlayArrow, "Videos") { onCollection(Filter.VIDEOS) }
        CollectionRow(Icons.Default.CloudUpload, "Recently uploaded", "What the phones sent, newest first") { onCollection(Filter.UPLOADS) }
        CollectionRow(Icons.Default.HelpOutline, "Unknown date") { onCollection(Filter.UNKNOWN) }
        CollectionRow(Icons.Default.Archive, "Archive") { onCollection(Filter.ARCHIVED) }
        CollectionRow(Icons.Default.Delete, "Trash", "Kept 60 days") { onCollection(Filter.TRASH) }
        if (signedIn) CollectionRow(Icons.Default.Lock, "Locked folder", "Only you, after your password", onLocked)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSeeAll).semantics { contentDescription = title }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("See all", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
    }
}

@Composable
private fun CollectionCard(title: String, subtitle: String, cover: String?, locked: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(if (locked) Color.Black else Color(0xFF1A1A1C)),
            contentAlignment = Alignment.Center) {
            if (locked) Icon(Icons.Default.Lock, contentDescription = "Locked", tint = Color(0xFF8A8A92), modifier = Modifier.size(36.dp))
            cover?.let {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(it).crossfade(true).build(),
                    contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun CollectionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF9A9AA2), modifier = Modifier.padding(end = 16.dp))
        Column {
            Text(title, fontSize = 15.sp)
            subtitle?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
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
