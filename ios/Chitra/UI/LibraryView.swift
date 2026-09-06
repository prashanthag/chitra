import SwiftUI
import PhotosUI

/// The Library tab: every photo, newest first, under pinned month headers.
/// Filtering sits in a toolbar menu and multi-select in a "Select" button,
/// the way Photos arranges them, rather than in a chip rail and a floating
/// action button.
struct LibraryView: View {
    @ObservedObject var vm: GalleryViewModel

    @State private var path: [LibraryRoute] = LibraryRoute.initialPath()
    @State private var viewerIndex: Int?
    @State private var picked: [PhotosPickerItem] = []
    @State private var confirmDeleteSelection = false
    @State private var openedDebugViewer = false

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle(vm.filter == .all ? "Library" : vm.filter.label)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
                .navigationDestination(for: LibraryRoute.self) { route in
                    switch route {
                    case .settings:
                        SettingsView(serverURL: vm.serverURL) { vm.setServerURL($0) }
                    case .editor(let item):
                        EditorView(item: item, serverURL: vm.serverURL) { vm.refresh() }
                    }
                }
        }
        .fullScreenCover(item: Binding(
            get: { viewerIndex.map { ViewerPresentation(index: $0) } },
            set: { if $0 == nil { viewerIndex = nil } }
        )) { presentation in
            ViewerView(
                items: vm.items,
                initialIndex: presentation.index,
                serverURL: vm.serverURL,
                onToggleFavorite: { vm.toggleFavorite($0) },
                onTrash: { vm.trash($0) },
                onArchive: { vm.archive($0) },
                onRestore: { vm.restore($0) },
                onRotate: { vm.rotate($0) },
                onEdit: { item in
                    viewerIndex = nil
                    path.append(.editor(item))
                },
                onAlbumChanged: {})
        }
        .onChange(of: vm.items.isEmpty) { _, empty in
            // Debug builds only: CHITRA_ROUTE=viewer opens the first item so
            // the full-screen chrome can be looked at without a tap.
            if !empty, !openedDebugViewer, DebugHooks.initialRoute == "viewer" {
                openedDebugViewer = true
                viewerIndex = 0
            }
        }
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            vm.uploadPicked(items)
            picked = []
        }
        .confirmationDialog("Delete \(vm.selection.count) items permanently?",
                            isPresented: $confirmDeleteSelection, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { vm.deleteSelected() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the files from the server. Only items already in the trash can be deleted.")
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        VStack(spacing: 0) {
            if let upload = vm.upload {
                uploadBar(upload)
            }
            feed
        }
        .overlay(alignment: .bottom) { noticeBanner }
        .safeAreaInset(edge: .bottom) {
            if vm.isSelecting { selectionToolbar }
        }
        .refreshable { vm.refresh() }
    }

    @ViewBuilder
    private var feed: some View {
        if let error = vm.error, vm.items.isEmpty {
            unreachable(error)
        } else if vm.items.isEmpty && vm.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.items.isEmpty {
            ContentUnavailableView("No Photos", systemImage: "photo.on.rectangle.angled",
                                   description: Text("Nothing here for this filter yet."))
        } else {
            GalleryGrid(
                items: vm.items,
                serverURL: vm.serverURL,
                uploadsFeed: vm.filter == .uploads,
                selection: vm.isSelecting ? vm.selection : nil,
                onItemTap: { item in
                    if vm.isSelecting {
                        vm.toggleSelection(item)
                    } else {
                        viewerIndex = vm.items.firstIndex(of: item) ?? 0
                    }
                },
                onLoadMore: { vm.loadNext() },
                menu: { item in tileMenu(item) })
        }
    }

    @ViewBuilder
    private func tileMenu(_ item: MediaItem) -> some View {
        Button { vm.toggleFavorite(item) } label: {
            Label(item.isFavorite ? "Remove from Favorites" : "Favorite",
                  systemImage: item.isFavorite ? "heart.slash" : "heart")
        }
        if item.isTrashed {
            Button { vm.restore(item) } label: { Label("Restore", systemImage: "arrow.uturn.backward") }
        } else {
            Button { vm.archive(item) } label: {
                Label(item.archived == 1 ? "Unarchive" : "Archive",
                      systemImage: item.archived == 1 ? "tray.and.arrow.up" : "archivebox")
            }
            if !item.isVideo {
                Button { path.append(.editor(item)) } label: { Label("Adjust", systemImage: "slider.horizontal.3") }
            }
            Button(role: .destructive) { vm.trash(item) } label: { Label("Delete", systemImage: "trash") }
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if vm.isSelecting {
            ToolbarItem(placement: .topBarLeading) {
                Button(vm.selection.count == vm.items.count ? "Deselect All" : "Select All") {
                    vm.selection = vm.selection.count == vm.items.count ? [] : Set(vm.items.map(\.id))
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { vm.endSelection() }
            }
            ToolbarItem(placement: .principal) {
                Text(vm.selection.isEmpty ? "Select Items" : "\(vm.selection.count) Selected")
                    .font(.headline)
            }
        } else {
            ToolbarItem(placement: .topBarLeading) {
                Menu {
                    Picker("Filter", selection: Binding(get: { vm.filter }, set: { vm.setFilter($0) })) {
                        ForEach(Filter.allCases) { filter in
                            Label(filter.label, systemImage: filter.symbol).tag(filter)
                        }
                    }
                } label: {
                    Image(systemName: vm.filter == .all
                          ? "line.3.horizontal.decrease.circle"
                          : "line.3.horizontal.decrease.circle.fill")
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                PhotosPicker(selection: $picked, maxSelectionCount: 50,
                             matching: .any(of: [.images, .videos]), photoLibrary: .shared()) {
                    Image(systemName: "arrow.up.circle")
                }
                .disabled(vm.upload?.running == true)

                Menu {
                    Button { vm.beginSelection() } label: { Label("Select", systemImage: "checkmark.circle") }
                    Button { vm.rescan() } label: { Label("Rescan Library", systemImage: "arrow.clockwise") }
                    Divider()
                    Button { path.append(.settings) } label: { Label("Settings", systemImage: "gearshape") }
                    Section("Library") {
                        Text("\(vm.items.count) of \(vm.total) shown")
                        if vm.itemsIndexed > 0 { Text("\(vm.itemsIndexed) indexed on the server") }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }

    private var selectionToolbar: some View {
        HStack {
            Button { vm.favoriteSelected() } label: { Image(systemName: "heart") }
            Spacer()
            Button { vm.archiveSelected() } label: { Image(systemName: "archivebox") }
            Spacer()
            if vm.filter == .trash {
                Button { vm.restoreSelected() } label: { Image(systemName: "arrow.uturn.backward") }
                Spacer()
                Button(role: .destructive) { confirmDeleteSelection = true } label: {
                    Image(systemName: "trash.slash")
                }
            } else {
                Button(role: .destructive) { vm.trashSelected() } label: { Image(systemName: "trash") }
            }
        }
        .font(.system(size: 20))
        .padding(.horizontal, 40)
        .padding(.vertical, 12)
        .background(.bar)
        .disabled(vm.selection.isEmpty)
    }

    // MARK: - Bits

    private func uploadBar(_ upload: UploadProgress) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(upload.running ? "Uploading \(upload.done) of \(upload.total)…" : upload.summary)
                .font(.caption)
                .foregroundStyle(.secondary)
            ProgressView(value: upload.fraction)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }

    @ViewBuilder
    private var noticeBanner: some View {
        if let notice = vm.notice {
            Text(notice)
                .font(.footnote)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: notice) {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    withAnimation { vm.notice = nil }
                }
        }
    }

    private func unreachable(_ error: String) -> some View {
        ContentUnavailableView {
            Label("Can't Reach the Server", systemImage: "wifi.exclamationmark")
        } description: {
            Text(vm.serverURL)
            Text(error).font(.caption)
        } actions: {
            Button("Try Again") { vm.refresh() }
            Button("Server Settings") { path.append(.settings) }
        }
    }
}

enum LibraryRoute: Hashable {
    case settings
    case editor(MediaItem)

    static func initialPath() -> [LibraryRoute] {
        DebugHooks.initialRoute == "settings" ? [.settings] : []
    }
}

/// `fullScreenCover(item:)` needs something Identifiable; an index alone is not.
struct ViewerPresentation: Identifiable {
    let id = UUID()
    let index: Int
    var snapshot: [MediaItem]?
}

extension Filter {
    var symbol: String {
        switch self {
        case .all: return "square.grid.2x2"
        case .photos: return "photo"
        case .videos: return "video"
        case .favorites: return "heart"
        case .uploads: return "arrow.up.circle"
        case .archived: return "archivebox"
        case .unknown: return "questionmark.circle"
        case .trash: return "trash"
        }
    }
}
