package jp.rimtty.codematch.history

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Android ContentResolver -> ContentProvider write boundary.
 *
 * The provider is declared only in the androidTest manifest and stores data
 * in a UUID-named cache file. It stands in for the system document provider
 * while keeping the test independent of an external picker or viewer app.
 */
@RunWith(AndroidJUnit4::class)
class HistoryPdfDocumentProviderInstrumentationTest {
    @Test
    fun writeDocumentUsesRealContentResolverAndProviderFileDescriptor() {
        val resolver = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .contentResolver
        val bytes = "%PDF-document-provider-${UUID.randomUUID()}".toByteArray()
        val providerAuthority = InstrumentationRegistry.getInstrumentation()
            .context.packageName + ".history.documents"
        val destination = Uri.parse("content://$providerAuthority/history.pdf")
        val document = PendingHistoryPdf(bytes = bytes, fileName = "history.pdf")

        val result = HistoryPdfBridge.writeDocument(
            contentResolver = resolver,
            destination = destination,
            document = document,
        )

        assertTrue("result=$result", result is HistoryPdfResult.Success)
        assertEquals(HistoryPdfBridge.PDF_MIME_TYPE, resolver.getType(destination))
        try {
            val persistedBytes = requireNotNull(resolver.openInputStream(destination)).use { it.readBytes() }
            assertArrayEquals(bytes, persistedBytes)
        } finally {
            resolver.delete(destination, null, null)
        }
    }
}
