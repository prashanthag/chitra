import SwiftUI

/// Face clusters from the server's face indexer, as circular tiles.
struct PeopleView: View {
    let serverURL: String
    var onCluster: (Cluster) -> Void

    @State private var clusters: [Cluster]?
    @State private var status: FacesStatus?
    @State private var error: String?

    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 12)]

    var body: some View {
        Group {
            if let error {
                Text("Error: \(error)")
                    .foregroundStyle(Palette.error)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if clusters == nil {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if clusters!.isEmpty {
                VStack(spacing: 6) {
                    Text("No people yet").font(.headline)
                    Text("Run face indexing on the server, then come back.")
                        .font(.caption)
                        .foregroundStyle(Palette.secondaryText)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(24)
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(clusters!) { cluster in
                            Button { onCluster(cluster) } label: {
                                VStack(spacing: 4) {
                                    Color.clear
                                        .aspectRatio(1, contentMode: .fit)
                                        .overlay { RemoteImage(url: Urls.clusterThumb(serverURL, cluster.id)) }
                                        .background(Palette.tile)
                                        .clipShape(Circle())
                                    Text(cluster.name ?? "\(cluster.count) photos")
                                        .font(.caption)
                                        .foregroundStyle(Color(white: 0.83))
                                        .lineLimit(1)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(12)
                }
            }
        }
        .navigationTitle("People")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let status {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 1) {
                        Text("People").font(.headline)
                        Text("\(status.clusters) groups · \(status.faces) faces · \(status.processed)/\(status.totalPhotos) processed")
                            .font(.system(size: 10))
                            .foregroundStyle(Palette.secondaryText)
                    }
                }
            }
        }
        .task {
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

/// Every photo in one face cluster — a flat grid, no month headers, matching
/// the Android cluster screen.
struct ClusterMediaView: View {
    let cluster: Cluster
    let serverURL: String
    var onItemTap: ([MediaItem], Int) -> Void

    @State private var items: [MediaItem]?
    @State private var error: String?

    var body: some View {
        Group {
            if let error {
                Text("Error: \(error)").foregroundStyle(Palette.error)
            } else if let items {
                GeometryReader { geometry in
                    let available = geometry.size.width - 4
                    let columns = max(1, Int(available / 110))
                    let tile = (available - 2 * CGFloat(columns - 1)) / CGFloat(columns)
                    ScrollView {
                        LazyVGrid(columns: Array(repeating: GridItem(.fixed(tile), spacing: 2), count: columns), spacing: 2) {
                            ForEach(items) { item in
                                Tile(item: item, serverURL: serverURL, size: tile)
                                    .onTapGesture {
                                        onItemTap(items, items.firstIndex(of: item) ?? 0)
                                    }
                            }
                        }
                        .padding(2)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(cluster.name ?? "Person \(cluster.id) · \(cluster.count) photos")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                items = try await PhotoAPI(baseUrl: serverURL).clusterMedia(cluster.id)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
