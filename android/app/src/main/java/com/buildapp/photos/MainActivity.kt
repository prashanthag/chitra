package com.buildapp.photos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.buildapp.photos.data.DebugHooks
import com.buildapp.photos.ui.PhotosApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (DebugHooks.isDebuggable(this) && intent?.extras != null) {
            // Apply launch extras before the UI reads settings (debug builds only).
            lifecycleScope.launch { DebugHooks.apply(this@MainActivity, intent) }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhotosApp()
                }
            }
        }
    }
}
