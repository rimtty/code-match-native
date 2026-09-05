package jp.rimtty.codematch.scanner.inateck

import com.google.gson.Gson
import jp.rimtty.codematch.scanner.ble.*
import org.junit.Assert.*
import org.junit.Test

class InateckExactReplayGatewayTest {
    private val items = listOf(
        mapOf("area" to "test", "name" to "qrcode_on", "value" to "1"),
        mapOf("area" to "test", "name" to "code128_on", "value" to "0"),
    )
    private val snapshot = SymbologySnapshot("synthetic", listOf(
        ScannerSettingItem("qrcode_on", "test", 1), ScannerSettingItem("code128_on", "test", 0),
    ))
    private val command get() = Gson().toJson(items)

    @Test fun newConnectionUsesRetainedBaselineUntilVerifiedRecoveryIsAcknowledged() {
        val store = InMemorySymbologySnapshotStore(INATECK_ANDROID_SDK_PROFILE_IDENTITY)
        store.save(snapshot)
        // A newly created gateway/transport/session represents the next physical
        // connection. Reuse the old store, never recapture from the new read.
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        val transport = InateckSdkTransport(gateway)
        val device = jp.rimtty.codematch.scanner.api.ScannerDevice(snapshot.deviceId, "test")
        assertTrue(gateway.arm(requireNotNull(store.load(device.id))))
        assertTrue(transport.connect(device))
        val session = BleSymbologySession(device, transport,
            BleSymbologyProfile(INATECK_SETTINGS_ENDPOINT, InateckAreaNameSymbologyCodec, INATECK_ANDROID_SDK_PROFILE_IDENTITY), store,
            nowMillis = { 123L })
        assertTrue(session.onConnected())
        sdk.readCallback!!(Result.success(items))
        assertFalse(session.configurationState.isReady)
        assertEquals(snapshot, store.load(device.id))
        sdk.readCallback!!(Result.success(items))
        sdk.writeCallback!!(Result.success(Unit))
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        assertFalse(session.configurationState.isReady)
        assertEquals(snapshot, store.load(device.id))
        assertTrue(gateway.releaseCompletedWrite())
        assertEquals(BleSymbologySessionState.Ready, session.state)
        assertTrue(session.configurationState.isReady)
        assertNull(store.load(device.id))
        assertEquals(snapshot.copy(capturedAtMillis = 123L), session.currentSnapshot)
    }

    @Test fun productionTwentyFiveSecondWriteDeadlineKeepsBaselineAndIgnoresLateSuccess() {
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        val transport = InateckSdkTransport(gateway)
        val device = jp.rimtty.codematch.scanner.api.ScannerDevice(snapshot.deviceId, "test")
        val store = InMemorySymbologySnapshotStore(INATECK_ANDROID_SDK_PROFILE_IDENTITY)
        store.save(snapshot)
        var now = 0L
        val session = BleSymbologySession(device, transport,
            BleSymbologyProfile(INATECK_SETTINGS_ENDPOINT, InateckAreaNameSymbologyCodec, INATECK_ANDROID_SDK_PROFILE_IDENTITY),
            store, nowMillis = { now }, commandTimeoutMillis = 25_000, settingsReadTimeoutMillis = 6_000)
        assertTrue(transport.connect(device))
        assertTrue(gateway.arm(snapshot))
        assertTrue(session.onConnected())
        sdk.readCallback!!(Result.success(items)) // session recovery inventory
        sdk.readCallback!!(Result.success(items)) // immediate pre-write equality gate
        sdk.writeCallback!!(Result.success(Unit)) // held SDK completion
        now = 24_999
        session.tick()
        assertEquals(BleSymbologySessionState.Restoring, session.state)
        now = 25_000
        session.tick()
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertTrue(transport.isLinkActive)
        assertFalse(transport.connect(device))
        assertTrue(gateway.releaseCompletedWrite())
        assertFalse(session.configurationState.isReady)
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertEquals(snapshot, store.load(device.id))
        assertEquals(1, gateway.issuedWrites)
        sdk.disconnectCallback!!(Result.success(Unit))
        assertFalse(transport.isLinkActive)
    }

    @Test fun refusesUnarmedWrongDeviceModifiedAndPartialCommands() {
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        assertFalse(gateway.writeSettings(snapshot.deviceId, command) {})
        assertTrue(gateway.arm(snapshot))
        assertFalse(gateway.arm(snapshot))
        assertFalse(gateway.writeSettings("different", command) {})
        assertFalse(gateway.writeSettings(snapshot.deviceId, Gson().toJson(items.take(1))) {})
        assertFalse(gateway.writeSettings(snapshot.deviceId, command.replace("\"0\"", "\"1\"")) {})
        assertTrue(sdk.events.isEmpty())
    }

