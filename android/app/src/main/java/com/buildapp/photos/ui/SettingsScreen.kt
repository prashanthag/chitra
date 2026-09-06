package com.buildapp.photos.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.data.BackupPlanner
import com.buildapp.photos.data.BackupPrefs
import com.buildapp.photos.data.BackupWorker
import com.buildapp.photos.data.DeviceBucket
import com.buildapp.photos.data.DeviceMedia
import com.buildapp.photos.data.SettingsRepository
import com.buildapp.photos.data.UploadLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server address + backup controls, modelled on Google Photos' "Backup" and
 * "Back up device folders" settings: pick which folders sync, whether videos
 * and mobile data are included, see status, and kick a run off by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    onBack: () -> Unit,
    onServerUrlSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val prefs by settings.backup.collectAsState(initial = BackupPrefs())
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var health by remember(serverUrl) { mutableStateOf<String?>(null) }
    var hasPerm by remember { mutableStateOf(BackupWorker.hasMediaPermission(context)) }
    var buckets by remember { mutableStateOf<List<DeviceBucket>>(emptyList()) }
    var ledgerCount by remember { mutableStateOf(0) }
    // Every BackupWorker request is auto-tagged with the class name, so one
    // flow covers the periodic, content-trigger and "now" jobs.
    val infos by WorkManager.getInstance(context)
        .getWorkInfosByTagFlow(BackupWorker::class.java.name)
        .collectAsState(initial = emptyList())
    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }

    LaunchedEffect(serverUrl) {
        health = try {
            val h = withContext(Dispatchers.IO) { PhotoApi.create(serverUrl).health() }
            "Connected · ${h.itemsIndexed} items indexed"
        } catch (e: Exception) {
            "Not reachable (${e.message ?: e.javaClass.simpleName})"
        }
        ledgerCount = withContext(Dispatchers.IO) { UploadLedger.get(context).count(serverUrl) }
    }
    LaunchedEffect(hasPerm, running?.id) {
        if (hasPerm) {
            buckets = withContext(Dispatchers.IO) { DeviceMedia.buckets(context.contentResolver) }
            ledgerCount = withContext(Dispatchers.IO) { UploadLedger.get(context).count(serverUrl) }
        }
    }

    fun enableBackup() = scope.launch {
        // Persist a default folder set only once the device folders are
        // known. Right after the permission grant the list is still empty,
        // and writing {} here would make backup silently upload nothing.
        if (settings.backup.first().bucketIds == null) {
            val known = buckets.ifEmpty { withContext(Dispatchers.IO) { DeviceMedia.buckets(context.contentResolver) } }
            val defaults = BackupPlanner.defaultBuckets(known)
            if (defaults.isNotEmpty()) settings.setBackupBuckets(defaults)
        }
        settings.setBackupEnabled(true)
        BackupWorker.schedule(context, settings.backup.first().wifiOnly)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPerm = grants.values.any { it } || BackupWorker.hasMediaPermission(context)
        if (hasPerm) enableBackup()
        else android.widget.Toast.makeText(context, "Photos permission needed for backup", android.widget.Toast.LENGTH_SHORT).show()
    }

    val selected: Set<Long> = prefs.bucketIds ?: BackupPlanner.defaultBuckets(buckets)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                SectionTitle("Server")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("http://host:port") },
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onServerUrlSaved(url.trim()) }, enabled = url.trim() != serverUrl) { Text("Save") }
                    Spacer(Modifier.width(12.dp))
                    Text(health ?: "Checking…", color = Color.Gray, fontSize = 12.sp)
                }
            }

            item {
                SectionTitle("Backup")
                ToggleRow(
                    title = "Auto backup",
                    subtitle = "Upload new photos & videos from the folders below, in the background",
                    checked = prefs.enabled,
                ) { want ->
                    if (want) {
                        if (hasPerm) enableBackup() else permLauncher.launch(BackupWorker.requiredPermissions())
                    } else scope.launch {
                        settings.setBackupEnabled(false)
                        BackupWorker.cancel(context)
                    }
                }
                if (!hasPerm) {
                    OutlinedButton(onClick = { permLauncher.launch(BackupWorker.requiredPermissions()) }) {
                        Text("Allow photo access")
                    }
                }
                ToggleRow("Back up videos", "Videos are large; off = photos only", prefs.includeVideos) { on ->
                    scope.launch { settings.setBackupVideos(on) }
                }
                ToggleRow("Wi-Fi only", "Never use mobile data for backup", prefs.wifiOnly) { on ->
                    scope.launch {
                        settings.setBackupWifiOnly(on)
                        if (settings.backup.first().enabled) BackupWorker.schedule(context, on)
                    }
                }
            }

            item {
                StatusCard(prefs, running, ledgerCount)
                Row(Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = { BackupWorker.runNow(context) },
                        enabled = hasPerm && running == null,
                    ) { Text(if (running != null) "Backing up…" else "Back up now") }
                }
            }

            item {
                AccountSection(serverUrl)
                SectionTitle("Back up device folders")
                Text(
                    "Only the folders switched on are uploaded. Camera is on by default; " +
                        "turn on Screenshots, WhatsApp Images, Downloads… as you like.",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                )
                if (!hasPerm) Text("Allow photo access to list folders.", color = Color.Gray, fontSize = 12.sp)
                else if (buckets.isEmpty()) Text("No photos or videos found on this device.", color = Color.Gray, fontSize = 12.sp)
            }
            items(buckets, key = { it.id }) { b ->
                BucketRow(b, checked = b.id in selected) { on ->
                    scope.launch {
                        settings.updateBackupBuckets { cur ->
                            val base = cur ?: BackupPlanner.defaultBuckets(buckets)
                            if (on) base + b.id else base - b.id
                        }
                        // A newly enabled folder backfills right away.
                        if (on && settings.backup.first().enabled) BackupWorker.runNow(context, wifiOnly = prefs.wifiOnly)
                    }
                }
                HorizontalDivider(color = Color(0xFF26262A))
            }
        }
    }
}

