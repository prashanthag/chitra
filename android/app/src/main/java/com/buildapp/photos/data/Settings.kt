package com.buildapp.photos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
}

class SettingsRepository(private val context: Context) {
    val serverUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[SettingsKeys.SERVER_URL] ?: "http://192.168.68.74:8000"
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[SettingsKeys.SERVER_URL] = url }
    }
}
