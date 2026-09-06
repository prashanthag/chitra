import Foundation
import Photos
import UniformTypeIdentifiers

/**
 Talks to /api/upload and /api/upload/check. One request per file: a lost
 Wi-Fi packet mid-batch would void a whole batch (the server only saves once
 the entire multipart body has arrived), and per-file requests give real
 progress.

 Each body is staged as a file on disk and uploaded with `uploadTask(fromFile:)`,
 so a 4 GB video never lands in memory and Content-Length is always the real
 length — the Android client's stale-size retry has no counterpart here.
 */
enum Uploader {

    struct FileResult {
        let name: String
        let ok: Bool
        var duplicate: Bool = false
        var serverId: String? = nil
        var error: String? = nil
    }

    struct CheckFile: Encodable {
        let name: String
        let size: Int64
        let hash: String?
    }

    private struct CheckRequest: Encodable { let files: [CheckFile] }

    private struct CheckResponse: Decodable {
        let ok: Bool
        let exists: [Bool]
    }

    private struct UploadItem: Decodable {
        let id: String?
        let name: String
        let indexed: Bool
        let duplicate: Bool
        let reason: String?

        enum CodingKeys: String, CodingKey { case id, name, indexed, duplicate, reason }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = c.opt(.id)
            name = c.get(.name, "")
            indexed = c.get(.indexed, false)
            duplicate = c.get(.duplicate, false)
            reason = c.opt(.reason)
        }
    }

    private struct UploadResponse: Decodable {
        let ok: Bool
        let items: [UploadItem]

        enum CodingKeys: String, CodingKey { case ok, items }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            ok = c.get(.ok, false)
            items = c.get(.items, [])
        }
    }

    /// Where the phone says a file came from, so the server can file uploads
    /// by device and album and use the phone as the camera for EXIF-less files.
    struct Source {
        var folder: String?
        var device: String?
        var deviceMake: String? = DeviceInfo.make()
    }

    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 600
        cfg.timeoutIntervalForResource = 3600
        cfg.allowsCellularAccess = true
        return URLSession(configuration: cfg)
    }()

    static func base(_ serverUrl: String) -> String { Urls.base(serverUrl) }

    // MARK: - Pre-flight

    /// Which of these files does the server already have? With a content hash
    /// the match is by bytes regardless of name; without one the server falls
    /// back to name + size.
    static func check(serverUrl: String, files: [CheckFile]) async -> [Bool]? {
        guard !files.isEmpty else { return [] }
        guard let url = URL(string: base(serverUrl) + "api/upload/check") else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(CheckRequest(files: files))
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return try JSONDecoder().decode(CheckResponse.self, from: data).exists
        } catch {
            return nil
        }
    }

    // MARK: - Upload

    /// Upload one library asset. Never throws; the result says what happened.
    static func upload(asset: PHAsset, name: String, serverUrl: String, source: Source) async -> FileResult {
        let staged: URL
        do {
            staged = try await stageMultipart(name: name, mime: mimeType(for: name), source: source) { sink in
                try await DeviceMedia.readData(for: asset, sink: sink)
            }
        } catch {
            return FileResult(name: name, ok: false, error: error.localizedDescription)
        }
        defer { try? FileManager.default.removeItem(at: staged) }
        return await send(staged, name: name, serverUrl: serverUrl)
    }

    /// Upload a file already on disk (the manual picker's fallback path for
    /// items PhotoKit won't hand us as assets).
    static func upload(fileAt fileURL: URL, name: String, serverUrl: String, source: Source) async -> FileResult {
        let staged: URL
        do {
            staged = try await stageMultipart(name: name, mime: mimeType(for: name), source: source) { sink in
                let handle = try FileHandle(forReadingFrom: fileURL)
                defer { try? handle.close() }
                while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
                    sink(chunk)
                }
            }
        } catch {
            return FileResult(name: name, ok: false, error: error.localizedDescription)
        }
        defer { try? FileManager.default.removeItem(at: staged) }
        return await send(staged, name: name, serverUrl: serverUrl)
    }

    private static let boundary = "----ChitraFormBoundary7MA4YWxkTrZu0gW"

    /// Writes the whole multipart body to a temp file, streaming the payload
    /// through `writePayload` so the bytes are never all in memory at once.
    private static func stageMultipart(
        name: String,
        mime: String,
        source: Source,
        writePayload: (@escaping (Data) -> Void) async throws -> Void
    ) async throws -> URL {
        let staged = FileManager.default.temporaryDirectory
            .appendingPathComponent("upload-\(UUID().uuidString).multipart")
        FileManager.default.createFile(atPath: staged.path, contents: nil)
        let handle = try FileHandle(forWritingTo: staged)

        func writeString(_ s: String) { handle.write(Data(s.utf8)) }
        func field(_ key: String, _ value: String) {
            writeString("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(key)\"\r\n\r\n\(value)\r\n")
        }

        if let device = source.device { field("device", device) }
        if let make = source.deviceMake { field("device_make", make) }
        if let folder = source.folder { field("folder", folder) }
        writeString("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(name)\"\r\n")
        writeString("Content-Type: \(mime)\r\n\r\n")

        do {
            try await writePayload { chunk in handle.write(chunk) }
        } catch {
            try? handle.close()
            try? FileManager.default.removeItem(at: staged)
            throw error
        }
        writeString("\r\n--\(boundary)--\r\n")
        try handle.close()
        return staged
    }

    private static func send(_ body: URL, name: String, serverUrl: String) async -> FileResult {
        guard let url = URL(string: base(serverUrl) + "api/upload") else {
            return FileResult(name: name, ok: false, error: "bad server url")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        do {
            let (data, response) = try await session.upload(for: request, fromFile: body)
            guard let http = response as? HTTPURLResponse else {
                return FileResult(name: name, ok: false, error: "no response")
            }
            guard (200..<300).contains(http.statusCode) else {
                return FileResult(name: name, ok: false, error: "HTTP \(http.statusCode)")
            }
            let parsed = try JSONDecoder().decode(UploadResponse.self, from: data)
            guard let item = parsed.items.first else {
                return FileResult(name: name, ok: false, error: "empty response")
            }
            guard item.indexed else {
                return FileResult(name: name, ok: false, error: item.reason ?? "not indexed")
            }
            return FileResult(name: item.name.isEmpty ? name : item.name, ok: true,
                              duplicate: item.duplicate, serverId: item.id)
        } catch {
            return FileResult(name: name, ok: false, error: error.localizedDescription)
        }
    }

    static func mimeType(for filename: String) -> String {
        let ext = (filename as NSString).pathExtension
        if let type = UTType(filenameExtension: ext), let mime = type.preferredMIMEType {
            return mime
        }
        return "application/octet-stream"
    }
}
