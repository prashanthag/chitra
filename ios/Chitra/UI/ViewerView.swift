import SwiftUI
import AVKit

/// Full-screen pager over a list of items, with the same action row the
/// Android `ViewerDialog` puts in its top-right corner.
struct ViewerView: View {
    let items: [MediaItem]
    let initialIndex: Int
    let serverURL: String
    var onToggleFavorite: (MediaItem) -> Void = { _ in }
    var onTrash: ((MediaItem) -> Void)? = nil
    var onArchive: ((MediaItem) -> Void)? = nil
    var onRestore: ((MediaItem) -> Void)? = nil
    var onRotate: ((MediaItem) -> Void)? = nil
    var onEdit: ((MediaItem) -> Void)? = nil
    /// nil hides the button (a trashed item, or a list that isn't editable).
    var onAlbumChanged: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var index: Int
    @State private var showInfo = false
    @State private var detail: MediaItem?
    @State private var shareURL: URL?
    @State private var preparingShare = false
    @State private var addingToAlbum: MediaItem?

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
        guard !items.isEmpty else { return nil }
        return items[min(index, items.count - 1)]
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                    page(for: item, isCurrent: offset == index)
                        .tag(offset)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()

            if let item = current {
                actionBar(for: item)
                if showInfo {
                    infoPanel(for: detail ?? item)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                        .padding(16)
                }
            }
        }
        .statusBarHidden()
        .onChange(of: index) { _, _ in
            showInfo = false
            detail = nil
        }
        .task(id: showInfo ? current?.id : nil) {
            // The list API omits GPS/camera columns — fetch the full record.
            guard showInfo, let item = current else { return }
            var fetched = try? await PhotoAPI(baseUrl: serverURL).meta(item.id)
            fetched?.favorite = item.favorite
            detail = fetched
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL { ShareSheet(items: [shareURL]) }
        }
        .sheet(item: $addingToAlbum) { item in
            AddToAlbumSheet(item: item, serverURL: serverURL) { onAlbumChanged?() }
        }
        .onAppear { if items.isEmpty { dismiss() } }
    }

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
            ZoomableImage(url: Urls.preview(serverURL, item.id, version: item.editVersion))
        }
    }

    private func actionBar(for item: MediaItem) -> some View {
        VStack {
            HStack(spacing: 2) {
                Spacer()
                button("info.circle") { showInfo.toggle() }
                if onAlbumChanged != nil, !item.isTrashed {
                    button("rectangle.stack.badge.plus") { addingToAlbum = item }
                }
                button(preparingShare ? "hourglass" : "square.and.arrow.up") {
                    guard !preparingShare else { return }
                    preparingShare = true
                    Task {
                        shareURL = await Downloader.downloadForSharing(item: item, serverURL: serverURL)
                        preparingShare = false
                    }
                }
                button(item.isFavorite ? "heart.fill" : "heart",
                       tint: item.isFavorite ? Palette.favorite : .white) {
                    onToggleFavorite(item)
                }
                if item.isTrashed {
                    if let onRestore {
                        button("arrow.uturn.backward") { onRestore(item); dismiss() }
                    }
                } else {
                    if !item.isVideo {
                        if let onEdit { button("slider.horizontal.3") { onEdit(item); dismiss() } }
                        if let onRotate { button("rotate.right") { onRotate(item) } }
                    }
                    if let onArchive {
                        button(item.archived == 1 ? "tray.and.arrow.up" : "archivebox") {
                            onArchive(item); dismiss()
                        }
                    }
                    if let onTrash { button("trash") { onTrash(item); dismiss() } }
                }
                button("xmark") { dismiss() }
            }
            .padding(.horizontal, 8)
            .padding(.top, 4)
            Spacer()
        }
    }

    private func button(_ systemName: String, tint: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(Color.black.opacity(0.35), in: Circle())
        }
        .buttonStyle(.plain)
    }

    private func infoPanel(for item: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(infoRows(for: item), id: \.0) { key, value in
                HStack(alignment: .top, spacing: 8) {
                    Text(key)
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.secondaryText)
                        .frame(minWidth: 90, alignment: .leading)
                    Text(value)
                        .font(.system(size: 13))
                        .foregroundStyle(.white)
                }
            }
        }
        .padding(16)
        .background(Palette.panel.opacity(0.87), in: RoundedRectangle(cornerRadius: 12))
    }

    private func infoRows(for item: MediaItem) -> [(String, String)] {
        var rows: [(String, String)] = [("Name", item.name)]
        rows.append(("Taken", takenAtLabel(item.takenAt)))
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

/// A photo that pinches and double-taps to zoom. Panning only takes over the
/// gesture once zoomed in, so a swipe at 1× still turns the page.
private struct ZoomableImage: View {
    let url: String

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
                    }
            )
            .simultaneousGesture(
                DragGesture()
                    .onChanged { if total > 1 { drag = $0.translation } }
                    .onEnded { _ in
                        offset.width += drag.width
                        offset.height += drag.height
                        drag = .zero
                    },
                including: total > 1 ? .all : .subviews
            )
            .onTapGesture(count: 2) {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if scale > 1 { scale = 1; offset = .zero } else { scale = 2.5 }
                }
            }
            .onChange(of: url) { _, _ in
                scale = 1; pinch = 1; offset = .zero; drag = .zero
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
