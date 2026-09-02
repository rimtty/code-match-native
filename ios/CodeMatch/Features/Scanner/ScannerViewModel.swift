import AVFoundation
import Foundation
import SwiftUI

@MainActor
final class ScannerViewModel: ObservableObject {
    @Published private(set) var step: ScanStep = .qr
    @Published private(set) var inputSource: ScanInputSource = .camera
    @Published private(set) var qrValue = ""
    @Published private(set) var barcodeValue = ""
    @Published private(set) var isCameraRunning = false
    @Published private(set) var isCameraStarting = false
    @Published private(set) var isEndingSession = false
    @Published private(set) var message = ""
    @Published private(set) var focusPoint: CGPoint?
    /// 一致した品番がこのセッションで何箱目の照合かを示す通し番号。結果表示中以外は0。
    @Published private(set) var sessionBoxNumber = 0
    @Published private(set) var isAutoAdvanceEnabled: Bool
    @Published private(set) var autoAdvanceDelay: AutoAdvanceDelay
    @Published private(set) var autoAdvanceSecondsRemaining: Int?

    let camera: CameraScanner
    private let feedback = FeedbackPlayer.shared
    private let historyStore: HistoryStore
    private let bluetoothScanner: BluetoothScannerService
    private var scanLocked = false
    private var barcodeCandidate: (value: String, count: Int, date: Date)?
    private var autoAdvanceTask: Task<Void, Never>?
    private let autoAdvanceTickDuration: Duration
    /// 接続済みスキャナを初期入力にする一方、利用者がカメラを選んだ後は
    /// 同じ照合セッション内で自動的にBluetoothへ戻さないためのフラグ。
    private var cameraWasSelectedByUser = false
    private var localizedMessageBuilder: (() -> String)?

    var expectedCode: ExpectedCode? {
        switch step {
        case .qr: .qr
        case .barcode: .barcode
        case .result: nil
        }
    }

    private func setLocalizedMessage(_ builder: @escaping () -> String) {
        let localizedMessage = builder()
        message = localizedMessage
        localizedMessageBuilder = builder
    }

    func refreshLocalizedMessage() {
        if let builder = localizedMessageBuilder {
            message = builder()
        }
    }

    /// 実ラベル由来のサンプルペイロード(品番 BCJH-52-81GG)。デモ判定に使用する。
    static let sampleQRPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    static let sampleBarcodePayload = "BCJH-52-81GG@1N5X0C"
    static let sampleMismatchBarcodePayload = "BCJH-55-81GG@1KVV0C"

    var qrPartNumber: String? {
        CodeMatcher.partNumber(fromQR: qrValue)
    }

    var barcodePartNumber: String? {
        CodeMatcher.partNumber(fromBarcode: barcodeValue)
    }

    init(
        historyStore: HistoryStore,
        bluetoothScanner: BluetoothScannerService,
        camera: CameraScanner = CameraScanner(),
        isAutoAdvanceEnabled: Bool = AutoAdvanceSettings.isEnabled(),
        autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceSettings.delay(),
        autoAdvanceTickDuration: Duration = .seconds(1)
    ) {
        self.historyStore = historyStore
        self.bluetoothScanner = bluetoothScanner
        self.camera = camera
        self.isAutoAdvanceEnabled = isAutoAdvanceEnabled
        self.autoAdvanceDelay = autoAdvanceDelay
        self.autoAdvanceTickDuration = autoAdvanceTickDuration
        camera.delegate = self
        bluetoothScanner.onCode = { [weak self] value in
            self?.handleBluetoothScan(value)
        }
        setLocalizedMessage {
            AppLocalization.string("まず、納品書兼現品票のQRコードをカメラに映してください。")
        }

        if ProcessInfo.processInfo.arguments.contains("-demoMatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleBarcodePayload
            step = .result(.match)
            setLocalizedMessage { AppLocalization.string("品目番号が一致しています。") }
            historyStore.recordMatch(code: recordedCode, qrPayload: qrValue, barcodePayload: barcodeValue)
            sessionBoxNumber = historyStore.activeSessionMatchCount(code: recordedCode)
        } else if ProcessInfo.processInfo.arguments.contains("-demoMismatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleMismatchBarcodePayload
            step = .result(.mismatch)
            setLocalizedMessage {
                AppLocalization.string("品目番号が一致しません。納品書と現品の取り違えを確認してください。")
            }
        }
    }

    deinit {
        autoAdvanceTask?.cancel()
        // 予期しない画面破棄経路でも、非同期停止処理がCameraScanner自身を
        // 完了まで保持し、実行中のカメラデバイスを残さない。
        camera.stop()
    }

