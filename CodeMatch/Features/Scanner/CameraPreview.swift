import AVFoundation
import SwiftUI

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let onTap: (CGPoint) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.onTap = onTap
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.onTap = onTap
    }
}

final class PreviewView: UIView {
    var onTap: ((CGPoint) -> Void)?

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
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        let point = gesture.location(in: self)
        onTap?(previewLayer.captureDevicePointConverted(fromLayerPoint: point))
    }
}
