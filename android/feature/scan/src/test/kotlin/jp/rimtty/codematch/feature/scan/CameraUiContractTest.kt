package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraUiContractTest {
    @Test
    fun guideShapeFollowsLogicalFormat() {
        assertEquals(CameraGuideShape.QR_SQUARE, CameraGuide(ScanFormat.QR).shape)
        assertEquals(CameraGuideShape.CODE_128_WIDE, CameraGuide(ScanFormat.CODE_128).shape)
    }

    @Test
    fun roiMatchesTheOverlayGeometryForEachFormat() {
        val qr = CameraGuide(ScanFormat.QR).regionOfInterest
        // The preview is 4:3, so a physically square guide has different
        // normalized width/height fractions in the two axes.
        assertEquals(qr.width * 4f / 3f, qr.height, 0.0001f)
        assertEquals(.465f, qr.width, 0.0001f)
        assertEquals(.62f, qr.height, 0.0001f)

        val code128 = CameraGuide(ScanFormat.CODE_128).roi
        assertEquals(.80f, code128.width, 0.0001f)
        assertEquals(.24f, code128.height, 0.0001f)
        assertEquals(.90f, code128.right, 0.0001f)
    }

    @Test
    fun focusPointIsNormalizedToPreviewBounds() {
        assertEquals(CameraFocusPoint(.25f, .75f), CameraFocusPoint(.25f, .75f))
        assertThrows(IllegalArgumentException::class.java) {
            CameraFocusPoint(-.01f, .5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraFocusPoint(.5f, 1.01f)
        }
    }
}
