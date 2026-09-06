import SwiftUI
import UIKit

/// Thumbnails come back versioned and immutable-cached from the server, so a
/// memory cache in front of URLCache is all this needs — the Coil setup the
/// Android client leans on, minus the library.
actor ImageLoader {
    static let shared = ImageLoader()

    private let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 500
        cache.totalCostLimit = 128 << 20
        return cache
    }()

    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 512 << 20, diskPath: "chitra-images")
        cfg.requestCachePolicy = .returnCacheDataElseLoad
        cfg.timeoutIntervalForRequest = 30
        return URLSession(configuration: cfg)
    }()

    func cached(_ urlString: String) -> UIImage? {
        memory.object(forKey: urlString as NSString)
    }

    func image(for urlString: String) async -> UIImage? {
        if let hit = memory.object(forKey: urlString as NSString) { return hit }
        if let running = inFlight[urlString] { return await running.value }
        guard let url = URL(string: urlString) else { return nil }

        let task = Task<UIImage?, Never> { [session] in
            guard let (data, response) = try? await session.data(from: url),
                  let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let image = UIImage(data: data)
            else { return nil }
            return image
        }
        inFlight[urlString] = task
        let image = await task.value
        inFlight[urlString] = nil
        if let image {
            memory.setObject(image, forKey: urlString as NSString,
                             cost: Int(image.size.width * image.size.height * 4))
        }
        return image
    }
}

/// An image from the server, with the placeholder tile showing through until
/// it lands. Reloads when the URL changes (which is how an edit's bumped
/// `edit_version` busts the cache).
struct RemoteImage<Placeholder: View>: View {
    let url: String
    var contentMode: ContentMode = .fill
    @ViewBuilder var placeholder: () -> Placeholder

    @State private var image: UIImage?
    @State private var loaded = false

    var body: some View {
        // A resizable image with .fill returns a size that *covers* the
        // proposal, so it grows past the space it was given and pushes its
        // neighbours out of a grid row. Laying it out inside a GeometryReader
        // pins it to the space actually offered and clips the overflow, so
        // every caller can treat this as "fills its slot".
        GeometryReader { geometry in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: contentMode)
                        .frame(width: geometry.size.width, height: geometry.size.height)
                } else {
                    placeholder()
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .task(id: url) {
            if let hit = await ImageLoader.shared.cached(url) {
                image = hit
                loaded = true
                return
            }
            image = nil
            loaded = false
            let fetched = await ImageLoader.shared.image(for: url)
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.15)) { image = fetched }
            loaded = true
        }
    }
}

extension RemoteImage where Placeholder == Color {
    init(url: String, contentMode: ContentMode = .fill) {
        self.init(url: url, contentMode: contentMode, placeholder: { Palette.tile })
    }
}
