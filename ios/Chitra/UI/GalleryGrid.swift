import SwiftUI

/// The month-sectioned tile grid shared by the main feed, folder albums and
/// manual albums.
struct GalleryGrid: View {
    let items: [MediaItem]
    let serverURL: String
    var uploadsFeed = false
    var onItemTap: (MediaItem) -> Void
    var onLoadMore: () -> Void = {}

    /// Tiles are laid out at a size we compute rather than with an adaptive
    /// GridItem: an adaptive column leaves the row height to the cell, and a
    /// 1:1 aspect ratio inside a lazy grid resolves against an unbounded
    /// height proposal, which stretched every thumbnail into a portrait.
    private static let minimumTile: CGFloat = 110
    private static let spacing: CGFloat = 2

    var body: some View {
        GeometryReader { geometry in
            let available = geometry.size.width - Self.spacing * 2
            let columns = max(1, Int(available / Self.minimumTile))
            let tile = (available - Self.spacing * CGFloat(columns - 1)) / CGFloat(columns)
            ScrollView {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.fixed(tile), spacing: Self.spacing), count: columns),
                    spacing: Self.spacing,
                    pinnedViews: [.sectionHeaders]
                ) {
                    ForEach(gallerySections(items, uploadsFeed: uploadsFeed)) { section in
                        Section {
                            ForEach(section.items) { item in
                                Tile(item: item, serverURL: serverURL, size: tile)
                                    .onTapGesture { onItemTap(item) }
                                    .onAppear {
                                        if item.id == items[max(0, items.count - 20)].id { onLoadMore() }
                                    }
                            }
                        } header: {
                            Text(section.label)
                                .font(.subheadline)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 8)
                                .background(.ultraThinMaterial)
                        }
                    }
                }
                .padding(Self.spacing)
            }
        }
    }
}

struct Tile: View {
    let item: MediaItem
    let serverURL: String
    /// The grid hands down an exact square. A flexible frame here lets the
    /// filled image decide the width and the row spills across its neighbours.
    let size: CGFloat

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
                        .padding(4)
                        .background(Color.black.opacity(0.65), in: Circle())
                        .padding(4)
                }
            }
            .overlay(alignment: .topLeading) {
                if item.isFavorite {
                    Image(systemName: "heart.fill")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.favorite)
                        .padding(4)
                }
            }
            .background(Palette.tile)
    }
}

/// "On this day" strip above the feed, one card per year that has photos.
struct MemoriesRow: View {
    let memories: Memories
    let serverURL: String
    var onTap: (MediaItem) -> Void

    private var currentYear: Int { Calendar.current.component(.year, from: Date()) }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Memories").font(.subheadline)
                    Text(memories.monthDay)
                        .font(.system(size: 10))
                        .foregroundStyle(Palette.secondaryText)
                }
                .padding(.trailing, 4)

                ForEach(memories.groups) { group in
                    if let first = group.items.first {
                        let years = currentYear - (Int(group.year) ?? currentYear)
                        Button {
                            onTap(first)
                        } label: {
                            RemoteImage(url: Urls.thumb(serverURL, first.id, version: first.editVersion))
                                .frame(width: 84, height: 110)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .overlay(alignment: .bottomLeading) {
                                    Text(years > 0 ? "\(years) yr ago" : group.year)
                                        .font(.system(size: 10))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 3)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .background(Color.black.opacity(0.67))
                                }
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }
}
