import SwiftUI

/// Server-side adjustments: the sliders post to /api/media/<id>/edit, which
/// rewrites the file in place and bumps edit_version so every cached
/// thumbnail busts.
struct EditorView: View {
    let item: MediaItem
    let serverURL: String
    var onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var brightness: Double = 1.0
    @State private var contrast: Double = 1.0
    @State private var saturation: Double = 1.0
    @State private var sharpness: Double = 1.0
    @State private var saving = false
    @State private var version = 0

    private var api: PhotoAPI { PhotoAPI(baseUrl: serverURL) }

    private var previewURL: String {
        let isHEIC = ["heic", "heif"].contains(item.ext.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased())
        // A local counter on the URL forces a reload after each server-side edit.
        return Urls.full(serverURL, item.id, asJpeg: isHEIC) + (isHEIC ? "&" : "?") + "v=\(version)"
    }

    var body: some View {
        VStack(spacing: 0) {
            RemoteImage(url: previewURL, contentMode: .fit)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)

            VStack(spacing: 8) {
                slider("Brightness", value: $brightness)
                slider("Contrast", value: $contrast)
                slider("Saturation", value: $saturation)
                slider("Sharpness", value: $sharpness)
            }
            .padding(16)
        }
        .navigationTitle("Edit · \(item.name)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    Task {
                        saving = true
                        try? await api.edit(item.id, params: ["auto_enhance": .bool(true)])
                        saving = false
                        version += 1
                    }
                } label: {
                    Image(systemName: "wand.and.stars")
                }
                .disabled(saving)

                Button {
                    Task {
                        saving = true
                        try? await api.edit(item.id, params: [
                            "brightness": .number(brightness),
                            "contrast": .number(contrast),
                            "saturation": .number(saturation),
                            "sharpness": .number(sharpness),
                        ])
                        saving = false
                        onSaved()
                        dismiss()
                    }
                } label: {
                    Image(systemName: "checkmark")
                }
                .disabled(saving)
            }
        }
    }

    private func slider(_ label: String, value: Binding<Double>) -> some View {
        VStack(spacing: 2) {
            HStack {
                Text(label)
                Spacer()
                Text(String(format: "%.2f", value.wrappedValue))
                    .foregroundStyle(Palette.secondaryText)
                    .monospacedDigit()
            }
            .font(.subheadline)
            Slider(value: value, in: 0...2, step: 0.05)
        }
    }
}
