import XCTest
@testable import CodeMatch

final class CodeMatcherTests: XCTestCase {
    // 実ラベルからデコードした実データ
    private let qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let barcodePayload = "BCJH-52-81GG@1N5X0C"

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

    func testNonStandardQRFallsBackToContainment() {
        XCTAssertEqual(
            CodeMatcher.compare(qrPayload: "PART:BCJH-52-81GG;QTY:12", barcodePayload: barcodePayload),
            .match
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
        XCTAssertEqual(CodeMatcher.format(partNumber: "BCJH5281GG"), "BCJH-52-81GG")
        XCTAssertEqual(CodeMatcher.format(partNumber: "ABC"), "ABC")
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

    func testTagBarcodeRecordParsing() {
        let record = TagBarcodeRecord.parse(barcodePayload)
        XCTAssertEqual(record?.partNumber, "BCJH-52-81GG")
        XCTAssertEqual(record?.managementCode, "1N5X0C")

        let noCode = TagBarcodeRecord.parse("BCJH-52-81GG")
        XCTAssertEqual(noCode?.partNumber, "BCJH-52-81GG")
        XCTAssertNil(noCode?.managementCode)

        XCTAssertNil(TagBarcodeRecord.parse("  "))
    }
}

@MainActor
final class HistoryStoreTests: XCTestCase {
    func testSessionRecordsNormalizedMatchesAndEnds() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let matchedAt = startedAt.addingTimeInterval(30)

        let sessionID = store.beginSession(at: startedAt)
        store.recordMatch(code: "  ABC-123\n", at: matchedAt)

        XCTAssertEqual(store.activeSession?.id, sessionID)
        XCTAssertEqual(store.activeSession?.matchedCount, 1)
        XCTAssertEqual(store.activeSession?.entries.first?.code, "ABC-123")
        XCTAssertEqual(store.activeSession?.entries.first?.matchedAt, matchedAt)

        store.endActiveSession(at: startedAt.addingTimeInterval(60))
        XCTAssertNil(store.activeSession)
        XCTAssertEqual(store.sessions.first?.matchedCount, 1)
    }

    func testSessionsArePersistedAndLoaded() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let original = HistoryStore(storageURL: storageURL)
        original.beginSession()
        original.recordMatch(code: "MATCHED-CODE")
        original.endActiveSession()

        let restored = HistoryStore(storageURL: storageURL)

        XCTAssertEqual(restored.sessions.count, 1)
        XCTAssertEqual(restored.sessions.first?.entries.first?.code, "MATCHED-CODE")
        XCTAssertFalse(restored.sessions.first?.isActive ?? true)
    }

    func testNewSessionIsSeparateFromPreviousHistory() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()
        store.recordMatch(code: "FIRST")
        store.endActiveSession()

        store.beginSession()
        store.recordMatch(code: "SECOND")

        XCTAssertEqual(store.sessions.count, 2)
        XCTAssertEqual(store.sessions[0].entries.map(\.code), ["SECOND"])
        XCTAssertEqual(store.sessions[1].entries.map(\.code), ["FIRST"])
    }

    func testRecordMatchStoresPayloadsAndDetectsDuplicates() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(
            code: "BCJH-52-81GG",
            qrPayload: "DCLP675300BCJH5281GG02...",
            barcodePayload: "BCJH-52-81GG@1N5X0C"
        )

        XCTAssertTrue(store.activeSessionHasMatch(code: "BCJH-52-81GG"))
        XCTAssertFalse(store.activeSessionHasMatch(code: "BCJH-55-81GG"))
        XCTAssertEqual(store.activeSession?.entries.first?.qrPayload, "DCLP675300BCJH5281GG02...")
        XCTAssertEqual(store.activeSession?.entries.first?.barcodePayload, "BCJH-52-81GG@1N5X0C")

        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertEqual(restored.sessions.first?.entries.first?.barcodePayload, "BCJH-52-81GG@1N5X0C")
    }

    func testSessionNameCanBeSetAtStartAndRenamed() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)

        let id = store.beginSession(name: "  午前便  ")
        XCTAssertEqual(store.activeSession?.name, "午前便")

        store.renameSession(id: id, name: "午後便")
        XCTAssertEqual(store.activeSession?.name, "午後便")

        // 空文字への変更は「名前なし」へ戻す
        store.renameSession(id: id, name: "   ")
        XCTAssertNil(store.activeSession?.name)

        store.renameSession(id: id, name: "確定名")
        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertEqual(restored.sessions.first?.name, "確定名")
    }

    func testBeginSessionWithEmptyNameStoresNil() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession(name: "")
        XCTAssertNil(store.activeSession?.name)
        XCTAssertEqual(store.activeSession?.displayName, "")
    }

    func testDeleteSessionsRemovesAndPersists() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()
        store.recordMatch(code: "FIRST")
        store.endActiveSession()
        store.beginSession()
        store.recordMatch(code: "SECOND")
        store.endActiveSession()

        store.deleteSessions(at: IndexSet(integer: 0))

        XCTAssertEqual(store.sessions.count, 1)
        XCTAssertEqual(store.sessions.first?.entries.map(\.code), ["FIRST"])

        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertEqual(restored.sessions.count, 1)
    }

    func testEndingSessionWithNoMatchesDiscardsIt() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)

        store.beginSession(name: "空のセッション")
        store.endActiveSession()

        XCTAssertTrue(store.sessions.isEmpty)

        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertTrue(restored.sessions.isEmpty)
    }

    private func temporaryStorageURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("match-history.json")
    }
}
