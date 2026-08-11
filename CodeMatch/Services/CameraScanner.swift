@preconcurrency import AVFoundation
import UIKit

@MainActor
protocol CameraScannerDelegate: AnyObject {
    func cameraScanner(_ scanner: CameraScanner, didRead value: String, type: AVMetadataObject.ObjectType)
    func cameraScanner(_ scanner: CameraScanner, didFail message: String)
}

final class CameraScanner: NSObject, @unchecked Sendable {
    let session = AVCaptureSession()
    weak var delegate: CameraScannerDelegate?

    private let sessionQueue = DispatchQueue(label: "jp.rimtty.CodeMatch.camera-session")
    private let metadataQueue = DispatchQueue(label: "jp.rimtty.CodeMatch.metadata")
    private var configured = false

    func requestAccessAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.configureAndStart()
                } else {
                    self.reportFailure("カメラが許可されていません。設定アプリでカメラを許可してください。")
                }
            }
        case .denied, .restricted:
            reportFailure("カメラが許可されていません。設定アプリでカメラを許可してください。")
        @unknown default:
            reportFailure("カメラの利用状態を確認できませんでした。")
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    func focus(at devicePoint: CGPoint) {
        sessionQueue.async {
            guard let device = AVCaptureDevice.default(for: .video) else { return }
            do {
                try device.lockForConfiguration()
                if device.isFocusPointOfInterestSupported {
                    device.focusPointOfInterest = devicePoint
                    device.focusMode = device.isFocusModeSupported(.autoFocus) ? .autoFocus : .continuousAutoFocus
                }
                if device.isExposurePointOfInterestSupported {
                    device.exposurePointOfInterest = devicePoint
                    device.exposureMode = .continuousAutoExposure
                }
                device.unlockForConfiguration()
            } catch {
                // The camera can continue using continuous autofocus.
            }
        }
    }

    private func configureAndStart() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if !self.configured, !self.configureSession() { return }
            guard !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    private func configureSession() -> Bool {
        session.beginConfiguration()
        session.sessionPreset = .high
        defer { session.commitConfiguration() }

        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            reportFailure("背面カメラを開始できませんでした。実機でカメラを確認してください。")
            return false
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            reportFailure("コード読み取り機能を開始できませんでした。")
            return false
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: metadataQueue)

        let supported = output.availableMetadataObjectTypes
        output.metadataObjectTypes = [.qr, .code128].filter(supported.contains)
        guard !output.metadataObjectTypes.isEmpty else {
            reportFailure("この端末ではQRコード・Code 128を読み取れません。")
            return false
        }

        if device.isFocusModeSupported(.continuousAutoFocus) {
            do {
                try device.lockForConfiguration()
                device.focusMode = .continuousAutoFocus
                device.unlockForConfiguration()
            } catch { }
        }

        configured = true
        return true
    }

    private func reportFailure(_ message: String) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.delegate?.cameraScanner(self, didFail: message)
        }
    }
}

extension CameraScanner: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
            .first(where: { $0.stringValue?.isEmpty == false }),
              let value = code.stringValue
        else { return }

        Task { @MainActor [weak self] in
            guard let self else { return }
            self.delegate?.cameraScanner(self, didRead: value, type: code.type)
        }
    }
}
