package jp.rimtty.codematch.core.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.ZoneId
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryPdfExporterInstrumentationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatedPdfHasHeaderBytesAndRendersEveryPage() {
        val bytes = HistoryPdfExporter.generate(
            session = longSession(),
            language = AppLanguage.ENGLISH,
            zoneId = ZoneId.of("UTC"),
        )

        assertTrue(bytes.size > MINIMUM_PDF_BYTES)
        assertArrayEquals(PDF_HEADER, bytes.copyOf(PDF_HEADER.size))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("%%EOF"))

        val pdf = temporaryPdf(bytes, "generated")
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue("long report should span pages", renderer.pageCount > 1)
                    repeat(renderer.pageCount) { pageIndex ->
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                page.width,
                                page.height,
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                assertTrue(
                                    "page $pageIndex should contain rendered report content",
                                    bitmap.hasNonWhitePixel(),
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun cacheWriteCreatesNonEmptyPdfOnlyBelowDedicatedCacheDirectory() {
        val file = HistoryPdfExporter.writeToCache(
            context = context,
            session = MatchSession(startedAt = 0L, name = "cache boundary"),
            language = AppLanguage.ENGLISH,
            zoneId = ZoneId.of("UTC"),
        )
        try {
            val directory = File(context.cacheDir, HistoryPdfExporter.CACHE_DIRECTORY).canonicalFile
            assertTrue(file.isFile)
            assertTrue(file.length() > MINIMUM_PDF_BYTES)
            assertEquals(directory, file.canonicalFile.parentFile)
            assertTrue(file.name.endsWith(".pdf"))
            assertArrayEquals(
                PDF_HEADER,
                file.inputStream().use { it.readNBytes(PDF_HEADER.size) },
            )
        } finally {
            file.delete()
        }
    }

    private fun temporaryPdf(bytes: ByteArray, name: String): File =
        File(context.cacheDir, "$name-${System.nanoTime()}.pdf").apply {
            writeBytes(bytes)
        }

    private fun longSession(): MatchSession = MatchSession(
        startedAt = 1_700_000_000_000L,
        endedAt = 1_700_000_120_000L,
        name = "Long report",
        entries = (0 until 80).map { index ->
            MatchEntry(
                id = "entry-$index",
                code = "PART-${index % 7}",
                matchedAt = 1_700_000_001_000L + index * 1_000L,
                qrPayload = "QR-${index.toString().padStart(2, '0')}-" + "Q".repeat(120),
                barcodePayload = "CODE128-${index.toString().padStart(2, '0')}-" + "B".repeat(120),
                sequence = index.toLong(),
            )
        },
    )

    private fun Bitmap.hasNonWhitePixel(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { pixel ->
            Color.red(pixel) < 245 || Color.green(pixel) < 245 || Color.blue(pixel) < 245
        }
    }

    private companion object {
        val PDF_HEADER = "%PDF-".toByteArray(Charsets.ISO_8859_1)
        const val MINIMUM_PDF_BYTES = 100L
    }
}
