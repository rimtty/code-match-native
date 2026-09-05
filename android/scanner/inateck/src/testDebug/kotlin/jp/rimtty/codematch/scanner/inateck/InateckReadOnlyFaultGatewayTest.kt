package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness
import jp.rimtty.codematch.scanner.ble.BleSymbologySession
import jp.rimtty.codematch.scanner.ble.BleSymbologySessionState
import jp.rimtty.codematch.scanner.ble.BleSymbologyProfile
import jp.rimtty.codematch.scanner.ble.InMemorySymbologySnapshotStore
import org.junit.Assert.*
import org.junit.Test

class InateckReadOnlyFaultGatewayTest {
    @Test fun sixSecondSessionDeadlineRejectsLateReadBeforeDisconnectCompletes() {
        val sdk = StubSdk()
        val gateway = InateckReadOnlyFaultGateway(sdk)
        val transport = InateckSdkTransport(gateway)
        val device = ScannerDevice("synthetic", "test")
        var now = 0L
        val session = BleSymbologySession(
            device, transport,
            BleSymbologyProfile(
                settingsCharacteristicUuid = INATECK_SETTINGS_ENDPOINT,
                codec = InateckAreaNameSymbologyCodec,
                identity = INATECK_ANDROID_SDK_PROFILE_IDENTITY,
            ),
            InMemorySymbologySnapshotStore(INATECK_ANDROID_SDK_PROFILE_IDENTITY),
            nowMillis = { now },
            commandTimeoutMillis = 25_000,
            settingsReadTimeoutMillis = 6_000,
        )
        assertTrue(transport.connect(device))
        assertTrue(session.onConnected())
        sdk.readCallback!!(Result.success(listOf(
            mapOf("area" to "test", "name" to "qrcode_on", "value" to "1"),
            mapOf("area" to "test", "name" to "code128_on", "value" to "1"),
        )))
        now = 5_999
        session.tick()
        assertEquals(BleSymbologySessionState.LoadingSettings, session.state)
        now = 6_000
        session.tick()
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertTrue(transport.isLinkActive)
        assertFalse(transport.connect(device))
        // SDK reply really reaches the current transport; the session deadline
        // guard must still refuse to become Ready while disconnect is pending.
        assertTrue(gateway.releaseCompletedRead())
        assertEquals(BleSymbologySessionState.AwaitingTransportReset, session.state)
        assertFalse(session.configurationState.isReady)
        assertFalse(session.onConnected())
        sdk.disconnectCallback!!(Result.success(Unit))
        assertFalse(transport.isLinkActive)
        assertEquals(0, sdk.writes)
    }

    @Test fun holdsCopiesAndReleasesOneSdkResultWithoutStacking() {
        val sdk = StubSdk()
        val gateway = InateckReadOnlyFaultGateway(sdk)
        var observed: Result<List<Map<String, String>>>? = null
        assertTrue(gateway.readSettings("synthetic") { observed = it })
        assertFalse(gateway.readSettings("synthetic") {})
        assertFalse(gateway.releaseCompletedRead())
        val item = mutableMapOf("name" to "qrcode_on", "area" to "test", "value" to "1")
        sdk.readCallback!!(Result.success(listOf(item)))
        item["value"] = "0"
        assertNull(observed)
        assertTrue(gateway.hasCompletedRead)
        assertTrue(gateway.releaseCompletedRead())
        assertEquals("1", observed!!.getOrThrow().single()["value"])
        assertFalse(gateway.releaseCompletedRead())
    }

    @Test fun settingAndIlluminationWritesNeverReachSdk() {
        val sdk = StubSdk()
        val gateway = InateckReadOnlyFaultGateway(sdk)
        var failed = false
        assertTrue(gateway.writeSettings("synthetic", "private command") { failed = it.isFailure })
        assertTrue(failed)
        assertFalse(gateway.setIllumination("synthetic", true) {})
        assertEquals(0, sdk.writes)
    }

    @Test fun closedGatewayDropsPendingAndLateResults() {
        val sdk = StubSdk()
        val gateway = InateckReadOnlyFaultGateway(sdk)
        var callbacks = 0
        assertTrue(gateway.readSettings("synthetic") { callbacks++ })
        gateway.close()
        sdk.readCallback!!(Result.success(emptyList()))
        assertFalse(gateway.releaseCompletedRead())
        assertFalse(gateway.readSettings("synthetic") {})
        assertFalse(gateway.writeSettings("synthetic", "") {})
        assertEquals(0, callbacks)
    }

    @Test fun sdkFailureIsSanitizedAndRejectedReadDoesNotLeavePendingSlot() {
        val sdk = StubSdk()
        val gateway = InateckReadOnlyFaultGateway(sdk)
        sdk.acceptRead = false
        assertFalse(gateway.readSettings("synthetic") {})
        sdk.acceptRead = true
        var message: String? = null
        assertTrue(gateway.readSettings("synthetic") { message = it.exceptionOrNull()?.message })
        sdk.readCallback!!(Result.failure(IllegalStateException("private vendor reply")))
        assertTrue(gateway.releaseCompletedRead())
        assertEquals("SDK read failed", message)
    }

    @Test fun realTransportRejectsReleasedResultAfterPhysicalDisconnect() {
        val sdk = StubSdk()
        val gateway = InateckReadOnlyFaultGateway(sdk)
        val transport = InateckSdkTransport(gateway)
        val device = ScannerDevice("synthetic", "test")
        assertTrue(transport.connect(device))
        var callbacks = 0
        assertTrue(transport.read(INATECK_SETTINGS_ENDPOINT) { callbacks++ })
        sdk.readCallback!!(Result.success(listOf(mapOf("area" to "test", "name" to "qrcode_on", "value" to "1"))))
        assertTrue(transport.disconnect(device))
        assertTrue(transport.isLinkActive)
        assertFalse(transport.connect(device))
        sdk.disconnectCallback!!(Result.success(Unit))
        assertFalse(transport.isLinkActive)
        assertTrue(gateway.releaseCompletedRead())
        assertEquals(0, callbacks)
        assertTrue(transport.connect(device))
    }

    private class StubSdk : InateckSdkGateway {
        override val readiness = BleTransportReadiness()
        var acceptRead = true
        var writes = 0
        var readCallback: ((Result<List<Map<String, String>>>) -> Unit)? = null
        var disconnectCallback: ((Result<Unit>) -> Unit)? = null
        override fun startDiscovery(onDevice: (InateckSdkDevice) -> Unit, onFinished: () -> Unit) = true
        override fun stopDiscovery() = true
        override fun connect(deviceId: String, onScanBytes: (ByteArray) -> Unit, onDisconnected: (Boolean) -> Unit, completion: (Result<Unit>) -> Unit): Boolean {
            completion(Result.success(Unit)); return true
        }
        override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean {
            disconnectCallback = completion; return true
        }
        override fun readSettings(deviceId: String, completion: (Result<List<Map<String, String>>>) -> Unit): Boolean {
            readCallback = completion; return acceptRead
        }
        override fun writeSettings(deviceId: String, commandJson: String, completion: (Result<Unit>) -> Unit): Boolean { writes++; return true }
        override fun setIllumination(deviceId: String, enabled: Boolean, completion: (Result<Unit>) -> Unit): Boolean { writes++; return true }
        override fun close() = Unit
    }
}
