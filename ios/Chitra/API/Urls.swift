import Foundation

/// Thumbnail / preview / playback URLs, built by hand exactly like the
/// Android client's `Urls` helper — none of these go through the JSON API.
enum Urls {
    static func base(_ serverUrl: String) -> String {
        serverUrl.hasSuffix("/") ? serverUrl : serverUrl + "/"
    }

    /// Thumb URL. With a version the server marks the response immutable and
    /// the image cache never re-fetches, so only pass one that came from the
    /// server (edit_version); a caller with no version gets the short-lived
    /// unversioned URL instead of pinning "?v=0" forever.
    static func thumb(_ serverUrl: String, _ id: String, version: Int? = nil, w: Int? = nil) -> String {
        var q: [String] = []
        if let version { q.append("v=\(version)") }
        if let w { q.append("w=\(w)") }
        return base(serverUrl) + "api/media/\(id)/thumb" + (q.isEmpty ? "" : "?" + q.joined(separator: "&"))
    }

    /// Viewer-sized (2048px) JPEG of a photo, cached and versioned like a thumb.
    static func preview(_ serverUrl: String, _ id: String, version: Int? = nil) -> String {
        base(serverUrl) + "api/media/\(id)/preview" + (version.map { "?v=\($0)" } ?? "")
    }

    static func full(_ serverUrl: String, _ id: String, asJpeg: Bool = false) -> String {
        base(serverUrl) + "api/media/\(id)/full" + (asJpeg ? "?as=jpeg" : "")
    }

    static func stream(_ serverUrl: String, _ id: String) -> String {
        base(serverUrl) + "api/media/\(id)/stream.mp4"
    }

    /// Playback URL: the server redirects to the original when the phone can
    /// decode it and the bitrate suits Wi-Fi, else to the 1080p transcode.
    /// Every iPhone since the 6s decodes HEVC in hardware.
    static func play(_ serverUrl: String, _ id: String, codecs: String = "h264,hevc") -> String {
        base(serverUrl) + "api/media/\(id)/play?codecs=\(codecs)"
    }

    static func clusterThumb(_ serverUrl: String, _ id: Int) -> String {
        base(serverUrl) + "api/clusters/\(id)/thumb"
    }

    static func shareLink(_ serverUrl: String, _ token: String) -> String {
        base(serverUrl) + "s/\(token)"
    }
}
