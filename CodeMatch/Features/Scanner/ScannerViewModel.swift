import AVFoundation
import Foundation
import SwiftUI

@MainActor
final class ScannerViewModel: ObservableObject {
    @Published private(set) var step: ScanStep = .qr
    @Published private(set) var qrValue = ""
    @Published private(set) var barcodeValue = ""
    @Published private(set) var isCameraRunning = false
    @Published private(set) var message = "まず、納品書兼現品票のQRコードをカメラに映してください。"
    @Published private(set) var focusPoint: CGPoint?

    let camera = CameraScanner()
    private let feedback = FeedbackPlayer()
    private let historyStore: HistoryStore
    private var scanLocked = false
    private var barcodeCandidate: (value: String, count: Int, date: Date)?

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

    init(historyStore: HistoryStore) {
        self.historyStore = historyStore
        camera.delegate = self

        if ProcessInfo.processInfo.arguments.contains("-demoMatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleBarcodePayload
            step = .result(.match)
            message = "品目番号が一致しています。"
            historyStore.recordMatch(code: recordedCode)
        } else if ProcessInfo.processInfo.arguments.contains("-demoMismatch") {
            qrValue = Self.sampleQRPayload
            barcodeValue = Self.sampleMismatchBarcodePayload
            step = .result(.mismatch)
            message = "品目番号が一致しません。納品書と現品の取り違えを確認してください。"
        }
    }

    func toggleCamera() {
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
        message = "まず、納品書兼現品票のQRコードをカメラに映してください。"
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
        qrValue = Self.sampleQRPayload
        barcodeValue = shouldMatch ? Self.sampleBarcodePayload : Self.sampleMismatchBarcodePayload
        finishComparison()
    }

    private func acceptQR(_ value: String) {
        scanLocked = true
        qrValue = value
        step = .barcode
        // 次のステップではCode 128だけを検出対象にして読み取りを速くする
        camera.setActiveType(ExpectedCode.barcode.metadataType)
        if let part = CodeMatcher.partNumber(fromQR: value) {
            message = "品目番号 \(CodeMatcher.format(partNumber: part)) を読み取りました。続けて現品票のCode 128バーコードを映してください。"
        } else {
            message = "読取完了。続けて横長のCode 128バーコードを映してください。"
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

        scanLocked = true
        barcodeValue = value
        feedback.scanAccepted()
        camera.stop()
        isCameraRunning = false
        finishComparison(resultSoundDelay: 0.28)
    }

    private func finishComparison(resultSoundDelay: TimeInterval = 0) {
        let result = CodeMatcher.compare(qrPayload: qrValue, barcodePayload: barcodeValue)
        step = .result(result)
        message = result == .match
            ? "品目番号が一致しています。"
            : "品目番号が一致しません。納品書と現品の取り違えを確認してください。"
        if result == .match {
            historyStore.recordMatch(code: recordedCode)
            feedback.success(after: resultSoundDelay)
        } else {
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
        guard !scanLocked, let expectedCode else { return }

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
