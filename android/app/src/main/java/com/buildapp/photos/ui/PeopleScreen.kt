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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.buildapp.photos.api.Cluster
import com.buildapp.photos.api.FacesStatus
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.api.Urls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    serverUrl: String,
    onBack: () -> Unit,
    onClusterSelected: (Cluster) -> Unit,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var clusters by remember { mutableStateOf<List<Cluster>?>(null) }
    var status by remember { mutableStateOf<FacesStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl) {
        try {
            status = api.facesStatus()
            clusters = api.clusters()
        } catch (e: Exception) {
            error = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("People", fontWeight = FontWeight.SemiBold)
                        status?.let {
                            Text(
                                "${it.clusters} groups · ${it.faces} faces · ${it.processed}/${it.totalPhotos} processed",
                                color = Color.Gray,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Error: $error", color = Color(0xFFEF5350))
                }
                clusters == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                clusters!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No people yet", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(
                            "Run face indexing on the server, then come back.",
                            color = Color.Gray,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(clusters!!, key = { it.id }) { c ->
                        ClusterTile(c, serverUrl, onClick = { onClusterSelected(c) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterTile(c: Cluster, serverUrl: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Color(0xFF1A1A1C)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(Urls.clusterThumb(serverUrl, c.id))
                    .crossfade(true)
                    .build(),
                contentDescription = c.name ?: "Person ${c.id}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            c.name ?: "${c.count} photos",
            color = Color.LightGray,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterMediaScreen(
    serverUrl: String,
    cluster: Cluster,
    onBack: () -> Unit,
    onItemClick: (List<MediaItem>, Int) -> Unit,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cluster.id) {
        try { items = api.clusterMedia(cluster.id) } catch (e: Exception) { error = e.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cluster.name ?: "Person ${cluster.id} · ${cluster.count} photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Text("Error: $error", color = Color(0xFFEF5350))
                items == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items!!, key = { it.id }) { m ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .background(Color(0xFF1A1A1C))
                                .clickable {
                                    val list = items!!
                                    onItemClick(list, list.indexOfFirst { it.id == m.id }.coerceAtLeast(0))
                                },
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(Urls.thumb(serverUrl, m.id))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