    func toggleCamera() {
        guard inputSource == .camera else { return }
        if isCameraRunning || isCameraStarting {
            stopCamera(showMessage: true)
        } else {
            startCamera()
        }
    }

    func startCamera() {
        guard !isEndingSession, let expectedCode else { return }
        inputSource = .camera
        isCameraStarting = true
        isCameraRunning = false
        setLocalizedMessage { AppLocalization.string("カメラを準備しています…") }
        camera.setActiveType(expectedCode.metadataType)
        camera.requestAccessAndStart()
    }

    func stopCamera(showMessage: Bool = false) {
        focusPoint = nil
        camera.stop { [weak self] in
            guard let self else { return }
            self.isCameraStarting = false
            self.isCameraRunning = false
            if showMessage {
                let isQr = self.expectedCode == .qr
                self.setLocalizedMessage {
                    isQr
                    ? AppLocalization.string("カメラを停止しました。「QRコードを読み取る」で再開できます。")
                    : AppLocalization.string("カメラを停止しました。「バーコードを読み取る」で再開できます。")
                }
            }
        }
    }

    func reset(automaticallyStartScanning: Bool = false) {
        cancelAutoAdvanceCountdown()
        camera.stop()
        step = .qr
        qrValue = ""
        barcodeValue = ""
        isCameraRunning = false
        isCameraStarting = false
        scanLocked = false
        barcodeCandidate = nil
        focusPoint = nil
        sessionBoxNumber = 0
        if inputSource == .bluetooth, bluetoothScanner.isConnected {
            bluetoothScanner.setExpectedCode(.qr)
            setLocalizedMessage { AppLocalization.string("BCST-47で納品書兼現品票のQRコードを読み取ってください。") }
        } else {
            bluetoothScanner.setExpectedCode(nil)
            inputSource = .camera
            setLocalizedMessage { AppLocalization.string("まず、納品書兼現品票のQRコードをカメラに映してください。") }
            if automaticallyStartScanning {
                startCamera()
            }
        }
    }

    /// バーコード待機中に、誤って読み取ったQRだけを破棄してQR工程へ戻す。
    /// セッションと照合済み件数は維持し、選択中の入力方法で直ちに読み取りを再開する。
    func rereadQR() {
        guard step == .barcode, !isEndingSession else { return }

        cancelAutoAdvanceCountdown()
        step = .qr
        qrValue = ""
        barcodeValue = ""
        scanLocked = false
        barcodeCandidate = nil
        focusPoint = nil

        if inputSource == .bluetooth, bluetoothScanner.isConnected {
            bluetoothScanner.setExpectedCode(.qr)
            setLocalizedMessage { AppLocalization.string("BCST-47で別の納品書兼現品票のQRコードを読み取ってください。") }
            return
        }

        bluetoothScanner.setExpectedCode(nil)
        inputSource = .camera
        camera.setActiveType(ExpectedCode.qr.metadataType)
        setLocalizedMessage { AppLocalization.string("別の納品書兼現品票のQRコードを枠の中央に合わせてください。") }
        if !isCameraRunning, !isCameraStarting {
            startCamera()
        }
    }

    func setAutoAdvanceEnabled(_ isEnabled: Bool) {
        guard isAutoAdvanceEnabled != isEnabled else { return }
        isAutoAdvanceEnabled = isEnabled
        if isEnabled {
            startAutoAdvanceCountdownIfNeeded()
        } else {
            cancelAutoAdvanceCountdown()
        }
    }

    func setAutoAdvanceDelay(_ delay: AutoAdvanceDelay) {
        guard autoAdvanceDelay != delay else { return }
        autoAdvanceDelay = delay
        startAutoAdvanceCountdownIfNeeded()
    }

    func selectInputSource(_ source: ScanInputSource) {
        guard !isEndingSession, expectedCode != nil else { return }

        switch source {
        case .camera:
            cameraWasSelectedByUser = true
            activateCamera()
        case .bluetooth:
            cameraWasSelectedByUser = false
            guard bluetoothScanner.isReadyForScanning else {
                inputSource = .camera
                let isConnected = bluetoothScanner.isConnected
                setLocalizedMessage {
                    isConnected
                        ? AppLocalization.string("Bluetoothスキャナの読み取り設定を準備しています。少し待ってからもう一度選択してください。")
                        : AppLocalization.string("Bluetoothスキャナが未接続です。設定画面で接続してください。")
                }
                return
            }
            activateBluetooth()
        }
    }

