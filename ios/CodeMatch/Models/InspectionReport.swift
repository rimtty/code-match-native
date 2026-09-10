import Foundation

/// 検品レポートの1行。紙の検品表で操作者がチェックする単位に対応する:
/// 澤井製作所は品番+枝番、モルテンは納品番号、デンソーは品番。
/// `keyText` は紙の表記に合わせて出す（澤井・モルテンはハイフンなしの生の品番、デンソーは6-4）。
struct InspectionReportRow: Equatable {
    let keyText: String
    /// モルテンのみ: その納品番号の生の部品番号。
    let partNumber: String?
    /// モルテンのみ: 納入先。同じ品番の2つの納品を見分ける。
    let deliveryDestination: String?
    let boxCount: Int
    /// 1箱あたりの数量。箱ごとに値が違う、または数量のない箱があれば nil。
    let quantityPerBox: Double?
    /// 各箱の数量の合計。数量のない箱が1つでもあれば nil。
    let totalQuantity: Double?
    /// セッションの仕向地としてQRを解析できなかった箱（旧履歴など）は true。
    let isUnparsed: Bool

    init(
        keyText: String,
        partNumber: String? = nil,
        deliveryDestination: String? = nil,
        boxCount: Int,
        quantityPerBox: Double?,
        totalQuantity: Double?,
        isUnparsed: Bool = false
    ) {
        self.keyText = keyText
        self.partNumber = partNumber
        self.deliveryDestination = deliveryDestination
        self.boxCount = boxCount
        self.quantityPerBox = quantityPerBox
        self.totalQuantity = totalQuantity
        self.isUnparsed = isUnparsed
    }
}

/// セッション1件分の検品レポートの行。
///
/// 行は品番、次いで枝番（澤井製作所）または納品番号（モルテン）の昇順に並べ、操作者が検品表の行を
/// すぐ見つけられるようにする。読取順は意図的に提供しない。仕向地として解析できない
/// QRの箱は記録済みの品番をキーにした行として末尾に残し、レポートの箱数の合計が
/// 常にセッションの箱数と一致するようにする。Android の `InspectionReportContent` と同じ規則。
struct InspectionReport: Equatable {
    enum Layout: Equatable {
        case sawai
        case molten
        case denso
    }

    let layout: Layout
    let rows: [InspectionReportRow]

    var rowCount: Int { rows.count }

    static func make(session: MatchSession) -> InspectionReport {
        let destination = session.resolvedDestination
        let layout: Layout
        switch destination {
        case .molten: layout = .molten
        case .denso: layout = .denso
        case .sawai, nil: layout = .sawai
        }

        var parsed = BucketList()
        var unparsed = BucketList()

        for entry in session.entries {
            var placed = false
            switch destination {
            case .sawai:
                if let record = entry.kanbanRecord {
                    let suffix = record.partSuffix
                    parsed.add(
                        key: SortKey(primary: record.partNumber, secondary: suffix ?? ""),
                        keyText: suffix.map { "\(record.partNumber) (\($0))" } ?? record.partNumber,
                        quantity: record.deliveryQuantity
                    )
                    placed = true
                }
            case .molten:
                // モルテンの一覧表は品番ごとなので、品番を第1キーにして同じ品番の納品番号を並べる
                if let record = entry.moltenRecord {
                    parsed.add(
                        key: SortKey(primary: record.partNumber, secondary: record.deliveryNumber),
                        keyText: record.deliveryNumber,
                        quantity: Double(record.packQuantity),
                        partNumber: record.partNumber,
                        deliveryDestination: record.deliveryDestination
                    )
                    placed = true
                }
            case .denso:
                if let record = entry.densoRecord {
                    parsed.add(
                        key: SortKey(primary: record.partNumber, secondary: ""),
                        keyText: CodeMatcher.format(partNumber: record.partNumber, destination: .denso),
                        quantity: Double(record.packQuantity)
                    )
                    placed = true
                }
            case nil:
                break
            }
            if !placed {
                unparsed.add(key: SortKey(primary: entry.code, secondary: ""), keyText: entry.code, quantity: nil)
            }
        }

        let rows = parsed.sortedRows(isUnparsed: false) + unparsed.sortedRows(isUnparsed: true)
        return InspectionReport(layout: layout, rows: rows)
    }

    private struct SortKey: Hashable, Comparable {
        let primary: String
        let secondary: String

        static func < (lhs: SortKey, rhs: SortKey) -> Bool {
            if lhs.primary != rhs.primary { return lhs.primary < rhs.primary }
            return lhs.secondary < rhs.secondary
        }
    }

    private struct Bucket {
        let keyText: String
        let partNumber: String?
        let deliveryDestination: String?
        var quantities: [Double?] = []

        func row(isUnparsed: Bool) -> InspectionReportRow {
            let allPresent = quantities.allSatisfy { $0 != nil }
            let perBox = quantities.first ?? nil
            let sameEverywhere = allPresent && quantities.allSatisfy { $0 == perBox }
            return InspectionReportRow(
                keyText: keyText,
                partNumber: partNumber,
                deliveryDestination: deliveryDestination,
                boxCount: quantities.count,
                quantityPerBox: sameEverywhere ? perBox : nil,
                totalQuantity: allPresent ? quantities.reduce(0) { $0 + ($1 ?? 0) } : nil,
                isUnparsed: isUnparsed
            )
        }
    }

    private struct BucketList {
        private var buckets: [SortKey: Bucket] = [:]

        mutating func add(
            key: SortKey,
            keyText: String,
            quantity: Double?,
            partNumber: String? = nil,
            deliveryDestination: String? = nil
        ) {
            var bucket = buckets[key] ?? Bucket(
                keyText: keyText,
                partNumber: partNumber,
                deliveryDestination: deliveryDestination
            )
            bucket.quantities.append(quantity)
            buckets[key] = bucket
        }

        func sortedRows(isUnparsed: Bool) -> [InspectionReportRow] {
            buckets.keys.sorted().compactMap { buckets[$0]?.row(isUnparsed: isUnparsed) }
        }
    }
}
