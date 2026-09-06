import SwiftUI
import PhotosUI

private enum Route: Hashable {
    case people
    case cluster(Cluster)
    case albums
    case folderAlbum(Album)
    case userAlbum(UserAlbum)
    case map
    case editor(MediaItem)
    case settings

    static func initialPath() -> [Route] {
        switch DebugHooks.initialRoute {
        case "settings": return [.settings]
        case "albums": return [.albums]
        case "people": return [.people]
        case "map": return [.map]
        default: return []
        }
    }
}

/// What the full-screen viewer is showing. A live target follows the feed (so
/// a favorite toggle is reflected straight away); a static one is a snapshot
/// taken from an album, a cluster or the memories strip.
private struct ViewerTarget: Identifiable {
    let id = UUID()
    var snapshot: [MediaItem]?
    var index: Int
}

struct ContentView: View {
    @StateObject private var vm = GalleryViewModel()
    @ObservedObject private var backup = BackupService.shared

    /// Seeded from the debug launch environment. Pushing in onAppear instead
    /// races the navigation destinations being registered, and the push is
    /// sometimes dropped.
    @State private var path: [Route] = Route.initialPath()
    @State private var viewer: ViewerTarget?
    @State private var albumsChanged = 0
    @State private var showSearch = false
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        NavigationStack(path: $path) {
            gallery
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                }
        }
        .tint(.accentColor)
        .fullScreenCover(item: $viewer) { target in
            let items = target.snapshot ?? vm.items
            ViewerView(
                items: items,
                initialIndex: target.index,
                serverURL: vm.serverURL,
                onToggleFavorite: { vm.toggleFavorite($0) },
                onTrash: target.snapshot == nil ? { vm.trash($0) } : nil,
                onArchive: target.snapshot == nil ? { vm.archive($0) } : nil,
                onRestore: target.snapshot == nil ? { vm.restore($0) } : nil,
                onRotate: target.snapshot == nil ? { vm.rotate($0) } : nil,
                onEdit: target.snapshot == nil ? { item in
                    viewer = nil
                    path.append(.editor(item))
                } : nil,
                onAlbumChanged: { albumsChanged += 1 })
        }
        .onAppear {
            // scenePhase is already .active by the time the first view appears,
            // so its onChange never fires for the launch itself.
            backup.resumeIfEnabled()
        }
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            vm.uploadPicked(items)
            picked = []
        }
    }

    // MARK: - Gallery

    private var gallery: some View {
        VStack(spacing: 0) {
            if showSearch { searchBar }
            filterRow
            if let upload = vm.upload {
                VStack(alignment: .leading, spacing: 4) {
                    Text(upload.running ? "Uploading \(upload.done)/\(upload.total)…" : upload.summary)
                        .font(.caption)
                        .foregroundStyle(Palette.secondaryText)
                    ProgressView(value: upload.fraction)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
            }
            if let memories = vm.memories, !memories.groups.isEmpty {
                MemoriesRow(memories: memories, serverURL: vm.serverURL) { item in
                    viewer = ViewerTarget(snapshot: [item], index: 0)
                }
            }
            feed
        }
        .background(Color(.systemBackground))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Photos").font(.headline)
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.secondaryText)
                        .lineLimit(1)
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { path.append(.map) } label: { Image(systemName: "map") }
                Button { path.append(.albums) } label: { Image(systemName: "rectangle.stack") }
                Button { path.append(.people) } label: { Image(systemName: "person.crop.circle") }
                Button {
                    showSearch.toggle()
                    if !showSearch { vm.setQuery("") }
                } label: {
                    Image(systemName: "magnifyingglass")
                }
                Button { path.append(.settings) } label: { Image(systemName: "gearshape") }
            }
        }
        .overlay(alignment: .bottomTrailing) { uploadButton }
        .overlay(alignment: .bottom) { noticeBanner }
        .refreshable { vm.refresh() }
    }

    @ViewBuilder
    private var feed: some View {
        if let error = vm.error, vm.items.isEmpty {
            errorView(error)
        } else if vm.items.isEmpty && vm.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.items.isEmpty {
            Text("No items")
                .foregroundStyle(Palette.secondaryText)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            GalleryGrid(
                items: vm.items,
                serverURL: vm.serverURL,
                uploadsFeed: vm.filter == .uploads,
                onItemTap: { item in
                    viewer = ViewerTarget(snapshot: nil, index: vm.items.firstIndex(of: item) ?? 0)
                },
                onLoadMore: { vm.loadNext() })
        }
    }

    private var subtitle: String {
        var parts = ["\(vm.items.count)/\(vm.total)"]
        if vm.itemsIndexed > 0 { parts.append("\(vm.itemsIndexed) indexed") }
        let query = vm.query.trimmingCharacters(in: .whitespaces)
        if !query.isEmpty { parts.append("q=\"\(query)\"") }
        return parts.joined(separator: " · ")
    }

    private var searchBar: some View {
        VStack(spacing: 6) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(Palette.secondaryText)
                TextField(vm.semantic ? "Try: dog, beach, sunset…" : "Search filename…",
                          text: Binding(get: { vm.query }, set: { vm.setQuery($0) }))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Done") {
                    showSearch = false
                    vm.setQuery("")
                }
            }
            HStack(spacing: 6) {
                chip("Filename", selected: !vm.semantic) { vm.setSemantic(false) }
                chip("Smart (CLIP)", selected: vm.semantic) { vm.setSemantic(true) }
                Spacer()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var filterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Filter.allCases) { filter in
                    chip(filter.label, selected: vm.filter == filter) { vm.setFilter(filter) }
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Color.accentColor.opacity(0.3) : Palette.tile,
                            in: Capsule())
                .overlay(Capsule().stroke(selected ? Color.accentColor : Palette.divider, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var uploadButton: some View {
        PhotosPicker(
            selection: $picked,
            maxSelectionCount: 50,
            matching: .any(of: [.images, .videos]),
            photoLibrary: .shared()
        ) {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .medium))
                .frame(width: 56, height: 56)
                .background(Color.accentColor, in: Circle())
                .foregroundStyle(.white)
                .shadow(radius: 6)
        }
        // One batch at a time: a pick made mid-upload used to be silently
        // discarded.
        .disabled(vm.upload?.running == true)
        .opacity(vm.upload?.running == true ? 0.5 : 1)
        .padding(20)
    }

    @ViewBuilder
    private var noticeBanner: some View {
        if let notice = vm.notice {
            Text(notice)
                .font(.footnote)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Palette.panel, in: Capsule())
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: notice) {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    withAnimation { vm.notice = nil }
                }
        }
    }

    private func errorView(_ error: String) -> some View {
        VStack(spacing: 8) {
            Text("Couldn't reach server").font(.headline)
            Text(vm.serverURL).font(.caption).foregroundStyle(Palette.secondaryText)
            Text(error).font(.caption).foregroundStyle(Palette.error).multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button("Server settings") { path.append(.settings) }
                Button("Retry") { vm.refresh() }
            }
            .padding(.top, 8)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Routes

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .people:
            PeopleView(serverURL: vm.serverURL) { path.append(.cluster($0)) }

        case .cluster(let cluster):
            ClusterMediaView(cluster: cluster, serverURL: vm.serverURL) { items, index in
                viewer = ViewerTarget(snapshot: items, index: index)
            }

        case .albums:
            AlbumsView(
                serverURL: vm.serverURL,
                onFolderAlbum: { path.append(.folderAlbum($0)) },
                onUserAlbum: { path.append(.userAlbum($0)) })

        case .folderAlbum(let album):
            FolderAlbumView(album: album, serverURL: vm.serverURL) { items, index in
                viewer = ViewerTarget(snapshot: items, index: index)
            }

        case .userAlbum(let album):
            UserAlbumView(
                album: album,
                serverURL: vm.serverURL,
                reloadKey: albumsChanged,
                onDeleted: { path.removeLast() },
                onItemTap: { items, index in
                    viewer = ViewerTarget(snapshot: items, index: index)
                })

        case .map:
            PhotoMapView(serverURL: vm.serverURL) { location in
                if let index = vm.items.firstIndex(where: { $0.id == location.id }) {
                    viewer = ViewerTarget(snapshot: nil, index: index)
                } else {
                    viewer = ViewerTarget(snapshot: [MediaItem(id: location.id, name: location.name, kind: location.kind)], index: 0)
                }
            }

        case .editor(let item):
            EditorView(item: item, serverURL: vm.serverURL) { vm.refresh() }

        case .settings:
            SettingsView(serverURL: vm.serverURL) { vm.setServerURL($0) }
        }
    }
}
