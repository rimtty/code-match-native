package jp.rimtty.codematch.scanner.camera

import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelsTest {
    @Test
    fun `guide accepts a candidate contained by the guide`() {
        val guide = CameraGuide(0.1f, 0.1f, 0.9f, 0.9f)
        val candidate = CameraRect(200f, 200f, 600f, 600f)

        assertTrue(guide.accepts(candidate, width = 1_000f, height = 1_000f))
    }

    @Test
    fun `guide rejects candidate whose center is outside even with edge overlap`() {
        val guide = CameraGuide(0.25f, 0.25f, 0.75f, 0.75f)
        val candidate = CameraRect(0f, 400f, 300f, 600f)

        assertFalse(guide.accepts(candidate, width = 1_000f, height = 1_000f))
    }

    @Test
    fun `all four corners must be inside the guide`() {
        val guide = CameraGuide(0.2f, 0.2f, 0.8f, 0.8f)
        val candidate = CameraRect(0f, 400f, 300f, 600f)

        assertFalse(guide.accepts(candidate, width = 1_000f, height = 1_000f))
    }

    @Test
    fun `rotated quadrilateral is accepted when all transformed corners are inside`() {
        val guide = CameraGuide(0.1f, 0.1f, 0.9f, 0.9f)
        val candidate = CameraQuad(
            topLeft = CameraPoint(450f, 250f),
            topRight = CameraPoint(750f, 450f),
            bottomRight = CameraPoint(550f, 750f),
            bottomLeft = CameraPoint(250f, 550f),
        )

        assertTrue(guide.accepts(candidate, width = 1_000f, height = 1_000f))
    }

    @Test
    fun `rotated or cropped quadrilateral is rejected when one corner exits guide`() {
        val guide = CameraGuide(0.1f, 0.1f, 0.9f, 0.9f)
        val candidate = CameraQuad(
            topLeft = CameraPoint(450f, 250f),
            topRight = CameraPoint(950f, 450f),
            bottomRight = CameraPoint(550f, 750f),
            bottomLeft = CameraPoint(250f, 550f),
        )

        assertFalse(guide.accepts(candidate, width = 1_000f, height = 1_000f))
    }

    @Test
    fun `default guides match the expected symbology`() {
        val qr = CameraGuide.forFormat(ScanFormat.QR)
        val code128 = CameraGuide.forFormat(ScanFormat.CODE_128)

        assertTrue(qr.rightFraction - qr.leftFraction < 1f)
        assertTrue(code128.rightFraction - code128.leftFraction >
            code128.bottomFraction - code128.topFraction)
    }
}
