import XCTest
@testable import Chitra

final class UploadProgressTests: XCTestCase {

    func testSummaryMentionsDuplicatesAndFailuresOnlyWhenPresent() {
        XCTAssertEqual("Uploaded 3 of 3",
                       UploadProgress(done: 3, total: 3, sent: 3, duplicates: 0, failed: 0, running: false).summary)
        XCTAssertEqual("Uploaded 2 of 4, 1 already in library, 1 failed",
                       UploadProgress(done: 4, total: 4, sent: 2, duplicates: 1, failed: 1, running: false).summary)
    }

    func testFilterChipsCarryHumanLabelsAndParseByName() {
        XCTAssertEqual("Uploads", Filter.uploads.label)
        XCTAssertEqual(Filter.uploads, Filter(rawValue: "uploads"))
        XCTAssertNil(Filter(rawValue: "nope"))
    }
}

final class GallerySectionTests: XCTestCase {

    private func item(_ id: String, taken: Double?, added: Double? = nil) -> MediaItem {
        MediaItem(id: id, name: "\(id).jpg", takenAt: taken, addedAt: added)
    }

    private func epoch(_ year: Int, _ month: Int, _ day: Int) -> Double {
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day; components.hour = 12
        return Calendar.current.date(from: components)!.timeIntervalSince1970
    }

    func testConsecutiveItemsFromOneMonthShareAHeader() {
        let items = [
            item("a", taken: epoch(2026, 9, 2)),
            item("b", taken: epoch(2026, 9, 1)),
            item("c", taken: epoch(2026, 8, 30)),
        ]
        let sections = gallerySections(items, uploadsFeed: false)
        XCTAssertEqual(2, sections.count)
        XCTAssertEqual(["a", "b"], sections[0].items.map(\.id))
        XCTAssertEqual(["c"], sections[1].items.map(\.id))
    }

    func testUndatedItemsGroupUnderOneHeader() {
        let sections = gallerySections([item("a", taken: nil), item("b", taken: nil)], uploadsFeed: false)
        XCTAssertEqual(1, sections.count)
        XCTAssertEqual("Undated", sections[0].label)
    }

    func testTheUploadsFeedSectionsByUploadDayNotCaptureMonth() {
        // Two photos taken years apart but uploaded the same day belong to one
        // section; sectioning by capture month would split every backfill run.
        let day = epoch(2026, 9, 5)
        let items = [
            item("a", taken: epoch(2019, 3, 1), added: day),
            item("b", taken: epoch(2024, 7, 4), added: day),
        ]
        let sections = gallerySections(items, uploadsFeed: true)
        XCTAssertEqual(1, sections.count)
        XCTAssertTrue(sections[0].label.hasPrefix("Uploaded "))
    }

    func testSectionIdsAreUniqueAcrossRepeatedLabels() {
        // Two runs of the same month can't collide into one SwiftUI identity.
        let sections = gallerySections([
            item("a", taken: epoch(2026, 9, 2)),
            item("b", taken: epoch(2026, 8, 2)),
            item("c", taken: epoch(2026, 9, 1)),
        ], uploadsFeed: false)
        XCTAssertEqual(3, Set(sections.map(\.id)).count)
    }
}

final class ModelDecodingTests: XCTestCase {

    func testAListRowDecodesWithoutTheDetailOnlyColumns() throws {
        let json = Data("""
        {"id":"abc","name":"IMG_1.jpg","kind":"photo","ext":".jpg","favorite":1,"edit_version":2}
        """.utf8)
        let item = try JSONDecoder().decode(MediaItem.self, from: json)
        XCTAssertEqual("abc", item.id)
        XCTAssertTrue(item.isFavorite)
        XCTAssertEqual(2, item.editVersion)
        XCTAssertNil(item.lat)
        XCTAssertFalse(item.isTrashed)
    }

    func testAnOlderServerMissingColumnsFallsBackInsteadOfFailing() throws {
        let json = Data(#"{"id":"abc"}"#.utf8)
        let item = try JSONDecoder().decode(MediaItem.self, from: json)
        XCTAssertEqual("photo", item.kind)
        XCTAssertEqual(0, item.favorite)
        XCTAssertEqual(0, item.editVersion)
    }

    func testMediaPageDecodesTheServerShape() throws {
        let json = Data("""
        {"page":1,"per_page":80,"total":2,"items":[{"id":"a"},{"id":"b"}]}
        """.utf8)
        let page = try JSONDecoder().decode(MediaPage.self, from: json)
        XCTAssertEqual(80, page.perPage)
        XCTAssertEqual(["a", "b"], page.items.map(\.id))
    }

    func testAlbumKeyDistinguishesAPhoneFolderFromAFolderAlbum() throws {
        let plain = try JSONDecoder().decode(Album.self, from: Data(#"{"album":"2024","count":3}"#.utf8))
        let phone = try JSONDecoder().decode(Album.self, from: Data(#"{"album":"uploads","folder":"Camera","count":9}"#.utf8))
        XCTAssertEqual("2024", plain.key)
        XCTAssertEqual("2024", plain.label)
        XCTAssertEqual("uploads/Camera", phone.key)
        XCTAssertEqual("Camera", phone.label)
    }
}
