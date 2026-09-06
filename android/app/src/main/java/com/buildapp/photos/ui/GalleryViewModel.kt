package com.buildapp.photos.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buildapp.photos.api.IdsBody
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.withContext

enum class Filter(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), FAVORITES("Favorites"),
    UPLOADS("Uploads"), ARCHIVED("Archived"), UNKNOWN("Unknown"), TRASH("Trash"),
    LOCKED("Locked folder");

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
    /** The server answered 401: sign in (accounts exist) or re-enter the password (Locked folder). */
    val needLogin: Boolean = false,
    val needUnlock: Boolean = false,
    val user: com.buildapp.photos.api.User? = null,
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsRepository(app.applicationContext)
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()
    private var api: PhotoApi? = null
    private var endReached = false
    // Bumped whenever the list is reset (refresh, filter, query, server). A
    // page that was in flight for an older generation is dropped instead of
    // being spliced into the new list.
    private var gen = 0

    init {
        viewModelScope.launch {
            val url = settings.serverUrl.first()
            val initial = Filter.byName(DebugHooks.initialFilter) ?: Filter.ALL
            _state.value = _state.value.copy(serverUrl = url, filter = initial)
            api = PhotoApi.create(url)
            refresh()
            refreshAuth()
        }
    }

    /**
     * Reload from page 1. The current tiles stay on screen until the fresh
     * first page arrives (no blank flash), then get replaced.
     */
    fun refresh() {
        gen++
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
        val g = gen
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
                val locked = if (s.filter == Filter.LOCKED) 1 else null
                // Semantic search is library-wide and ignores every filter, so
                // it never applies to the Uploads view (a name search does).
                val semantic = s.semantic && q != null && s.filter != Filter.UPLOADS
                val resp = if (semantic) {
                    // Semantic search returns top-k by CLIP similarity (single page).
                    api.searchSemantic(q = q!!, topK = 200)
                } else api.media(
                    page = nextPage, perPage = 80,
                    kind = kind, album = album, q = q,
                    favorites = fav, trashed = trashed, archived = archived,
                    dated = dated, undated = undated, sort = sort, locked = locked,
                )
                if (g != gen) return@launch   // list was reset while this page was in flight
                if (semantic || resp.items.size < resp.perPage) endReached = true
                _state.update { cur ->
                    cur.copy(
                        // Uploads land at the top of the feed while the user
                        // scrolls, shifting offset pages; a repeated id would
                        // be a duplicate LazyGrid key and crash the grid.
                        items = if (nextPage == 1) resp.items else (cur.items + resp.items).distinctBy { it.id },
                        page = nextPage,
                        total = resp.total,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                if (g != gen) return@launch
                val code = (e as? retrofit2.HttpException)?.code()
                _state.update {
                    it.copy(
                        loading = false, error = e.message ?: "error",
                        // 401 on the Locked folder means "password again"; anywhere else, sign in.
                        needUnlock = code == 401 && s.filter == Filter.LOCKED,
                        needLogin = code == 401 && s.filter != Filter.LOCKED,
                    )
                }
            }
        }
    }

    fun setFilter(filter: Filter) {
        if (_state.value.filter == filter) return
        gen++
        endReached = false
        _state.value = _state.value.copy(filter = filter, items = emptyList(), page = 0, error = null)
        loadNext()
    }

    fun setQuery(q: String) {
        if (_state.value.query == q) return
        gen++
        endReached = false
        _state.value = _state.value.copy(query = q, items = emptyList(), page = 0, error = null)
        loadNext()
    }

    fun setSemantic(semantic: Boolean) {
        if (_state.value.semantic == semantic) return
        gen++
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
                    // Runs on the IO thread while the main thread also writes
                    // _state; update{} is atomic, a read-copy-write is not.
                    _state.update {
                        it.copy(upload = UploadProgress(done, total, sent, dups, failed, running = done < total))
                    }
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
                val resp = api.rotate(item.id, degrees)
                // The server bumps edit_version and returns it; take its value
                // so the versioned (immutably cached) thumb URL changes and the
                // tile reloads, and stays in step with what a refresh returns.
                val fromServer = (resp["edit_version"] as? JsonPrimitive)?.intOrNull
                _state.value = _state.value.copy(
                    items = _state.value.items.map {
                        if (it.id == item.id) it.copy(editVersion = fromServer ?: (it.editVersion + 1)) else it
                    }
                )
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    /** Who is signed in (null while the server is open or after sign-out). */
    fun refreshAuth() {
        viewModelScope.launch {
            try {
                val a = api?.authState() ?: return@launch
                _state.update { it.copy(user = a.user) }
            } catch (_: Exception) {}
        }
    }

    fun login(name: String, password: String, onResult: (String?) -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val r = api.login(com.buildapp.photos.api.LoginBody(name, password))
                settings.setSession(r.token, r.user.name)
                _state.update { it.copy(needLogin = false, user = r.user, error = null) }
                onResult(null)
                refresh()
            } catch (e: Exception) {
                onResult(if ((e as? retrofit2.HttpException)?.code() == 401) "Wrong name or password" else (e.message ?: "error"))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try { api?.logout() } catch (_: Exception) {}
            settings.setSession(null, null)
            _state.update { it.copy(user = null) }
            refresh()
        }
    }

    /** Open the Locked folder for this session with the account password. */
    fun unlockLocked(password: String, onResult: (Boolean) -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            val ok = try { api.unlockLocked(com.buildapp.photos.api.PasswordBody(password)); true } catch (_: Exception) { false }
            if (ok) _state.update { it.copy(needUnlock = false) }
            onResult(ok)
            if (ok && _state.value.filter == Filter.LOCKED) refresh()
        }
    }

    fun clearAuthPrompts() { _state.update { it.copy(needLogin = false, needUnlock = false) } }

    /** Move an item into (or out of) my Locked folder; it leaves the current list either way. */
    fun setLocked(item: MediaItem, locked: Boolean) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                if (locked) api.lockMedia(IdsBody(listOf(item.id))) else api.unlockMedia(IdsBody(listOf(item.id)))
                _state.update { s -> s.copy(items = s.items.filterNot { it.id == item.id }) }
            } catch (e: Exception) { actionFailed(e) }
        }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch {
            gen++   // a page still in flight from the old server must not land in the new list
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
