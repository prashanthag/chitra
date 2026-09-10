import Foundation

/// One month (or upload day) of tiles under a sticky header.
struct GallerySection: Identifiable {
    let id: String
    let label: String
    var items: [MediaItem]
}

/// The Uploads feed is ordered by upload time, so it sections by upload day;
/// sectioning it by capture month would fragment into one header per run of
/// items.
func gallerySections(_ items: [MediaItem], uploadsFeed: Bool) -> [GallerySection] {
    var sections: [GallerySection] = []
    for item in items {
        let label = uploadsFeed ? uploadDayLabel(item.addedAt) : monthLabel(item.takenAt)
        if sections.last?.label == label {
            sections[sections.count - 1].items.append(item)
        } else {
            sections.append(GallerySection(id: "\(label)-\(item.id)", label: label, items: [item]))
        }
    }
    return sections
}
