import Foundation
import Photos

/// An album on the device as PhotoKit sees it (Recents, Screenshots,
/// WhatsApp, a shared album…). The iOS counterpart of a MediaStore bucket.
struct DeviceAlbum: Identifiable, Hashable {
    let id: String              // PHAssetCollection.localIdentifier
    let name: String
    let count: Int
    /// localIdentifier of the newest asset, for the album thumbnail.
    let coverAssetId: String?
}

/// One photo or video from the library. Plain data so the planner can be
/// unit-tested off-device.
struct DeviceItem: Identifiable, Hashable {
    let id: String              // PHAsset.localIdentifier
    let name: String
    /// Byte size when PhotoKit will tell us, else -1. The exact size always
    /// comes back from the hashing pass, which reads the real bytes.
    let size: Int64
    let albumId: String
    let albumName: String
    let isVideo: Bool
    /// Creation date, epoch seconds — what "newest first" sorts on.
    let dateAdded: TimeInterval
}

/// Reads the device library through PhotoKit, grouped by the album an asset
/// lives in. The Android client read MediaStore's unified Files collection;
/// PhotoKit has no folder column, so an album takes the folder's place.
enum DeviceMedia {

    // MARK: - Permission

    static func authorizationStatus() -> PHAuthorizationStatus {
        PHPhotoLibrary.authorizationStatus(for: .readWrite)
    }

    static func hasAccess() -> Bool {
        let s = authorizationStatus()
        return s == .authorized || s == .limited
    }

