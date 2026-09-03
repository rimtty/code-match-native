package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckAreaNameSettingsContractTest {
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
