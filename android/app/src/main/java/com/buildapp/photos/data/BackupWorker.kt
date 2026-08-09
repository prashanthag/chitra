package com.buildapp.photos.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic backup of the device camera roll to the server.
 *
 * Tracks last-seen MediaStore _ID via DataStore preference 'backup_last_id'.
 * Each run queries new images/videos with ID > last seen, batches them, and
 * uploads via [Uploader]. On success the cursor is advanced.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(applicationContext)
        val serverUrl = runCatching { settings.serverUrl.first() }.getOrNull() ?: return@withContext Result.retry()
        val lastId = settings.backupLastId.first()

        val newUris = mutableListOf<Uri>()
        var maxId = lastId
        // Query images
        scanCollection(
            applicationContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            lastId,
        ) { id, uri ->
            if (id > maxId) maxId = id
            newUris += uri
        }
        // Query videos
        scanCollection(
            applicationContext.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            lastId,
        ) { id, uri ->
            if (id > maxId) maxId = id
            newUris += uri
        }

        if (newUris.isEmpty()) {
            return@withContext Result.success()
        }
        // Upload in chunks of 10
        val chunks = newUris.chunked(10)
        for (chunk in chunks) {
            val res = Uploader.upload(applicationContext, serverUrl, chunk)
            if (res.isFailure) return@withContext Result.retry()
        }
        settings.setBackupLastId(maxId)
        Result.success()
    }

    private fun scanCollection(
        cr: ContentResolver,
        collection: Uri,
        lastId: Long,
        emit: (Long, Uri) -> Unit,
    ) {
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns._ID} > ?"
        val args = arrayOf(lastId.toString())
        cr.query(collection, proj, sel, args, "${MediaStore.MediaColumns._ID} ASC")?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cur.moveToNext()) {
                val id = cur.getLong(idCol)
                emit(id, ContentUris.withAppendedId(collection, id))
            }
        }
    }

    companion object {
        const val WORK_NAME = "chitra-backup"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                // The one-time request below handles the initial backfill;
                // delaying the periodic run avoids both racing at enable time.
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
            // Kick off an immediate first pass too (backfills the whole
            // camera roll on first enable instead of waiting for the period).
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
                    )
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