    static func requestAccess() async -> Bool {
        await withCheckedContinuation { cont in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                cont.resume(returning: status == .authorized || status == .limited)
            }
        }
    }

    // MARK: - Albums

    /// Every album worth backing up, camera roll first, then the biggest.
    /// Smart albums that are views rather than places (Favorites, Recently
    /// Deleted, Hidden) are left out — their contents already live in the
    /// camera roll, and backing them up twice would just churn.
    static func albums() -> [DeviceAlbum] {
        guard hasAccess() else { return [] }
        var out: [DeviceAlbum] = []
        var seen = Set<String>()

        func add(_ collection: PHAssetCollection) {
            guard !seen.contains(collection.localIdentifier) else { return }
            let assets = PHAsset.fetchAssets(in: collection, options: assetFetchOptions(includeVideos: true))
            guard assets.count > 0 else { return }
            seen.insert(collection.localIdentifier)
            out.append(DeviceAlbum(
                id: collection.localIdentifier,
                name: collection.localizedTitle ?? "Album",
                count: assets.count,
                coverAssetId: assets.firstObject?.localIdentifier))
        }

        let library = PHAssetCollection.fetchAssetCollections(
            with: .smartAlbum, subtype: .smartAlbumUserLibrary, options: nil)
        library.enumerateObjects { c, _, _ in add(c) }

        let interestingSmart: [PHAssetCollectionSubtype] = [
            .smartAlbumScreenshots, .smartAlbumSelfPortraits, .smartAlbumPanoramas,
            .smartAlbumBursts, .smartAlbumLivePhotos, .smartAlbumSlomoVideos,
            .smartAlbumTimelapses, .smartAlbumDepthEffect,
        ]
        for subtype in interestingSmart {
            PHAssetCollection.fetchAssetCollections(with: .smartAlbum, subtype: subtype, options: nil)
                .enumerateObjects { c, _, _ in add(c) }
        }

        PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
            .enumerateObjects { c, _, _ in add(c) }

        // The camera roll stays pinned first (it is the default choice), the
        // rest sort by size like the Android folder list.
        let cameraRollId = library.firstObject?.localIdentifier
        return out.sorted { a, b in
            if a.id == cameraRollId { return true }
            if b.id == cameraRollId { return false }
            return a.count > b.count
        }
    }

    /// The album backup starts with when the user has never chosen: the
    /// camera roll, or the largest album if PhotoKit has no user library.
    static func defaultAlbumIds(_ albums: [DeviceAlbum]) -> Set<String> {
        guard let first = albums.first else { return [] }
        return [first.id]
    }

    // MARK: - Items

    private static func assetFetchOptions(includeVideos: Bool) -> PHFetchOptions {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.predicate = includeVideos
            ? NSPredicate(format: "mediaType == %d OR mediaType == %d",
                          PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
            : NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        return opts
    }

    /// Every asset in the chosen albums, newest first, one entry per asset.
    /// An asset that sits in several selected albums is reported once, under
    /// the first album it was found in — the same one-folder-per-file shape
    /// the upload's `folder` field expects.
    static func items(albumIds: Set<String>, includeVideos: Bool = true) -> [DeviceItem] {
        guard hasAccess(), !albumIds.isEmpty else { return [] }
        let collections = PHAssetCollection.fetchAssetCollections(
            withLocalIdentifiers: Array(albumIds), options: nil)
        var out: [DeviceItem] = []
        var seen = Set<String>()
        let options = assetFetchOptions(includeVideos: includeVideos)
        collections.enumerateObjects { collection, _, _ in
            let name = collection.localizedTitle ?? "Album"
            PHAsset.fetchAssets(in: collection, options: options).enumerateObjects { asset, _, _ in
                guard !seen.contains(asset.localIdentifier) else { return }
                seen.insert(asset.localIdentifier)
                let info = resourceInfo(for: asset)
                out.append(DeviceItem(
                    id: asset.localIdentifier,
                    name: info.name,
                    size: info.size,
                    albumId: collection.localIdentifier,
                    albumName: name,
                    isVideo: asset.mediaType == .video,
                    dateAdded: (asset.creationDate ?? asset.modificationDate ?? .distantPast).timeIntervalSince1970))
            }
        }
        return out
    }

    static func asset(withId id: String) -> PHAsset? {
        PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
    }

    // MARK: - Resources

    /// The resource to upload: the edited render when the user has edited the
    /// asset, else the original the camera wrote.
    static func uploadResource(for asset: PHAsset) -> PHAssetResource? {
        let resources = PHAssetResource.assetResources(for: asset)
        let preferred: [PHAssetResourceType] = asset.mediaType == .video
            ? [.fullSizeVideo, .video]
            : [.fullSizePhoto, .photo]
        for type in preferred {
            if let hit = resources.first(where: { $0.type == type }) { return hit }
        }
        return resources.first
    }

    /// Display name and byte size. PhotoKit exposes the size only through an
    /// undocumented key, so a miss returns -1 and the caller falls back to the
    /// exact size the hashing pass measures.
    static func resourceInfo(for asset: PHAsset) -> (name: String, size: Int64) {
        guard let resource = uploadResource(for: asset) else {
            return ("item-\(asset.localIdentifier.prefix(8)).jpg", -1)
        }
        let size = (resource.value(forKey: "fileSize") as? NSNumber)?.int64Value ?? -1
        return (resource.originalFilename, size)
    }

    /// Streams an asset's bytes to `sink` in chunks. Used both to hash a file
    /// without holding it in memory and to write the multipart upload body.
    static func readData(for asset: PHAsset, sink: @escaping (Data) -> Void) async throws {
        guard let resource = uploadResource(for: asset) else {
            throw NSError(domain: "Chitra", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "no resource for asset"])
        }
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true      // iCloud-optimised originals
        return try await withCheckedThrowingContinuation { cont in
            PHAssetResourceManager.default().requestData(
                for: resource,
                options: options,
                dataReceivedHandler: sink,
                completionHandler: { error in
                    if let error { cont.resume(throwing: error) } else { cont.resume() }
                })
        }
    }

    /// The server's quick hash plus the exact byte size, in one streaming pass.
    static func contentHash(for asset: PHAsset) async -> (hash: String, size: Int64)? {
        var streaming = ContentHash.Streaming()
        do {
            try await readData(for: asset) { streaming.append($0) }
        } catch {
            return nil
        }
        guard streaming.size > 0 else { return nil }
        return (streaming.finalize(), streaming.size)
    }
}
