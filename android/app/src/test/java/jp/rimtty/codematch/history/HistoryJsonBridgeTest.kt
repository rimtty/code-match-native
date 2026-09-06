package jp.rimtty.codematch.history

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import jp.rimtty.codematch.core.export.HistoryJsonExporter
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors the PDF share contract for the JSON history export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryJsonBridgeTest {
    private lateinit var context: Context

    private val exportedAt = Instant.parse("2026-09-07T01:23:45Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearFileProviderPathCache()
    }

    /**
     * FileProvider caches one path strategy per authority in a static map, and
     * Robolectric gives every test its own data directory. A strategy left
     * behind by an earlier test therefore points at a directory this test's
     * cache files can never be under, which would make the share-contract
     * assertion depend on test order rather than on the provider configuration.
     */
    private fun clearFileProviderPathCache() {
        val field = try {
            FileProvider::class.java.getDeclaredField("sCache")
        } catch (_: NoSuchFieldException) {
            // No cache in this version means there is nothing to go stale.
            return
        }
        field.isAccessible = true
        (field.get(null) as? MutableMap<*, *>)?.clear()
    }

    @Test
    fun shareCacheWritesTheWholeHistoryBelowTheExportCacheDirectory() {
        val session = MatchSession(
            id = "session-1",
            startedAt = Instant.parse("2026-09-07T00:00:00Z").toEpochMilli(),
            entries = listOf(
                MatchEntry(
                    id = "entry-1",
                    code = "D10E-50-N10B",
                    matchedAt = Instant.parse("2026-09-07T00:05:00Z").toEpochMilli(),
                    qrPayload = MOLTEN_TRAILING_SPACE_QR,
                    barcodePayload = "D10E-50-N10B@0UBL00",
                ),
            ),
        )

        val result = HistoryJsonBridge.writeShareCache(
            context = context,
            sessions = listOf(session),
            exportedAt = exportedAt,
            zoneId = ZoneId.of("UTC"),
        )

        assertTrue("result=$result", result is HistoryJsonResult.Success)
        val file = (result as HistoryJsonResult.Success).value
        try {
            assertEquals(
                File(context.cacheDir, HistoryJsonExporter.CACHE_DIRECTORY).canonicalFile,
                file.parentFile?.canonicalFile,
            )
            assertEquals("codematch-history-20260907-0123.json", file.name)
            val json = file.readText()
            // The trailing spaces of the Molten record must survive the file.
            assertTrue(json.contains("\"$MOLTEN_TRAILING_SPACE_QR\""))
            assertTrue(json.contains("\"platform\": \"android\""))
            assertTrue(json.contains("\"appVersion\": \"${HistoryJsonBridge.appVersion(context)}\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun appVersionCombinesNameAndCode() {
        val version = HistoryJsonBridge.appVersion(context)

        assertTrue("version=$version", Regex(".+ \\(\\d+\\)").matches(version))
    }

    @Test
    fun cacheFailureIsTypedWithoutExposingExceptionDetails() {
        val result = HistoryJsonBridge.writeShareCache(
            context = context,
            sessions = emptyList(),
            exportedAt = exportedAt,
            zoneId = ZoneId.of("UTC"),
            writeToCache = { _, _, _, _ -> error("cache path") },
        )

        assertEquals(
            HistoryJsonFailure.CACHE_WRITE_FAILED,
            (result as HistoryJsonResult.Failure).reason,
        )
    }

    @Test
    fun fileProviderAndShareLaunchFailuresAreTyped() {
        val outsideFile = File(context.filesDir, "not-in-provider-roots.json").apply {
            writeText("{}")
        }
        try {
            val contextWithoutProvider = object : ContextWrapper(context) {
                override fun getPackageName(): String = "jp.rimtty.codematch.no_provider_json"
            }
            val providerResult = HistoryJsonBridge.createShareChooser(contextWithoutProvider, outsideFile)
            assertEquals(
                HistoryJsonFailure.FILE_PROVIDER_FAILED,
                (providerResult as HistoryJsonResult.Failure).reason,
            )
        } finally {
            outsideFile.delete()
        }

        val failingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        val launchResult = HistoryJsonBridge.launchShare(
            context = failingContext,
            chooser = Intent(Intent.ACTION_CHOOSER),
        )
        assertEquals(
            HistoryJsonFailure.SHARE_LAUNCH_FAILED,
            (launchResult as HistoryJsonResult.Failure).reason,
        )
    }

    @Test
    fun shareChooserUsesFileProviderUriJsonTypeClipDataAndReadGrant() {
        val directory = File(context.cacheDir, HistoryJsonExporter.CACHE_DIRECTORY)
        assertTrue(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "share-contract.json").apply {
            writeText("{}")
        }

        try {
            val chooserResult = HistoryJsonBridge.createShareChooser(context, file)
            assertTrue("result=$chooserResult", chooserResult is HistoryJsonResult.Success)
            val chooser = (chooserResult as HistoryJsonResult.Success).value
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)

            @Suppress("DEPRECATION")
            val sendIntent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            val actualSendIntent = requireNotNull(sendIntent)
            assertEquals(Intent.ACTION_SEND, actualSendIntent.action)
            assertEquals("application/json", actualSendIntent.type)
            assertEquals(HistoryJsonBridge.JSON_MIME_TYPE, actualSendIntent.type)

            @Suppress("DEPRECATION")
            val sharedUri = actualSendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(sharedUri)
            val uriText = requireNotNull(sharedUri).toString()
            assertTrue(uriText.startsWith("content://${context.packageName}.fileprovider/"))
            assertTrue(uriText.contains("/history_export/share-contract.json"))
            assertTrue(
                actualSendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            )
            assertEquals(
                sharedUri,
                actualSendIntent.clipData?.getItemAt(0)?.uri,
            )
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val MOLTEN_TRAILING_SPACE_QR =
            "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    }
}
