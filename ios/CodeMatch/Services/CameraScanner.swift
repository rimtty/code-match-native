@preconcurrency import AVFoundation
import UIKit
import Combine

@MainActor
protocol CameraScannerDelegate: AnyObject {
    func cameraScannerDidStart(_ scanner: CameraScanner)
    func cameraScannerDidStop(_ scanner: CameraScanner)
    func cameraScanner(_ scanner: CameraScanner, didRead value: String, type: AVMetadataObject.ObjectType)
    func cameraScanner(_ scanner: CameraScanner, didFail message: String)
}

final class CameraScanner: NSObject, ObservableObject, @unchecked Sendable {
    typealias Completion = @MainActor @Sendable () -> Void

    private static var unsupportedScreenCaptureMessage: String {
        AppLocalization.string(
            "画面ミラーリングまたは画面収録中は、この端末でカメラを安全に開始できません。ミラーリングを終了してから、もう一度カメラを開始してください。"
        )
    }

    let session = AVCaptureSession()
    weak var delegate: CameraScannerDelegate?

    private let sessionQueue: DispatchQueue
    private let metadataQueue = DispatchQueue(label: "jp.rimtty.CodeMatch.metadata")
    private var configured = false
    private var metadataOutput: AVCaptureMetadataOutput?
    private var activeType: AVMetadataObject.ObjectType?
    private var captureDevice: AVCaptureDevice?
    private var wantsRunning = false
    private var isShutDown = false

