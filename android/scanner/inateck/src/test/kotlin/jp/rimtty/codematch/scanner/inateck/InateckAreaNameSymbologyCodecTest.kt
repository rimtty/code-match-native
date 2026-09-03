package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.ble.SymbologySettingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InateckAreaNameSymbologyCodecTest {
    @Test
    fun everySdkReportedBinarySettingIsRetainedInOriginalOrder() {
        val payload = """
            {"data":[
              {"area":"11","name":"future_symbol_on","value":"0"},
              {"area":"28","name":"qrcode_on","value":"1"},
              {"area":"11","name":"code128_on","value":"1"}
            ]}
        """.trimIndent().encodeToByteArray()

        val snapshot = InateckAreaNameSymbologyCodec.decodeSnapshot("device", payload, 42L)

        assertEquals(
            listOf("future_symbol_on", "qrcode_on", "code128_on"),
            snapshot?.settings?.map { it.name },
        )
        assertEquals(42L, snapshot?.capturedAtMillis)
    }

    @Test
    fun nonBinaryOrDuplicateIdentityInventoryIsRejected() {
        assertNull(
            InateckAreaNameSymbologyCodec.decodeSnapshot(
                "device",
                "{\"data\":[{\"area\":\"3\",\"name\":\"volume\",\"value\":\"4\"}]}"
                    .encodeToByteArray(),
                0L,
            ),
        )
        assertNull(
            InateckAreaNameSymbologyCodec.decodeSnapshot(
                "device",
                ("{\"data\":[{\"area\":\"11\",\"name\":\"code128_on\",\"value\":\"0\"}," +
                    "{\"area\":\"11\",\"name\":\"code128_on\",\"value\":\"1\"}]}")
                    .encodeToByteArray(),
                0L,
            ),
        )
    }

    @Test
    fun commandEncoderEmitsOnlyExactSdkKeys() {
        val encoded = InateckAreaNameSymbologyCodec.encodeCommands(
            listOf(
                SymbologySettingCommand(
                    area = "28",
                    name = "qrcode_on",
                    value = 1,
                    flag = 2022,
                    extraFields = mapOf("ignored" to "true"),
                ),
            ),
        ).decodeToString()

        assertEquals("[{\"area\":\"28\",\"name\":\"qrcode_on\",\"value\":\"1\"}]", encoded)
    }
}
