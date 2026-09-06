import Foundation

/// Missing keys and wrong types both fall back to the default, mirroring the
/// Android client's `ignoreUnknownKeys` + `coerceInputValues` JSON setup: an
/// older server that doesn't send a column must not fail the whole page.
extension KeyedDecodingContainer {
    func get<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback
    }

    func opt<T: Decodable>(_ key: Key) -> T? {
        (try? decodeIfPresent(T.self, forKey: key)) ?? nil
    }
}

struct MediaItem: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var kind: String            // "photo" or "video"
    var ext: String
    var mime: String?
    var size: Int64?
    var takenAt: Double?
    var width: Int?
    var height: Int?
    var album: String?
    var favorite: Int = 0
    var trashedAt: Double?
    var archived: Int = 0
    var lat: Double?
    var lng: Double?
    var place: String?
    var cameraMake: String?
    var cameraModel: String?
    /// Bumped by the server on edit/rotate; goes into the thumb URL as ?v= for cache-busting.
    var editVersion: Int = 0
    /// When the item entered the library (upload time), epoch seconds.
    var addedAt: Double?
    /// Which phone and folder a backup upload came from (detail endpoint).
    var sourceDevice: String?
    var sourceFolder: String?
    /// Detail endpoint only: preformatted exposure settings.
    var exposure: [String: String]?
    /// Detail endpoint only, videos: duration, codec, frame_rate, bitrate.
    var video: [String: String]?

    var isVideo: Bool { kind == "video" }
    var isFavorite: Bool { favorite == 1 }
    var isTrashed: Bool { trashedAt != nil }

    enum CodingKeys: String, CodingKey {
        case id, name, kind, ext, mime, size, width, height, album, favorite, archived
        case lat, lng, place, exposure, video
        case takenAt = "taken_at"
        case trashedAt = "trashed_at"
        case cameraMake = "camera_make"
        case cameraModel = "camera_model"
        case editVersion = "edit_version"
        case addedAt = "added_at"
        case sourceDevice = "source_device"
        case sourceFolder = "source_folder"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = c.get(.name, "")
        kind = c.get(.kind, "photo")
        ext = c.get(.ext, "")
        mime = c.opt(.mime)
        size = c.opt(.size)
        takenAt = c.opt(.takenAt)
        width = c.opt(.width)
        height = c.opt(.height)
        album = c.opt(.album)
        favorite = c.get(.favorite, 0)
        trashedAt = c.opt(.trashedAt)
        archived = c.get(.archived, 0)
        lat = c.opt(.lat)
        lng = c.opt(.lng)
        place = c.opt(.place)
        cameraMake = c.opt(.cameraMake)
        cameraModel = c.opt(.cameraModel)
        editVersion = c.get(.editVersion, 0)
        addedAt = c.opt(.addedAt)
        sourceDevice = c.opt(.sourceDevice)
        sourceFolder = c.opt(.sourceFolder)
        exposure = c.opt(.exposure)
        video = c.opt(.video)
    }

    /// A stand-in record built from an id — for a map marker that is not in
    /// the loaded feed, and for tests. Everything else is decoded.
    init(id: String, name: String, kind: String = "photo", ext: String = ".jpg",
         takenAt: Double? = nil, addedAt: Double? = nil, favorite: Int = 0,
         archived: Int = 0, trashedAt: Double? = nil, editVersion: Int = 0) {
        self.id = id; self.name = name; self.kind = kind; self.ext = ext
        self.takenAt = takenAt; self.addedAt = addedAt; self.favorite = favorite
        self.archived = archived; self.trashedAt = trashedAt; self.editVersion = editVersion
    }
}

struct MediaPage: Codable {
    var page: Int = 1
    var perPage: Int = 0
    var total: Int = 0
    var items: [MediaItem] = []
    var q: String?

    enum CodingKeys: String, CodingKey {
        case page, total, items, q
        case perPage = "per_page"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        page = c.get(.page, 1)
        perPage = c.get(.perPage, 0)
        total = c.get(.total, 0)
        items = c.get(.items, [])
        q = c.opt(.q)
    }
}

struct TimelineBucket: Codable, Hashable {
    var y: String
    var m: String
    var n: Int
}

struct FavoriteResp: Codable {
    var ok: Bool
    var favorite: Bool
}

struct MemoryGroup: Codable, Hashable, Identifiable {
    var year: String
    var title: String?
    var items: [MediaItem] = []

    var id: String { year }

    enum CodingKeys: String, CodingKey { case year, title, items }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        year = c.get(.year, "")
        title = c.opt(.title)
        items = c.get(.items, [])
    }
}

