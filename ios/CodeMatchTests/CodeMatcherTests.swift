import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

@MainActor
final class CameraPreviewTests: XCTestCase {
    func testPreviewLayerCanRebindAndDetachCaptureSessions() {
        let view = PreviewView()
        let firstSession = AVCaptureSession()
        let secondSession = AVCaptureSession()

        view.setSession(firstSession)
        XCTAssertTrue(view.previewLayer.session === firstSession)

        view.setSession(secondSession)
        XCTAssertTrue(view.previewLayer.session === secondSession)

        view.setSession(nil)
        XCTAssertNil(view.previewLayer.session)
    }

    func testPreviewDismantleDetachesSessionAndDisablesRecovery() {
        let view = PreviewView()
        let session = AVCaptureSession()
        view.isActive = true
        view.setSession(session)

        view.prepareForDismantle()

        XCTAssertFalse(view.isActive)
        XCTAssertNil(view.previewLayer.session)
    }

    func testInactivePreviewDetachesReusableCaptureSession() {
        let view = PreviewView()
        let session = AVCaptureSession()

        view.setActive(true, session: session)
        XCTAssertTrue(view.previewLayer.session === session)

        view.setActive(false, session: session)
        XCTAssertFalse(view.isActive)
        XCTAssertNil(view.previewLayer.session)

        view.setActive(true, session: session)
        XCTAssertTrue(view.previewLayer.session === session)
    }

    func testShutdownRetainsScannerUntilQueuedTeardownCompletes() async {
        let queue = DispatchQueue(label: "CameraPreviewTests.suspended-session")
        queue.suspend()
        var scanner: CameraScanner? = CameraScanner(sessionQueue: queue)
        weak var weakScanner = scanner
        let shutdownCompleted = expectation(description: "camera shutdown completed")

        scanner?.shutdown {
            shutdownCompleted.fulfill()
        }
        scanner = nil

        // 旧実装のweak selfでは、この時点でscannerが解放され停止処理が消えていた。
        XCTAssertNotNil(weakScanner)
        queue.resume()

        await fulfillment(of: [shutdownCompleted], timeout: 2)
        XCTAssertNil(weakScanner)
    }

    func testMetadataRegionIsClampedToNormalizedCoordinates() {
        XCTAssertEqual(
            CameraScanner.normalizedMetadataRect(
                CGRect(x: -0.25, y: 0.2, width: 0.75, height: 1.1)
            ),
            CGRect(x: 0, y: 0.2, width: 0.5, height: 0.8)
        )
        XCTAssertNil(CameraScanner.normalizedMetadataRect(.zero))
        XCTAssertNil(
            CameraScanner.normalizedMetadataRect(
                CGRect(x: CGFloat.nan, y: 0, width: 1, height: 1)
            )
        )
    }

    func testCameraStartIsBlockedOnlyForUnsupportedScreenCapture() {
        XCTAssertTrue(
            CameraScanner.shouldBlockCameraStart(
                isScreenCaptured: true,
                supportsMultitaskingCamera: false
            )
        )
        XCTAssertFalse(
            CameraScanner.shouldBlockCameraStart(
                isScreenCaptured: false,
                supportsMultitaskingCamera: false
            )
        )
        XCTAssertFalse(
            CameraScanner.shouldBlockCameraStart(
                isScreenCaptured: true,
                supportsMultitaskingCamera: true
            )
        )
    }

}

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
    }

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

    func testSharedMatchingFixtures() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let fixtureURL = repositoryRoot
            .appendingPathComponent("shared/test-fixtures/matching-cases.json")
        let fixtures = try JSONDecoder().decode(
            SharedMatchingFixtures.self,
            from: Data(contentsOf: fixtureURL)
        )

        XCTAssertEqual(fixtures.schemaVersion, 1)
        XCTAssertFalse(fixtures.cases.isEmpty)

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
        }
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

    func testBluetoothScanPayloadValidationRejectsReverseOrderFormats() {
        XCTAssertTrue(KanbanQRRecord.isValidScanPayload(qrPayload))
        XCTAssertFalse(KanbanQRRecord.isValidScanPayload(barcodePayload))
        XCTAssertFalse(KanbanQRRecord.isValidScanPayload(String(qrPayload.prefix(65))))

        XCTAssertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload))
        XCTAssertTrue(TagBarcodeRecord.isValidScanPayload("KAAA-55-D86B@0Y5U0I"))
        XCTAssertFalse(TagBarcodeRecord.isValidScanPayload(qrPayload))
        XCTAssertFalse(TagBarcodeRecord.isValidScanPayload("BCJH-52-81GG"))
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

final class AppLanguageTests: XCTestCase {
    private let languageStorageKey = AppLanguage.storageKey
    private var originalLanguageRawValue: String?

    override func setUp() {
        super.setUp()
        originalLanguageRawValue = UserDefaults.standard.string(forKey: languageStorageKey)
    }

    override func tearDown() {
        if let value = originalLanguageRawValue {
            UserDefaults.standard.set(value, forKey: languageStorageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: languageStorageKey)
        }
        super.tearDown()
    }

    func testCurrentLanguageFallsBackToJapaneseWithoutStoredValue() {
        UserDefaults.standard.removeObject(forKey: languageStorageKey)
        XCTAssertEqual(AppLanguage.current, .japanese)
    }

    func testCurrentLanguageFallsBackToJapaneseForInvalidStoredValue() {
        UserDefaults.standard.set("unsupported", forKey: languageStorageKey)
        XCTAssertEqual(AppLanguage.current, .japanese)
    }

    func testAppLocalizationRespectsCurrentLocaleLanguage() {
        UserDefaults.standard.set(AppLanguage.english.rawValue, forKey: languageStorageKey)
        XCTAssertEqual(AppLocalization.string("設定"), "Settings")

        UserDefaults.standard.set(AppLanguage.japanese.rawValue, forKey: languageStorageKey)
        XCTAssertEqual(AppLocalization.string("言語"), "言語")
    }

