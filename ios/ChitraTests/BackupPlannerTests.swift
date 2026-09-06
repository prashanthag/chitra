import XCTest
@testable import Chitra

final class BackupPlannerTests: XCTestCase {
    private let camera = "camera"
    private let shots = "screenshots"
    private let whatsapp = "whatsapp"

    private func item(_ id: Int, _ album: String, video: Bool = false,
                      size: Int64 = 2_000_000, added: TimeInterval? = nil) -> DeviceItem {
        DeviceItem(
            id: "asset-\(id)",
            name: video ? "VID_\(id).mp4" : "IMG_\(id).jpg",
            size: size,
            albumId: album,
            albumName: album == camera ? "Recents" : (album == shots ? "Screenshots" : "WhatsApp"),
            isVideo: video,
            dateAdded: added ?? TimeInterval(id))
    }

    private lazy var roll: [DeviceItem] = [
        item(1, camera, added: 10),
        item(2, camera, added: 50),
        item(3, camera, video: true, added: 30),
        item(4, shots, added: 40),
        item(5, whatsapp, added: 60),
        item(6, camera, size: 4_000, added: 70),   // icon-sized stray
    ]

    private func ids(_ plan: [DeviceItem]) -> [String] { plan.map(\.id) }

    func testOnlySelectedAlbumsAreUploaded() {
        let plan = BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera]))
        XCTAssertEqual(["asset-2", "asset-3", "asset-1"], ids(plan))
    }

    func testAddingAnAlbumLaterBackfillsIt() {
        let plan = BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera, shots]))
        XCTAssertTrue(plan.contains { $0.id == "asset-4" })
        XCTAssertFalse(plan.contains { $0.albumId == whatsapp })
    }

    func testAlreadyUploadedItemsAreSkipped() {
        let plan = BackupPlanner.plan(items: roll, uploadedIds: ["asset-2", "asset-3"],
                                      options: BackupOptions(albumIds: [camera]))
        XCTAssertEqual(["asset-1"], ids(plan))
    }

    func testVideosCanBeExcluded() {
        let plan = BackupPlanner.plan(items: roll, uploadedIds: [],
                                      options: BackupOptions(albumIds: [camera], includeVideos: false))
        XCTAssertEqual(["asset-2", "asset-1"], ids(plan))
    }

    func testTinyFilesAreIgnored() {
        let plan = BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [camera]))
        XCTAssertFalse(plan.contains { $0.id == "asset-6" })
        let lenient = BackupPlanner.plan(items: roll, uploadedIds: [],
                                         options: BackupOptions(albumIds: [camera], minBytes: 0))
        XCTAssertTrue(lenient.contains { $0.id == "asset-6" })
    }

    func testAnUnknownSizeIsNeverFilteredOut() {
        // PhotoKit won't always say how big an asset is; a -1 must not be read
        // as "smaller than 10 KB" and silently drop the file from every run.
        let unknown = item(7, camera, size: -1, added: 80)
        let plan = BackupPlanner.plan(items: [unknown], uploadedIds: [], options: BackupOptions(albumIds: [camera]))
        XCTAssertEqual(["asset-7"], ids(plan))
    }

    func testNewestFirstSoFreshPhotosLandBeforeTheBacklog() {
        let plan = BackupPlanner.plan(items: roll, uploadedIds: [],
                                      options: BackupOptions(albumIds: [camera, shots, whatsapp]))
        XCTAssertEqual(["asset-5", "asset-2", "asset-4", "asset-3", "asset-1"], ids(plan))
    }

    func testNoAlbumsSelectedMeansNothingIsUploaded() {
        XCTAssertTrue(BackupPlanner.plan(items: roll, uploadedIds: [], options: BackupOptions(albumIds: [])).isEmpty)
    }

    func testDefaultSelectionIsTheFirstAlbumWhichIsTheCameraRoll() {
        let albums = [
            DeviceAlbum(id: camera, name: "Recents", count: 300, coverAssetId: nil),
            DeviceAlbum(id: shots, name: "Screenshots", count: 9, coverAssetId: nil),
        ]
        XCTAssertEqual([camera], DeviceMedia.defaultAlbumIds(albums))
        XCTAssertEqual(Set<String>(), DeviceMedia.defaultAlbumIds([]))
    }
}
