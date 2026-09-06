import Foundation

/// 照合履歴を1つのJSONファイルへ書き出す。
///
/// 現場で採取したQR / Code 128の全文をそのまま端末外へ持ち出して解析するための出力なので、
/// ペイロードは一切正規化しない（末尾の空白まで含めて記録どおり）。
/// 保存形式（`MatchSession`）とファイル形式を切り離すため、書き出し専用のDTOを介して符号化する。
/// スキーマはAndroid版と共通。変更するときは必ず両OSを揃えること。
enum HistoryExporter {
    /// 出力スキーマの版。互換性のない変更を入れるときだけ上げる。
    static let schemaVersion = 1
    /// 書き出し元のプラットフォーム識別子。
    static let platform = "ios"

    /// `CFBundleShortVersionString (CFBundleVersion)` 形式のアプリ版数。
    static var bundleAppVersion: String {
        let shortVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        return "\(shortVersion ?? "-") (\(build ?? "-"))"
    }

    /// 全セッション（照合中・終了済みの両方）をJSONへ符号化する。
    /// セッションは保存順（新しい順）、記録は照合順のまま並べる。
    static func jsonData(
        sessions: [MatchSession],
        exportedAt: Date = Date(),
        appVersion: String = HistoryExporter.bundleAppVersion
    ) throws -> Data {
        let file = ExportFile(
            schemaVersion: schemaVersion,
            platform: platform,
            appVersion: appVersion,
            exportedAt: exportedAt,
            sessions: sessions.map(ExportSession.init(session:))
        )

        let encoder = JSONEncoder()
        // 人が読める形にしつつ、日付中の "/" をエスケープしないでおく。
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(file)
    }

    /// 共有シート用にJSONを一時ファイルへ書き出してURLを返す。
    static func writeTemporaryJSON(
        sessions: [MatchSession],
        exportedAt: Date = Date(),
        appVersion: String = HistoryExporter.bundleAppVersion
    ) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(fileName(exportedAt: exportedAt))
        try jsonData(sessions: sessions, exportedAt: exportedAt, appVersion: appVersion)
            .write(to: url, options: .atomic)
        return url
    }

    /// `codematch-history-20260907-1023.json`。共有先で見分けるための名前なので端末のローカル時刻で作る。
    static func fileName(exportedAt: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return "codematch-history-\(formatter.string(from: exportedAt)).json"
    }
}

// MARK: - 書き出し専用DTO

/// 出力ファイル全体。
private struct ExportFile: Encodable {
    let schemaVersion: Int
    let platform: String
    let appVersion: String
    let exportedAt: Date
    let sessions: [ExportSession]
}

/// 1セッション分。空欄は省略せず `null` として出す。
private struct ExportSession: Encodable {
    let id: String
    let name: String?
    let destination: String?
    let startedAt: Date
    let endedAt: Date?
    let entries: [ExportEntry]

    init(session: MatchSession) {
        id = session.id.uuidString
        name = session.name
        destination = session.resolvedDestination?.rawValue
        startedAt = session.startedAt
        endedAt = session.endedAt
        entries = session.entries.map(ExportEntry.init(entry:))
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case destination
        case startedAt
        case endedAt
        case entries
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encodeOrNull(name, forKey: .name)
        try container.encodeOrNull(destination, forKey: .destination)
        try container.encode(startedAt, forKey: .startedAt)
        try container.encodeOrNull(endedAt, forKey: .endedAt)
        try container.encode(entries, forKey: .entries)
    }
}

/// 1箱分の照合記録。QR / Code 128の全文は記録どおりに出す（前後の空白も削らない）。
private struct ExportEntry: Encodable {
    let id: String
    let code: String
    let matchedAt: Date
    let qrPayload: String?
    let barcodePayload: String?

    init(entry: MatchHistoryEntry) {
        id = entry.id.uuidString
        code = entry.code
        matchedAt = entry.matchedAt
        qrPayload = entry.qrPayload
        barcodePayload = entry.barcodePayload
    }

    enum CodingKeys: String, CodingKey {
        case id
        case code
        case matchedAt
        case qrPayload
        case barcodePayload
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(code, forKey: .code)
        try container.encode(matchedAt, forKey: .matchedAt)
        try container.encodeOrNull(qrPayload, forKey: .qrPayload)
        try container.encodeOrNull(barcodePayload, forKey: .barcodePayload)
    }
}

private extension KeyedEncodingContainer {
    /// 空欄をキーごと省略せず `null` として書く。`encodeIfPresent` と違い項目が必ず並ぶ。
    mutating func encodeOrNull<T: Encodable>(_ value: T?, forKey key: Key) throws {
        if let value {
            try encode(value, forKey: key)
        } else {
            try encodeNil(forKey: key)
        }
    }
}
