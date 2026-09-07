import Combine
import Foundation

/// 照合ログの1件。カメラ・Bluetoothどちらの入力でも、最終判定・不受理・セッション開始終了を
/// 1イベント1行で残す。読み取ったQR / Code 128の生ペイロードを含むため端末内にだけ保存し、
/// 持ち出しは設定画面からの共有操作に限る。
/// スキーマ（項目名と値）はAndroid版と共通。変更するときは必ず両OSを揃えること。
struct ScanLogEvent: Codable, Equatable {
    /// 発生時刻。ISO 8601（ミリ秒・UTC）で書き出す。
    var at: Date
    /// 記録時のアクティブセッション。セッション外なら `null`。
    var session: UUID?
    /// `camera` / `bluetooth`。
    var source: String
    /// `qr` / `barcode` / `result` / `none`。
    var step: String
    /// `session_start` / `session_end` / `qr_accepted` / `barcode_candidate` /
    /// `barcode_accepted` / `match` / `mismatch` / `duplicate` / `rejected`。
    var event: String
    /// `rejected` の理由（snake_case）。それ以外は `null`。
    var reason: String?
    /// 仕向地の識別子（`Destination.rawValue`）。未確定なら `null`。
    var destination: String?
    /// 読み取った納品書兼現品票QRの全文。
    var qr: String?
    /// 読み取った現品票Code 128の全文。
    var barcode: String?
    /// 履歴へ残す品番表記。
    var code: String?
    /// 一致時の「本セッションでN箱目」。
    var boxNumber: Int?
    /// 画面に出した文言。
    var message: String?

    init(
        at: Date,
        session: UUID?,
        source: String,
        step: String,
        event: String,
        reason: String? = nil,
        destination: String? = nil,
        qr: String? = nil,
        barcode: String? = nil,
        code: String? = nil,
        boxNumber: Int? = nil,
        message: String? = nil
    ) {
        self.at = at
        self.session = session
        self.source = source
        self.step = step
        self.event = event
        self.reason = reason
        self.destination = destination
        self.qr = qr
        self.barcode = barcode
        self.code = code
        self.boxNumber = boxNumber
        self.message = message
    }

    enum CodingKeys: String, CodingKey {
        case at
        case session
        case source
        case step
        case event
        case reason
        case destination
        case qr
        case barcode
        case code
        case boxNumber
        case message
    }

    /// 空欄はキーごと省略せず `null` として書く（履歴JSONと同じ規約）。
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(at, forKey: .at)
        try container.encodeScanLogValueOrNull(session?.uuidString, forKey: .session)
        try container.encode(source, forKey: .source)
        try container.encode(step, forKey: .step)
        try container.encode(event, forKey: .event)
        try container.encodeScanLogValueOrNull(reason, forKey: .reason)
        try container.encodeScanLogValueOrNull(destination, forKey: .destination)
        try container.encodeScanLogValueOrNull(qr, forKey: .qr)
        try container.encodeScanLogValueOrNull(barcode, forKey: .barcode)
        try container.encodeScanLogValueOrNull(code, forKey: .code)
        try container.encodeScanLogValueOrNull(boxNumber, forKey: .boxNumber)
        try container.encodeScanLogValueOrNull(message, forKey: .message)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        at = try container.decode(Date.self, forKey: .at)
        session = try container.decodeIfPresent(String.self, forKey: .session)
            .flatMap(UUID.init(uuidString:))
        source = try container.decode(String.self, forKey: .source)
        step = try container.decode(String.self, forKey: .step)
        event = try container.decode(String.self, forKey: .event)
        reason = try container.decodeIfPresent(String.self, forKey: .reason)
        destination = try container.decodeIfPresent(String.self, forKey: .destination)
        qr = try container.decodeIfPresent(String.self, forKey: .qr)
        barcode = try container.decodeIfPresent(String.self, forKey: .barcode)
        code = try container.decodeIfPresent(String.self, forKey: .code)
        boxNumber = try container.decodeIfPresent(Int.self, forKey: .boxNumber)
        message = try container.decodeIfPresent(String.self, forKey: .message)
    }
}

private extension KeyedEncodingContainer {
    /// `encodeIfPresent` と違い、空欄でも項目が必ず並ぶ。
    mutating func encodeScanLogValueOrNull<T: Encodable>(_ value: T?, forKey key: Key) throws {
        if let value {
            try encode(value, forKey: key)
        } else {
            try encodeNil(forKey: key)
        }
    }
}

/// 照合ログをJSON Lines（1行1イベント）で端末内に追記保存する。
///
/// Bluetooth診断ログ（`BluetoothScannerService.trace`）は読取値を一切含まない不変条件があるため、
/// 生ペイロードを持つ照合ログはそれとは別のファイルとして扱う。
@MainActor
final class ScanLogStore: ObservableObject {
    /// 端末に残す件数の上限。
    static let limit = 5000
    /// この件数を超えたときにだけ書き直して `limit` 件へ切り詰める（書き直しの頻度を抑える）。
    static let trimThreshold = 5500
    /// 出力スキーマの版。互換性のない変更を入れるときだけ上げる。
    static let schemaVersion = 1
    /// 書き出し元のプラットフォーム識別子。
    static let platform = "ios"
    /// UIテストで保存済みのログを消してから起動するための引数。
    static let resetArgument = "-resetScanLog"

    @Published private(set) var eventCount = 0
    @Published private(set) var storageError: String?

    private let storageURL: URL
    private let encoder: JSONEncoder

