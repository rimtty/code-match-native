import AVFoundation

enum ScanStep: Equatable {
    case qr
    case barcode
    case result(MatchResult)

    var progress: Int {
        switch self {
        case .qr: 1
        case .barcode: 2
        case .result: 3
        }
    }
}

enum MatchResult: Equatable {
    case match
    case mismatch
}

enum ExpectedCode: Equatable {
    case qr
    case barcode

    var metadataType: AVMetadataObject.ObjectType {
        switch self {
        case .qr: .qr
        case .barcode: .code128
        }
    }
}

/// 現場ラベルの実データ仕様に基づく品番照合。
///
/// - 現品票のCode 128: `品番(ハイフン付き)@管理コード` 例: `BCJH-52-81GG@1N5X0C`
/// - 納品書兼現品票のQR: 固定長レコード。先頭からカード番号(10桁)、品目番号(10桁・区切りなし)、
///   枝番(2桁・空白の場合あり)、数量などが続く。例: `DCLP675300` + `BCJH5281GG` + `02` + …
///
/// 2つのペイロードは文字列としては一致しないため、双方から品番を抽出して比較する。
enum CodeMatcher {
    /// 大文字化し、英数字以外(ハイフン・空白など)を取り除く。
    static func normalize(_ raw: String) -> String {
        raw.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    /// Code 128ペイロードから品番を抽出する。`@`より前がハイフン付き品番。
    /// `BCJH-52-81GG@1N5X0C` → `BCJH5281GG`
    static func partNumber(fromBarcode raw: String) -> String? {
        let head = raw.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? raw
        let normalized = normalize(head)
        return normalized.isEmpty ? nil : normalized
    }

    /// QRペイロードから品目番号を抽出する。
    /// 先頭10桁がカード番号(英字4+数字6)の標準フォーマットのとき、続く10桁が品目番号。
    static func partNumber(fromQR raw: String) -> String? {
        let payload = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard payload.count >= 20 else { return nil }
        let cardNumber = payload.prefix(10)
        guard cardNumber.range(of: "^[A-Z]{4}[0-9]{6}$", options: .regularExpression) != nil else {
            return nil
        }
        let start = payload.index(payload.startIndex, offsetBy: 10)
        let end = payload.index(payload.startIndex, offsetBy: 20)
        let part = String(payload[start..<end])
        guard part.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil else { return nil }
        return part
    }

    /// QRの品目番号とバーコードの品番が同一かを判定する。
    /// QRが標準フォーマットでない場合は、正規化した全文に品番が含まれるかで代替判定する。
    static func compare(qrPayload: String, barcodePayload: String) -> MatchResult {
        guard let barcodePart = partNumber(fromBarcode: barcodePayload) else { return .mismatch }

        if let qrPart = partNumber(fromQR: qrPayload) {
            return qrPart == barcodePart ? .match : .mismatch
        }

        // 誤検出を避けるため、代替判定は品番が十分な長さの場合だけ行う。
        guard barcodePart.count >= 6 else { return .mismatch }
        return normalize(qrPayload).contains(barcodePart) ? .match : .mismatch
    }

    /// 10桁の品番を現品票の表記(4-2-4)へ整形する。 `BCJH5281GG` → `BCJH-52-81GG`
    static func format(partNumber: String) -> String {
        guard partNumber.count == 10 else { return partNumber }
        let head = partNumber.prefix(4)
        let mid = partNumber.dropFirst(4).prefix(2)
        let tail = partNumber.suffix(4)
        return "\(head)-\(mid)-\(tail)"
    }
}

/// 納品書兼現品票QR(66桁固定長レコード)の解析結果。
/// フィールド位置は docs/qr-barcode-spec-analysis.html の実データ解析に基づく。
struct KanbanQRRecord: Equatable {
    let cardNumber: String        // 1-10桁: カード番号
    let partNumber: String        // 11-20桁: 品目番号
    let partSuffix: String?       // 21-22桁: 枝番(空白の場合あり)
    let deliveryQuantity: Double? // 23-30桁: 納入数量(×100で記録)
    let instructedQuantity: Double? // 31-38桁: 指示数(×100で記録)
    let factoryCode: String?      // 39桁: 工場コード
    let warehouseCode: String?    // 52-56桁: 受入部品庫
    let supplyPointCode: String?  // 57-61桁: 供給先

    /// Bluetoothスキャナ入力を納品書QRとして受理できるかを判定する。
    /// SDKの文字列コールバックにはシンボル種別が含まれないため、実データ仕様の
    /// 66桁固定長と必須フィールドの両方を確認してCode 128の逆順入力を防ぐ。
    static func isValidScanPayload(_ payload: String) -> Bool {
        let record = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        return record.count == 66 && parse(record) != nil
    }

    static func parse(_ payload: String) -> KanbanQRRecord? {
        let record = payload.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard record.count >= 20 else { return nil }

        func slice(_ range: Range<Int>) -> String? {
            guard record.count >= range.upperBound else { return nil }
            let start = record.index(record.startIndex, offsetBy: range.lowerBound)
            let end = record.index(record.startIndex, offsetBy: range.upperBound)
            return String(record[start..<end])
        }

        func trimmedOrNil(_ value: String?) -> String? {
            let trimmed = value?.trimmingCharacters(in: .whitespaces)
            return (trimmed?.isEmpty ?? true) ? nil : trimmed
        }

        func quantity(_ value: String?) -> Double? {
            guard let value, let raw = Double(value.trimmingCharacters(in: .whitespaces)) else { return nil }
            return raw / 100
        }

        guard
            let cardNumber = slice(0..<10),
            cardNumber.range(of: "^[A-Z]{4}[0-9]{6}$", options: .regularExpression) != nil,
            let partNumber = slice(10..<20),
            partNumber.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil
        else { return nil }

        return KanbanQRRecord(
            cardNumber: cardNumber,
            partNumber: partNumber,
            partSuffix: trimmedOrNil(slice(20..<22)),
            deliveryQuantity: quantity(slice(22..<30)),
            instructedQuantity: quantity(slice(30..<38)),
            factoryCode: trimmedOrNil(slice(38..<39)),
            warehouseCode: trimmedOrNil(slice(51..<56)),
            supplyPointCode: trimmedOrNil(slice(56..<61))
        )
    }
}

/// 現品票Code 128(`品番@管理コード`)の解析結果。
struct TagBarcodeRecord: Equatable {
    let partNumber: String       // ハイフン付き品番
    let managementCode: String?  // @以降の管理コード

    /// 現品票Code 128の業務フォーマット（4-2-4の品番@管理コード）かを確認する。
    /// 物理シンボル種別はSDK通知に含まれないため、QR文字列などを次工程で受理しない。
    static func isValidScanPayload(_ payload: String) -> Bool {
        let value = payload.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return value.range(
            of: "^[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]{4}@[A-Z0-9]+$",
            options: .regularExpression
        ) != nil
    }

    static func parse(_ payload: String) -> TagBarcodeRecord? {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let parts = trimmed.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
        let part = String(parts[0]).trimmingCharacters(in: .whitespaces)
        guard !part.isEmpty else { return nil }
        let code = parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespaces) : nil
        return TagBarcodeRecord(partNumber: part, managementCode: (code?.isEmpty ?? true) ? nil : code)
    }
}
