import AVFoundation
import Foundation
import SwiftUI

@MainActor
final class ScannerViewModel: ObservableObject {
    @Published private(set) var step: ScanStep = .qr
    @Published private(set) var qrValue = ""
    @Published private(set) var barcodeValue = ""
    @Published private(set) var isCameraRunning = false
    @Published private(set) var message = "まず、正方形のQRコードをカメラに映してください。"
    @Published private(set) var focusPoint: CGPoint?

    let camera = CameraScanner()
    private let feedback = FeedbackPlayer()
    private var scanLocked = false
    private var barcodeCandidate: (value: String, count: Int, date: Date)?

    var expectedCode: ExpectedCode? {
        switch step {
        case .qr: .qr
        case .barcode: .barcode
        case .result: nil
        }
    }

    init() {
        camera.delegate = self

        if ProcessInfo.processInfo.arguments.contains("-demoMatch") {
            let reference = "GA141KR9PA02@092D10"
            qrValue = reference
            barcodeValue = reference
            step = .result(.match)
            message = "2つのコードは一致しています。"
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
        guard expectedCode != nil else { return }
        isCameraRunning = true
        message = "カメラを準備しています…"
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
        message = "まず、正方形のQRコードをカメラに映してください。"
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
        let reference = "GA141KR9PA02@092D10"
        qrValue = reference
        barcodeValue = shouldMatch ? reference : "GA141KR9PA02@092D11"
        finishComparison()
    }

    private func acceptQR(_ value: String) {
        scanLocked = true
        qrValue = value
        step = .barcode
        message = "読取完了。続けて横長のCode 128バーコードを映してください。"
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
           now.timeIntervalSince(candidate.date) < 0.7 {
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
        finishComparison()
    }

    private func finishComparison() {
        let result = CodeMatcher.compare(qrValue, barcodeValue)
        step = .result(result)
        message = result == .match
            ? "2つのコードは一致しています。"
            : "コードが一致しません。取り違えを確認してください。"
        result == .match ? feedback.success() : feedback.failure()
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
