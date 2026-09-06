import SwiftUI
import UIKit

/// UIActivityViewController for SwiftUI — the share sheet the Android client
/// reaches through ACTION_SEND.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

enum Downloader {
    /// Pulls the actual file down so the share sheet has something real to
    /// hand on. HEIC goes out as JPEG, since most targets can't read HEIC.
    static func downloadForSharing(item: MediaItem, serverURL: String) async -> URL? {
        let isHEIC = ["heic", "heif"].contains(item.ext.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased())
        let source = Urls.full(serverURL, item.id, asJpeg: isHEIC)
        let name = isHEIC
            ? item.name.replacingOccurrences(of: "\\.hei[cf]$", with: ".jpg", options: [.regularExpression, .caseInsensitive])
            : item.name
        guard let url = URL(string: source) else { return nil }
        do {
            let (temporary, response) = try await URLSession.shared.download(from: url)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
            let destination = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: temporary, to: destination)
            return destination
        } catch {
            return nil
        }
    }
}
