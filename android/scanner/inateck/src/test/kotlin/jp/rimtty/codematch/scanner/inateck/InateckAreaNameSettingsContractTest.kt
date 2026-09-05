package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckAreaNameSettingsContractTest {
    @Test
    fun freshInventoryMustHaveExactlyTheRequestedSymbologyIdentities() {
        val requested = setOf(
            InateckAreaNameSettingsContract.SettingTriple("device-area", "qrcode_on", "1"),
            InateckAreaNameSettingsContract.SettingTriple("device-area", "code128_on", "0"),
        )
        val inventory = requested.map { mapOf("area" to it.area, "name" to it.name, "value" to it.value) }
        assertTrue(InateckAreaNameSettingsContract.containsRequestedSymbologies(inventory.reversed(), requested))
        assertTrue(!InateckAreaNameSettingsContract.containsRequestedSymbologies(
            inventory + mapOf("area" to "device-area", "name" to "ean_13_on", "value" to "1"), requested,
        ))
        assertTrue(!InateckAreaNameSettingsContract.containsRequestedSymbologies(inventory.take(1), requested))
    }

    @Test
    fun exactAreaNameValueCommandRoundTripsToInventory() {
        val command = """
            [
              {"area":"barcode","name":"qrcode_on","value":"1"},
              {"area":"barcode","name":"code128_on","value":"0"}
            ]
        """.trimIndent()
        val parsed = InateckAreaNameSettingsContract.parseCommand(command)
        val inventory = InateckAreaNameSettingsContract.normalizeInventory(
            listOf(
                mapOf("area" to "barcode", "name" to "code128_on", "value" to "0"),
                mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "1"),
            ),
        )

        assertEquals(parsed, inventory)
        assertEquals(2, parsed?.size)
    }

    @Test
    fun malformedExtraAndDuplicateItemsAreRejected() {
        assertNull(InateckAreaNameSettingsContract.parseCommand("not-json"))
        assertNull(
            InateckAreaNameSettingsContract.parseCommand(
                "[{\"area\":\"a\",\"name\":\"n\",\"value\":\"2\"}]",
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.parseCommand(
                "[{\"area\":\"a\",\"name\":\"n\",\"value\":\"1\",\"flag\":1}]",
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.parseCommand(
                "[{\"area\":\"a\",\"name\":\"n\",\"value\":\"1\"}," +
                    "{\"area\":\"a\",\"name\":\"n\",\"value\":\"1\"}]",
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.parseCommand(
                "[{\"area\":\"a\",\"name\":\"n\",\"value\":\"0\"}," +
                    "{\"area\":\"a\",\"name\":\"n\",\"value\":\"1\"}]",
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.parseCommand(
                "[{\"area\":\"system\",\"name\":\"volume\",\"value\":\"1\"}]",
            ),
        )
    }

    @Test
    fun inventoryExtractsOnlySymbologiesAndAllowsGeneralValues() {
        val inventory = listOf(
            mapOf("area" to "system", "name" to "volume", "value" to "4"),
            mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "1"),
            mapOf("area" to "barcode", "name" to "future_symbol_on", "value" to "not-binary"),
            mapOf("area" to "barcode", "name" to "code128_on", "value" to "0"),
        )

        assertEquals(
            listOf(
                InateckAreaNameSettingsContract.SettingTriple("barcode", "qrcode_on", "1"),
                InateckAreaNameSettingsContract.SettingTriple("barcode", "code128_on", "0"),
            ),
            InateckAreaNameSettingsContract.extractSymbologies(inventory),
        )
        assertEquals(
            setOf(
                InateckAreaNameSettingsContract.SettingTriple("barcode", "qrcode_on", "1"),
                InateckAreaNameSettingsContract.SettingTriple("barcode", "code128_on", "0"),
            ),
            InateckAreaNameSettingsContract.normalizeInventory(inventory),
        )
        assertTrue(
            InateckAreaNameSettingsContract.containsRequestedSymbologies(
                settings = inventory +
                    mapOf("area" to "system", "name" to "beep", "value" to "1"),
                requested = setOf(
                    InateckAreaNameSettingsContract.SettingTriple("barcode", "qrcode_on", "1"),
                    InateckAreaNameSettingsContract.SettingTriple("barcode", "code128_on", "0"),
                ),
            ),
        )
        assertTrue(
            !InateckAreaNameSettingsContract.containsRequestedSymbologies(
                settings = inventory,
                requested = setOf(
                    InateckAreaNameSettingsContract.SettingTriple("barcode", "qrcode_on", "0"),
                ),
            ),
        )
        assertTrue(
            !InateckAreaNameSettingsContract.containsRequestedSymbologies(
                settings = inventory +
                    mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "0"),
                requested = setOf(
                    InateckAreaNameSettingsContract.SettingTriple("barcode", "qrcode_on", "1"),
                ),
            ),
        )
    }

    @Test
    fun malformedOrDuplicateSymbologyFailsClosedWhileUnknownSettingsAreIgnored() {
        assertNull(
            InateckAreaNameSettingsContract.normalizeInventory(
                listOf(
                    mapOf("area" to "system", "name" to "volume", "value" to "4"),
                    mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "2"),
                    mapOf("area" to "barcode", "name" to "code128_on", "value" to "1"),
                ),
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.normalizeInventory(
                listOf(
                    mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "1"),
                    mapOf("area" to "barcode", "name" to "QRCode_On", "value" to "0"),
                    mapOf("area" to "barcode", "name" to "code128_on", "value" to "1"),
                ),
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.normalizeInventory(
                listOf(
                    mapOf("area" to "system", "name" to "volume", "value" to "4"),
                    mapOf("area" to "barcode", "name" to "qrcode_on"),
                    mapOf("area" to "barcode", "name" to "code128_on", "value" to "1"),
                ),
            ),
        )
        assertEquals(
            emptySet<InateckAreaNameSettingsContract.SettingTriple>(),
            InateckAreaNameSettingsContract.normalizeInventory(
                listOf(
                    mapOf("area" to "system", "name" to "volume", "value" to "4"),
                    mapOf("area" to "barcode", "name" to "future_symbol_on", "value" to "true"),
                ),
            ),
        )
    }

    @Test
    fun inventoryRejectsMissingOrUnknownFields() {
        assertNull(
            InateckAreaNameSettingsContract.normalizeInventory(
                listOf(mapOf("name" to "qrcode_on", "value" to "1")),
            ),
        )
        assertNull(
            InateckAreaNameSettingsContract.normalizeInventory(
                listOf(
                    mapOf(
                        "area" to "barcode",
                        "name" to "qrcode_on",
                        "value" to "1",
                        "raw" to "forbidden",
                    ),
                ),
            ),
        )
        assertTrue(
            InateckAreaNameSettingsContract.normalizeInventory(emptyList()).isNullOrEmpty(),
        )
    }
}
