import XCTest
@testable import Chitra

final class ContentHashTests: XCTestCase {

    func testMatchesTheServersQuickHashVectors() {
        // Same vectors as server/tests/test_api.py ContentHashTests and the
        // Android client's ContentHashTest — all three must agree byte for byte
        // or the upload pre-flight silently stops matching.
        let big = Data((0..<3_000_000).map { UInt8($0 % 251) })
        XCTAssertEqual("c0cf07b6c7a6aeb7bf336a2af9c5dc04364500e6a65b1249eb5b2e78be8ccf3e", ContentHash.of(big))
        XCTAssertEqual("a5c35e5d848a9c891a479ebaeb7083b71e8bee487416b56f2333dca466e9f7e6",
                       ContentHash.of(Data("hello chitra".utf8)))
    }

    func testTailNeverOverlapsTheHead() {
        // A file just over the head size: the tail starts at HEAD, not size - TAIL.
        let bytes = Data((0..<(ContentHash.head + 10)).map { UInt8($0 % 7) })
        let head = bytes.prefix(ContentHash.head)
        let tail = bytes.suffix(from: ContentHash.head)
        XCTAssertEqual(ContentHash.of(size: Int64(bytes.count), head: Data(head), tail: Data(tail)),
                       ContentHash.of(bytes))
    }

    func testStreamingAccumulatorMatchesTheWholeFileHash() {
        // PhotoKit hands the bytes over in chunks with no seeking, so the
        // streaming path has to land on the same digest as a random-access read.
        let bytes = Data((0..<1_000_000).map { UInt8($0 % 251) })
        var streaming = ContentHash.Streaming()
        for chunk in stride(from: 0, to: bytes.count, by: 65_536) {
            streaming.append(bytes[chunk..<min(chunk + 65_536, bytes.count)])
        }
        XCTAssertEqual(Int64(bytes.count), streaming.size)
        XCTAssertEqual(ContentHash.of(bytes), streaming.finalize())
    }

    func testStreamingHandlesAFileJustOverTheHead() {
        let bytes = Data((0..<(ContentHash.head + 10)).map { UInt8($0 % 7) })
        var streaming = ContentHash.Streaming()
        streaming.append(bytes)
        XCTAssertEqual(ContentHash.of(bytes), streaming.finalize())
    }

    func testStreamingHandlesAFileSmallerThanTheHead() {
        let bytes = Data("hello chitra".utf8)
        var streaming = ContentHash.Streaming()
        for byte in bytes { streaming.append(Data([byte])) }
        XCTAssertEqual(ContentHash.of(bytes), streaming.finalize())
    }

    func testHashesAFileOnDisk() throws {
        let bytes = Data((0..<900_000).map { UInt8($0 % 251) })
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("hash-\(UUID().uuidString).bin")
        try bytes.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(ContentHash.of(bytes), ContentHash.of(fileAt: url))
    }
}