    func testEnglishLocalizationUsesNaturalPluralForms() {
        UserDefaults.standard.set(AppLanguage.english.rawValue, forKey: languageStorageKey)

        XCTAssertEqual(AppLocalization.string("\(1)箱"), "1 box")
        XCTAssertEqual(AppLocalization.string("\(2)箱"), "2 boxes")
        XCTAssertEqual(
            AppLocalization.string("これまでに\(1)セッションを端末内へ保存しています"),
            "1 session saved on this device"
        )
        XCTAssertEqual(
            AppLocalization.string("これまでに\(2)セッションを端末内へ保存しています"),
            "2 sessions saved on this device"
        )
    }
}

final class AutoAdvanceSettingsTests: XCTestCase {
    func testDefaultsAreOffWithAThreeSecondCountdown() {
        let suiteName = "AutoAdvanceSettingsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertFalse(AutoAdvanceSettings.isEnabled(in: defaults))
        XCTAssertEqual(AutoAdvanceSettings.delay(in: defaults), .threeSeconds)

        defaults.set(true, forKey: AutoAdvanceSettings.enabledKey)
        defaults.set(5, forKey: AutoAdvanceSettings.delaySecondsKey)

        XCTAssertTrue(AutoAdvanceSettings.isEnabled(in: defaults))
        XCTAssertEqual(AutoAdvanceSettings.delay(in: defaults), .fiveSeconds)
        XCTAssertEqual(AutoAdvanceDelay.allCases.map(\.rawValue), [1, 3, 5])
    }
}

@MainActor
final class BluetoothScannerServiceTests: XCTestCase {
    private var originalLanguageRawValue: String?

    override func setUp() {
        super.setUp()
        originalLanguageRawValue = UserDefaults.standard.string(forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(AppLanguage.japanese.rawValue, forKey: AppLanguage.storageKey)
    }

    override func tearDown() {
        if let originalLanguageRawValue {
            UserDefaults.standard.set(originalLanguageRawValue, forKey: AppLanguage.storageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: AppLanguage.storageKey)
        }
        super.tearDown()
    }

    func testInitialGATTSetupCodesUseTheVerifiedInateckSequence() {
        XCTAssertEqual(
            BluetoothScannerSetupCode.allCases.map(\.rawValue),
            ["/*EnterSet*/", "/*BLE_GATT*/", "/*ExitSave*/"]
        )
    }

    func testSymbologyModeStatusTextExplainsActiveRestriction() {
        XCTAssertEqual(BluetoothScannerSymbologyMode(expectedCode: .qr), .sessionCodes)
        XCTAssertEqual(BluetoothScannerSymbologyMode(expectedCode: .barcode), .sessionCodes)
        XCTAssertEqual(BluetoothScannerSymbologyMode(expectedCode: nil), .unrestricted)
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.unrestricted.statusText,
            "読取対象：接続前の設定へ復元済み"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.sessionCodes.statusText,
            "読取対象：QR・Code 128（照合セッション）"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.qrOnly.statusText,
            "読取対象：QRのみ（旧設定から復旧中）"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.code128Only.statusText,
            "読取対象：Code 128のみ（旧設定から復旧中）"
        )
    }