    func handleBluetoothConnectionState(_ state: BluetoothScannerConnectionState) {
        guard !isEndingSession else { return }
        if state.connectedDevice != nil {
            guard bluetoothScanner.isReadyForScanning,
                  expectedCode != nil,
                  !cameraWasSelectedByUser else { return }
            if inputSource != .bluetooth {
                activateBluetooth()
            } else {
                // 設定中メッセージを、完了した現在工程の案内へ戻す。
                updateBluetoothInstruction()
            }
            return
        }

        guard inputSource == .bluetooth else { return }
        activateCamera()
        setLocalizedMessage {
            AppLocalization.string("Bluetoothスキャナとの接続が切れたため、現在の読取ステップを維持してカメラへ切り替えました。")
        }
    }

    func handleBluetoothConfigurationState(_ state: BluetoothScannerConfigurationState) {
        guard !isEndingSession else { return }
        switch state {
        case .ready:
            handleBluetoothConnectionState(bluetoothScanner.state)
        case .failed(let reason):
            guard inputSource == .bluetooth else { return }
            cameraWasSelectedByUser = true
            activateCamera()
            setLocalizedMessage {
                AppLocalization.string("\(reason) 現在の読取ステップを維持してカメラへ切り替えました。")
            }
        case .configuring:
            if inputSource == .bluetooth {
                setLocalizedMessage {
                    AppLocalization.string("BCST-47の読み取り対象を設定しています。完了するまでお待ちください。")
                }
            }
        case .unavailable:
            break
        }
    }

    func prepareForSessionEnd(completion: CameraScanner.Completion? = nil) {
        cancelAutoAdvanceCountdown()
        guard !isEndingSession else { return }
        isEndingSession = true
        scanLocked = true
        focusPoint = nil
        camera.stop { [weak self] in
            guard let self else {
                completion?()
                return
            }
            // PreviewLayerはstopRunning完了後にだけ外す。実行中のレイヤーを
            // メインスレッドから先に切断してUIが固まる順序を避ける。
            self.isCameraRunning = false
            self.isCameraStarting = false
            self.bluetoothScanner.setExpectedCode(nil)
            self.isEndingSession = false
            completion?()
        }
    }

    func prepareForBackground() {
        cancelAutoAdvanceCountdown()
        stopCamera()
        if inputSource == .bluetooth {
            // バックグラウンド中は読取コールバックを扱わないため、強制終了に
            // 備えてスキャナーを照合開始前の全バーコード設定へ戻す。
            bluetoothScanner.setExpectedCode(nil)
        }
    }

    func resumeAfterForeground() {
        if step == .result(.match) {
            startAutoAdvanceCountdownIfNeeded()
            return
        }
        guard !isEndingSession,
              inputSource == .bluetooth,
              bluetoothScanner.isConnected,
              let expectedCode else { return }
        // 論理的なQR→Code 128の進行状態は保持し、ハードウェア設定だけを戻す。
        bluetoothScanner.setExpectedCode(expectedCode)
    }

    func focus(at devicePoint: CGPoint, displayAt viewPoint: CGPoint) {
        guard isCameraRunning else { return }
        focusPoint = viewPoint
        camera.focus(at: devicePoint)
        Task {
            try? await Task.sleep(for: .milliseconds(850))
            if focusPoint == viewPoint { focusPoint = nil }
        }
    }

    func runDemo(shouldMatch: Bool) {
        camera.stop()
        isCameraRunning = false
        isCameraStarting = false
        qrValue = Self.sampleQRPayload
        barcodeValue = shouldMatch ? Self.sampleBarcodePayload : Self.sampleMismatchBarcodePayload
        finishComparison()
    }

    private func acceptQR(_ value: String) {
        scanLocked = true
        qrValue = value
        step = .barcode
        if inputSource == .bluetooth {
            bluetoothScanner.setExpectedCode(.barcode)
        }
        // 次のステップではCode 128だけを検出対象にして読み取りを速くする
        if inputSource == .camera {
            camera.setActiveType(ExpectedCode.barcode.metadataType)
        }
        if let part = CodeMatcher.partNumber(fromQR: value) {
            let partNumber = CodeMatcher.format(partNumber: part)
            let isBluetooth = inputSource == .bluetooth
            setLocalizedMessage {
                let instruction = isBluetooth
                    ? AppLocalization.string("続けてBCST-47で現品票のCode 128バーコードを読み取ってください。")
                    : AppLocalization.string("続けて現品票のCode 128バーコードを映してください。")
                return AppLocalization.string("品目番号 \(partNumber) を読み取りました。\(instruction)")
            }
        } else {
            setLocalizedMessage {
                self.inputSource == .bluetooth
                    ? AppLocalization.string("読取完了。続けてBCST-47でCode 128バーコードを読み取ってください。")
                    : AppLocalization.string("読取完了。続けて横長のCode 128バーコードを映してください。")
            }
        }
        feedback.scanAccepted()
        Task {
            try? await Task.sleep(for: .milliseconds(250))
            scanLocked = false
        }
    }

