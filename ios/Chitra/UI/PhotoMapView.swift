import SwiftUI
import MapKit

/// Places: every geotagged photo as a thumbnail pin. The Android client used
/// osmdroid; MapKit does the same job with no tile-source setup.
struct PhotoMapView: View {
    let serverURL: String

    @State private var locations: [LocationItem]?
    @State private var error: String?
    @State private var camera: MapCameraPosition = .automatic
    @State private var viewer: ViewerPresentation?

    var body: some View {
        Group {
            if let error {
                ContentUnavailableView {
                    Label("Can't Load Places", systemImage: "map")
                } description: {
                    Text(error).font(.caption)
                }
            } else if locations == nil {
                ProgressView()
            } else if locations!.isEmpty {
                ContentUnavailableView("No Places Yet", systemImage: "mappin.slash",
                                       description: Text("Nothing in the library carries GPS coordinates."))
            } else {
                Map(position: $camera) {
                    ForEach(locations!) { location in
                        Annotation(location.name,
                                   coordinate: CLLocationCoordinate2D(latitude: location.lat, longitude: location.lng)) {
                            Button {
                                viewer = ViewerPresentation(
                                    index: 0,
                                    snapshot: [MediaItem(id: location.id, name: location.name, kind: location.kind)])
                            } label: {
                                RemoteImage(url: Urls.thumb(serverURL, location.id, w: 160))
                                    .frame(width: 46, height: 46)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white, lineWidth: 2))
                                    .shadow(radius: 3)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .navigationTitle("Places")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: serverURL) {
            do {
                let fetched = try await PhotoAPI(baseUrl: serverURL).locations()
                locations = fetched
                // Frame every pin, the way the Android map fits its bounding box.
                if let region = boundingRegion(fetched) { camera = .region(region) }
            } catch {
                self.error = error.localizedDescription
            }
        }
        .fullScreenCover(item: $viewer) { presentation in
            ViewerView(items: presentation.snapshot ?? [],
                       initialIndex: presentation.index,
                       serverURL: serverURL)
        }
    }

    private func boundingRegion(_ items: [LocationItem]) -> MKCoordinateRegion? {
        guard !items.isEmpty else { return nil }
        let lats = items.map(\.lat), lngs = items.map(\.lng)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLng = lngs.min(), let maxLng = lngs.max() else { return nil }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLng + maxLng) / 2)
        let span = MKCoordinateSpan(
            latitudeDelta: max(0.01, (maxLat - minLat) * 1.4),
            longitudeDelta: max(0.01, (maxLng - minLng) * 1.4))
        return MKCoordinateRegion(center: center, span: span)
    }
}
