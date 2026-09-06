import AVFoundation

enum AutoAdvanceDelay: Int, CaseIterable, Identifiable {
    case oneSecond = 1
    case threeSeconds = 3
    case fiveSeconds = 5

    var id: Int { rawValue }
    var label: String { AppLocalization.string("\(rawValue)秒") }
}

/// 一致結果の表示後に次の照合へ進む動作を、設定画面と照合画面で共有する。
enum AutoAdvanceSettings {
    static let enabledKey = "autoAdvanceOnMatch"
    static let delaySecondsKey = "autoAdvanceDelaySeconds"
    static let defaultEnabled = false
    static let defaultDelay = AutoAdvanceDelay.threeSeconds

    static func isEnabled(in defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: enabledKey) as? Bool ?? defaultEnabled
    }

    static func delay(in defaults: UserDefaults = .standard) -> AutoAdvanceDelay {
        AutoAdvanceDelay(rawValue: defaults.integer(forKey: delaySecondsKey)) ?? defaultDelay
    }

    static func resetForUITestingIfRequested(
        arguments: [String],
        defaults: UserDefaults = .standard
    ) {
        guard arguments.contains("-resetAutoAdvance") else { return }
        defaults.removeObject(forKey: enabledKey)
        defaults.removeObject(forKey: delaySecondsKey)
    }
}

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
    case duplicate
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

/// 仕向地。QRのレコード様式が仕向地ごとに異なるため、照合前にどちらかを判定する。
///
/// - `sawai` (澤井製作所): 66桁固定長の納品書兼現品票レコード。
/// - `moltec` (モルテック): 61桁固定長の納品書レコード。末尾の空白も有効なデータ。
enum Destination: String, Codable, CaseIterable, Equatable {
    case sawai
    case moltec

    /// 澤井製作所QRの桁数。`KanbanQRRecord` の受理条件と同じ値を使う。
    private static let sawaiRecordLength = 66

    /// 伝送終端(CR/LF/NUL)だけを前後から取り除く。
    /// モルテックQRは末尾の空白まで含めて1レコードなので、空白は決して削らない。
    static func stripTransportTerminators(_ raw: String) -> String {
        func isTerminator(_ scalar: Unicode.Scalar) -> Bool {
            scalar == "\r" || scalar == "\n" || scalar.value == 0
        }

        var value = raw
        while let first = value.unicodeScalars.first, isTerminator(first) {
            value.removeFirst()
        }
        while let last = value.unicodeScalars.last, isTerminator(last) {
            value.removeLast()
        }
        return value
    }

    /// QRペイロードの仕向地を判定する。どちらのレコード様式でもない場合は nil。
    static func detect(qrPayload raw: String) -> Destination? {
        let payload = stripTransportTerminators(raw)
        if isSawaiRecord(payload) { return .sawai }
        if MoltecQRRecord.isValidScanPayload(payload) { return .moltec }

        // モルテックのレコードは前後の空白まで含めて61桁なので空白は削れないが、
        // 澤井製作所のレコードは前後に空白を持たない。スキャナが空白を付けて通知しても
        // 従来どおり照合できるよう、モルテックとして読めないときだけ空白を落として再判定する。
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        return isSawaiRecord(trimmed) ? .sawai : nil
    }

    private static func isSawaiRecord(_ payload: String) -> Bool {
        payload.count == sawaiRecordLength && KanbanQRRecord.parse(payload) != nil
    }
}