    init(
        storageURL: URL? = nil,
        arguments: [String] = ProcessInfo.processInfo.arguments
    ) {
        self.storageURL = storageURL ?? Self.defaultStorageURL
        encoder = JSONEncoder()
        // 1行に収めるため整形はせず、日付中の "/" もエスケープしない。
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(ScanLogStore.timestampFormatter.string(from: date))
        }

        if arguments.contains(Self.resetArgument) {
            try? FileManager.default.removeItem(at: self.storageURL)
            eventCount = 0
        } else {
            eventCount = Self.storedLines(at: self.storageURL).count
        }
    }

    // MARK: - 記録

    func record(_ event: ScanLogEvent) {
        do {
            let line = try encodedLine(event)
            try append(line)
            eventCount += 1
            if eventCount > Self.trimThreshold {
                try trimToLimit()
            }
            storageError = nil
        } catch {
            storageError = AppLocalization.string("照合ログを保存できませんでした。端末の空き容量を確認してください。")
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: storageURL)
        eventCount = 0
        storageError = nil
    }

    // MARK: - 読み出し

    /// 保存済みの行（改行なし）を古い順に返す。
    func lines() -> [String] {
        Self.storedLines(at: storageURL)
    }

    /// 保存済みの全イベントを古い順に復号する。
    func events() throws -> [ScanLogEvent] {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            guard let date = ScanLogStore.timestampFormatter.date(from: text) else {
                throw DecodingError.dataCorrupted(
                    DecodingError.Context(
                        codingPath: decoder.codingPath,
                        debugDescription: "Unsupported timestamp: \(text)"
                    )
                )
            }
            return date
        }
        return try lines().map { try decoder.decode(ScanLogEvent.self, from: Data($0.utf8)) }
    }

    // MARK: - 書き出し

    /// ヘッダ行 + 保存済みの全行。共有・保存はこの文字列をそのまま使う。
    func exportText(
        exportedAt: Date = Date(),
        appVersion: String = HistoryExporter.bundleAppVersion
    ) -> String {
        let stored = lines()
        let header = Self.headerLine(
            exportedAt: exportedAt,
            appVersion: appVersion,
            eventCount: stored.count
        )
        return ([header] + stored).joined(separator: "\n") + "\n"
    }

    /// 共有シート用にJSONLを一時ファイルへ書き出してURLを返す。
    func writeTemporaryExport(
        exportedAt: Date = Date(),
        appVersion: String = HistoryExporter.bundleAppVersion
    ) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(Self.fileName(exportedAt: exportedAt))
        let text = exportText(exportedAt: exportedAt, appVersion: appVersion)
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }

    /// `codematch-scan-log-20260907-1023.jsonl`。共有先で見分けるための名前なので端末のローカル時刻で作る。
    static func fileName(exportedAt: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return "codematch-scan-log-\(formatter.string(from: exportedAt)).jsonl"
    }

    /// ヘッダ行。項目の並びをAndroid版と揃えるため、符号化器に任せず自分で組み立てる。
    static func headerLine(exportedAt: Date, appVersion: String, eventCount: Int) -> String {
        let version = jsonEscaped(appVersion)
        let timestamp = jsonEscaped(timestampFormatter.string(from: exportedAt))
        return "{\"type\":\"header\",\"schemaVersion\":\(schemaVersion),\"platform\":\"\(platform)\","
            + "\"appVersion\":\"\(version)\",\"exportedAt\":\"\(timestamp)\","
            + "\"eventCount\":\(eventCount),\"limit\":\(limit)}"
    }

    // MARK: - 内部

    private func encodedLine(_ event: ScanLogEvent) throws -> String {
        let data = try encoder.encode(event)
        guard let line = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        // JSONEncoderは改行を含まないが、1行1イベントの前提を壊さないよう保険をかける。
        return line.replacingOccurrences(of: "\n", with: "")
    }

    private func append(_ line: String) throws {
        if !FileManager.default.fileExists(atPath: storageURL.path) {
            try createStorageFile()
        }
        let handle = try FileHandle(forWritingTo: storageURL)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((line + "\n").utf8))
    }

    private func createStorageFile() throws {
        try prepareDirectory()
        try Data().write(to: storageURL, options: [.atomic, .completeFileProtection])
    }

    /// 保存先ディレクトリを用意し、バックアップ対象から外す（履歴と同じ手順）。
    private func prepareDirectory() throws {
        let directory = storageURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        var protectedDirectory = directory
        try? protectedDirectory.setResourceValues(resourceValues)
    }

    /// 新しい方から `limit` 件だけ残して書き直す。
    private func trimToLimit() throws {
        let kept = Self.storedLines(at: storageURL).suffix(Self.limit)
        let text = kept.joined(separator: "\n") + (kept.isEmpty ? "" : "\n")
        try prepareDirectory()
        try Data(text.utf8).write(to: storageURL, options: [.atomic, .completeFileProtection])
        eventCount = kept.count
    }

    private static func storedLines(at url: URL) -> [String] {
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8)
        else { return [] }
        return text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
    }

    private static func jsonEscaped(_ value: String) -> String {
        var escaped = ""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"": escaped += "\\\""
            case "\\": escaped += "\\\\"
            case "\n": escaped += "\\n"
            case "\r": escaped += "\\r"
            case "\t": escaped += "\\t"
            default:
                if scalar.value < 0x20 {
                    escaped += String(format: "\\u%04x", scalar.value)
                } else {
                    escaped.unicodeScalars.append(scalar)
                }
            }
        }
        return escaped
    }

    private static let timestampFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()

    private static var defaultStorageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CodeMatch", isDirectory: true)
            .appendingPathComponent("scan-log.jsonl")
    }
}
