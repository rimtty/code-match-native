import XCTest
@testable import CodeMatch

/// 照合履歴の一括書き出し(JSON)を検証する。
///
/// この出力は端末外での解析のために使うので、QR / Code 128の全文は記録どおりでなければならない。
/// 特にモルテンの納品書QRは末尾の空白まで含めて61桁が1レコードなので、
/// 桁数と1文字ずつの一致まで確かめる。スキーマはAndroid版と共通のため、
/// 項目名・型・`null`の出方が変わると相互運用が壊れる。
final class HistoryExporterTests: XCTestCase {
    // 現場ラベルの実データ。
    private let moltenQRPAF1 = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private let moltenTagPAF1FirstBox = "PAF1-15-422@0NKD3C"
    private let moltenTagPAF1SecondBox = "PAF1-15-422@0NLL3C"
    // 末尾に空白4つを含むレコード。トリムされていないことの確認に使う。
    private let moltenQRD10E = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private let moltenTagD10E = "D10E-50-N10B@0UBL00"

    private let exportedAt = Date(timeIntervalSince1970: 1_757_207_025) // 2025-09-07T01:03:45Z
    private let moltenStartedAt = Date(timeIntervalSince1970: 1_700_000_000)
    private let sawaiStartedAt = Date(timeIntervalSince1970: 1_600_000_000)

    // MARK: - 固定値

    func testFixturePayloadsAreFullRecords() {
        XCTAssertEqual(moltenQRPAF1.count, 61)
        XCTAssertEqual(moltenQRD10E.count, 61)
        XCTAssertTrue(moltenQRD10E.hasSuffix("    "))
    }

    // MARK: - ファイル全体

    func testEnvelopeCarriesSchemaVersionPlatformAndExportTime() throws {
        let root = try exportRoot(sessions: [moltenSession(), sawaiLegacySession()])

        XCTAssertEqual(root["schemaVersion"] as? Int, 1)
        XCTAssertEqual(root["platform"] as? String, "ios")
        XCTAssertEqual(root["appVersion"] as? String, "1.0 (4)")
        XCTAssertEqual(root["exportedAt"] as? String, "2025-09-07T01:03:45Z")

        let sessions = try XCTUnwrap(root["sessions"] as? [[String: Any]])
        XCTAssertEqual(sessions.count, 2)
    }

    func testSessionsKeepStoredOrderAndEntriesKeepRecordedOrder() throws {
        let root = try exportRoot(sessions: [moltenSession(), sawaiLegacySession()])
        let sessions = try XCTUnwrap(root["sessions"] as? [[String: Any]])

        XCTAssertEqual(sessions[0]["destination"] as? String, "molten")
        XCTAssertEqual(sessions[1]["destination"] as? String, "sawai")

        // 同じ納品書QRでも箱ごとに現品票が変わるので、記録順のまま並ぶことを確かめる
        let moltenEntries = try XCTUnwrap(sessions[0]["entries"] as? [[String: Any]])
        XCTAssertEqual(moltenEntries.count, 3)
        XCTAssertEqual(moltenEntries[0]["barcodePayload"] as? String, moltenTagPAF1FirstBox)
        XCTAssertEqual(moltenEntries[1]["barcodePayload"] as? String, moltenTagPAF1SecondBox)
        XCTAssertEqual(moltenEntries[2]["barcodePayload"] as? String, moltenTagD10E)
    }

    // MARK: - セッション

    func testActiveSessionAndUnnamedSessionEmitNullFields() throws {
        let root = try exportRoot(sessions: [moltenSession(), sawaiLegacySession()])
        let sessions = try XCTUnwrap(root["sessions"] as? [[String: Any]])

        let molten = sessions[0]
        XCTAssertEqual(molten["id"] as? String, moltenSessionID.uuidString)
        XCTAssertEqual(molten["name"] as? String, "モルテン 9/8 便")
        XCTAssertEqual(molten["startedAt"] as? String, "2023-11-14T22:13:20Z")
        XCTAssertEqual(molten["endedAt"] as? String, "2023-11-14T23:13:20Z")

        // 照合中のセッションは endedAt を省略せず null として出す
        let sawai = sessions[1]
        XCTAssertTrue(sawai.keys.contains("endedAt"))
        XCTAssertTrue(sawai["endedAt"] is NSNull)
        // 名前なしのセッションも name を省略しない
        XCTAssertTrue(sawai.keys.contains("name"))
        XCTAssertTrue(sawai["name"] is NSNull)
    }

