import Foundation

var failures = 0
func check(_ label: String, _ actual: String, _ expected: String) {
    if actual == expected { print("  ok  \(label)") }
    else { failures += 1; print("FAIL  \(label)\n        got: \(actual)\n   expected: \(expected)") }
}
func check(_ label: String, _ actual: [String], _ expected: [String]) {
    check(label, actual.joined(separator: ","), expected.joined(separator: ","))
}
func check(_ label: String, _ condition: Bool) {
    if condition { print("  ok  \(label)") } else { failures += 1; print("FAIL  \(label)") }
}

print("— Urls —")
check("thumb, no version", Urls.thumb("http://h:1", "abc"), "http://h:1/api/media/abc/thumb")
check("thumb v=0", Urls.thumb("http://h:1", "abc", version: 0), "http://h:1/api/media/abc/thumb?v=0")
check("thumb trailing slash", Urls.thumb("http://h:1/", "abc", version: 3), "http://h:1/api/media/abc/thumb?v=3")
check("thumb v+w", Urls.thumb("http://h:1", "abc", version: 3, w: 32), "http://h:1/api/media/abc/thumb?v=3&w=32")
check("thumb w only", Urls.thumb("http://h:1", "abc", w: 1024), "http://h:1/api/media/abc/thumb?w=1024")
check("preview", Urls.preview("http://h:1", "abc", version: 2), "http://h:1/api/media/abc/preview?v=2")
check("full as jpeg", Urls.full("http://h:1", "abc", asJpeg: true), "http://h:1/api/media/abc/full?as=jpeg")
check("stream", Urls.stream("http://h:1/", "abc"), "http://h:1/api/media/abc/stream.mp4")
check("play", Urls.play("http://h:1", "abc"), "http://h:1/api/media/abc/play?codecs=h264,hevc")
check("share link", Urls.shareLink("http://h:1", "tok"), "http://h:1/s/tok")
check("cluster thumb", Urls.clusterThumb("http://h:1", 7), "http://h:1/api/clusters/7/thumb")

print("— ContentHash (server quick_hash vectors) —")
let big = Data((0..<3_000_000).map { UInt8($0 % 251) })
check("3 MB vector", ContentHash.of(big), "c0cf07b6c7a6aeb7bf336a2af9c5dc04364500e6a65b1249eb5b2e78be8ccf3e")
check("short vector", ContentHash.of(Data("hello chitra".utf8)), "a5c35e5d848a9c891a479ebaeb7083b71e8bee487416b56f2333dca466e9f7e6")
let justOver = Data((0..<(ContentHash.head + 10)).map { UInt8($0 % 7) })
check("tail never overlaps head",
      ContentHash.of(size: Int64(justOver.count), head: Data(justOver.prefix(ContentHash.head)),
                     tail: Data(justOver.suffix(from: ContentHash.head))),
      ContentHash.of(justOver))
var streaming = ContentHash.Streaming()
let streamed = Data((0..<1_000_000).map { UInt8($0 % 251) })
for offset in stride(from: 0, to: streamed.count, by: 65_536) {
    streaming.append(streamed[offset..<min(offset + 65_536, streamed.count)])
}
check("streaming == random access", streaming.finalize(), ContentHash.of(streamed))
check("streaming size", "\(streaming.size)", "1000000")
var overHead = ContentHash.Streaming(); overHead.append(justOver)
check("streaming just over head", overHead.finalize(), ContentHash.of(justOver))
var tiny = ContentHash.Streaming()
for byte in Data("hello chitra".utf8) { tiny.append(Data([byte])) }
check("streaming byte at a time", tiny.finalize(), ContentHash.of(Data("hello chitra".utf8)))
let onDisk = FileManager.default.temporaryDirectory.appendingPathComponent("chitra-hash-test.bin")
try! streamed.write(to: onDisk)
check("file on disk", ContentHash.of(fileAt: onDisk) ?? "nil", ContentHash.of(streamed))
try? FileManager.default.removeItem(at: onDisk)

print("— BackupPlanner —")
func item(_ id: Int, _ album: String, video: Bool = false, size: Int64 = 2_000_000, added: TimeInterval) -> DeviceItem {
    DeviceItem(id: "asset-\(id)", name: video ? "VID_\(id).mp4" : "IMG_\(id).jpg", size: size,
               albumId: album, albumName: album, isVideo: video, dateAdded: added)
}
let camera = "camera", shots = "screenshots", whatsapp = "whatsapp"
let roll = [
    item(1, camera, added: 10), item(2, camera, added: 50), item(3, camera, video: true, added: 30),
    item(4, shots, added: 40), item(5, whatsapp, added: 60), item(6, camera, size: 4_000, added: 70),
]
func ids(_ plan: [DeviceItem]) -> [String] { plan.map(\.id) }
check("only selected albums", ids(BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera]))),
      ["asset-2", "asset-3", "asset-1"])
