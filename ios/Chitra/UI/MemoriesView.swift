import SwiftUI

/// The Memories tab — Photos' "For You" in shape: one full-bleed card per
/// year that has photos from this day, tapped to open that year's set.
struct MemoriesView: View {
    let serverURL: String

    @State private var memories: Memories?
    @State private var error: String?
    @State private var viewer: ViewerPresentation?

    var body: some View {
        NavigationStack {
            Group {
                if let error {
                    ContentUnavailableView {
                        Label("Can't Reach the Server", systemImage: "wifi.exclamationmark")
                    } description: {
                        Text(error).font(.caption)
                    }
                } else if let memories {
                    if memories.groups.isEmpty {
                        ContentUnavailableView("Nothing on This Day", systemImage: "sparkles",
                                               description: Text("Come back when the library holds photos taken on \(prettyMonthDay(memories.monthDay)) in an earlier year."))
                    } else {
                        cards(memories)
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Memories")
            .task(id: serverURL) { await load() }
            .refreshable { await load() }
        }
        .fullScreenCover(item: $viewer) { presentation in
            ViewerView(items: presentation.snapshot ?? [],
                       initialIndex: presentation.index,
                       serverURL: serverURL,
                       onAlbumChanged: {})
        }
    }

    private func cards(_ memories: Memories) -> some View {
        ScrollView {
            VStack(spacing: 20) {
                ForEach(memories.groups) { group in
                    if let cover = group.items.first {
                        Button {
                            viewer = ViewerPresentation(index: 0, snapshot: group.items)
                        } label: {
                            card(group: group, cover: cover, monthDay: memories.monthDay)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    private func card(group: MemoryGroup, cover: MediaItem, monthDay: String) -> some View {
        let years = Calendar.current.component(.year, from: Date()) - (Int(group.year) ?? 0)
        return RemoteImage(url: Urls.preview(serverURL, cover.id, version: cover.editVersion))
            .frame(height: 380)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 2) {
                    // The server labels a group from its CLIP embeddings and
                    // falls back to the literal "Memories", which reads oddly
                    // on a card inside the Memories tab.
                    Text(group.title.flatMap { $0 == "Memories" ? nil : $0 } ?? prettyMonthDay(monthDay))
                        .font(.title2.weight(.bold))
                    Text(subtitle(years: years, year: group.year, count: group.items.count))
                        .font(.subheadline)
                        .opacity(0.85)
                }
                .foregroundStyle(.white)
                .shadow(radius: 6)
                .padding(20)
            }
            .overlay(alignment: .bottom) {
                LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 140)
                    .allowsHitTesting(false)
            }
            .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private func subtitle(years: Int, year: String, count: Int) -> String {
        let photos = "\(count) photo\(count == 1 ? "" : "s")"
        guard years > 0 else { return "\(year) · \(photos)" }
        return "\(years) year\(years == 1 ? "" : "s") ago · \(photos)"
    }

    private func load() async {
        do {
            memories = try await PhotoAPI(baseUrl: serverURL).memories()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// The server reports "On this day" as "09-05"; spell it out for prose.
func prettyMonthDay(_ monthDay: String) -> String {
    let parts = monthDay.split(separator: "-")
    guard parts.count == 2, let month = Int(parts[0]), let day = Int(parts[1]),
          (1...12).contains(month) else { return monthDay }
    var components = DateComponents()
    components.year = Calendar.current.component(.year, from: Date())
    components.month = month
    components.day = day
    guard let date = Calendar.current.date(from: components) else { return monthDay }
    let formatter = DateFormatter()
    formatter.dateFormat = DateFormatter.dateFormat(fromTemplate: "MMMMd", options: 0, locale: .current) ?? "MMMM d"
    return formatter.string(from: date)
}