    @Test fun freshReadPrecedesOneExactReplayAndResultIsHeldExactlyOnce() {
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        gateway.arm(snapshot)
        var callbacks = 0
        assertTrue(gateway.writeSettings(snapshot.deviceId, command) { assertTrue(it.isSuccess); callbacks++ })
        assertEquals(listOf("read"), sdk.events)
        sdk.readCallback!!(Result.success(items.reversed()))
        sdk.readCallback!!(Result.success(items))
        assertEquals(listOf("read", "write"), sdk.events)
        assertEquals(command, sdk.writtenCommand)
        assertEquals(1, gateway.issuedWrites)
        assertFalse(gateway.writeSettings(snapshot.deviceId, command) {})
        sdk.writeCallback!!(Result.success(Unit))
        assertEquals(0, callbacks)
        assertTrue(gateway.hasCompletedWrite)
        assertTrue(gateway.completedWriteSucceeded)
        assertTrue(gateway.releaseCompletedWrite())
        assertFalse(gateway.releaseCompletedWrite())
        assertEquals(1, callbacks)
    }

    @Test fun freshMismatchAndReadFailureNeverDispatchWrite() {
        for (fresh in listOf(Result.success(items.take(1)), Result.failure(IllegalStateException("private")))) {
            val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
            gateway.arm(snapshot)
            var failed = false
            assertTrue(gateway.writeSettings(snapshot.deviceId, command) { failed = it.isFailure })
            sdk.readCallback!!(fresh)
            assertTrue(failed)
            assertEquals(listOf("read"), sdk.events)
            assertEquals(0, gateway.issuedWrites)
        }
    }

    @Test fun disconnectInvalidatesPreWriteReadButNotHeldWriteDelivery() {
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        gateway.arm(snapshot)
        gateway.writeSettings(snapshot.deviceId, command) {}
        gateway.disconnect(snapshot.deviceId) {}
        sdk.readCallback!!(Result.success(items))
        assertEquals(listOf("read", "disconnect"), sdk.events)
        assertEquals(0, gateway.issuedWrites)
    }

    @Test fun rejectedPreparationCannotDispatchWriteFromALateSdkCallback() {
        val sdk = StubSdk(); sdk.acceptRead = false
        val gateway = InateckExactReplayGateway(sdk)
        gateway.arm(snapshot)
        assertFalse(gateway.writeSettings(snapshot.deviceId, command) {})
        sdk.readCallback!!(Result.success(items))
        assertEquals(listOf("read"), sdk.events)
        assertEquals(0, gateway.issuedWrites)
    }

    @Test fun heldWriteCanBeDeliveredAfterDisconnectForRealTransportToReject() {
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        val transport = InateckSdkTransport(gateway)
        val device = jp.rimtty.codematch.scanner.api.ScannerDevice(snapshot.deviceId, "test")
        assertTrue(transport.connect(device))
        gateway.arm(snapshot)
        var callbacks = 0
        assertTrue(transport.write(INATECK_SETTINGS_ENDPOINT, command.toByteArray()) { callbacks++ })
        sdk.readCallback!!(Result.success(items))
        sdk.writeCallback!!(Result.success(Unit))
        assertTrue(transport.disconnect(device))
        assertTrue(transport.isLinkActive)
        assertFalse(transport.connect(device))
        sdk.disconnectCallback!!(Result.success(Unit))
        assertFalse(transport.isLinkActive)
        assertTrue(gateway.releaseCompletedWrite())
        assertEquals(0, callbacks)
    }

    @Test fun sanitizesSdkFailureAndCloseDiscardsHeldData() {
        val sdk = StubSdk(); val gateway = InateckExactReplayGateway(sdk)
        gateway.arm(snapshot)
        var error: String? = null
        gateway.writeSettings(snapshot.deviceId, command) { error = it.exceptionOrNull()?.message }
        sdk.readCallback!!(Result.success(items))
        sdk.writeCallback!!(Result.failure(IllegalStateException("private SDK reply")))
        assertTrue(gateway.releaseCompletedWrite())
        assertEquals("SDK replay failed", error)
        gateway.close()
        assertFalse(gateway.releaseCompletedWrite())
        assertFalse(gateway.arm(snapshot))
        assertFalse(gateway.setIllumination(snapshot.deviceId, true) {})
    }

    private class StubSdk : InateckSdkGateway {
        override val readiness = BleTransportReadiness()
        val events = mutableListOf<String>()
        var writtenCommand: String? = null
        var acceptRead = true
        var readCallback: ((Result<List<Map<String, String>>>) -> Unit)? = null
        var writeCallback: ((Result<Unit>) -> Unit)? = null
        var disconnectCallback: ((Result<Unit>) -> Unit)? = null
        override fun startDiscovery(onDevice: (InateckSdkDevice) -> Unit, onFinished: () -> Unit) = true
        override fun stopDiscovery() = true
        override fun connect(deviceId: String, onScanBytes: (ByteArray) -> Unit, onDisconnected: (Boolean) -> Unit, completion: (Result<Unit>) -> Unit): Boolean { completion(Result.success(Unit)); return true }
        override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean { events += "disconnect"; disconnectCallback = completion; return true }
        override fun readSettings(deviceId: String, completion: (Result<List<Map<String, String>>>) -> Unit): Boolean { events += "read"; readCallback = completion; return acceptRead }
        override fun writeSettings(deviceId: String, commandJson: String, completion: (Result<Unit>) -> Unit): Boolean { events += "write"; writtenCommand = commandJson; writeCallback = completion; return true }
        override fun close() = Unit
    }
}
