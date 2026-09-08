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

/// 仕向地。QRのレコード様式が仕向地ごとに異なるため、照合前にどれかを判定する。
///
/// - `sawai` (澤井製作所): 66桁固定長の納品書兼現品票レコード。
/// - `molten` (モルテン): 61桁固定長の納品書レコード。末尾の空白も有効なデータ。
/// - `denso` (デンソー): JAMA自己記述形式のかんばんレコード。ヘッダが項目定義を持つ可変長。
enum Destination: String, Codable, CaseIterable, Equatable {
    case sawai
    case molten
    case denso

    /// 澤井製作所QRの桁数。`KanbanQRRecord` の受理条件と同じ値を使う。
    private static let sawaiRecordLength = 66

    /// 伝送終端(CR/LF/NUL)だけを前後から取り除く。
    /// モルテンQRは末尾の空白まで含めて1レコードなので、空白は決して削らない。
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

    /// QRペイロードの仕向地を判定する。いずれのレコード様式でもない場合は nil。
    ///
    /// デンソーを最初に判定する: `KanbanQRRecord.parse` は寛容(先頭10桁が
    /// `[A-Z0-9]{4}[0-9]{6}`、20桁以上)なので、`JAMA501195…` をカード番号として
    /// 受理してしまう。JAMA自己記述形式として解析できるQRは常にデンソーとする。
    static func detect(qrPayload raw: String) -> Destination? {
        let payload = stripTransportTerminators(raw)
        if isDensoRecord(payload) { return .denso }
        if isSawaiRecord(payload) { return .sawai }
        if MoltenQRRecord.isValidScanPayload(payload) { return .molten }

        // モルテンのレコードは前後の空白まで含めて61桁なので空白は削れないが、
        // 澤井製作所のレコードは前後に空白を持たない。スキャナが空白を付けて通知しても
        // 従来どおり照合できるよう、モルテンとして読めないときだけ空白を落として再判定する。
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        return isSawaiRecord(trimmed) ? .sawai : nil
    }

    private static func isSawaiRecord(_ payload: String) -> Bool {
        payload.count == sawaiRecordLength && KanbanQRRecord.parse(payload) != nil
    }

    private static func isDensoRecord(_ payload: String) -> Bool {
        payload.prefix(4).uppercased() == "JAMA" && DensoKanbanRecord.parse(payload) != nil
    }
}

extension Destination {
    /// 画面に出す仕向地の名称。表示のたびに現在の言語で解決する。
    var displayName: String {
        switch self {
        case .sawai: AppLocalization.string("澤井製作所")
        case .molten: AppLocalization.string("モルテン")
        case .denso: AppLocalization.string("デンソー")
        }
    }
}

/// 現場ラベルの実データ仕様に基づく品番照合。
///
/// - 現品票のCode 128: `品番(ハイフン付き)@管理コード` 例: `BCJH-52-81GG@1N5X0C`
/// - 澤井製作所の納品書兼現品票QR: 66桁固定長レコード。先頭からカード番号(10桁)、
///   品目番号(10桁の欄・区切りなし・9桁品番は左詰めで末尾空白)、枝番(2桁・空白の場合あり)、
///   数量などが続く。例: `DCLP675300` + `BCJH5281GG` + `02` + …、
///   9桁品番の例: `DAH4093540` + `BCJH5281F ` + `  ` + …
/// - 現品票のCode 128の品番は10桁品番が4-2-4、9桁品番が4-2-3(例: `BCJH-52-81F@01R95K`)。
/// - モルテンの納品書QR: 61桁固定長レコード。7-16桁が左詰めの部品番号(9桁または10桁)。
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
        case .molten:
            return MoltenQRRecord.parse(payload)?.partNumber
        case .denso:
            return DensoKanbanRecord.parse(payload)?.partNumber
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

    /// 品番を仕向地の現品票の表記へ整形する。
    /// デンソーの10桁は6-4 (`8601507722` → `860150-7722`)。
    /// 澤井製作所・モルテン・仕向地未確定は従来の長さ規則(10→4-2-4 / 9→4-2-3)。
    /// それ以外の桁数はそのまま返す。
    static func format(partNumber: String, destination: Destination?) -> String {
        if destination == .denso {
            guard partNumber.count == densoPartNumberLength else { return partNumber }
            return "\(partNumber.prefix(6))-\(partNumber.dropFirst(6))"
        }
        guard partNumber.count == 10 || partNumber.count == 9 else { return partNumber }
        let head = partNumber.prefix(4)
        let mid = partNumber.dropFirst(4).prefix(2)
        let tail = partNumber.dropFirst(6)
        return "\(head)-\(mid)-\(tail)"
    }

    private static let densoPartNumberLength = 10
}

