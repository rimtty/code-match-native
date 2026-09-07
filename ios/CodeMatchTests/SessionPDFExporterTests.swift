import PDFKit
import XCTest
@testable import CodeMatch

/// セッション詳細PDFの本文を検証する。
/// 仕向地ごとに納品書ブロックの構成が変わるため、モルテン（納品番号ごと）と
/// 澤井製作所（品番ごと・従来どおり）の双方を現場ラベルの実データで確認する。
///
/// PDFKitの抽出テキストは描画した文字列そのままではなく、全角スペース(U+3000)を
/// 半角スペースへ置き換え、連続する空白を1つにまとめる。よって「　」区切りの行は
/// 半角スペース区切りとして突き合わせる。
final class SessionPDFExporterTests: XCTestCase {
    // 現場ラベルの実データ。モルテンの納品書QRは末尾の空白まで含めて61桁が1レコード。
    private let moltenQRD10E = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private let moltenTagD10E = "D10E-50-N10B@0UBL00"
    private let moltenQRPAF1 = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private let moltenTagPAF1FirstBox = "PAF1-15-422@0NKD3C"
    private let moltenTagPAF1SecondBox = "PAF1-15-422@0NLL3C"
    // 澤井製作所の納品書兼現品票QR(66桁)。
    private let sawaiQR = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let sawaiTag = "BCJH-52-81GG@1N5X0C"
    // デンソーのかんばんQR(JAMA自己記述形式)。同じ品番の2箱はかんばん連番だけが違う。
    private let densoQRKanban0140 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private let densoQRKanban0141 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0141SWS    20260908S0010000720000009924543330454333M6"
    private let densoTag0140 = "860150-7722@1DZ50O"
    private let densoTag0141 = "860150-7722@1DZB0O"

    private let startedAt = Date(timeIntervalSince1970: 1_700_000_000)

    func testFixturePayloadsAreFullRecords() {
        XCTAssertEqual(moltenQRD10E.count, 61)
        XCTAssertEqual(moltenQRPAF1.count, 61)
        XCTAssertEqual(sawaiQR.count, 66)
        XCTAssertEqual(densoQRKanban0140.count, 221)
        XCTAssertEqual(densoQRKanban0141.count, 221)
    }

    func testMoltenInstructionDateAndTimeAreFormattedForDisplay() throws {
        let record = try XCTUnwrap(MoltenQRRecord.parse(moltenQRPAF1))
        XCTAssertEqual(record.instructionDate, "0908")
        XCTAssertEqual(record.formattedInstructionDate, "09/08")
        XCTAssertEqual(record.instructionTime, "0000")
        XCTAssertEqual(record.formattedInstructionTime, "00:00")

        // 時刻欄が空白のレコードは整形せずnilのまま扱う。
        let withoutTime = try XCTUnwrap(MoltenQRRecord.parse(moltenQRD10E))
        XCTAssertEqual(withoutTime.formattedInstructionDate, "09/08")
        XCTAssertNil(withoutTime.formattedInstructionTime)
    }

    func testMoltenSessionListsDeliveryNoteBlockPerDeliveryNumber() throws {
        let text = pdfText(for: moltenSession())

        // ヘッダー: 仕向地と納品番号の種類数
        assertContains("仕向地: モルテン", in: text)
        assertContains("検査箱数: 3箱（品番数: 2）", in: text)
        assertContains("納品番号数: 2", in: text)

        // 同じ品番の2箱は1つの納品番号にまとまり、箱数と累計収容数を見出しに出す
        assertContains("納品番号 UAG5560（2箱・累計 240個）", in: text)
        assertContains("受注者: AK6805 部品番号: PAF1-15-422 納品番号: UAG5560", in: text)
        assertContains("納入先: FA2 TYロケーション: P59 供給先: 01FEM", in: text)
        assertContains("収容数: 120 納入指示日(JUMP): 09/08 時刻: 00:00", in: text)

        // 空欄のTYロケーションと時刻は「-」で埋める
        assertContains("納品番号 U543820（1箱・累計 2個）", in: text)
        assertContains("受注者: AK6805 部品番号: D10E-50-N10B 納品番号: U543820", in: text)
        assertContains("納入先: MB TYロケーション: - 供給先: S6007", in: text)
        assertContains("収容数: 2 納入指示日(JUMP): 09/08 時刻: -", in: text)

        // 箱番号は納品番号ごとに振り直す（管理コードで箱を特定して確認する）
        XCTAssertTrue(try boxLine(managementCode: "0NKD3C", in: text).hasPrefix("1箱目 "))
        XCTAssertTrue(try boxLine(managementCode: "0NLL3C", in: text).hasPrefix("2箱目 "))
        XCTAssertTrue(try boxLine(managementCode: "0UBL00", in: text).hasPrefix("1箱目 "))

        // 澤井製作所向けの納品書ブロックは出さない
        XCTAssertFalse(text.contains("納品書情報"))
        XCTAssertFalse(text.contains("カード番号"))
    }

    func testSawaiSessionKeepsPartNumberBlocksAndShowsDestination() throws {
        let text = pdfText(for: sawaiSession())

        assertContains("仕向地: 澤井製作所", in: text)
        assertContains("検査箱数: 1箱（品番数: 1）", in: text)
        assertContains("納品書情報", in: text)
        assertContains("品目番号: BCJH-52-81GG（枝番 02） カード番号: DCLP675300", in: text)
        XCTAssertTrue(try boxLine(managementCode: "1N5X0C", in: text).hasPrefix("1箱目 "))

        // モルテン向けの見出しと集計は出さない
        XCTAssertFalse(text.contains("納品番号"))
        XCTAssertFalse(text.contains("TYロケーション"))
        // デンソー向けのかんばん項目も出さない
        XCTAssertFalse(text.contains("かんばん連番"))
    }