/// 現場ラベルの実データ仕様に基づく品番照合。
///
/// - 現品票のCode 128: `品番(ハイフン付き)@管理コード` 例: `BCJH-52-81GG@1N5X0C`
/// - 澤井製作所の納品書兼現品票QR: 66桁固定長レコード。先頭からカード番号(10桁)、
///   品目番号(10桁・区切りなし)、枝番(2桁・空白の場合あり)、数量などが続く。
///   例: `DCLP675300` + `BCJH5281GG` + `02` + …
/// - モルテックの納品書QR: 61桁固定長レコード。7-16桁が左詰めの部品番号(9桁または10桁)。
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

    /// QRペイロードから品目番号(部品番号)を抽出する。
    /// 先に仕向地を判定し、そのレコード様式の固定位置からだけ取り出す。
    /// どちらのレコード様式でもないQRからは抽出しない。
    static func partNumber(fromQR raw: String) -> String? {
        let payload = Destination.stripTransportTerminators(raw)
        switch Destination.detect(qrPayload: payload) {
        case .sawai:
            return KanbanQRRecord.parse(payload)?.partNumber
        case .moltec:
            return MoltecQRRecord.parse(payload)?.partNumber
        case nil:
            return nil
        }
    }

    /// QRの品目番号とバーコードの品番が同一かを判定する。
    /// 双方をレコード様式どおりに解析できたときだけ比較し、部分一致では判定しない。
    static func compare(qrPayload: String, barcodePayload: String) -> MatchResult {
        guard
            let barcodePart = partNumber(fromBarcode: barcodePayload),
            let qrPart = partNumber(fromQR: qrPayload)
        else { return .mismatch }

        return qrPart == barcodePart ? .match : .mismatch
    }

    /// 品番を現品票の表記へ整形する。
    /// 10桁は4-2-4 (`BCJH5281GG` → `BCJH-52-81GG`)、9桁は4-2-3 (`PAF115422` → `PAF1-15-422`)。
    /// それ以外の桁数はそのまま返す。
    static func format(partNumber: String) -> String {
        guard partNumber.count == 10 || partNumber.count == 9 else { return partNumber }
        let head = partNumber.prefix(4)
        let mid = partNumber.dropFirst(4).prefix(2)
        let tail = partNumber.dropFirst(6)
        return "\(head)-\(mid)-\(tail)"
    }
}

/// 仕向地 澤井製作所の納品書兼現品票QR(66桁固定長レコード)の解析結果。
/// フィールド位置は docs/PRODUCT_SPEC.md と実データ解析に基づく。
/// もう一方の仕向地モルテック(61桁)は `MoltecQRRecord` が担当する。
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

/// 仕向地 モルテックの納品書QR(61桁固定長レコード)の解析結果。
/// フィールド位置は実データ解析に基づく。数値以外の欄は左詰めで空白埋めされる。
struct MoltecQRRecord: Equatable {
    let ordererCode: String       // 1-6桁: 先頭コード+受注者 例: `AK6805`
    let partNumber: String        // 7-16桁: 部品番号(左詰め・末尾空白は除去)
    let deliveryNumber: String    // 26-32桁: 納品番号
    let deliveryDestination: String // 36-38桁: 納入先
    let tyLocation: String?       // 39-41桁: TYロケーション(空欄あり)
    let supplyPoint: String       // 42-46桁: 供給先
    let packQuantity: Int         // 47-53桁: 収容数(ゼロ埋め整数)
    let instructionDate: String   // 54-57桁: 納入指示日(JUMP) MMDD
    let instructionTime: String?  // 58-61桁: 時刻 HHMM(空白4桁のときnil)
    let canonicalPayload: String  // 61桁へ空白補完し大文字化した正規形

    /// レコード全体の桁数。
    static let recordLength = 61
    /// 末尾空白が欠けた読取値も受理する下限桁数。
    static let minimumScanPayloadLength = 57

    /// 読取値を61桁の正規形へ整える。末尾空白が落ちた読取値は空白で補完する。
    /// 桁数と文字種を満たさない場合はnil。
    static func canonicalize(_ payload: String) -> String? {
        let value = Destination.stripTransportTerminators(payload).uppercased()
        guard value.count >= minimumScanPayloadLength, value.count <= recordLength else {
            return nil
        }
        let padded = value + String(repeating: " ", count: recordLength - value.count)
        guard padded.range(
            of: "^[A-Z0-9 -]{\(recordLength)}$",
            options: .regularExpression
        ) != nil else { return nil }
        return padded
    }

