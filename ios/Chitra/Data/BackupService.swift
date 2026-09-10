import Foundation
import Photos
import BackgroundTasks
import Network
import UIKit

/// Live progress of a backup run, for the Settings status card.
struct BackupRun: Equatable {
    var done = 0
    var total = 0
    var sent = 0
    var dups = 0
    var failed = 0
}

/**
 Backs up the chosen device albums to the server — the iOS counterpart of
 Android's `BackupWorker`.

 Three triggers share it: a `BGProcessingTask` the system runs when it feels
 like it, a PhotoKit change observation that fires while the app is alive, and
 the manual "Back up now" button. What to send is decided by `BackupPlanner`
 from PhotoKit + the `UploadLedger`; the server's /api/upload/check lets a
 reinstalled app skip everything the library already holds.

 iOS gives no equivalent of WorkManager's guaranteed 15-minute sweep: a
 background run happens only when the system schedules one, so the app also
 sweeps whenever it comes to the foreground.
 */
@MainActor
final class BackupService: ObservableObject {
    static let shared = BackupService()
    static let taskIdentifier = "com.buildapp.photos.backup"

    /// Non-nil while a run is in flight.
    @Published private(set) var run: BackupRun?

    private let settings = SettingsStore.shared
    private let ledger = UploadLedger.shared
    private var current: Task<Void, Never>?
    private var observer: LibraryObserver?
    private let network = NetworkMonitor()

    private init() {}

    var isRunning: Bool { run != nil }

    // MARK: - Triggers

