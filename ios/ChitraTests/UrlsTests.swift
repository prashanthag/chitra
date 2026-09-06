import XCTest
@testable import Chitra

final class UrlsTests: XCTestCase {

    func testThumbURLCarriesTheEditVersionAndToleratesATrailingSlash() {
        // No version known: unversioned (the server gives it a short max-age).
        XCTAssertEqual("http://h:1/api/media/abc/thumb", Urls.thumb("http://h:1", "abc"))
        XCTAssertEqual("http://h:1/api/media/abc/thumb?v=0", Urls.thumb("http://h:1", "abc", version: 0))
        XCTAssertEqual("http://h:1/api/media/abc/thumb?v=3", Urls.thumb("http://h:1/", "abc", version: 3))
        XCTAssertEqual("http://h:1/api/media/abc/thumb?v=3&w=32", Urls.thumb("http://h:1", "abc", version: 3, w: 32))
        XCTAssertEqual("http://h:1/api/media/abc/thumb?w=1024", Urls.thumb("http://h:1", "abc", w: 1024))
        XCTAssertEqual("http://h:1/api/media/abc/preview?v=2", Urls.preview("http://h:1", "abc", version: 2))
    }

    func testOtherURLsKeepTheirShape() {
        XCTAssertEqual("http://h:1/api/media/abc/full?as=jpeg", Urls.full("http://h:1", "abc", asJpeg: true))
        XCTAssertEqual("http://h:1/api/media/abc/stream.mp4", Urls.stream("http://h:1/", "abc"))
        XCTAssertEqual("http://h:1/api/media/abc/play?codecs=h264,hevc", Urls.play("http://h:1", "abc"))
        XCTAssertEqual("http://h:1/s/tok", Urls.shareLink("http://h:1", "tok"))
        XCTAssertEqual("http://h:1/api/clusters/7/thumb", Urls.clusterThumb("http://h:1", 7))
    }
}
