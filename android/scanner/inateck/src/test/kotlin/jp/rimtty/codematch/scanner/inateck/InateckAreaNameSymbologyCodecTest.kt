package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.ble.SymbologySettingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InateckAreaNameSymbologyCodecTest {
    @Test
    fun knownSdkSymbologiesAreRetainedInOriginalOrderAndGeneralSettingsAreIgnored() {
        val payload = """
            {"data":[
              {"area":"system","name":"volume","value":"4"},
              {"area":"11","name":"future_symbol_on","value":"0"},
              {"area":"28","name":"qrcode_on","value":"1"},
              {"area":"11","name":"code128_on","value":"1"},
              {"area":"12","name":"ean_13_on","value":"0"},
              {"area":"system","name":"beep","value":"1"}
            ]}
        """.trimIndent().encodeToByteArray()

        val snapshot = InateckAreaNameSymbologyCodec.decodeSnapshot("device", payload, 42L)

        assertEquals(
            listOf("qrcode_on", "code128_on", "ean_13_on"),
            snapshot?.settings?.map { it.name },
        )
        assertEquals(42L, snapshot?.capturedAtMillis)
    }

    @Test
    fun generalNonBinaryValueIsIgnoredButKnownNonBinaryAndDuplicateIdentityAreRejected() {
        val payload = """
            {"data":[
              {"area":"system","name":"volume","value":"4"},
              {"area":"28","name":"qrcode_on","value":"1"},
              {"area":"11","name":"code128_on","value":"0"}
            ]}
        """.trimIndent().encodeToByteArray()
        val snapshot = InateckAreaNameSymbologyCodec.decodeSnapshot("device", payload, 0L)
        assertEquals(listOf("qrcode_on", "code128_on"), snapshot?.settings?.map { it.name })

        assertNull(
            InateckAreaNameSymbologyCodec.decodeSnapshot(
                "device",
                ("{\"data\":[{\"area\":\"system\",\"name\":\"volume\",\"value\":\"4\"}," +
                    "{\"area\":\"3\",\"name\":\"qrcode_on\",\"value\":\"4\"}," +
                    "{\"area\":\"4\",\"name\":\"code128_on\",\"value\":\"1\"}]}")
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
        assertNull(
            InateckAreaNameSymbologyCodec.decodeSnapshot(
                "device",
                "{\"data\":[{\"area\":\"system\",\"name\":\"volume\",\"value\":\"4\"}]}"
                    .encodeToByteArray(),
                0L,
            ),
        )
    }

    @Test
    fun missingRequiredSymbologyIsLeftForBleSessionToReject() {
        val payload = """
            {"data":[
              {"area":"system","name":"volume","value":"4"},
              {"area":"28","name":"qrcode_on","value":"1"}
            ]}
        """.trimIndent().encodeToByteArray()

        val snapshot = InateckAreaNameSymbologyCodec.decodeSnapshot("device", payload, 0L)

        assertEquals(listOf("qrcode_on"), snapshot?.settings?.map { it.name })
        assertEquals(false, snapshot?.hasRequiredSessionSymbols())
    }

    @Test
    fun commandEncoderRejectsGeneralSettingsAndEmitsOnlyOfficialKeys() {
        val encoded = InateckAreaNameSymbologyCodec.encodeCommands(
            listOf(
                SymbologySettingCommand(
                    area = "28",
                    name = "qrcode_on",
                    value = 1,
                ),
            ),
        ).decodeToString()

        assertEquals("[{\"area\":\"28\",\"name\":\"qrcode_on\",\"value\":\"1\"}]", encoded)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            InateckAreaNameSymbologyCodec.encodeCommands(
                listOf(
                    SymbologySettingCommand(
                        area = "system",
                        name = "volume",
                        value = 1,
                    ),
                ),
            )
        }
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
