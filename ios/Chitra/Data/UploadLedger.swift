import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/**
 Which library assets have been sent to which server, plus a cache of the
 content hashes computed for them. Keyed by PHAsset localIdentifier + server,
 so switching servers or adding an album later backfills correctly.

 The hash cache matters more on iOS than it did on Android: PhotoKit hands out
 asset bytes only as a front-to-back stream, so a hash costs a full read of the
 file. Caching it per (asset, modification date) means a re-run pre-flights the
 roll from SQLite instead of re-reading every video.
 */
final class UploadLedger {
    static let shared = UploadLedger()

    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "com.buildapp.photos.ledger")

    init(path: String? = nil) {
        let file = path ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("backup_ledger.db").path
        try? FileManager.default.createDirectory(
            at: URL(fileURLWithPath: file).deletingLastPathComponent(),
            withIntermediateDirectories: true)
        if sqlite3_open(file, &db) != SQLITE_OK { db = nil }
        exec("""
            CREATE TABLE IF NOT EXISTS uploaded (
              media_id TEXT NOT NULL,
              server TEXT NOT NULL,
              name TEXT,
              size INTEGER,
              server_id TEXT,
              duplicate INTEGER NOT NULL DEFAULT 0,
              uploaded_at INTEGER NOT NULL,
              PRIMARY KEY (media_id, server))
            """)
        exec("""
            CREATE TABLE IF NOT EXISTS hashes (
              media_id TEXT NOT NULL PRIMARY KEY,
              modified REAL NOT NULL,
              size INTEGER NOT NULL,
              hash TEXT NOT NULL)
            """)
    }

    deinit { if let db { sqlite3_close(db) } }

    private func exec(_ sql: String) {
        queue.sync { _ = sqlite3_exec(db, sql, nil, nil, nil) }
    }

    // MARK: - Uploaded rows

    func uploadedIds(server: String) -> Set<String> {
        queue.sync {
            var out = Set<String>()
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT media_id FROM uploaded WHERE server = ?", -1, &stmt, nil) == SQLITE_OK else { return out }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, server, -1, SQLITE_TRANSIENT)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let c = sqlite3_column_text(stmt, 0) { out.insert(String(cString: c)) }
            }
            return out
        }
    }

    func markUploaded(server: String, item: DeviceItem, serverId: String?, duplicate: Bool) {
        queue.sync {
            var stmt: OpaquePointer?
            let sql = """
                INSERT OR REPLACE INTO uploaded
                (media_id, server, name, size, server_id, duplicate, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, item.id, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, server, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 3, item.name, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 4, item.size)
            if let serverId { sqlite3_bind_text(stmt, 5, serverId, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 5) }
            sqlite3_bind_int(stmt, 6, duplicate ? 1 : 0)
            sqlite3_bind_int64(stmt, 7, Int64(Date().timeIntervalSince1970))
            sqlite3_step(stmt)
        }
    }

    func count(server: String) -> Int {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM uploaded WHERE server = ?", -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, server, -1, SQLITE_TRANSIENT)
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
        }
    }

    func clear(server: String) {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "DELETE FROM uploaded WHERE server = ?", -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, server, -1, SQLITE_TRANSIENT)
            sqlite3_step(stmt)
        }
    }

    // MARK: - Hash cache

    /// The cached hash for an asset, or nil when it was never hashed or the
    /// asset has been edited since (a new modification date re-hashes).
    func cachedHash(mediaId: String, modified: TimeInterval) -> (hash: String, size: Int64)? {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT hash, size, modified FROM hashes WHERE media_id = ?", -1, &stmt, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, mediaId, -1, SQLITE_TRANSIENT)
            guard sqlite3_step(stmt) == SQLITE_ROW, let c = sqlite3_column_text(stmt, 0) else { return nil }
            let storedModified = sqlite3_column_double(stmt, 2)
            guard abs(storedModified - modified) < 1 else { return nil }
            return (String(cString: c), sqlite3_column_int64(stmt, 1))
        }
    }

    func cacheHash(mediaId: String, modified: TimeInterval, size: Int64, hash: String) {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO hashes (media_id, modified, size, hash) VALUES (?, ?, ?, ?)", -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, mediaId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_double(stmt, 2, modified)
            sqlite3_bind_int64(stmt, 3, size)
            sqlite3_bind_text(stmt, 4, hash, -1, SQLITE_TRANSIENT)
            sqlite3_step(stmt)
        }
    }
}
