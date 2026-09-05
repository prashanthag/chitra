package com.buildapp.photos.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Backs up the chosen device folders to the server.
 *
 * Three triggers share this worker: a 15-minute periodic sweep, a one-shot
 * "content changed" job that fires when MediaStore gains a new photo, and the
 * manual "Back up now" button. What to send is decided by [BackupPlanner]
 * from MediaStore + the [UploadLedger]; the server's /api/upload/check lets
 * a reinstalled app skip everything the library already holds.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        // One run at a time. The 15-minute sweep, the new-photo trigger and
        // "Back up now" can all fire within a minute; unserialised they each
        // re-planned the same roll and pre-flighted it three times over.
        // Waiting (not skipping) so a "Back up now" that replaced a running
        // job still runs once the cancelled one has let go.
        runLock.withLock { runOnce() }

    private suspend fun runOnce(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val settings = SettingsRepository(ctx)
        val prefs = settings.backup.first()
        val serverUrl = settings.serverUrl.first()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val fromTrigger = inputData.getBoolean(KEY_TRIGGER, false)
        val now = System.currentTimeMillis() / 1000
        if (!prefs.enabled && !manual) return@withContext Result.success()
        if (!hasMediaPermission(ctx)) {
            settings.setBackupStatus(now, "Photos permission not granted", -1)
            return@withContext Result.failure()
        }

        val cr = ctx.contentResolver
        val buckets = prefs.bucketIds ?: BackupPlanner.defaultBuckets(DeviceMedia.buckets(cr))
        val options = BackupOptions(bucketIds = buckets, includeVideos = prefs.includeVideos)
        val ledger = UploadLedger.get(ctx)
        val plan = BackupPlanner.plan(DeviceMedia.items(cr, buckets, prefs.includeVideos),
            ledger.uploadedIds(serverUrl), options)
        if (plan.isEmpty()) {
            settings.setBackupStatus(now, "Up to date", 0)
            rearmTrigger(ctx, prefs, fromTrigger)
            return@withContext Result.success()
        }

        var done = 0
        var sent = 0
        var dups = 0
        var failed = 0
        suspend fun progress() = setProgress(workDataOf(
            KEY_DONE to done, KEY_TOTAL to plan.size, KEY_SENT to sent, KEY_DUPS to dups, KEY_FAILED to failed,
        ))
        progress()

        // Pre-flight: anything the server already has is ledgered without
        // moving a byte. If the check itself fails we just upload; the server
        // de-duplicates on its side too.
        val remaining = ArrayList<DeviceItem>(plan.size)
        for (chunk in plan.chunked(200)) {
            if (isStopped) break
            // One small read per file (first 1 MiB + last 64 KiB) so the
            // server can match by content: a renamed copy or a photo that
            // reached the library another way is skipped, and a new file that
            // happens to share a name and size is not.
            val exists = Uploader.check(serverUrl, chunk.map {
                val uri = Uri.parse(it.uri)
                // Real size alongside the hash: the name+size fallback on the
                // server must not be fed a stale MediaStore size either.
                val real = runCatching {
                    ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd -> pfd.statSize }
                }.getOrNull()?.takeIf { s -> s > 0 } ?: it.size
                Uploader.CheckFile(it.name, real, ContentHash.of(ctx, uri))
            }).getOrNull()
            if (exists == null || exists.size != chunk.size) {
                remaining += chunk
                continue
            }
            chunk.forEachIndexed { i, item ->
                if (exists[i]) {
                    ledger.markUploaded(serverUrl, item, null, duplicate = true)
                    dups++
                    done++
                } else remaining += item
            }
            // After a reinstall the pre-flight covers the whole camera roll
            // and takes minutes; report after every chunk so the status card
            // does not sit at 0/N until it ends.
            progress()
        }
        progress()

        var consecutiveFailures = 0
        for (item in remaining) {
            if (isStopped) break
            val uri = Uri.parse(item.uri)
            val r = Uploader.uploadOne(ctx, serverUrl, uri, item.name, item.size, cr.getType(uri))
            done++
            if (r.ok) {
                consecutiveFailures = 0
                ledger.markUploaded(serverUrl, item, r.serverId, r.duplicate)
                if (r.duplicate) dups++ else sent++
            } else {
                failed++
                // A server that is down fails every file the same way; stop
                // hammering it and let WorkManager's backoff retry later.
                if (++consecutiveFailures >= 5) break
            }
            if (done % 5 == 0) progress()
        }
        progress()

        val pending = plan.size - done + failed
        val summary = buildString {
            append("Sent $sent")
            if (dups > 0) append(", $dups already on server")
            if (failed > 0) append(", $failed failed")
            if (pending > 0 && failed == 0) append(", $pending waiting")
        }
        settings.setBackupStatus(System.currentTimeMillis() / 1000, summary, pending)
        rearmTrigger(ctx, prefs, fromTrigger)
        if (pending > 0 && !isStopped) Result.retry() else Result.success()
    }

    /**
     * Content-URI triggers are one-shot, so the trigger job re-arms itself.
     * Only the trigger run does this: REPLACE from inside would cancel the
     * running job (itself), so it appends the next one behind it instead,
     * and the periodic/manual runs leave the pending trigger job alone.
     */
    private fun rearmTrigger(ctx: Context, prefs: BackupPrefs, fromTrigger: Boolean) {
        if (prefs.enabled && fromTrigger) {
            scheduleContentTrigger(ctx, prefs.wifiOnly, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    companion object {
        private val runLock = Mutex()
        const val WORK_NAME = "chitra-backup"
        const val WORK_NOW = "chitra-backup-now"
        const val WORK_TRIGGER = "chitra-backup-trigger"
        const val KEY_MANUAL = "manual"
        const val KEY_TRIGGER = "trigger"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SENT = "sent"
        const val KEY_DUPS = "dups"
        const val KEY_FAILED = "failed"

        fun hasMediaPermission(ctx: Context): Boolean {
            fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 34 ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
                else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        private fun network(wifiOnly: Boolean) = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        /** Periodic sweep + content-change trigger + an immediate first pass. */
        fun schedule(context: Context, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context)
            val periodic = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(network(wifiOnly))
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            scheduleContentTrigger(context, wifiOnly)
            runNow(context, wifiOnly = wifiOnly)
        }

        /** One-shot job that fires when MediaStore gains new images/videos. */
        fun scheduleContentTrigger(
            context: Context,
            wifiOnly: Boolean,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_TRIGGER, true).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(network(wifiOnly))
                        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(20, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_TRIGGER, policy, req)
        }

        /** "Back up now": ignores the Wi-Fi-only preference unless asked to keep it. */
        fun runNow(context: Context, wifiOnly: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_MANUAL, true).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network(wifiOnly)).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(WORK_TRIGGER)
            wm.cancelUniqueWork(WORK_NOW)
        }
    }
}
