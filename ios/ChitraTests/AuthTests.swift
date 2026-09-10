import XCTest
@testable import Chitra

final class AuthTests: XCTestCase {

    func testAuthStateDecodesServerFieldsAndDefaults() throws {
        let full = """
        {"auth_required": true, "user": {"id": 1, "name": "prash", "role": "admin"},
         "unlocked": false, "unlock_idle_seconds": 60, "can_delete": true}
        """
        let state = try JSONDecoder().decode(AuthState.self, from: Data(full.utf8))
        XCTAssertTrue(state.authRequired)
        XCTAssertEqual("prash", state.user?.name)
        XCTAssertTrue(state.user?.isAdmin ?? false)
        XCTAssertTrue(state.canDelete)
        XCTAssertEqual(60, state.unlockIdleSeconds)

        // A member on an older server: no can_delete field means "may delete"
        // (the server enforces either way); no user means signed out.
        let sparse = try JSONDecoder().decode(AuthState.self, from: Data(#"{"auth_required": false}"#.utf8))
        XCTAssertFalse(sparse.authRequired)
        XCTAssertNil(sparse.user)
        XCTAssertTrue(sparse.canDelete)

        let member = try JSONDecoder().decode(
            AuthState.self,
            from: Data(#"{"auth_required": true, "user": {"id": 5, "name": "ina", "role": "member"}, "can_delete": false}"#.utf8))
        XCTAssertFalse(member.canDelete)
        XCTAssertFalse(member.user?.isAdmin ?? true)
    }

    func testUserAlbumDecodesLockedAndSealedAsBoolOrInt() throws {
        let sealed = try JSONDecoder().decode(
            UserAlbum.self, from: Data(#"{"id": 2, "name": "Mun", "count": 3, "locked": true, "sealed": true, "cover": null}"#.utf8))
        XCTAssertTrue(sealed.locked)
        XCTAssertTrue(sealed.sealed)
        XCTAssertNil(sealed.cover)

        let numeric = try JSONDecoder().decode(
            UserAlbum.self, from: Data(#"{"id": 3, "name": "hm", "locked": 1, "sealed": 0}"#.utf8))
        XCTAssertTrue(numeric.locked)
        XCTAssertFalse(numeric.sealed)

        let plain = try JSONDecoder().decode(UserAlbum.self, from: Data(#"{"id": 4, "name": "Trip"}"#.utf8))
        XCTAssertFalse(plain.locked)
        XCTAssertFalse(plain.sealed)
    }

    func testBearerHeaderFollowsTheStoredToken() {
        let before = Auth.token
        defer { Auth.token = before }

        Auth.token = nil
        var anonymous = URLRequest(url: URL(string: "http://h:1/api/media")!)
        Auth.apply(to: &anonymous)
        XCTAssertNil(anonymous.value(forHTTPHeaderField: "Authorization"))
        XCTAssertTrue(Auth.headers().isEmpty)

        Auth.token = "abc123"
        var signedIn = URLRequest(url: URL(string: "http://h:1/api/media")!)
        Auth.apply(to: &signedIn)
        XCTAssertEqual("Bearer abc123", signedIn.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(["Authorization": "Bearer abc123"], Auth.headers())
    }
}