    func testLogicalStepChangesDoNotReconfigureReadySessionMode() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])

        var updates: [BluetoothScannerConfigurationState] = []
        let observation = service.$configurationState
            .dropFirst()
            .sink { updates.append($0) }

        service.setExpectedCode(.qr)
        XCTAssertEqual(updates, [.ready])
        updates.removeAll()

        service.setExpectedCode(.barcode)
        service.setExpectedCode(.qr)

        XCTAssertTrue(updates.isEmpty)
        XCTAssertEqual(service.expectedCode, .qr)
        XCTAssertEqual(service.persistedSymbologyMode, .sessionCodes)
        withExtendedLifetime(observation) {}
    }

    func testSymbologyCommandUsesEveryBarcodeTypeReportedByScanner() throws {
        let settings = """
        {"data":[
          {"area":"11","value":"1","name":"code39_on"},
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":1,"name":"code128_on"},
          {"area":"12","value":"0","name":"ean_13_on"},
          {"area":"15","value":"1","name":"USPS_On","flag":3019},
          {"area":"34","value":"1","name":"rss_expanded_on","flag":3038},
          {"area":"31","value":"1","name":"shake_reminder"}
        ]}
        """
        let original = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(from: settings)
        )
        XCTAssertEqual(
            original,
            [
                "code39_on": 1,
                "qrcode_on": 1,
                "code128_on": 1,
                "ean_13_on": 0,
                "USPS_On": 1,
                "rss_expanded_on": 1
            ]
        )

        let restricted = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(
                for: .sessionCodes,
                original: original
            )
        )
        XCTAssertEqual(
            restricted,
            [
                "code39_on": 0,
                "qrcode_on": 1,
                "code128_on": 1,
                "ean_13_on": 0,
                "USPS_On": 0,
                "rss_expanded_on": 0
            ]
        )

        let command = try XCTUnwrap(
            BluetoothScannerService.symbologySettingCommand(
                values: restricted,
                settings: settings
            )
        )
        let data = try XCTUnwrap(command.data(using: .utf8))
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [[String: String]]
        )

        XCTAssertEqual(items.first(where: { $0["name"] == "qrcode_on" })?["area"], "42")
        XCTAssertEqual(items.first(where: { $0["name"] == "qrcode_on" })?["value"], "1")
        XCTAssertEqual(items.first(where: { $0["name"] == "code128_on" })?["area"], "17")
        XCTAssertEqual(items.first(where: { $0["name"] == "code128_on" })?["value"], "1")
        XCTAssertEqual(items.first(where: { $0["name"] == "code39_on" })?["value"], "0")
        XCTAssertEqual(items.first(where: { $0["name"] == "ean_13_on" })?["value"], "0")
        XCTAssertEqual(items.first(where: { $0["name"] == "USPS_On" })?["value"], "0")
        XCTAssertEqual(items.first(where: { $0["name"] == "rss_expanded_on" })?["value"], "0")
        XCTAssertNil(items.first(where: { $0["name"] == "shake_reminder" }))
        XCTAssertTrue(BluetoothScannerService.hasRequiredSymbologySettings(settings))
        XCTAssertFalse(
            BluetoothScannerService.hasRequiredSymbologySettings(
                "{\"data\":[{\"area\":\"42\",\"value\":\"1\",\"name\":\"qrcode_on\"}]}"
            )
        )
    }

    func testOriginalBarcodeSettingsRoundTripWithoutChangingValues() throws {
        let settings = """
        {"data":[
          {"area":"11","value":"0","name":"code39_on"},
          {"area":"11","value":"1","name":"code128_on"},
          {"area":"12","value":"1","name":"ean_13_on"},
          {"area":"28","value":"0","name":"qrcode_on"},
          {"area":"27","value":"1","name":"datamatrix_on"}
        ]}
        """
        let original = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(from: settings)
        )
        let restricted = try XCTUnwrap(
            BluetoothScannerService.symbologySettingValues(
                for: .sessionCodes,
                original: original
            )
        )
        XCTAssertEqual(restricted.values.filter { $0 == 1 }.count, 2)
        XCTAssertEqual(restricted["code128_on"], 1)
        XCTAssertEqual(restricted["qrcode_on"], 1)

        let restoreCommand = try XCTUnwrap(
            BluetoothScannerService.symbologySettingCommand(
                values: original,
                settings: settings
            )
        )
        let data = try XCTUnwrap(restoreCommand.data(using: .utf8))
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [[String: String]]
        )
        XCTAssertEqual(
            Dictionary(uniqueKeysWithValues: items.compactMap { item in
                guard let name = item["name"], let value = item["value"].flatMap(Int.init) else {
                    return nil
                }
                return (name, value)
            }),
            original
        )
    }

    func testNormalizedPayloadRemovesOnlyTrailingTransportTerminators() {
        XCTAssertEqual(
            BluetoothScannerService.normalizedPayload("QR DATA   0*\r\n\0"),
            "QR DATA   0*"
        )
        XCTAssertEqual(BluetoothScannerService.normalizedPayload("  KEEP  "), "  KEEP  ")
    }

    func testDecodedSDKScanPayloadUnwrapsPinnedIOSSDKJSON() throws {
        let expected = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*\n"
        let callback = """
        {"code":"DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*\\n","source_code":"44434C5036373533303042434A483532383147473032303030303132303030303030313230304C303030303030303030303030424C4244494C4C553932202020302A0A","status":0}
        """

        XCTAssertEqual(BluetoothScannerService.decodedSDKScanPayload(callback), expected)
    }

    func testDecodedSDKScanPayloadUnwrapsScannerLibNotificationJSON() throws {
        let expected = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*\n"
        let jsonObject: [String: Any] = [
            "notify_type": 1,
            "notify_status": 1,
            "notify_data": Array(expected.utf8)
        ]
        let data = try JSONSerialization.data(withJSONObject: jsonObject)
        let callback = try XCTUnwrap(String(data: data, encoding: .utf8))

        XCTAssertEqual(BluetoothScannerService.decodedSDKScanPayload(callback), expected)
    }

    func testDecodedSDKScanPayloadKeepsDirectTextAndRejectsNonScanNotifications() throws {
        XCTAssertEqual(BluetoothScannerService.decodedSDKScanPayload("DIRECT-CODE"), "DIRECT-CODE")

        let configuration = """
        {"notify_type":0,"notify_status":1,"notify_data":[1,2,3]}
        """
        let incomplete = """
        {"notify_type":1,"notify_status":0,"notify_data":[68,67]}
        """
        let failedCode = """
        {"code":"BAD","source_code":"424144","status":1}
        """
        XCTAssertNil(BluetoothScannerService.decodedSDKScanPayload(configuration))
        XCTAssertNil(BluetoothScannerService.decodedSDKScanPayload(incomplete))
        XCTAssertNil(BluetoothScannerService.decodedSDKScanPayload(failedCode))
    }

    func testSimulatorDiscoveryConnectAndPreferredReconnect() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)

        service.startDiscovery()
        XCTAssertEqual(service.devices.count, 1)
        XCTAssertFalse(service.isConnected)

        service.connect(service.devices[0])
        XCTAssertTrue(service.isConnected)
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.preferredDeviceIDKey),
            service.devices[0].id
        )

        let restored = BluetoothScannerService(defaults: defaults)
        restored.reconnectPreferredDevice()
        XCTAssertTrue(restored.isConnected)
        XCTAssertEqual(restored.connectedDevice?.id, service.devices[0].id)
    }

    func testDiagnosticsKeepOnlyRecentConnectionEventsWithoutScanPayloads() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)

        for _ in 0..<BluetoothScannerService.diagnosticEventLimit {
            service.startDiscovery()
            service.stopDiscovery()
        }
        service.connect(service.devices[0])
        service.simulateScan("PRIVATE-SCAN-PAYLOAD")

        XCTAssertEqual(service.diagnosticEvents.count, BluetoothScannerService.diagnosticEventLimit)
        XCTAssertTrue(
            service.diagnosticEvents.contains(where: { $0.message.contains("Connect requested") })
        )
        XCTAssertFalse(
            service.diagnosticEvents.contains(where: { $0.message.contains("PRIVATE-SCAN-PAYLOAD") })
        )

        let relaunched = BluetoothScannerService(defaults: defaults)
        XCTAssertLessThanOrEqual(
            relaunched.diagnosticEvents.count,
            BluetoothScannerService.diagnosticEventLimit
        )
        XCTAssertTrue(
            relaunched.diagnosticEvents.contains(where: { $0.message.contains("Connect requested") })
        )
    }

    func testRelaunchRecoversRestrictedScannerToSafeBaseline() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let firstLaunch = BluetoothScannerService(defaults: defaults)
        firstLaunch.startDiscovery()
        firstLaunch.connect(firstLaunch.devices[0])
        firstLaunch.setExpectedCode(.barcode)

        XCTAssertEqual(firstLaunch.persistedSymbologyMode, .sessionCodes)
        XCTAssertEqual(
            firstLaunch.persistedSymbologySnapshot?.values,
            [
                "code39_on": 1,
                "code128_on": 1,
                "ean_13_on": 1,
                "qrcode_on": 1,
                "datamatrix_on": 1
            ]
        )

        // 照合セッション中にプロセスが終了した状況を、同じUserDefaultsを使う
        // 新しいサービスインスタンスで再現する。照合画面がなくても再接続時に
        // 保存した照合開始前の全バーコード設定へ戻す。
        let relaunched = BluetoothScannerService(defaults: defaults)
        relaunched.reconnectPreferredDevice()

        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertNil(relaunched.expectedCode)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .unrestricted)
        XCTAssertNil(relaunched.persistedSymbologySnapshot)
    }

    func testRelaunchRecoversLegacyCode128OnlyStateFromStuckBuild() throws {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let deviceID = "SIMULATOR-BCST-47"
        let snapshot = BluetoothScannerSymbologySnapshot(
            deviceID: deviceID,
            values: [
                "code39_on": 1,
                "code128_on": 1,
                "ean_13_on": 1,
                "qrcode_on": 1,
                "datamatrix_on": 1
            ]
        )
        defaults.set(deviceID, forKey: BluetoothScannerService.preferredDeviceIDKey)
        defaults.set(deviceID, forKey: BluetoothScannerService.lastKnownDeviceIDKey)
        defaults.set("BCST-47 (Simulator)", forKey: BluetoothScannerService.lastKnownDeviceNameKey)
        defaults.set(
            BluetoothScannerSymbologyMode.code128Only.rawValue,
            forKey: BluetoothScannerService.symbologyRecoveryModeKey
        )
        defaults.set(
            try JSONEncoder().encode(snapshot),
            forKey: BluetoothScannerService.symbologySnapshotKey
        )

        let relaunched = BluetoothScannerService(defaults: defaults)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .code128Only)

        relaunched.reconnectPreferredDevice()

        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertNil(relaunched.expectedCode)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .unrestricted)
        XCTAssertNil(relaunched.persistedSymbologySnapshot)
    }

    func testManualDisconnectRestoresSafeBaseline() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])
        service.setExpectedCode(.qr)

        XCTAssertEqual(service.persistedSymbologyMode, .sessionCodes)

        service.disconnect()

        XCTAssertFalse(service.isConnected)
        XCTAssertFalse(service.isReadyForScanning)
        XCTAssertNil(service.expectedCode)
        XCTAssertEqual(service.persistedSymbologyMode, .unrestricted)
        XCTAssertNil(service.persistedSymbologySnapshot)
    }

    func testManualDisconnectKeepsKnownDeviceAvailableForReconnectAfterSearch() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        let device = service.devices[0]
        service.connect(device)

        service.disconnect()

        XCTAssertNil(defaults.string(forKey: BluetoothScannerService.preferredDeviceIDKey))
        XCTAssertEqual(service.reconnectableDevice, device)

        // 実機SDKでは、iOSと接続済みのスキャナが広告を出さず検索結果が0件でも、
        // SDKキャッシュと保存済み端末を捨てずに再接続できる必要がある。
        service.startDiscovery()
        XCTAssertEqual(service.reconnectableDevice, device)
        service.reconnectKnownDevice()

        XCTAssertTrue(service.isReadyForScanning)
        XCTAssertEqual(service.connectedDevice, device)
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.preferredDeviceIDKey),
            device.id
        )
    }

    func testKnownDeviceSurvivesServiceRelaunchAfterManualDisconnect() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        let device = service.devices[0]
        service.connect(device)
        service.disconnect()

        let relaunched = BluetoothScannerService(defaults: defaults)

        XCTAssertEqual(relaunched.reconnectableDevice, device)
        relaunched.reconnectKnownDevice()
        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertEqual(relaunched.connectedDevice, device)
    }

    func testUpgradeMigratesLastConnectedDeviceFromDiagnosticsAfterOldManualDisconnect() throws {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let device = BluetoothScannerDevice(
            id: "9BBF90F3-6D04-6D53-69D5-E101FF61E548",
            name: "Nano 160D-636E-UNI"
        )
        let events = [
            BluetoothScannerDiagnosticEvent(
                date: Date(timeIntervalSince1970: 1),
                message: "Connected: \(device.name) [\(device.id)]"
            ),
            BluetoothScannerDiagnosticEvent(
                date: Date(timeIntervalSince1970: 2),
                message: "Scanner Bluetooth mode confirmed: GATT (2)"
            ),
            BluetoothScannerDiagnosticEvent(
                date: Date(timeIntervalSince1970: 3),
                message: "Disconnected"
            )
        ]
        defaults.set(
            try JSONEncoder().encode(events),
            forKey: BluetoothScannerService.diagnosticEventsKey
        )
        defaults.removeObject(forKey: BluetoothScannerService.preferredDeviceIDKey)

        let upgraded = BluetoothScannerService(defaults: defaults)

        XCTAssertEqual(upgraded.reconnectableDevice, device)
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.lastKnownDeviceIDKey),
            device.id
        )
        XCTAssertEqual(
            defaults.string(forKey: BluetoothScannerService.lastKnownDeviceNameKey),
            device.name
        )
    }

    func testDuplicateCallbackIsSuppressedInsideDebounceWindow() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        var currentDate = Date(timeIntervalSince1970: 1_700_000_000)
        let service = BluetoothScannerService(defaults: defaults, now: { currentDate })
        service.startDiscovery()
        service.connect(service.devices[0])
        var received: [String] = []
        service.onCode = { received.append($0) }

        service.simulateScan("ABC\r")
        currentDate.addTimeInterval(0.2)
        service.simulateScan("ABC\n")
        currentDate.addTimeInterval(0.8)
        service.simulateScan("ABC")

        XCTAssertEqual(received, ["ABC", "ABC"])
    }

    private let suiteName = "BluetoothScannerServiceTests"

    func testIlluminationSettingUsesReportedAreaAndConfirmsReadback() throws {
        let inventory = """
        {"status":0,"data":[
          {"area":"7","name":"code128_on","value":"1","flag":"2008"},
          {"area":"21","name":"qrcode_on","value":"1","flag":"2022"},
          {"area":"33","name":"lighting_lamp_control","value":"2","flag":"1003"},
          {"area":"1","name":"bt_mode_low","value":"0"}
        ]}
        """
        XCTAssertEqual(
            BluetoothScannerService.illuminationSetting(from: inventory),
            BluetoothScannerService.IlluminationSetting(area: "33", value: 2)
        )

        let command = try XCTUnwrap(
            BluetoothScannerService.illuminationCommand(settings: inventory, enabled: true)
        )
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(command.utf8)) as? [[String: String]]
        )
        XCTAssertEqual(items, [["area": "33", "name": "lighting_lamp_control", "value": "0"]])

        let readbackOn = inventory.replacingOccurrences(
            of: "\"lighting_lamp_control\",\"value\":\"2\"",
            with: "\"lighting_lamp_control\",\"value\":\"0\""
        )
        XCTAssertTrue(
            BluetoothScannerService.illuminationConfirmed(settings: readbackOn, area: "33", enabled: true)
        )
        XCTAssertFalse(
            BluetoothScannerService.illuminationConfirmed(settings: inventory, area: "33", enabled: true)
        )
        XCTAssertFalse(
            BluetoothScannerService.illuminationConfirmed(settings: readbackOn, area: "34", enabled: true)
        )

        // 項目が無い・重複・範囲外の値は扱わない。
        let missing = """
        {"status":0,"data":[{"area":"7","name":"code128_on","value":"1"}]}
        """
        XCTAssertNil(BluetoothScannerService.illuminationSetting(from: missing))
        XCTAssertNil(BluetoothScannerService.illuminationCommand(settings: missing, enabled: false))
        let duplicated = """
        {"status":0,"data":[
          {"area":"33","name":"lighting_lamp_control","value":"2"},
          {"area":"34","name":"lighting_lamp_control","value":"2"}
        ]}
        """
        XCTAssertNil(BluetoothScannerService.illuminationSetting(from: duplicated))
        let outOfRange = """
        {"status":0,"data":[{"area":"33","name":"lighting_lamp_control","value":"5"}]}
        """
        XCTAssertNil(BluetoothScannerService.illuminationSetting(from: outOfRange))
    }

    func testSimulatorIlluminationStartsOffAndFollowsRequests() {
        let defaults = isolatedDefaults()
        let service = BluetoothScannerService(defaults: defaults)
        XCTAssertEqual(service.illuminationState, .unknown)

        service.startDiscovery()
        service.connect(service.devices[0])
        XCTAssertEqual(service.illuminationState, .off)

        service.setIllumination(true)
        XCTAssertEqual(service.illuminationState, .on)
        service.setIllumination(false)
        XCTAssertEqual(service.illuminationState, .off)

        service.disconnect()
        XCTAssertEqual(service.illuminationState, .unknown)
        service.setIllumination(true)
        XCTAssertEqual(service.illuminationState, .unknown)
    }

    func testTuningProfileWritesOnlyDifferingItemsUsingReportedAreas() throws {
        let inventory = """
        {"status":0,"data":[
          {"area":"7","name":"code128_on","value":"1"},
          {"area":"40","name":"qrcode_read_more_code","value":"0"},
          {"area":"41","name":"datamatrix_read_multi","value":"1"},
          {"area":"42","name":"read_inverse_color","value":"0"},
          {"area":"43","name":"time_auto_off","value":"10"},
          {"area":"44","name":"auto_close_mode","value":"10"},
          {"area":"33","name":"lighting_lamp_control","value":"2"}
        ]}
        """
        let present = try XCTUnwrap(BluetoothScannerService.tuningItemsPresent(in: inventory))
        XCTAssertEqual(
            present.map(\.name),
            ["qrcode_read_more_code", "datamatrix_read_multi", "read_inverse_color", "auto_close_mode"]
        )

        let differences = BluetoothScannerService.tuningDifferences(settings: inventory)
        XCTAssertEqual(
            differences,
            [
                BluetoothScannerService.TuningItem(name: "datamatrix_read_multi", value: 0),
                BluetoothScannerService.TuningItem(name: "auto_close_mode", value: 20)
            ]
        )

        let command = try XCTUnwrap(
            BluetoothScannerService.tuningCommand(settings: inventory, items: differences)
        )
        let items = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(command.utf8)) as? [[String: String]]
        )
        XCTAssertEqual(items.count, 2)
        XCTAssertTrue(items.contains(["area": "41", "name": "datamatrix_read_multi", "value": "0"]))
        XCTAssertTrue(items.contains(["area": "44", "name": "auto_close_mode", "value": "20"]))

        // 既に一致していれば書く項目はない。inventoryに対象項目が無ければ空。
        let matching = inventory
            .replacingOccurrences(of: "\"datamatrix_read_multi\",\"value\":\"1\"", with: "\"datamatrix_read_multi\",\"value\":\"0\"")
            .replacingOccurrences(of: "\"auto_close_mode\",\"value\":\"10\"", with: "\"auto_close_mode\",\"value\":\"20\"")
        XCTAssertTrue(BluetoothScannerService.tuningDifferences(settings: matching).isEmpty)
        XCTAssertNil(BluetoothScannerService.tuningCommand(settings: matching, items: []))
        let none = """
        {"status":0,"data":[{"area":"7","name":"code128_on","value":"1"}]}
        """
        XCTAssertEqual(BluetoothScannerService.tuningItemsPresent(in: none), [])
        XCTAssertNil(BluetoothScannerService.tuningItemsPresent(in: "not json"))
    }

    func testSimulatorTuningStateFollowsConnection() {
        let defaults = isolatedDefaults()
        let service = BluetoothScannerService(defaults: defaults)
        XCTAssertEqual(service.tuningState, .unknown)
        service.startDiscovery()
        service.connect(service.devices[0])
        XCTAssertEqual(service.tuningState, .matched(applied: false))
        service.disconnect()
        XCTAssertEqual(service.tuningState, .unknown)
    }

    func testDiagnosticLogKeepsRecentEventsAndOmitsPayloads() throws {
        let defaults = isolatedDefaults()
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])
        service.setExpectedCode(.qr)
        var delivered: [String] = []
        service.onCode = { delivered.append($0) }

        let payload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        service.simulateScan(payload)
        XCTAssertEqual(delivered, [payload])

        let log = service.diagnosticLogText()
        XCTAssertTrue(log.contains("Scan callback accepted"))
        XCTAssertFalse(log.contains("BCJH5281GG"))
        XCTAssertFalse(log.contains(payload))
        XCTAssertTrue(log.contains("symbology mode: sessionCodes"))

        // 上限を超えた古いイベントは捨て、保持分だけを永続化する。
        for _ in 0..<(BluetoothScannerService.diagnosticEventLimit + 40) {
            service.stopDiscovery()
        }
        XCTAssertEqual(service.diagnosticEvents.count, BluetoothScannerService.diagnosticEventLimit)
        let data = try XCTUnwrap(defaults.data(forKey: BluetoothScannerService.diagnosticEventsKey))
        let persisted = try JSONDecoder().decode([BluetoothScannerDiagnosticEvent].self, from: data)
        XCTAssertEqual(persisted.count, BluetoothScannerService.diagnosticEventLimit)

        service.clearDiagnosticEvents()
        XCTAssertTrue(service.diagnosticEvents.isEmpty)
        XCTAssertNil(defaults.data(forKey: BluetoothScannerService.diagnosticEventsKey))
    }

    private func isolatedDefaults() -> UserDefaults {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

@MainActor
final class BluetoothScannerFlowTests: XCTestCase {
    private var originalLanguageRawValue: String?

    override func setUp() {
        super.setUp()
        originalLanguageRawValue = UserDefaults.standard.string(forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(AppLanguage.japanese.rawValue, forKey: AppLanguage.storageKey)
    }

    override func tearDown() {
        if let originalLanguageRawValue {
            UserDefaults.standard.set(originalLanguageRawValue, forKey: AppLanguage.storageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: AppLanguage.storageKey)
        }
        super.tearDown()
    }

    func testSuccessfulMatchCountsDownAndAutomaticallyStartsNextScan() async {
        let context = makeContext(
            autoAdvanceEnabled: true,
            autoAdvanceTickDuration: .milliseconds(40)
        )
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        let countdownUpdated = expectation(description: "countdown updates visibly")
        let nextScanStarted = expectation(description: "next QR scan starts")
        let countdownObservation = context.viewModel.$autoAdvanceSecondsRemaining
            .dropFirst()
            .sink { remaining in
                if remaining == 2 { countdownUpdated.fulfill() }
            }
        let stepObservation = context.viewModel.$step
            .dropFirst()
            .sink { step in
                if step == .qr { nextScanStarted.fulfill() }
            }

        context.viewModel.runDemo(shouldMatch: true)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 3)

        await fulfillment(of: [countdownUpdated, nextScanStarted], timeout: 2, enforceOrder: true)
        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        withExtendedLifetime((countdownObservation, stepObservation)) {}
    }

    func testTurningAutoAdvanceOffCancelsCountdownAndKeepsResultVisible() async {
        let context = makeContext(
            autoAdvanceEnabled: true,
            autoAdvanceTickDuration: .milliseconds(30)
        )
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 3)

        context.viewModel.setAutoAdvanceEnabled(false)
        try? await Task.sleep(for: .milliseconds(120))

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
    }

    func testChangingCountdownToFiveSecondsRestartsVisibleCount() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 3)

        context.viewModel.setAutoAdvanceDelay(.fiveSeconds)

        XCTAssertEqual(context.viewModel.autoAdvanceDelay, .fiveSeconds)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 5)
        context.viewModel.setAutoAdvanceEnabled(false)
    }

    func testChangingCountdownToOneSecondRestartsVisibleCount() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        context.viewModel.setAutoAdvanceDelay(.oneSecond)

        XCTAssertEqual(context.viewModel.autoAdvanceDelay, .oneSecond)
        XCTAssertEqual(context.viewModel.autoAdvanceSecondsRemaining, 1)
        context.viewModel.setAutoAdvanceEnabled(false)
    }

    func testMismatchNeverStartsAutoAdvanceCountdown() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: false)

        XCTAssertEqual(context.viewModel.step, .result(.mismatch))
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
    }

    func testSuccessfulPayloadCannotBeCountedTwiceInActiveSession() {
        let context = makeContext(autoAdvanceEnabled: true)
        defer { context.cleanup() }

        context.viewModel.runDemo(shouldMatch: true)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        context.viewModel.reset()
        context.viewModel.runDemo(shouldMatch: true)

        XCTAssertEqual(context.viewModel.step, .result(.duplicate))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 0)
        XCTAssertNil(context.viewModel.autoAdvanceSecondsRemaining)
        XCTAssertTrue(context.viewModel.message.contains("すでに照合済み"))
        XCTAssertTrue(context.viewModel.message.contains("照合件数に加えていません"))
    }

    func testDifferentBoxQRsWithSameBarcodeAreBothCounted() async {
        let context = makeContext()
        defer { context.cleanup() }
        let firstBoxQR = "DAAL134150BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        let secondBoxQR = "DAAL134140BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
        let sharedBarcode = "BCJH-55-81GG@1KVQ0C"

        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(firstBoxQR)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(sharedBarcode)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)

        context.viewModel.reset()
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(secondBoxQR)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(sharedBarcode)

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 2)
        XCTAssertEqual(context.viewModel.sessionBoxNumber, 2)
    }

    func testBluetoothRejectsCode128BeforeQRWithoutAdvancing() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertTrue(context.service.isReadyForScanning)
        XCTAssertEqual(context.service.expectedCode, .qr)
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("先に"))

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)

        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testBluetoothRejectsQRWhileWaitingForCode128() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))

        let otherQR = "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
        context.service.simulateScan(otherQR)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(context.viewModel.message.contains("Code 128"))
        XCTAssertTrue(context.service.isReadyForScanning)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testRereadQRClearsWrongQRAndReturnsBluetoothToQRWaiting() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)

        context.viewModel.rereadQR()

        XCTAssertEqual(context.viewModel.step, .qr)
        XCTAssertTrue(context.viewModel.qrValue.isEmpty)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)
        XCTAssertTrue(context.viewModel.message.contains("別の"))
    }

    func testBluetoothQRThenBarcodeCompletesMatchImmediately() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        var configurationUpdates: [BluetoothScannerConfigurationState] = []
        let configurationObservation = context.service.$configurationState
            .dropFirst()
            .sink { configurationUpdates.append($0) }

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertFalse(context.viewModel.isCameraRunning)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload + "\r\n")
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.qrValue.hasSuffix("   0*"))

        // サービス側の短時間デバウンスを越えて同じQRが再通知されても、
        // 次ステップのCode 128として誤確定しない。
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload + "\r\n")
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)
        XCTAssertTrue(configurationUpdates.isEmpty)
        XCTAssertTrue(context.service.isReadyForScanning)

        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload + "\r")

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
        XCTAssertTrue(configurationUpdates.isEmpty)
        withExtendedLifetime(configurationObservation) {}
    }

    func testConnectedBluetoothBecomesDefaultButManualCameraSelectionIsPreserved() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])

        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(context.viewModel.message.contains("BCST-47"))

        context.viewModel.selectInputSource(.camera)
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .camera)
    }

    func testBluetoothReadyRestoresInstructionAfterConfigurationMessage() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.viewModel.handleBluetoothConfigurationState(.configuring)
        XCTAssertTrue(context.viewModel.message.contains("設定しています"))

        context.viewModel.handleBluetoothConfigurationState(.ready)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(context.viewModel.message.contains("QRコードを読み取ってください"))
        XCTAssertFalse(context.viewModel.message.contains("設定しています"))
    }

    func testBluetoothBackgroundRestoresBaselineAndForegroundReappliesCurrentStep() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        context.viewModel.prepareForBackground()

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertNil(context.service.expectedCode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .unrestricted)

        context.viewModel.resumeAfterForeground()

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testInactivePhaseKeepsBluetoothSymbologyRestriction() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)

        var configurationUpdates: [BluetoothScannerConfigurationState] = []
        let observation = context.service.$configurationState
            .dropFirst()
            .sink { configurationUpdates.append($0) }

        // Control Centerや通知センターなどの一時的な非アクティブ化では、
        // スキャナーの読み取り設定を復元・再制限しない。
        context.viewModel.prepareForInactive()
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        context.viewModel.resumeAfterForeground()
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertTrue(configurationUpdates.isEmpty)
        XCTAssertTrue(context.service.isReadyForScanning)
        withExtendedLifetime(observation) {}
    }

    func testActivePhaseRestartsCameraStoppedByInactivePhase() async {
        let context = makeContext()
        defer { context.cleanup() }
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        context.viewModel.cameraScannerDidStart(context.viewModel.camera)
        XCTAssertTrue(context.viewModel.isCameraRunning)

        context.viewModel.prepareForInactive()
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(context.viewModel.isCameraRunning)
        XCTAssertFalse(context.viewModel.isCameraStarting)

        // 一時的な非アクティブ化で止めたカメラは、アクティブへ戻ったときに再開を要求する。
        context.viewModel.resumeAfterForeground()
        XCTAssertTrue(context.viewModel.isCameraStarting)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
    }

    func testActivePhaseDoesNotRestartCameraStoppedByUser() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.viewModel.cameraScannerDidStart(context.viewModel.camera)
        context.viewModel.stopCamera(showMessage: true)
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(context.viewModel.isCameraRunning)

        context.viewModel.prepareForInactive()
        context.viewModel.resumeAfterForeground()

        XCTAssertFalse(context.viewModel.isCameraStarting)
        XCTAssertFalse(context.viewModel.isCameraRunning)
    }

    func testEndingBluetoothSessionRestoresUnrestrictedBaseline() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)

        let completed = expectation(description: "Bluetooth session ended")
        context.viewModel.prepareForSessionEnd {
            completed.fulfill()
        }
        await fulfillment(of: [completed], timeout: 2)

        XCTAssertNil(context.service.expectedCode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .unrestricted)
    }

    func testEndingSessionWaitsForCameraStopBeforeCompleting() async {
        let queue = DispatchQueue(label: "BluetoothScannerFlowTests.suspended-camera")
        queue.suspend()
        let camera = CameraScanner(sessionQueue: queue)
        let context = makeContext(camera: camera)
        defer { context.cleanup() }
        let shutdownCompleted = expectation(description: "session end waits for camera")
        var didComplete = false

        // 実セッションの開始通知と同じ状態を作る。停止キューを止めている間は
        // PreviewLayerを外さないため、UI上もrunningのまま維持される必要がある。
        context.viewModel.cameraScannerDidStart(camera)
        XCTAssertTrue(context.viewModel.isCameraRunning)

        context.viewModel.prepareForSessionEnd {
            didComplete = true
            shutdownCompleted.fulfill()
        }

        XCTAssertTrue(context.viewModel.isEndingSession)
        XCTAssertTrue(context.viewModel.isCameraRunning)
        XCTAssertFalse(didComplete)
        queue.resume()

        await fulfillment(of: [shutdownCompleted], timeout: 2)
        XCTAssertTrue(didComplete)
        XCTAssertFalse(context.viewModel.isEndingSession)
        XCTAssertFalse(context.viewModel.isCameraRunning)
    }

    func testViewModelDeinitWaitsForCameraStop() async {
        let queue = DispatchQueue(label: "BluetoothScannerFlowTests.deinit-camera")
        queue.suspend()
        var camera: CameraScanner? = CameraScanner(sessionQueue: queue)
        weak var weakCamera = camera
        var context: (
            service: BluetoothScannerService,
            viewModel: ScannerViewModel,
            store: HistoryStore,
            cleanup: () -> Void
        )? = makeContext(camera: camera!)
        let stopDrained = expectation(description: "deinit camera stop drained")

        context?.cleanup()
        context = nil
        camera = nil

        // ScannerViewModel.deinitがstopをキューへ積み、処理完了まで
        // CameraScanner自身を保持していることを確認する。
        XCTAssertNotNil(weakCamera)
        queue.async {
            Task { @MainActor in
                stopDrained.fulfill()
            }
        }
        queue.resume()

        await fulfillment(of: [stopDrained], timeout: 2)
        XCTAssertNil(weakCamera)
    }

    func testBluetoothConfigurationFailureFallsBackToCameraThenReturnsWhenReady() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)

        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertTrue(context.viewModel.message.contains("カメラへ切り替えました"))

        // 設定失敗は利用者の選択ではないので、復旧してReadyへ戻ればBluetoothへ自動で戻る。
        context.service.retryConfiguration()
        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        // カメラ停止の完了を待ってから、現在工程がスキャナへ再適用されたことを確認する。
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.service.persistedSymbologyMode, .sessionCodes)
    }

    func testSelectingBluetoothWhileConfigurationFailedRequestsRetry() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        // 復元書込も失敗したままなら、サービスは失敗状態に留まる。
        XCTAssertEqual(context.service.configurationState, .failed("設定に失敗しました。"))

        context.viewModel.selectInputSource(.bluetooth)

        XCTAssertTrue(context.viewModel.message.contains("やり直しています"))
        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(context.service.expectedCode, .qr)
    }

    func testForegroundResumeRetriesFailedBluetoothConfiguration() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertEqual(context.service.configurationState, .failed("設定に失敗しました。"))

        context.viewModel.resumeAfterForeground()

        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
    }

    func testRepeatedBluetoothConfigurationFailuresStopAutomaticReturn() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        for _ in 0..<ScannerViewModel.bluetoothAutomaticReturnFailureLimit {
            context.viewModel.handleBluetoothConfigurationState(.ready)
            XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
            context.service.simulateConfigurationFailure("設定に失敗しました。")
            context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
            XCTAssertEqual(context.viewModel.inputSource, .camera)
            context.service.retryConfiguration()
        }

        // 連続失敗の上限に達した後は、Readyへ戻ってもカメラのまま維持する。
        XCTAssertTrue(context.service.isReadyForScanning)
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        // 利用者が明示的に選び直せばBluetoothへ戻れる。
        context.viewModel.selectInputSource(.bluetooth)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
    }

    func testAcceptedBluetoothScanResetsConfigurationFailureCount() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        context.service.retryConfiguration()
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        try? await Task.sleep(for: .milliseconds(300))

        // 読取が受理されれば失敗回数はリセットされ、次の失敗でも自動復帰する。
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        context.service.simulateConfigurationFailure("設定に失敗しました。")
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertEqual(context.viewModel.step, .barcode)
        context.service.retryConfiguration()
        context.viewModel.handleBluetoothConfigurationState(context.service.configurationState)
        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(context.service.expectedCode, .barcode)
    }

    func testCameraRejectsUnrelatedQRAndKeepsWaitingForKanbanQR() {
        let context = makeContext()
        defer { context.cleanup() }
        let camera = context.viewModel.camera
        XCTAssertEqual(context.viewModel.inputSource, .camera)

        // 無関係なQR（URL、短い値、66桁だが必須フィールド不正）はQR待機を保持する。
        for payload in [
            "https://example.com/tissue",
            "BCJH5281GG",
            String(repeating: "X", count: 66)
        ] {
            context.viewModel.cameraScanner(camera, didRead: payload, type: .qr)
            XCTAssertEqual(context.viewModel.step, .qr, payload)
            XCTAssertTrue(context.viewModel.qrValue.isEmpty, payload)
            XCTAssertTrue(context.viewModel.message.contains("QRコードではありません"), payload)
        }
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        context.viewModel.cameraScanner(camera, didRead: ScannerViewModel.sampleQRPayload, type: .qr)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.qrValue, ScannerViewModel.sampleQRPayload)
    }

    func testBluetoothScanDuringMismatchResultWarnsWithoutAdvancing() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))
        context.service.simulateScan(ScannerViewModel.sampleMismatchBarcodePayload)
        XCTAssertEqual(context.viewModel.step, .result(.mismatch))

        // 不一致の結果表示中のトリガーは進めず、確認操作が必要なことを知らせる。
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .result(.mismatch))
        XCTAssertTrue(context.viewModel.message.contains("次のコードを照合"))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 0)

        context.viewModel.reset()
        XCTAssertEqual(context.viewModel.step, .qr)
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)
    }

    func testBluetoothDisconnectKeepsCurrentStepAndFallsBackToCamera() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.selectInputSource(.bluetooth)
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        try? await Task.sleep(for: .milliseconds(300))

        context.service.disconnect()
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .camera)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.qrValue, ScannerViewModel.sampleQRPayload)
        XCTAssertTrue(context.viewModel.message.contains("カメラへ切り替えました"))

        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.viewModel.qrValue, ScannerViewModel.sampleQRPayload)
    }

    private func makeContext(
        camera: CameraScanner = CameraScanner(),
        autoAdvanceEnabled: Bool = false,
        autoAdvanceDelay: AutoAdvanceDelay = .threeSeconds,
        autoAdvanceTickDuration: Duration = .seconds(1)
    ) -> (
        service: BluetoothScannerService,
        viewModel: ScannerViewModel,
        store: HistoryStore,
        cleanup: () -> Void
    ) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let storageURL = directory.appendingPathComponent("history.json")
        let defaultsName = "BluetoothScannerFlowTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsName)!
        let service = BluetoothScannerService(defaults: defaults)
        let store = HistoryStore(storageURL: storageURL)
        store.beginSession()
        let viewModel = ScannerViewModel(
            historyStore: store,
            bluetoothScanner: service,
            camera: camera,
            isAutoAdvanceEnabled: autoAdvanceEnabled,
            autoAdvanceDelay: autoAdvanceDelay,
            autoAdvanceTickDuration: autoAdvanceTickDuration
        )
        return (service, viewModel, store, {
            try? FileManager.default.removeItem(at: directory)
            defaults.removePersistentDomain(forName: defaultsName)
        })
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

    private func temporaryStorageURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("match-history.json")
    }
}
