import SwiftUI

/// The month-sectioned tile grid shared by the library, albums and search.
/// Edge-to-edge, three-ish columns, pinned month headers — the shape Photos
/// uses, rather than the padded card grid the Android client draws.
struct GalleryGrid<Menu: View>: View {
    let items: [MediaItem]
    let serverURL: String
    var uploadsFeed = false
    /// Non-nil puts the grid in selection mode: tiles show a check and the
    /// tap handler toggles instead of opening the viewer.
    var selection: Set<String>?
    var onItemTap: (MediaItem) -> Void
    var onLoadMore: () -> Void = {}
    @ViewBuilder var menu: (MediaItem) -> Menu

    /// Tiles are laid out at a size we compute rather than with an adaptive
    /// GridItem: an adaptive column leaves the row height to the cell, and a
    /// 1:1 aspect ratio inside a lazy grid resolves against an unbounded
    /// height proposal, which stretched every thumbnail into a portrait.
    private static var minimumTile: CGFloat { 110 }
    private static var spacing: CGFloat { 2 }

    var body: some View {
        GeometryReader { geometry in
            let columns = max(1, Int(geometry.size.width / Self.minimumTile))
            let tile = (geometry.size.width - Self.spacing * CGFloat(columns - 1)) / CGFloat(columns)
            ScrollView {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.fixed(tile), spacing: Self.spacing), count: columns),
                    spacing: Self.spacing,
                    pinnedViews: [.sectionHeaders]
                ) {
                    ForEach(gallerySections(items, uploadsFeed: uploadsFeed)) { section in
                        Section {
                            ForEach(section.items) { item in
                                Tile(item: item,
                                     serverURL: serverURL,
                                     size: tile,
                                     selected: selection.map { $0.contains(item.id) })
                                    .onTapGesture { onItemTap(item) }
                                    .contextMenu { if selection == nil { menu(item) } }
                                    .onAppear {
                                        if item.id == items[max(0, items.count - 20)].id { onLoadMore() }
                                    }
                            }
                        } header: {
                            Text(section.label)
                                .font(.title3.weight(.semibold))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(.bar)
                        }
                    }
                }
            }
        }
    }
}

extension GalleryGrid where Menu == EmptyView {
    init(items: [MediaItem], serverURL: String, uploadsFeed: Bool = false,
         selection: Set<String>? = nil,
         onItemTap: @escaping (MediaItem) -> Void,
         onLoadMore: @escaping () -> Void = {}) {
        self.init(items: items, serverURL: serverURL, uploadsFeed: uploadsFeed,
                  selection: selection, onItemTap: onItemTap, onLoadMore: onLoadMore,
                  menu: { _ in EmptyView() })
    }
}

struct Tile: View {
    let item: MediaItem
    let serverURL: String
    /// The grid hands down an exact square. A flexible frame here lets the
    /// filled image decide the width and the row spills across its neighbours.
    let size: CGFloat
    /// nil when the grid is not in selection mode.
    var selected: Bool?

    var body: some View {
        RemoteImage(url: Urls.thumb(serverURL, item.id, version: item.editVersion))
            .frame(width: size, height: size)
            .clipped()
            .contentShape(Rectangle())
            .overlay(alignment: .bottomTrailing) {
                if item.isVideo {
                    Image(systemName: "play.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(.white)
                        .shadow(radius: 2)
                        .padding(5)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if item.isFavorite {
                    Image(systemName: "heart.fill")
                        .font(.system(size: 11))
                        .foregroundStyle(.white)
                        .shadow(radius: 2)
                        .padding(5)
                }
            }
            .overlay {
                if selected == true { Color.black.opacity(0.25) }
            }
            .overlay(alignment: .topTrailing) {
                if let selected {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, selected ? Color.accentColor : Color.black.opacity(0.25))
                        .padding(5)
                }
            }
            .background(Palette.tile)
    }
}
