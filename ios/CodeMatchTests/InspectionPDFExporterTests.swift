import PDFKit
import XCTest
@testable import CodeMatch

/// 検品レポートPDFの本文を検証する。表のセルは1つずつ描くので、PDFKitの抽出テキストでは
/// 各セルの文字列を個別に突き合わせる（全角スペースは半角に、連続空白は1つにまとめられる）。
final class InspectionPDFExporterTests: XCTestCase {
    private let sawaiQR5281 = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let sawaiQR5581 = "DCLP675340BCJH5581GG020000120000001200L000000000000BLBDILLU93   0*"
    private let moltenQRD10E = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private let moltenQRPAF1 = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private let densoQRKanban0140 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"

    private let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
    private let locale = Locale(identifier: "ja_JP")

    func testSawaiReportPrintsHeaderCountsColumnsAndOneRowPerPartNumberWithSuffix() throws {
        let session = MatchSession(
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(120),
            entries: [
                entry("BCJH-55-81GG", sawaiQR5581, "BCJH-55-81GG@1KVV0C"),
                entry("BCJH-52-81GG", sawaiQR5281, "BCJH-52-81GG@1N5X0C"),
                entry("BCJH-52-81GG", sawaiQR5281, "BCJH-52-81GG@1N5X0D")
            ],
            name: "朝便",
            destination: .sawai
        )

        let text = try pdfText(for: session)

        assertContains("検品レポート", in: text)
        assertContains("セッション名: 朝便", in: text)
        assertContains("仕向地: 澤井製作所", in: text)
        assertContains("検査箱数: 3箱", in: text)
        assertContains("品番数（枝番別）: 2", in: text)
        XCTAssertFalse(text.contains("納品番号数"))
        for header in ["No", "品番", "箱数", "数量計", "確認"] {
            assertContains(header, in: text)
        }
        assertContainsIgnoringSpaces("納入数量/箱", in: text)
        assertContains("BCJH5281GG (02)", in: text)
        assertContains("BCJH5581GG (02)", in: text)
        XCTAssertFalse(text.contains("BCJH-52-81GG"), "解析できた行は紙の表記（ハイフンなし）で出す")
        assertContains("検品表にあってこの一覧にない品番は、このセッションで照合されていません。", in: text)
        assertContains("1 / 1", in: text)
        // 2箱の行は 箱数 2・数量/箱 12・数量計 24 が並ぶ
        let rowLine = try XCTUnwrap(text.split(separator: "\n").first { $0.contains("BCJH5281GG (02)") }.map(String.init))
        XCTAssertTrue(rowLine.contains("24"), "数量計が同じ行にありません: \(rowLine)")
    }

