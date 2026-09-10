import Foundation
import Combine

/// Everything the backup service and the Settings screen share, mirroring
/// the Android `BackupPrefs` DataStore record.
struct BackupPrefs: Equatable {
    var enabled: Bool = false
    /// nil = the user never chose; the service falls back to the camera roll.
    var albumIds: Set<String>?
    var includeVideos: Bool = true
    var wifiOnly: Bool = true
    var lastRun: TimeInterval = 0
    var lastResult: String = ""
    var pending: Int = -1
}

/// UserDefaults-backed settings store. Android used a DataStore of flows;
/// here one observable object is what the SwiftUI views bind to.
final class SettingsStore: ObservableObject {
    static let shared = SettingsStore()
    static let defaultServerURL = "http://192.168.68.74:8000"

    private enum Key {
        static let serverURL = "server_url"
        static let backupEnabled = "backup_enabled"
        static let backupAlbums = "backup_albums"
        static let backupAlbumsSet = "backup_albums_set"
        static let backupVideos = "backup_videos"
        static let backupWifiOnly = "backup_wifi_only"
        static let backupLastRun = "backup_last_run"
        static let backupLastResult = "backup_last_result"
        static let backupPending = "backup_pending"
    }

    private let defaults: UserDefaults

    @Published private(set) var serverURL: String
    @Published private(set) var backup: BackupPrefs

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.serverURL = defaults.string(forKey: Key.serverURL) ?? Self.defaultServerURL
        self.backup = Self.readBackup(defaults)
    }

    private static func readBackup(_ d: UserDefaults) -> BackupPrefs {
        BackupPrefs(
            enabled: d.bool(forKey: Key.backupEnabled),
            albumIds: d.bool(forKey: Key.backupAlbumsSet)
                ? Set(d.stringArray(forKey: Key.backupAlbums) ?? [])
                : nil,
            includeVideos: d.object(forKey: Key.backupVideos) as? Bool ?? true,
            wifiOnly: d.object(forKey: Key.backupWifiOnly) as? Bool ?? true,
            lastRun: d.double(forKey: Key.backupLastRun),
            lastResult: d.string(forKey: Key.backupLastResult) ?? "",
            pending: d.object(forKey: Key.backupPending) as? Int ?? -1)
    }

    private func reload() {
        let fresh = Self.readBackup(defaults)
        if Thread.isMainThread {
            backup = fresh
        } else {
            DispatchQueue.main.async { self.backup = fresh }
        }
    }

    func setServerURL(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(trimmed, forKey: Key.serverURL)
        if Thread.isMainThread { serverURL = trimmed }
        else { DispatchQueue.main.async { self.serverURL = trimmed } }
    }

    func setBackupEnabled(_ on: Bool) {
        defaults.set(on, forKey: Key.backupEnabled)
        reload()
    }

    func setBackupAlbums(_ ids: Set<String>) {
        defaults.set(Array(ids), forKey: Key.backupAlbums)
        defaults.set(true, forKey: Key.backupAlbumsSet)
        reload()
    }

    /// Read-modify-write of the album set in one step, so two quick toggles
    /// cannot both read the old set and overwrite each other. `transform`
    /// receives nil while no set has been chosen yet.
    func updateBackupAlbums(_ transform: (Set<String>?) -> Set<String>) {
        let current = defaults.bool(forKey: Key.backupAlbumsSet)
            ? Set(defaults.stringArray(forKey: Key.backupAlbums) ?? [])
            : nil
        setBackupAlbums(transform(current))
    }

    func setBackupVideos(_ on: Bool) {
        defaults.set(on, forKey: Key.backupVideos)
        reload()
    }

    func setBackupWifiOnly(_ on: Bool) {
        defaults.set(on, forKey: Key.backupWifiOnly)
        reload()
    }

    func setBackupStatus(lastRun: TimeInterval, result: String, pending: Int) {
        defaults.set(lastRun, forKey: Key.backupLastRun)
        defaults.set(result, forKey: Key.backupLastResult)
        defaults.set(pending, forKey: Key.backupPending)
        reload()
    }
}
