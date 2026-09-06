import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

@MainActor
final class CameraPreviewTests: XCTestCase {
    func testPreviewLayerCanRebindAndDetachCaptureSessions() {
        let view = PreviewView()
        let firstSession = AVCaptureSession()
        let secondSession = AVCaptureSession()

        view.setSession(firstSession)
        XCTAssertTrue(view.previewLayer.session === firstSession)

        view.setSession(secondSession)
        XCTAssertTrue(view.previewLayer.session === secondSession)

        view.setSession(nil)
        XCTAssertNil(view.previewLayer.session)
    }

    func testPreviewDismantleDetachesSessionAndDisablesRecovery() {
        let view = PreviewView()
        let session = AVCaptureSession()
        view.isActive = true
        view.setSession(session)

        view.prepareForDismantle()

        XCTAssertFalse(view.isActive)
        XCTAssertNil(view.previewLayer.session)
    }

    func testInactivePreviewDetachesReusableCaptureSession() {
        let view = PreviewView()
        let session = AVCaptureSession()

        view.setActive(true, session: session)
        XCTAssertTrue(view.previewLayer.session === session)

        view.setActive(false, session: session)
        XCTAssertFalse(view.isActive)
        XCTAssertNil(view.previewLayer.session)

        view.setActive(true, session: session)
        XCTAssertTrue(view.previewLayer.session === session)
    }

    func testShutdownRetainsScannerUntilQueuedTeardownCompletes() async {
        let queue = DispatchQueue(label: "CameraPreviewTests.suspended-session")
        queue.suspend()
        var scanner: CameraScanner? = CameraScanner(sessionQueue: queue)
        weak var weakScanner = scanner
        let shutdownCompleted = expectation(description: "camera shutdown completed")

        scanner?.shutdown {
            shutdownCompleted.fulfill()
        }
        scanner = nil

        // 旧実装のweak selfでは、この時点でscannerが解放され停止処理が消えていた。
        XCTAssertNotNil(weakScanner)
        queue.resume()

        await fulfillment(of: [shutdownCompleted], timeout: 2)
        XCTAssertNil(weakScanner)
    }

    func testMetadataRegionIsClampedToNormalizedCoordinates() {
        XCTAssertEqual(
            CameraScanner.normalizedMetadataRect(
                CGRect(x: -0.25, y: 0.2, width: 0.75, height: 1.1)
            ),
            CGRect(x: 0, y: 0.2, width: 0.5, height: 0.8)
        )
        XCTAssertNil(CameraScanner.normalizedMetadataRect(.zero))
        XCTAssertNil(
            CameraScanner.normalizedMetadataRect(
                CGRect(x: CGFloat.nan, y: 0, width: 1, height: 1)
            )
        )
    }

    func testCameraStartIsBlockedOnlyForUnsupportedScreenCapture() {
        XCTAssertTrue(
            CameraScanner.shouldBlockCameraStart(
                isScreenCaptured: true,
                supportsMultitaskingCamera: false
            )
        )
        XCTAssertFalse(
            CameraScanner.shouldBlockCameraStart(
                isScreenCaptured: false,
                supportsMultitaskingCamera: false
            )
        )
        XCTAssertFalse(
            CameraScanner.shouldBlockCameraStart(
                isScreenCaptured: true,
                supportsMultitaskingCamera: true
            )
        )
    }

}
