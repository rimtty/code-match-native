import AVFoundation
import SwiftUI

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let expectedCode: ExpectedCode?
    let onTap: (CGPoint) -> Void
    let onRegionOfInterest: (CGRect) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.onTap = onTap
        view.onRegionOfInterest = onRegionOfInterest
        view.expectedCode = expectedCode
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.onTap = onTap
        uiView.onRegionOfInterest = onRegionOfInterest
        uiView.expectedCode = expectedCode
    }
}

final class PreviewView: UIView {
    var onTap: ((CGPoint) -> Void)?
    var onRegionOfInterest: ((CGRect) -> Void)?

    var expectedCode: ExpectedCode? {
        didSet {
            if expectedCode != oldValue { updateRegionOfInterest() }
        }
    }

    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        previewLayer.videoGravity = .resizeAspectFill
        let gesture = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(gesture)
        isAccessibilityElement = true
        accessibilityLabel = "カメラ映像。タップしてピントを合わせます"

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

    override func layoutSubviews() {
        super.layoutSubviews()
        updateRegionOfInterest()
    }

    @objc private func sessionDidStartRunning() {
        DispatchQueue.main.async { [weak self] in
            self?.updateRegionOfInterest()
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
        onTap?(previewLayer.captureDevicePointConverted(fromLayerPoint: point))
    }
}
