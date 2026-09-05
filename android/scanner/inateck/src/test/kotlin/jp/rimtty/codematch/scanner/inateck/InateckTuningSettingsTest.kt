package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckTuningSettingsTest {
    private fun item(name: String, value: String, area: String = "area-$name") =
        mapOf("area" to area, "name" to name, "value" to value)

    private val inventory = listOf(
        item("code128_on", "1"),
        item("qrcode_read_more_code", "0"),
        item("datamatrix_read_multi", "1"),
        item("read_inverse_color", "0"),
        item("time_auto_off", "10"),
        item("auto_close_mode", "10"),
        item("lighting_lamp_control", "2"),
    )

    @Test fun presentListsOnlyProfileItemsReportedByTheScanner() {
        assertEquals(
            listOf(
                InateckTuningSettings.Item("qrcode_read_more_code", 0),
                InateckTuningSettings.Item("datamatrix_read_multi", 1),
                InateckTuningSettings.Item("read_inverse_color", 0),
                InateckTuningSettings.Item("auto_close_mode", 10),
            ),
            InateckTuningSettings.present(inventory),
        )
        assertTrue(InateckTuningSettings.present(listOf(item("code128_on", "1"))).isEmpty())
    }

    @Test fun differencesAndCommandUseOnlyDifferingItemsWithReportedAreas() {
        val differences = InateckTuningSettings.differences(inventory)
        assertEquals(
            listOf(
                InateckTuningSettings.Item("datamatrix_read_multi", 0),
                InateckTuningSettings.Item("auto_close_mode", 20),
            ),
            differences,
        )
        val command = JsonParser.parseString(InateckTuningSettings.command(inventory, differences)).asJsonArray
        assertEquals(2, command.size())
        assertEquals(
            item("datamatrix_read_multi", "0"),
            command[0].asJsonObject.entrySet().associate { it.key to it.value.asString },
        )
        assertEquals(
            item("auto_close_mode", "20"),
            command[1].asJsonObject.entrySet().associate { it.key to it.value.asString },
        )
        assertNull(InateckTuningSettings.command(inventory, emptyList()))
    }

    @Test fun matchingInventoryNeedsNoWriteAndConfirms() {
        val matching = inventory.map { entry ->
            when (entry["name"]) {
                "datamatrix_read_multi" -> item("datamatrix_read_multi", "0")
                "auto_close_mode" -> item("auto_close_mode", "20")
                else -> entry
            }
        }
        assertTrue(InateckTuningSettings.differences(matching).isEmpty())
        assertTrue(InateckTuningSettings.confirmed(matching))
        assertFalse(InateckTuningSettings.confirmed(inventory))
        assertFalse(InateckTuningSettings.confirmed(listOf(item("code128_on", "1"))))
    }

    @Test fun ambiguousOrInvalidEntriesAreIgnored() {
        val duplicated = inventory + item("auto_close_mode", "10", area = "other")
        assertTrue(InateckTuningSettings.present(duplicated).none { it.name == "auto_close_mode" })
        val blankArea = listOf(item("auto_close_mode", "10", area = " "))
        assertTrue(InateckTuningSettings.present(blankArea).isEmpty())
        val notANumber = listOf(item("auto_close_mode", "x"))
        assertTrue(InateckTuningSettings.present(notANumber).isEmpty())
    }
}
