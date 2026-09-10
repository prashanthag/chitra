package com.buildapp.photos.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.flow.first

/**
 * Debug-build-only launch extras so the emulator E2E script can configure
 * the app without driving the UI:
 *
 *   am start -n com.buildapp.photos/.MainActivity \
 *      --es server_url http://10.0.2.2:8765 --ez backup_enable true \
 *      --es backup_buckets Camera,Screenshots --ez backup_videos true \
 *      --ez backup_wifi_only false --ez backup_now true --es filter uploads
 *
 * A release build ignores all of it.
 */
object DebugHooks {
    @Volatile var initialFilter: String? = null

    fun isDebuggable(ctx: Context) =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    suspend fun apply(ctx: Context, intent: Intent?) {
        if (intent == null || !isDebuggable(ctx)) return
        val settings = SettingsRepository(ctx)
        intent.getStringExtra("server_url")?.let { settings.setServerUrl(it) }
        // Sign in (accounts on the server): login_name + login_password.
        intent.getStringExtra("login_name")?.let { name ->
            val pw = intent.getStringExtra("login_password") ?: ""
            runCatching {
                val r = com.buildapp.photos.api.PhotoApi.create(settings.serverUrl.first()).login(
                    com.buildapp.photos.api.LoginBody(name, pw, device = "e2e"))
                settings.setSession(r.token, r.user.name)
            }
        }
        intent.getStringExtra("filter")?.let { initialFilter = it }
        if (intent.hasExtra("backup_videos")) settings.setBackupVideos(intent.getBooleanExtra("backup_videos", true))
        if (intent.hasExtra("backup_wifi_only")) settings.setBackupWifiOnly(intent.getBooleanExtra("backup_wifi_only", true))
        intent.getStringExtra("backup_buckets")?.let { names ->
            val wanted = names.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val ids = DeviceMedia.buckets(ctx.contentResolver)
                .filter { it.name.lowercase() in wanted }.map { it.id }.toSet()
            settings.setBackupBuckets(ids)
        }
        if (intent.hasExtra("backup_enable")) {
            val on = intent.getBooleanExtra("backup_enable", false)
            settings.setBackupEnabled(on)
            if (on) BackupWorker.schedule(ctx, settings.backup.first().wifiOnly) else BackupWorker.cancel(ctx)
        }
        // Wipe the ledger before starting a run, or the worker can read it
        // first and find nothing to send.
        if (intent.getBooleanExtra("ledger_clear", false)) UploadLedger.get(ctx).clear(settings.serverUrl.first())
        if (intent.getBooleanExtra("backup_now", false)) BackupWorker.runNow(ctx)
    }
}
