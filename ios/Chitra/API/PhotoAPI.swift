import Foundation

enum APIError: LocalizedError {
    case badURL(String)
    case http(Int)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .badURL(let u): return "Bad server URL: \(u)"
        case .http(let code): return "HTTP \(code)"
        case .decoding(let what): return "Bad response (\(what))"
        }
    }

    /// The server refuses writes while the library is mounted read-only.
    var isForbidden: Bool {
        if case .http(403) = self { return true }
        return false
    }
}

/// The JSON API, one method per Retrofit endpoint in `PhotoApi.kt`.
struct PhotoAPI {
    let baseUrl: String
    private let session: URLSession

    init(baseUrl: String, session: URLSession? = nil) {
        self.baseUrl = Urls.base(baseUrl)
        if let session {
            self.session = session
        } else {
            let cfg = URLSessionConfiguration.default
            cfg.timeoutIntervalForRequest = 60
            cfg.waitsForConnectivity = false
            self.session = URLSession(configuration: cfg)
        }
    }

    private static let decoder = JSONDecoder()
    private static let encoder = JSONEncoder()

    // MARK: - Plumbing

    private func url(_ path: String, _ query: [String: String?] = [:]) throws -> URL {
        guard var comps = URLComponents(string: baseUrl + path) else { throw APIError.badURL(baseUrl + path) }
        let items = query.compactMap { key, value -> URLQueryItem? in
            guard let value else { return nil }
            return URLQueryItem(name: key, value: value)
        }.sorted { $0.name < $1.name }
        if !items.isEmpty { comps.queryItems = items }
        guard let u = comps.url else { throw APIError.badURL(baseUrl + path) }
        return u
    }

