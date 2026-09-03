package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionCoordinatorTest {
    private val device = ScannerDevice("scanner-1", "BCST-47")

    @Test
    fun discoveryConnectionAndConfigurationAreExposedAsTypedStates() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport)

        assertTrue(coordinator.startDiscovery())
        assertEquals(BleConnectionState.Searching, coordinator.connectionState)
        transport.emit(BleTransportEvent.DeviceFound(BleDiscoveredDevice(device)))
        assertEquals(listOf(device), coordinator.devices)
        transport.emit(BleTransportEvent.DiscoveryStopped)
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)

        assertTrue(coordinator.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertEquals(ConfigurationState.Configuring, coordinator.configurationState)
        coordinator.markConfiguration(ConfigurationState.Ready)
        assertTrue(coordinator.state.configuration.isReady)
        assertEquals(ConnectionState.Connected(device), coordinator.connectionState.asApiState())
    }

    @Test
    fun unexpectedDisconnectSchedulesReconnectAndManualDisconnectDoesNot() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { 0L },
        )
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        assertEquals(BleConnectionState.Failed("Bluetooth scanner disconnected"), coordinator.connectionState)
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(999L))
        assertTrue(coordinator.tick(1_000L))
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
        assertEquals(listOf(device, device), transport.connectCalls)

        transport.emit(BleTransportEvent.Connected(device))
        assertTrue(coordinator.disconnect())
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
    }

    @Test
    fun scanPayloadIsForwardedButNeverWrittenToDiagnostics() {
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { 0L })
        val received = mutableListOf<ScanPayload>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload
            }
        })
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))
        val privateValue = "PRIVATE-SCAN-PAYLOAD"
        val payload = ScanPayload(
            value = privateValue,
            source = InputSource.BLUETOOTH,
            format = ScanFormat.QR,
            timestampMillis = 10L,
        )

        transport.emit(BleTransportEvent.ScanReceived(payload))

        assertEquals(listOf(payload), received)
        assertTrue(coordinator.state.diagnostics.none { it.message.contains(privateValue) })
    }

    @Test
    fun connectionCoordinatorNormalizesAndDebouncesPayloadsAndResetsOnReconnect() {
        var now = 1_000L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })
        val received = mutableListOf<String>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload.value
            }
        })
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))

        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC\r", timestampMillis = now)))
        now += 749L
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC\n", timestampMillis = now)))
        now += 1L
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC", timestampMillis = now)))
        assertEquals(listOf("ABC", "ABC"), received)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        coordinator.connect(device)
        transport.emit(BleTransportEvent.Connected(device))
        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("ABC", timestampMillis = now)))
        assertEquals(listOf("ABC", "ABC", "ABC"), received)
    }

    @Test
    fun unavailableBluetoothPreventsDiscoveryAndReportsRecoveryState() {
        val transport = RecordingTransport(BleAvailability.PoweredOff)
        val coordinator = BleConnectionCoordinator(transport)

        assertFalse(coordinator.startDiscovery())
        assertEquals(
            BleConnectionState.Unavailable("Bluetooth is off"),
            coordinator.connectionState,
        )
        assertTrue(transport.discoveryStarts == 0)
    }

    @Test
    fun discoveryTimeoutStopsAtExactlyFiveSeconds() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })

        assertTrue(coordinator.startDiscovery())
        assertFalse(coordinator.tick(4_999L))
        assertEquals(BleConnectionState.Searching, coordinator.connectionState)
        assertTrue(coordinator.tick(5_000L))
        assertEquals(BleConnectionState.Failed("Bluetooth discovery timed out"), coordinator.connectionState)
        assertEquals(1, transport.discoveryStops)
    }

    @Test
    fun connectionTimeoutUsesThirtySecondsAndSchedulesEightSecondReconnect() {
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })

        assertTrue(coordinator.connect(device))
        assertFalse(coordinator.tick(29_999L))
        assertEquals(BleConnectionState.Connecting(device), coordinator.connectionState)
        now = 30_000L
        assertTrue(coordinator.tick(30_000L))
        assertEquals(BleConnectionState.Failed("Bluetooth connection timed out"), coordinator.connectionState)
        assertEquals(listOf(device), transport.disconnectCalls)
        assertEquals(null, coordinator.pendingReconnectAtMillis)

        // A new link is not attempted until the transport confirms that the
        // timed-out physical GATT has actually closed.
        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(38_000L, coordinator.pendingReconnectAtMillis)

        now = 37_999L
        assertFalse(coordinator.tick(now))
        now = 38_000L
        assertTrue(coordinator.tick(now))
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
        assertEquals(listOf(device, device), transport.connectCalls)
    }

    @Test
    fun failedTimeoutCancellationCannotPublishLateConnectionOrStartReconnect() {
        var now = 0L
        val transport = RecordingTransport().apply { disconnectAccepted = false }
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })

        assertTrue(coordinator.connect(device))
        now = 30_000L
        assertFalse(coordinator.tick(now))
        assertEquals(BleConnectionState.Failed("Bluetooth connection timed out"), coordinator.connectionState)
        assertEquals(null, coordinator.pendingReconnectAtMillis)

        // The SDK may finish connecting after its public cancellation failed.
        // It must be closed again and must never become app-visible Connected.
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(2, transport.disconnectCalls.size)
        assertTrue(coordinator.connectionState !is BleConnectionState.Connected)
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(60_000L))
        assertEquals(1, transport.connectCalls.size)
    }

    @Test
    fun staleGenerationOrDeviceEventsCannotReplaceCurrentLinkOrDeliverScan() {
        val otherDevice = ScannerDevice("scanner-2", "BCST-47")
        var now = 0L
        val transport = RecordingTransport()
        val coordinator = BleConnectionCoordinator(transport, nowMillis = { now })
        val received = mutableListOf<String>()
        coordinator.setListener(object : BleScannerListener {
            override fun onScanPayload(payload: ScanPayload) {
                received += payload.value
            }
        })

        assertTrue(coordinator.connect(device))
        val firstRequest = coordinator.pendingRequestGeneration
        val firstLink = coordinator.pendingLinkGeneration
        transport.emit(
            BleTransportEvent.Connected(
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = true,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        now = 8_000L
        assertTrue(coordinator.tick(now))
        val secondRequest = coordinator.pendingRequestGeneration
        val secondLink = coordinator.pendingLinkGeneration

        // A late callback from the first link and a device mismatch are both
        // ignored while the second request is still connecting.
        transport.emit(
            BleTransportEvent.Connected(
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(BleTransportEvent.Connected(otherDevice))
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)

        transport.emit(
            BleTransportEvent.Connected(
                device = device,
                linkGeneration = secondLink,
                requestGeneration = secondRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.Disconnected(
                device = device,
                unexpected = true,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        transport.emit(
            BleTransportEvent.ScanReceived(
                payload = ScanPayload.qr("STALE"),
                device = device,
                linkGeneration = firstLink,
                requestGeneration = firstRequest,
            ),
        )
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertTrue(coordinator.pendingReconnectAtMillis == null)

        transport.emit(
            BleTransportEvent.ScanReceived(
                payload = ScanPayload.qr("CURRENT"),
                device = device,
                linkGeneration = secondLink,
                requestGeneration = secondRequest,
            ),
        )
        assertEquals(listOf("CURRENT"), received)
    }

    private class RecordingTransport(
        override var availability: BleAvailability = BleAvailability.Ready,
    ) : BleTransport {
        override var listener: BleTransportListener? = null
        var discoveryStarts = 0
        var discoveryStops = 0
        val connectCalls = mutableListOf<ScannerDevice>()
        val disconnectCalls = mutableListOf<ScannerDevice>()
        var disconnectAccepted = true

        override fun startDiscovery(): Boolean {
            discoveryStarts += 1
            listener?.onTransportEvent(BleTransportEvent.DiscoveryStarted)
            return true
        }

        override fun stopDiscovery(): Boolean {
            discoveryStops += 1
            listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
            return true
        }

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls += device
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls += device
            return disconnectAccepted
        }

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean = true

        override fun read(
            characteristicUuid: String,
            completion: (Result<ByteArray>) -> Unit,
        ): Boolean = true

        fun emit(event: BleTransportEvent) {
            listener?.onTransportEvent(event)
        }
    }
}
