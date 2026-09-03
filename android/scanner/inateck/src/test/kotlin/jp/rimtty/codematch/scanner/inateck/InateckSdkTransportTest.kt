package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.BleAdapterLifecycleState
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BlePermissionState
import jp.rimtty.codematch.scanner.ble.BleTransportEvent
import jp.rimtty.codematch.scanner.ble.BleTransportListener
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckSdkTransportTest {
    @Test
    fun discoveryConnectSettingsAndScanMapThroughNarrowGateway() {
        val gateway = FakeGateway()
        val deliveries = mutableListOf<InateckScanDeliveryKind>()
        val transport = InateckSdkTransport(
            gateway,
            nowMillis = { 123L },
            scanDeliveryObserver = deliveries::add,
        )
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)

        assertTrue(transport.startDiscovery())
        gateway.discoveryDevice?.invoke(InateckSdkDevice("id-1", "scanner"))
        gateway.discoveryFinished?.invoke()
        val device = ScannerDevice("id-1", "scanner")
        assertTrue(transport.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))

        var readResult: Result<ByteArray>? = null
        assertTrue(transport.read(INATECK_SETTINGS_ENDPOINT) { readResult = it })
        gateway.readCompletion?.invoke(Result.success(settings()))
        assertTrue(readResult?.getOrThrow()?.decodeToString()?.contains("qrcode_on") == true)

        var writeResult: Result<Unit>? = null
        assertTrue(
            transport.write(
                INATECK_SETTINGS_ENDPOINT,
                "[{\"area\":\"barcode\",\"name\":\"qrcode_on\",\"value\":\"1\"}]".encodeToByteArray(),
            ) { writeResult = it },
        )
        gateway.writeCompletion?.invoke(Result.success(Unit))
        assertTrue(writeResult?.isSuccess == true)

        gateway.scanBytes?.invoke("visible-only-to-subscriber\r".encodeToByteArray())
        val scan = events.filterIsInstance<BleTransportEvent.ScanReceived>().single()
        assertEquals("visible-only-to-subscriber", scan.payload.value)
        assertEquals(ScanFormat.QR, scan.payload.format)
        assertEquals(123L, scan.payload.timestampMillis)
        assertEquals(listOf(InateckScanDeliveryKind.DELIVERED), deliveries)
    }

    @Test
    fun scanDeliveryObserverDistinguishesInvalidUtf8AndDecoderRejection() {
        val gateway = FakeGateway()
        val deliveries = mutableListOf<InateckScanDeliveryKind>()
        val transport = InateckSdkTransport(
            gateway = gateway,
            scanDeliveryObserver = deliveries::add,
        )
        transport.listener = listener(mutableListOf())
        val device = ScannerDevice("id-1", "scanner")
        assertTrue(transport.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))

        gateway.scanBytes?.invoke(byteArrayOf(0xc3.toByte(), 0x28))
        gateway.scanBytes?.invoke("{\"status\":0}".encodeToByteArray())

        assertEquals(
            listOf(
                InateckScanDeliveryKind.INVALID_UTF8,
                InateckScanDeliveryKind.DECODER_REJECTED,
            ),
            deliveries,
        )
    }

    @Test
    fun pendingConnectionCanBeCancelledAndLateSuccessIsIgnored() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val device = ScannerDevice("id-1", "scanner")

        assertTrue(transport.connect(device))
        val lateCompletion = gateway.connectCompletion
        assertTrue(transport.disconnect(device))
        gateway.disconnectCompletion?.invoke(Result.success(Unit))
        lateCompletion?.invoke(Result.success(Unit))

        assertEquals(1, events.filterIsInstance<BleTransportEvent.Disconnected>().size)
        assertTrue(events.none { it is BleTransportEvent.Connected })
    }

    @Test
    fun wrongLogicalEndpointIsRejectedWithoutCallingSdk() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val device = ScannerDevice("id-1", "scanner")
        transport.connect(device)
        gateway.connectCompletion?.invoke(Result.success(Unit))

        var failure: Result<ByteArray>? = null
        assertFalse(transport.read("wrong") { failure = it })

        assertTrue(failure?.isFailure == true)
        assertEquals(0, gateway.readCalls)
    }

    @Test
    fun staleDiscoveryFinishCannotStopANewerDiscovery() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)

        assertTrue(transport.startDiscovery())
        val staleFinish = gateway.discoveryFinished
        assertTrue(transport.stopDiscovery())
        assertTrue(transport.startDiscovery())
        staleFinish?.invoke()

        assertEquals(2, events.filterIsInstance<BleTransportEvent.DiscoveryStarted>().size)
        assertEquals(1, events.filterIsInstance<BleTransportEvent.DiscoveryStopped>().size)
        gateway.discoveryFinished?.invoke()
        assertEquals(2, events.filterIsInstance<BleTransportEvent.DiscoveryStopped>().size)
    }

    @Test
    fun disconnectFailureKeepsPhysicalLinkActiveAndEmitsNoDisconnectedEvent() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val device = ScannerDevice("id-1", "scanner")
        assertTrue(transport.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))

        assertTrue(transport.disconnect(device))
        gateway.disconnectCompletion?.invoke(Result.failure(IllegalStateException("still linked")))

        assertTrue(transport.isLinkActive)
        assertTrue(events.none { it is BleTransportEvent.Disconnected })
    }

    private fun listener(events: MutableList<BleTransportEvent>) = object : BleTransportListener {
        override fun onTransportEvent(event: BleTransportEvent) {
            events += event
        }
    }

    private fun settings(): List<Map<String, String>> = listOf(
        mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "1"),
        mapOf("area" to "barcode", "name" to "code128_on", "value" to "1"),
    )

    private class FakeGateway : InateckSdkGateway {
        override val readiness = BleTransportReadiness(
            lifecycle = BleAdapterLifecycleState.FOREGROUND,
            availability = BleAvailability.Ready,
            discoveryPermission = BlePermissionState.GRANTED,
            connectionPermission = BlePermissionState.GRANTED,
        )
        var discoveryDevice: ((InateckSdkDevice) -> Unit)? = null
        var discoveryFinished: (() -> Unit)? = null
        var scanBytes: ((ByteArray) -> Unit)? = null
        var disconnectCallback: ((Boolean) -> Unit)? = null
        var connectCompletion: ((Result<Unit>) -> Unit)? = null
        var disconnectCompletion: ((Result<Unit>) -> Unit)? = null
        var readCompletion: ((Result<List<Map<String, String>>>) -> Unit)? = null
        var writeCompletion: ((Result<Unit>) -> Unit)? = null
        var readCalls = 0

        override fun startDiscovery(
            onDevice: (InateckSdkDevice) -> Unit,
            onFinished: () -> Unit,
        ): Boolean {
            discoveryDevice = onDevice
            discoveryFinished = onFinished
            return true
        }

        override fun stopDiscovery(): Boolean = true

        override fun connect(
            deviceId: String,
            onScanBytes: (ByteArray) -> Unit,
            onDisconnected: (unexpected: Boolean) -> Unit,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            scanBytes = onScanBytes
            disconnectCallback = onDisconnected
            connectCompletion = completion
            return true
        }

        override fun disconnect(
            deviceId: String,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            disconnectCompletion = completion
            return true
        }

        override fun readSettings(
            deviceId: String,
            completion: (Result<List<Map<String, String>>>) -> Unit,
        ): Boolean {
            readCalls++
            readCompletion = completion
            return true
        }

        override fun writeSettings(
            deviceId: String,
            commandJson: String,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            writeCompletion = completion
            return true
        }

        override fun close() = Unit
    }
}