    /// Called once at launch. Registering the BG task must happen before the
    /// app finishes launching or the scheduler rejects it.
    func registerBackgroundTask() {
        // The main queue, not nil: a nil queue is a private serial one, and
        // this handler hops straight onto the main-actor service.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskIdentifier, using: .main) { task in
            guard let task = task as? BGProcessingTask else { return }
            MainActor.assumeIsolated { self.handle(task) }
        }
    }

    /// Turn backup on: watch the library, ask the system for background runs,
    /// and do an immediate first pass.
    func start() {
        observeLibrary()
        scheduleBackgroundRun()
        runNow(manual: true)
    }

    func stop() {
        observer = nil
        current?.cancel()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
    }

    /// Called once per launch. The library observer and the background-task
    /// request live only as long as the process, so a restart has to re-arm
    /// them — otherwise backup silently stops after the first cold start.
    func resumeIfEnabled() {
        guard settings.backup.enabled else { return }
        observeLibrary()
        scheduleBackgroundRun()
        runNow(manual: false)
    }

    /// A sweep on foregrounding, and whenever the library gains something.
    /// Silent when backup is off or nothing is new.
    func sweep() {
        guard settings.backup.enabled else { return }
        runNow(manual: false)
    }

    /// "Back up now": a manual run ignores the Wi-Fi-only preference, like the
    /// Android button does.
    func runNow(manual: Bool) {
        let previous = current
        current = Task { [weak self] in
            // One run at a time. The foreground sweep, the library trigger and
            // "Back up now" can all fire within a minute; unserialised they
            // would each re-plan the same roll and pre-flight it three times
            // over. Waiting rather than skipping, so a manual tap still runs.
            await previous?.value
            guard let self else { return }
            await self.runOnce(manual: manual)
        }
    }

    func cancel() {
        current?.cancel()
    }

    private func observeLibrary() {
        guard observer == nil else { return }
        observer = LibraryObserver { [weak self] in
            Task { @MainActor in self?.sweep() }
        }
    }

    private func scheduleBackgroundRun() {
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    private func handle(_ task: BGProcessingTask) {
        // The next one is queued up front: a run that the system kills still
        // leaves a successor behind.
        scheduleBackgroundRun()
        let work = Task { @MainActor in
            await self.runOnce(manual: false)
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { work.cancel() }
    }

    // MARK: - The run

    private func runOnce(manual: Bool) async {
        let prefs = settings.backup
        let serverUrl = settings.serverURL
        let now = Date().timeIntervalSince1970
        guard prefs.enabled || manual else { return }
        guard DeviceMedia.hasAccess() else {
            settings.setBackupStatus(lastRun: now, result: "Photos permission not granted", pending: -1)
            return
        }
        // Wi-Fi only applies to the automatic triggers; the button is the
        // user saying "now, on whatever connection this is".
        if !manual && prefs.wifiOnly && network.isExpensive {
            return
        }

        let deviceName = DeviceInfo.name()
        let albumIds: Set<String>
        if let chosen = prefs.albumIds {
            albumIds = chosen
        } else {
            albumIds = DeviceMedia.defaultAlbumIds(await allAlbums())
        }
        let already = ledger.uploadedIds(server: serverUrl)
        let options = BackupOptions(albumIds: albumIds, includeVideos: prefs.includeVideos)
        let items = await Task.detached {
            DeviceMedia.items(albumIds: albumIds, includeVideos: prefs.includeVideos)
        }.value
        let plan = BackupPlanner.plan(items: items, uploadedIds: already, options: options)

        // Progress counts the whole roll, not just this run: a background run
        // is cut short after a few minutes and the next one would otherwise
        // restart the status card at 0 / <remaining>.
        let base = already.count
        guard !plan.isEmpty else {
            settings.setBackupStatus(lastRun: now, result: "Up to date", pending: 0)
            return
        }

        // Keep going for a while after the user swipes away, the way a
        // foreground data-sync job does on Android.
        let assertion = UIApplication.shared.beginBackgroundTask(withName: "chitra-backup")
        defer { if assertion != .invalid { UIApplication.shared.endBackgroundTask(assertion) } }

        var progress = BackupRun(done: base, total: base + plan.count)
        run = progress

        // Pre-flight: anything the server already has is ledgered without
        // moving a byte. If the check itself fails we just upload; the server
        // de-duplicates on its side too.
        var remaining: [DeviceItem] = []
        remaining.reserveCapacity(plan.count)
        for chunk in plan.chunked(into: 200) {
            if Task.isCancelled { break }
            var files: [Uploader.CheckFile] = []
            var hashed: [DeviceItem] = []
            for item in chunk {
                if Task.isCancelled { break }
                // One content hash per file so the server can match by bytes:
                // a renamed copy or a photo that reached the library another
                // way is skipped, and a new file that happens to share a name
                // and size is not. Hashes are cached in the ledger, so this
                // costs a full read only the first time an asset is seen.
                let fingerprint = await fingerprint(of: item)
                files.append(Uploader.CheckFile(name: item.name,
                                                size: fingerprint?.size ?? item.size,
                                                hash: fingerprint?.hash))
                hashed.append(item)
            }
            let exists = await Uploader.check(serverUrl: serverUrl, files: files)
            guard let exists, exists.count == hashed.count else {
                remaining.append(contentsOf: hashed)
                continue
            }
            for (index, item) in hashed.enumerated() where exists[index] {
                ledger.markUploaded(server: serverUrl, item: item, serverId: nil, duplicate: true)
                progress.dups += 1
                progress.done += 1
            }
            remaining.append(contentsOf: hashed.enumerated().filter { !exists[$0.offset] }.map(\.element))
            // After a reinstall the pre-flight covers the whole camera roll and
            // takes minutes; report after every chunk so the status card does
            // not sit at 0/N until it ends.
            run = progress
        }

        var consecutiveFailures = 0
        for item in remaining {
            if Task.isCancelled { break }
            guard let asset = DeviceMedia.asset(withId: item.id) else {
                progress.done += 1
                continue
            }
            let source = Uploader.Source(folder: item.albumName, device: deviceName)
            let result = await Uploader.upload(asset: asset, name: item.name,
                                               serverUrl: serverUrl, source: source)
            progress.done += 1
            if result.ok {
                consecutiveFailures = 0
                ledger.markUploaded(server: serverUrl, item: item, serverId: result.serverId, duplicate: result.duplicate)
                if result.duplicate { progress.dups += 1 } else { progress.sent += 1 }
            } else {
                progress.failed += 1
                // A server that is down fails every file the same way; stop
                // hammering it and let the next trigger try again.
                consecutiveFailures += 1
                if consecutiveFailures >= 5 { break }
            }
            if progress.done % 5 == 0 { run = progress }
        }
        run = progress

        let pending = (base + plan.count) - progress.done + progress.failed
        var summary = "Sent \(progress.sent)"
        if progress.dups > 0 { summary += ", \(progress.dups) already on server" }
        if progress.failed > 0 { summary += ", \(progress.failed) failed" }
        if pending > 0 && progress.failed == 0 { summary += ", \(pending) waiting" }
        settings.setBackupStatus(lastRun: Date().timeIntervalSince1970, result: summary, pending: pending)
        run = nil
    }

    /// Content hash + exact byte size for an asset, from the ledger cache when
    /// the asset hasn't changed since it was last hashed.
    private func fingerprint(of item: DeviceItem) async -> (hash: String, size: Int64)? {
        guard let asset = DeviceMedia.asset(withId: item.id) else { return nil }
        let modified = (asset.modificationDate ?? asset.creationDate ?? .distantPast).timeIntervalSince1970
        if let cached = ledger.cachedHash(mediaId: item.id, modified: modified) { return cached }
        guard let fresh = await DeviceMedia.contentHash(for: asset) else { return nil }
        ledger.cacheHash(mediaId: item.id, modified: modified, size: fresh.size, hash: fresh.hash)
        return fresh
    }

    private func allAlbums() async -> [DeviceAlbum] {
        await Task.detached { DeviceMedia.albums() }.value
    }
}

/// Fires whenever the photo library changes, the nearest thing iOS has to
/// Android's content-URI trigger.
private final class LibraryObserver: NSObject, PHPhotoLibraryChangeObserver {
    private let onChange: () -> Void

    init(onChange: @escaping () -> Void) {
        self.onChange = onChange
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    deinit { PHPhotoLibrary.shared().unregisterChangeObserver(self) }

    func photoLibraryDidChange(_ changeInstance: PHChange) { onChange() }
}

/// Whether the current route costs money (cellular / personal hotspot), for
/// the Wi-Fi-only preference.
final class NetworkMonitor {
    private let monitor = NWPathMonitor()
    private var expensive = false
    private let lock = NSLock()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.lock.lock()
            self?.expensive = path.isExpensive || path.isConstrained
            self?.lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "com.buildapp.photos.network"))
    }

    deinit { monitor.cancel() }

    var isExpensive: Bool {
        lock.lock(); defer { lock.unlock() }
        return expensive
    }
}

extension Array {
    func chunked(into size: Int) -> [[Element]] {
        guard size > 0 else { return [self] }
        return stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}
