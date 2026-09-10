import XCTest
@testable import CodeMatch

/// 検品レポートの行の作り方を、現場ラベルの実データで固定する。
/// 澤井製作所は品番+枝番、モルテンは納品番号、デンソーは品番が1行になり、行は品番順に並ぶ。
final class InspectionReportTests: XCTestCase {
    private let sawaiQR5281 = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let sawaiQR5581 = "DCLP675340BCJH5581GG020000120000001200L000000000000BLBDILLU93   0*"
    private let sawaiQRNoSuffix = "DAYA004770DFR55281GA  0001000000010000Y      000000BYBYTLYB15   0*"
    private let moltenQRD10E = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private let moltenQRPAF1 = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    // 納品番号 U009740 は U011230 より小さいが、品番 PAF115423 は D10E50N10B より後ろ。
    private let moltenQRPAF1423 = "AK6805PAF115423          U009740000MDCU4TS6030000012009081330"
    private let moltenQRD10EMDT = "AK6805D10E50N10B         U011230000MDTD  S6030000002009081330"
    private let densoQRKanban0140 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private let densoQRKanban0141 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0141SWS    20260908S0010000720000009924543330454333M6"

    private let startedAt = Date(timeIntervalSince1970: 1_700_000_000)

    func testSawaiRowsAreKeyedByPartNumberAndSuffixAndSortedByPartNumber() throws {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                entry("BCJH-55-81GG", sawaiQR5581, "BCJH-55-81GG@1KVV0C"),
                entry("BCJH-52-81GG", sawaiQR5281, "BCJH-52-81GG@1N5X0C"),
                entry("BCJH-52-81GG", sawaiQR5281, "BCJH-52-81GG@1N5X0D"),
                entry("DFR5-52-81GA", sawaiQRNoSuffix, "DFR5-52-81GA@001F2S")
            ],
            destination: .sawai
        )
        let expectedQuantity = try XCTUnwrap(KanbanQRRecord.parse(sawaiQR5281)?.deliveryQuantity)

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.layout, .sawai)
        XCTAssertEqual(report.rows.map(\.keyText), ["BCJH5281GG (02)", "BCJH5581GG (02)", "DFR55281GA"])
        let first = try XCTUnwrap(report.rows.first)
        XCTAssertEqual(first.boxCount, 2)
        XCTAssertEqual(first.quantityPerBox, expectedQuantity)
        XCTAssertEqual(first.totalQuantity, expectedQuantity * 2)
        XCTAssertNil(first.partNumber)
        XCTAssertNil(first.deliveryDestination)
        XCTAssertFalse(report.rows.contains(where: \.isUnparsed))
        XCTAssertEqual(report.rows.reduce(0) { $0 + $1.boxCount }, session.matchedCount)
    }

    func testMoltenRowsAreOnePerDeliveryNumberWithRawPartAndDeliveryPoint() throws {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                entry("PAF1-15-422", moltenQRPAF1, "PAF1-15-422@0NKD3C"),
                entry("PAF1-15-422", moltenQRPAF1, "PAF1-15-422@0NLL3C"),
                entry("D10E-50-N10B", moltenQRD10E, "D10E-50-N10B@0UBL00")
            ],
            destination: .molten
        )

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.layout, .molten)
        XCTAssertEqual(report.rows.map(\.keyText), ["U543820", "UAG5560"])
        let paf1 = report.rows[1]
        XCTAssertEqual(paf1.partNumber, "PAF115422")
        XCTAssertEqual(paf1.deliveryDestination, "FA2")
        XCTAssertEqual(paf1.boxCount, 2)
        XCTAssertEqual(paf1.quantityPerBox, 120)
        XCTAssertEqual(paf1.totalQuantity, 240)
        let d10e = report.rows[0]
        XCTAssertEqual(d10e.partNumber, "D10E50N10B")
        XCTAssertEqual(d10e.deliveryDestination, "MB")
        XCTAssertEqual(d10e.boxCount, 1)
        XCTAssertEqual(d10e.totalQuantity, 2)
    }

    func testMoltenRowsSortByPartNumberBeforeDeliveryNumberSoOnePartStaysTogether() {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                entry("PAF1-15-423", moltenQRPAF1423, "PAF1-15-423@0N5R3C"),
                entry("PAF1-15-422", moltenQRPAF1, "PAF1-15-422@0NKD3C"),
                entry("D10E-50-N10B", moltenQRD10E, "D10E-50-N10B@0UBL00"),
                entry("D10E-50-N10B", moltenQRD10EMDT, "D10E-50-N10B@0UK60K")
            ],
            destination: .molten
        )

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.rows.map(\.keyText), ["U011230", "U543820", "UAG5560", "U009740"])
        XCTAssertEqual(report.rows.map(\.partNumber), ["D10E50N10B", "D10E50N10B", "PAF115422", "PAF115423"])
    }

    func testDensoRowsAreOnePerPartNumberFormattedSixFour() throws {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                entry("860150-7722", densoQRKanban0140, "860150-7722@1DZ50O"),
                entry("860150-7722", densoQRKanban0141, "860150-7722@1DZB0O")
            ],
            destination: .denso
        )

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.layout, .denso)
        XCTAssertEqual(report.rowCount, 1)
        let row = try XCTUnwrap(report.rows.first)
        XCTAssertEqual(row.keyText, "860150-7722")
        XCTAssertEqual(row.boxCount, 2)
        XCTAssertEqual(row.quantityPerBox, 24)
        XCTAssertEqual(row.totalQuantity, 48)
    }

    func testBoxesWithoutAParsableQRTrailAsUnparsedRowsSoNoBoxIsDropped() throws {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                entry("ZZZZ-00-0000", nil, nil),
                entry("BCJH-52-81GG", sawaiQR5281, "BCJH-52-81GG@1N5X0C"),
                entry("AAAA-11-1111", "legacy payload", "AAAA-11-1111@X")
            ],
            destination: .sawai
        )

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.rows.map(\.keyText), ["BCJH5281GG (02)", "AAAA-11-1111", "ZZZZ-00-0000"])
        let legacy = try XCTUnwrap(report.rows.last)
        XCTAssertTrue(legacy.isUnparsed)
        XCTAssertEqual(legacy.boxCount, 1)
        XCTAssertNil(legacy.quantityPerBox)
        XCTAssertNil(legacy.totalQuantity)
        XCTAssertEqual(report.rows.reduce(0) { $0 + $1.boxCount }, session.matchedCount)
    }

    func testSessionWithoutDestinationFallsBackToSawaiLayoutWithEveryBoxUnparsed() {
        let session = MatchSession(startedAt: startedAt, entries: [entry("PART-1", nil, nil)])

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.layout, .sawai)
        XCTAssertTrue(report.rows.allSatisfy(\.isUnparsed))
    }

    func testADensoKanbanIsNeverReadAsASawaiSlip() throws {
        // 寛容な澤井の解析は JAMA のQRもカード番号として受理する。行は解析器ではなく仕向地で決める。
        let session = MatchSession(
            startedAt: startedAt,
            entries: [entry("860150-7722", densoQRKanban0140, "860150-7722@1DZ50O")],
            destination: .sawai
        )

        let report = InspectionReport.make(session: session)

        XCTAssertEqual(report.rows.map(\.keyText), ["860150-7722"])
        XCTAssertTrue(try XCTUnwrap(report.rows.first).isUnparsed)
    }

    private func entry(_ code: String, _ qrPayload: String?, _ barcodePayload: String?) -> MatchHistoryEntry {
        MatchHistoryEntry(code: code, matchedAt: startedAt, qrPayload: qrPayload, barcodePayload: barcodePayload)
    }
}
