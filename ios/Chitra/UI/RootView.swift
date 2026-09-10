import SwiftUI

/// The four tabs Photos uses. Everything the Android client hung off a single
/// top bar — map, albums, people, search, settings — lives inside one of them.
struct RootView: View {
    @StateObject private var library = GalleryViewModel()
    @ObservedObject private var backup = BackupService.shared
    @ObservedObject private var auth = AuthSession.shared

    @State private var tab: Tab = Tab.initial
    /// Bumped whenever album membership changes anywhere, so the Albums tab
    /// reloads its counts and covers.
    @State private var albumsChanged = 0

    enum Tab: Hashable {
        case library, memories, albums, search

        static var initial: Tab {
            switch DebugHooks.initialRoute {
            case "albums", "people", "map": return .albums
            case "memories": return .memories
            case "search": return .search
            default: return .library
            }
        }
    }

    var body: some View {
        TabView(selection: $tab) {
            LibraryView(vm: library)
                .tabItem { Label("Library", systemImage: "photo.on.rectangle.angled") }
                .tag(Tab.library)

            MemoriesView(serverURL: library.serverURL)
                .tabItem { Label("Memories", systemImage: "sparkles") }
                .tag(Tab.memories)

            AlbumsView(serverURL: library.serverURL,
                       reloadKey: albumsChanged,
                       onAlbumsChanged: { albumsChanged += 1 })
                .tabItem { Label("Albums", systemImage: "rectangle.stack") }
                .tag(Tab.albums)

            SearchView(serverURL: library.serverURL)
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(Tab.search)
        }
        .onAppear {
            // scenePhase is already .active by the time the first view appears,
            // so its onChange never fires for the launch itself.
            backup.resumeIfEnabled()
        }
        // Accounts: a 401 anywhere asks to sign in; the Locked folder asks
        // for the password on every open and whenever the session relocked.
        .sheet(isPresented: $auth.needsLogin) {
            LoginSheet(serverURL: library.serverURL) { library.refresh(); albumsChanged += 1 }
        }
        .sheet(isPresented: $auth.needsUnlock) {
            UnlockSheet(serverURL: library.serverURL)
        }
        .task(id: library.serverURL) {
            await auth.refresh(serverURL: library.serverURL)
            if let name = DebugHooks.loginName {
                DebugHooks.loginName = nil
                if (try? await auth.login(serverURL: library.serverURL, name: name,
                                          password: DebugHooks.loginPassword ?? "")) != nil {
                    library.refresh()
                }
            }
        }
    }
}
