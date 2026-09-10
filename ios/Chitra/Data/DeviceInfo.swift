import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// How this phone identifies itself on uploads (source device + camera fallback).
enum DeviceInfo {
    /// The user-visible device name. iOS 16+ hands unentitled apps the model
    /// name ("iPhone") rather than "Prashantha's iPhone"; either is a fine
    /// label for the server's "backed up from" field.
    static func name() -> String {
        #if canImport(UIKit)
        let device = UIDevice.current
        let trimmed = device.name.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? device.model : trimmed
        #else
        return Host.current().localizedName ?? "Mac"
        #endif
    }

    static func make() -> String { "Apple" }
}