    private func acceptBarcodeCandidate(_ value: String) {
        let now = Date()
        if let candidate = barcodeCandidate,
           candidate.value == value,
           now.timeIntervalSince(candidate.date) < 1.5 {
            barcodeCandidate = (value, candidate.count + 1, now)
        } else {
            barcodeCandidate = (value, 1, now)
        }

        guard barcodeCandidate?.count ?? 0 >= 2 else {
            setLocalizedMessage {
                AppLocalization.string("コードを確認中です。そのまま一瞬だけ保持してください。")
            }
            return
        }

        acceptBarcode(value, resultSoundDelay: 0.28)
    }

    private func acceptBarcode(_ value: String, resultSoundDelay: TimeInterval = 0) {
        scanLocked = true
        barcodeValue = value
        feedback.scanAccepted()
        if inputSource == .camera, isCameraRunning || isCameraStarting {
            camera.stop { [weak self] in
                guard let self else { return }
                // 結果画面へ遷移してCameraPreviewを破棄するのは、実セッションが
                // 停止してからに限定する。
                self.isCameraRunning = false
                self.isCameraStarting = false
                self.finishComparison(resultSoundDelay: resultSoundDelay)
            }
        } else {
            camera.stop()
            isCameraRunning = false
            isCameraStarting = false
            finishComparison(resultSoundDelay: resultSoundDelay)
        }
    }

    private func handleBluetoothScan(_ value: String) {
        guard inputSource == .bluetooth, bluetoothScanner.isReadyForScanning,
              !scanLocked, let expectedCode else { return }

        switch expectedCode {
        case .qr:
            guard KanbanQRRecord.isValidScanPayload(value) else {
                rejectBluetoothScan(
                    "読み取り順序が違います。先に納品書兼現品票のQRコードを読み取ってください。"
                )
                return
            }
            acceptQR(value)
        case .barcode:
            // BCST-47は1回の読取結果を複数回通知することがある。
            // QR確定後に同じペイロードが再通知されてもバーコードとして扱わない。
            guard value != qrValue else { return }
            guard TagBarcodeRecord.isValidScanPayload(value) else {
                rejectBluetoothScan(
                    "読み取り順序が違います。現在は現品票のCode 128バーコード待ちです。"
                )
                return
            }
            // 外部スキャナはトリガー操作そのものを確定操作として扱う。
            acceptBarcode(value)
        }
    }

    private func rejectBluetoothScan(_ reason: String.LocalizationValue) {
        setLocalizedMessage {
            "\(AppLocalization.string(reason)) " + AppLocalization.string("読み取った値は照合に使用していません。")
        }
        feedback.invalidScan()
    }

    private func updateBluetoothInstruction() {
        setLocalizedMessage {
            self.expectedCode == .qr
                ? AppLocalization.string("BCST-47で納品書兼現品票のQRコードを読み取ってください。")
                : AppLocalization.string("BCST-47で現品票のCode 128バーコードを読み取ってください。")
        }
    }

    private func activateCamera() {
        bluetoothScanner.setExpectedCode(nil)
        inputSource = .camera
        barcodeCandidate = nil
        startCamera()
    }

    private func activateBluetooth() {
        focusPoint = nil
        barcodeCandidate = nil
        inputSource = .bluetooth

        // カメラを使っていない初回接続や自動再接続では待つ対象がないため、
        // スキャナを即座に現在工程へ設定する。
        guard isCameraRunning || isCameraStarting else {
            camera.stop()
            bluetoothScanner.setExpectedCode(expectedCode)
            updateBluetoothInstruction()
            return
        }

        // 表示はすぐBluetoothへ切り替えるが、非表示になったCameraPreviewは
        // stopRunning完了までセッションへ接続したまま保持する。
        setLocalizedMessage {
            AppLocalization.string("カメラを停止してBluetoothスキャナへ切り替えています…")
        }
        camera.stop { [weak self] in
            guard let self, self.inputSource == .bluetooth else { return }
            self.isCameraRunning = false
            self.isCameraStarting = false
            self.bluetoothScanner.setExpectedCode(self.expectedCode)
            self.updateBluetoothInstruction()
        }
    }

