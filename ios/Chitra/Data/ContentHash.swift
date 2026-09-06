import Foundation
import CryptoKit

/**
 The server's quick content hash (app.py quick_hash), bit for bit:
 SHA-256 of the byte size (8 bytes, big-endian), the first 256 KiB and
 the last 64 KiB (tail starts at max(256 KiB, size - 64 KiB), so it never
 overlaps the head). Exact copies match regardless of file name, which is
 what lets a reinstalled app skip a whole camera roll the library already has.
 */
enum ContentHash {
    static let head = 256 << 10
    static let tail = 64 << 10

    static func of(size: Int64, head headBytes: Data, tail tailBytes: Data) -> String {
        var sha = SHA256()
        var be = size.bigEndian
        sha.update(data: Data(bytes: &be, count: 8))
        sha.update(data: headBytes)
        if size > Int64(head) { sha.update(data: tailBytes) }
        return sha.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Hash of a whole in-memory file (tests, small files).
    static func of(_ bytes: Data) -> String {
        let size = Int64(bytes.count)
        let headSlice = bytes.prefix(head)
        let tailStart = Int(max(Int64(head), size - Int64(tail)))
        let tailSlice = size > Int64(head)
            ? bytes[tailStart..<min(bytes.count, tailStart + tail)]
            : Data()
        return of(size: size, head: Data(headSlice), tail: Data(tailSlice))
    }

    /// Hash of a file on disk; nil when it cannot be read. Reads only the two
    /// windows the hash covers, never the middle of a 4 GB video.
    static func of(fileAt url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) else { return nil }
        do {
            let headBytes = try handle.read(upToCount: Int(min(Int64(head), size))) ?? Data()
            var tailBytes = Data()
            if size > Int64(head) {
                let start = max(Int64(head), size - Int64(tail))
                try handle.seek(toOffset: UInt64(start))
                tailBytes = try handle.read(upToCount: Int(min(Int64(tail), size - start))) ?? Data()
            }
            return of(size: size, head: headBytes, tail: tailBytes)
        } catch {
            return nil
        }
    }

    /// Streaming accumulator for sources that can only be read front to back.
    /// PhotoKit hands out asset bytes as a chunk stream with no seeking, so
    /// the head is kept as it arrives and the last 64 KiB are carried in a
    /// rolling buffer; nothing else is retained.
    struct Streaming {
        private var headBuffer = Data()
        private var tailBuffer = Data()
        private(set) var size: Int64 = 0

        init() {
            headBuffer.reserveCapacity(ContentHash.head)
            tailBuffer.reserveCapacity(ContentHash.tail * 2)
        }

        mutating func append(_ chunk: Data) {
            size += Int64(chunk.count)
            if headBuffer.count < ContentHash.head {
                headBuffer.append(chunk.prefix(ContentHash.head - headBuffer.count))
            }
            // Everything past the head is a tail candidate; keep the last 64 KiB.
            tailBuffer.append(chunk)
            if tailBuffer.count > ContentHash.tail {
                tailBuffer = Data(tailBuffer.suffix(ContentHash.tail))
            }
        }

        func finalize() -> String {
            // The tail window starts at max(head, size - tail): for a file only
            // a little larger than the head the two would otherwise overlap,
            // and the server's tail is the non-overlapping remainder.
            var tailBytes = tailBuffer
            if size > Int64(ContentHash.head) {
                let start = max(Int64(ContentHash.head), size - Int64(ContentHash.tail))
                let wanted = Int(size - start)
                if tailBytes.count > wanted { tailBytes = Data(tailBytes.suffix(wanted)) }
            }
            return ContentHash.of(size: size, head: headBuffer, tail: tailBytes)
        }
    }
}
