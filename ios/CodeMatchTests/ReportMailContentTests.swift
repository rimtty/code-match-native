import XCTest
@testable import CodeMatch

/// レポートをメールで共有するときの宛先・件名・本文を固定する。
final class ReportMailContentTests: XCTestCase {
    private let sawaiQR = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let moltenQRPAF1 = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
    private let locale = Locale(identifier: "ja_JP")

    func testRecipientIsTheFixedOperatorAddress() {
        XCTAssertEqual(ReportMailContent.recipients, ["takemoto1075@icloud.com"])
    }

    func testInspectionMailRepeatsTheReportHeaderAndNamesTheAttachment() {
        let session = MatchSession(
            startedAt: startedAt,
            endedAt: startedAt.addingTimeInterval(120),
            entries: [
                MatchHistoryEntry(code: "BCJH-52-81GG", matchedAt: startedAt, qrPayload: sawaiQR, barcodePayload: "BCJH-52-81GG@1N5X0C"),
                MatchHistoryEntry(code: "BCJH-52-81GG", matchedAt: startedAt, qrPayload: sawaiQR, barcodePayload: "BCJH-52-81GG@1N5X0D")
            ],
            name: "朝便",
            destination: .sawai
        )
        let appLanguage = AppLanguage(locale)

        let mail = ReportMailContent.make(session: session, kind: .inspection, fileName: "検品レポート_朝便.pdf", locale: locale)

        XCTAssertEqual(mail.subject, "検品レポート \(appLanguage.formatDateTime(startedAt)) - 澤井製作所")
        XCTAssertFalse(mail.subject.contains("朝便"))
        let expectedBody = [
            "お疲れさまです。",
            "CodeMatch の検品レポートをお送りします。",
            "",
            "■ セッション",
            "セッション名: 朝便",
            "開始: \(appLanguage.formatDateTime(startedAt))",
            "終了: \(appLanguage.formatDateTime(startedAt.addingTimeInterval(120)))",
            "仕向地: 澤井製作所",
            "検査箱数: 2箱",
            "品番数（枝番別）: 1",
            "",
            "■ 添付",
            "検品レポート_朝便.pdf"
        ].joined(separator: "\n")
        XCTAssertEqual(mail.body, expectedBody)
        XCTAssertFalse(mail.body.contains(sawaiQR), "メール本文に生の読取値は載せない")
    }

    func testHistoryMailOfAMoltenSessionAddsTheDeliveryNumberCountAndUsesTheStartDateWhenUnnamed() {
        let session = MatchSession(
            startedAt: startedAt,
            entries: [
                MatchHistoryEntry(code: "PAF1-15-422", matchedAt: startedAt, qrPayload: moltenQRPAF1, barcodePayload: "PAF1-15-422@0NKD3C")
            ],
            destination: .molten
        )
        let start = AppLanguage(locale).formatDateTime(startedAt)

        let mail = ReportMailContent.make(session: session, kind: .matchHistory, fileName: "照合履歴_x.pdf", locale: locale)

        XCTAssertEqual(mail.subject, "照合履歴レポート \(start) - モルテン")
        XCTAssertTrue(mail.body.hasPrefix("お疲れさまです。\nCodeMatch の照合履歴レポートをお送りします。\n"))
        XCTAssertTrue(mail.body.contains("状態: 照合中"))
        XCTAssertTrue(mail.body.contains("検査箱数: 1箱"))
        XCTAssertTrue(mail.body.contains("品番数: 1"))
        XCTAssertTrue(mail.body.contains("納品番号数: 1"))
        XCTAssertTrue(mail.body.hasSuffix("■ 添付\n照合履歴_x.pdf"))
        XCTAssertFalse(mail.body.contains("セッション名"))
    }
}