    func testSessionWithoutStoredDestinationFallsBackToTheFirstPayload() throws {
        // 仕向地を記録していない旧履歴でも、QR全文から解決した値を出す
        let root = try exportRoot(sessions: [sawaiLegacySession()])
        let sessions = try XCTUnwrap(root["sessions"] as? [[String: Any]])
        XCTAssertEqual(sessions[0]["destination"] as? String, "sawai")
    }

    func testSessionWithoutAnyDestinationEmitsNull() throws {
        let session = MatchSession(startedAt: sawaiStartedAt, entries: [], destination: nil)
        let root = try exportRoot(sessions: [session])
        let sessions = try XCTUnwrap(root["sessions"] as? [[String: Any]])

        XCTAssertTrue(sessions[0].keys.contains("destination"))
        XCTAssertTrue(sessions[0]["destination"] is NSNull)
        XCTAssertEqual((sessions[0]["entries"] as? [[String: Any]])?.count, 0)
    }

    // MARK: - 記録

    func testEntryCarriesIdCodeAndMatchedAt() throws {
        let root = try exportRoot(sessions: [moltenSession()])
        let entries = try XCTUnwrap(
            (try XCTUnwrap(root["sessions"] as? [[String: Any]]))[0]["entries"] as? [[String: Any]]
        )

        XCTAssertEqual(entries[0]["id"] as? String, moltenFirstEntryID.uuidString)
        XCTAssertEqual(entries[0]["code"] as? String, "PAF1-15-422")
        XCTAssertEqual(entries[0]["matchedAt"] as? String, "2023-11-14T22:23:20Z")
    }

    func testLegacyEntryWithoutPayloadsEmitsNulls() throws {
        let root = try exportRoot(sessions: [sawaiLegacySession()])
        let entries = try XCTUnwrap(
            (try XCTUnwrap(root["sessions"] as? [[String: Any]]))[0]["entries"] as? [[String: Any]]
        )

        XCTAssertEqual(entries.count, 2)
        let legacy = entries[1]
        XCTAssertEqual(legacy["code"] as? String, "BCJH-52-81GG")
        XCTAssertTrue(legacy.keys.contains("qrPayload"))
        XCTAssertTrue(legacy["qrPayload"] is NSNull)
        XCTAssertTrue(legacy.keys.contains("barcodePayload"))
        XCTAssertTrue(legacy["barcodePayload"] is NSNull)
    }

    /// 出力の要。QR全文は1文字も足さず削らずに往復する。
    func testPayloadsRoundTripByteForByte() throws {
        let root = try exportRoot(sessions: [moltenSession()])
        let entries = try XCTUnwrap(
            (try XCTUnwrap(root["sessions"] as? [[String: Any]]))[0]["entries"] as? [[String: Any]]
        )

        let firstQR = try XCTUnwrap(entries[0]["qrPayload"] as? String)
        XCTAssertEqual(firstQR.count, 61)
        XCTAssertEqual(firstQR, moltenQRPAF1)
        XCTAssertEqual(Array(firstQR.unicodeScalars), Array(moltenQRPAF1.unicodeScalars))

        // 末尾に空白4つを持つレコードもトリムしない
        let trailingSpaceQR = try XCTUnwrap(entries[2]["qrPayload"] as? String)
        XCTAssertEqual(trailingSpaceQR.count, 61)
        XCTAssertEqual(trailingSpaceQR, moltenQRD10E)
        XCTAssertEqual(Array(trailingSpaceQR.unicodeScalars), Array(moltenQRD10E.unicodeScalars))
        XCTAssertTrue(trailingSpaceQR.hasSuffix("    "))
        XCTAssertEqual(entries[2]["barcodePayload"] as? String, moltenTagD10E)
    }

    // MARK: - 符号化の体裁