    private func finishComparison(resultSoundDelay: TimeInterval = 0) {
        // 結果表示と次の照合の間もQR・Code 128のセッション固定モードを維持する。
        // 工程ごとのGATT設定変更をなくし、連続トリガーと設定通信の競合を防ぐ。
        let comparison = CodeMatcher.compare(qrPayload: qrValue, barcodePayload: barcodeValue)
        let result: MatchResult
        if comparison == .match,
           historyStore.activeSessionContainsMatchedQRPayload(qrValue) {
            result = .duplicate
        } else {
            result = comparison
        }
        step = .result(result)

        switch result {
        case .match:
            historyStore.recordMatch(
                code: recordedCode,
                qrPayload: qrValue,
                barcodePayload: barcodeValue
            )
            let boxNumber = historyStore.activeSessionMatchCount(code: recordedCode)
            sessionBoxNumber = boxNumber
            setLocalizedMessage {
                boxNumber >= 2
                    ? AppLocalization.string("品目番号が一致しています。この品番は本セッションで\(boxNumber)箱目です。")
                    : AppLocalization.string("品目番号が一致しています。")
            }
            feedback.success(after: resultSoundDelay)
            startAutoAdvanceCountdownIfNeeded()
        case .mismatch:
            sessionBoxNumber = 0
            setLocalizedMessage {
                AppLocalization.string("品目番号が一致しません。納品書と現品の取り違えを確認してください。")
            }
            feedback.failure()
        case .duplicate:
            sessionBoxNumber = 0
            setLocalizedMessage {
                AppLocalization.string("すでに照合済みです。このコードは照合件数に加えていません。")
            }
            feedback.failure()
        }
    }

    private func startAutoAdvanceCountdownIfNeeded() {
        cancelAutoAdvanceCountdown()
        guard isAutoAdvanceEnabled,
              !isEndingSession,
              step == .result(.match) else { return }

        autoAdvanceSecondsRemaining = autoAdvanceDelay.rawValue
        autoAdvanceTask = Task { [weak self] in
            while let self,
                  let remaining = self.autoAdvanceSecondsRemaining,
                  remaining > 0 {
                do {
                    try await Task.sleep(for: self.autoAdvanceTickDuration)
                } catch {
                    return
                }
                guard !Task.isCancelled,
                      self.isAutoAdvanceEnabled,
                      self.step == .result(.match) else { return }

                let nextRemaining = remaining - 1
                if nextRemaining == 0 {
                    self.reset(automaticallyStartScanning: true)
                    return
                }
                self.autoAdvanceSecondsRemaining = nextRemaining
            }
        }
    }

    private func cancelAutoAdvanceCountdown() {
        autoAdvanceTask?.cancel()
        autoAdvanceTask = nil
        autoAdvanceSecondsRemaining = nil
    }

    /// 履歴へ残す値。読み取れた品番を優先し、抽出できない場合はQRの生値を使う。
    private var recordedCode: String {
        if let part = barcodePartNumber ?? qrPartNumber {
            return CodeMatcher.format(partNumber: part)
        }
        return qrValue
    }
}

extension ScannerViewModel: CameraScannerDelegate {
    func cameraScannerDidStart(_ scanner: CameraScanner) {
        guard inputSource == .camera, expectedCode != nil else {
            scanner.stop()
            return
        }
        isCameraStarting = false
        isCameraRunning = true
        setLocalizedMessage {
            self.expectedCode == .qr
                ? AppLocalization.string("QRコードを枠の中央に合わせてください。")
                : AppLocalization.string("横長のCode 128バーコード全体を枠に合わせてください。")
        }
    }

    func cameraScannerDidStop(_ scanner: CameraScanner) {
        isCameraStarting = false
        isCameraRunning = false
    }

    func cameraScanner(
        _ scanner: CameraScanner,
        didRead value: String,
        type: AVMetadataObject.ObjectType
    ) {
        guard inputSource == .camera, !scanLocked, let expectedCode else { return }

        switch (expectedCode, type) {
        case (.qr, .qr):
            acceptQR(value)
        case (.barcode, .code128):
            acceptBarcodeCandidate(value)
        default:
            setLocalizedMessage {
                expectedCode == .qr
                    ? AppLocalization.string("正方形のQRコードを枠に合わせてください。")
                    : AppLocalization.string("横長のCode 128バーコードを枠に合わせてください。")
            }
        }
    }

    func cameraScanner(_ scanner: CameraScanner, didFail message: String) {
        isCameraStarting = false
        isCameraRunning = false
        setLocalizedMessage { message }
    }
}
