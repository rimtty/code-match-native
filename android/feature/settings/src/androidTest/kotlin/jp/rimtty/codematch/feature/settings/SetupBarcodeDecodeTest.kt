package jp.rimtty.codematch.feature.settings

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Proves that Android's bundled decoder can read every vector shown by the
 * first-run scanner guide. The bitmap is memory-only and is never persisted or
 * logged. A physical BCST-47 scan remains a separate M4 acceptance gate.
 */
class SetupBarcodeDecodeTest {
    @Test
    fun allSetupBarcodesDecodeToTheirExactCommands() {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128)
                .build(),
        )

        try {
            BluetoothScannerSetupCode.entries.forEach { setupCode ->
                val bitmap = Code128Encoder.encode(setupCode).toBitmap()
                val decoded = Tasks.await(
                    scanner.process(InputImage.fromBitmap(bitmap, 0)),
                    DECODE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )

                assertEquals(
                    "${setupCode.name} should decode exactly once",
                    1,
                    decoded.size,
                )
                assertEquals(Barcode.FORMAT_CODE_128, decoded.single().format)
                assertEquals(setupCode.rawValue, decoded.single().rawValue)
                bitmap.recycle()
            }
        } finally {
            scanner.close()
        }
    }

    private fun Code128Barcode.toBitmap(): Bitmap {
        val width = widthModules * PIXELS_PER_MODULE
        val height = heightModules * PIXELS_PER_MODULE
        val pixels = IntArray(width * height) { Color.WHITE }
        val barTop = quietZoneModules * PIXELS_PER_MODULE
        val barBottom = barTop + barHeightModules * PIXELS_PER_MODULE

        modules.forEachIndexed { moduleIndex, isBlack ->
            if (!isBlack) return@forEachIndexed
            val left = moduleIndex * PIXELS_PER_MODULE
            val right = left + PIXELS_PER_MODULE
            for (y in barTop until barBottom) {
                val row = y * width
                for (x in left until right) {
                    pixels[row + x] = Color.BLACK
                }
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val PIXELS_PER_MODULE = 4
        const val DECODE_TIMEOUT_SECONDS = 10L
    }
}
