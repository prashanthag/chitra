import Foundation

enum Filter: String, CaseIterable, Identifiable {
    case all, photos, videos, favorites, uploads, archived, unknown, trash

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: return "All"
        case .photos: return "Photos"
        case .videos: return "Videos"
        case .favorites: return "Favorites"
        case .uploads: return "Uploads"
        case .archived: return "Archived"
        case .unknown: return "Unknown"
        case .trash: return "Trash"
        }
    }
}

/// Progress of a manual pick-and-upload; shown under the filter row.
struct UploadProgress: Equatable {
    var done = 0
    var total = 0
    var sent = 0
    var duplicates = 0
    var failed = 0
    var running = true

    var summary: String {
        var s = "Uploaded \(sent) of \(total)"
        if duplicates > 0 { s += ", \(duplicates) already in library" }
        if failed > 0 { s += ", \(failed) failed" }
        return s
    }

    var fraction: Double { total == 0 ? 0 : Double(done) / Double(total) }
}
