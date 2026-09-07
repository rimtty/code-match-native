import XCTest
@testable import CodeMatch

@MainActor
final class ScanLogStoreTests: XCTestCase {
    private var directory: URL!
    private var storageURL: URL!

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        storageURL = directory.appendingPathComponent("scan-log.jsonl")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private func makeStore(arguments: [String] = []) -> ScanLogStore {
        ScanLogStore(storageURL: storageURL, arguments: arguments)
    }

    private func makeEvent(
        event: String = "match",
        at: Date = Date(timeIntervalSince1970: 1_757_200_000.125),
        session: UUID? = UUID(uuidString: "1B7B0F44-0F09-4E9B-9B77-6E39E4B0E9AA"),
        reason: String? = nil,
        destination: String? = "sawai",
        qr: String? = ScannerViewModel.sampleQRPayload,
        barcode: String? = ScannerViewModel.sampleBarcodePayload,
        code: String? = "BCJH-52-81GG",
        boxNumber: Int? = 1,
        message: String? = "品目番号が一致しています。"
    ) -> ScanLogEvent {
        ScanLogEvent(
            at: at,
            session: session,
            source: "bluetooth",
            step: "result",
            event: event,
            reason: reason,
            destination: destination,
            qr: qr,
            barcode: barcode,
            code: code,
            boxNumber: boxNumber,
            message: message
        )
    }

    func testRecordAppendsOneLinePerEventAndCountsThem() throws {
        let store = makeStore()
        XCTAssertEqual(store.eventCount, 0)

        store.record(makeEvent(event: "qr_accepted"))
        store.record(makeEvent(event: "match"))

        XCTAssertEqual(store.eventCount, 2)
        XCTAssertNil(store.storageError)
        XCTAssertEqual(store.lines().count, 2)
        XCTAssertEqual(try store.events().map(\.event), ["qr_accepted", "match"])
    }

    func testEncodedLineKeepsEveryFieldAndWritesExplicitNulls() throws {
        let store = makeStore()
        store.record(
            makeEvent(
                event: "rejected",
                reason: "wrong_symbology",
                destination: nil,
                qr: "SOMETHING",
                barcode: nil,
                code: nil,
                boxNumber: nil,
                message: nil
            )
        )

        let line = try XCTUnwrap(store.lines().first)
        XCTAssertFalse(line.contains("\n"))
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any]
        )
        XCTAssertEqual(
            Set(json.keys),
            [
                "at", "session", "source", "step", "event", "reason",
                "destination", "qr", "barcode", "code", "boxNumber", "message"
            ]
        )
        for key in ["destination", "barcode", "code", "boxNumber", "message"] {
            XCTAssertTrue(json[key] is NSNull, "\(key) は null で並ぶ")
        }
        XCTAssertEqual(json["at"] as? String, "2025-09-06T23:06:40.125Z")
        XCTAssertEqual(json["reason"] as? String, "wrong_symbology")
        XCTAssertEqual(json["step"] as? String, "result")
    }

    func testPayloadWithQuotesNewlinesAndBackslashesSurvivesTheRoundTrip() throws {
        let store = makeStore()
        let hostile = "AB\"CD\\EF\nGH\tIJ/KL"
        store.record(makeEvent(qr: hostile, barcode: hostile, message: hostile))

        XCTAssertEqual(store.lines().count, 1)
        let restored = try XCTUnwrap(store.events().first)
        XCTAssertEqual(restored.qr, hostile)
        XCTAssertEqual(restored.barcode, hostile)
        XCTAssertEqual(restored.message, hostile)
    }

    func testExceedingTheTrimThresholdKeepsTheNewestEvents() throws {
        let store = makeStore()
        for index in 1...(ScanLogStore.trimThreshold + 1) {
            store.record(makeEvent(code: "CODE-\(index)"))
        }

        XCTAssertEqual(store.eventCount, ScanLogStore.limit)
        let events = try store.events()
        XCTAssertEqual(events.count, ScanLogStore.limit)
        XCTAssertEqual(events.last?.code, "CODE-\(ScanLogStore.trimThreshold + 1)")
        XCTAssertEqual(
            events.first?.code,
            "CODE-\(ScanLogStore.trimThreshold + 1 - ScanLogStore.limit + 1)"
        )
    }

    func testClearEmptiesTheLog() {
        let store = makeStore()
        store.record(makeEvent())
        store.record(makeEvent())

        store.clear()

        XCTAssertEqual(store.eventCount, 0)
        XCTAssertTrue(store.lines().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: storageURL.path))
    }

    func testExportTextStartsWithTheHeaderAndKeepsEveryEventLine() throws {
        let store = makeStore()
        store.record(makeEvent(event: "qr_accepted"))
        store.record(makeEvent(event: "barcode_accepted"))
        store.record(makeEvent(event: "match"))

        let text = store.exportText(
            exportedAt: Date(timeIntervalSince1970: 1_757_200_000),
            appVersion: "1.0 (5)"
        )
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)

        XCTAssertEqual(lines.count, 4)
        XCTAssertEqual(
            lines[0],
            "{\"type\":\"header\",\"schemaVersion\":1,\"platform\":\"ios\",\"appVersion\":\"1.0 (5)\","
                + "\"exportedAt\":\"2025-09-06T23:06:40.000Z\",\"eventCount\":3,\"limit\":5000}"
        )
        XCTAssertEqual(Array(lines.dropFirst()), store.lines())
    }

    func testWriteTemporaryExportUsesTheSharedFileNameConvention() throws {
        let store = makeStore()
        store.record(makeEvent())
        let exportedAt = Date(timeIntervalSince1970: 1_757_200_000)

        let url = try store.writeTemporaryExport(exportedAt: exportedAt, appVersion: "1.0 (5)")
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertEqual(url.lastPathComponent, ScanLogStore.fileName(exportedAt: exportedAt))
        XCTAssertTrue(url.lastPathComponent.hasPrefix("codematch-scan-log-"))
        XCTAssertTrue(url.lastPathComponent.hasSuffix(".jsonl"))
        let written = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(written, store.exportText(exportedAt: exportedAt, appVersion: "1.0 (5)"))
    }

    func testStoredEventsSurviveAnotherStoreForTheSameFile() throws {
        let store = makeStore()
        store.record(makeEvent(event: "session_start"))
        store.record(makeEvent(event: "session_end"))

        let reopened = makeStore()

        XCTAssertEqual(reopened.eventCount, 2)
        XCTAssertEqual(try reopened.events().map(\.event), ["session_start", "session_end"])
    }

    func testResetArgumentDiscardsTheStoredLogOnLaunch() {
        let store = makeStore()
        store.record(makeEvent())
        XCTAssertEqual(store.eventCount, 1)

        let relaunched = makeStore(arguments: ["-resetScanLog"])

        XCTAssertEqual(relaunched.eventCount, 0)
        XCTAssertTrue(relaunched.lines().isEmpty)
    }
}
