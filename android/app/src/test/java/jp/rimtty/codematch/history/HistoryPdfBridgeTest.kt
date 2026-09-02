package jp.rimtty.codematch.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import jp.rimtty.codematch.core.export.HistoryPdfExporter
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryPdfBridgeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun createDocumentUsesPdfMimeTypeAndSanitizedFilename() {
        val session = MatchSession(
            startedAt = 0L,
            name = "../morning/report:09:00?.pdf",
        )
        val fileName = HistoryPdfExporter.fileName(session, AppLanguage.ENGLISH, ZoneId.of("UTC"))
        val document = PendingHistoryPdf(
            bytes = "%PDF-test".toByteArray(),
            fileName = fileName,
        )

        assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, document.mimeType)
        assertTrue(fileName.endsWith(".pdf"))
        assertFalse(fileName.contains('/'))
        assertFalse(fileName.contains('\\'))
        assertFalse(fileName.contains(".."))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pendingDocumentRejectsPathTraversal() {
        PendingHistoryPdf(
            bytes = byteArrayOf(1),
            fileName = "../outside.pdf",
        )
    }

    @Test
    fun createDocumentContractSetsPdfTypeAndSuggestedName() {
        val document = PendingHistoryPdf(
            bytes = byteArrayOf(1),
            fileName = "MatchHistory_safe.pdf",
        )

        val intent = HistoryPdfBridge.createDocumentContract()
            .createIntent(context, document.fileName)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, intent.type)
        assertEquals(document.fileName, intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun writeDocumentCopiesExactlyTheGeneratedBytesToSelectedDestination() {
        val bytes = "%PDF-test-bytes".toByteArray()
        val document = PendingHistoryPdf(bytes, "history.pdf")
        val destination = Uri.parse("content://documents/tree/history.pdf")
        val output = ByteArrayOutputStream()
        var openedUri: Uri? = null

        val written = HistoryPdfBridge.writeDocument(
            destination = destination,
            openOutputStream = { uri ->
                openedUri = uri
                output
            },
            document = document,
        )

        assertTrue(written)
        assertEquals(destination, openedUri)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun writeDocumentReturnsFalseWhenTheSelectedDestinationCannotBeOpened() {
        val document = PendingHistoryPdf(byteArrayOf(1, 2, 3), "history.pdf")

        assertFalse(
            HistoryPdfBridge.writeDocument(
                destination = Uri.parse("content://documents/missing.pdf"),
                openOutputStream = { null },
                document = document,
            ),
        )
    }

    @Test
    fun shareChooserUsesFileProviderUriPdfTypeClipDataAndReadGrant() {
        val directory = File(context.cacheDir, HistoryPdfExporter.CACHE_DIRECTORY)
        assertTrue(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "share-contract.pdf").apply {
            writeBytes("%PDF-share".toByteArray())
        }

        try {
            val chooser = HistoryPdfBridge.createShareChooser(context, file)
            assertNotNull(chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser?.action)

            @Suppress("DEPRECATION")
            val sendIntent = chooser?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(sendIntent)
            val actualSendIntent = requireNotNull(sendIntent)
            assertEquals(Intent.ACTION_SEND, actualSendIntent.action)
            assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, actualSendIntent.type)

            @Suppress("DEPRECATION")
            val sharedUri = actualSendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(sharedUri)
            assertTrue(sharedUri.toString().startsWith("content://${context.packageName}.fileprovider/"))
            assertTrue(sharedUri.toString().contains("/history_pdf/share-contract.pdf"))
            assertTrue(
                actualSendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            )
            assertNotNull(actualSendIntent.clipData)
            assertEquals(sharedUri, actualSendIntent.clipData?.getItemAt(0)?.uri)
        } finally {
            file.delete()
        }
    }
}
