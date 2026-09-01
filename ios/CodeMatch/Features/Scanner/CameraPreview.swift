import AVFoundation
import SwiftUI

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let isActive: Bool
    let expectedCode: ExpectedCode?
    let onTap: (_ devicePoint: CGPoint, _ viewPoint: CGPoint) -> Void
    let onRegionOfInterest: (CGRect) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.setActive(isActive, session: session)
        view.onTap = onTap
        view.onRegionOfInterest = onRegionOfInterest
        view.expectedCode = expectedCode
        view.accessibilityLabel = AppLocalization.string("カメラ映像。タップしてピントを合わせます")
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        // Bluetoothとの切替や画面再構築後も、表示中のレイヤーを現在の
        // CameraScannerのセッションへ確実に接続する。
        uiView.setActive(isActive, session: session)
        uiView.onTap = onTap
        uiView.onRegionOfInterest = onRegionOfInterest
        uiView.expectedCode = expectedCode
        let localizedAccessibilityLabel = AppLocalization.string("カメラ映像。タップしてピントを合わせます")
        if uiView.accessibilityLabel != localizedAccessibilityLabel {
            uiView.accessibilityLabel = localizedAccessibilityLabel
        }
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: ()) {
        // AVCaptureSessionはプレビュー層を保持することがあるため、画面遷移時に
        // 古い表示先を明示的に外す。再表示した新しいレイヤーが黒くなるのを防ぐ。
        uiView.prepareForDismantle()
    }
}

final class PreviewView: UIView {
    var onTap: ((_ devicePoint: CGPoint, _ viewPoint: CGPoint) -> Void)?
    var onRegionOfInterest: ((CGRect) -> Void)?
    var isActive = false

    var expectedCode: ExpectedCode? {
        didSet {
            if expectedCode != oldValue { updateRegionOfInterest() }
        }
    }

    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    func setSession(_ session: AVCaptureSession?) {
        guard previewLayer.session !== session else { return }
        previewLayer.session = session
        updateRegionOfInterest()
    }

    /// AVCaptureSession自体は再利用する一方、停止中のプレビュー接続は外す。
    /// Bluetooth表示中や照合終了後にIOSurfaceを保持し続けないための境界。
    func setActive(_ active: Bool, session: AVCaptureSession) {
        isActive = active
        setSession(active ? session : nil)
    }

    func prepareForDismantle() {
        isActive = false
        setSession(nil)
        onTap = nil
        onRegionOfInterest = nil
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        previewLayer.videoGravity = .resizeAspectFill
        let gesture = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(gesture)
        isAccessibilityElement = true
        accessibilityIdentifier = "cameraPreview"
        accessibilityLabel = AppLocalization.string("カメラ映像。タップしてピントを合わせます")

        // セッション開始後でないと座標変換が確定しないため、開始通知でも再計算する。
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sessionDidStartRunning),
            name: .AVCaptureSessionDidStartRunning,
            object: nil
        )
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateRegionOfInterest()
    }

    @objc private func sessionDidStartRunning(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  self.isActive,
                  let startedSession = notification.object as? AVCaptureSession,
                  startedSession === self.previewLayer.session
            else { return }
            self.updateRegionOfInterest()
        }
    }

    /// 画面上のガイド枠に対応する領域をメタデータ座標系へ変換して通知する。
    /// 検出対象をガイド枠周辺に絞ることで、読み取りが速く誤読しにくくなる。
    private func updateRegionOfInterest() {
        guard bounds.width > 0, bounds.height > 0 else { return }

        let guide: CGRect
        switch expectedCode {
        case .barcode:
            // 横長ガイド(高さ56%)に少し余裕を持たせた領域
            let height = bounds.height * 0.68
            guide = CGRect(
                x: bounds.minX + 8,
                y: bounds.midY - height / 2,
                width: bounds.width - 16,
                height: height
            )
        default:
            guide = bounds.insetBy(dx: 14, dy: 14)
        }

        let metadataRect = previewLayer.metadataOutputRectConverted(fromLayerRect: guide)
        guard metadataRect.width > 0, metadataRect.height > 0 else { return }
        onRegionOfInterest?(metadataRect)
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        let point = gesture.location(in: self)
        guard bounds.width > 0, bounds.height > 0 else { return }
        let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: point)
        let viewPoint = CGPoint(
            x: min(max(point.x / bounds.width, 0), 1),
            y: min(max(point.y / bounds.height, 0), 1)
        )
        onTap?(devicePoint, viewPoint)
    }
}
