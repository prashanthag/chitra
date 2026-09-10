import SwiftUI

/// The Android client's dark palette, so the two apps look like one product.
enum Palette {
    static let tile = Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x1C / 255)
    static let secondaryText = Color(red: 0x9A / 255, green: 0x9A / 255, blue: 0xA2 / 255)
    static let divider = Color(red: 0x26 / 255, green: 0x26 / 255, blue: 0x2A / 255)
    static let panel = Color(red: 0x16 / 255, green: 0x16 / 255, blue: 0x1A / 255)
    static let favorite = Color(red: 0xE9 / 255, green: 0x1E / 255, blue: 0x63 / 255)
    static let error = Color(red: 0xEF / 255, green: 0x53 / 255, blue: 0x50 / 255)
    static let warning = Color(red: 0xFF / 255, green: 0xB7 / 255, blue: 0x4D / 255)
}

/// "September 2026" — the month header of the main feed.
func monthLabel(_ epoch: Double?) -> String {
    guard let epoch, epoch > 0 else { return "Undated" }
    let formatter = DateFormatter()
    formatter.dateFormat = DateFormatter.dateFormat(fromTemplate: "yMMMM", options: 0, locale: .current) ?? "MMMM yyyy"
    return formatter.string(from: Date(timeIntervalSince1970: epoch))
}

/// "Uploaded 2 September 2026": the same wording the web and Android clients
/// use for the Recently uploaded view.
func uploadDayLabel(_ epoch: Double?) -> String {
    guard let epoch, epoch > 0 else { return "Upload date unknown" }
    let formatter = DateFormatter()
    formatter.dateStyle = .long
    formatter.timeStyle = .none
    return "Uploaded " + formatter.string(from: Date(timeIntervalSince1970: epoch))
}

func takenAtLabel(_ epoch: Double?) -> String {
    guard let epoch, epoch > 0 else { return "Date unknown" }
    let formatter = DateFormatter()
    formatter.dateFormat = "MMM d, yyyy h:mm a"
    return formatter.string(from: Date(timeIntervalSince1970: epoch))
}

func relativeLabel(_ epoch: TimeInterval) -> String {
    guard epoch > 0 else { return "never" }
    let when = Date(timeIntervalSince1970: epoch)
    let now = Date()
    // A run that finished a moment ago is "just now", not "in 0 seconds":
    // the timestamp is written before the label is read and rounds forward.
    if when >= now.addingTimeInterval(-5) { return "just now" }
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .full
    return formatter.localizedString(for: when, relativeTo: now)
}

func byteLabel(_ bytes: Int64) -> String {
    bytes >= 1_000_000
        ? String(format: "%.1f MB", Double(bytes) / 1e6)
        : "\(bytes / 1000) KB"
}
