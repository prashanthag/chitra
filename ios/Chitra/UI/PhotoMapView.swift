import SwiftUI
import MapKit

/// Every geotagged photo as a pin. The Android client used osmdroid; here
/// MapKit does the same job with no tile-source setup.
struct PhotoMapView: View {
    let serverURL: String
    var onMarkerTap: (LocationItem) -> Void

    @State private var locations: [LocationItem]?
    @State private var error: String?
    @State private var camera: MapCameraPosition = .automatic

    var body: some View {
        Group {
            if let error {
                Text("Error: \(error)").foregroundStyle(Palette.error)
            } else if locations == nil {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if locations!.isEmpty {
                Text("No GPS-tagged photos yet")
                    .foregroundStyle(Palette.secondaryText)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Map(position: $camera) {
                    ForEach(locations!) { location in
                        Annotation(location.name,
                                   coordinate: CLLocationCoordinate2D(latitude: location.lat, longitude: location.lng)) {
                            Button { onMarkerTap(location) } label: {
                                RemoteImage(url: Urls.thumb(serverURL, location.id, w: 160))
                                    .frame(width: 44, height: 44)
                                    .clipShape(RoundedRectangle(cornerRadius: 6))
                                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(.white, lineWidth: 2))
                                    .shadow(radius: 3)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .navigationTitle("Map · \(locations.map { "\($0.count)" } ?? "…") geotagged")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                let fetched = try await PhotoAPI(baseUrl: serverURL).locations()
                locations = fetched
                // Fit the view to every pin, the way the Android map does.
                if let region = boundingRegion(fetched) {
                    camera = .region(region)
                }
            } catch {
                self.error = error.localizedDescription
            }
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
