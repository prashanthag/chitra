import Foundation

struct BackupOptions {
    /// PhotoKit album identifiers the user chose to back up. Empty = nothing.
    var albumIds: Set<String>
    var includeVideos: Bool = true
    /// Thumbnails and other stray small images; skip anything tiny. A size of
    /// -1 means PhotoKit wouldn't say, and the item is kept.
    var minBytes: Int64 = 10_000
}

/**
 Decides what to send, given what the device has and what the ledger says was
 already sent. Pure Swift so it is unit-tested without a device.
 */
enum BackupPlanner {
    static func plan(items: [DeviceItem], uploadedIds: Set<String>, options: BackupOptions) -> [DeviceItem] {
        guard !options.albumIds.isEmpty else { return [] }
        return items
            .filter { options.albumIds.contains($0.albumId) }
            .filter { options.includeVideos || !$0.isVideo }
            .filter { $0.size < 0 || $0.size >= options.minBytes }
            .filter { !uploadedIds.contains($0.id) }
            // Newest first: the photo you just took shows up on the server
            // within the first batch, the 2019 backlog trickles in after.
            .sorted { a, b in
                a.dateAdded == b.dateAdded ? a.id > b.id : a.dateAdded > b.dateAdded
            }
    }
}
