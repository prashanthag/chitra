import SwiftUI
import AVKit

/// Full-screen pager, chromed the way Photos is: a slim top bar with the
/// capture date, actions on a bottom toolbar, both hidden by a tap, and a
/// downward drag to dismiss.
struct ViewerView: View {
    let items: [MediaItem]
    let initialIndex: Int
    let serverURL: String
    var onToggleFavorite: (MediaItem) -> Void = { _ in }
    var onTrash: ((MediaItem) -> Void)?
    var onArchive: ((MediaItem) -> Void)?
    var onRestore: ((MediaItem) -> Void)?
    var onRotate: ((MediaItem) -> Void)?
    var onEdit: ((MediaItem) -> Void)?
    /// nil hides "Add to Album".
    var onAlbumChanged: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var index: Int
    @State private var chromeVisible = true
    @State private var showInfo = false
    @State private var detail: MediaItem?
    @State private var shareURL: URL?
    @State private var preparingShare = false
    @State private var addingToAlbum: MediaItem?
    @State private var zoomed = false
    @State private var dragOffset: CGFloat = 0

    init(items: [MediaItem], initialIndex: Int, serverURL: String,
         onToggleFavorite: @escaping (MediaItem) -> Void = { _ in },
         onTrash: ((MediaItem) -> Void)? = nil,
         onArchive: ((MediaItem) -> Void)? = nil,
         onRestore: ((MediaItem) -> Void)? = nil,
         onRotate: ((MediaItem) -> Void)? = nil,
         onEdit: ((MediaItem) -> Void)? = nil,
         onAlbumChanged: (() -> Void)? = nil) {
        self.items = items
        self.initialIndex = initialIndex
        self.serverURL = serverURL
        self.onToggleFavorite = onToggleFavorite
        self.onTrash = onTrash
        self.onArchive = onArchive
        self.onRestore = onRestore
        self.onRotate = onRotate
        self.onEdit = onEdit
        self.onAlbumChanged = onAlbumChanged
        _index = State(initialValue: max(0, min(initialIndex, items.count - 1)))
    }

    private var current: MediaItem? {
        items.isEmpty ? nil : items[min(index, items.count - 1)]
    }

    /// How far through the dismiss drag we are, for the background fade.
    private var dismissProgress: CGFloat {
        min(1, abs(dragOffset) / 300)
    }

