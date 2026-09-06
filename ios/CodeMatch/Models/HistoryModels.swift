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

extension MatchHistoryEntry {
    /// 澤井製作所の納品書兼現品票QR(66桁)として解析した結果。
    /// 別の仕向地やQR全文を持たない旧履歴ではnil。
    var kanbanRecord: KanbanQRRecord? {
        guard let qrPayload, Destination.detect(qrPayload: qrPayload) == .sawai else { return nil }
        return KanbanQRRecord.parse(qrPayload)
    }

    /// モルテンの納品書QR(61桁)として解析した結果。
    /// 別の仕向地やQR全文を持たない旧履歴ではnil。
    var moltenRecord: MoltenQRRecord? {
        guard let qrPayload, Destination.detect(qrPayload: qrPayload) == .molten else { return nil }
        return MoltenQRRecord.parse(qrPayload)
    }

    /// この記録がどの箱を検査したかを表す箱固有キー。仕向地ごとの作り方は `BoxIdentity` に従う。
    /// QR全文を持たない旧履歴や、モルテンでCode 128全文を持たない記録ではnil。
    var boxIdentity: String? {
        qrPayload.flatMap { BoxIdentity.make(qrPayload: $0, barcodePayload: barcodePayload) }
    }
}

struct MatchSession: Identifiable, Codable, Equatable {
    let id: UUID
    let startedAt: Date
    var endedAt: Date?
    var entries: [MatchHistoryEntry]
    /// 任意のセッション名。旧バージョンの履歴や未入力ではnil。
    var name: String?
    /// このセッションの仕向地。最初にQRを受理／一致を記録したときに確定し、以後は上書きしない。
    /// 旧バージョンの履歴では nil。
    var destination: Destination?

    init(
        id: UUID = UUID(),
        startedAt: Date = Date(),
        endedAt: Date? = nil,
        entries: [MatchHistoryEntry] = [],
        name: String? = nil,
        destination: Destination? = nil
    ) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.entries = entries
        self.name = name
        self.destination = destination
    }

    enum CodingKeys: String, CodingKey {
        case id
        case startedAt
        case endedAt
        case entries
        case name
        case destination
    }

    /// 保存済みの履歴は復号に失敗すると全件破棄されるため、後から増えた項目は寛容に読む。
    /// 既存項目は自動生成の復号と同じ扱いのまま、仕向地だけは未知の値でもnilに倒す。
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        startedAt = try container.decode(Date.self, forKey: .startedAt)
        endedAt = try container.decodeIfPresent(Date.self, forKey: .endedAt)
        entries = try container.decode([MatchHistoryEntry].self, forKey: .entries)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        destination = try? container.decodeIfPresent(Destination.self, forKey: .destination)
    }

    var isActive: Bool { endedAt == nil }
    var matchedCount: Int { entries.count }

    /// 表示用のセッション名。未設定なら空文字。
    var displayName: String { name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }

    /// 表示に使う仕向地。仕向地を記録していない旧履歴では最初の記録のQRから判定する。
    var resolvedDestination: Destination? {
        destination ?? entries.first?.qrPayload.flatMap(Destination.detect(qrPayload:))
    }

    /// この品番がこのセッションで照合された回数。1回の照合を1箱として数える。
    func matchCount(code: String) -> Int {
        entries.filter { $0.code == code }.count
    }

    /// モルテンの納品番号ごとの検査済み箱数と累計数量。
    /// 1箱1レコードなので、箱数は同じ納品番号の記録数、累計数量は各記録の収容数の合計。
    func deliverySummary(deliveryNumber: String) -> DeliveryBoxSummary {
        let key = DeliveryBoxSummary.normalized(deliveryNumber)
        let records = entries.compactMap(\.moltenRecord).filter { $0.deliveryNumber == key }
        return DeliveryBoxSummary(
            deliveryNumber: key,
            boxCount: records.count,
            totalQuantity: records.reduce(0) { $0 + $1.packQuantity }
        )
    }

    /// このセッションに含まれるモルテンの納品番号の種類数。
    var deliveryNumberCount: Int {
        Set(entries.compactMap { $0.moltenRecord?.deliveryNumber }).count
    }

    /// 同一品番の照合をまとめたグループ。最初に照合された順に並ぶ。
    var groupedEntries: [GroupedMatchEntry] {
        var order: [String] = []
        var buckets: [String: [MatchHistoryEntry]] = [:]
        for entry in entries {
            if buckets[entry.code] == nil { order.append(entry.code) }
            buckets[entry.code, default: []].append(entry)
        }
        return order.map { GroupedMatchEntry(code: $0, entries: buckets[$0] ?? []) }
    }
}

/// モルテンの納品番号1件分の集計。箱数と累計数量（収容数の合計）を持つ。
struct DeliveryBoxSummary: Equatable {
    let deliveryNumber: String
    let boxCount: Int
    let totalQuantity: Int

    /// 納品番号は前後の空白を除去し大文字化して突き合わせる。
    static func normalized(_ deliveryNumber: String) -> String {
        deliveryNumber.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// 該当する箱が1つもないときの集計。
    static func empty(deliveryNumber: String) -> DeliveryBoxSummary {
        DeliveryBoxSummary(
            deliveryNumber: normalized(deliveryNumber),
            boxCount: 0,
            totalQuantity: 0
        )
    }
}

/// 同一品番の照合履歴を1つにまとめた表示用グループ。1件の照合 = 1箱。
struct GroupedMatchEntry: Identifiable, Equatable {
    let code: String
    /// 照合順（古い順）の個別記録。
    let entries: [MatchHistoryEntry]

    var id: String { code }
    var boxCount: Int { entries.count }
    var firstMatchedAt: Date { entries.first?.matchedAt ?? .distantPast }
    var lastMatchedAt: Date { entries.last?.matchedAt ?? .distantPast }

    /// この品番の記録をモルテンの納品番号ごとにまとめた内訳。最初に照合された順に並ぶ。
    /// 澤井製作所のQRには納品番号がないため、その品番のグループでは空になる。
    var deliveryGroups: [DeliveryGroup] {
        var order: [String] = []
        var buckets: [String: [MatchHistoryEntry]] = [:]
        var records: [String: MoltenQRRecord] = [:]
        for entry in entries {
            guard let record = entry.moltenRecord else { continue }
            let number = record.deliveryNumber
            if buckets[number] == nil {
                order.append(number)
                records[number] = record
            }
            buckets[number, default: []].append(entry)
        }
        return order.compactMap { number in
            guard let record = records[number] else { return nil }
            return DeliveryGroup(
                deliveryNumber: number,
                record: record,
                entries: buckets[number] ?? []
            )
        }
    }
}

/// 同一納品番号（モルテン）の照合履歴を1つにまとめた表示用グループ。1件の照合 = 1箱。
struct DeliveryGroup: Identifiable, Equatable {
    let deliveryNumber: String
    /// この納品番号で最初に照合した記録の納品書レコード。納品先や指示日など共通欄の表示に使う。
    let record: MoltenQRRecord
    /// 照合順（古い順）の個別記録。
    let entries: [MatchHistoryEntry]

    var id: String { deliveryNumber }
    var boxCount: Int { entries.count }
    /// 累計数量。各箱の収容数を合計する。完了判定はアプリでは行わない。
    var totalQuantity: Int {
        entries.compactMap { $0.moltenRecord?.packQuantity }.reduce(0, +)
    }
}

/// 履歴表示用の曜日付き日時フォーマッタ (例: 2026/08/17(日) 21:35)
enum JPDate {
    static func dateTime(_ date: Date, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    static func time(_ date: Date, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
