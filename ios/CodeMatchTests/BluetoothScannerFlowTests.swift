import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

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

    func testBluetoothDistinguishesUnrelatedCodesFromWrongOrder() async {
        let context = makeContext()
        defer { context.cleanup() }
        context.service.startDiscovery()
        context.service.connect(context.service.devices[0])
        context.viewModel.handleBluetoothConnectionState(context.service.state)

        // QR待機: Code 128形式は順序違い、それ以外は無関係なコードとして案内する。
        context.service.simulateScan(ScannerViewModel.sampleBarcodePayload)
        XCTAssertTrue(context.viewModel.message.contains("読み取り順序が違います"))
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan("https://example.com/tissue")
        XCTAssertTrue(context.viewModel.message.contains("QRコードではありません"))
        XCTAssertEqual(context.viewModel.step, .qr)

        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan(ScannerViewModel.sampleQRPayload)
        XCTAssertEqual(context.viewModel.step, .barcode)

        // Code 128待機: QR形式は順序違い、それ以外は無関係なコードとして案内する。
        try? await Task.sleep(for: .milliseconds(800))
        let otherQR = "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
        context.service.simulateScan(otherQR)
        XCTAssertTrue(context.viewModel.message.contains("読み取り順序が違います"))
        try? await Task.sleep(for: .milliseconds(800))
        context.service.simulateScan("https://example.com/tissue")
        XCTAssertTrue(context.viewModel.message.contains("Code 128バーコードではありません"))
        XCTAssertEqual(context.viewModel.step, .barcode)
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
