package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScannerSessionManualDisconnectTest {
    private val device = ScannerDevice("scanner-1", "observed scanner")
    private val profileIdentity = "adapter-profile-v1"

    @Test
    fun latestManualDisconnectCancelsReconnectQueuedDuringRestore() {
        val transport = RecordingTransport()
        val connection = BleConnectionCoordinator(
            transport = transport,
            nowMillis = { 0L },
        )
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(
                settingsCharacteristicUuid = "adapter-settings-endpoint",
                codec = IosObservedSymbologyCodec,
                identity = profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )
        val bridge = BleScannerSessionCoordinator(connection, session)

        assertTrue(bridge.connect(device))
        transport.emit(BleTransportEvent.Connected(device))
        transport.completeRead(originalSettings())
        assertTrue(bridge.startSession(ScanFormat.QR))
        transport.completeWrite(Result.success(Unit))

        assertTrue(bridge.disconnect())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertTrue(bridge.reconnectKnownDevice())

        // The latest manual action wins, even though reconnect was requested
        // while the baseline restore was still pending.
        assertTrue(bridge.disconnect())
        transport.completeWrite(Result.success(Unit))
        assertEquals(1, transport.disconnectCalls)

        transport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(null, connection.pendingReconnectAtMillis)
        assertFalse(connection.tick(8_000L))
        assertEquals(1, transport.connectCalls)
    }

    private class RecordingTransport : BleTransport {
        override val availability: BleAvailability = BleAvailability.Ready
        override var listener: BleTransportListener? = null
        var connectCalls = 0
        var disconnectCalls = 0
        private val readCallbacks = ArrayDeque<(Result<ByteArray>) -> Unit>()
        private val writeCallbacks = ArrayDeque<(Result<Unit>) -> Unit>()

        override fun startDiscovery(): Boolean = true

        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls++
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls++
            return true
        }

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

    private fun originalSettings(): String = """
        {"data":[
          {"area":"qr-area","value":"1","name":"qrcode_on"},
          {"area":"code128-area","value":"1","name":"code128_on"},
          {"area":"other-area","value":"1","name":"code39_on"}
        ]}
    """.trimIndent()
}
