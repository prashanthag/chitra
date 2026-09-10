import SwiftUI

/// The Search tab. The system search field with a scope bar replaces the
/// custom text field and chip pair the Android client draws: "Filename"
/// searches names, "Smart" runs the server's CLIP index.
struct SearchView: View {
    let serverURL: String

    @State private var query = ""
    @State private var scope: SearchScope = .filename
    @State private var results: [MediaItem] = []
    @State private var searching = false
    @State private var error: String?
    @State private var viewer: ViewerPresentation?
    @State private var searchTask: Task<Void, Never>?

    enum SearchScope: String, CaseIterable {
        case filename, smart
        var label: String { self == .filename ? "Filename" : "Smart" }
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Search")
                .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                            prompt: scope == .smart ? "Try: dog, beach, sunset" : "Search file names")
                .searchScopes($scope) {
                    ForEach(SearchScope.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                .onChange(of: query) { _, _ in scheduleSearch() }
                .onChange(of: scope) { _, _ in scheduleSearch() }
        }
        .fullScreenCover(item: $viewer) { presentation in
            ViewerView(items: presentation.snapshot ?? [],
                       initialIndex: presentation.index,
                       serverURL: serverURL,
                       onAlbumChanged: {})
        }
    }

    @ViewBuilder
    private var content: some View {
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            ContentUnavailableView("Search the Library", systemImage: "magnifyingglass",
                                   description: Text("Filename matches names. Smart searches what is in the picture, using the server's CLIP index."))
        } else if searching && results.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error {
            ContentUnavailableView {
                Label("Search Failed", systemImage: "exclamationmark.triangle")
            } description: {
                // Smart search 503s when the server has no embeddings yet.
                Text(scope == .smart ? "The server has no CLIP index yet. Run clip_indexer.py, or search by filename." : error)
            }
        } else if results.isEmpty {
            ContentUnavailableView.search(text: query)
        } else {
            GalleryGrid(items: results, serverURL: serverURL) { item in
                viewer = ViewerPresentation(index: results.firstIndex(of: item) ?? 0, snapshot: results)
            }
        }
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else {
            results = []
            error = nil
            return
        }
        searching = true
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            let api = PhotoAPI(baseUrl: serverURL)
            do {
                let page = scope == .smart
                    ? try await api.searchSemantic(q: text, topK: 200)
                    : try await api.media(page: 1, perPage: 200, q: text)
                guard !Task.isCancelled else { return }
                results = page.items
                error = nil
            } catch {
                guard !Task.isCancelled else { return }
                results = []
                self.error = error.localizedDescription
            }
            searching = false
        }
    }
}
