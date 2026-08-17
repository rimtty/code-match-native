import Foundation

struct MatchHistoryEntry: Identifiable, Codable, Equatable {
    let id: UUID
    let code: String
    let matchedAt: Date
    /// 照合時のQRコード全文。旧バージョンで記録した履歴ではnil。
    var qrPayload: String?
    /// 照合時のCode 128全文。旧バージョンで記録した履歴ではnil。
    var barcodePayload: String?

    init(
        id: UUID = UUID(),
        code: String,
        matchedAt: Date = Date(),
        qrPayload: String? = nil,
        barcodePayload: String? = nil
    ) {
        self.id = id
        self.code = code
        self.matchedAt = matchedAt
        self.qrPayload = qrPayload
        self.barcodePayload = barcodePayload
    }
}

struct MatchSession: Identifiable, Codable, Equatable {
    let id: UUID
    let startedAt: Date
    var endedAt: Date?
    var entries: [MatchHistoryEntry]
    /// 任意のセッション名。旧バージョンの履歴や未入力ではnil。
    var name: String?

    init(
        id: UUID = UUID(),
        startedAt: Date = Date(),
        endedAt: Date? = nil,
        entries: [MatchHistoryEntry] = [],
        name: String? = nil
    ) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.entries = entries
        self.name = name
    }

    var isActive: Bool { endedAt == nil }
    var matchedCount: Int { entries.count }

    /// 表示用のセッション名。未設定なら空文字。
    var displayName: String { name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }

    /// この品番がすでにこのセッションで照合済みかどうか。
    func hasMatch(code: String) -> Bool {
        entries.contains { $0.code == code }
    }
}

/// 履歴表示用の曜日付き日時フォーマッタ (例: 2026/08/17(日) 21:35)
enum JPDate {
    private static let dateTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ja_JP")
        formatter.dateFormat = "yyyy/MM/dd(E) HH:mm"
        return formatter
    }()

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ja_JP")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    static func dateTime(_ date: Date) -> String {
        dateTimeFormatter.string(from: date)
    }

    static func time(_ date: Date) -> String {
        timeFormatter.string(from: date)
    }
}
