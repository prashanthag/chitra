import SwiftUI

/// Manual albums first (any photo, any folder), then the read-only folder
/// albums derived from the library root, then the phone folders that backup
/// uploads came from.
struct AlbumsView: View {
    let serverURL: String
    var onFolderAlbum: (Album) -> Void
    var onUserAlbum: (UserAlbum) -> Void

    @State private var folders: [Album]?
    @State private var mine: [UserAlbum] = []
    @State private var error: String?
    @State private var creating = false
    @State private var newName = ""
    @State private var reloadTick = 0

    private var api: PhotoAPI { PhotoAPI(baseUrl: serverURL) }
    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 12)]

    var body: some View {
        Group {
            if let error {
                Text("Error: \(error)").foregroundStyle(Palette.error)
            } else if folders == nil {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                content
            }
        }
        .navigationTitle("Albums")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: reloadTick) {
            do {
                mine = try await api.userAlbums()
                folders = try await api.albums()
            } catch {
                self.error = error.localizedDescription
            }
        }
        .alert("New album", isPresented: $creating) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) { newName = "" }
            Button("Create") {
                let name = newName.trimmingCharacters(in: .whitespaces)
                newName = ""
                guard !name.isEmpty else { return }
                Task {
                    if let created = try? await api.createUserAlbum(name: name) {
                        onUserAlbum(created.album)
                    }
                }
            }
        }
    }

    private var content: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 12) {
                Section {
                    Button { creating = true } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Color.clear
                                .aspectRatio(1, contentMode: .fit)
                                .overlay { Image(systemName: "plus").font(.system(size: 32)).foregroundStyle(.gray) }
                                .background(Palette.tile)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Text("New album").font(.subheadline)
                        }
                    }
                    .buttonStyle(.plain)

                    ForEach(mine) { album in
                        Button { onUserAlbum(album) } label: {
                            AlbumTile(
                                coverId: album.cover,
                                title: album.name,
                                subtitle: "\(album.count) items" + (album.shareToken != nil ? " · shared" : ""),
                                serverURL: serverURL)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    sectionLabel("My albums")
                }

                let phone = (folders ?? []).filter { $0.folder != nil }
                if !phone.isEmpty {
                    Section {
                        ForEach(phone) { album in folderTile(album) }
                    } header: {
                        sectionLabel("Phone folders")
                    }
                }

                Section {
                    ForEach((folders ?? []).filter { $0.folder == nil }) { album in folderTile(album) }
                } header: {
                    sectionLabel("Folders")
                }
            }
            .padding(12)
        }
    }

    private func folderTile(_ album: Album) -> some View {
        Button { onFolderAlbum(album) } label: {
            AlbumTile(
                coverId: album.cover,
                title: album.label,
                subtitle: "\(album.count) items" + (album.device.map { " · \($0)" } ?? ""),
                serverURL: serverURL)
        }
        .buttonStyle(.plain)
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.callout.weight(.medium))
            .foregroundStyle(Palette.secondaryText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
    }
}

private struct AlbumTile: View {
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
                    }
                }
                .background(Palette.tile)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            Text(title).font(.subheadline).lineLimit(1)
            Text(subtitle).font(.caption).foregroundStyle(Palette.secondaryText)
        }
    }
}

/// A folder album: the same sectioned grid as the main gallery, paged.
struct FolderAlbumView: View {
    let album: Album
    let serverURL: String
    var onItemTap: ([MediaItem], Int) -> Void

    @State private var items: [MediaItem] = []
    @State private var page = 0
    @State private var loading = false
    @State private var endReached = false

    var body: some View {
        GalleryGrid(
            items: items,
            serverURL: serverURL,
            onItemTap: { item in
                onItemTap(items, items.firstIndex(of: item) ?? 0)
            },
            onLoadMore: { load() })
        .navigationTitle("\(album.label) · \(album.count)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if items.isEmpty { load() } }
    }

    private func load() {
        guard !loading, !endReached else { return }
        loading = true
        Task {
            defer { loading = false }
            guard let response = try? await PhotoAPI(baseUrl: serverURL)
                .media(page: page + 1, perPage: 80, album: album.album, folder: album.folder)
            else { return }
            items += response.items
            page += 1
            if response.items.count < response.perPage { endReached = true }
        }
    }
}

/// A manual album: a static list, with a public share link and delete.
struct UserAlbumView: View {
    let album: UserAlbum
    let serverURL: String
    var reloadKey: Int = 0
    var onDeleted: () -> Void
    var onItemTap: ([MediaItem], Int) -> Void

    @State private var items: [MediaItem]?
    @State private var confirmDelete = false
    @State private var shareURL: URL?
    @State private var notice: String?

    private var api: PhotoAPI { PhotoAPI(baseUrl: serverURL) }

    var body: some View {
        Group {
            if let items {
                if items.isEmpty {
                    Text("Empty album. Open a photo and tap “Add to album”.")
                        .foregroundStyle(Palette.secondaryText)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    GalleryGrid(items: items, serverURL: serverURL) { item in
                        onItemTap(items, items.firstIndex(of: item) ?? 0)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("\(album.name) · \(items?.count ?? album.count)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    // Mint the public link and hand it to the share sheet.
                    Task {
                        do {
                            let response = try await api.shareUserAlbum(album.id)
                            let base = serverURL.hasSuffix("/") ? String(serverURL.dropLast()) : serverURL
                            shareURL = URL(string: base + response.url)
                        } catch {
                            notice = "Share failed: \(error.localizedDescription)"
                        }
                    }
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                Button(role: .destructive) { confirmDelete = true } label: {
                    Image(systemName: "trash")
                }
            }
        }
        .task(id: reloadKey) {
            items = (try? await api.userAlbumMedia(album.id)) ?? []
        }
        .alert("Delete album?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                Task {
                    try? await api.deleteUserAlbum(album.id)
                    onDeleted()
                }
            }
        } message: {
            Text("The photos stay in the library.")
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL { ShareSheet(items: [shareURL]) }
        }
        .alert("Album", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(notice ?? "")
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
                            Text("No albums yet. Create one below.")
                                .foregroundStyle(Palette.secondaryText)
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
                                    Text("\(album.count)").foregroundStyle(Palette.secondaryText)
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
                Section("New album") {
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
            .navigationTitle("Add to album")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .task(id: tick) {
                albums = (try? await api.userAlbums(mediaId: item.id)) ?? []
            }
        }
    }
}
