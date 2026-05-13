package com.buildapp.photos.ui

import android.preference.PreferenceManager
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.buildapp.photos.api.LocationItem
import com.buildapp.photos.api.PhotoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    serverUrl: String,
    onBack: () -> Unit,
    onMarkerClick: (LocationItem) -> Unit,
) {
    val context = LocalContext.current
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var locations by remember { mutableStateOf<List<LocationItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl) {
        try {
            val list = withContext(Dispatchers.IO) { api.locations() }
            locations = list
        } catch (e: Exception) {
            error = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Map · ${locations?.size ?: '…'} geotagged")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Text("Error: $error", color = Color(0xFFEF5350))
                locations == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                locations!!.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No GPS-tagged photos yet", color = Color.Gray)
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        Configuration.getInstance().load(
                            ctx,
                            PreferenceManager.getDefaultSharedPreferences(ctx),
                        )
                        Configuration.getInstance().userAgentValue = ctx.packageName
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            // Drop markers
                            for (loc in locations!!) {
                                val m = Marker(this).apply {
                                    position = GeoPoint(loc.lat, loc.lng)
                                    title = loc.name
                                    setOnMarkerClickListener { _, _ ->
                                        onMarkerClick(loc); true
                                    }
                                }
                                overlays.add(m)
                            }
                            // Fit view to all markers
                            val lats = locations!!.map { it.lat }
                            val lngs = locations!!.map { it.lng }
                            val bb = BoundingBox(
                                lats.max(), lngs.max(), lats.min(), lngs.min(),
                            )
                            post { zoomToBoundingBox(bb, false, 80) }
                        }
                    },
                )
            }
        }
    }
}
