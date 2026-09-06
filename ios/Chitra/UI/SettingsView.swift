import SwiftUI
import Photos

/**
 Server address + backup controls, modelled on the Android settings screen
 (itself modelled on Google Photos' "Backup" and "Back up device folders"):
 pick which albums sync, whether videos and mobile data are included, see
 status, and kick a run off by hand.
 */
struct SettingsView: View {
    let serverURL: String
    var onServerURLSaved: (String) -> Void

    @ObservedObject private var settings = SettingsStore.shared
    @ObservedObject private var backup = BackupService.shared

    @State private var url: String = ""
    @State private var health: String?
    @State private var hasAccess = DeviceMedia.hasAccess()
    @State private var albums: [DeviceAlbum] = []
    @State private var ledgerCount = 0
    @State private var permissionDenied = false

    private var prefs: BackupPrefs { settings.backup }
    private var selected: Set<String> { prefs.albumIds ?? DeviceMedia.defaultAlbumIds(albums) }

    var body: some View {
        List {
            serverSection
            backupSection
            statusSection
            albumsSection
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { url = serverURL }
        .task(id: serverURL) {
            health = await checkHealth()
            ledgerCount = UploadLedger.shared.count(server: serverURL)
        }
        .task(id: hasAccess) {
            guard hasAccess else { return }
            albums = await Task.detached { DeviceMedia.albums() }.value
            ledgerCount = UploadLedger.shared.count(server: serverURL)
        }
        .onChange(of: backup.run) { _, run in
            if run == nil { ledgerCount = UploadLedger.shared.count(server: serverURL) }
        }
        .alert("Photos permission needed for backup", isPresented: $permissionDenied) {
            Button("OK", role: .cancel) {}
        }
    }

    // MARK: - Sections

    private var serverSection: some View {
        Section("Server") {
            TextField("http://host:port", text: $url)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            HStack {
                Button("Save") {
                    onServerURLSaved(url.trimmingCharacters(in: .whitespaces))
                }
                .disabled(url.trimmingCharacters(in: .whitespaces) == serverURL)
                Spacer()
                Text(health ?? "Checking…")
                    .font(.caption)
                    .foregroundStyle(Palette.secondaryText)
                    .multilineTextAlignment(.trailing)
            }
        }
    }

    private var backupSection: some View {
        Section("Backup") {
            toggleRow(
                "Auto backup",
                "Upload new photos & videos from the albums below, in the background",
                isOn: prefs.enabled
            ) { want in
                if want {
                    Task { await enableBackup() }
                } else {
                    settings.setBackupEnabled(false)
                    backup.stop()
                }
            }

            if !hasAccess {
                Button("Allow photo access") {
                    Task { await requestAccess() }
                }
            }

            toggleRow("Back up videos", "Videos are large; off = photos only", isOn: prefs.includeVideos) {
                settings.setBackupVideos($0)
            }
            toggleRow("Wi-Fi only", "Never use mobile data for backup", isOn: prefs.wifiOnly) {
                settings.setBackupWifiOnly($0)
            }
        }
    }

    private var statusSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                if let run = backup.run {
                    Text(run.total > 0 ? "Backing up \(run.done) / \(run.total)" : "Checking for new photos…")
                        .font(.subheadline)
                    ProgressView(value: run.total == 0 ? 0 : Double(run.done) / Double(run.total))
                } else {
                    Text("Last backup: \(relativeLabel(prefs.lastRun))").font(.subheadline)
                    if !prefs.lastResult.isEmpty {
                        Text(prefs.lastResult).font(.caption).foregroundStyle(Palette.secondaryText)
                    }
                    if prefs.pending > 0 {
                        Text("\(prefs.pending) waiting to upload")
                            .font(.caption)
                            .foregroundStyle(Palette.warning)
                    }
                }
                Text("\(ledgerCount) items backed up to this server")
                    .font(.caption)
                    .foregroundStyle(Palette.secondaryText)
            }
            .padding(.vertical, 4)

