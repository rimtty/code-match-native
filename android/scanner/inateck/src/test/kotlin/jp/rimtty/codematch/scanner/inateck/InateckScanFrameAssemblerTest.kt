package jp.rimtty.codematch.scanner.inateck

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckScanFrameAssemblerTest {
    @Test
    fun fragmentedFrameCompletesAtTerminator() {
        val assembler = InateckScanFrameAssembler()

        assertTrue(assembler.append("long-qr-".bytes()).isEmpty())
        val completed = assembler.append("payload\r".bytes())

        assertEquals(1, completed.size)
        assertArrayEquals("long-qr-payload".bytes(), completed.single())
        assertFalse(assembler.hasPendingBytes)
    }

    @Test
    fun multipleFramesAndRepeatedTerminatorsStaySeparate() {
        val assembler = InateckScanFrameAssembler()

        val completed = assembler.append("first\r\nsecond\u0000".bytes())

        assertEquals(listOf("first", "second"), completed.map { it.text() })
    }

    @Test
    fun idleFlushSupportsUnterminatedScannerPayload() {
        val assembler = InateckScanFrameAssembler()

        assembler.append("unterminated".bytes())

        assertArrayEquals("unterminated".bytes(), assembler.flushPending())
        assertNull(assembler.flushPending())
    }

    @Test
    fun resetDropsPartialFrameBeforeCommandTraffic() {
        val assembler = InateckScanFrameAssembler()
        assembler.append("partial".bytes())

        assembler.reset()

        assertNull(assembler.flushPending())
        assertEquals("next", assembler.append("next\r".bytes()).single().text())
    }

    @Test
    fun oversizedFrameAndItsSuffixAreDiscarded() {
        val assembler = InateckScanFrameAssembler(maxFrameBytes = 4)

        assertTrue(assembler.append("oversized-suffix".bytes()).isEmpty())
        assertNull(assembler.flushPending())
        assertTrue(assembler.append("delayed-suffix".bytes()).isEmpty())
        assertNull(assembler.flushPending())
        assertTrue(assembler.append("\r".bytes()).isEmpty())
        assertEquals("safe", assembler.append("safe\r".bytes()).single().text())
    }

    private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)
    private fun ByteArray.text(): String = toString(StandardCharsets.UTF_8)
}