/// 仕向地 澤井製作所の納品書兼現品票QR(66桁固定長レコード)の解析結果。
/// フィールド位置は docs/PRODUCT_SPEC.md と実データ解析に基づく。
/// もう一方の仕向地モルテン(61桁)は `MoltenQRRecord` が担当する。
struct KanbanQRRecord: Equatable {
    let cardNumber: String        // 1-10桁: カード番号(英数4桁のコード + 6桁の連番)
    let partNumber: String        // 11-20桁: 品目番号(9桁または10桁。9桁は左詰めで末尾空白を除去済み)
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
            // カード番号は英数4桁のコード + 6桁の連番。実データは `DCLP675300` 型と
            // `DAH4093870` 型の両方があり、4桁目が数字でも受理する(#129)。
            cardNumber.range(of: "^[A-Z0-9]{4}[0-9]{6}$", options: .regularExpression) != nil,
            let partField = slice(10..<20),
            // 品目番号欄は10桁。9桁品番は左詰めで末尾が空白(`BCJH5281F `)。
            partField.range(of: "^[A-Z0-9]{9}[A-Z0-9 ]$", options: .regularExpression) != nil
        else { return nil }

        return KanbanQRRecord(
            cardNumber: cardNumber,
            partNumber: partField.trimmingCharacters(in: .whitespaces),
            partSuffix: trimmedOrNil(slice(20..<22)),
            deliveryQuantity: quantity(slice(22..<30)),
            instructedQuantity: quantity(slice(30..<38)),
            factoryCode: trimmedOrNil(slice(38..<39)),
            warehouseCode: trimmedOrNil(slice(51..<56)),
            supplyPointCode: trimmedOrNil(slice(56..<61))
        )
    }
}

/// 仕向地 モルテンの納品書QR(61桁固定長レコード)の解析結果。
/// フィールド位置は実データ解析に基づく。数値以外の欄は左詰めで空白埋めされる。
struct MoltenQRRecord: Equatable {
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

    /// モルテンの納品書QRとして受理できるかを判定する。
    /// SDKの文字列コールバックにはシンボル種別が含まれないため、固定長と必須フィールドの
    /// 両方を確認してCode 128の逆順入力を防ぐ。
    static func isValidScanPayload(_ payload: String) -> Bool {
        parse(payload) != nil
    }