    var body: some View {
        ZStack {
            Color.black
                .opacity(1 - dismissProgress * 0.6)
                .ignoresSafeArea()

            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                    page(for: item, isCurrent: offset == index)
                        .tag(offset)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .offset(y: dragOffset)
            .scaleEffect(1 - dismissProgress * 0.15)

            if let item = current, chromeVisible {
                topBar(for: item)
                bottomBar(for: item)
            }
        }
        .statusBarHidden(!chromeVisible)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.2)) { chromeVisible.toggle() }
        }
        // A downward flick closes the viewer. Only when the photo is not
        // zoomed in — panning a zoomed photo owns the drag instead.
        .simultaneousGesture(
            zoomed ? nil :
                DragGesture(minimumDistance: 24)
                    .onChanged { value in
                        guard abs(value.translation.height) > abs(value.translation.width) else { return }
                        dragOffset = value.translation.height
                    }
                    .onEnded { value in
                        if dragOffset > 120 || value.predictedEndTranslation.height > 400 {
                            dismiss()
                        } else {
                            withAnimation(.spring(response: 0.3)) { dragOffset = 0 }
                        }
                    }
        )
        .onChange(of: index) { _, _ in
            detail = nil
            zoomed = false
        }
        .sheet(isPresented: $showInfo) {
            if let item = current {
                InfoSheet(item: detail ?? item, serverURL: serverURL) { detail = $0 }
            }
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL { ShareSheet(items: [shareURL]) }
        }
        .sheet(item: $addingToAlbum) { item in
            AddToAlbumSheet(item: item, serverURL: serverURL) { onAlbumChanged?() }
        }
        .onAppear { if items.isEmpty { dismiss() } }
    }

    // MARK: - Pages

    @ViewBuilder
    private func page(for item: MediaItem, isCurrent: Bool) -> some View {
        if item.isVideo {
            // Only the settled page gets a live player, so swiping never spins
            // up several decoders at once.
            if isCurrent {
                VideoPage(url: Urls.play(serverURL, item.id))
            } else {
                RemoteImage(url: Urls.thumb(serverURL, item.id, version: item.editVersion, w: 1024),
                            contentMode: .fit)
            }
        } else {
            // The cached 2048px preview: EXIF-rotated, HEIC/TIFF flattened,
            // immutable-cached. The original would be re-encoded by the server
            // on every swipe.
            ZoomableImage(url: Urls.preview(serverURL, item.id, version: item.editVersion),
                          zoomed: $zoomed)
        }
    }

    // MARK: - Chrome

    private func topBar(for item: MediaItem) -> some View {
        VStack {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 17, weight: .semibold))
                }
                Spacer()
                VStack(spacing: 0) {
                    Text(viewerDayLabel(item.takenAt)).font(.footnote.weight(.medium))
                    Text(viewerTimeLabel(item.takenAt)).font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                moreMenu(for: item)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.bar)
            Spacer()
        }
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    @ViewBuilder
    private func moreMenu(for item: MediaItem) -> some View {
        Menu {
            if !item.isTrashed {
                if onAlbumChanged != nil {
                    Button { addingToAlbum = item } label: { Label("Add to Album", systemImage: "rectangle.stack.badge.plus") }
                }
                if !item.isVideo {
                    if let onEdit {
                        Button { onEdit(item); dismiss() } label: { Label("Adjust", systemImage: "slider.horizontal.3") }
                    }
                    if let onRotate {
                        Button { onRotate(item) } label: { Label("Rotate", systemImage: "rotate.right") }
                    }
                }
                if let onArchive {
                    Button { onArchive(item); dismiss() } label: {
                        Label(item.archived == 1 ? "Unarchive" : "Archive",
                              systemImage: item.archived == 1 ? "tray.and.arrow.up" : "archivebox")
                    }
                }
            } else if let onRestore {
                Button { onRestore(item); dismiss() } label: { Label("Restore", systemImage: "arrow.uturn.backward") }
            }
        } label: {
            Image(systemName: "ellipsis.circle").font(.system(size: 17))
        }
    }

    private func bottomBar(for item: MediaItem) -> some View {
        VStack {
            Spacer()
            HStack {
                Button {
                    guard !preparingShare else { return }
                    preparingShare = true
                    Task {
                        shareURL = await Downloader.downloadForSharing(item: item, serverURL: serverURL)
                        preparingShare = false
                    }
                } label: {
                    Image(systemName: preparingShare ? "hourglass" : "square.and.arrow.up")
                }
                Spacer()
                Button { onToggleFavorite(item) } label: {
                    Image(systemName: item.isFavorite ? "heart.fill" : "heart")
                        .foregroundStyle(item.isFavorite ? Palette.favorite : Color.accentColor)
                }
                Spacer()
                Button { showInfo = true } label: { Image(systemName: "info.circle") }
                Spacer()
                if let onTrash, !item.isTrashed {
                    Button(role: .destructive) { onTrash(item); dismiss() } label: {
                        Image(systemName: "trash")
                    }
                } else {
                    Image(systemName: "trash").opacity(0)
                }
            }
            .font(.system(size: 20))
            .padding(.horizontal, 32)
            .padding(.vertical, 12)
            .background(.bar)
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

private func viewerDayLabel(_ epoch: Double?) -> String {
    guard let epoch, epoch > 0 else { return "Date unknown" }
    let formatter = DateFormatter()
    formatter.dateStyle = .long
    formatter.timeStyle = .none
    return formatter.string(from: Date(timeIntervalSince1970: epoch))
}

private func viewerTimeLabel(_ epoch: Double?) -> String {
    guard let epoch, epoch > 0 else { return "" }
    let formatter = DateFormatter()
    formatter.dateStyle = .none
    formatter.timeStyle = .short
    return formatter.string(from: Date(timeIntervalSince1970: epoch))
}

/// The metadata card, as a half-height sheet the way Photos shows "Info".
private struct InfoSheet: View {
    let item: MediaItem
    let serverURL: String
    var onDetail: (MediaItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var loaded: MediaItem?

    private var shown: MediaItem { loaded ?? item }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(infoRows(for: shown), id: \.0) { key, value in
                        LabeledContent(key, value: value)
                    }
                }
            }
            .navigationTitle(shown.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task {
            // The list API omits GPS/camera columns — fetch the full record.
            guard var fetched = try? await PhotoAPI(baseUrl: serverURL).meta(item.id) else { return }
            fetched.favorite = item.favorite
            loaded = fetched
            onDetail(fetched)
        }
    }

    private func infoRows(for item: MediaItem) -> [(String, String)] {
        var rows: [(String, String)] = [("Taken", takenAtLabel(item.takenAt))]
        let camera = [item.cameraMake, item.cameraModel].compactMap { $0 }.joined(separator: " ")
        if !camera.isEmpty { rows.append(("Camera", camera)) }
        let exposure = item.exposure ?? [:]
        for (key, label) in [("lens", "Lens"), ("aperture", "Aperture"), ("shutter", "Shutter"),
                             ("iso", "ISO"), ("focal_length", "Focal length"),
                             ("exposure_bias", "Exposure bias"), ("flash", "Flash")] {
            if let value = exposure[key] { rows.append((label, value)) }
        }
        let video = item.video ?? [:]
        for (key, label) in [("duration", "Duration"), ("codec", "Codec"),
                             ("frame_rate", "Frame rate"), ("bitrate", "Bitrate")] {
            if let value = video[key] { rows.append((label, value)) }
        }
        if let width = item.width, let height = item.height {
            rows.append(("Resolution", "\(width) × \(height)"))
        }
        if let size = item.size { rows.append(("Size", byteLabel(size))) }
        rows.append(("Type", "\(item.kind) · \(item.ext.replacingOccurrences(of: ".", with: "").uppercased())"))
        if let album = item.album { rows.append(("Folder", album)) }
        if let folder = item.sourceFolder { rows.append(("Phone folder", folder)) }
        if let device = item.sourceDevice { rows.append(("Backed up from", device)) }
        if let place = item.place { rows.append(("Place", place)) }
        if let lat = item.lat, let lng = item.lng {
            rows.append(("Location", String(format: "%.5f, %.5f", lat, lng)))
        }
        return rows
    }
}

/// A photo that pinches and double-taps to zoom. `zoomed` is lifted out so the
/// viewer can stand its dismiss gesture down while the photo is magnified.
private struct ZoomableImage: View {
    let url: String
    @Binding var zoomed: Bool

    @State private var scale: CGFloat = 1
    @State private var pinch: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var drag: CGSize = .zero

    private var total: CGFloat { max(1, scale * pinch) }

    var body: some View {
        RemoteImage(url: url, contentMode: .fit)
            .scaleEffect(total)
            .offset(x: offset.width + drag.width, y: offset.height + drag.height)
            .gesture(
                MagnificationGesture()
                    .onChanged { pinch = $0 }
                    .onEnded { _ in
                        scale = max(1, scale * pinch)
                        pinch = 1
                        if scale == 1 { offset = .zero }
                        zoomed = scale > 1
                    }
            )
            .simultaneousGesture(
                total > 1
                    ? DragGesture()
                        .onChanged { drag = $0.translation }
                        .onEnded { _ in
                            offset.width += drag.width
                            offset.height += drag.height
                            drag = .zero
                        }
                    : nil
            )
            .onTapGesture(count: 2) {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if scale > 1 { scale = 1; offset = .zero } else { scale = 2.5 }
                    zoomed = scale > 1
                }
            }
            .onChange(of: url) { _, _ in
                scale = 1; pinch = 1; offset = .zero; drag = .zero; zoomed = false
            }
    }
}

/// The server's /play endpoint redirects to the original or to a live 1080p
/// H.264 transcode, so AVPlayer can play whatever the library holds.
private struct VideoPage: View {
    let url: String
    @State private var player: AVPlayer?

    var body: some View {
        Group {
            if let player {
                VideoPlayer(player: player)
            } else {
                Color.black
            }
        }
        .onAppear {
            guard let source = URL(string: url) else { return }
            let created = AVPlayer(url: source)
            created.play()
            player = created
        }
        .onDisappear {
            player?.pause()
            player = nil
        }
    }
}