    /// モルテックの納品書QRとして受理できるかを判定する。
    /// SDKの文字列コールバックにはシンボル種別が含まれないため、固定長と必須フィールドの
    /// 両方を確認してCode 128の逆順入力を防ぐ。
    static func isValidScanPayload(_ payload: String) -> Bool {
        parse(payload) != nil
    }

    static func parse(_ payload: String) -> MoltecQRRecord? {
        guard let record = canonicalize(payload) else { return nil }
        let characters = Array(record)

        func slice(_ range: Range<Int>) -> String { String(characters[range]) }
        func trimmed(_ value: String) -> String { value.trimmingCharacters(in: .whitespaces) }
        func trimmedOrNil(_ value: String) -> String? {
            let value = trimmed(value)
            return value.isEmpty ? nil : value
        }
        func matches(_ value: String, _ pattern: String) -> Bool {
            value.range(of: pattern, options: .regularExpression) != nil
        }

        let partField = slice(6..<16)
        let quantityField = slice(46..<53)
        let dateField = slice(53..<57)
        let timeField = slice(57..<61)
        guard
            matches(partField, "^[A-Z0-9]{9}[A-Z0-9 ]$"),
            matches(quantityField, "^[0-9]{7}$"),
            let packQuantity = Int(quantityField),
            matches(dateField, "^[0-9]{4}$"),
            matches(timeField, "^([0-9]{4}| {4})$")
        else { return nil }

        return MoltecQRRecord(
            ordererCode: slice(0..<6),
            partNumber: trimmed(partField),
            deliveryNumber: slice(25..<32),
            deliveryDestination: trimmed(slice(35..<38)),
            tyLocation: trimmedOrNil(slice(38..<41)),
            supplyPoint: trimmed(slice(41..<46)),
            packQuantity: packQuantity,
            instructionDate: dateField,
            instructionTime: trimmed(timeField).isEmpty ? nil : timeField,
            canonicalPayload: record
        )
    }
}

/// 「同じ箱を二重に検査していないか」を判定するための箱固有キー。
///
/// 澤井製作所はカード番号がQRに含まれるためQR単体で箱を識別できるが、
/// モルテックのQRは1品番1レコードで箱を区別しないため、現品票の管理コードまで含める。
enum BoxIdentity {
    static func make(qrPayload: String, barcodePayload: String?) -> String? {
        func identity(_ value: String) -> String {
            value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        }

        switch Destination.detect(qrPayload: qrPayload) {
        case .sawai:
            let qr = identity(qrPayload)
            return qr.isEmpty ? nil : qr
        case .moltec:
            guard let canonical = MoltecQRRecord.canonicalize(qrPayload) else { return nil }
            let tag = identity(barcodePayload ?? "")
            return tag.isEmpty ? nil : "\(canonical)|\(tag)"
        case nil:
            return nil
        }
    }
}

/// 現品票Code 128(`品番@管理コード`)の解析結果。
struct TagBarcodeRecord: Equatable {
    let partNumber: String       // ハイフン付き品番
    let managementCode: String?  // @以降の管理コード

    /// 現品票Code 128の業務フォーマット（品番@管理コード）かを確認する。
    /// 物理シンボル種別はSDK通知に含まれないため、QR文字列などを次工程で受理しない。
    /// 品番の末尾ブロックは澤井製作所が4桁固定、モルテックは3桁または4桁。
    /// 仕向地が未判定(nil)のときは、どちらの仕向地でも取りこぼさない緩い方を使う。
    static func isValidScanPayload(_ payload: String, destination: Destination?) -> Bool {
        let value = payload.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let tailLength = destination == .sawai ? "{4}" : "{3,4}"
        return value.range(
            of: "^[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]\(tailLength)@[A-Z0-9]+$",
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