            Button(backup.isRunning ? "Backing up…" : "Back up now") {
                backup.runNow(manual: true)
            }
            .disabled(!hasAccess || backup.isRunning)

            // iOS never guarantees a background window the way WorkManager's
            // periodic sweep does; say so rather than let it look broken.
            Text("iOS schedules background runs when it sees fit. Chitra also sweeps every time you open the app.")
                .font(.caption2)
                .foregroundStyle(Palette.secondaryText)
        }
    }

    private var albumsSection: some View {
        Section {
            if !hasAccess {
                Text("Allow photo access to list albums.")
                    .font(.caption)
                    .foregroundStyle(Palette.secondaryText)
            } else if albums.isEmpty {
                Text("No photos or videos found on this device.")
                    .font(.caption)
                    .foregroundStyle(Palette.secondaryText)
            }
            ForEach(albums) { album in
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8).fill(Palette.tile)
                        if let coverId = album.coverAssetId {
                            LocalAssetThumbnail(assetId: coverId)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                    }
                    .frame(width: 48, height: 48)
                    VStack(alignment: .leading) {
                        Text(album.name)
                        Text("\(album.count) items")
                            .font(.caption)
                            .foregroundStyle(Palette.secondaryText)
                    }
                    Spacer()
                    Toggle("", isOn: Binding(
                        get: { selected.contains(album.id) },
                        set: { on in
                            settings.updateBackupAlbums { current in
                                let base = current ?? DeviceMedia.defaultAlbumIds(albums)
                                return on ? base.union([album.id]) : base.subtracting([album.id])
                            }
                            // A newly enabled album backfills right away.
                            if on && settings.backup.enabled { backup.runNow(manual: true) }
                        }))
                    .labelsHidden()
                }
            }
        } header: {
            Text("Back up device albums")
        } footer: {
            Text("Only the albums switched on are uploaded. The camera roll is on by default; turn on Screenshots, shared albums and the rest as you like.")
        }
    }

    // MARK: - Actions

    private func toggleRow(_ title: String, _ subtitle: String, isOn: Bool, onChange: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: Binding(get: { isOn }, set: onChange)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(subtitle).font(.caption).foregroundStyle(Palette.secondaryText)
            }
        }
    }

    private func checkHealth() async -> String {
        do {
            let health = try await PhotoAPI(baseUrl: serverURL).health()
            return "Connected · \(health.itemsIndexed) items indexed"
        } catch {
            return "Not reachable (\(error.localizedDescription))"
        }
    }

    private func requestAccess() async {
        let granted = await DeviceMedia.requestAccess()
        hasAccess = granted
        if !granted { permissionDenied = true }
    }

    private func enableBackup() async {
        if !hasAccess {
            await requestAccess()
            guard hasAccess else { return }
        }
        // Persist a default album set only once the device albums are known.
        // Right after the permission grant the list is still empty, and
        // writing {} here would make backup silently upload nothing.
        if settings.backup.albumIds == nil {
            let known = albums.isEmpty ? await Task.detached { DeviceMedia.albums() }.value : albums
            albums = known
            let defaults = DeviceMedia.defaultAlbumIds(known)
            if !defaults.isEmpty { settings.setBackupAlbums(defaults) }
        }
        settings.setBackupEnabled(true)
        backup.start()
    }
}

/// A thumbnail straight out of PhotoKit, for the device-album rows.
struct LocalAssetThumbnail: View {
    let assetId: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Color.clear
            }
        }
        .task(id: assetId) {
            guard let asset = DeviceMedia.asset(withId: assetId) else { return }
            let options = PHImageRequestOptions()
            // High quality delivers exactly one callback; the opportunistic
            // mode calls back twice and a continuation may only resume once.
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            image = await withCheckedContinuation { continuation in
                PHImageManager.default().requestImage(
                    for: asset,
                    targetSize: CGSize(width: 144, height: 144),
                    contentMode: .aspectFill,
                    options: options
                ) { result, _ in
                    continuation.resume(returning: result)
                }
            }
        }
    }
}