    static func parse(_ payload: String) -> MoltenQRRecord? {
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

        return MoltenQRRecord(
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

extension MoltenQRRecord {
    /// 納入指示日(JUMP)の表示形。`0908` → `09/08`。
    var formattedInstructionDate: String {
        Self.pairs(instructionDate, separator: "/")
    }

    /// 時刻の表示形。`0000` → `00:00`。時刻欄が空欄の記録ではnilのまま返す。
    var formattedInstructionTime: String? {
        instructionTime.map { Self.pairs($0, separator: ":") }
    }

    /// 4桁の値を2桁ずつに区切る。4桁でない値は整形せずそのまま返す。
    private static func pairs(_ value: String, separator: String) -> String {
        guard value.count == 4 else { return value }
        return "\(value.prefix(2))\(separator)\(value.suffix(2))"
    }
}

/// デンソーのかんばんQRが持つ項目1つ。項目番号は3桁の文字列。
struct DensoKanbanItem: Equatable {
    let id: String
    let value: String
}

/// 仕向地 デンソーのかんばんQR(JAMA自己記述形式)の解析結果。
///
/// レコード構造:
/// `JAMA` + 版1桁 + ヘッダ長4桁(L) + 前置き10桁 + (項目番号3桁 + 桁数2桁) × N + データ部
///
/// ヘッダ長 L は「ヘッダ長欄の先頭から項目定義の末尾まで」の桁数なので、
/// ヘッダ本体は `payload[9 ..< 5+L]`、データ部は `payload[5+L...]`。
/// 項目定義の桁数の合計はデータ部の長さと過不足なく一致しなければならない。
///
/// 221桁固定とは決めつけない: 実データは L=119・項目21・データ97桁だが、
/// 項目構成が変わったかんばんも同じ規則で解析できるようにしてある。
struct DensoKanbanRecord: Equatable {
    let version: String            // JAMAに続く版1桁
    let preamble: String           // ヘッダ本体の先頭10桁(内容は検証しない生値)
    let formType: String?          // 100: 帳票区分
    let partNumber: String         // 104: 部品番号(ハイフンなし10桁)
    let packagingCode: String?     // 111: 包装
    let packQuantity: Int          // 112: 収容数
    let nextProcess: String?       // 121: 次区
    let instructionCode: String?   // 124(+`-`+141): 指示
    let kanbanSerial: String       // 152: かんばん連番(箱ごとに固有)
    let managementNumber: String?  // 402: 管理番号
    let deliveryDate: String?      // 519: 納入日 YYYYMMDD
    let deliveryRun: String?       // 520: 便
    let instructedQuantity: Int?   // 521: 指示数
    let itemNumber: String?        // 523: アイテムNo
    let receivingCode: String?     // 401: 受入
    let orderedItems: [DensoKanbanItem] // 全項目の生値(ヘッダの並び順)
    let items: [String: String]    // 項目番号 → 生値
    let canonicalPayload: String   // 終端除去+大文字化した正規形(桁数はそのまま)

    /// 様式の先頭固定文字列。
    static let formatPrefix = "JAMA"
    /// `JAMA` + 版1桁 + ヘッダ長4桁。
    private static let headerFieldsLength = 9
    /// ヘッダ本体の先頭に置かれる前置きの桁数。
    private static let preambleLength = 10
    /// 項目定義1つぶんの桁数(項目番号3桁 + 桁数2桁)。
    private static let descriptorLength = 5

    /// Bluetoothスキャナ入力をデンソーのかんばんQRとして受理できるかを判定する。
    static func isValidScanPayload(_ payload: String) -> Bool {
        parse(payload) != nil
    }

    /// 読取値の正規形。解析できない値は nil。
    static func canonicalize(_ payload: String) -> String? {
        parse(payload)?.canonicalPayload
    }

    static func parse(_ payload: String) -> DensoKanbanRecord? {
        let record = Destination.stripTransportTerminators(payload).uppercased()
        let characters = Array(record)
        guard characters.count >= headerFieldsLength else { return nil }

        func slice(_ range: Range<Int>) -> String { String(characters[range]) }
        func isDigits(_ value: String) -> Bool {
            !value.isEmpty && value.allSatisfy { $0.isASCII && $0.isNumber }
        }
        func trimmedOrNil(_ value: String?) -> String? {
            let trimmed = value?.trimmingCharacters(in: .whitespaces)
            return (trimmed?.isEmpty ?? true) ? nil : trimmed
        }

        guard slice(0..<4) == formatPrefix else { return nil }
        let version = slice(4..<5)
        guard isDigits(version) else { return nil }

        let headerLengthField = slice(5..<headerFieldsLength)
        guard isDigits(headerLengthField), let headerLength = Int(headerLengthField) else {
            return nil
        }
        // ヘッダ長は「ヘッダ長欄の先頭から項目定義の末尾まで」なので、
        // データ部の開始位置は 5 + L。
        let dataStart = 5 + headerLength
        guard characters.count >= dataStart else { return nil }

        let headerBody = slice(headerFieldsLength..<dataStart)
        guard headerBody.count >= preambleLength else { return nil }
        let preamble = String(headerBody.prefix(preambleLength))
        let descriptorField = String(headerBody.dropFirst(preambleLength))
        guard !descriptorField.isEmpty, descriptorField.count % descriptorLength == 0 else {
            return nil
        }

        let descriptorCharacters = Array(descriptorField)
        var lengths: [(id: String, length: Int)] = []
        var seenIDs = Set<String>()
        var index = 0
        while index < descriptorCharacters.count {
            let id = String(descriptorCharacters[index..<(index + 3)])
            let lengthField = String(descriptorCharacters[(index + 3)..<(index + descriptorLength)])
            guard isDigits(id), isDigits(lengthField), let length = Int(lengthField) else {
                return nil
            }
            // 同じ項目番号が2度出るレコードは解釈が定まらないので受理しない。
            guard seenIDs.insert(id).inserted else { return nil }
            lengths.append((id: id, length: length))
            index += descriptorLength
        }

        let dataCharacters = Array(characters[dataStart...])
        guard lengths.reduce(0, { $0 + $1.length }) == dataCharacters.count else { return nil }

        var orderedItems: [DensoKanbanItem] = []
        var items: [String: String] = [:]
        var offset = 0
        for entry in lengths {
            let value = String(dataCharacters[offset..<(offset + entry.length)])
            orderedItems.append(DensoKanbanItem(id: entry.id, value: value))
            items[entry.id] = value
            offset += entry.length
        }

        // 必須項目: 部品番号・収容数・かんばん連番。1つでも欠ければかんばんとして扱わない。
        guard
            let partNumber = trimmedOrNil(items["104"]),
            partNumber.range(of: "^[A-Z0-9]{1,18}$", options: .regularExpression) != nil,
            let packQuantityField = items["112"]?.trimmingCharacters(in: .whitespaces),
            isDigits(packQuantityField),
            let packQuantity = Int(packQuantityField),
            let kanbanSerial = trimmedOrNil(items["152"])
        else { return nil }

        let instructionBase = trimmedOrNil(items["124"])
        let instructionSuffix = trimmedOrNil(items["141"])
        let instructionCode: String? = instructionBase.map { base in
            instructionSuffix.map { "\(base)-\($0)" } ?? base
        }
        let instructedQuantityField = items["521"]?.trimmingCharacters(in: .whitespaces)

        return DensoKanbanRecord(
            version: version,
            preamble: preamble,
            formType: trimmedOrNil(items["100"]),
            partNumber: partNumber,
            packagingCode: trimmedOrNil(items["111"]),
            packQuantity: packQuantity,
            nextProcess: trimmedOrNil(items["121"]),
            instructionCode: instructionCode,
            kanbanSerial: kanbanSerial,
            managementNumber: trimmedOrNil(items["402"]),
            deliveryDate: trimmedOrNil(items["519"]),
            deliveryRun: trimmedOrNil(items["520"]),
            instructedQuantity: instructedQuantityField.flatMap { Int($0) },
            itemNumber: trimmedOrNil(items["523"]),
            receivingCode: trimmedOrNil(items["401"]),
            orderedItems: orderedItems,
            items: items,
            canonicalPayload: record
        )
    }
}

extension DensoKanbanRecord {
    /// 納入日の表示形。`20260908` → `2026/09/08`。8桁でない値はそのまま返す。
    var formattedDeliveryDate: String? {
        guard let deliveryDate else { return nil }
        guard deliveryDate.count == 8 else { return deliveryDate }
        let year = deliveryDate.prefix(4)
        let month = deliveryDate.dropFirst(4).prefix(2)
        let day = deliveryDate.dropFirst(6)
        return "\(year)/\(month)/\(day)"
    }
}

/// 「同じ箱を二重に検査していないか」を判定するための箱固有キー。
///
/// 澤井製作所はカード番号がQRに含まれるためQR単体で箱を識別でき、
/// デンソーもかんばん連番(項目152)が箱ごとに違うのでQR単体で識別できる。
/// モルテンのQRは1品番1レコードで箱を区別しないため、現品票の管理コードまで含める。
enum BoxIdentity {
    static func make(qrPayload: String, barcodePayload: String?) -> String? {
        func identity(_ value: String) -> String {
            value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        }

        switch Destination.detect(qrPayload: qrPayload) {
        case .sawai, .denso:
            let qr = identity(qrPayload)
            return qr.isEmpty ? nil : qr
        case .molten:
            guard let canonical = MoltenQRRecord.canonicalize(qrPayload) else { return nil }
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

    /// 澤井製作所の現品票: 品番は4-2-4(10桁)または4-2-3(9桁品番、例 `BCJH-52-81F`)。
    private static let sawaiFormatPattern = "^[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]{3,4}@[A-Z0-9]+$"
    /// モルテンの現品票: 品番は4-2-3または4-2-4。
    private static let moltenFormatPattern = "^[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]{3,4}@[A-Z0-9]+$"
    /// デンソーの現品票: 品番は6-4。
    private static let densoFormatPattern = "^[A-Z0-9]{6}-[A-Z0-9]{4}@[A-Z0-9]+$"

    /// 現品票Code 128の業務フォーマット（品番@管理コード）かを確認する。
    /// 物理シンボル種別はSDK通知に含まれないため、QR文字列などを次工程で受理しない。
    /// 品番の区切りは澤井製作所・モルテンが4-2-3または4-2-4、デンソーが6-4。
    /// 仕向地が未判定(nil)のときは、どの仕向地でも取りこぼさないよう3つのORで判定する。
    static func isValidScanPayload(_ payload: String, destination: Destination?) -> Bool {
        let value = payload.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let patterns: [String]
        switch destination {
        case .sawai: patterns = [sawaiFormatPattern]
        case .molten: patterns = [moltenFormatPattern]
        case .denso: patterns = [densoFormatPattern]
        case nil: patterns = [sawaiFormatPattern, moltenFormatPattern, densoFormatPattern]
        }
        return patterns.contains {
            value.range(of: $0, options: .regularExpression) != nil
        }
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
