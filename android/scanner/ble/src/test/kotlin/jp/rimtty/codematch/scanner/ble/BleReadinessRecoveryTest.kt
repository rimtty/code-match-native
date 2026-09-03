package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReadinessRecoveryTest {
    private val device = ScannerDevice("synthetic-scanner", "Test scanner")

    @Test
    fun explicitDiscoveryCancelsAwaitingReconnectAfterRadioRecovery() {
        val transport = RecordingTransport()
        val coordinator = coordinator(transport)
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.ConnectionFailed(device, "Synthetic failure"))
        transport.availability = BleAvailability.PoweredOff
        transport.emit(BleTransportEvent.AvailabilityChanged(transport.availability))
        transport.availability = BleAvailability.Ready
        transport.emit(BleTransportEvent.AvailabilityChanged(transport.availability))
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)

        assertTrue(coordinator.startDiscovery())
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(1_000L))
        assertEquals(BleConnectionState.Searching, coordinator.connectionState)
        assertEquals(1, transport.connectCalls)
        assertEquals(1, transport.discoveryStarts)
    }

    @Test
    fun scheduledReconnectWaitsForAnAlreadyStartedDiscoveryToStop() {
        val transport = RecordingTransport()
        val coordinator = coordinator(transport)
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.ConnectionFailed(device, "Synthetic failure"))
        // An adapter-owned discovery event can arrive without the public
        // startDiscovery entry point. The ticker must still not overlap it.
        transport.emit(BleTransportEvent.DiscoveryStarted)
        assertFalse(coordinator.tick(1_000L))
        assertEquals(1, transport.connectCalls)
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)

        transport.emit(BleTransportEvent.DiscoveryStopped)
        assertTrue(coordinator.tick(1_000L))
        assertEquals(2, transport.connectCalls)
    }

    @Test
    fun manualCloseFailureRemainsRetryableAfterAvailabilityRecovery() {
        val transport = RecordingTransport()
        val coordinator = coordinator(transport)
        failManualCloseThenRecoverRadio(coordinator, transport)

        assertTrue(coordinator.disconnect())
        assertEquals(2, transport.disconnectCalls)
        assertTrue(coordinator.hasPhysicalLink)
        assertTrue(coordinator.disconnect())
        assertEquals(2, transport.disconnectCalls)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertFalse(coordinator.hasPhysicalLink)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(10_000L))
        assertEquals(1, transport.connectCalls)
    }

    @Test
    fun reconnectAfterFailedManualCloseClosesBeforeConnectingEvenAfterRadioRecovery() {
        val transport = RecordingTransport()
        val coordinator = coordinator(transport)
        failManualCloseThenRecoverRadio(coordinator, transport)

        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(2, transport.disconnectCalls)
        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(2, transport.disconnectCalls)
        assertFalse(coordinator.tick(1_000L))
        assertEquals(1, transport.connectCalls)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertTrue(coordinator.tick(1_000L))
        assertEquals(2, transport.connectCalls)
    }

    private fun failManualCloseThenRecoverRadio(
        coordinator: BleConnectionCoordinator,
        transport: RecordingTransport,
    ) {
        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertTrue(coordinator.disconnect())
        transport.emit(BleTransportEvent.DisconnectFailed(device))
        transport.availability = BleAvailability.PoweredOff
        transport.emit(BleTransportEvent.AvailabilityChanged(transport.availability))
        transport.availability = BleAvailability.Ready
        transport.emit(BleTransportEvent.AvailabilityChanged(transport.availability))
        assertTrue(coordinator.hasPhysicalLink)
        assertEquals(1, transport.disconnectCalls)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
    }

    private fun coordinator(transport: RecordingTransport) = BleConnectionCoordinator(
        transport = transport,
        nowMillis = { 0L },
        reconnectDelayMillis = { 1_000L },
    )

    private class RecordingTransport : BleTransport {
        override var availability: BleAvailability = BleAvailability.Ready
        override var listener: BleTransportListener? = null
        var discoveryStarts = 0
        var connectCalls = 0
        var disconnectCalls = 0

        override fun startDiscovery(): Boolean {
            discoveryStarts++
            return true
        }
        override fun stopDiscovery() = true
        override fun connect(device: ScannerDevice): Boolean {
            connectCalls++
            return true
        }
        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls++
            return true
        }
        override fun read(
            characteristicUuid: String,
            completion: (Result<ByteArray>) -> Unit,
        ) = false
        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ) = false
        fun emit(event: BleTransportEvent) = listener?.onTransportEvent(event)
    }
}