struct Memories: Codable {
    var monthDay: String = ""
    var groups: [MemoryGroup] = []

    enum CodingKeys: String, CodingKey {
        case groups
        case monthDay = "month_day"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        monthDay = c.get(.monthDay, "")
        groups = c.get(.groups, [])
    }
}

struct LocationItem: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var kind: String
    var lat: Double
    var lng: Double
    var takenAt: Double?
    var album: String?

    enum CodingKeys: String, CodingKey {
        case id, name, kind, lat, lng, album
        case takenAt = "taken_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = c.get(.name, "")
        kind = c.get(.kind, "photo")
        lat = c.get(.lat, 0)
        lng = c.get(.lng, 0)
        takenAt = c.opt(.takenAt)
        album = c.opt(.album)
    }
}

/// A folder album derived from the library root (read-only).
struct Album: Codable, Identifiable, Hashable {
    var album: String
    var count: Int = 0
    /// Phone folder inside the uploads album (Camera, WhatsApp Images...), from the backup app.
    var folder: String?
    var device: String?
    var cover: String?

    var label: String { folder ?? album }
    var key: String { folder != nil ? "\(album)/\(folder!)" : album }
    var id: String { key }

    enum CodingKeys: String, CodingKey { case album, count, folder, device, cover }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        album = c.get(.album, "")
        count = c.get(.count, 0)
        folder = c.opt(.folder)
        device = c.opt(.device)
        cover = c.opt(.cover)
    }
}

/// A manual album: a named set of media ids, independent of folders.
struct UserAlbum: Codable, Identifiable, Hashable {
    var id: Int
    var name: String
    var count: Int = 0
    var cover: String?
    var shareToken: String?
    /// Only when listed with ?media_id=: whether that item is in the album.
    var contains: Bool?

    enum CodingKeys: String, CodingKey {
        case id, name, count, cover, contains
        case shareToken = "share_token"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        name = c.get(.name, "")
        count = c.get(.count, 0)
        cover = c.opt(.cover)
        shareToken = c.opt(.shareToken)
        contains = c.opt(.contains)
    }
}

struct UserAlbumResp: Codable {
    var ok: Bool = false
    var album: UserAlbum
    var added: Int = 0
    var removed: Int = 0

    enum CodingKeys: String, CodingKey { case ok, album, added, removed }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = c.get(.ok, false)
        album = try c.decode(UserAlbum.self, forKey: .album)
        added = c.get(.added, 0)
        removed = c.get(.removed, 0)
    }
}

struct AlbumShareResp: Codable {
    var ok: Bool
    var token: String
    var url: String
}

struct ShareResp: Codable {
    var ok: Bool
    var token: String
}

struct OkResp: Codable {
    var ok: Bool
}

struct Cluster: Codable, Identifiable, Hashable {
    var id: Int
    var name: String?
    var count: Int = 0
    var repFaceId: Int?
    var repMediaId: String?

    enum CodingKeys: String, CodingKey {
        case id, name, count
        case repFaceId = "rep_face_id"
        case repMediaId = "rep_media_id"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        name = c.opt(.name)
        count = c.get(.count, 0)
        repFaceId = c.opt(.repFaceId)
        repMediaId = c.opt(.repMediaId)
    }
}

struct FacesStatus: Codable {
    var processed: Int = 0
    var totalPhotos: Int = 0
    var faces: Int = 0
    var clusters: Int = 0

    enum CodingKeys: String, CodingKey {
        case processed, faces, clusters
        case totalPhotos = "total_photos"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        processed = c.get(.processed, 0)
        totalPhotos = c.get(.totalPhotos, 0)
        faces = c.get(.faces, 0)
        clusters = c.get(.clusters, 0)
    }
}

struct Person: Codable, Identifiable, Hashable {
    var id: Int
    var name: String
    var count: Int = 0
}

struct Health: Codable {
    var ok: Bool = false
    var itemsIndexed: Int = 0
    var heicSupported: Bool = false
    var mediaRoot: String = ""

    enum CodingKeys: String, CodingKey {
        case ok
        case itemsIndexed = "items_indexed"
        case heicSupported = "heic_supported"
        case mediaRoot = "media_root"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = c.get(.ok, false)
        itemsIndexed = c.get(.itemsIndexed, 0)
        heicSupported = c.get(.heicSupported, false)
        mediaRoot = c.get(.mediaRoot, "")
    }
}

// Request bodies.

struct IdsBody: Encodable {
    var ids: [String]
}

struct NewAlbumBody: Encodable {
    var name: String
    var mediaIds: [String] = []

    enum CodingKeys: String, CodingKey {
        case name
        case mediaIds = "media_ids"
    }
}
