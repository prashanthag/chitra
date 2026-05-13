package com.buildapp.photos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buildapp.photos.api.MediaItem
import com.buildapp.photos.api.PhotoApi
import com.buildapp.photos.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Filter { ALL, PHOTOS, VIDEOS, FAVORITES }

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
    val memories: com.buildapp.photos.api.Memories? = null,
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
            _state.value = _state.value.copy(serverUrl = url)
            api = PhotoApi.create(url)
            refresh()
        }
    }

    fun refresh() {
        endReached = false
        _state.value = _state.value.copy(items = emptyList(), page = 0, error = null)
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
                val q = s.query.takeIf { it.isNotBlank() }
                val resp = api.media(page = nextPage, perPage = 80, kind = kind, favorites = fav, q = q)
                if (resp.items.size < resp.perPage) endReached = true
                _state.value = _state.value.copy(
                    items = _state.value.items + resp.items,
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
            } catch (_: Exception) {}
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