    /// デンソーはかんばんの共通欄を3行にまとめ、箱ごとの記録にかんばん連番を添える。
    /// 澤井製作所の解析は寛容でデンソーのQRも受理できてしまうため、
    /// カード番号などの澤井向けの欄が出ないことまで確認する。
    func testDensoSessionPrintsKanbanBlockAndBoxes() throws {
        let text = pdfText(for: densoSession())

        assertContains("仕向地: デンソー", in: text)
        assertContains("検査箱数: 2箱（品番数: 1）", in: text)

        // かんばんの共通欄3行（PDFKitは全角スペースを半角へ畳む）
        assertContains("部品番号: 860150-7722 収容数: 24 指示数: 72", in: text)
        assertContains("次区: D850 指示: C01008-45 納入日: 2026/09/08 便: S001", in: text)
        assertContains("管理番号: SWS アイテムNo: 9924543330 受入: M6", in: text)

        // 箱ごとの記録はかんばん連番で見分ける
        assertContains("かんばん連番: 0140", in: text)
        assertContains("かんばん連番: 0141", in: text)
        XCTAssertTrue(try boxLine(managementCode: "1DZ50O", in: text).hasPrefix("1箱目 "))
        XCTAssertTrue(try boxLine(managementCode: "1DZB0O", in: text).hasPrefix("2箱目 "))

        // モルテン向けの集計と、澤井製作所向けの納品書欄は出さない
        XCTAssertFalse(text.contains("納品番号数"))
        XCTAssertFalse(text.contains("カード番号"))
    }

    func testSessionWithoutParsableQRHasNoDestinationLine() {
        let session = MatchSession(
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(600),
            entries: [
                MatchHistoryEntry(code: "BCJH-52-81GG", matchedAt: startedAt.addingTimeInterval(30))
            ]
        )

        let text = pdfText(for: session)

        XCTAssertFalse(text.contains("仕向地"))
        XCTAssertFalse(text.contains("納品番号数"))
        assertContains("検査箱数: 1箱（品番数: 1）", in: text)
    }

    // MARK: - Helpers

    /// 同じ品番の2箱(納品番号UAG5560)と、別品番の1箱(納品番号U543820)を持つモルテンのセッション。
    private func moltenSession() -> MatchSession {
        MatchSession(
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(900),
            entries: [
                MatchHistoryEntry(
                    code: "PAF1-15-422",
                    matchedAt: startedAt.addingTimeInterval(60),
                    qrPayload: moltenQRPAF1,
                    barcodePayload: moltenTagPAF1FirstBox
                ),
                MatchHistoryEntry(
                    code: "PAF1-15-422",
                    matchedAt: startedAt.addingTimeInterval(120),
                    qrPayload: moltenQRPAF1,
                    barcodePayload: moltenTagPAF1SecondBox
                ),
                MatchHistoryEntry(
                    code: "D10E-50-N10B",
                    matchedAt: startedAt.addingTimeInterval(180),
                    qrPayload: moltenQRD10E,
                    barcodePayload: moltenTagD10E
                )
            ],
            destination: .molten
        )
    }

    private func sawaiSession() -> MatchSession {
        MatchSession(
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(600),
            entries: [
                MatchHistoryEntry(
                    code: "BCJH-52-81GG",
                    matchedAt: startedAt.addingTimeInterval(30),
                    qrPayload: sawaiQR,
                    barcodePayload: sawaiTag
                )
            ],
            destination: .sawai
        )
    }

    /// 同じ品番(860150-7722)の2箱を持つデンソーのセッション。かんばん連番は0140と0141。
    private func densoSession() -> MatchSession {
        MatchSession(
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(600),
            entries: [
                MatchHistoryEntry(
                    code: "860150-7722",
                    matchedAt: startedAt.addingTimeInterval(30),
                    qrPayload: densoQRKanban0140,
                    barcodePayload: densoTag0140
                ),
                MatchHistoryEntry(
                    code: "860150-7722",
                    matchedAt: startedAt.addingTimeInterval(90),
                    qrPayload: densoQRKanban0141,
                    barcodePayload: densoTag0141
                )
            ],
            destination: .denso
        )
    }

    /// PDFから全ページの本文を取り出す。
    private func pdfText(
        for session: MatchSession,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> String {
        let data = SessionPDFExporter.generatePDF(for: session, locale: Locale(identifier: "ja_JP"))
        guard let document = PDFDocument(data: data) else {
            XCTFail("PDFを解析できませんでした", file: file, line: line)
            return ""
        }
        return (0..<document.pageCount)
            .compactMap { document.page(at: $0)?.string }
            .joined(separator: "\n")
    }

    /// 管理コードで1箱分の読み取り記録の行を特定する。照合時刻は環境依存のため突き合わせに使わない。
    private func boxLine(
        managementCode: String,
        in text: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> String {
        let matched = text
            .split(separator: "\n")
            .first { $0.contains("管理コード: \(managementCode)") }
            .map(String.init)
        return try XCTUnwrap(matched, "管理コード \(managementCode) の行がありません", file: file, line: line)
    }

    private func assertContains(
        _ needle: String,
        in text: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(text.contains(needle), "PDF本文に「\(needle)」がありません", file: file, line: line)
    }
}
