package jp.rimtty.codematch.scanner.ble

import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSymbologySessionTest {
    private val device = ScannerDevice("scanner-1", "BCST-47")
    private val settingsEndpoint = "settings-endpoint-from-adapter"
    private val profileIdentity = "test-profile-v1"

    @Test
    fun connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical() {
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val session = session(transport, store)

        assertTrue(session.onConnected())
        assertEquals(BleSymbologySessionState.LoadingSettings, session.state)
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertEquals(ConfigurationState.Ready, session.configurationState)
        assertNotNull(session.currentSnapshot)

        assertTrue(session.startSession(ScanFormat.QR))
        assertEquals(BleSymbologySessionState.ApplyingSession(ScanFormat.QR), session.state)
        assertEquals(1, transport.writeCallbacks.size)
        val sessionCommands = parseCommands(transport.writes.single())
        assertEquals(2, sessionCommands.count { it.asJsonObject.get("value").asString == "1" })
        assertEquals(0, sessionCommands.first { it.asJsonObject.get("name").asString == "code39_on" }
            .asJsonObject.get("value").asString.toInt())
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.SessionReady, session.state)
        assertTrue(session.isReadyForScanning)

        // Code128 is a logical phase only. No second GATT command is sent.
        assertTrue(session.startSession(ScanFormat.CODE_128))
        assertEquals(1, transport.writes.size)
        assertEquals(ScanFormat.CODE_128, session.expectedFormat)

        assertTrue(session.endSession())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertEquals(1, transport.writeCallbacks.size)
        val restoredCommands = parseCommands(transport.writes.last())
        assertEquals(1, restoredCommands.first { it.asJsonObject.get("name").asString == "code39_on" }
            .asJsonObject.get("value").asString.toInt())
        assertEquals("42", restoredCommands.first { it.asJsonObject.get("name").asString == "qrcode_on" }
            .asJsonObject.get("area").asString)
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertEquals(ConfigurationState.Ready, session.configurationState)
        assertFalse(session.isReadyForScanning)
        assertNull(store.load(device.id))
        assertNull(session.expectedFormat)
    }

    @Test
    fun timeoutBlocksCommandsUntilDisconnectResetAndReconnectRestoresSnapshot() {
        var now = 1_000L
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val session = session(transport, store, nowMillis = { now })
        session.onConnected()
        transport.completeRead(settingsJson())
        session.startSession(ScanFormat.QR)

        now += 3_000L
        assertTrue(session.tick() is BleCommandTickResult.TimedOut)
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertTrue(session.isCommandBlockedAfterTimeout)
        assertEquals(1, transport.disconnectCalls.size)
        assertFalse(session.startSession(ScanFormat.CODE_128))

        // The owner acknowledges link closure before the queue is released.
        session.onTransportResetCompleted()
        assertEquals(BleSymbologySessionState.AwaitingReconnect, session.state)
        assertFalse(session.isCommandBlockedAfterTimeout)
        transport.discardPendingWrites()

        assertTrue(session.onConnected())
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertEquals(2, transport.writes.size)
        transport.completeWrite(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertNull(store.load(device.id))
        assertFalse(session.isSessionActive)
        assertNull(session.preSessionSnapshot)
        assertNull(session.expectedFormat)
    }

    @Test
    fun snapshotBelongingToAnotherDeviceIsRejectedBeforeAnyWrite() {
        val transport = RecordingTransport()
        val store = InMemorySymbologySnapshotStore(profileIdentity)
        val other = SymbologySettings.parse("other-scanner", settingsJson())!!
        store.save(other)
        val session = session(transport, store)

        session.onConnected()
        transport.completeRead(settingsJson())

        assertEquals(
            BleSymbologySessionState.Failed("Saved scanner settings belong to another device"),
            session.state,
        )
        assertEquals(0, transport.writeCallbacks.size)
        assertEquals(ConfigurationState.Failed("Saved scanner settings belong to another device"), session.configurationState)
    }

    @Test
    fun recoveryRejectsChangedAreaOrFlagInsteadOfApplyingByNameOnly() {
        listOf(
            { item: ScannerSettingItem -> item.copy(area = "changed-area") },
            { item: ScannerSettingItem -> item.copy(flag = 2999) },
        ).forEach { mutateIdentity ->
            val transport = RecordingTransport()
            val store = InMemorySymbologySnapshotStore(profileIdentity)
            val saved = SymbologySettings.parse(device.id, settingsJson())!!
            store.save(
                saved.copy(
                    settings = saved.settings.mapIndexed { index, item ->
                        if (index == 1) mutateIdentity(item) else item
                    },
                ),
            )
            val session = session(transport, store)

            assertTrue(session.onConnected())
            transport.completeRead(settingsJson())

            assertEquals(
                BleSymbologySessionState.Failed("Saved scanner settings no longer match the device inventory"),
                session.state,
            )
            assertEquals(0, transport.writeCallbacks.size)
        }
    }

    @Test
    fun incompleteFreshInventoryCannotStartOrReportReady() {
        val transport = RecordingTransport()
        val session = session(transport, InMemorySymbologySnapshotStore(profileIdentity))

        session.onConnected()
        transport.completeRead("""{"data":[{"area":"1","value":"1","name":"qrcode_on"}]}""")

        assertTrue(session.state is BleSymbologySessionState.Failed)
        assertFalse(session.startSession(ScanFormat.QR))
        assertFalse(session.configurationState.isReady)
        assertTrue(session.diagnosticEvents.none { it.message.contains("qrcode") })
    }

    @Test
    fun profileCodecReceivesRawReadBytesAndOwnsWriteEncoding() {
        val transport = RecordingTransport()
        val snapshot = SymbologySnapshot(
            deviceId = device.id,
            settings = listOf(
                ScannerSettingItem(
                    name = SymbologySettings.QR_NAME,
                    area = "android-qr-area",
                    value = 0,
                    flag = 4001,
                    extraFields = mapOf("opaque" to "0xA5"),
                ),
                ScannerSettingItem(
                    name = SymbologySettings.CODE_128_NAME,
                    area = "android-code128-area",
                    value = 1,
                    flag = 4002,
                    extraFields = mapOf("mode" to "binary"),
                ),
                ScannerSettingItem(
                    name = "vendor_symbol",
                    area = "vendor-area",
                    value = 1,
                    flag = 9001,
                    extraFields = mapOf("vendorField" to "opaque"),
                ),
            ),
        )
        val codec = RecordingCodec(snapshot)
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(settingsEndpoint, codec, profileIdentity),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )
        val rawRead = byteArrayOf(0x00, 0x7F, 0x10, 0xFF.toByte())

        assertTrue(session.onConnected())
        transport.completeRead(rawRead)
        assertArrayEquals(rawRead, codec.lastDecodedPayload)
        assertEquals(BleSymbologySessionState.Ready, session.state)

        assertTrue(session.startSession(ScanFormat.QR))
        assertEquals(1, codec.encodeCalls)
        assertEquals(snapshot.settings.size, codec.lastCommands.size)
        assertEquals(4001, codec.lastCommands.first { it.name == SymbologySettings.QR_NAME }.flag)
        assertEquals("0xA5", codec.lastCommands.first { it.name == SymbologySettings.QR_NAME }
            .extraFields["opaque"])
        assertArrayEquals(codec.encodedPayload, transport.writes.single())
    }

    @Test
    fun codecCannotReturnInventoryForAnotherDevice() {
        val transport = RecordingTransport()
        val wrongDeviceSnapshot = SymbologySettings.parse("other-device", settingsJson())!!
        val session = BleSymbologySession(
            device = device,
            transport = transport,
            profile = BleSymbologyProfile(
                settingsEndpoint,
                RecordingCodec(wrongDeviceSnapshot, bindRequestedDeviceId = false),
                profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore(profileIdentity),
        )

        assertTrue(session.onConnected())
        transport.completeRead(byteArrayOf(1))

        assertEquals(
            BleSymbologySessionState.Failed("Scanner settings belong to another device"),
            session.state,
        )
        assertTrue(transport.writes.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun sessionRejectsSnapshotStoreForAnotherProfile() {
        BleSymbologySession(
            device = device,
            transport = RecordingTransport(),
            profile = BleSymbologyProfile(
                settingsEndpoint,
                IosObservedSymbologyCodec,
                profileIdentity,
            ),
            snapshotStore = InMemorySymbologySnapshotStore("other-profile"),
        )
    }

    @Test
    fun restoreDoesNotBecomeReadyWhenPersistedSnapshotCannotBeCleared() {
        val transport = RecordingTransport()
        val backing = InMemorySymbologySnapshotStore(profileIdentity)
        val rejectingStore = object : SymbologySnapshotStore by backing {
            override fun clear(deviceId: String): SymbologySnapshotClearResult =
                SymbologySnapshotClearResult.Rejected("clear rejected")
        }
        val session = session(transport, rejectingStore)

        session.onConnected()
        transport.completeRead(settingsJson())
        assertTrue(session.startSession(ScanFormat.QR))
        transport.completeWrite(Result.success(Unit))
        assertTrue(session.endSession())
        transport.completeWrite(Result.success(Unit))

        assertEquals(
            BleSymbologySessionState.Failed("Saved scanner settings could not be cleared"),
            session.state,
        )
        assertFalse(session.configurationState.isReady)
        assertNotNull(backing.load(device.id))
    }

    @Test
    fun settingsReadIsSingleFlightAndTimesOutBeforeAcceptingLateBytes() {
        var now = 1_000L
        val transport = RecordingTransport()
        val session = session(
            transport = transport,
            store = InMemorySymbologySnapshotStore(profileIdentity),
            nowMillis = { now },
        )

        assertTrue(session.onConnected())
        assertTrue(session.isSettingsReadPending)
        assertFalse(session.onConnected())
        assertEquals(1, transport.readCallbacks.size)

        now += 2_999L
        assertEquals(BleCommandTickResult.Noop, session.tick())
        now += 1L
        assertEquals(BleCommandTickResult.Noop, session.tick())
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(session.isSettingsReadPending)
        assertEquals(1, transport.disconnectCalls.size)

        // The old callback is stale after the timeout and cannot make the
        // session Ready or trigger a settings write.
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(session.onConnected())

        session.onTransportResetCompleted()
        assertTrue(session.onConnected())
        transport.completeRead(settingsJson())
        assertEquals(BleSymbologySessionState.Ready, session.state)
    }

    private fun session(
        transport: RecordingTransport,
        store: SymbologySnapshotStore,
        nowMillis: () -> Long = { 0L },
        settingsReadTimeoutMillis: Long = 3_000L,
    ) = BleSymbologySession(
        device = device,
        transport = transport,
        profile = BleSymbologyProfile(
            settingsEndpoint,
            IosObservedSymbologyCodec,
            profileIdentity,
        ),
        snapshotStore = store,
        diagnostics = BleDiagnosticLog(nowMillis = nowMillis),
        nowMillis = nowMillis,
        settingsReadTimeoutMillis = settingsReadTimeoutMillis,
    )

    private fun parseCommands(payload: ByteArray) =
        JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonArray

    private class RecordingTransport : BleTransport {
        override val availability: BleAvailability = BleAvailability.Ready
        override var listener: BleTransportListener? = null
        val readCallbacks = mutableListOf<(Result<ByteArray>) -> Unit>()
        val writeCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
        val writes = mutableListOf<ByteArray>()
        val disconnectCalls = mutableListOf<ScannerDevice>()

        override fun startDiscovery(): Boolean = true
        override fun stopDiscovery(): Boolean = true
        override fun connect(device: ScannerDevice): Boolean = true

        override fun disconnect(device: ScannerDevice): Boolean {
            disconnectCalls += device
            return true
        }

        override fun write(
            characteristicUuid: String,
            payload: ByteArray,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            writes += payload.copyOf()
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

        fun completeRead(json: String) {
            completeRead(json.toByteArray(Charsets.UTF_8))
        }

        fun completeRead(payload: ByteArray) {
            readCallbacks.removeAt(0)(Result.success(payload))
        }

        fun completeWrite(result: Result<Unit>) {
            writeCallbacks.removeAt(0)(result)
        }

        fun discardPendingWrites() {
            writeCallbacks.clear()
        }
    }

    private class RecordingCodec(
        private val snapshot: SymbologySnapshot,
        private val bindRequestedDeviceId: Boolean = true,
    ) : BleSymbologyCodec {
        var lastDecodedPayload: ByteArray = byteArrayOf()
        var lastCommands: List<SymbologySettingCommand> = emptyList()
        var encodeCalls: Int = 0
        val encodedPayload = byteArrayOf(0x4B, 0x01, 0x7F)

        override fun decodeSnapshot(
            deviceId: String,
            payload: ByteArray,
            capturedAtMillis: Long,
        ): SymbologySnapshot {
            lastDecodedPayload = payload.copyOf()
            return snapshot.copy(
                deviceId = if (bindRequestedDeviceId) deviceId else snapshot.deviceId,
                capturedAtMillis = capturedAtMillis,
            )
        }

        override fun encodeCommands(commands: List<SymbologySettingCommand>): ByteArray {
            encodeCalls++
            lastCommands = commands
            return encodedPayload.copyOf()
        }
    }

    private fun settingsJson(): String = """
        {"data":[
          {"area":"11","value":"1","name":"code39_on"},
          {"area":"42","value":"1","name":"qrcode_on"},
          {"area":17,"value":1,"name":"code128_on"},
          {"area":"12","value":"0","name":"ean_13_on"},
          {"area":"99","value":"1","name":"future_symbol","flag":2028,"vendor":"keep"}
        ]}
    """.trimIndent()
}
