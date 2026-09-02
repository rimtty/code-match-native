package jp.rimtty.codematch.scanner.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSymbologySnapshotSerializerTest {
    private val serializer = BleSymbologySnapshotSerializer()
    private val profileIdentity = "vendor:model:firmware:codec-v1"

    @Test
    fun completeInventoryRoundTripsWithoutDroppingMetadata() {
        val original = SymbologySnapshot(
            deviceId = "scanner-1",
            capturedAtMillis = 1234L,
            settings = listOf(
                ScannerSettingItem(
                    name = "qrcode_on",
                    area = "area-qr",
                    value = 1,
                    flag = 2001,
                    extraFields = linkedMapOf(
                        "vendorText" to "opaque",
                        "vendorJson" to "{\"nested\":true}",
                    ),
                ),
                ScannerSettingItem(
                    name = "future_symbol",
                    area = "area-future",
                    value = 0,
                    flag = null,
                ),
                ScannerSettingItem(
                    name = "code128_on",
                    area = "area-code128",
                    value = 1,
                    flag = 2008,
                ),
            ),
        )

        val encoded = serializer.encode(original, profileIdentity)
        val decoded = serializer.decodeResult(
            serialized = encoded,
            expectedDeviceId = original.deviceId,
            expectedProfileIdentity = profileIdentity,
        )

        assertEquals(BleSymbologySnapshotDecodeResult.Accepted(original), decoded)
        assertEquals(original, serializer.decode(encoded, original.deviceId, profileIdentity))
        assertTrue(encoded.contains("schemaVersion"))
        assertTrue(encoded.contains("profileIdentity"))
    }

    @Test
    fun corruptAndUnknownVersionValuesAreRejected() {
        val malformed = listOf(
            "not-json",
            "[]",
            "{}",
            "{\"schemaVersion\":1,\"profileIdentity\":\"$profileIdentity\",\"deviceId\":\"scanner-1\",\"capturedAtMillis\":0,\"settings\":[]}",
            "{\"schemaVersion\":1,\"profileIdentity\":\"$profileIdentity\",\"deviceId\":\"scanner-1\",\"capturedAtMillis\":0,\"settings\":[{\"name\":\"qrcode_on\",\"area\":\"a\",\"value\":2}]}",
        )
        malformed.forEach { serialized ->
            assertTrue(serializer.decodeResult(serialized) is BleSymbologySnapshotDecodeResult.Rejected)
        }

        val snapshot = sampleSnapshot()
        val encoded = serializer.encode(snapshot, profileIdentity)
        val unknownVersion = encoded.replace(
            "\"schemaVersion\":1",
            "\"schemaVersion\":99",
        )

        assertEquals(
            BleSymbologySnapshotRejectionReason.UNSUPPORTED_VERSION,
            (serializer.decodeResult(unknownVersion) as BleSymbologySnapshotDecodeResult.Rejected)
                .reason,
        )
    }

    @Test
    fun deviceAndProfileIdentityMismatchesAreRejectedBeforeUse() {
        val encoded = serializer.encode(sampleSnapshot(), profileIdentity)

        assertEquals(
            BleSymbologySnapshotRejectionReason.DEVICE_MISMATCH,
            (serializer.decodeResult(
                encoded,
                expectedDeviceId = "different-device",
                expectedProfileIdentity = profileIdentity,
            ) as BleSymbologySnapshotDecodeResult.Rejected).reason,
        )
        assertEquals(
            BleSymbologySnapshotRejectionReason.PROFILE_MISMATCH,
            (serializer.decodeResult(
                encoded,
                expectedDeviceId = "scanner-1",
                expectedProfileIdentity = "different-profile",
            ) as BleSymbologySnapshotDecodeResult.Rejected).reason,
        )
        assertNull(serializer.decode(encoded, "different-device", profileIdentity))
    }

    @Test
    fun serializerStoresOnlySymbologyInventoryFields() {
        val encoded = serializer.encode(sampleSnapshot(), profileIdentity)

        assertTrue(encoded.contains("settings"))
        assertTrue(encoded.contains("name"))
        assertTrue(encoded.contains("area"))
        assertTrue(encoded.contains("value"))
        assertTrue(!encoded.contains("scanPayload"))
        assertTrue(!encoded.contains("rawFrame"))
    }

    private fun sampleSnapshot(): SymbologySnapshot = SymbologySnapshot(
        deviceId = "scanner-1",
        settings = listOf(
            ScannerSettingItem("qrcode_on", "qr", 1, flag = 2001),
            ScannerSettingItem("code128_on", "code128", 0, flag = 2008),
        ),
        capturedAtMillis = 42L,
    )
}
