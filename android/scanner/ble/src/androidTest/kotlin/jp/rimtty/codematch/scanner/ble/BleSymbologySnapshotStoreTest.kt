package jp.rimtty.codematch.scanner.ble

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class BleSymbologySnapshotStoreTest {
    private val profileIdentity = "vendor:model:firmware:codec-v1"

    @Test
    fun dataStoreRoundTripsAndClearsOnlyTheMatchingDevice() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = BleSymbologySnapshotStore(dataStore, profileIdentity)
        val snapshot = sampleSnapshot()

        store.save(snapshot)

        assertEquals(snapshot, store.load(snapshot.deviceId))
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.DEVICE_MISMATCH,
            ),
            store.read("other-device"),
        )
        assertEquals(
            SymbologySnapshotClearResult.Rejected(
                BleSymbologySnapshotRejectionReason.DEVICE_MISMATCH,
            ),
            store.clear("other-device"),
        )
        assertEquals(snapshot, store.loadLatest())

        assertEquals(SymbologySnapshotClearResult.Cleared, store.clear(snapshot.deviceId))
        assertEquals(SymbologySnapshotReadResult.Missing, store.readLatest())
        assertEquals(SymbologySnapshotClearResult.Missing, store.clear(snapshot.deviceId))
        // Preferences DataStore keeps its backing file even when the single
        // snapshot key has been removed; the read above verifies its content.
        file.delete()
    }

    @Test
    fun corruptAndUnknownVersionValuesAreRejectedAndNeverReturned() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = BleSymbologySnapshotStore(dataStore, profileIdentity)
        val key = stringPreferencesKey("completeSnapshot")

        runBlocking {
            dataStore.edit { preferences -> preferences[key] = "not-json" }
        }
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.CORRUPT,
            ),
            store.readLatest(),
        )

        val encoded = BleSymbologySnapshotSerializer().encode(sampleSnapshot(), profileIdentity)
        runBlocking {
            dataStore.edit { preferences ->
                preferences[key] = encoded.replace(
                    "\"schemaVersion\":1",
                    "\"schemaVersion\":2",
                )
            }
        }
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.UNSUPPORTED_VERSION,
            ),
            store.readLatest(),
        )

        file.delete()
    }

    @Test
    fun profileMismatchIsRejectedWithoutClearingTheStoredValue() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val firstStore = BleSymbologySnapshotStore(dataStore, profileIdentity)
        firstStore.save(sampleSnapshot())

        val otherProfileStore = BleSymbologySnapshotStore(dataStore, "other-profile")
        assertEquals(
            SymbologySnapshotReadResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            ),
            otherProfileStore.readLatest(),
        )
        assertEquals(
            SymbologySnapshotClearResult.Rejected(
                BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            ),
            otherProfileStore.clear("scanner-1"),
        )
        assertEquals(sampleSnapshot(), firstStore.loadLatest())

        file.delete()
    }

    @Test
    fun storeDoesNotDependOnScannerTransportOrPersistScanData() {
        val file = temporaryFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = BleSymbologySnapshotStore(dataStore, profileIdentity)

        store.save(sampleSnapshot())
        val serialized = runBlocking {
            dataStore.data.first()[stringPreferencesKey("completeSnapshot")]
        }
        // The store has one dedicated preference and its serializer schema;
        // scanner callbacks are not part of either boundary.
        assertTrue(serialized != null)
        assertFalse(serialized.orEmpty().contains("scanPayload"))
        assertFalse(serialized.orEmpty().contains("rawFrame"))
        assertTrue(BLE_SYMBOLOGY_DATASTORE_FILE_NAME.endsWith(".preferences_pb"))

        file.delete()
    }

    private fun temporaryFile(): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(context.cacheDir, "ble-symbology-${UUID.randomUUID()}.preferences_pb")
    }

    private fun sampleSnapshot(): SymbologySnapshot = SymbologySnapshot(
        deviceId = ScannerDevice("scanner-1", "BCST-47").id,
        settings = listOf(
            ScannerSettingItem(
                name = "qrcode_on",
                area = "qr-area",
                value = 1,
                flag = 2001,
                extraFields = mapOf("vendor" to "opaque"),
            ),
            ScannerSettingItem("code128_on", "code128-area", 0, flag = 2008),
            ScannerSettingItem("future_symbol", "future-area", 1),
        ),
        capturedAtMillis = 99L,
    )
}
