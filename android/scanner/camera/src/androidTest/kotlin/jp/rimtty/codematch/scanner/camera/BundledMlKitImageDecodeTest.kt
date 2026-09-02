package jp.rimtty.codematch.scanner.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real bundled ML Kit model against the canonical shared images.
 *
 * This deliberately supplies an in-memory Bitmap to ML Kit instead of a
 * camera frame. It verifies the decoder and the same QR/Code 128 allow-list
 * used by [CameraScanner], while leaving camera availability out of the test.
 */
@RunWith(AndroidJUnit4::class)
class BundledMlKitImageDecodeTest {
    @Test
    fun qrFixtureDecodesWithQrOnlyScanner() {
        val barcodes = decode("images/reference-qr.png", ScanFormat.QR)

        assertEquals(1, barcodes.size)
        assertEquals(Barcode.FORMAT_QR_CODE, barcodes.single().format)
        assertEquals(
            QR_PAYLOAD,
            barcodes.single().rawValue,
        )
    }

    @Test
    fun code128FixtureDecodesWithCode128OnlyScanner() {
        val barcodes = decode("images/reference-code128.png", ScanFormat.CODE_128)

        assertEquals(1, barcodes.size)
        assertEquals(Barcode.FORMAT_CODE_128, barcodes.single().format)
        assertEquals(CODE128_PAYLOAD, barcodes.single().rawValue)
    }

    @Test
    fun eachFormatAllowListRejectsTheOtherSharedFixture() {
        val qrWithCode128Only = decode("images/reference-qr.png", ScanFormat.CODE_128)
        val code128WithQrOnly = decode("images/reference-code128.png", ScanFormat.QR)

        assertTrue(qrWithCode128Only.isEmpty())
        assertTrue(code128WithQrOnly.isEmpty())
    }

    private fun decode(assetPath: String, expectedFormat: ScanFormat): List<Barcode> {
        val bitmap = loadBitmap(assetPath)
        // Reuse CameraScanner's production factory so this test covers the
        // exact allow-list mapping used by camera binding, not a duplicate
        // test-only configuration.
        val scanner = CameraScannerDependencies().scannerFactory(expectedFormat)
        return try {
            Tasks.await(
                scanner.process(InputImage.fromBitmap(bitmap, 0)),
                MODEL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        } finally {
            scanner.close()
            bitmap.recycle()
        }
    }

    private fun loadBitmap(assetPath: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)
                ?: error("Shared barcode fixture could not be decoded")
        }
    }

    private companion object {
        const val MODEL_TIMEOUT_SECONDS = 30L
        const val QR_PAYLOAD =
            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        const val CODE128_PAYLOAD = "BCJH-52-81GG@1N5X0C"
    }
}
