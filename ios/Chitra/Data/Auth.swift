import Foundation

/// The session token every request carries, readable from any thread: the
/// JSON API, the image loader, the video player, the uploader and the share
/// downloader all go through here. This is the plumbing the Android client's
/// `Auth.interceptor` does; the UI-facing state lives in `AuthSession`.
enum Auth {
    private static let key = "session_token"
    private static let lock = NSLock()
    private static var stored: String? = UserDefaults.standard.string(forKey: key)

    static var token: String? {
        get {
            lock.lock(); defer { lock.unlock() }
            return stored
        }
        set {
            lock.lock(); stored = newValue; lock.unlock()
            if let newValue { UserDefaults.standard.set(newValue, forKey: key) }
            else { UserDefaults.standard.removeObject(forKey: key) }
        }
    }

    /// `Authorization: Bearer <token>` while signed in; nothing otherwise, so
    /// an open server (no accounts yet) sees plain requests.
    static func apply(to request: inout URLRequest) {
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    }

    /// The same header as a dictionary, for AVURLAsset.
    static func headers() -> [String: String] {
        token.map { ["Authorization": "Bearer \($0)"] } ?? [:]
    }
}
