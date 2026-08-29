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
}

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

@MainActor
final class BluetoothScannerServiceTests: XCTestCase {
    func testInitialGATTSetupCodesUseTheVerifiedInateckSequence() {
        XCTAssertEqual(
            BluetoothScannerSetupCode.allCases.map(\.rawValue),
            ["/*EnterSet*/", "/*BLE_GATT*/", "/*ExitSave*/"]
        )
    }

    func testSymbologyModeStatusTextExplainsActiveRestriction() {
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.unrestricted.statusText,
            "読取対象：QR／Code 128（安全状態）"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.qrOnly.statusText,
            "読取対象：QRのみ（照合ステップ1）"
        )
        XCTAssertEqual(
            BluetoothScannerSymbologyMode.code128Only.statusText,
            "読取対象：Code 128のみ（照合ステップ2）"
        )
    }

    func testRepeatedExpectedCodeDoesNotReconfigureReadyScanner() {
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
        service.setExpectedCode(.qr)

        XCTAssertEqual(updates, [.ready])
        XCTAssertEqual(service.persistedSymbologyMode, .qrOnly)
        withExtendedLifetime(observation) {}
    }

    func testSymbologyCommandUsesAreasReportedByScanner() throws {
        let settings = """
        {"data":[
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":1,"name":"code128_on"}
        ]}
        """
        let command = try XCTUnwrap(
            BluetoothScannerService.symbologySettingCommand(
                values: ["qrcode_on": 1, "code128_on": 0],
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
        XCTAssertEqual(items.first(where: { $0["name"] == "code128_on" })?["value"], "0")
        XCTAssertTrue(BluetoothScannerService.hasRequiredSymbologySettings(settings))
        XCTAssertFalse(
            BluetoothScannerService.hasRequiredSymbologySettings(
                "{\"data\":[{\"area\":\"42\",\"value\":\"1\",\"name\":\"qrcode_on\"}]}"
            )
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

        for _ in 0..<12 {
            service.startDiscovery()
            service.stopDiscovery()
        }
        service.connect(service.devices[0])
        service.simulateScan("PRIVATE-SCAN-PAYLOAD")

        XCTAssertEqual(service.diagnosticEvents.count, 20)
        XCTAssertTrue(
            service.diagnosticEvents.contains(where: { $0.message.contains("Connect requested") })
        )
        XCTAssertFalse(
            service.diagnosticEvents.contains(where: { $0.message.contains("PRIVATE-SCAN-PAYLOAD") })
        )

        let relaunched = BluetoothScannerService(defaults: defaults)
        XCTAssertLessThanOrEqual(relaunched.diagnosticEvents.count, 20)
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

        XCTAssertEqual(firstLaunch.persistedSymbologyMode, .code128Only)

        // Code 128待ちでプロセスが終了した状況を、同じUserDefaultsを使う
        // 新しいサービスインスタンスで再現する。照合画面がなくても再接続時に
        // QRとCode 128を両方有効にする安全状態へ戻す。
        let relaunched = BluetoothScannerService(defaults: defaults)
        relaunched.reconnectPreferredDevice()

        XCTAssertTrue(relaunched.isReadyForScanning)
        XCTAssertNil(relaunched.expectedCode)
        XCTAssertEqual(relaunched.persistedSymbologyMode, .unrestricted)
    }

    func testManualDisconnectRestoresSafeBaseline() {
        let defaults = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let service = BluetoothScannerService(defaults: defaults)
        service.startDiscovery()
        service.connect(service.devices[0])
        service.setExpectedCode(.qr)

        XCTAssertEqual(service.persistedSymbologyMode, .qrOnly)

        service.disconnect()

        XCTAssertFalse(service.isConnected)
        XCTAssertFalse(service.isReadyForScanning)
        XCTAssertNil(service.expectedCode)
        XCTAssertEqual(service.persistedSymbologyMode, .unrestricted)
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

    private func isolatedDefaults() -> UserDefaults {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

@MainActor
final class BluetoothScannerFlowTests: XCTestCase {
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
        XCTAssertNil(context.service.expectedCode)
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
    }

    func testBluetoothQRThenBarcodeCompletesMatchImmediately() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.viewModel.inputSource, .bluetooth)
        XCTAssertFalse(context.viewModel.isCameraRunning)

        context.service.simulateScan(ScannerViewModel.sampleQRPayload + "\r\n")
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.qrValue.hasSuffix("   0*"))

        // サービス側の短時間デバウンスを越えて同じQRが再通知されても、
        // 次ステップのCode 128として誤確定しない。
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload + "\r\n")
        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertTrue(context.viewModel.barcodeValue.isEmpty)

        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload + "\r")

        XCTAssertEqual(context.viewModel.step, .result(.match))
        XCTAssertEqual(context.store.activeSession?.matchedCount, 1)
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
        XCTAssertEqual(context.service.persistedSymbologyMode, .code128Only)

        context.viewModel.prepareForBackground()

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertNil(context.service.expectedCode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .unrestricted)

        context.viewModel.resumeAfterForeground()

        XCTAssertEqual(context.viewModel.step, .barcode)
        XCTAssertEqual(context.service.expectedCode, .barcode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .code128Only)
    }

    func testEndingBluetoothSessionRestoresUnrestrictedBaseline() {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        XCTAssertEqual(context.service.expectedCode, .qr)
        XCTAssertEqual(context.service.persistedSymbologyMode, .qrOnly)

        context.viewModel.prepareForSessionEnd()

        XCTAssertNil(context.service.expectedCode)
        XCTAssertEqual(context.service.persistedSymbologyMode, .unrestricted)
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

    private func makeContext() -> (
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
        let viewModel = ScannerViewModel(historyStore: store, bluetoothScanner: service)
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

    /// 同一品番のラベルが複数箱に貼られる運用のため、重複した照合もそのまま記録される。
    func testDuplicateMatchesAreRecordedAndGroupedAsBoxes() {
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
