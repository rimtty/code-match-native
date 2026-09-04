package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class InateckIlluminationSettingsTest {
    private fun lamp(area: String = "device-area", value: String = "1") =
        mapOf("area" to area, "name" to "lighting_lamp_control", "value" to value)

    @Test fun commandPreservesReportedIdentityAndDoesNotWriteOtherSettings() {
        val inventory = listOf(lamp(), mapOf("area" to "other", "name" to "volume", "value" to "4"))
        for ((enabled, value) in listOf(true to "0", false to "2")) {
            val command = JsonParser.parseString(InateckIlluminationSettings.command(inventory, enabled)).asJsonArray
            assertEquals(1, command.size())
            assertEquals(lamp(value = value), command[0].asJsonObject.entrySet().associate { it.key to it.value.asString })
        }
    }

    @Test fun missingAmbiguousOrInvalidInventoryCannotProduceCommand() {
        for (inventory in listOf(emptyList(), listOf(lamp(), lamp("other")), listOf(lamp(value = "3")), listOf(lamp("")))) {
            assertNull(InateckIlluminationSettings.command(inventory, false))
        }
    }

    @Test fun confirmationRequiresSameIdentityAndRequestedValue() {
        assertTrue(InateckIlluminationSettings.confirmed(listOf(lamp(value = "2")), "device-area", false))
        assertFalse(InateckIlluminationSettings.confirmed(listOf(lamp(value = "1")), "device-area", true))
        assertFalse(InateckIlluminationSettings.confirmed(listOf(lamp(value = "2")), "other", false))
    }
}
