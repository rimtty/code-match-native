package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckOperationGateTest {
    @Test
    fun onlyOneOperationCanRunAndInvalidatedCallbackStaysStale() {
        val gate = InateckOperationGate()
        val timedOut = gate.begin()

        assertNotNull(timedOut)
        assertNull(gate.begin())
        assertTrue(gate.isCurrent(timedOut!!))

        gate.invalidate()
        assertFalse(gate.isCurrent(timedOut))

        val reconnectOperation = gate.begin()
        assertNotNull(reconnectOperation)
        assertNotEquals(timedOut, reconnectOperation)
        assertFalse(gate.isCurrent(timedOut))
    }
}
