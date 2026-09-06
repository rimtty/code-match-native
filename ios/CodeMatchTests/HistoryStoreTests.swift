import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

@MainActor
final class HistoryStoreTests: XCTestCase {
    // 現場ラベルの実データ。モルテックの納品書QRは末尾の空白まで含めて61桁が1レコード。
    private let moltecQRD10E = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private let moltecTagD10E = "D10E-50-N10B@0UBL00"
    private let moltecQRPAF1 = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private let moltecTagPAF1FirstBox = "PAF1-15-422@0NKD3C"
    private let moltecTagPAF1SecondBox = "PAF1-15-422@0NLL3C"
    // 同じ品番BCKE-34-716Bで納品番号だけが異なる2枚の納品書。
    private let moltecQRUAG7520 = "AK6805BCKE34716B         UAG7520000FA3P20F-DAM000010809080000"
    private let moltecTagUAG7520 = "BCKE-34-716B@0GGI30"
    private let moltecQRUAG7530 = "AK6805BCKE34716B         UAG7530000FA3P20F-DAM000010809080500"
    private let moltecTagUAG7530FirstBox = "BCKE-34-716B@0GGC30"
    private let moltecTagUAG7530SecondBox = "BCKE-34-716B@0G5Z30"
    // 澤井製作所の納品書兼現品票QR(66桁)。カード番号が箱ごとに異なる。
    private let sawaiQR = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let sawaiOtherCardQR = "DCLP675301BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private let sawaiTag = "BCJH-52-81GG@1N5X0C"

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

    func testRecordMatchStoresPayloadsAndCountsMatches() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(
            code: "BCJH-52-81GG",
            qrPayload: "DCLP675300BCJH5281GG02...",
            barcodePayload: "BCJH-52-81GG@1N5X0C"
        )

