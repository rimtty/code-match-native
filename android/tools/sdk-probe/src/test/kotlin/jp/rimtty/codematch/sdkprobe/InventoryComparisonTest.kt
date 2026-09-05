package jp.rimtty.codematch.sdkprobe

import org.junit.Assert.*
import org.junit.Test

class InventoryComparisonTest {
    private fun item(name: String, value: String) = mapOf("area" to "arbitrary", "name" to name, "value" to value)
    @Test fun comparesAllFieldsIgnoringOnlyOrder() {
        val witness = InventoryComparison()
        val baseline = listOf(item("a", "1"), item("b", "0"))
        witness.capture("device", baseline)
        assertTrue(witness.compare("device", baseline.reversed()).startsWith("一致："))
        assertTrue(witness.compare("device", listOf(item("a", "0"), item("b", "0"))).startsWith("不一致："))
        assertTrue(witness.compare("device", baseline + item("c", "1")).startsWith("不一致："))
    }
    @Test fun rejectsWrongDeviceDuplicateKeysAndMissingBaseline() {
        val witness = InventoryComparison()
        assertTrue(witness.compare("x", listOf(item("a", "1"))).startsWith("比較不可："))
        assertTrue(witness.capture("x", listOf(item("a", "1"), item("a", "1"))).startsWith("基準取得失敗："))
        witness.capture("x", listOf(item("a", "1")))
        assertTrue(witness.compare("y", listOf(item("a", "1"))).startsWith("比較不可："))
        assertTrue(witness.capture("x", listOf(item("a", "0"))).startsWith("基準は既に"))
        witness.clear()
        assertTrue(witness.compare("x", listOf(item("a", "1"))).startsWith("比較不可："))
    }
}
