package com.buildapp.photos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
    val BACKUP_LAST_ID = longPreferencesKey("backup_last_id")   // legacy cursor, unused
    val BACKUP_BUCKETS = stringSetPreferencesKey("backup_buckets")
    val BACKUP_BUCKETS_SET = booleanPreferencesKey("backup_buckets_set")
    val BACKUP_VIDEOS = booleanPreferencesKey("backup_videos")
    val BACKUP_WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
    val BACKUP_LAST_RUN = longPreferencesKey("backup_last_run")
    val BACKUP_LAST_RESULT = stringPreferencesKey("backup_last_result")
    val BACKUP_PENDING = intPreferencesKey("backup_pending")
}

/** Everything the backup worker and the Settings screen share. */
data class BackupPrefs(
    val enabled: Boolean = false,
    /** null = the user never chose; the worker falls back to the Camera folder. */
    val bucketIds: Set<Long>? = null,
    val includeVideos: Boolean = true,
    val wifiOnly: Boolean = true,
    val lastRun: Long = 0L,
    val lastResult: String = "",
    val pending: Int = -1,
)

class SettingsRepository(private val context: Context) {
    val serverUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[SettingsKeys.SERVER_URL] ?: DEFAULT_SERVER_URL
    }
    val backupEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[SettingsKeys.BACKUP_ENABLED] ?: false
    }
    val backupLastId: Flow<Long> = context.settingsDataStore.data.map {
        it[SettingsKeys.BACKUP_LAST_ID] ?: 0L
    }
    val backup: Flow<BackupPrefs> = context.settingsDataStore.data.map { p ->
        BackupPrefs(
            enabled = p[SettingsKeys.BACKUP_ENABLED] ?: false,
            bucketIds = if (p[SettingsKeys.BACKUP_BUCKETS_SET] == true)
                (p[SettingsKeys.BACKUP_BUCKETS] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
            else null,
            includeVideos = p[SettingsKeys.BACKUP_VIDEOS] ?: true,
            wifiOnly = p[SettingsKeys.BACKUP_WIFI_ONLY] ?: true,
            lastRun = p[SettingsKeys.BACKUP_LAST_RUN] ?: 0L,
            lastResult = p[SettingsKeys.BACKUP_LAST_RESULT] ?: "",
            pending = p[SettingsKeys.BACKUP_PENDING] ?: -1,
        )
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[SettingsKeys.SERVER_URL] = url }
    }

    suspend fun setBackupEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.BACKUP_ENABLED] = enabled }
    }

    suspend fun setBackupLastId(id: Long) {
        context.settingsDataStore.edit { it[SettingsKeys.BACKUP_LAST_ID] = id }
    }

    suspend fun setBackupBuckets(ids: Set<Long>) {
        context.settingsDataStore.edit {
            it[SettingsKeys.BACKUP_BUCKETS] = ids.map { id -> id.toString() }.toSet()
            it[SettingsKeys.BACKUP_BUCKETS_SET] = true
        }
    }

    suspend fun setBackupVideos(on: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.BACKUP_VIDEOS] = on }
    }

    suspend fun setBackupWifiOnly(on: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.BACKUP_WIFI_ONLY] = on }
    }

    suspend fun setBackupStatus(lastRun: Long, result: String, pending: Int) {
        context.settingsDataStore.edit {
            it[SettingsKeys.BACKUP_LAST_RUN] = lastRun
            it[SettingsKeys.BACKUP_LAST_RESULT] = result
            it[SettingsKeys.BACKUP_PENDING] = pending
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://192.168.68.74:8000"
    }
}
