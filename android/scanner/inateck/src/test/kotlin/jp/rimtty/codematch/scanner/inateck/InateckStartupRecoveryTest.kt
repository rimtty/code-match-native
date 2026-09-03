package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckStartupRecoveryTest {
    @Test
    fun reconnectIsRequestedOncePerForegroundEpoch() {
        var calls = 0
        val recovery = InateckStartupRecovery {
            calls++
            true
        }

        assertTrue(recovery.onForeground())
        assertFalse(recovery.onForeground())
        assertEquals(1, calls)

        recovery.onBackground()
        assertTrue(recovery.onForeground())
        assertEquals(2, calls)
    }

    @Test
    fun failedAttemptCanRetryAfterBluetoothOrPermissionRecovery() {
        var calls = 0
        val recovery = InateckStartupRecovery {
            calls++
            false
        }

        assertFalse(recovery.onForeground())
        assertFalse(recovery.onForeground())
        assertEquals(1, calls)

        recovery.onBackground()
        assertFalse(recovery.onForeground())
        assertEquals(2, calls)
    }
}
