import Foundation

/**
 Debug-build-only launch environment so a script can configure the app
 without driving the UI — the iOS counterpart of the Android client's
 `am start --es` extras:

     SIMCTL_CHILD_CHITRA_SERVER_URL=http://127.0.0.1:8765 \
     SIMCTL_CHILD_CHITRA_ROUTE=settings \
     SIMCTL_CHILD_CHITRA_FILTER=uploads \
     SIMCTL_CHILD_CHITRA_BACKUP_ENABLE=1 \
     SIMCTL_CHILD_CHITRA_BACKUP_ALBUMS=Recents,Screenshots \
       xcrun simctl launch --terminate-running-process <device> com.buildapp.photos

 (simctl passes any SIMCTL_CHILD_-prefixed variable through to the app; in
 Xcode they go in the scheme's Run > Arguments > Environment Variables.)

 A release build ignores all of it.
 */
enum DebugHooks {
    /// Filter the library should open on, and where to start: a tab
    /// ("memories", "albums", "search") or a screen pushed inside one
    /// ("settings", "people", "map").
    static var initialFilter: Filter?
    static var initialRoute: String?

    static func apply() {
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        let settings = SettingsStore.shared

        if let url = env["CHITRA_SERVER_URL"], !url.isEmpty { settings.setServerURL(url) }
        if let filter = env["CHITRA_FILTER"] { initialFilter = Filter(rawValue: filter.lowercased()) }
        if let route = env["CHITRA_ROUTE"] { initialRoute = route.lowercased() }
        if let videos = env["CHITRA_BACKUP_VIDEOS"] { settings.setBackupVideos(flag(videos)) }
        if let wifi = env["CHITRA_BACKUP_WIFI_ONLY"] { settings.setBackupWifiOnly(flag(wifi)) }

        if let names = env["CHITRA_BACKUP_ALBUMS"] {
            let wanted = Set(names.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                .filter { !$0.isEmpty })
            let ids = DeviceMedia.albums().filter { wanted.contains($0.name.lowercased()) }.map(\.id)
            settings.setBackupAlbums(Set(ids))
        }
        if let enable = env["CHITRA_BACKUP_ENABLE"] {
            settings.setBackupEnabled(flag(enable))
        }
        // Wipe the ledger before starting a run, or the service can read it
        // first and find nothing to send.
        if let clear = env["CHITRA_LEDGER_CLEAR"], flag(clear) {
            UploadLedger.shared.clear(server: settings.serverURL)
        }
        #endif
    }

    private static func flag(_ value: String) -> Bool {
        ["1", "true", "yes", "on"].contains(value.lowercased())
    }
}