check("adding an album backfills", BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera, shots])).contains { $0.id == "asset-4" })
check("other albums stay out", !BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera, shots])).contains { $0.albumId == whatsapp })
check("already uploaded skipped", ids(BackupPlanner.plan(items: roll, uploadedIds: ["asset-2", "asset-3"], options: BackupOptions(albumIds: [camera]))), ["asset-1"])
check("videos excluded", ids(BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera], includeVideos: false))), ["asset-2", "asset-1"])
check("tiny files ignored", !BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera])).contains { $0.id == "asset-6" })
check("lenient keeps tiny", BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera], minBytes: 0)).contains { $0.id == "asset-6" })
check("unknown size is kept", ids(BackupPlanner.plan(items: [item(7, camera, size: -1, added: 80)], uploadedIds: [], options: BackupOptions(albumIds: [camera]))), ["asset-7"])
check("newest first", ids(BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera, shots, whatsapp]))),
      ["asset-5", "asset-2", "asset-4", "asset-3", "asset-1"])
check("no albums, nothing planned", BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [])).isEmpty)

print("— Gallery sections —")
func epoch(_ y: Int, _ m: Int, _ d: Int) -> Double {
    var c = DateComponents(); c.year = y; c.month = m; c.day = d; c.hour = 12
    return Calendar.current.date(from: c)!.timeIntervalSince1970
}
func media(_ id: String, taken: Double?, added: Double? = nil) -> MediaItem {
    MediaItem(id: id, name: "\(id).jpg", takenAt: taken, addedAt: added)
}
let monthly = gallerySections([media("a", taken: epoch(2026, 9, 2)), media("b", taken: epoch(2026, 9, 1)), media("c", taken: epoch(2026, 8, 30))], uploadsFeed: false)
check("two month sections", "\(monthly.count)", "2")
check("first section groups the month", monthly[0].items.map(\.id), ["a", "b"])
check("undated grouped", gallerySections([media("a", taken: nil), media("b", taken: nil)], uploadsFeed: false)[0].label, "Undated")
let day = epoch(2026, 9, 5)
let uploads = gallerySections([media("a", taken: epoch(2019, 3, 1), added: day), media("b", taken: epoch(2024, 7, 4), added: day)], uploadsFeed: true)
check("uploads section by upload day", "\(uploads.count)", "1")
check("upload header wording", uploads[0].label.hasPrefix("Uploaded "))
let repeated = gallerySections([media("a", taken: epoch(2026, 9, 2)), media("b", taken: epoch(2026, 8, 2)), media("c", taken: epoch(2026, 9, 1))], uploadsFeed: false)
check("section ids unique", "\(Set(repeated.map(\.id)).count)", "3")

print("— Upload progress / filters —")
check("plain summary", UploadProgress(done: 3, total: 3, sent: 3, duplicates: 0, failed: 0, running: false).summary, "Uploaded 3 of 3")
check("full summary", UploadProgress(done: 4, total: 4, sent: 2, duplicates: 1, failed: 1, running: false).summary,
      "Uploaded 2 of 4, 1 already in library, 1 failed")
check("filter label", Filter.uploads.label, "Uploads")
check("filter by name", Filter(rawValue: "uploads") == .uploads)
check("unknown filter name", Filter(rawValue: "nope") == nil)

print("— Model decoding —")
let listRow = try! JSONDecoder().decode(MediaItem.self, from: Data(#"{"id":"abc","name":"IMG_1.jpg","kind":"photo","ext":".jpg","favorite":1,"edit_version":2}"#.utf8))
check("list row favorite", listRow.isFavorite)
check("list row edit version", "\(listRow.editVersion)", "2")
check("list row has no gps", listRow.lat == nil)
let sparse = try! JSONDecoder().decode(MediaItem.self, from: Data(#"{"id":"abc"}"#.utf8))
check("missing columns fall back", sparse.kind, "photo")
let page = try! JSONDecoder().decode(MediaPage.self, from: Data(#"{"page":1,"per_page":80,"total":2,"items":[{"id":"a"},{"id":"b"}]}"#.utf8))
check("page per_page", "\(page.perPage)", "80")
check("page items", page.items.map(\.id), ["a", "b"])
let plain = try! JSONDecoder().decode(Album.self, from: Data(#"{"album":"2024","count":3}"#.utf8))
let phone = try! JSONDecoder().decode(Album.self, from: Data(#"{"album":"uploads","folder":"Camera","count":9}"#.utf8))
check("folder album key", plain.key, "2024")
check("phone folder key", phone.key, "uploads/Camera")
check("phone folder label", phone.label, "Camera")

print(failures == 0 ? "\nALL PASS" : "\n\(failures) FAILURES")
exit(failures == 0 ? 0 : 1)
