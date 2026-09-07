import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

final class CodeMatcherTests: XCTestCase {
    private struct SharedMatchingFixtures: Decodable {
        let schemaVersion: Int
        let cases: [SharedMatchingCase]
    }

    private struct SharedMatchingCase: Decodable {
        let id: String
        let qrPayload: String
        let barcodePayload: String
        let expected: String
        let destination: String?
    }

    // 実ラベルからデコードした実データ（仕向地 澤井製作所・66桁）
    private let qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let barcodePayload = "BCJH-52-81GG@1N5X0C"

    // 仕向地 モルテンの実データ（61桁・末尾の空白も有効なデータなので削らないこと）
    private let moltenQRPayload = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private let moltenBarcodePayload = "D10E-50-N10B@0UBL00"
    // 部品番号が9桁（4-2-3表記）のペア
    private let moltenShortPartQRPayload = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private let moltenShortPartBarcodePayload = "PAF1-15-422@0NKD3C"

    // 仕向地 デンソーの実データ（かんばん 0140・221桁）。
    // 項目144・402・515・516の空白の連なりもデータなので、詰めないこと。
    private let densoQRPayload =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private let densoBarcodePayload = "860150-7722@1DZ50O"

    // 項目構成を変えた合成かんばん（項目3つ・ヘッダ長も別）。
    // デンソーを先に判定しないと、66桁は澤井製作所、61桁はモルテンとして
    // 完全なレコードに見えてしまう。
    private let densoSawaiLengthPayload =
        "JAMA5002950000000031521010410112120140      8601507722000000000024"
    private let densoMoltenLengthPayload =
        "JAMA500295000000003104101120715210860150772200000240140123456"

    func testPartNumberFromBarcode() {
        XCTAssertEqual(CodeMatcher.partNumber(fromBarcode: barcodePayload), "BCJH5281GG")
        XCTAssertEqual(CodeMatcher.partNumber(fromBarcode: "KAAA-55-D86B@0Y5U0I"), "KAAA55D86B")
        XCTAssertEqual(CodeMatcher.partNumber(fromBarcode: "BCJH-52-81GG"), "BCJH5281GG")
        XCTAssertNil(CodeMatcher.partNumber(fromBarcode: "@ABC123"))
    }

