import Foundation
import SwiftUI
import Photos
import PhotosUI

@MainActor
final class GalleryViewModel: ObservableObject {
    @Published private(set) var items: [MediaItem] = []
    @Published private(set) var loading = false
    @Published private(set) var error: String?
    @Published private(set) var total = 0
    @Published private(set) var itemsIndexed = 0
    @Published private(set) var memories: Memories?
    @Published private(set) var serverURL: String
    @Published var filter: Filter = .all
    @Published var query = ""
    @Published var semantic = false
    @Published var upload: UploadProgress?
    /// One-off message, surfaced as a brief overlay.
    @Published var notice: String?
    /// Selection mode, driven by the library's "Select" button.
    @Published var isSelecting = false
    @Published var selection: Set<String> = []

    private let settings = SettingsStore.shared
    private var api: PhotoAPI
    private var page = 0
    private var endReached = false
    /// Bumped whenever the list is reset (refresh, filter, query, server). A
    /// page that was in flight for an older generation is dropped instead of
    /// being spliced into the new list.
    private var generation = 0
    private var searchDebounce: Task<Void, Never>?

    init() {
        let url = settings.serverURL
        serverURL = url
        api = PhotoAPI(baseUrl: url)
        filter = DebugHooks.initialFilter ?? .all
        refresh()
    }

    // MARK: - Loading

    /// Reload from page 1. The current tiles stay on screen until the fresh
    /// first page arrives (no blank flash), then get replaced.
    func refresh() {
        generation += 1
        endReached = false
        page = 0
        error = nil
        loading = false
        loadNext()

        Task { [api] in
            if let health = try? await api.health() { itemsIndexed = health.itemsIndexed }
        }
        Task { [api] in
            memories = try? await api.memories()
        }
    }

    func loadNext() {
        guard !loading, !endReached else { return }
        loading = true
        let generationAtStart = generation
        let nextPage = page + 1
        let filter = filter
        let query = query.trimmingCharacters(in: .whitespaces)
        let semantic = semantic

        Task { [api] in
            do {
                let kind: String? = {
                    switch filter {
                    case .photos: return "photo"
                    case .videos: return "video"
                    default: return nil
                    }
                }()
                let q = query.isEmpty ? nil : query
                // Main feeds show only items with a real capture date; the
                // Unknown chip shows the rest (mirrors the web client).
                let undated = filter == .unknown ? 1 : nil
                let dated = (q == nil && [.all, .photos, .videos].contains(filter)) ? 1 : nil
                // Uploads: the phone-backup album, newest upload first.
                let album = filter == .uploads ? "uploads" : nil
                let sort = filter == .uploads ? "added" : nil
                // Semantic search is library-wide and ignores every filter, so
                // it never applies to the Uploads view (a name search does).
                let useSemantic = semantic && q != nil && filter != .uploads

                let response: MediaPage = useSemantic
                    ? try await api.searchSemantic(q: q!, topK: 200)
                    : try await api.media(
                        page: nextPage, perPage: 80, kind: kind, album: album, q: q,
                        favorites: filter == .favorites ? 1 : nil,
                        trashed: filter == .trash ? 1 : nil,
                        archived: filter == .archived ? 1 : nil,
                        dated: dated, undated: undated, sort: sort)

                guard generationAtStart == generation else { return }  // list was reset mid-flight
                if useSemantic || response.items.count < response.perPage { endReached = true }
                // Uploads land at the top of the feed while the user scrolls,
                // shifting offset pages; a repeated id would be a duplicate
                // SwiftUI identity and drop rows out of the grid.
                items = nextPage == 1 ? response.items : dedupe(items + response.items)
                page = nextPage
                total = response.total
                loading = false
                error = nil
            } catch {
                guard generationAtStart == generation else { return }
                loading = false
                self.error = error.localizedDescription
            }
        }
    }

    private func dedupe(_ list: [MediaItem]) -> [MediaItem] {
        var seen = Set<String>()
        return list.filter { seen.insert($0.id).inserted }
    }

