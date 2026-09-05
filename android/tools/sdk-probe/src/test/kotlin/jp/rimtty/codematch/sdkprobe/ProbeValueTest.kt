package jp.rimtty.codematch.sdkprobe

import org.junit.Assert.*
import org.junit.Test

class ProbeValueTest {
    @Test fun acceptsPrintableVersion() { assertEquals("MODEL V1.2.3", probeValue("MODEL V1.2.3")) }
    @Test fun rejectsMissingAndControlCharacters() {
        listOf(null, "", " ", "V1\n", "V1\u0000", "x".repeat(129)).forEach { assertNull(probeValue(it)) }
    }
}
