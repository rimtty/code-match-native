@preconcurrency import AVFoundation
import UIKit

@MainActor
protocol CameraScannerDelegate: AnyObject {
    func cameraScannerDidStart(_ scanner: CameraScanner)
    func cameraScannerDidStop(_ scanner: CameraScanner)
    func cameraScanner(_ scanner: CameraScanner, didRead value: String, type: AVMetadataObject.ObjectType)
    func cameraScanner(_ scanner: CameraScanner, didFail message: String)
}

final class CameraScanner: NSObject, @unchecked Sendable {
    let session = AVCaptureSession()
    weak var delegate: CameraScannerDelegate?

    private let sessionQueue = DispatchQueue(label: "jp.rimtty.CodeMatch.camera-session")
    private let metadataQueue = DispatchQueue(label: "jp.rimtty.CodeMatch.metadata")
    private var configured = false
    private var metadataOutput: AVCaptureMetadataOutput?
    private var activeType: AVMetadataObject.ObjectType?
    private var captureDevice: AVCaptureDevice?
    private var wantsRunning = false

    override init() {
        super.init()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sessionRuntimeError(_:)),
            name: AVCaptureSession.runtimeErrorNotification,
            object: session
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sessionWasInterrupted(_:)),
            name: AVCaptureSession.wasInterruptedNotification,
            object: session
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sessionInterruptionEnded(_:)),
            name: AVCaptureSession.interruptionEndedNotification,
            object: session
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sessionDidStart(_:)),
            name: AVCaptureSession.didStartRunningNotification,
            object: session
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sessionDidStop(_:)),
            name: AVCaptureSession.didStopRunningNotification,
            object: session
        )
    }

    func requestAccessAndStart() {
        // 権限ダイアログ中に画面が閉じられた場合、後から届く許可結果で
        // セッションを勝手に再開しないよう、開始意思をキュー上で管理する。
        sessionQueue.async { [weak self] in
            self?.wantsRunning = true
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.configureAndStart()
                } else {
                    self.cancelPendingStart()
                    self.reportFailure("カメラが許可されていません。設定アプリでカメラを許可してください。")
                }
            }
        case .denied, .restricted:
            cancelPendingStart()
            reportFailure("カメラが許可されていません。設定アプリでカメラを許可してください。")
        @unknown default:
            cancelPendingStart()
            reportFailure("カメラの利用状態を確認できませんでした。")
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.wantsRunning = false
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
    }

    /// 現在のステップで読むコード種別だけを検出対象にする。
    /// 不要な解析を省き、検出速度と誤読耐性を高める。
    func setActiveType(_ type: AVMetadataObject.ObjectType?) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.activeType = type
            self.applyActiveType()
        }
    }

    /// ガイド枠に対応する領域(メタデータ座標系)だけを検出対象にする。
    func setRegionOfInterest(_ metadataRect: CGRect) {
        sessionQueue.async { [weak self] in
            guard let self, let output = self.metadataOutput else { return }
            guard let safeRect = Self.normalizedMetadataRect(metadataRect) else { return }
            output.rectOfInterest = safeRect
        }
    }

    static func normalizedMetadataRect(_ metadataRect: CGRect) -> CGRect? {
        guard metadataRect.origin.x.isFinite,
              metadataRect.origin.y.isFinite,
              metadataRect.width.isFinite,
              metadataRect.height.isFinite
        else { return nil }

        // AVCaptureMetadataOutputが受け付ける正規化座標(0...1)へ制限する。
        // レイアウト途中の範囲外座標を渡すとInvalidParameterになり得る。
        let unitRect = CGRect(x: 0, y: 0, width: 1, height: 1)
        let safeRect = metadataRect.standardized.intersection(unitRect)
        guard !safeRect.isNull, safeRect.width > 0, safeRect.height > 0 else { return nil }
        return safeRect
    }

    func focus(at devicePoint: CGPoint) {
        sessionQueue.async { [weak self] in
            guard let self, let device = self.captureDevice else { return }
            let safePoint = CGPoint(
                x: min(max(devicePoint.x, 0), 1),
                y: min(max(devicePoint.y, 0), 1)
            )
            do {
                try device.lockForConfiguration()
                if device.isFocusPointOfInterestSupported {
                    device.focusPointOfInterest = safePoint
                    device.focusMode = device.isFocusModeSupported(.autoFocus) ? .autoFocus : .continuousAutoFocus
                }
                if device.isExposurePointOfInterestSupported {
                    device.exposurePointOfInterest = safePoint
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
            guard let self, self.wantsRunning else { return }
            if !self.configured, !self.configureSession() {
                self.wantsRunning = false
                return
            }
            guard self.wantsRunning else { return }
            guard !self.session.isRunning else { return }
            self.session.startRunning()
            print(
                "[CameraScanner] Started session running=\(self.session.isRunning) " +
                "inputs=\(self.session.inputs.count) outputs=\(self.session.outputs.count)"
            )
        }
    }

    private func cancelPendingStart() {
        sessionQueue.async { [weak self] in
            self?.wantsRunning = false
        }
    }

    @objc private func sessionRuntimeError(_ notification: Notification) {
        let error = notification.userInfo?[AVCaptureSessionErrorKey] as? NSError
        print("[CameraScanner] Runtime error: \(error?.domain ?? "unknown") \(error?.code ?? 0) \(error?.localizedDescription ?? "")")

        sessionQueue.async { [weak self] in
            guard let self, self.wantsRunning else { return }
            // mediaServicesWereReset後は、直前まで使用中だったセッションを再開する。
            if error?.code == AVError.mediaServicesWereReset.rawValue,
               !self.session.isRunning {
                self.session.startRunning()
            } else if error?.code != AVError.mediaServicesWereReset.rawValue {
                self.wantsRunning = false
                self.reportFailure("カメラでエラーが発生しました。カメラを停止してから再度開始してください。")
            }
        }
    }

    @objc private func sessionWasInterrupted(_ notification: Notification) {
        let reason = notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber
        print("[CameraScanner] Session interrupted reason=\(reason?.intValue ?? -1)")
    }

    @objc private func sessionInterruptionEnded(_ notification: Notification) {
        print("[CameraScanner] Session interruption ended")
        sessionQueue.async { [weak self] in
            guard let self, self.wantsRunning, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    @objc private func sessionDidStart(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.delegate?.cameraScannerDidStart(self)
        }
    }

    @objc private func sessionDidStop(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.delegate?.cameraScannerDidStop(self)
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
        captureDevice = device

        // Decode QR/Code 128 values directly from live video frames. This app
        // intentionally has no AVCapturePhotoOutput and never takes a photo.
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            reportFailure("コード読み取り機能を開始できませんでした。")
            return false
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: metadataQueue)
        metadataOutput = output

        let supported = output.availableMetadataObjectTypes
        output.metadataObjectTypes = [.qr, .code128].filter(supported.contains)
        guard !output.metadataObjectTypes.isEmpty else {
            reportFailure("この端末ではQRコード・Code 128を読み取れません。")
            return false
        }
        applyActiveType()

        do {
            try device.lockForConfiguration()
            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }
            // ラベルは至近距離で読むため、近距離に限定して合焦を速くする。
            if device.isAutoFocusRangeRestrictionSupported {
                device.autoFocusRangeRestriction = .near
            }
            // 滑らかなAF(動画向け)を無効化し、合焦完了までの時間を短縮する。
            if device.isSmoothAutoFocusSupported {
                device.isSmoothAutoFocusEnabled = false
            }
            device.unlockForConfiguration()
        } catch { }

        configured = true
        return true
    }

    private func applyActiveType() {
        guard let output = metadataOutput else { return }
        let supported = output.availableMetadataObjectTypes
        if let activeType, supported.contains(activeType) {
            output.metadataObjectTypes = [activeType]
        } else {
            output.metadataObjectTypes = [.qr, .code128].filter(supported.contains)
        }
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
