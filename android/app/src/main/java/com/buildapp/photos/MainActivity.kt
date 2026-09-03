package com.buildapp.photos

import android.content.Intent
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
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds only: apply launch extras synchronously so the ViewModel
        // created by setContent reads the configured server. First creation
        // only: a rotation re-runs onCreate with the same intent and would
        // otherwise restart a running backup.
        if (savedInstanceState == null && DebugHooks.isDebuggable(this) && intent?.extras != null) {
            runBlocking { DebugHooks.apply(this@MainActivity, intent) }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhotosApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // `am start` on an activity that is already on top is delivered here,
        // not to onCreate; the emulator E2E relies on that to change folders
        // and trigger runs between steps. Debug builds only, like onCreate.
        if (DebugHooks.isDebuggable(this) && intent.extras != null) {
            lifecycleScope.launch { DebugHooks.apply(this@MainActivity, intent) }
        }
    }
}
