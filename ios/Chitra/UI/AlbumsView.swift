import SwiftUI

enum AlbumsRoute: Hashable {
    case userAlbum(UserAlbum)
    case folderAlbum(Album)
    case people
    case cluster(Cluster)
    case places
    case filtered(FilteredFeed)

    static func initialPath() -> [AlbumsRoute] {
        switch DebugHooks.initialRoute {
        case "people": return [.people]
        case "map": return [.places]
        default: return []
        }
    }
}

/// A server-side query given a name, so Media Types and Utilities can push a
/// grid without each needing its own screen.
struct FilteredFeed: Hashable {
    var title: String
    var kind: String?
    var favorites: Bool = false
    var archived: Bool = false
    var trashed: Bool = false
}

/// The Albums tab, laid out like Photos': a grid of albums up top, then
/// People & Places, Media Types and Utilities as plain rows.
struct AlbumsView: View {
    let serverURL: String
    var reloadKey: Int
    var onAlbumsChanged: () -> Void

    @State private var path: [AlbumsRoute] = AlbumsRoute.initialPath()
    @State private var folders: [Album]?
    @State private var mine: [UserAlbum] = []
    @State private var error: String?
    @State private var creating = false
    @State private var newName = ""
    @State private var tick = 0

    private var api: PhotoAPI { PhotoAPI(baseUrl: serverURL) }
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 16)]

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if let error {
                    ContentUnavailableView {
                        Label("Can't Reach the Server", systemImage: "wifi.exclamationmark")
                    } description: {
                        Text(error).font(.caption)
                    }
                } else if folders == nil {
                    ProgressView()
                } else {
                    list
                }
            }
            .navigationTitle("Albums")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { creating = true } label: { Image(systemName: "plus") }
                }
            }
            .navigationDestination(for: AlbumsRoute.self) { route in
                destination(route)
            }
            .task(id: "\(serverURL)-\(reloadKey)-\(tick)") { await load() }
            .refreshable { await load() }
            .alert("New Album", isPresented: $creating) {
                TextField("Name", text: $newName)
                Button("Cancel", role: .cancel) { newName = "" }
                Button("Create") {
                    let name = newName.trimmingCharacters(in: .whitespaces)
                    newName = ""
                    guard !name.isEmpty else { return }
                    Task {
                        if let created = try? await api.createUserAlbum(name: name) {
                            tick += 1
                            path.append(.userAlbum(created.album))
                        }
                    }
                }
            }
        }
    }

    // MARK: - Sections

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if !mine.isEmpty {
                    section("My Albums") {
                        LazyVGrid(columns: columns, spacing: 16) {
                            ForEach(mine) { album in
                                NavigationLink(value: AlbumsRoute.userAlbum(album)) {
                                    AlbumTile(coverId: album.cover, title: album.name,
                                              subtitle: "\(album.count)" + (album.shareToken != nil ? " · Shared" : ""),
                                              serverURL: serverURL)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                let phone = (folders ?? []).filter { $0.folder != nil }
                if !phone.isEmpty {
                    section("Phone Folders") {
                        LazyVGrid(columns: columns, spacing: 16) {
                            ForEach(phone) { album in
                                NavigationLink(value: AlbumsRoute.folderAlbum(album)) {
                                    AlbumTile(coverId: album.cover, title: album.label,
                                              subtitle: "\(album.count)" + (album.device.map { " · \($0)" } ?? ""),
                                              serverURL: serverURL)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                let library = (folders ?? []).filter { $0.folder == nil }
                if !library.isEmpty {
                    section("Folders") {
                        LazyVGrid(columns: columns, spacing: 16) {
                            ForEach(library) { album in
                                NavigationLink(value: AlbumsRoute.folderAlbum(album)) {
                                    AlbumTile(coverId: album.cover, title: album.label,
                                              subtitle: "\(album.count)", serverURL: serverURL)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                section("People & Places") {
                    rows {
                        row("People", "person.2.crop.square.stack", value: AlbumsRoute.people)
                        Divider().padding(.leading, 52)
                        row("Places", "map", value: AlbumsRoute.places)
                    }
                }

                section("Media Types") {
                    rows {
                        row("Photos", "photo", value: .filtered(FilteredFeed(title: "Photos", kind: "photo")))
                        Divider().padding(.leading, 52)
                        row("Videos", "video", value: .filtered(FilteredFeed(title: "Videos", kind: "video")))
                        Divider().padding(.leading, 52)
                        row("Favorites", "heart", value: .filtered(FilteredFeed(title: "Favorites", kind: nil, favorites: true)))
                    }
                }

                section("Utilities") {
                    rows {
                        row("Archived", "archivebox", value: .filtered(FilteredFeed(title: "Archived", kind: nil, archived: true)))
                        Divider().padding(.leading, 52)
                        row("Recently Deleted", "trash", value: .filtered(FilteredFeed(title: "Recently Deleted", kind: nil, trashed: true)))
                    }
                }
            }
            .padding(16)
        }
    }

    @ViewBuilder
    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title2.weight(.bold))
            content()
        }
    }

    @ViewBuilder
    private func rows<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func row(_ title: String, _ symbol: String, value: AlbumsRoute) -> some View {
        NavigationLink(value: value) {
            HStack(spacing: 12) {
                Image(systemName: symbol).frame(width: 28)
                Text(title)
                Spacer()
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func destination(_ route: AlbumsRoute) -> some View {
        switch route {
        case .userAlbum(let album):
            UserAlbumView(album: album, serverURL: serverURL, reloadKey: reloadKey,
                          onDeleted: { path.removeLast(); tick += 1 },
                          onAlbumChanged: onAlbumsChanged)
        case .folderAlbum(let album):
            FolderAlbumView(album: album, serverURL: serverURL, onAlbumChanged: onAlbumsChanged)
        case .people:
            PeopleView(serverURL: serverURL) { path.append(.cluster($0)) }
        case .cluster(let cluster):
            ClusterMediaView(cluster: cluster, serverURL: serverURL, onAlbumChanged: onAlbumsChanged)
        case .places:
            PhotoMapView(serverURL: serverURL)
        case .filtered(let feed):
            FilteredMediaView(feed: feed, serverURL: serverURL, onAlbumChanged: onAlbumsChanged)
        }
    }

    private func load() async {
        do {
            mine = try await api.userAlbums()
            folders = try await api.albums()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct AlbumTile: View {
    let coverId: String?
    let title: String
    let subtitle: String
    let serverURL: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    if let coverId {
                        RemoteImage(url: Urls.thumb(serverURL, coverId))
                    } else {
                        Image(systemName: "photo").font(.largeTitle).foregroundStyle(.tertiary)
                    }
                }
                .background(Palette.tile)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            Text(title).font(.subheadline).lineLimit(1)
            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

/// A paged server query — a folder album, a media type or a utility bucket.
/// One screen instead of four near-identical ones.
struct FilteredMediaView: View {
    var title: String
    var album: Album?
    var feed: FilteredFeed?
    let serverURL: String
    var onAlbumChanged: () -> Void

    @State private var items: [MediaItem] = []
    @State private var page = 0
    @State private var loading = false
    @State private var endReached = false
    @State private var viewer: ViewerPresentation?

    init(album: Album, serverURL: String, onAlbumChanged: @escaping () -> Void) {
        self.title = album.label
        self.album = album
        self.serverURL = serverURL
        self.onAlbumChanged = onAlbumChanged
    }

    init(feed: FilteredFeed, serverURL: String, onAlbumChanged: @escaping () -> Void) {
        self.title = feed.title
        self.feed = feed
        self.serverURL = serverURL
        self.onAlbumChanged = onAlbumChanged
    }

    var body: some View {
        Group {
            if items.isEmpty && !loading {
                ContentUnavailableView("Nothing Here", systemImage: "photo.on.rectangle.angled")
            } else {
                GalleryGrid(items: items, serverURL: serverURL, onItemTap: { item in
                    viewer = ViewerPresentation(index: items.firstIndex(of: item) ?? 0, snapshot: items)
                }, onLoadMore: { load() })
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if items.isEmpty { load() } }
        .fullScreenCover(item: $viewer) { presentation in
            ViewerView(items: presentation.snapshot ?? [],
                       initialIndex: presentation.index,
                       serverURL: serverURL,
                       onAlbumChanged: onAlbumChanged)
        }
    }

    private func load() {
        guard !loading, !endReached else { return }
        loading = true
        Task {
            defer { loading = false }
            let api = PhotoAPI(baseUrl: serverURL)
            let response = try? await api.media(
                page: page + 1, perPage: 80,
                kind: feed?.kind, album: album?.album, folder: album?.folder,
                favorites: feed?.favorites == true ? 1 : nil,
                trashed: feed?.trashed == true ? 1 : nil,
                archived: feed?.archived == true ? 1 : nil)
            guard let response else { return }
            items += response.items
            page += 1
            if response.items.count < response.perPage { endReached = true }
        }
    }
}

/// Kept as its own name so the Albums grid reads clearly.
func FolderAlbumView(album: Album, serverURL: String, onAlbumChanged: @escaping () -> Void) -> some View {
    FilteredMediaView(album: album, serverURL: serverURL, onAlbumChanged: onAlbumChanged)
}

/// A manual album: a static list, with a public share link and delete.
struct UserAlbumView: View {
    let album: UserAlbum
    let serverURL: String
    var reloadKey: Int = 0
    var onDeleted: () -> Void
    var onAlbumChanged: () -> Void

    @State private var items: [MediaItem]?
    @State private var confirmDelete = false
    @State private var shareURL: URL?
    @State private var notice: String?
    @State private var viewer: ViewerPresentation?
    @State private var tick = 0

    private var api: PhotoAPI { PhotoAPI(baseUrl: serverURL) }

    var body: some View {
        Group {
            if let items {
                if items.isEmpty {
                    ContentUnavailableView("Empty Album", systemImage: "rectangle.stack",
                                           description: Text("Open a photo and choose Add to Album."))
                } else {
                    GalleryGrid(items: items, serverURL: serverURL) { item in
                        viewer = ViewerPresentation(index: items.firstIndex(of: item) ?? 0, snapshot: items)
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(album.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        // Mint the public link and hand it to the share sheet.
                        Task {
                            do {
                                let response = try await api.shareUserAlbum(album.id)
                                let base = serverURL.hasSuffix("/") ? String(serverURL.dropLast()) : serverURL
                                shareURL = URL(string: base + response.url)
                            } catch {
                                notice = error.localizedDescription
                            }
                        }
                    } label: { Label("Share Link", systemImage: "link") }
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Delete Album", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .task(id: "\(album.id)-\(reloadKey)-\(tick)") {
            items = (try? await api.userAlbumMedia(album.id)) ?? []
        }
        .confirmationDialog("Delete “\(album.name)”?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete Album", role: .destructive) {
                Task {
                    try? await api.deleteUserAlbum(album.id)
                    onDeleted()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The photos stay in the library.")
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL { ShareSheet(items: [shareURL]) }
        }
        .alert("Share Failed", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(notice ?? "")
        }
        .fullScreenCover(item: $viewer) { presentation in
            ViewerView(items: presentation.snapshot ?? [],
                       initialIndex: presentation.index,
                       serverURL: serverURL,
                       onAlbumChanged: { onAlbumChanged(); tick += 1 })
        }
    }
}

/// Picker listing every manual album with a check for the ones this item is
/// in; tapping toggles membership.
struct AddToAlbumSheet: View {
    let item: MediaItem
    let serverURL: String
    var onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var albums: [UserAlbum]?
    @State private var newName = ""
    @State private var tick = 0

    private var api: PhotoAPI { PhotoAPI(baseUrl: serverURL) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let albums {
                        if albums.isEmpty {
                            Text("No albums yet.").foregroundStyle(.secondary)
                        }
                        ForEach(albums) { album in
                            Button {
                                Task {
                                    if album.contains == true {
                                        _ = try? await api.removeFromUserAlbum(album.id, ids: [item.id])
                                    } else {
                                        _ = try? await api.addToUserAlbum(album.id, ids: [item.id])
                                    }
                                    onChanged()
                                    tick += 1
                                }
                            } label: {
                                HStack {
                                    Text(album.name).foregroundStyle(.primary)
                                    Spacer()
                                    Text("\(album.count)").foregroundStyle(.secondary)
                                    if album.contains == true {
                                        Image(systemName: "checkmark").foregroundStyle(.tint)
                                    }
                                }
                            }
                        }
                    } else {
                        ProgressView()
                    }
                }
                Section("New Album") {
                    HStack {
                        TextField("Name", text: $newName)
                        Button("Create") {
                            let name = newName.trimmingCharacters(in: .whitespaces)
                            newName = ""
                            guard !name.isEmpty else { return }
                            Task {
                                _ = try? await api.createUserAlbum(name: name, mediaIds: [item.id])
                                onChanged()
                                tick += 1
                            }
                        }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            .navigationTitle("Add to Album")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .task(id: tick) {
                albums = (try? await api.userAlbums(mediaId: item.id)) ?? []
            }
        }
        .presentationDetents([.medium, .large])
    }
}