    func testPartNumberFromQR() {
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: qrPayload), "BCJH5281GG")
        // 枝番が空白のQR(納品書側の枝番欄が空)でも品目番号は取れる
        XCTAssertEqual(
            CodeMatcher.partNumber(fromQR: "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"),
            "DFR55581GA"
        )
        // カード番号フォーマットでない場合は固定位置抽出をしない
        XCTAssertNil(CodeMatcher.partNumber(fromQR: "HELLO WORLD 1234567890"))
        XCTAssertNil(CodeMatcher.partNumber(fromQR: "SHORT"))
    }

    func testRealPairMatches() {
        XCTAssertEqual(CodeMatcher.compare(qrPayload: qrPayload, barcodePayload: barcodePayload), .match)
    }

    private func loadSharedMatchingFixtures() throws -> SharedMatchingFixtures {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let fixtureURL = repositoryRoot
            .appendingPathComponent("shared/test-fixtures/matching-cases.json")
        return try JSONDecoder().decode(
            SharedMatchingFixtures.self,
            from: Data(contentsOf: fixtureURL)
        )
    }

    func testSharedMatchingFixtures() throws {
        let fixtures = try loadSharedMatchingFixtures()

        XCTAssertEqual(fixtures.schemaVersion, 2)
        XCTAssertEqual(fixtures.cases.count, 47)
        XCTAssertEqual(
            Set(fixtures.cases.map(\.id)).count,
            fixtures.cases.count,
            "Shared fixture IDs must be unique"
        )

        for fixture in fixtures.cases {
            let expected: MatchResult
            switch fixture.expected {
            case "match":
                expected = .match
            case "mismatch":
                expected = .mismatch
            default:
                XCTFail("Unknown shared fixture result: \(fixture.expected)")
                continue
            }
            XCTAssertEqual(
                CodeMatcher.compare(
                    qrPayload: fixture.qrPayload,
                    barcodePayload: fixture.barcodePayload
                ),
                expected,
                "Shared fixture failed: \(fixture.id)"
            )

            // destination を持つケースは、仕向地判定がその値を返すことまで固定する。
            guard let destination = fixture.destination else { continue }
            XCTAssertEqual(
                Destination.detect(qrPayload: fixture.qrPayload)?.rawValue,
                destination,
                "Shared fixture destination failed: \(fixture.id)"
            )
        }
    }

    /// 仕様書 §4 の現場ラベル12組（label-NN）は、照合結果だけでなくカメラ・BLE共通の
    /// 読取境界（66桁QR検証、4-2-4@管理コード検証）も通り、QRの品目番号と
    /// 現品票の品番が一致する。#9・#10 は枝番が空白。
    func testSharedLabelPairsPassBothScanBoundaries() throws {
        let labelPairs = try loadSharedMatchingFixtures().cases.filter {
            $0.id.hasPrefix("label-") && $0.expected == "match"
        }
        XCTAssertEqual(labelPairs.count, 12)

        for pair in labelPairs {
            XCTAssertTrue(KanbanQRRecord.isValidScanPayload(pair.qrPayload), pair.id)
            XCTAssertTrue(
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, destination: .sawai),
                pair.id
            )
            // 66桁レコードはモルテンの受理条件（57〜61桁）には決して当てはまらない。
            XCTAssertFalse(MoltenQRRecord.isValidScanPayload(pair.qrPayload), pair.id)
            let record = KanbanQRRecord.parse(pair.qrPayload)
            XCTAssertEqual(
                record?.partNumber,
                CodeMatcher.partNumber(fromBarcode: pair.barcodePayload),
                pair.id
            )
            let expectsBlankSuffix = pair.id.hasPrefix("label-09") || pair.id.hasPrefix("label-10")
            XCTAssertEqual(record?.partSuffix, expectsBlankSuffix ? nil : "02", pair.id)
        }

        // 実測レコードのフィールド解析（数量は×100で記録、工場コードは L / Y / A の3種）。
        func record(_ prefix: String) -> KanbanQRRecord? {
            labelPairs.first { $0.id.hasPrefix(prefix) }.flatMap { KanbanQRRecord.parse($0.qrPayload) }
        }
        let label02 = record("label-02")
        XCTAssertEqual(label02?.cardNumber, "DCLP675340")
        XCTAssertEqual(label02?.deliveryQuantity ?? 0, 12, accuracy: 0.001)
        XCTAssertEqual(label02?.factoryCode, "L")
        XCTAssertEqual(label02?.warehouseCode, "BLBDI")
        XCTAssertEqual(label02?.supplyPointCode, "LLU93")
        let label11 = record("label-11")
        XCTAssertEqual(label11?.deliveryQuantity ?? 0, 336, accuracy: 0.001)
        XCTAssertEqual(label11?.instructedQuantity ?? 0, 336, accuracy: 0.001)
        XCTAssertEqual(label11?.factoryCode, "Y")
        XCTAssertEqual(label11?.supplyPointCode, "LYB14")
        let label12 = record("label-12")
        XCTAssertEqual(label12?.deliveryQuantity ?? 0, 18, accuracy: 0.001)
        XCTAssertEqual(label12?.factoryCode, "A")
        XCTAssertEqual(label12?.warehouseCode, "BAB15")
        XCTAssertEqual(label12?.supplyPointCode, "LAB14")
    }

    func testDifferentPartNumberMismatches() {
        // BCJH-55-81GG (LH) と BCJH-52-81GG (RH) の取り違え
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: qrPayload, barcodePayload: "BCJH-55-81GG@1KVV0C"),
            .mismatch
        )
    }

    func testLotSuffixDifferenceStillMatches() {
        // バーコードの@以降(管理コード)は品番照合に影響しない
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: qrPayload, barcodePayload: "BCJH-52-81GG@ZZZZZZ"),
            .match
        )
    }

    /// どちらの仕向地のレコードでもないQRは、品番を含んでいても一致にしない。
    func testNonStandardQRIsMismatch() {
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: "PART:BCJH-52-81GG;QTY:12", barcodePayload: barcodePayload),
            .mismatch
        )
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: "PART:DFR5-55-8SDA;QTY:30", barcodePayload: barcodePayload),
            .mismatch
        )
    }

    func testEmptyPayloadsMismatch() {
        XCTAssertEqual(CodeMatcher.compare(qrPayload: "", barcodePayload: ""), .mismatch)
        XCTAssertEqual(CodeMatcher.compare(qrPayload: qrPayload, barcodePayload: ""), .mismatch)
    }

    func testFormatPartNumber() {
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "BCJH5281GG", destination: nil),
            "BCJH-52-81GG"
        )
        // モルテンの9桁品番は4-2-3で表記する
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "PAF115422", destination: nil),
            "PAF1-15-422"
        )
        XCTAssertEqual(CodeMatcher.format(partNumber: "ABC", destination: nil), "ABC")
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "ABCDEFGHIJK", destination: nil),
            "ABCDEFGHIJK"
        )
    }

    func testKanbanQRRecordParsesAllFields() {
        let record = KanbanQRRecord.parse(qrPayload)
        XCTAssertEqual(record?.cardNumber, "DCLP675300")
        XCTAssertEqual(record?.partNumber, "BCJH5281GG")
        XCTAssertEqual(record?.partSuffix, "02")
        XCTAssertEqual(record?.deliveryQuantity, 12)
        XCTAssertEqual(record?.instructedQuantity, 12)
        XCTAssertEqual(record?.factoryCode, "L")
        XCTAssertEqual(record?.warehouseCode, "BLBDI")
        XCTAssertEqual(record?.supplyPointCode, "LLU92")
    }

    func testKanbanQRRecordHandlesBlankSuffix() {
        let record = KanbanQRRecord.parse(
            "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
        )
        XCTAssertEqual(record?.partNumber, "DFR55581GA")
        XCTAssertNil(record?.partSuffix)
        XCTAssertEqual(record?.deliveryQuantity, 100)
        XCTAssertEqual(record?.factoryCode, "Y")
        XCTAssertEqual(record?.warehouseCode, "BYBYT")
        XCTAssertEqual(record?.supplyPointCode, "LYB16")
    }

    func testKanbanQRRecordRejectsNonStandardPayload() {
        XCTAssertNil(KanbanQRRecord.parse("PART:BCJH-52-81GG;QTY:12"))
        XCTAssertNil(KanbanQRRecord.parse("SHORT"))
    }

    func testBluetoothScanPayloadValidationRejectsReverseOrderFormats() {
        XCTAssertTrue(KanbanQRRecord.isValidScanPayload(qrPayload))
        XCTAssertFalse(KanbanQRRecord.isValidScanPayload(barcodePayload))
        XCTAssertFalse(KanbanQRRecord.isValidScanPayload(String(qrPayload.prefix(65))))

        XCTAssertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload, destination: .sawai))
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload("KAAA-55-D86B@0Y5U0I", destination: .sawai)
        )
        XCTAssertFalse(TagBarcodeRecord.isValidScanPayload(qrPayload, destination: .sawai))
        XCTAssertFalse(TagBarcodeRecord.isValidScanPayload("BCJH-52-81GG", destination: .sawai))

        XCTAssertTrue(MoltenQRRecord.isValidScanPayload(moltenQRPayload))
        XCTAssertFalse(MoltenQRRecord.isValidScanPayload(barcodePayload))
        XCTAssertFalse(MoltenQRRecord.isValidScanPayload(qrPayload))
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload(moltenBarcodePayload, destination: .molten)
        )
        XCTAssertFalse(TagBarcodeRecord.isValidScanPayload(moltenQRPayload, destination: .molten))
    }

    func testTagBarcodeRecordParsing() {
        let record = TagBarcodeRecord.parse(barcodePayload)
        XCTAssertEqual(record?.partNumber, "BCJH-52-81GG")
        XCTAssertEqual(record?.managementCode, "1N5X0C")

        let noCode = TagBarcodeRecord.parse("BCJH-52-81GG")
        XCTAssertEqual(noCode?.partNumber, "BCJH-52-81GG")
        XCTAssertNil(noCode?.managementCode)

        XCTAssertNil(TagBarcodeRecord.parse("  "))
    }

    // MARK: - 仕向地 モルテン

    /// 仕向地モルテンの実データ7組は、照合結果だけでなく読取境界
    /// （61桁QR検証、4-2-3/4-2-4@管理コード検証）も通り、仕向地判定も安定する。
    func testSharedMoltenPairsPassBothScanBoundaries() throws {
        let moltenPairs = try loadSharedMatchingFixtures().cases.filter {
            $0.id.hasPrefix("molten-") && $0.expected == "match"
        }
        XCTAssertEqual(moltenPairs.count, 7)

        for pair in moltenPairs {
            XCTAssertTrue(MoltenQRRecord.isValidScanPayload(pair.qrPayload), pair.id)
            XCTAssertTrue(
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, destination: .molten),
                pair.id
            )
            XCTAssertEqual(Destination.detect(qrPayload: pair.qrPayload), .molten, pair.id)
            let record = MoltenQRRecord.parse(pair.qrPayload)
            XCTAssertEqual(
                record?.partNumber,
                CodeMatcher.partNumber(fromBarcode: pair.barcodePayload),
                pair.id
            )
            // PAF1系だけが9桁品番（現品票では4-2-3表記）。
            XCTAssertEqual(record?.partNumber.count, pair.id.contains("PAF1") ? 9 : 10, pair.id)
        }
    }

    func testDestinationDetectStripsOnlyTransportTerminators() {
        XCTAssertEqual(Destination.detect(qrPayload: moltenQRPayload + "\r\n"), .molten)
        XCTAssertEqual(Destination.detect(qrPayload: qrPayload + "\r\n"), .sawai)
        // 澤井製作所のレコードは前後に空白を持たないので、空白付きの読取値も従来どおり通す。
        // モルテンのレコードは空白まで含めて61桁なので、同じ空白があると桁数超過になる。
        XCTAssertEqual(Destination.detect(qrPayload: " \(qrPayload) \n"), .sawai)
        XCTAssertNil(Destination.detect(qrPayload: " \(moltenQRPayload) "))

        XCTAssertNil(Destination.detect(qrPayload: "PART:BCJH-52-81GG;QTY:12"))
        XCTAssertNil(Destination.detect(qrPayload: String(repeating: "X", count: 66)))
        // 57桁に満たない読取値は補完しない
        let tooShort = String(moltenQRPayload.dropLast(5))
        XCTAssertEqual(tooShort.count, 56)
        XCTAssertNil(Destination.detect(qrPayload: tooShort))
        // 先頭の空白は削らないため、桁がずれたレコードは受理しない
        let shifted = " " + String(moltenQRPayload.dropLast())
        XCTAssertEqual(shifted.count, 61)
        XCTAssertNil(Destination.detect(qrPayload: shifted))

        // JAMA自己記述形式は桁数に関係なくデンソーとして判定する
        XCTAssertEqual(Destination.detect(qrPayload: densoQRPayload), .denso)
        XCTAssertEqual(Destination.detect(qrPayload: densoQRPayload + "\r\n"), .denso)
        XCTAssertEqual(Destination.detect(qrPayload: densoQRPayload.lowercased()), .denso)
        XCTAssertNil(Destination.detect(qrPayload: String(densoQRPayload.dropLast())))
    }

    func testMoltenQRRecordParsesAllFields() {
        XCTAssertEqual(moltenShortPartQRPayload.count, 61)
        let record = MoltenQRRecord.parse(moltenShortPartQRPayload)
        XCTAssertEqual(record?.ordererCode, "AK6805")
        XCTAssertEqual(record?.partNumber, "PAF115422")
        XCTAssertEqual(record?.deliveryNumber, "UAG5560")
        XCTAssertEqual(record?.deliveryDestination, "FA2")
        XCTAssertEqual(record?.tyLocation, "P59")
        XCTAssertEqual(record?.supplyPoint, "01FEM")
        XCTAssertEqual(record?.packQuantity, 120)
        XCTAssertEqual(record?.instructionDate, "0908")
        XCTAssertEqual(record?.instructionTime, "0000")
        XCTAssertEqual(record?.canonicalPayload, moltenShortPartQRPayload)
    }

    func testMoltenQRRecordHandlesBlankOptionalFields() {
        XCTAssertEqual(moltenQRPayload.count, 61)
        let record = MoltenQRRecord.parse(moltenQRPayload)
        XCTAssertEqual(record?.partNumber, "D10E50N10B")
        XCTAssertEqual(record?.deliveryNumber, "U543820")
        XCTAssertEqual(record?.deliveryDestination, "MB")
        XCTAssertNil(record?.tyLocation)
        XCTAssertEqual(record?.supplyPoint, "S6007")
        XCTAssertEqual(record?.packQuantity, 2)
        XCTAssertEqual(record?.instructionDate, "0908")
        XCTAssertNil(record?.instructionTime)
    }

    func testMoltenQRRecordPadsStrippedTrailingSpaces() {
        let stripped = String(moltenQRPayload.dropLast(4))
        XCTAssertEqual(stripped.count, 57)
        XCTAssertEqual(
            MoltenQRRecord.parse(stripped)?.canonicalPayload,
            MoltenQRRecord.parse(moltenQRPayload)?.canonicalPayload
        )
        XCTAssertEqual(MoltenQRRecord.canonicalize(stripped), moltenQRPayload)

        XCTAssertNil(MoltenQRRecord.parse(String(moltenQRPayload.dropLast(5))))
        XCTAssertNil(MoltenQRRecord.parse(moltenQRPayload + " "))
    }

    func testMoltenQRRecordRejectsInvalidFields() {
        // 7-16桁(部品番号)は左詰めなので、先頭が空白のレコードは受理しない
        XCTAssertNil(MoltenQRRecord.parse(replacing(moltenQRPayload, at: 6, with: " ")))
        // 47-53桁(収容数)は数字のみ
        XCTAssertNil(MoltenQRRecord.parse(replacing(moltenQRPayload, at: 46, with: "X")))
        // 58-61桁(時刻)は4桁の数字か空白4桁のどちらか
        let brokenTime = String(moltenShortPartQRPayload.dropLast(4)) + "00 0"
        XCTAssertEqual(brokenTime.count, 61)
        XCTAssertNil(MoltenQRRecord.parse(brokenTime))
        // 小文字で通知された読取値は大文字化して受理する
        XCTAssertEqual(
            MoltenQRRecord.parse(moltenQRPayload.lowercased())?.canonicalPayload,
            moltenQRPayload
        )
    }

    func testTagBarcodeRecordAcceptsFourTwoThreeOnlyForMolten() {
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, destination: .molten)
        )
        XCTAssertFalse(
            TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, destination: .sawai)
        )
        // 仕向地未判定のときは、どちらの仕向地の現品票も取りこぼさない
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, destination: nil)
        )

        let destinations: [Destination?] = [.sawai, .molten, nil]
        for destination in destinations {
            XCTAssertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload, destination: destination))
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload("PAF1-15-42@0NKD3C", destination: destination)
            )
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload("PAF1-15-42200@0NKD3C", destination: destination)
            )
        }
    }

    func testPartNumberFromQRIsDestinationAware() {
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: moltenShortPartQRPayload), "PAF115422")
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: moltenQRPayload), "D10E50N10B")
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: qrPayload), "BCJH5281GG")
        // カード番号らしき先頭20桁だけでは、もう仕向地を判定できないので抽出しない
        XCTAssertNil(CodeMatcher.partNumber(fromQR: String(qrPayload.prefix(20))))
        // 空白付きで通知された澤井製作所の読取値は従来どおり照合できる
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: " \(qrPayload.lowercased())\n"), "BCJH5281GG")
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: " \(qrPayload.lowercased())\n", barcodePayload: barcodePayload),
            .match
        )

        // デンソーは固定位置ではなく、JAMAレコードの項目104から取り出す。
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: densoQRPayload), "8601507722")
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: densoQRPayload, barcodePayload: densoBarcodePayload),
            .match
        )
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: densoQRPayload, barcodePayload: "860150-7791@01335C"),
            .mismatch
        )
    }

    func testBoxIdentityPerDestination() {
        // 澤井製作所はカード番号で箱が決まるため、現品票は箱の識別に使わない
        let sawaiIdentity = BoxIdentity.make(qrPayload: qrPayload, barcodePayload: barcodePayload)
        XCTAssertEqual(sawaiIdentity, qrPayload.uppercased())
        XCTAssertEqual(BoxIdentity.make(qrPayload: qrPayload, barcodePayload: nil), sawaiIdentity)
        XCTAssertEqual(
            BoxIdentity.make(qrPayload: qrPayload, barcodePayload: "BCJH-52-81GG@ZZZZZZ"),
            sawaiIdentity
        )

        // モルテンのQRは1品番1レコードなので、現品票の管理コードまで含めて箱を識別する
        let firstBox = BoxIdentity.make(
            qrPayload: moltenQRPayload,
            barcodePayload: moltenBarcodePayload
        )
        XCTAssertNotNil(firstBox)
        XCTAssertNotEqual(
            firstBox,
            BoxIdentity.make(qrPayload: moltenQRPayload, barcodePayload: "D10E-50-N10B@0UXL0K")
        )
        // 末尾空白が落ちた読取値でも同じ箱として扱う
        XCTAssertEqual(
            BoxIdentity.make(
                qrPayload: String(moltenQRPayload.dropLast(4)),
                barcodePayload: moltenBarcodePayload
            ),
            firstBox
        )
        XCTAssertNil(BoxIdentity.make(qrPayload: moltenQRPayload, barcodePayload: nil))
        XCTAssertNil(BoxIdentity.make(qrPayload: moltenQRPayload, barcodePayload: ""))

        XCTAssertNil(
            BoxIdentity.make(qrPayload: "PART:BCJH-52-81GG;QTY:12", barcodePayload: barcodePayload)
        )

        // デンソーはかんばん連番(項目152)が箱ごとに違うので、澤井製作所と同じく
        // QR単体で箱が決まり、現品票は箱の識別に使わない。
        let densoIdentity = BoxIdentity.make(
            qrPayload: densoQRPayload,
            barcodePayload: densoBarcodePayload
        )
        XCTAssertEqual(densoIdentity, densoQRPayload)
        XCTAssertEqual(
            BoxIdentity.make(qrPayload: densoQRPayload, barcodePayload: nil),
            densoIdentity
        )
        XCTAssertEqual(
            BoxIdentity.make(qrPayload: densoQRPayload, barcodePayload: "860150-7722@1DZB0O"),
            densoIdentity
        )
    }


    // MARK: - 仕向地 デンソー

    /// 仕向地デンソーの実データ7組は、照合結果だけでなく読取境界
    /// （JAMA自己記述レコードの解析、6-4@管理コード検証）も通り、仕向地判定も安定する。
    func testSharedDensoPairsPassBothScanBoundaries() throws {
        let densoPairs = try loadSharedMatchingFixtures().cases.filter {
            $0.id.hasPrefix("denso-") && $0.expected == "match"
        }
        XCTAssertEqual(densoPairs.count, 7)

        for pair in densoPairs {
            XCTAssertEqual(pair.qrPayload.count, 221, pair.id)
            XCTAssertTrue(DensoKanbanRecord.isValidScanPayload(pair.qrPayload), pair.id)
            XCTAssertTrue(
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, destination: .denso),
                pair.id
            )
            // 6-4の現品票は他の仕向地のセッションでは受理しない。
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, destination: .sawai),
                pair.id
            )
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, destination: .molten),
                pair.id
            )
            // 221桁のかんばんは澤井製作所(66桁)・モルテン(57〜61桁)には決して当てはまらない。
            XCTAssertFalse(KanbanQRRecord.isValidScanPayload(pair.qrPayload), pair.id)
            XCTAssertFalse(MoltenQRRecord.isValidScanPayload(pair.qrPayload), pair.id)
            XCTAssertEqual(Destination.detect(qrPayload: pair.qrPayload), .denso, pair.id)

            let record = DensoKanbanRecord.parse(pair.qrPayload)
            XCTAssertEqual(
                record?.partNumber,
                CodeMatcher.partNumber(fromBarcode: pair.barcodePayload),
                pair.id
            )
            XCTAssertEqual(record?.partNumber.count, 10, pair.id)
            // 現品票は品番を6-4で印字する。
            XCTAssertEqual(
                CodeMatcher.format(partNumber: record?.partNumber ?? "", destination: .denso),
                TagBarcodeRecord.parse(pair.barcodePayload)?.partNumber,
                pair.id
            )
        }

        // かんばん連番が箱ごとに違うので、7枚は7箱として区別できる（2品番）。
        let serials = Set(densoPairs.compactMap { DensoKanbanRecord.parse($0.qrPayload)?.kanbanSerial })
        XCTAssertEqual(serials.count, 7)
        let identities = Set(densoPairs.compactMap {
            BoxIdentity.make(qrPayload: $0.qrPayload, barcodePayload: $0.barcodePayload)
        })
        XCTAssertEqual(identities.count, 7)
        let partNumbers = Set(densoPairs.compactMap { CodeMatcher.partNumber(fromQR: $0.qrPayload) })
        XCTAssertEqual(partNumbers, ["8601507722", "8601507791"])
    }

    func testDensoKanbanRecordParsesAllItemsFromRealPayload() {
        XCTAssertEqual(densoQRPayload.count, 221)
        let record = DensoKanbanRecord.parse(densoQRPayload)

        XCTAssertEqual(record?.version, "5")
        XCTAssertEqual(record?.preamble, "5000001021")
        XCTAssertEqual(record?.orderedItems.count, 21)
        XCTAssertEqual(
            record?.orderedItems.map(\.id),
            [
                "100", "104", "111", "112", "121", "124", "127", "141", "142", "144", "152",
                "402", "515", "516", "519", "520", "521", "526", "523", "522", "401"
            ]
        )
        XCTAssertEqual(record?.formType, "20")
        XCTAssertEqual(record?.partNumber, "8601507722")
        XCTAssertEqual(record?.packagingCode, "00")
        XCTAssertEqual(record?.packQuantity, 24)
        XCTAssertEqual(record?.nextProcess, "D850")
        XCTAssertEqual(record?.instructionCode, "C01008-45")
        XCTAssertEqual(record?.kanbanSerial, "0140")
        XCTAssertEqual(record?.managementNumber, "SWS")
        XCTAssertEqual(record?.deliveryDate, "20260908")
        XCTAssertEqual(record?.formattedDeliveryDate, "2026/09/08")
        XCTAssertEqual(record?.deliveryRun, "S001")
        XCTAssertEqual(record?.instructedQuantity, 72)
        XCTAssertEqual(record?.itemNumber, "9924543330")
        XCTAssertEqual(record?.receivingCode, "M6")
        // 空欄の項目は桁数のまま生値で保持する。
        XCTAssertEqual(record?.items["144"], "      ")
        XCTAssertEqual(record?.items["142"], "M")
        XCTAssertEqual(record?.canonicalPayload, densoQRPayload)

        // 小文字で通知された読取値は大文字化して受理する。
        XCTAssertEqual(
            DensoKanbanRecord.parse(densoQRPayload.lowercased())?.canonicalPayload,
            densoQRPayload
        )
        XCTAssertEqual(DensoKanbanRecord.canonicalize(densoQRPayload), densoQRPayload)
        XCTAssertTrue(DensoKanbanRecord.isValidScanPayload(densoQRPayload))
        XCTAssertFalse(DensoKanbanRecord.isValidScanPayload(qrPayload))
        XCTAssertFalse(DensoKanbanRecord.isValidScanPayload(moltenQRPayload))
        XCTAssertFalse(DensoKanbanRecord.isValidScanPayload(densoBarcodePayload))
    }

    /// 汎用パーサであること（項目数・並び・ヘッダ長が変わっても解析できる）と、
    /// デンソーを最初に判定していることを同時に固定する。
    func testDensoKanbanRecordParsesDifferentItemLayout() {
        let record = DensoKanbanRecord.parse(densoSawaiLengthPayload)
        XCTAssertEqual(record?.orderedItems.map(\.id), ["152", "104", "112"])
        XCTAssertEqual(record?.partNumber, "8601507722")
        XCTAssertEqual(record?.packQuantity, 24)
        XCTAssertEqual(record?.kanbanSerial, "0140")
        // 宣言されていない項目は無いだけで、解析は成立する。
        XCTAssertNil(record?.formType)
        XCTAssertNil(record?.deliveryDate)
        XCTAssertNil(record?.formattedDeliveryDate)
        XCTAssertNil(record?.instructedQuantity)

        // 66桁は澤井製作所の完全なレコードにも見えるが、デンソーとして判定する。
        XCTAssertEqual(densoSawaiLengthPayload.count, 66)
        XCTAssertTrue(KanbanQRRecord.isValidScanPayload(densoSawaiLengthPayload))
        XCTAssertEqual(Destination.detect(qrPayload: densoSawaiLengthPayload), .denso)

        // 61桁はモルテンの完全なレコードにも見えるが、やはりデンソーとして判定する。
        XCTAssertEqual(densoMoltenLengthPayload.count, 61)
        XCTAssertTrue(MoltenQRRecord.isValidScanPayload(densoMoltenLengthPayload))
        XCTAssertEqual(Destination.detect(qrPayload: densoMoltenLengthPayload), .denso)
        XCTAssertEqual(
            DensoKanbanRecord.parse(densoMoltenLengthPayload)?.kanbanSerial,
            "0140123456"
        )
        XCTAssertEqual(CodeMatcher.partNumber(fromQR: densoMoltenLengthPayload), "8601507722")
    }

    func testDensoKanbanRecordRejectsBrokenHeaderOrDataLength() {
        XCTAssertNil(DensoKanbanRecord.parse(""))
        XCTAssertNil(DensoKanbanRecord.parse("JAMA"))

        func replacingHeader(_ value: String) -> String {
            String(densoQRPayload.prefix(5)) + value + String(densoQRPayload.dropFirst(9))
        }

        // 様式が違う / 版とヘッダ長が数字でない。
        XCTAssertNil(DensoKanbanRecord.parse("JAMB" + densoQRPayload.dropFirst(4)))
        XCTAssertNil(DensoKanbanRecord.parse("JAMAX" + densoQRPayload.dropFirst(5)))
        XCTAssertNil(DensoKanbanRecord.parse(replacingHeader("01X9")))
        // ヘッダがペイロードより長い。
        XCTAssertNil(DensoKanbanRecord.parse(replacingHeader("9999")))
        // 119 - 14 = 105 は項目定義の整数個だが、118 - 14 = 104 は違う。
        XCTAssertNil(DensoKanbanRecord.parse(replacingHeader("0118")))
        // 整数個でも、桁数の合計がデータ部と合わなければ受理しない。
        XCTAssertNil(DensoKanbanRecord.parse(replacingHeader("0114")))
        // データ部の過不足。
        XCTAssertNil(DensoKanbanRecord.parse(String(densoQRPayload.dropLast())))
        XCTAssertNil(DensoKanbanRecord.parse(densoQRPayload + "0"))

        // 項目番号の重複は解釈が定まらない。
        let duplicateItemID = "JAMA5" + "0034" + "5000000004"
            + "10410" + "10410" + "11207" + "15210"
            + "8601507722" + "8601507722" + "0000024" + "0140123456"
        XCTAssertNil(DensoKanbanRecord.parse(duplicateItemID))

        // 必須項目: 104(部品番号)・112(収容数)・152(かんばん連番)。
        let withoutPartNumber = "JAMA5" + "0024" + "5000000002"
            + "11207" + "15210" + "0000024" + "0140123456"
        XCTAssertNil(DensoKanbanRecord.parse(withoutPartNumber))
        let withoutPackQuantity = "JAMA5" + "0024" + "5000000002"
            + "10410" + "15210" + "8601507722" + "0140123456"
        XCTAssertNil(DensoKanbanRecord.parse(withoutPackQuantity))
        let nonNumericPackQuantity = "JAMA5" + "0029" + "5000000003"
            + "15210" + "10410" + "11212"
            + "0140      " + "8601507722" + "00000000002X"
        XCTAssertNil(DensoKanbanRecord.parse(nonNumericPackQuantity))
        let blankKanbanSerial = "JAMA5" + "0029" + "5000000003"
            + "15210" + "10410" + "11212"
            + "          " + "8601507722" + "000000000024"
        XCTAssertNil(DensoKanbanRecord.parse(blankKanbanSerial))

        // 壊れたかんばんを他の仕向地へ降格させない。
        XCTAssertNil(Destination.detect(qrPayload: String(densoQRPayload.dropLast())))
        XCTAssertNil(CodeMatcher.partNumber(fromQR: String(densoQRPayload.dropLast())))
        XCTAssertNil(
            BoxIdentity.make(
                qrPayload: String(densoQRPayload.dropLast()),
                barcodePayload: densoBarcodePayload
            )
        )
    }

    func testTagBarcodeRecordAcceptsSixFourOnlyForDenso() {
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, destination: .denso)
        )
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload("860150-7722@1dz50o", destination: .denso)
        )
        // 仕向地未判定のときは、どの仕向地の現品票も取りこぼさない。
        XCTAssertTrue(TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, destination: nil))
        XCTAssertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload, destination: nil))
        XCTAssertTrue(
            TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, destination: nil)
        )

        // 6-4は澤井製作所・モルテンのセッションでは拒否し、
        // 4-2-4 / 4-2-3はデンソーのセッションでは拒否する。
        XCTAssertFalse(
            TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, destination: .sawai)
        )
        XCTAssertFalse(
            TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, destination: .molten)
        )
        XCTAssertFalse(TagBarcodeRecord.isValidScanPayload(barcodePayload, destination: .denso))
        XCTAssertFalse(
            TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, destination: .denso)
        )

        let destinations: [Destination?] = [.sawai, .molten, .denso, nil]
        for destination in destinations {
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload("860150-772@1DZ50O", destination: destination)
            )
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload("86015-7722@1DZ50O", destination: destination)
            )
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload("8601507722@1DZ50O", destination: destination)
            )
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload("860150-7722", destination: destination)
            )
            XCTAssertFalse(
                TagBarcodeRecord.isValidScanPayload(densoQRPayload, destination: destination)
            )
        }
    }

    func testFormatPartNumberIsDestinationAware() {
        // 6-4になるのはデンソーだけ。他の仕向地は従来の4-2-4 / 4-2-3のまま。
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "8601507722", destination: .denso),
            "860150-7722"
        )
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "8601507722", destination: .sawai),
            "8601-50-7722"
        )
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "8601507722", destination: .molten),
            "8601-50-7722"
        )
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "8601507722", destination: nil),
            "8601-50-7722"
        )
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "BCJH5281GG", destination: .sawai),
            "BCJH-52-81GG"
        )
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "PAF115422", destination: .molten),
            "PAF1-15-422"
        )
        // デンソーは10桁以外を整形しない。
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "PAF115422", destination: .denso),
            "PAF115422"
        )
        XCTAssertEqual(CodeMatcher.format(partNumber: "ABC", destination: .denso), "ABC")
        XCTAssertEqual(
            CodeMatcher.format(partNumber: "ABCDEFGHIJK", destination: .denso),
            "ABCDEFGHIJK"
        )
    }

    private func replacing(_ payload: String, at index: Int, with character: Character) -> String {
        var characters = Array(payload)
        characters[index] = character
        return String(characters)
    }
}
