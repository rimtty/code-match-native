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
    @Published private(set) var message = "まず、納品書兼現品票のQRコードをカメラに映してください。"
    @Published private(set) var focusPoint: CGPoint?
    /// 一致した品番がこのセッションで何箱目の照合かを示す通し番号。結果表示中以外は0。
    @Published private(set) var sessionBoxNumber = 0

    let camera = CameraScanner()
    private let feedback = FeedbackPlayer()
    private let historyStore: HistoryStore
    private let bluetoothScanner: BluetoothScannerService
    private var scanLocked = false
    private var barcodeCandidate: (value: String, count: Int, date: Date)?
    /// 接続済みスキャナを初期入力にする一方、利用者がカメラを選んだ後は
    /// 同じ照合セッション内で自動的にBluetoothへ戻さないためのフラグ。
    private var cameraWasSelectedByUser = false

    var expectedCode: ExpectedCode? {
        switch step {
        case .qr: .qr
        case .barcode: .barcode
        case .result: nil
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

    init(historyStore: HistoryStore, bluetoothScanner: BluetoothScannerService) {
        self.historyStore = historyStore
        self.bluetoothScanner = bluetoothScanner
        camera.delegate = self
        bluetoothScanner.onCode = { [weak self] value in
            self?.handleBluetoothScan(value)
        }

        if ProcessInfo.processInfo.arguments.contains("-demoMatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleBarcodePayload
            step = .result(.match)
            message = "品目番号が一致しています。"
            historyStore.recordMatch(code: recordedCode, qrPayload: qrValue, barcodePayload: barcodeValue)
            sessionBoxNumber = historyStore.activeSessionMatchCount(code: recordedCode)
        } else if ProcessInfo.processInfo.arguments.contains("-demoMismatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleMismatchBarcodePayload
            step = .result(.mismatch)
            message = "品目番号が一致しません。納品書と現品の取り違えを確認してください。"
        }
    }

    func toggleCamera() {
        guard inputSource == .camera else { return }
        if isCameraRunning {
            camera.stop()
            isCameraRunning = false
            message = expectedCode == .qr
                ? "カメラを停止しました。「QRコードを読み取る」で再開できます。"
                : "カメラを停止しました。「バーコードを読み取る」で再開できます。"
        } else {
            startCamera()
        }
    }

    func startCamera() {
        guard let expectedCode else { return }
        inputSource = .camera
        isCameraRunning = true
        message = "カメラを準備しています…"
        camera.setActiveType(expectedCode.metadataType)
        camera.requestAccessAndStart()
        message = expectedCode == .qr
            ? "QRコードを枠の中央に合わせてください。"
            : "横長のCode 128バーコード全体を枠に合わせてください。"
    }

    func reset() {
        camera.stop()
        step = .qr
        qrValue = ""
        barcodeValue = ""
        isCameraRunning = false
        scanLocked = false
        barcodeCandidate = nil
        focusPoint = nil
        sessionBoxNumber = 0
        if inputSource == .bluetooth, bluetoothScanner.isConnected {
            message = "BCST-47で納品書兼現品票のQRコードを読み取ってください。"
        } else {
            inputSource = .camera
            message = "まず、納品書兼現品票のQRコードをカメラに映してください。"
        }
    }

    func selectInputSource(_ source: ScanInputSource) {
        guard expectedCode != nil else { return }

        switch source {
        case .camera:
            cameraWasSelectedByUser = true
            activateCamera()
        case .bluetooth:
            cameraWasSelectedByUser = false
            guard bluetoothScanner.isConnected else {
                inputSource = .camera
                message = "Bluetoothスキャナが未接続です。設定画面で接続してください。"
                return
            }
            activateBluetooth()
        }
    }

    func handleBluetoothConnectionState(_ state: BluetoothScannerConnectionState) {
        if state.connectedDevice != nil {
            guard expectedCode != nil, !cameraWasSelectedByUser else { return }
            activateBluetooth()
            return
        }

        guard inputSource == .bluetooth else { return }
        activateCamera()
        message = "Bluetoothスキャナとの接続が切れたため、現在の読取ステップを維持してカメラへ切り替えました。"
    }

    func focus(at point: CGPoint) {
        guard isCameraRunning else { return }
        focusPoint = point
        camera.focus(at: point)
        Task {
            try? await Task.sleep(for: .milliseconds(850))
            if focusPoint == point { focusPoint = nil }
        }
    }

    func runDemo(shouldMatch: Bool) {
        camera.stop()
        isCameraRunning = false
        qrValue = Self.sampleQRPayload
        barcodeValue = shouldMatch ? Self.sampleBarcodePayload : Self.sampleMismatchBarcodePayload
        finishComparison()
    }

    private func acceptQR(_ value: String) {
        scanLocked = true
        qrValue = value
        step = .barcode
        // 次のステップではCode 128だけを検出対象にして読み取りを速くする
        if inputSource == .camera {
            camera.setActiveType(ExpectedCode.barcode.metadataType)
        }
        if let part = CodeMatcher.partNumber(fromQR: value) {
            let instruction = inputSource == .bluetooth
                ? "続けてBCST-47で現品票のCode 128バーコードを読み取ってください。"
                : "続けて現品票のCode 128バーコードを映してください。"
            message = "品目番号 \(CodeMatcher.format(partNumber: part)) を読み取りました。\(instruction)"
        } else {
            message = inputSource == .bluetooth
                ? "読取完了。続けてBCST-47でCode 128バーコードを読み取ってください。"
                : "読取完了。続けて横長のCode 128バーコードを映してください。"
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
            message = "コードを確認中です。そのまま一瞬だけ保持してください。"
            return
        }

        acceptBarcode(value, resultSoundDelay: 0.28)
    }

    private func acceptBarcode(_ value: String, resultSoundDelay: TimeInterval = 0) {
        scanLocked = true
        barcodeValue = value
        feedback.scanAccepted()
        camera.stop()
        isCameraRunning = false
        finishComparison(resultSoundDelay: resultSoundDelay)
    }

    private func handleBluetoothScan(_ value: String) {
        guard inputSource == .bluetooth, bluetoothScanner.isConnected,
              !scanLocked, let expectedCode else { return }

        switch expectedCode {
        case .qr:
            acceptQR(value)
        case .barcode:
            // BCST-47は1回の読取結果を複数回通知することがある。
            // QR確定後に同じペイロードが再通知されてもバーコードとして扱わない。
            guard value != qrValue else { return }
            // 外部スキャナはトリガー操作そのものを確定操作として扱う。
            acceptBarcode(value)
        }
    }

    private func updateBluetoothInstruction() {
        message = expectedCode == .qr
            ? "BCST-47で納品書兼現品票のQRコードを読み取ってください。"
            : "BCST-47で現品票のCode 128バーコードを読み取ってください。"
    }

    private func activateCamera() {
        inputSource = .camera
        barcodeCandidate = nil
        startCamera()
    }

    private func activateBluetooth() {
        camera.stop()
        isCameraRunning = false
        focusPoint = nil
        barcodeCandidate = nil
        inputSource = .bluetooth
        updateBluetoothInstruction()
    }

    private func finishComparison(resultSoundDelay: TimeInterval = 0) {
        let result = CodeMatcher.compare(qrPayload: qrValue, barcodePayload: barcodeValue)
        step = .result(result)

        switch result {
        case .match:
            // 同一品番のラベルが複数箱に貼られる運用のため、重複でもそのまま記録する
            historyStore.recordMatch(
                code: recordedCode,
                qrPayload: qrValue,
                barcodePayload: barcodeValue
            )
            let boxNumber = historyStore.activeSessionMatchCount(code: recordedCode)
            sessionBoxNumber = boxNumber
            message = boxNumber >= 2
                ? "品目番号が一致しています。この品番は本セッションで\(boxNumber)箱目です。"
                : "品目番号が一致しています。"
            feedback.success(after: resultSoundDelay)
        case .mismatch:
            sessionBoxNumber = 0
            message = "品目番号が一致しません。納品書と現品の取り違えを確認してください。"
            feedback.failure()
        }
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
            message = expectedCode == .qr
                ? "正方形のQRコードを枠に合わせてください。"
                : "横長のCode 128バーコードを枠に合わせてください。"
        }
    }

    func cameraScanner(_ scanner: CameraScanner, didFail message: String) {
        isCameraRunning = false
        self.message = message
    }
}
