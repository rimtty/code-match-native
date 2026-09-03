package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectBudgetTest {
    private val device = ScannerDevice("test-id", "test-scanner")

    @Test
    fun explicitReconnectStartsANewBudgetAfterAutomaticCloseRetriesAreExhausted() {
        val transport = TestTransport()
        var now = 0L
        val coordinator = BleConnectionCoordinator(
            transport,
            reconnectDelayMillis = { 1_000L },
            maxReconnectAttempts = 1,
            nowMillis = { now },
        )
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertFalse(coordinator.reconnectKnownDevice())
        now = 1_000L
        assertFalse(coordinator.tick(now))
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertTrue(coordinator.hasPhysicalLink)
        assertEquals(1, transport.connectCalls)

        // An explicit retry begins a fresh bounded recovery sequence, still
        // closing the old link before it may start another connection.
        transport.close = { true }
        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(1, transport.connectCalls)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(1_999L))
        now = 2_000L
        assertTrue(coordinator.tick(now))
        assertEquals(2, transport.connectCalls)
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
    }

    @Test
    fun synchronousCloseAcknowledgmentDoesNotOverwriteListenerStartedConnection() {
        val transport = TestTransport()
        val coordinator = BleConnectionCoordinator(transport)
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        var reconnectFromCallback = true
        coordinator.setListener(object : BleScannerListener {
            override fun onStateChanged(state: BleScannerState) {
                if (reconnectFromCallback && state.connection == BleConnectionState.Idle &&
                    !coordinator.hasPhysicalLink
                ) {
                    reconnectFromCallback = false
                    assertTrue(coordinator.connect(device))
                }
            }
        })
        transport.close = {
            transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
            true
        }

        assertTrue(coordinator.disconnect())
        assertEquals(2, transport.connectCalls)
        assertEquals(BleConnectionState.Connecting(device), coordinator.connectionState)
        assertTrue(coordinator.hasPhysicalLink)
    }

    @Test
    fun synchronousConnectFailureUsesTheTickClockForNextBackoff() {
        val transport = TestTransport()
        val coordinator = BleConnectionCoordinator(
            transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { 0L },
        )
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.ConnectionFailed(device, "synthetic failure"))
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        transport.onConnect = {
            transport.emit(BleTransportEvent.ConnectionFailed(device, "synthetic failure"))
            true
        }

        assertTrue(coordinator.tick(1_000L))
        assertEquals(2_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(1_999L))
        assertEquals(2, transport.connectCalls)
    }

    private class TestTransport : BleTransport {
        override val availability = BleAvailability.Ready
        override var listener: BleTransportListener? = null
        var connectCalls = 0
        var onConnect: () -> Boolean = { true }
        var close: () -> Boolean = { false }
        override fun connect(device: ScannerDevice): Boolean {
            connectCalls++
            return onConnect()
        }
        override fun disconnect(device: ScannerDevice): Boolean = close()
        override fun startDiscovery(): Boolean = false
        override fun stopDiscovery(): Boolean = false
        override fun read(characteristicUuid: String, completion: (Result<ByteArray>) -> Unit) = false
        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ) = false
        fun emit(event: BleTransportEvent) = listener?.onTransportEvent(event)
    }
}