    func testMoltenReportPrintsOneRowPerDeliveryNumberWithDeliveryPoint() throws {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                entry("PAF1-15-422", moltenQRPAF1, "PAF1-15-422@0NKD3C"),
                entry("D10E-50-N10B", moltenQRD10E, "D10E-50-N10B@0UBL00"),
                entry("PAF1-15-422", moltenQRPAF1, "PAF1-15-422@0NLL3C")
            ],
            destination: .molten
        )

        let text = try pdfText(for: session)

        assertContains("仕向地: モルテン", in: text)
        assertContains("状態: 照合中", in: text)
        assertContains("検査箱数: 3箱", in: text)
        assertContains("納品番号数: 2", in: text)
        XCTAssertFalse(text.contains("品番数"))
        for header in ["納品番号", "納入先", "累計"] {
            assertContains(header, in: text)
        }
        assertContainsIgnoringSpaces("収容数/箱", in: text)
        assertContains("UAG5560", in: text)
        assertContains("PAF115422", in: text)
        assertContains("FA2", in: text)
        assertContains("U543820", in: text)
        assertContains("D10E50N10B", in: text)
        assertContains("240", in: text)
    }

    func testDensoReportPrintsOneRowPerPartNumberWithoutInstructedQuantity() throws {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [entry("860150-7722", densoQRKanban0140, "860150-7722@1DZ50O")],
            destination: .denso
        )

        let text = try pdfText(for: session)

        assertContains("仕向地: デンソー", in: text)
        assertContains("品番数: 1", in: text)
        XCTAssertFalse(text.contains("枝番別"))
        XCTAssertFalse(text.contains("指示数"))
        assertContains("860150-7722", in: text)
        assertContainsIgnoringSpaces("収容数/箱", in: text)
        assertContains("24", in: text)
    }

    func testEmptySessionPrintsTheNoMatchesLine() throws {
        let text = try pdfText(for: MatchSession(startedAt: startedAt))

        assertContains("検品レポート", in: text)
        assertContains("一致したコードはありません。", in: text)
        XCTAssertFalse(text.contains("確認"))
    }

    func testLongReportRepeatsTheTableHeaderOnEveryPageAndNumbersPages() throws {
        // 品番だけを変えた120枚の澤井の納品書。カード番号は同じでも品番が違えば別の行になる。
        let entries = (0..<120).map { index -> MatchHistoryEntry in
            let part = "BCJH" + String(format: "%04d", index) + "GG"
            let code = "\(part.prefix(4))-\(part.dropFirst(4).prefix(2))-\(part.dropFirst(6))"
            return entry(
                code,
                "DCLP675300" + part + "020000120000001200L000000000000BLBDILLU92   0*",
                "\(code)@1N5X0C"
            )
        }
        let session = MatchSession(startedAt: startedAt, entries: entries, destination: .sawai)

        let document = try pdfDocument(for: session)

        XCTAssertGreaterThanOrEqual(document.pageCount, 3)
        for pageIndex in 0..<document.pageCount {
            let pageText = try XCTUnwrap(document.page(at: pageIndex)?.string)
            XCTAssertTrue(pageText.contains("確認"), "ページ \(pageIndex + 1) に表ヘッダーがありません")
            XCTAssertTrue(
                pageText.contains("\(pageIndex + 1) / \(document.pageCount)"),
                "ページ \(pageIndex + 1) にページ番号がありません"
            )
        }
        let lastPage = try XCTUnwrap(document.page(at: document.pageCount - 1)?.string)
        XCTAssertTrue(lastPage.contains("BCJH0119GG"))
    }

    func testFileNameIsDestinationAndStartTimeNeverTheSessionName() {
        let session = MatchSession(startedAt: startedAt, name: "morning/09:00 run", destination: .sawai)
        let start = SessionPDFExporter.sanitizedFileNamePart(AppLanguage(locale).formatDateTime(startedAt))

        let inspection = InspectionPDFExporter.fileName(for: session, locale: locale)
        let noDestination = InspectionPDFExporter.fileName(for: MatchSession(startedAt: startedAt, name: "morning"), locale: locale)
        let history = SessionPDFExporter.fileName(for: session, locale: locale)

        XCTAssertEqual(inspection, "検品レポート_澤井製作所_\(start).pdf")
        XCTAssertEqual(noDestination, "検品レポート_\(start).pdf")
        XCTAssertEqual(history, "照合履歴レポート_澤井製作所_\(start).pdf")
        XCTAssertFalse(inspection.contains("morning"))
        XCTAssertFalse(inspection.contains("/"))
    }

    // MARK: - Helpers

    private func entry(_ code: String, _ qrPayload: String?, _ barcodePayload: String?) -> MatchHistoryEntry {
        MatchHistoryEntry(code: code, matchedAt: startedAt, qrPayload: qrPayload, barcodePayload: barcodePayload)
    }

    private func pdfDocument(
        for session: MatchSession,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> PDFDocument {
        let data = InspectionPDFExporter.generatePDF(for: session, locale: locale)
        return try XCTUnwrap(PDFDocument(data: data), "PDFを解析できませんでした", file: file, line: line)
    }

    private func pdfText(
        for session: MatchSession,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> String {
        let document = try pdfDocument(for: session, file: file, line: line)
        return (0..<document.pageCount)
            .compactMap { document.page(at: $0)?.string }
            .joined(separator: "\n")
    }

    private func assertContains(
        _ needle: String,
        in text: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(text.contains(needle), "PDF本文に「\(needle)」がありません", file: file, line: line)
    }

    /// PDFKit は「収容数/箱」のように記号の後へ空白を挟むことがあるので、空白を除いて突き合わせる。
    private func assertContainsIgnoringSpaces(
        _ needle: String,
        in text: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let compact = text.replacingOccurrences(of: " ", with: "")
        XCTAssertTrue(compact.contains(needle), "PDF本文に「\(needle)」がありません", file: file, line: line)
    }
}