    func testDatesAreISO8601UTC() throws {
        let data = try HistoryExporter.jsonData(
            sessions: [moltenSession(), sawaiLegacySession()],
            exportedAt: exportedAt,
            appVersion: "1.0 (4)"
        )
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))

        for key in ["exportedAt", "startedAt", "matchedAt"] {
            let values = try dateStrings(in: text, key: key)
            XCTAssertFalse(values.isEmpty, key)
            for value in values {
                XCTAssertTrue(value.hasSuffix("Z"), "\(key): \(value)")
                XCTAssertEqual(value.count, 20, "\(key): \(value)")
                XCTAssertNotNil(ISO8601DateFormatter().date(from: value), "\(key): \(value)")
            }
        }
        // 日付の "/" はエスケープしない設定なので、区切りが読める形で残る
        XCTAssertFalse(text.contains("\\/"))
    }

    func testEmptyHistoryStillProducesAValidEnvelope() throws {
        let root = try exportRoot(sessions: [])
        XCTAssertEqual(root["schemaVersion"] as? Int, 1)
        XCTAssertEqual((root["sessions"] as? [[String: Any]])?.count, 0)
    }

    func testTemporaryFileIsNamedByExportTimeAndHoldsTheSameJSON() throws {
        let url = try HistoryExporter.writeTemporaryJSON(
            sessions: [moltenSession()],
            exportedAt: exportedAt,
            appVersion: "1.0 (4)"
        )
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertEqual(url.pathExtension, "json")
        XCTAssertTrue(url.lastPathComponent.hasPrefix("codematch-history-"))
        // 名前は端末のローカル時刻で作るため、時刻そのものではなく形だけを確かめる
        let stem = url.deletingPathExtension().lastPathComponent
            .replacingOccurrences(of: "codematch-history-", with: "")
        XCTAssertNotNil(
            stem.range(of: "^[0-9]{8}-[0-9]{4}$", options: .regularExpression),
            stem
        )

        let written = try Data(contentsOf: url)
        let expected = try HistoryExporter.jsonData(
            sessions: [moltenSession()],
            exportedAt: exportedAt,
            appVersion: "1.0 (4)"
        )
        XCTAssertEqual(written, expected)
    }

    // MARK: - Helpers

    private var moltenSessionID: UUID {
        UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    }

    private var moltenFirstEntryID: UUID {
        UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    }

    /// モルテンの終了済みセッション。同じ納品書QRのPAF1が2箱と、末尾に空白を持つD10Eが1箱。
    private func moltenSession() -> MatchSession {
        MatchSession(
            id: moltenSessionID,
            startedAt: moltenStartedAt,
            endedAt: moltenStartedAt.addingTimeInterval(3600),
            entries: [
                MatchHistoryEntry(
                    id: moltenFirstEntryID,
                    code: "PAF1-15-422",
                    matchedAt: moltenStartedAt.addingTimeInterval(600),
                    qrPayload: moltenQRPAF1,
                    barcodePayload: moltenTagPAF1FirstBox
                ),
                MatchHistoryEntry(
                    id: UUID(uuidString: "77777777-7777-7777-7777-777777777777")!,
                    code: "PAF1-15-422",
                    matchedAt: moltenStartedAt.addingTimeInterval(700),
                    qrPayload: moltenQRPAF1,
                    barcodePayload: moltenTagPAF1SecondBox
                ),
                MatchHistoryEntry(
                    id: UUID(uuidString: "33333333-3333-3333-3333-333333333333")!,
                    code: "D10E-50-N10B",
                    matchedAt: moltenStartedAt.addingTimeInterval(900),
                    qrPayload: moltenQRD10E,
                    barcodePayload: moltenTagD10E
                ),
            ],
            name: "モルテン 9/8 便",
            destination: .molten
        )
    }

    /// 澤井製作所の照合中セッション。仕向地・名前は未記録で、2件目はQR全文を持たない旧履歴。
    private func sawaiLegacySession() -> MatchSession {
        MatchSession(
            id: UUID(uuidString: "44444444-4444-4444-4444-444444444444")!,
            startedAt: sawaiStartedAt,
            endedAt: nil,
            entries: [
                MatchHistoryEntry(
                    id: UUID(uuidString: "55555555-5555-5555-5555-555555555555")!,
                    code: "BCJH-52-81GG",
                    matchedAt: sawaiStartedAt.addingTimeInterval(60),
                    qrPayload: "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
                    barcodePayload: "BCJH-52-81GG@1N5X0C"
                ),
                MatchHistoryEntry(
                    id: UUID(uuidString: "66666666-6666-6666-6666-666666666666")!,
                    code: "BCJH-52-81GG",
                    matchedAt: sawaiStartedAt.addingTimeInterval(120),
                    qrPayload: nil,
                    barcodePayload: nil
                ),
            ],
            name: nil,
            destination: nil
        )
    }

    private func exportRoot(sessions: [MatchSession]) throws -> [String: Any] {
        let data = try HistoryExporter.jsonData(
            sessions: sessions,
            exportedAt: exportedAt,
            appVersion: "1.0 (4)"
        )
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// `"key" : "value"` の値だけを取り出す。`null` の項目は拾わない。
    private func dateStrings(in text: String, key: String) throws -> [String] {
        let pattern = "\"\(key)\"\\s*:\\s*\"([^\"]*)\""
        let regex = try NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).compactMap { match in
            Range(match.range(at: 1), in: text).map { String(text[$0]) }
        }
    }
}