/**
 * Accounts: who is signed in, sign out, and (admin) the user list with an
 * add row. With no accounts yet, a "Create admin" row switches the server
 * from open to sign-in required.
 */
@Composable
private fun AccountSection(serverUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(serverUrl) { PhotoApi.create(serverUrl) }
    val settings = remember { SettingsRepository(context) }
    var auth by remember { mutableStateOf<com.buildapp.photos.api.AuthState?>(null) }
    var users by remember { mutableStateOf<List<com.buildapp.photos.api.User>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("member") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverUrl, tick) {
        auth = runCatching { api.authState() }.getOrNull()
        users = if (auth?.user?.role == "admin") runCatching { api.users() }.getOrDefault(emptyList()) else emptyList()
    }
    SectionTitle("Account")
    val a = auth
    when {
        a == null -> Text("…", color = Color.Gray, fontSize = 12.sp)
        !a.authRequired -> Text("No accounts yet: everyone on the network is an admin. Create the first account to require sign-in.",
            color = Color.Gray, fontSize = 12.sp)
        a.user == null -> Text("Not signed in. Sign in from the prompt on the Photos screen.", color = Color.Gray, fontSize = 12.sp)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Signed in as ${a.user.name} (${a.user.role})", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { api.logout() }
                    settings.setSession(null, null); tick++
                }
            }) { Text("Sign out") }
        }
    }
    if (a != null && (!a.authRequired || a.user?.role == "admin")) {
        users.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(u.name, modifier = Modifier.weight(1f))
                Text(u.role, color = Color.Gray, fontSize = 12.sp)
                if (u.id != a.user?.id) IconButton(onClick = {
                    scope.launch { runCatching { api.deleteUser(u.id) }; tick++ }
                }) { Icon(Icons.Default.Delete, contentDescription = "Delete user", tint = Color.Gray) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (a.authRequired) {
                FilterChip(selected = role == "member", onClick = { role = "member" }, label = { Text("member") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = role == "admin", onClick = { role = "admin" }, label = { Text("admin") })
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = {
                scope.launch {
                    val r = runCatching { api.createUser(com.buildapp.photos.api.NewUserBody(name.trim(), pw, if (a.authRequired) role else "admin")) }
                    msg = if (r.isSuccess) (if (a.authRequired) "User added" else "Admin created. Sign in from the Photos screen.") else "Could not create (name taken, or password under 4 characters)"
                    if (r.isSuccess) { name = ""; pw = "" }
                    tick++
                }
            }) { Text(if (a.authRequired) "Add user" else "Create admin") }
        }
        msg?.let { Text(it, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9AA2))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusCard(prefs: BackupPrefs, running: WorkInfo?, ledgerCount: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (running != null) {
                val p = running.progress
                val done = p.getInt(BackupWorker.KEY_DONE, 0)
                val total = p.getInt(BackupWorker.KEY_TOTAL, 0)
                Text(if (total > 0) "Backing up $done / $total" else "Checking for new photos…",
                    style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                val last = if (prefs.lastRun > 0)
                    DateUtils.getRelativeTimeSpanString(prefs.lastRun * 1000).toString() else "never"
                Text("Last backup: $last", style = MaterialTheme.typography.bodyMedium)
                if (prefs.lastResult.isNotBlank()) {
                    Text(prefs.lastResult, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9AA2))
                }
                if (prefs.pending > 0) {
                    Text("${prefs.pending} waiting to upload", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB74D))
                }
            }
            Text("$ledgerCount items backed up to this server",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9AA2), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun BucketRow(b: DeviceBucket, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1A1C)),
        ) {
            b.coverUri?.let {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(it).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(b.name, style = MaterialTheme.typography.bodyLarge)
            Text("${b.count} items", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9AA2))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
