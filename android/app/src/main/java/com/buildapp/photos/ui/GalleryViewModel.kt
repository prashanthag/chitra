package com.buildapp.photos.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.data.DebugHooks
import com.buildapp.photos.data.SettingsRepository
import com.buildapp.photos.data.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Filter(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), FAVORITES("Favorites"),
    UPLOADS("Uploads"), ARCHIVED("Archived"), UNKNOWN("Unknown"), TRASH("Trash");

    companion object {
        fun byName(name: String?): Filter? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Progress of a manual pick-and-upload; shown under the filter row. */
data class UploadProgress(
    val done: Int = 0,
    val total: Int = 0,
    val sent: Int = 0,
    val duplicates: Int = 0,
    val failed: Int = 0,
    val running: Boolean = true,
) {
    val summary: String
        get() = buildString {
            append("Uploaded $sent of $total")
            if (duplicates > 0) append(", $duplicates already in library")
            if (failed > 0) append(", $failed failed")
        }
}

data class GalleryState(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val total: Int = 0,
    val serverUrl: String = "",
    val itemsIndexed: Int = 0,
    val filter: Filter = Filter.ALL,
    val query: String = "",
    val semantic: Boolean = false,
    val memories: com.buildapp.photos.api.Memories? = null,
    val upload: UploadProgress? = null,
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsRepository(app.applicationContext)
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()
    private var api: PhotoApi? = null
    private var endReached = false

    init {
        viewModelScope.launch {
            val url = settings.serverUrl.first()
            val initial = Filter.byName(DebugHooks.initialFilter) ?: Filter.ALL
            _state.value = _state.value.copy(serverUrl = url, filter = initial)
            api = PhotoApi.create(url)
            refresh()
        }
    }

    /**
     * Reload from page 1. The current tiles stay on screen until the fresh
     * first page arrives (no blank flash), then get replaced.
     */
    fun refresh() {
        endReached = false
        _state.value = _state.value.copy(page = 0, error = null, loading = false)
        loadNext()
        viewModelScope.launch {
            try {
                val h = api?.health() ?: return@launch
                _state.value = _state.value.copy(itemsIndexed = h.itemsIndexed)
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            try {
                val mem = api?.memories()
                _state.value = _state.value.copy(memories = mem)
            } catch (_: Exception) {}
        }
    }

    fun loadNext() {
        val api = api ?: return
        val s = _state.value
        if (s.loading || endReached) return
        _state.value = s.copy(loading = true)
        viewModelScope.launch {
            try {
                val nextPage = s.page + 1
                val kind = when (s.filter) {
                    Filter.PHOTOS -> "photo"
                    Filter.VIDEOS -> "video"
                    else -> null
                }
                val fav = if (s.filter == Filter.FAVORITES) 1 else null
                val trashed = if (s.filter == Filter.TRASH) 1 else null
                val archived = if (s.filter == Filter.ARCHIVED) 1 else null
                val q = s.query.takeIf { it.isNotBlank() }
                // Main feeds show only items with a real capture date; the
                // Unknown chip shows the rest (mirrors the web client).
                val undated = if (s.filter == Filter.UNKNOWN) 1 else null
                val dated = if (q == null && s.filter in listOf(Filter.ALL, Filter.PHOTOS, Filter.VIDEOS)) 1 else null
                // Uploads: the phone-backup album, newest upload first.
                val album = if (s.filter == Filter.UPLOADS) "uploads" else null
                val sort = if (s.filter == Filter.UPLOADS) "added" else null
                val resp = if (s.semantic && q != null) {
                    // Semantic search returns top-k by CLIP similarity (single page).
                    val r = api.searchSemantic(q = q, topK = 200)
                    endReached = true
                    r
                } else api.media(
                    page = nextPage, perPage = 80,
                    kind = kind, album = album, q = q,
                    favorites = fav, trashed = trashed, archived = archived,
                    dated = dated, undated = undated, sort = sort,
                )
                if (resp.items.size < resp.perPage) endReached = true
                _state.value = _state.value.copy(
                    items = if (nextPage == 1) resp.items else _state.value.items + resp.items,
                    page = nextPage,
                    total = resp.total,
                    loading = false,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "error")
            }
        }
    }

    fun setFilter(filter: Filter) {
        if (_state.value.filter == filter) return
        endReached = false
        _state.value = _state.value.copy(filter = filter, items = emptyList(), page = 0, error = null)
        loadNext()
    }

    fun setQuery(q: String) {
        if (_state.value.query == q) return
        endReached = false
        _state.value = _state.value.copy(query = q, items = emptyList(), page = 0, error = null)
        loadNext()
    }

    fun setSemantic(semantic: Boolean) {
        if (_state.value.semantic == semantic) return
        endReached = false
        _state.value = _state.value.copy(semantic = semantic, items = emptyList(), page = 0, error = null)
        if (_state.value.query.isNotBlank()) loadNext()
    }

    /**
     * Manual upload of picked items. Runs in the ViewModel scope so a
     * rotation or a dismissed snackbar no longer cancels it mid-transfer;
     * one request per file so a failure costs one file, not the batch.
     */
    fun uploadPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.upload?.running == true) return
        val url = _state.value.serverUrl
        _state.value = _state.value.copy(upload = UploadProgress(total = uris.size))
        viewModelScope.launch {
            var sent = 0
            var dups = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                Uploader.uploadAll(getApplication(), url, uris) { done, total, r ->
                    if (!r.ok) failed++ else if (r.duplicate) dups++ else sent++
                    _state.value = _state.value.copy(
                        upload = UploadProgress(done, total, sent, dups, failed, running = done < total),
                    )
                }
            }
            _state.value = _state.value.copy(
                upload = UploadProgress(uris.size, uris.size, sent, dups, failed, running = false),
            )
            // Show the user where the files went.
            if (sent > 0 || dups > 0) {
                if (_state.value.filter == Filter.UPLOADS) refresh() else setFilter(Filter.UPLOADS)
            }
        }
    }

    fun clearUpload() {
        _state.value = _state.value.copy(upload = null)
    }

    fun toggleFavorite(item: MediaItem) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val r = api.toggleFavorite(item.id)
                _state.value = _state.value.copy(
                    items = _state.value.items.map {
                        if (it.id == item.id) it.copy(favorite = if (r.favorite) 1 else 0) else it
                    }
                )
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    private fun actionFailed(e: Exception) {
        val msg = if (e.message?.contains("403") == true)
            "Rejected: library is read-only" else "Action failed: ${e.message}"
        android.widget.Toast.makeText(getApplication(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun trash(item: MediaItem) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.trash(item.id)
                _state.value = _state.value.copy(
                    items = _state.value.items.filterNot { it.id == item.id },
                    total = _state.value.total - 1,
                )
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    fun archive(item: MediaItem) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.archive(item.id)
                _state.value = _state.value.copy(
                    items = _state.value.items.filterNot { it.id == item.id },
                    total = _state.value.total - 1,
                )
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    fun restore(item: MediaItem) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.restore(item.id)
                _state.value = _state.value.copy(
                    items = _state.value.items.filterNot { it.id == item.id },
                )
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    fun rotate(item: MediaItem, degrees: Int = 90) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.rotate(item.id, degrees)
                // The server bumps edit_version; mirror it so the versioned
                // (immutably cached) thumb URL changes and the tile reloads.
                _state.value = _state.value.copy(
                    items = _state.value.items.map {
                        if (it.id == item.id) it.copy(editVersion = it.editVersion + 1) else it
                    }
                )
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch {
            settings.setServerUrl(url)
            api = PhotoApi.create(url)
            _state.value = GalleryState(serverUrl = url)
            refresh()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            try { api?.rescan() } catch (_: Exception) {}
            refresh()
        }
    }
}
