import SwiftUI

@main
struct ChitraApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Debug builds only: apply launch environment before the first view is
        // built, so the view model reads the configured server.
        DebugHooks.apply()
        // The scheduler only accepts registrations made before launch finishes.
        BackupService.shared.registerBackgroundTask()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
        .onChange(of: scenePhase) { _, phase in
            // iOS hands out no guaranteed periodic window, so every trip to the
            // foreground doubles as the backup sweep.
            if phase == .active { BackupService.shared.sweep() }
        }
    }
}
