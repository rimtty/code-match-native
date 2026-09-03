package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleKnownDeviceRecoveryTest {
    private val profileIdentity = "observed-adapter-profile-v1"
    private val device = ScannerDevice("scanner-1", "BCST-47")

    @Test
    fun knownIdentityRoundTripsWithoutSettingsOrPayloadFields() {
        val serializer = BleKnownDeviceSerializer()

        val encoded = serializer.encode(device, profileIdentity)

        assertEquals(
            BleKnownDeviceReadResult.Found(device),
            serializer.decodeResult(encoded, profileIdentity),
        )
        assertTrue(encoded.contains("deviceId"))
        assertTrue(encoded.contains("deviceName"))
        assertTrue(encoded.contains("profileIdentity"))
        assertFalse(encoded.contains("settings"))
        assertFalse(encoded.contains("scanPayload"))
        assertFalse(encoded.contains("rawFrame"))
    }

    @Test
    fun corruptVersionAndProfileIdentityAreRejectedBeforeReconnect() {
        val serializer = BleKnownDeviceSerializer()
        val encoded = serializer.encode(device, profileIdentity)

        assertEquals(
            BleKnownDeviceReadResult.Rejected(BleKnownDeviceRejectionReason.CORRUPT),
            serializer.decodeResult("not-json", profileIdentity),
        )
        assertEquals(
            BleKnownDeviceReadResult.Rejected(BleKnownDeviceRejectionReason.UNSUPPORTED_VERSION),
            serializer.decodeResult(
                encoded.replace(
                    "\"schemaVersion\":1",
                    "\"schemaVersion\":2",
                ),
                profileIdentity,
            ),
        )
        assertEquals(
            BleKnownDeviceReadResult.Rejected(BleKnownDeviceRejectionReason.PROFILE_MISMATCH),
            serializer.decodeResult(encoded, "another-adapter-profile"),
        )
        assertEquals(
            BleKnownDeviceReadResult.Rejected(BleKnownDeviceRejectionReason.CORRUPT),
            serializer.decodeResult(
                "{\"schemaVersion\":1,\"profileIdentity\":\"$profileIdentity\",\"deviceId\":\"\",\"deviceName\":\"scanner\"}",
                profileIdentity,
            ),
        )
    }

    @Test
    fun coordinatorReusesPersistedKnownDeviceAfterServiceRecreation() {
        val knownStore = InMemoryKnownDeviceStore(profileIdentity)
        val firstTransport = RecordingTransport()
        val first = coordinator(firstTransport, knownStore)

        assertTrue(first.connect(device))
        firstTransport.emit(BleTransportEvent.Connected(device))
        assertEquals(BleKnownDeviceReadResult.Found(device), knownStore.read())

        assertTrue(first.disconnect())
        assertEquals(BleConnectionState.Idle, first.connectionState)
        assertNull(first.pendingReconnectAtMillis)
        firstTransport.emit(BleTransportEvent.Disconnected(device, unexpected = false))
        assertEquals(BleConnectionState.Idle, first.connectionState)

        // A new service/coordinator instance can reconstruct the ScannerDevice
        // from the app-private identity store without running discovery.
        val secondTransport = RecordingTransport()
        val second = coordinator(secondTransport, knownStore)
        assertEquals(device, second.knownDevice)
        assertEquals(BleKnownDeviceReadResult.Found(device), second.persistedKnownDevice)
        assertTrue(second.reconnectKnownDevice())
        assertEquals(listOf(device), secondTransport.connectCalls)
        assertEquals(BleConnectionState.Connecting(device), second.connectionState)
    }

    @Test
    fun knownReconnectRetriesAfterTemporaryBluetoothOutage() {
        val knownStore = InMemoryKnownDeviceStore(profileIdentity).apply {
            save(device)
        }
        val transport = RecordingTransport(BleAvailability.PoweredOff)
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            knownDeviceStore = knownStore,
            reconnectDelayMillis = { 1_000L },
            nowMillis = { 0L },
        )

        // A process recreation can happen while the scanner radio is off.
        // The first recovery request remains bounded but must not be lost.
        assertFalse(coordinator.reconnectKnownDevice())
        assertEquals(
            BleConnectionState.Unavailable("Bluetooth is off"),
            coordinator.connectionState,
        )
        assertEquals(1_000L, coordinator.pendingReconnectAtMillis)
        assertTrue(transport.connectCalls.isEmpty())

        transport.availability = BleAvailability.Ready
        assertFalse(coordinator.tick(999L))
        assertTrue(coordinator.tick(1_000L))
        assertEquals(listOf(device), transport.connectCalls)
        assertEquals(BleConnectionState.Reconnecting(device, 1), coordinator.connectionState)
    }

    @Test
    fun unavailableKnownReconnectStopsAfterBoundedAttempts() {
        val knownStore = InMemoryKnownDeviceStore(profileIdentity).apply {
            save(device)
        }
        val transport = RecordingTransport(BleAvailability.PoweredOff)
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            knownDeviceStore = knownStore,
            reconnectDelayMillis = { 100L },
            maxReconnectAttempts = 2,
            nowMillis = { 0L },
        )

        assertFalse(coordinator.reconnectKnownDevice())
        assertEquals(100L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(99L))
        assertFalse(coordinator.tick(100L))
        assertEquals(200L, coordinator.pendingReconnectAtMillis)
        assertFalse(coordinator.tick(200L))
        assertEquals(null, coordinator.pendingReconnectAtMillis)
        assertEquals(2, coordinator.reconnectAttemptCount)
        assertTrue(transport.connectCalls.isEmpty())
        assertTrue(coordinator.state.diagnostics.size <= 20)
    }

    @Test
    fun rejectedIdentityCannotStartReconnectOrAdvertiseReady() {
        val expectedProfileIdentity = profileIdentity
        val knownStore = object : KnownDeviceStore {
            override val profileIdentity: String = expectedProfileIdentity

            override fun read(): BleKnownDeviceReadResult = BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.PROFILE_MISMATCH,
            )

            override fun save(device: ScannerDevice): BleKnownDeviceWriteResult =
                BleKnownDeviceWriteResult.Rejected(BleKnownDeviceRejectionReason.PROFILE_MISMATCH)

            override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult =
                BleKnownDeviceClearResult.Rejected(BleKnownDeviceRejectionReason.PROFILE_MISMATCH)
        }
        val transport = RecordingTransport()
        val coordinator = coordinator(transport, knownStore)

        assertNull(coordinator.knownDevice)
        assertFalse(coordinator.reconnectKnownDevice())
        assertTrue(transport.connectCalls.isEmpty())
        assertFalse(coordinator.configurationState.isReady)
        assertTrue(coordinator.state.diagnostics.none { it.message.contains("profile") })
    }

    private fun coordinator(
        transport: RecordingTransport,
        store: KnownDeviceStore,
    ): BleConnectionCoordinator = BleConnectionCoordinator(
        transport = transport,
        knownDeviceStore = store,
        nowMillis = { 0L },
    )

    private class InMemoryKnownDeviceStore(
        override val profileIdentity: String,
    ) : KnownDeviceStore {
        private var current: BleKnownDeviceReadResult = BleKnownDeviceReadResult.Missing

        override fun read(): BleKnownDeviceReadResult = current

        override fun save(device: ScannerDevice): BleKnownDeviceWriteResult {
            current = BleKnownDeviceReadResult.Found(device)
            return BleKnownDeviceWriteResult.Saved
        }

        override fun clear(expectedDeviceId: String?): BleKnownDeviceClearResult {
            val found = current as? BleKnownDeviceReadResult.Found
                ?: return BleKnownDeviceClearResult.Missing
            if (expectedDeviceId != null && expectedDeviceId != found.device.id) {
                return BleKnownDeviceClearResult.Rejected(
                    BleKnownDeviceRejectionReason.DEVICE_MISMATCH,
                )
            }
            current = BleKnownDeviceReadResult.Missing
            return BleKnownDeviceClearResult.Cleared
        }
    }

    private class RecordingTransport(
        override var availability: BleAvailability = BleAvailability.Ready,
    ) : BleTransport {
        override var listener: BleTransportListener? = null
        val connectCalls = mutableListOf<ScannerDevice>()

        override fun startDiscovery(): Boolean = true

        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            connectCalls += device
            return true
        }

        override fun disconnect(device: ScannerDevice): Boolean = true

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
