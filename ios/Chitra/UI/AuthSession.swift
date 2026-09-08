import SwiftUI
import Combine

/// Who is signed in and what they may do, mirroring the Android client's
/// `needLogin` / `needUnlock` / `user` / `canDelete` state:
///
/// - the server is open until the first account exists, then every call
///   needs a session (`needsLogin` shows the sign-in sheet on any 401);
/// - only admins have a Locked folder; it asks for the password on every
///   open (`needsUnlock`) and relocks after a minute idle;
/// - members add and organise but never delete or rewrite files
///   (`canDelete`), and never see the Trash.
@MainActor
final class AuthSession: ObservableObject {
    static let shared = AuthSession()

    @Published private(set) var user: User?
    @Published private(set) var authRequired = false
    @Published private(set) var unlocked = false
    @Published private(set) var canDelete = true
    @Published private(set) var idleSeconds = 60
    /// The server wants a login: RootView presents the sign-in sheet.
    @Published var needsLogin = false
    /// Locked content wants the password (again): RootView presents the unlock sheet.
    @Published var needsUnlock = false
    /// What to do once the Locked folder is open — the Android `unlockThen`.
    var afterUnlock: (() -> Void)?

    var isSignedIn: Bool { user != nil }
    var isAdmin: Bool { user?.role == "admin" }
    /// Lock actions and the Locked folder exist for admins only.
    var canLock: Bool { isAdmin }

    private let defaults = UserDefaults.standard

    private init() {
        if let name = defaults.string(forKey: "user_name") {
            user = User(id: defaults.integer(forKey: "user_id"), name: name,
                        role: defaults.string(forKey: "user_role") ?? "member")
        }
    }

    // MARK: - Server state

    func refresh(serverURL: String) async {
        guard let state = try? await PhotoAPI(baseUrl: serverURL).authState() else { return }
        authRequired = state.authRequired
        unlocked = state.unlocked
        canDelete = state.canDelete
        idleSeconds = max(10, state.unlockIdleSeconds)
        if let current = state.user {
            remember(current)
        } else {
            // A token the server no longer knows (or an open server): drop it.
            forget()
            if state.authRequired { needsLogin = true }
        }
    }

    func login(serverURL: String, name: String, password: String) async throws {
        let response = try await PhotoAPI(baseUrl: serverURL).login(name: name, password: password)
        Auth.token = response.token
        remember(response.user)
        needsLogin = false
        await refresh(serverURL: serverURL)
    }

    func logout(serverURL: String) async {
        try? await PhotoAPI(baseUrl: serverURL).logout()
        forget()
        unlocked = false
        canDelete = true
        await refresh(serverURL: serverURL)
    }

    /// Open the Locked folder for this session; throws on a wrong password.
    func unlock(serverURL: String, password: String) async throws {
        try await PhotoAPI(baseUrl: serverURL).unlockLocked(password: password)
        unlocked = true
        needsUnlock = false
        let next = afterUnlock
        afterUnlock = nil
        next?()
    }

    /// Close it again (idle timeout, or leaving locked content).
    func relock(serverURL: String) async {
        try? await PhotoAPI(baseUrl: serverURL).lockLocked()
        unlocked = false
    }

    /// Every 401 the API sees lands here. Signed in means locked content
    /// wants the password again; otherwise the server wants a login. The
    /// unlock call itself failing with 401 means the session is gone.
    func unauthorized(path: String) {
        if path.hasSuffix("/api/login") { return }
        if user != nil, !path.hasSuffix("/api/locked/unlock") {
            needsUnlock = true
        } else {
            forget()
            needsLogin = true
        }
    }

    private func remember(_ current: User) {
        user = current
        defaults.set(current.id, forKey: "user_id")
        defaults.set(current.name, forKey: "user_name")
        defaults.set(current.role, forKey: "user_role")
    }

    private func forget() {
        user = nil
        Auth.token = nil
        needsUnlock = false
        afterUnlock = nil
        for key in ["user_id", "user_name", "user_role"] { defaults.removeObject(forKey: key) }
    }
}
