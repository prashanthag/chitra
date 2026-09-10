import SwiftUI

/// Face clusters from the server's indexer, as the circular tiles Photos uses
/// for People.
struct PeopleView: View {
    let serverURL: String
    var onCluster: (Cluster) -> Void

    @State private var clusters: [Cluster]?
    @State private var status: FacesStatus?
    @State private var error: String?

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 16)]

    var body: some View {
        Group {
            if let error {
                ContentUnavailableView {
                    Label("Can't Load People", systemImage: "person.crop.circle.badge.exclamationmark")
                } description: {
                    Text(error).font(.caption)
                }
            } else if clusters == nil {
                ProgressView()
            } else if clusters!.isEmpty {
                ContentUnavailableView("No People Yet", systemImage: "person.2",
                                       description: Text("Run face_indexer.py on the server, then come back."))
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(clusters!) { cluster in
                            Button { onCluster(cluster) } label: {
                                VStack(spacing: 6) {
                                    Color.clear
                                        .aspectRatio(1, contentMode: .fit)
                                        .overlay { RemoteImage(url: Urls.clusterThumb(serverURL, cluster.id)) }
                                        .background(Palette.tile)
                                        .clipShape(Circle())
                                    Text(cluster.name ?? "\(cluster.count) photos")
                                        .font(.caption)
                                        .lineLimit(1)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)

                    if let status {
                        Text("\(status.clusters) groups · \(status.faces) faces · \(status.processed) of \(status.totalPhotos) photos processed")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.bottom, 24)
                    }
                }
            }
        }
        .navigationTitle("People")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: serverURL) {
            do {
                let api = PhotoAPI(baseUrl: serverURL)
                status = try await api.facesStatus()
                clusters = try await api.clusters()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Every photo in one face cluster.
struct ClusterMediaView: View {
    let cluster: Cluster
    let serverURL: String
    var onAlbumChanged: () -> Void

    @State private var items: [MediaItem]?
    @State private var error: String?
    @State private var viewer: ViewerPresentation?

    var body: some View {
        Group {
            if let error {
                ContentUnavailableView {
                    Label("Can't Load Photos", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(error).font(.caption)
                }
            } else if let items {
                GalleryGrid(items: items, serverURL: serverURL) { item in
                    viewer = ViewerPresentation(index: items.firstIndex(of: item) ?? 0, snapshot: items)
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(cluster.name ?? "Person \(cluster.id)")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                items = try await PhotoAPI(baseUrl: serverURL).clusterMedia(cluster.id)
            } catch {
                self.error = error.localizedDescription
            }
        }
        .fullScreenCover(item: $viewer) { presentation in
            ViewerView(items: presentation.snapshot ?? [],
                       initialIndex: presentation.index,
                       serverURL: serverURL,
                       onAlbumChanged: onAlbumChanged)
        }
    }
}