    init(
        sessionQueue: DispatchQueue = DispatchQueue(label: "jp.rimtty.CodeMatch.camera-session")
    ) {
        self.sessionQueue = sessionQueue
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
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(screenCaptureStateDidChange(_:)),
            name: UIScreen.capturedDidChangeNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @MainActor
    func requestAccessAndStart() {
        let screenCaptureActive = Self.isScreenCaptureActive()
        let supportsMultitaskingCamera = session.isMultitaskingCameraAccessSupported
        print(
            "[CameraScanner] Capture environment screenCaptured=\(screenCaptureActive) " +
            "multitaskingSupported=\(supportsMultitaskingCamera)"
        )

        // DeviceHub、AirPlay、画面収録などで画面が複製されている間に、
        // マルチタスクカメラ非対応端末で開始するとカメラサービス全体が
        // 復帰不能になるOS不具合を確認している。標準カメラまで巻き込むため、
        // この組み合わせではハードウェアへ触れる前に開始を拒否する。
        if Self.shouldBlockCameraStart(
            isScreenCaptured: screenCaptureActive,
            supportsMultitaskingCamera: supportsMultitaskingCamera
        ) {
            reportFailure(Self.unsupportedScreenCaptureMessage)
            return
        }

        // 権限ダイアログ中に画面が閉じられた場合、後から届く許可結果で
        // セッションを勝手に再開しないよう、開始意思をキュー上で管理する。
        sessionQueue.async { [self] in
            guard !isShutDown else { return }
            wantsRunning = true
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
                    self.reportFailure(AppLocalization.string("カメラが許可されていません。設定アプリでカメラを許可してください。"))
                }
            }
        case .denied, .restricted:
            cancelPendingStart()
            reportFailure(AppLocalization.string("カメラが許可されていません。設定アプリでカメラを許可してください。"))
        @unknown default:
            cancelPendingStart()
            reportFailure(AppLocalization.string("カメラの利用状態を確認できませんでした。"))
        }
    }

    func stop(completion: Completion? = nil) {
        // `self`を強く保持する。画面破棄と同時に停止要求を出した場合でも、
        // キュー上のstopRunningが完了する前にCameraScannerを解放しない。
        sessionQueue.async { [self] in
            wantsRunning = false
            if session.isRunning {
                let startedAt = ProcessInfo.processInfo.systemUptime
                session.stopRunning()
                let elapsed = ProcessInfo.processInfo.systemUptime - startedAt
                print("[CameraScanner] Stopped session in \(String(format: "%.3f", elapsed))s")
            }
            completeOnMain(completion, operation: "Stop")
        }
    }

    /// 照合セッション終了時の最終解放。カメラ停止を待ってから入力・出力を外し、
    /// mediaserverdが次のアプリへデバイスを確実に渡せる状態にする。
    func shutdown(completion: Completion? = nil) {
        sessionQueue.async { [self] in
            wantsRunning = false

            if !isShutDown {
                isShutDown = true
                let startedAt = ProcessInfo.processInfo.systemUptime
                if session.isRunning {
                    session.stopRunning()
                }

                metadataOutput?.setMetadataObjectsDelegate(nil, queue: nil)
                session.beginConfiguration()
                removeConfiguredInputsAndOutputs()
                session.commitConfiguration()

                metadataOutput = nil
                captureDevice = nil
                configured = false

                let elapsed = ProcessInfo.processInfo.systemUptime - startedAt
                print("[CameraScanner] Shutdown completed in \(String(format: "%.3f", elapsed))s")
            }

            completeOnMain(completion, operation: "Shutdown")
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

    static func shouldBlockCameraStart(
        isScreenCaptured: Bool,
        supportsMultitaskingCamera: Bool
    ) -> Bool {
        isScreenCaptured && !supportsMultitaskingCamera
    }

    @MainActor
    private static func isScreenCaptureActive() -> Bool {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .contains { $0.traitCollection.sceneCaptureState == .active }
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
                    if device.isFocusModeSupported(.autoFocus) {
                        device.focusMode = .autoFocus
                    } else if device.isFocusModeSupported(.continuousAutoFocus) {
                        device.focusMode = .continuousAutoFocus
                    }
                }
                if device.isExposurePointOfInterestSupported {
                    device.exposurePointOfInterest = safePoint
                    if device.isExposureModeSupported(.autoExpose) {
                        device.exposureMode = .autoExpose
                    } else if device.isExposureModeSupported(.continuousAutoExposure) {
                        device.exposureMode = .continuousAutoExposure
                    }
                }
                device.unlockForConfiguration()
            } catch {
                // The camera can continue using continuous autofocus.
            }
        }
    }

    private func configureAndStart() {
        sessionQueue.async { [weak self] in
            guard let self, self.wantsRunning, !self.isShutDown else { return }
            if !self.configured, !self.configureSession() {
                self.wantsRunning = false
                return
            }
            guard self.wantsRunning else { return }
            guard !self.session.isRunning else { return }
            self.session.startRunning()
            let formatDescription: String
            if let device = self.captureDevice {
                let dimensions = CMVideoFormatDescriptionGetDimensions(
                    device.activeFormat.formatDescription
                )
                formatDescription = "format=\(dimensions.width)x\(dimensions.height)"
            } else {
                formatDescription = "format=unknown"
            }
            print(
                "[CameraScanner] Started session running=\(self.session.isRunning) " +
                "interrupted=\(self.session.isInterrupted) " +
                "inputs=\(self.session.inputs.count) outputs=\(self.session.outputs.count) " +
                formatDescription
            )
        }
    }

    private func cancelPendingStart() {
        sessionQueue.async { [weak self] in
            self?.wantsRunning = false
        }
    }

    private func completeOnMain(_ completion: Completion?, operation: String) {
        guard let completion else { return }
        let queuedAt = ProcessInfo.processInfo.systemUptime
        Task { @MainActor in
            let delay = ProcessInfo.processInfo.systemUptime - queuedAt
            print(
                "[CameraScanner] \(operation) completion reached main actor in "
                    + "\(String(format: "%.3f", delay))s"
            )
            completion()
        }
    }

    @objc private func sessionRuntimeError(_ notification: Notification) {
        let error = notification.userInfo?[AVCaptureSessionErrorKey] as? NSError
        print("[CameraScanner] Runtime error: \(error?.domain ?? "unknown") \(error?.code ?? 0) \(error?.localizedDescription ?? "")")

        sessionQueue.async { [weak self] in
            guard let self, self.wantsRunning, !self.isShutDown else { return }
            // mediaServicesWereReset後は、直前まで使用中だったセッションを再開する。
            if error?.code == AVError.mediaServicesWereReset.rawValue,
               !self.session.isRunning {
                self.session.startRunning()
            } else if error?.code != AVError.mediaServicesWereReset.rawValue {
                self.wantsRunning = false
                self.reportFailure(AppLocalization.string("カメラでエラーが発生しました。カメラを停止してから再度開始してください。"))
            }
        }
    }

    @objc private func sessionWasInterrupted(_ notification: Notification) {
        let reason = notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber
        let rawValue = reason?.intValue ?? -1
        print(
            "[CameraScanner] Session interrupted reason=\(rawValue) "
                + "(\(Self.interruptionDescription(rawValue)))"
        )
    }

    @objc private func sessionInterruptionEnded(_ notification: Notification) {
        print("[CameraScanner] Session interruption ended")
        sessionQueue.async { [weak self] in
            guard let self,
                  self.wantsRunning,
                  !self.isShutDown,
                  !self.session.isRunning else { return }
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

    @objc private func screenCaptureStateDidChange(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let screenCaptureActive = Self.isScreenCaptureActive()
            let supportsMultitaskingCamera = self.session.isMultitaskingCameraAccessSupported
            print(
                "[CameraScanner] Capture environment changed screenCaptured=" +
                "\(screenCaptureActive) multitaskingSupported=\(supportsMultitaskingCamera)"
            )
            guard Self.shouldBlockCameraStart(
                isScreenCaptured: screenCaptureActive,
                supportsMultitaskingCamera: supportsMultitaskingCamera
            ) else { return }

            // カメラ稼働後にAirPlayやDeviceHubのミラーリングが始まった場合も、
            // OSのカメラサービスが復帰不能になる前に直ちに停止する。
            self.stop { [weak self] in
                self?.reportFailure(Self.unsupportedScreenCaptureMessage)
            }
        }
    }

    private func configureSession() -> Bool {
        session.beginConfiguration()
        // QR / Code 128の認識には720pで十分。`.high`に任せると端末によって
        // 高解像度のカメラパイプラインが選ばれ、cameracapturedの負荷と
        // バッファ使用量が大きくなるため、上限を明示する。
        session.sessionPreset = .hd1280x720
        var configurationSucceeded = false
        defer {
            if !configurationSucceeded {
                metadataOutput?.setMetadataObjectsDelegate(nil, queue: nil)
                removeConfiguredInputsAndOutputs()
                metadataOutput = nil
                captureDevice = nil
            }
            session.commitConfiguration()
        }

        // iOS 18以降は専用entitlementなしで、対応端末なら画面ミラーリング等の
        // 別フォアグラウンド処理と競合せずカメラを利用できる。これを開始前に
        // 有効化し、reason 4の永続的な黒画面を回避する。
        if #available(iOS 18.0, *), session.isMultitaskingCameraAccessSupported {
            session.isMultitaskingCameraAccessEnabled = true
        }
        if #available(iOS 18.0, *) {
            print(
                "[CameraScanner] Multitasking camera supported="
                    + "\(session.isMultitaskingCameraAccessSupported) "
                    + "enabled=\(session.isMultitaskingCameraAccessEnabled)"
            )
        }

        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            reportFailure(AppLocalization.string("背面カメラを開始できませんでした。実機でカメラを確認してください。"))
            return false
        }
        session.addInput(input)
        captureDevice = device

        // Decode QR/Code 128 values directly from live video frames. This app
        // intentionally has no AVCapturePhotoOutput and never takes a photo.
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            reportFailure(AppLocalization.string("コード読み取り機能を開始できませんでした。"))
            return false
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: metadataQueue)
        metadataOutput = output

        let supported = output.availableMetadataObjectTypes
        output.metadataObjectTypes = [.qr, .code128].filter(supported.contains)
        guard !output.metadataObjectTypes.isEmpty else {
            reportFailure(AppLocalization.string("この端末ではQRコード・Code 128を読み取れません。"))
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
        configurationSucceeded = true
        return true
    }

    private func removeConfiguredInputsAndOutputs() {
        session.outputs.forEach(session.removeOutput)
        session.inputs.forEach(session.removeInput)
    }

    private static func interruptionDescription(_ rawValue: Int) -> String {
        switch rawValue {
        case 1: "audioDeviceInUseByAnotherClient"
        case 2: "videoDeviceInUseByAnotherClient"
        case 3: "videoDeviceNotAvailableInBackground"
        case 4: "videoDeviceNotAvailableWithMultipleForegroundApps"
        case 5: "videoDeviceNotAvailableDueToSystemPressure"
        default: "unknown"
        }
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
