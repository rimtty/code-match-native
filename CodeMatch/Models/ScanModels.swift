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
