package com.buildapp.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.api.Urls
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    item: MediaItem,
    serverUrl: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    var brightness by remember { mutableStateOf(1.0f) }
    var contrast by remember { mutableStateOf(1.0f) }
    var saturation by remember { mutableStateOf(1.0f) }
    var sharpness by remember { mutableStateOf(1.0f) }
    var saving by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit · ${item.name}", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            saving = true
                            val params: Map<String, JsonElement> = mapOf(
                                "auto_enhance" to JsonPrimitive(true),
                            )
                            withContext(Dispatchers.IO) {
                                runCatching { api.edit(item.id, params) }
                            }
                            saving = false
                            version += 1
                        }
                    }) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "Auto-enhance")
                    }
                    IconButton(
                        enabled = !saving,
                        onClick = {
                            scope.launch {
                                saving = true
                                val params: Map<String, JsonElement> = mapOf(
                                    "brightness" to JsonPrimitive(brightness.toDouble()),
                                    "contrast" to JsonPrimitive(contrast.toDouble()),
                                    "saturation" to JsonPrimitive(saturation.toDouble()),
                                    "sharpness" to JsonPrimitive(sharpness.toDouble()),
                                )
                                withContext(Dispatchers.IO) {
                                    runCatching { api.edit(item.id, params) }
                                }
                                saving = false
                                onSaved()
                            }
                        },
                    ) { Icon(Icons.Default.Check, contentDescription = "Apply") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val url = Urls.full(
                    serverUrl, item.id,
                    asJpeg = item.ext.equals(".heic", true) || item.ext.equals(".heif", true),
                ) + "&v=$version"
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditSlider("Brightness", brightness) { brightness = it }
                EditSlider("Contrast", contrast) { contrast = it }
                EditSlider("Saturation", saturation) { saturation = it }
                EditSlider("Sharpness", sharpness) { sharpness = it }
            }
        }
    }
}

@Composable
private fun EditSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), color = Color.Gray)
        }
        Slider(
            value = value, onValueChange = onChange,
            valueRange = 0f..2f, steps = 39,
        )
    }
}