        XCTAssertEqual(store.activeSessionMatchCount(code: "BCJH-52-81GG"), 1)
        XCTAssertEqual(store.activeSessionMatchCount(code: "BCJH-55-81GG"), 0)
        XCTAssertEqual(store.activeSession?.entries.first?.qrPayload, "DCLP675300BCJH5281GG02...")
        XCTAssertEqual(store.activeSession?.entries.first?.barcodePayload, "BCJH-52-81GG@1N5X0C")

        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertEqual(restored.sessions.first?.entries.first?.barcodePayload, "BCJH-52-81GG@1N5X0C")
    }

    func testActiveSessionDetectsOnlyPreviouslyMatchedBoxQR() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()
        let firstBoxQR = "DAAL134150BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        let secondBoxQR = "DAAL134140BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        store.recordMatch(
            code: "BCJH-55-81GG",
            qrPayload: firstBoxQR,
            barcodePayload: "BCJH-55-81GG@1KVQ0C"
        )

        XCTAssertTrue(
            store.activeSessionContainsMatchedQRPayload(" \(firstBoxQR.lowercased())\n")
        )
        XCTAssertFalse(
            store.activeSessionContainsMatchedQRPayload(secondBoxQR)
        )
    }

    /// 同一品番でもラベルの管理コードが異なる箱は、それぞれ記録してまとめて表示する。
    func testDistinctLabelsForSamePartAreRecordedAndGroupedAsBoxes() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(code: "BCJH-52-81GG", barcodePayload: "BCJH-52-81GG@1N5X0C")
        store.recordMatch(code: "BCJH-55-81GG", barcodePayload: "BCJH-55-81GG@1KVV0C")
        store.recordMatch(code: "BCJH-52-81GG", barcodePayload: "BCJH-52-81GG@1N5X0D")
        store.recordMatch(code: "BCJH-52-81GG", barcodePayload: "BCJH-52-81GG@1N5X0E")

        XCTAssertEqual(store.activeSession?.matchedCount, 4)
        XCTAssertEqual(store.activeSessionMatchCount(code: "BCJH-52-81GG"), 3)

        let groups = store.activeSession?.groupedEntries ?? []
        XCTAssertEqual(groups.count, 2)
        // 最初に照合された順に並び、箱数は照合回数と一致する
        XCTAssertEqual(groups.first?.code, "BCJH-52-81GG")
        XCTAssertEqual(groups.first?.boxCount, 3)
        XCTAssertEqual(
            groups.first?.entries.map(\.barcodePayload),
            ["BCJH-52-81GG@1N5X0C", "BCJH-52-81GG@1N5X0D", "BCJH-52-81GG@1N5X0E"]
        )
        XCTAssertEqual(groups.last?.code, "BCJH-55-81GG")
        XCTAssertEqual(groups.last?.boxCount, 1)
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

    /// モルテックは同じ納品番号の全箱で納品書QRが同一なので、現品票の管理コードまで見て箱を区別する。
    func testMoltecSameQRDifferentLabelsAreDistinctBoxes() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        XCTAssertEqual(moltecQRPAF1.count, 61)
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(
            code: "PAF1-15-422",
            qrPayload: moltecQRPAF1,
            barcodePayload: moltecTagPAF1FirstBox
        )

        XCTAssertFalse(
            store.activeSessionContainsMatchedBox(
                qrPayload: moltecQRPAF1,
                barcodePayload: moltecTagPAF1SecondBox
            )
        )

        store.recordMatch(
            code: "PAF1-15-422",
            qrPayload: moltecQRPAF1,
            barcodePayload: moltecTagPAF1SecondBox
        )

        XCTAssertEqual(store.activeSessionMatchCount(code: "PAF1-15-422"), 2)
        XCTAssertEqual(store.activeSession?.matchedCount, 2)
    }

    /// 同じ納品書QRと同じ現品票の組み合わせは、同じ箱の二重検査として検出する。
    func testMoltecSameQRSameLabelIsDuplicate() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        XCTAssertEqual(moltecQRD10E.count, 61)
        // 末尾の空白4桁もレコードの一部
        XCTAssertTrue(moltecQRD10E.hasSuffix("20908    "))
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(
            code: "D10E-50-N10B",
            qrPayload: moltecQRD10E,
            barcodePayload: moltecTagD10E
        )

        XCTAssertTrue(
            store.activeSessionContainsMatchedBox(
                qrPayload: moltecQRD10E,
                barcodePayload: moltecTagD10E
            )
        )
        // 末尾空白が落ちた読取値や小文字の管理コードでも同じ箱として扱う
        XCTAssertTrue(
            store.activeSessionContainsMatchedBox(
                qrPayload: String(moltecQRD10E.dropLast(4)),
                barcodePayload: " \(moltecTagD10E.lowercased())\n"
            )
        )
        // 管理コードが違えば別の箱
        XCTAssertFalse(
            store.activeSessionContainsMatchedBox(
                qrPayload: moltecQRD10E,
                barcodePayload: "D10E-50-N10B@0UXL0K"
            )
        )
    }

    /// 澤井製作所の重複判定はQR全文だけで決まる（従来どおり）。現品票は判定に使わない。
    func testSawaiDuplicateRuleStillKeysOnQROnly() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        XCTAssertEqual(sawaiQR.count, 66)
        XCTAssertEqual(sawaiOtherCardQR.count, 66)
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(code: "BCJH-52-81GG", qrPayload: sawaiQR, barcodePayload: sawaiTag)

        // 別ラベルの現品票でも、同じカード番号のQRなら照合済みの箱
        XCTAssertTrue(
            store.activeSessionContainsMatchedBox(
                qrPayload: sawaiQR,
                barcodePayload: "BCJH-52-81GG@1N5X0D"
            )
        )
        XCTAssertTrue(store.activeSessionContainsMatchedQRPayload(sawaiQR))
        // カード番号が異なれば別の箱
        XCTAssertFalse(
            store.activeSessionContainsMatchedBox(
                qrPayload: sawaiOtherCardQR,
                barcodePayload: sawaiTag
            )
        )
    }

    /// 納品番号ごとの箱数と累計数量（収容数の合計）を集計する。
    func testDeliverySummaryCountsBoxesAndQuantity() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        XCTAssertEqual(moltecQRUAG7520.count, 61)
        XCTAssertEqual(moltecQRUAG7530.count, 61)
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7520,
            barcodePayload: moltecTagUAG7520
        )
        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7530,
            barcodePayload: moltecTagUAG7530FirstBox
        )
        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7530,
            barcodePayload: moltecTagUAG7530SecondBox
        )

        XCTAssertEqual(
            store.activeSessionDeliverySummary(deliveryNumber: "UAG7520"),
            DeliveryBoxSummary(deliveryNumber: "UAG7520", boxCount: 1, totalQuantity: 108)
        )
        XCTAssertEqual(
            store.activeSessionDeliverySummary(deliveryNumber: "UAG7530"),
            DeliveryBoxSummary(deliveryNumber: "UAG7530", boxCount: 2, totalQuantity: 216)
        )
        XCTAssertEqual(
            store.activeSessionDeliverySummary(deliveryNumber: "UAG9999"),
            DeliveryBoxSummary(deliveryNumber: "UAG9999", boxCount: 0, totalQuantity: 0)
        )

        store.endActiveSession()
        // アクティブセッションがなければ0箱・0個
        XCTAssertEqual(
            store.activeSessionDeliverySummary(deliveryNumber: "UAG7530"),
            DeliveryBoxSummary(deliveryNumber: "UAG7530", boxCount: 0, totalQuantity: 0)
        )
    }

    /// セッションの仕向地は最初の記録で確定し、以後は上書きしない。
    func testRecordMatchSetsDestinationOnceAndPersists() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()
        XCTAssertNil(store.activeSession?.destination)

        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7520,
            barcodePayload: moltecTagUAG7520
        )
        XCTAssertEqual(store.activeSession?.destination, .moltec)

        store.recordMatch(code: "BCJH-52-81GG", qrPayload: sawaiQR, barcodePayload: sawaiTag)
        XCTAssertEqual(store.activeSession?.destination, .moltec)

        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertEqual(restored.sessions.first?.destination, .moltec)
        XCTAssertEqual(restored.sessions.first?.resolvedDestination, .moltec)

        // QR全文を渡さない経路でも、明示指定した仕向地で確定できる
        store.endActiveSession()
        store.beginSession()
        store.recordMatch(code: "BCJH-52-81GG", destination: .sawai)
        XCTAssertEqual(store.activeSession?.destination, .sawai)
    }

    func testSetActiveSessionDestinationIfNeededPersistsAndDoesNotOverwrite() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)

        // アクティブセッションがなければ何も起きない
        store.setActiveSessionDestinationIfNeeded(.moltec)
        XCTAssertTrue(store.sessions.isEmpty)

        store.beginSession()
        store.setActiveSessionDestinationIfNeeded(.moltec)
        XCTAssertEqual(store.activeSession?.destination, .moltec)

        store.setActiveSessionDestinationIfNeeded(.sawai)
        XCTAssertEqual(store.activeSession?.destination, .moltec)

        let restored = HistoryStore(storageURL: storageURL)
        XCTAssertEqual(restored.activeSession?.destination, .moltec)

        // 記録時にも確定済みの仕向地は書き換えない
        store.recordMatch(code: "BCJH-52-81GG", qrPayload: sawaiQR, barcodePayload: sawaiTag)
        XCTAssertEqual(store.activeSession?.destination, .moltec)
    }

    /// 仕向地を持たない旧バージョンの履歴も読み込め、最初の記録のQRから仕向地を推定できる。
    func testLegacyJSONWithoutDestinationLoadsAndResolvesFromFirstEntry() throws {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        try writeHistoryJSON(historyJSON(qrPayload: sawaiQR), to: storageURL)

        let store = HistoryStore(storageURL: storageURL)

        XCTAssertEqual(store.sessions.count, 1)
        XCTAssertEqual(store.sessions.first?.entries.map(\.code), ["BCJH-52-81GG"])
        XCTAssertNil(store.sessions.first?.name)
        XCTAssertNil(store.sessions.first?.destination)
        XCTAssertEqual(store.sessions.first?.resolvedDestination, .sawai)
    }

    /// 未知の仕向地文字列でも履歴全体を失わない（復号失敗は全件破棄につながるため）。
    func testUnknownDestinationRawValueDoesNotDiscardHistory() throws {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        try writeHistoryJSON(
            historyJSON(qrPayload: sawaiQR, destination: "mars"),
            to: storageURL
        )

        let store = HistoryStore(storageURL: storageURL)

        XCTAssertEqual(store.sessions.count, 1)
        XCTAssertEqual(store.sessions.first?.entries.count, 1)
        XCTAssertNil(store.sessions.first?.destination)
        XCTAssertEqual(store.sessions.first?.resolvedDestination, .sawai)
    }

    /// 同一品番の中は納品番号ごとに、最初に照合された順で内訳を並べる。
    func testGroupedEntriesExposeDeliveryGroupsInFirstSeenOrder() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()

        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7530,
            barcodePayload: moltecTagUAG7530FirstBox
        )
        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7520,
            barcodePayload: moltecTagUAG7520
        )
        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7530,
            barcodePayload: moltecTagUAG7530SecondBox
        )
        store.recordMatch(code: "BCJH-52-81GG", qrPayload: sawaiQR, barcodePayload: sawaiTag)

        let groups = store.activeSession?.groupedEntries ?? []
        XCTAssertEqual(groups.map(\.code), ["BCKE-34-716B", "BCJH-52-81GG"])

        let deliveryGroups = groups.first?.deliveryGroups ?? []
        XCTAssertEqual(deliveryGroups.map(\.deliveryNumber), ["UAG7530", "UAG7520"])
        XCTAssertEqual(deliveryGroups.first?.boxCount, 2)
        XCTAssertEqual(deliveryGroups.first?.totalQuantity, 216)
        XCTAssertEqual(deliveryGroups.first?.record.partNumber, "BCKE34716B")
        XCTAssertEqual(
            deliveryGroups.first?.entries.map(\.barcodePayload),
            [moltecTagUAG7530FirstBox, moltecTagUAG7530SecondBox]
        )
        XCTAssertEqual(deliveryGroups.last?.boxCount, 1)
        XCTAssertEqual(deliveryGroups.last?.totalQuantity, 108)

        // 澤井製作所のQRには納品番号がないため内訳は空
        XCTAssertEqual(groups.last?.deliveryGroups, [])
    }

    func testDeliveryNumberCount() {
        let storageURL = temporaryStorageURL()
        defer { try? FileManager.default.removeItem(at: storageURL.deletingLastPathComponent()) }
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()
        XCTAssertEqual(store.activeSession?.deliveryNumberCount, 0)

        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7530,
            barcodePayload: moltecTagUAG7530FirstBox
        )
        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7530,
            barcodePayload: moltecTagUAG7530SecondBox
        )
        XCTAssertEqual(store.activeSession?.deliveryNumberCount, 1)

        store.recordMatch(
            code: "BCKE-34-716B",
            qrPayload: moltecQRUAG7520,
            barcodePayload: moltecTagUAG7520
        )
        // 澤井製作所の記録は納品番号を持たないため数に入らない
        store.recordMatch(code: "BCJH-52-81GG", qrPayload: sawaiQR, barcodePayload: sawaiTag)
        XCTAssertEqual(store.activeSession?.deliveryNumberCount, 2)
    }

    private func temporaryStorageURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("match-history.json")
    }

    /// 旧バージョンが保存したままの形（仕向地なし）の履歴JSON。
    /// `destination` を渡すと、その生値を持つ履歴になる。
    private func historyJSON(qrPayload: String, destination: String? = nil) -> String {
        let destinationField = destination.map { ",\n    \"destination\": \"\($0)\"" } ?? ""
        return """
        [
          {
            "id": "9F6E1D8C-0000-4000-8000-000000000001",
            "startedAt": "2026-09-06T09:00:00Z",
            "endedAt": "2026-09-06T09:30:00Z",
            "entries": [
              {
                "id": "9F6E1D8C-0000-4000-8000-000000000002",
                "code": "BCJH-52-81GG",
                "matchedAt": "2026-09-06T09:10:00Z",
                "qrPayload": "\(qrPayload)",
                "barcodePayload": "\(sawaiTag)"
              }
            ]\(destinationField)
          }
        ]
        """
    }

    private func writeHistoryJSON(_ json: String, to storageURL: URL) throws {
        try FileManager.default.createDirectory(
            at: storageURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data(json.utf8).write(to: storageURL)
    }
}