    /// True once the grid has scrolled within 20 tiles of the end.
    func loadMoreIfNeeded(currentItem: MediaItem) {
        guard let index = items.firstIndex(of: currentItem) else { return }
        if index >= items.count - 20 { loadNext() }
    }

    private func resetAndLoad() {
        endSelection()
        generation += 1
        endReached = false
        items = []
        page = 0
        error = nil
        loading = false
        loadNext()
    }

    func setFilter(_ newFilter: Filter) {
        guard filter != newFilter else { return }
        filter = newFilter
        resetAndLoad()
    }

    /// Typing re-runs the search after a short pause, rather than firing one
    /// request per keystroke the way a plain text-change listener would.
    func setQuery(_ newQuery: String) {
        guard query != newQuery else { return }
        query = newQuery
        searchDebounce?.cancel()
        searchDebounce = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            self?.resetAndLoad()
        }
    }

    func setSemantic(_ on: Bool) {
        guard semantic != on else { return }
        semantic = on
        if !query.trimmingCharacters(in: .whitespaces).isEmpty { resetAndLoad() }
    }

    func setServerURL(_ url: String) {
        generation += 1   // a page still in flight from the old server must not land in the new list
        settings.setServerURL(url)
        serverURL = settings.serverURL
        api = PhotoAPI(baseUrl: serverURL)
        items = []
        page = 0
        total = 0
        endReached = false
        error = nil
        memories = nil
        refresh()
    }

    func rescan() {
        Task { [api] in
            try? await api.rescan()
            refresh()
        }
    }

    // MARK: - Item actions

    private func actionFailed(_ error: Error) {
        if let apiError = error as? APIError, apiError.isForbidden {
            notice = "Rejected: library is read-only"
        } else {
            notice = "Action failed: \(error.localizedDescription)"
        }
    }

    func toggleFavorite(_ item: MediaItem) {
        Task { [api] in
            do {
                let response = try await api.toggleFavorite(item.id)
                update(item.id) { $0.favorite = response.favorite ? 1 : 0 }
            } catch { actionFailed(error) }
        }
    }

    func trash(_ item: MediaItem) {
        Task { [api] in
            do {
                try await api.trash(item.id)
                remove(item.id)
            } catch { actionFailed(error) }
        }
    }

    func archive(_ item: MediaItem) {
        Task { [api] in
            do {
                try await api.archive(item.id)
                remove(item.id)
            } catch { actionFailed(error) }
        }
    }

    func restore(_ item: MediaItem) {
        Task { [api] in
            do {
                try await api.restore(item.id)
                items.removeAll { $0.id == item.id }
            } catch { actionFailed(error) }
        }
    }

    func rotate(_ item: MediaItem, degrees: Int = 90) {
        Task { [api] in
            do {
                // The server bumps edit_version and returns it; take its value
                // so the versioned (immutably cached) thumb URL changes and the
                // tile reloads, and stays in step with what a refresh returns.
                let fromServer = try await api.rotate(item.id, degrees: degrees)
                update(item.id) { $0.editVersion = fromServer ?? ($0.editVersion + 1) }
            } catch { actionFailed(error) }
        }
    }

    // MARK: - Selection

    func beginSelection() {
        isSelecting = true
        selection = []
    }

    func endSelection() {
        isSelecting = false
        selection = []
    }

    func toggleSelection(_ item: MediaItem) {
        if selection.contains(item.id) { selection.remove(item.id) } else { selection.insert(item.id) }
    }

    var selectedItems: [MediaItem] { items.filter { selection.contains($0.id) } }

    /// Trash, restore and permanent delete have batch endpoints. Favourite and
    /// archive do not, so those walk the selection one call at a time.
    func trashSelected() {
        runBatch { api, ids in try await api.batchTrash(ids) } thenRemove: { true }
    }

    func restoreSelected() {
        runBatch { api, ids in try await api.batchRestore(ids) } thenRemove: { true }
    }

    func deleteSelected() {
        runBatch { api, ids in try await api.batchDelete(ids) } thenRemove: { true }
    }

    func favoriteSelected() {
        let chosen = selectedItems
        endSelection()
        Task { [api] in
            for item in chosen where !item.isFavorite {
                if let response = try? await api.toggleFavorite(item.id) {
                    update(item.id) { $0.favorite = response.favorite ? 1 : 0 }
                }
            }
        }
    }

    func archiveSelected() {
        let ids = Array(selection)
        endSelection()
        Task { [api] in
            for id in ids {
                do {
                    try await api.archive(id)
                    remove(id)
                } catch { actionFailed(error) }
            }
        }
    }

    private func runBatch(_ call: @escaping (PhotoAPI, [String]) async throws -> Void,
                          thenRemove: @escaping () -> Bool) {
        let ids = Array(selection)
        guard !ids.isEmpty else { return }
        endSelection()
        Task { [api] in
            do {
                try await call(api, ids)
                if thenRemove() {
                    items.removeAll { ids.contains($0.id) }
                    total = max(0, total - ids.count)
                }
            } catch { actionFailed(error) }
        }
    }

    private func update(_ id: String, _ change: (inout MediaItem) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[index])
    }

    private func remove(_ id: String) {
        items.removeAll { $0.id == id }
        total = max(0, total - 1)
    }

    // MARK: - Manual upload

    /// Manual upload of picked items. Runs detached from any view's lifetime
    /// so leaving the screen no longer cancels it mid-transfer; one request
    /// per file so a failure costs one file, not the batch.
    func uploadPicked(_ picked: [PhotosPickerItem]) {
        guard !picked.isEmpty, upload?.running != true else { return }
        let serverUrl = serverURL
        upload = UploadProgress(total: picked.count)
        Task {
            let device = DeviceInfo.name()
            var sent = 0, duplicates = 0, failed = 0
            for (index, item) in picked.enumerated() {
                let result = await Self.uploadPickedItem(item, serverUrl: serverUrl, device: device)
                if !result.ok { failed += 1 } else if result.duplicate { duplicates += 1 } else { sent += 1 }
                upload = UploadProgress(done: index + 1, total: picked.count, sent: sent,
                                        duplicates: duplicates, failed: failed,
                                        running: index + 1 < picked.count)
            }
            let finished = UploadProgress(done: picked.count, total: picked.count, sent: sent,
                                          duplicates: duplicates, failed: failed, running: false)
            upload = finished
            notice = finished.summary
            // Show the user where the files went.
            if sent > 0 || duplicates > 0 {
                if filter == .uploads { refresh() } else { setFilter(.uploads) }
            }
        }
    }

    /// The picker hands back an opaque item. When the library is authorized it
    /// carries a PHAsset identifier and we can stream the original; otherwise
    /// the bytes come through the transfer API into a temp file.
    private static func uploadPickedItem(_ item: PhotosPickerItem, serverUrl: String, device: String) async -> Uploader.FileResult {
        if let identifier = item.itemIdentifier, let asset = DeviceMedia.asset(withId: identifier) {
            let info = DeviceMedia.resourceInfo(for: asset)
            return await Uploader.upload(asset: asset, name: info.name, serverUrl: serverUrl,
                                         source: Uploader.Source(folder: nil, device: device))
        }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            return Uploader.FileResult(name: "picked", ok: false, error: "could not read item")
        }
        let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
        let name = "IMG-\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)"
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        defer { try? FileManager.default.removeItem(at: temporary) }
        guard (try? data.write(to: temporary)) != nil else {
            return Uploader.FileResult(name: name, ok: false, error: "could not stage item")
        }
        return await Uploader.upload(fileAt: temporary, name: name, serverUrl: serverUrl,
                                     source: Uploader.Source(folder: nil, device: device))
    }

    func clearUpload() { upload = nil }
}
