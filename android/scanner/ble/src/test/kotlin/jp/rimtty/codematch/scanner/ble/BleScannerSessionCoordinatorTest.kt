package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScannerSessionCoordinatorTest {
    private val device = ScannerDevice("scanner-1", "observed scanner")
    private val profileIdentity = "adapter-profile-v1"

    @Test
    fun connectionReadAndWriteMustCompleteBeforePayloadsAreForwarded() {
        val transport = RecordingTransport()
        val connection = BleConnectionCoordinator(transport)
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(
                settingsCharacteristicUuid = "endpoint-supplied-by-adapter",
                codec = IosObservedSymbologyCodec,
                identity = profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )
        val bridge = BleScannerSessionCoordinator(connection, session)
        val received = mutableListOf<ScanPayload>()
        bridge.onPayload = { received += it }

        assertTrue(bridge.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(BleSymbologySessionState.LoadingSettings, bridge.state.symbology)
        assertEquals(ConfigurationState.Configuring, bridge.state.configuration)
        assertFalse(bridge.state.isReadyForScanning)

        // A physical connection alone must not leak a scan callback.
        transport.emit(
            BleTransportEvent.ScanReceived(
                ScanPayload.qr("before-ready", source = InputSource.BLUETOOTH),
            ),
        )
        assertTrue(received.isEmpty())

        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Ready, bridge.state.symbology)
        assertEquals(ConfigurationState.Ready, bridge.state.configuration)
        assertFalse(bridge.state.isReadyForScanning)

        assertTrue(bridge.startSession(ScanFormat.QR))
        assertEquals(BleSymbologySessionState.ApplyingSession(ScanFormat.QR), bridge.state.symbology)
        assertFalse(bridge.state.isReadyForScanning)
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.SessionReady, bridge.state.symbology)
        assertTrue(bridge.state.isReadyForScanning)

        val payload = ScanPayload(
            value = "after-ready",
            source = InputSource.BLUETOOTH,
            format = ScanFormat.QR,
            timestampMillis = 1L,
        )
        transport.emit(BleTransportEvent.ScanReceived(payload))
        assertEquals(listOf(payload), received)

        assertTrue(bridge.setExpectedFormat(ScanFormat.CODE_128))
        transport.emit(BleTransportEvent.ScanReceived(payload))
        assertEquals(1, received.size)
        assertFalse(bridge.state.isReadyForScanning)
        transport.completeWrite(Result.success(Unit))
        assertTrue(bridge.state.isReadyForScanning)
        transport.emit(
            BleTransportEvent.ScanReceived(
                payload.copy(value = "code128-step", format = ScanFormat.QR),
            ),
        )
        assertEquals(ScanFormat.CODE_128, received.last().format)

        // The BLE bridge drops a mislabeled callback rather than handing a
        // camera value to a production BLE consumer.
        transport.emit(
            BleTransportEvent.ScanReceived(
                payload.copy(value = "wrong-source", source = InputSource.CAMERA),
            ),
        )
        assertEquals(2, received.size)
    }

    @Test
    fun disconnectInvalidatesSessionAndStopsPayloadDelivery() {
        val transport = RecordingTransport()
        val connection = BleConnectionCoordinator(transport)
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(
                settingsCharacteristicUuid = "adapter-endpoint",
                codec = IosObservedSymbologyCodec,
                identity = profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )
        val bridge = BleScannerSessionCoordinator(connection, session)
        val received = mutableListOf<ScanPayload>()
        bridge.onPayload = { received += it }

        bridge.connect(device)
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(settingsJson())
        bridge.startSession(ScanFormat.QR)
        transport.completeWrite(Result.success(Unit))
        assertTrue(bridge.state.isReadyForScanning)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = true))
        assertEquals(BleSymbologySessionState.Disconnected, bridge.state.symbology)
        assertEquals(ConfigurationState.Unavailable, bridge.state.configuration)
        assertFalse(bridge.state.isReadyForScanning)

        transport.emit(BleTransportEvent.ScanReceived(ScanPayload.qr("after-disconnect")))
        assertTrue(received.isEmpty())
    }

    @Test
    fun adapterReadinessBlocksDiscoveryAndConnectionWithoutPlatformTypes() {
        val transport = RecordingTransport()
        transport.readiness = BleTransportReadiness(
            lifecycle = BleAdapterLifecycleState.BACKGROUND,
            availability = BleAvailability.Ready,
        )
        val connection = BleConnectionCoordinator(transport)

        assertFalse(connection.startDiscovery())
        assertEquals(
            BleConnectionState.Unavailable("Bluetooth adapter is inactive"),
            connection.connectionState,
        )
        assertFalse(connection.connect(device))
        assertEquals(0, transport.connectCalls)

        transport.readiness = BleTransportReadiness(
            lifecycle = BleAdapterLifecycleState.FOREGROUND,
            availability = BleAvailability.Ready,
            discoveryPermission = BlePermissionState.GRANTED,
            connectionPermission = BlePermissionState.DENIED,
        )
        assertFalse(connection.connect(device))
        assertEquals(
            BleConnectionState.Unavailable("Bluetooth connection permission is required"),
            connection.connectionState,
        )
        assertEquals(0, transport.connectCalls)
    }

    @Test
    fun closeDetachesCallbacksAndRejectsNewOperations() {
        val transport = RecordingTransport()
        val connection = BleConnectionCoordinator(transport)
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile("endpoint", IosObservedSymbologyCodec, profileIdentity),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )
        val bridge = BleScannerSessionCoordinator(connection, session)
        var states = 0
        bridge.setListener { states++ }
        bridge.close()
        val statesAfterClose = states

        assertFalse(bridge.startDiscovery())
        assertFalse(bridge.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        assertEquals(statesAfterClose, states)
    }

    private class RecordingTransport : BleTransport {
        override var availability: BleAvailability = BleAvailability.Ready
        override var readiness: BleTransportReadiness = BleTransportReadiness()
        override var listener: BleTransportListener? = null
        var connectCalls: Int = 0
        private val readCallbacks = ArrayDeque<(Result<ByteArray>) -> Unit>()
        private val writeCallbacks = ArrayDeque<(Result<Unit>) -> Unit>()

        override fun startDiscovery(): Boolean = true

        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls++
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean = true

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            writeCallbacks += completion
            return true
        }

        override fun read(
            characteristicUuid: String,
            completion: (Result<ByteArray>) -> Unit,
        ): Boolean {
            readCallbacks += completion
            return true
        }

        fun emit(event: BleTransportEvent) {
            listener?.onTransportEvent(event)
        }

        fun completeRead(json: String) {
            readCallbacks.removeFirst()(Result.success(json.toByteArray(Charsets.UTF_8)))
        }

        fun completeWrite(result: Result<Unit>) {
            writeCallbacks.removeFirst()(result)
        }
    }

    private fun settingsJson(): String = """
        {"data":[
          {"area":"qr-area","value":"1","name":"qrcode_on"},
          {"area":"code128-area","value":"1","name":"code128_on"},
          {"area":"other-area","value":"1","name":"code39_on"}
        ]}
    """.trimIndent()
}