    private func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw APIError.http(http.statusCode)
        }
        do {
            return try Self.decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(String(describing: T.self))
        }
    }

    private func get<T: Decodable>(_ path: String, _ query: [String: String?] = [:], as type: T.Type) async throws -> T {
        try await send(URLRequest(url: try url(path, query)), as: type)
    }

    private func request(_ method: String, _ path: String, _ query: [String: String?] = [:], body: Data? = nil) throws -> URLRequest {
        var r = URLRequest(url: try url(path, query))
        r.httpMethod = method
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return r
    }

    /// A write whose response body we don't care about beyond "it worked".
    @discardableResult
    private func fire(_ method: String, _ path: String, _ query: [String: String?] = [:], body: Data? = nil) async throws -> [String: JSONValue] {
        try await send(try request(method, path, query, body: body), as: [String: JSONValue].self)
    }

    // MARK: - Reads

    func health() async throws -> Health {
        try await get("api/health", as: Health.self)
    }

    func meta(_ id: String) async throws -> MediaItem {
        try await get("api/media/\(id)", as: MediaItem.self)
    }

    func media(page: Int, perPage: Int = 60, kind: String? = nil, album: String? = nil,
               folder: String? = nil, q: String? = nil, favorites: Int? = nil,
               year: Int? = nil, month: Int? = nil, trashed: Int? = nil,
               archived: Int? = nil, dated: Int? = nil, undated: Int? = nil,
               sort: String? = nil) async throws -> MediaPage {
        try await get("api/media", [
            "page": String(page), "per_page": String(perPage),
            "kind": kind, "album": album, "folder": folder, "q": q,
            "favorites": favorites.map(String.init), "year": year.map(String.init),
            "month": month.map(String.init), "trashed": trashed.map(String.init),
            "archived": archived.map(String.init), "dated": dated.map(String.init),
            "undated": undated.map(String.init), "sort": sort,
        ], as: MediaPage.self)
    }

    func albums() async throws -> [Album] {
        try await get("api/albums", as: [Album].self)
    }

    func userAlbums(mediaId: String? = nil) async throws -> [UserAlbum] {
        try await get("api/user_albums", ["media_id": mediaId], as: [UserAlbum].self)
    }

    func userAlbumMedia(_ id: Int) async throws -> [MediaItem] {
        try await get("api/user_albums/\(id)/media", as: [MediaItem].self)
    }

    func timeline() async throws -> [TimelineBucket] {
        try await get("api/timeline", as: [TimelineBucket].self)
    }

    func memories() async throws -> Memories {
        try await get("api/memories", as: Memories.self)
    }

    func searchSemantic(q: String, topK: Int = 80) async throws -> MediaPage {
        try await get("api/search_semantic", ["q": q, "top_k": String(topK)], as: MediaPage.self)
    }

    func clusters() async throws -> [Cluster] {
        try await get("api/clusters", as: [Cluster].self)
    }

    func clusterMedia(_ id: Int) async throws -> [MediaItem] {
        try await get("api/clusters/\(id)/media", as: [MediaItem].self)
    }

    func facesStatus() async throws -> FacesStatus {
        try await get("api/faces/status", as: FacesStatus.self)
    }

    func persons() async throws -> [Person] {
        try await get("api/persons", as: [Person].self)
    }

    func locations() async throws -> [LocationItem] {
        try await get("api/locations", as: [LocationItem].self)
    }

    // MARK: - Writes

    func rescan() async throws {
        try await fire("POST", "api/rescan")
    }

    func toggleFavorite(_ id: String) async throws -> FavoriteResp {
        try await send(try request("POST", "api/media/\(id)/favorite"), as: FavoriteResp.self)
    }

    func trash(_ id: String) async throws {
        try await fire("POST", "api/media/\(id)/trash")
    }

    func restore(_ id: String) async throws {
        try await fire("POST", "api/media/\(id)/restore")
    }

    func archive(_ id: String) async throws {
        try await fire("POST", "api/media/\(id)/archive")
    }

    /// Returns the server's new `edit_version` so the caller can cache-bust
    /// the (immutably cached) versioned thumb URL and stay in step with what
    /// a refresh would return.
    func rotate(_ id: String, degrees: Int = 90) async throws -> Int? {
        let resp = try await fire("POST", "api/media/\(id)/rotate", ["degrees": String(degrees)])
        return resp["edit_version"]?.intValue
    }

    func share(_ id: String) async throws -> ShareResp {
        try await send(try request("POST", "api/media/\(id)/share"), as: ShareResp.self)
    }

    func edit(_ id: String, params: [String: JSONValue]) async throws {
        try await fire("POST", "api/media/\(id)/edit", body: try Self.encoder.encode(params))
    }

    func createUserAlbum(name: String, mediaIds: [String] = []) async throws -> UserAlbumResp {
        try await send(
            try request("POST", "api/user_albums", body: try Self.encoder.encode(NewAlbumBody(name: name, mediaIds: mediaIds))),
            as: UserAlbumResp.self)
    }

    func addToUserAlbum(_ id: Int, ids: [String]) async throws -> UserAlbumResp {
        try await send(
            try request("POST", "api/user_albums/\(id)/items", body: try Self.encoder.encode(IdsBody(ids: ids))),
            as: UserAlbumResp.self)
    }

    func removeFromUserAlbum(_ id: Int, ids: [String]) async throws -> UserAlbumResp {
        try await send(
            try request("DELETE", "api/user_albums/\(id)/items", body: try Self.encoder.encode(IdsBody(ids: ids))),
            as: UserAlbumResp.self)
    }

    func deleteUserAlbum(_ id: Int) async throws {
        _ = try await send(try request("DELETE", "api/user_albums/\(id)"), as: OkResp.self)
    }

    func shareUserAlbum(_ id: Int) async throws -> AlbumShareResp {
        try await send(try request("POST", "api/user_albums/\(id)/share"), as: AlbumShareResp.self)
    }
}

/// Just enough of a dynamic JSON value to read `edit_version` back out of a
/// write response and to send the editor's mixed number/bool parameters.
enum JSONValue: Codable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null
    case array([JSONValue])
    case object([String: JSONValue])

    var intValue: Int? {
        if case .number(let d) = self { return Int(d) }
        return nil
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let d = try? c.decode(Double.self) { self = .number(d) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else { self = .null }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let d): try c.encode(d)
        case .bool(let b): try c.encode(b)
        case .null: try c.encodeNil()
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }
}
