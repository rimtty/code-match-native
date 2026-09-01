package jp.rimtty.codematch.scanner.ble

import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSymbologyTest {
    @Test
    fun parserAcceptsDataAndInfoAndRetainsUnknownSymbologyEntries() {
        val data = """
            {"data":[
              {"area":"11","value":"1","name":"code39_on"},
              {"area":"42","value":"1","name":"qrcode_on"},
              {"area":17,"value":1,"name":"code128_on"},
              {"area":"12","value":"0","name":"ean_13_on"},
              {"area":"15","value":"1","name":"USPS_On","flag":3019},
              {"area":"34","value":"1","name":"rss_expanded_on","flag":3038},
              {"area":"99","value":"1","name":"future_symbol","flag":2028,"vendor":"keep"},
              {"area":"31","value":"1","name":"shake_reminder"}
            ]}
        """.trimIndent()

        val snapshot = SymbologySettings.parse("scanner-1", data, capturedAtMillis = 123L)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals("scanner-1", snapshot.deviceId)
        assertEquals(7, snapshot.settings.size)
        assertEquals(123L, snapshot.capturedAtMillis)
        assertEquals(1, snapshot.find("future_symbol")?.value)
        assertEquals("\"keep\"", snapshot.find("future_symbol")?.extraFields?.get("vendor"))
        assertNull(snapshot.find("shake_reminder"))

        val info = """{"info":[{"area":"1","value":"1","name":"qrcode_on"},{"area":"2","value":"1","name":"code128_on"}]}"""
        assertEquals(2, SymbologySettings.parse("scanner-2", info)?.settings?.size)
    }

    @Test
    fun sessionModeDisablesEveryReportedTypeExceptQrAndCode128() {
        val snapshot = SymbologySettings.parse("scanner-1", settingsJson())!!
        val commands = SymbologySettings.commandsFor(snapshot, BleSymbologyMode.SESSION_CODES)!!

        assertEquals(snapshot.settings.size, commands.size)
        assertEquals("42", commands.first { it.name == "qrcode_on" }.area)
        assertEquals("17", commands.first { it.name == "code128_on" }.area)
        assertEquals(2, commands.count { it.value == 1 })
        assertEquals(1, commands.first { it.name == "qrcode_on" }.value)
        assertEquals(1, commands.first { it.name == "code128_on" }.value)
        assertEquals(0, commands.first { it.name == "code39_on" }.value)
        assertEquals(0, commands.first { it.name == "future_symbol" }.value)
    }

    @Test
    fun restoreUsesOriginalValuesAndDeviceReportedAreas() {
        val snapshot = SymbologySettings.parse("scanner-1", settingsJson())!!
        val commands = SymbologySettings.commandsFor(snapshot, BleSymbologyMode.UNRESTRICTED)!!
        val encoded = SymbologySettings.encodeCommands(commands)
        val array = JsonParser.parseString(encoded).asJsonArray

        assertEquals(snapshot.settings.size, array.size())
        assertEquals("42", array.first { it.asJsonObject.get("name").asString == "qrcode_on" }
            .asJsonObject.get("area").asString)
        assertEquals("0", array.first { it.asJsonObject.get("name").asString == "ean_13_on" }
            .asJsonObject.get("value").asString)
        assertTrue(array.any { it.asJsonObject.get("name").asString == "future_symbol" })
    }

    @Test
    fun expectedFormatChangesRemainInOnePhysicalSessionMode() {
        assertEquals(
            BleSymbologyMode.UNRESTRICTED,
            BleSymbologyMode.forExpectedFormat(null),
        )
        assertEquals(
            BleSymbologyMode.SESSION_CODES,
            BleSymbologyMode.forExpectedFormat(ScanFormat.QR),
        )
        assertEquals(
            BleSymbologyMode.SESSION_CODES,
            BleSymbologyMode.forExpectedFormat(ScanFormat.CODE_128),
        )
    }

    @Test
    fun malformedOrIncompleteSettingsCannotBeUsedForSession() {
        val missingCode128 = """{"data":[{"area":"42","value":"1","name":"qrcode_on"}]}"""
        val malformed = "not-json"

        val missing = SymbologySettings.parse("scanner", missingCode128)
        assertNotNull(missing)
        assertFalse(SymbologySettings.hasRequired(missing!!))
        assertNull(SymbologySettings.commandsFor(missing, BleSymbologyMode.SESSION_CODES))
        assertNull(SymbologySettings.parse("scanner", malformed))
    }

    @Test
    fun snapshotSerializationPreservesUnknownSettingMetadata() {
        val original = SymbologySettings.parse("scanner-1", settingsJson(), 99L)!!
        val restored = SymbologySettings.decodeSnapshot(SymbologySettings.encodeSnapshot(original))

        assertEquals(original, restored)
        assertEquals(
            "\"keep\"",
            restored?.find("future_symbol")?.extraFields?.get("vendor"),
        )
    }

    @Test
    fun observedIosCodecRoundTripsFlagsAndExtraFieldsThroughCommands() {
        val original = SymbologySettings.parse("scanner-1", settingsJson())!!
        val commands = SymbologySettings.commandsFor(original, BleSymbologyMode.UNRESTRICTED)!!

        val encoded = IosObservedSymbologyCodec.encodeCommands(commands)
        val roundTripped = SymbologySettings.parse(
            deviceId = original.deviceId,
            // The observed write is a bare array; the read response wraps
            // the same entries in the canonical `data` envelope.
            settingsJson = "{\"data\":${String(encoded, Charsets.UTF_8)}}",
        )

        assertEquals(original.settings, roundTripped?.settings)
        assertEquals(2028, roundTripped?.find("future_symbol")?.flag)
        assertEquals(
            "\"keep\"",
            roundTripped?.find("future_symbol")?.extraFields?.get("vendor"),
        )
    }

    @Test
    fun arbitraryTwentyNineItemInventoryRoundTripsWithoutDroppingUnknowns() {
        val items = buildList {
            add(ScannerSettingItem("qrcode_on", "area-qr", 0, flag = 2022))
            add(ScannerSettingItem("code128_on", "area-code128", 1, flag = 2008))
            repeat(27) { index ->
                add(
                    ScannerSettingItem(
                        name = "vendor_symbol_$index",
                        area = "device-area-${index * 17 + 3}",
                        value = index % 2,
                        flag = 2001 + (index % 27),
                        extraFields = mapOf("vendor" to "\"field-$index\""),
                    ),
                )
            }
        }
        assertEquals(29, items.size)
        val original = SymbologySnapshot("scanner-29", items, capturedAtMillis = 456L)

        val restored = SymbologySettings.decodeSnapshot(SymbologySettings.encodeSnapshot(original))

        assertEquals(original, restored)
        assertEquals(29, restored?.settings?.size)
        assertEquals("device-area-445", restored?.find("vendor_symbol_26")?.area)
        assertEquals("\"field-26\"", restored?.find("vendor_symbol_26")?.extraFields?.get("vendor"))
    }

    private fun settingsJson(): String = """
        {"data":[
          {"area":"11","value":"1","name":"code39_on"},
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":1,"name":"code128_on"},
          {"area":"12","value":"0","name":"ean_13_on"},
          {"area":"15","value":"1","name":"USPS_On","flag":3019},
          {"area":"34","value":"1","name":"rss_expanded_on","flag":3038},
          {"area":"99","value":"1","name":"future_symbol","flag":2028,"vendor":"keep"}
        ]}
    """.trimIndent()
}
