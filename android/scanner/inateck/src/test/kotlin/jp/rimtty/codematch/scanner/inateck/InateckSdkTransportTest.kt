package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.BleAdapterLifecycleState
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BleConnectionCoordinator
import jp.rimtty.codematch.scanner.ble.BleConnectionState
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
    fun disconnectFailureKeepsPhysicalLinkActiveAndEmitsTypedFailureEvent() {
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
        val failure = events.filterIsInstance<BleTransportEvent.DisconnectFailed>().single()
        assertEquals(device, failure.device)
        assertTrue(failure.requestGeneration != null)
        assertTrue(failure.linkGeneration != null)

        // The failed completion must leave the gateway retryable. A later
        // successful close is the only event that clears the link.
        assertTrue(transport.disconnect(device))
        gateway.disconnectCompletion?.invoke(Result.success(Unit))
        assertFalse(transport.isLinkActive)
        assertEquals(1, events.filterIsInstance<BleTransportEvent.Disconnected>().size)
    }

    @Test
    fun coordinatorAcceptsReconnectAfterAcknowledgedManualDisconnect() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val coordinator = BleConnectionCoordinator(transport)
        val device = ScannerDevice("id-1", "scanner")
        assertTrue(coordinator.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)

        assertTrue(coordinator.disconnect())
        gateway.disconnectCompletion?.invoke(Result.success(Unit))
        assertFalse(coordinator.hasPhysicalLink)
        assertTrue(coordinator.reconnectKnownDevice())
        gateway.connectCompletion?.invoke(Result.success(Unit))

        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertTrue(coordinator.hasPhysicalLink)
    }

    @Test
    fun pendingUnexpectedDisconnectRetiresCallbacksAndAllowsAnotherAttempt() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val device = ScannerDevice("id-1", "scanner")
        assertTrue(transport.connect(device))
        val lateCompletion = gateway.connectCompletion
        val oldDisconnect = gateway.disconnectCallback

        oldDisconnect?.invoke(true)
        assertFalse(transport.isLinkActive)
        lateCompletion?.invoke(Result.success(Unit))
        assertTrue(events.none { it is BleTransportEvent.Connected })
        assertTrue(transport.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        oldDisconnect?.invoke(true)

        assertTrue(transport.isLinkActive)
        assertEquals(1, events.filterIsInstance<BleTransportEvent.Connected>().size)
        assertEquals(1, events.filterIsInstance<BleTransportEvent.Disconnected>().size)
    }

    @Test
    fun duplicateConnectCompletionCannotDemoteEstablishedLink() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        assertTrue(transport.connect(ScannerDevice("id-1", "scanner")))

        gateway.connectCompletion?.invoke(Result.success(Unit))
        gateway.connectCompletion?.invoke(Result.failure(IllegalStateException("late")))
        gateway.connectCompletion?.invoke(Result.success(Unit))

        assertTrue(transport.isLinkActive)
        assertEquals(1, events.filterIsInstance<BleTransportEvent.Connected>().size)
        assertTrue(events.none { it is BleTransportEvent.ConnectionFailed })
    }

    @Test
    fun coordinatorAcceptsConnectionAfterSynchronousGatewayRejection() {
        val gateway = FakeGateway().apply { connectAccepted = false }
        val transport = InateckSdkTransport(gateway)
        val coordinator = BleConnectionCoordinator(transport)
        val device = ScannerDevice("id-1", "scanner")
        assertFalse(coordinator.connect(device))
        val staleCompletion = gateway.connectCompletion
        assertFalse(transport.isLinkActive)

        gateway.connectAccepted = true
        assertTrue(coordinator.connect(device))
        staleCompletion?.invoke(Result.success(Unit))
        assertEquals(BleConnectionState.Connecting(device), coordinator.connectionState)
        gateway.connectCompletion?.invoke(Result.success(Unit))

        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
    }

    @Test
    fun explicitCoordinatorTokensAreEchoedIndependentlyOfCallbackEpoch() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val device = ScannerDevice("id-1", "scanner")
        assertTrue(transport.connect(device, requestGeneration = 41L, linkGeneration = 73L))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        gateway.scanBytes?.invoke("TEST-ONLY".encodeToByteArray())
        assertTrue(transport.disconnect(device))
        gateway.disconnectCompletion?.invoke(Result.success(Unit))

        val connected = events.filterIsInstance<BleTransportEvent.Connected>().single()
        val scanned = events.filterIsInstance<BleTransportEvent.ScanReceived>().single()
        val disconnected = events.filterIsInstance<BleTransportEvent.Disconnected>().single()
        assertEquals(41L, connected.requestGeneration)
        assertEquals(73L, connected.linkGeneration)
        assertEquals(41L, scanned.requestGeneration)
        assertEquals(73L, scanned.linkGeneration)
        assertEquals(41L, disconnected.requestGeneration)
        assertEquals(73L, disconnected.linkGeneration)
    }

    @Test
    fun gatewayConnectExceptionRetiresPendingIdentityAndLateCompletion() {
        val gateway = FakeGateway().apply { throwOnConnect = true }
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val device = ScannerDevice("id-1", "scanner")
        assertFalse(transport.connect(device))
        val staleCompletion = gateway.connectCompletion
        assertFalse(transport.isLinkActive)

        gateway.throwOnConnect = false
        assertTrue(transport.connect(device))
        staleCompletion?.invoke(Result.success(Unit))
        assertTrue(events.none { it is BleTransportEvent.Connected })
        gateway.connectCompletion?.invoke(Result.success(Unit))
        assertEquals(1, events.filterIsInstance<BleTransportEvent.Connected>().size)
    }

    @Test
    fun readinessLossDemotesLiveCoordinatorButRetainsLinkAndBlocksLateSettingsAndScans() {
        var now = 0L
        val gateway = FakeGateway()
        val deliveries = mutableListOf<InateckScanDeliveryKind>()
        val transport = InateckSdkTransport(
            gateway = gateway,
            nowMillis = { now },
            scanDeliveryObserver = deliveries::add,
        )
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")

        assertTrue(coordinator.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)

        var readResult: Result<ByteArray>? = null
        assertTrue(transport.read(INATECK_SETTINGS_ENDPOINT) { readResult = it })
        gateway.readinessSnapshot = readiness(availability = BleAvailability.PoweredOff)
        transport.refreshReadiness()

        assertEquals(
            BleConnectionState.Unavailable("Bluetooth is off"),
            coordinator.connectionState,
        )
        assertTrue(coordinator.hasPhysicalLink)
        assertEquals(device, coordinator.knownDevice)

        // The callback belongs to the old physical link, but its successful
        // settings result must not reopen Ready after readiness loss.
        gateway.readCompletion?.invoke(Result.success(settings()))
        assertTrue(readResult?.isFailure == true)
        assertTrue(readResult?.exceptionOrNull()?.message == "Inateck scanner readiness unavailable")

        gateway.scanBytes?.invoke("late-scan".encodeToByteArray())
        assertTrue(events.none { it is BleTransportEvent.ScanReceived })
        assertEquals(listOf(InateckScanDeliveryKind.STALE), deliveries)

        // Readiness recovery is only a status event. It neither closes the
        // link nor clears the identity, and it cannot make the old callback
        // epoch usable again.
        gateway.readinessSnapshot = readiness()
        transport.refreshReadiness()
        assertTrue(coordinator.hasPhysicalLink)
        assertTrue(events.count { it is BleTransportEvent.AvailabilityChanged } == 2)
        gateway.scanBytes?.invoke("still-late".encodeToByteArray())
        assertEquals(2, deliveries.size)
        assertTrue(events.none { it is BleTransportEvent.ScanReceived })

        // Only a matching physical close retires the old identity.
        gateway.disconnectCallback?.invoke(false)
        assertFalse(coordinator.hasPhysicalLink)
        assertEquals(1, events.count { it is BleTransportEvent.Disconnected })
    }

    @Test
    fun lateWriteCompletionAfterReadinessRecoveryCannotPublishReady() {
        var now = 0L
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway, nowMillis = { now })
        val events = mutableListOf<BleTransportEvent>()
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")
        val command = "[{\"area\":\"barcode\",\"name\":\"qrcode_on\",\"value\":\"1\"}]"

        assertTrue(coordinator.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        var writeResult: Result<Unit>? = null
        assertTrue(
            transport.write(INATECK_SETTINGS_ENDPOINT, command.encodeToByteArray()) {
                writeResult = it
            },
        )
        val oldWriteCompletion = gateway.writeCompletion

        gateway.readinessSnapshot = readiness(availability = BleAvailability.PoweredOff)
        transport.refreshReadiness()
        gateway.readinessSnapshot = readiness()
        transport.refreshReadiness()
        oldWriteCompletion?.invoke(Result.success(Unit))

        assertTrue(writeResult?.isFailure == true)
        assertEquals(
            "Inateck scanner readiness unavailable",
            writeResult?.exceptionOrNull()?.message,
        )
        assertEquals(
            BleConnectionState.Unavailable("Bluetooth is off"),
            coordinator.connectionState,
        )
        assertTrue(coordinator.hasPhysicalLink)

        // A fresh physical link clears the readiness latch; its write callback
        // is accepted only after the new connection has been acknowledged.
        gateway.disconnectCallback?.invoke(false)
        assertFalse(coordinator.hasPhysicalLink)
        assertTrue(coordinator.reconnectKnownDevice())
        gateway.connectCompletion?.invoke(Result.success(Unit))
        var freshWriteResult: Result<Unit>? = null
        assertTrue(
            transport.write(INATECK_SETTINGS_ENDPOINT, command.encodeToByteArray()) {
                freshWriteResult = it
            },
        )
        gateway.writeCompletion?.invoke(Result.success(Unit))
        assertTrue(freshWriteResult?.isSuccess == true)
    }

    @Test
    fun readinessLossDuringPendingConnectRejectsLateSuccessUntilExplicitCloseAndReconnect() {
        var now = 0L
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway, nowMillis = { now })
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")

        assertTrue(coordinator.connect(device))
        val lateCompletion = gateway.connectCompletion
        gateway.readinessSnapshot = readiness(connectionPermission = BlePermissionState.DENIED)
        transport.refreshReadiness()

        assertEquals(
            BleConnectionState.Unavailable("Bluetooth permission is required"),
            coordinator.connectionState,
        )
        assertTrue(coordinator.hasPhysicalLink)
        assertEquals(device, coordinator.knownDevice)

        // Even if the SDK reports success after a CONNECT denial, this old
        // callback cannot become an active/Ready link.
        gateway.readinessSnapshot = readiness()
        transport.refreshReadiness()
        lateCompletion?.invoke(Result.success(Unit))
        assertTrue(coordinator.hasPhysicalLink)
        assertTrue(events.none { it is BleTransportEvent.Connected })

        // A user close is explicit recovery authority; the subsequent
        // attempt is a fresh link and must perform its own connect callback.
        assertTrue(coordinator.disconnect())
        gateway.disconnectCompletion?.invoke(Result.success(Unit))
        assertFalse(coordinator.hasPhysicalLink)
        assertTrue(coordinator.reconnectKnownDevice())
        gateway.connectCompletion?.invoke(Result.success(Unit))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        assertEquals(1, events.count { it is BleTransportEvent.Connected })
    }

    @Test
    fun scanPermissionLossStopsDiscoveryWithoutDemotingConnectGrantedLink() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val coordinator = BleConnectionCoordinator(transport)
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")

        assertTrue(coordinator.startDiscovery())
        gateway.readinessSnapshot = readiness(discoveryPermission = BlePermissionState.DENIED)
        transport.refreshReadiness()
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
        assertEquals(1, events.count { it is BleTransportEvent.DiscoveryStopped })
        assertTrue(events.none { it is BleTransportEvent.AvailabilityChanged })

        // SCAN is not required by an already-connected link, and it is not a
        // prerequisite for a CONNECT-granted connection attempt.
        assertTrue(coordinator.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
        gateway.scanBytes?.invoke("scan-without-scan-permission".encodeToByteArray())
        assertEquals(1, events.count { it is BleTransportEvent.ScanReceived })
    }

    @Test
    fun repeatedReadinessLossIsDeduplicatedAndReadyDoesNotAutoConnect() {
        var now = 0L
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway, nowMillis = { now })
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val coordinator = BleConnectionCoordinator(
            transport = transport,
            nowMillis = { now },
            reconnectDelayMillis = { 100L },
        )
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")

        assertTrue(coordinator.connect(device))
        gateway.readinessSnapshot = readiness(availability = BleAvailability.PoweredOff)
        transport.refreshReadiness()
        transport.refreshReadiness()
        transport.refreshReadiness()
        assertEquals(1, events.count { it is BleTransportEvent.AvailabilityChanged })
        assertEquals(1, coordinator.reconnectAttemptCount)

        gateway.readinessSnapshot = readiness()
        transport.refreshReadiness()
        transport.refreshReadiness()
        assertEquals(2, events.count { it is BleTransportEvent.AvailabilityChanged })
        assertEquals(0, events.count { it is BleTransportEvent.Connected })
        assertEquals(1, coordinator.reconnectAttemptCount)
        assertTrue(coordinator.hasPhysicalLink)
    }

    @Test
    fun readinessReadFailureUsesSanitizedFailClosedStateAndNoDynamicError() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = listener(events)
        val coordinator = BleConnectionCoordinator(transport)
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")

        assertTrue(coordinator.connect(device))
        gateway.throwOnReadiness = true
        transport.refreshReadiness()

        assertEquals(
            BleAvailability.Failed("Bluetooth readiness unavailable"),
            transport.readiness.availability,
        )
        val availability = events.filterIsInstance<BleTransportEvent.AvailabilityChanged>().single()
        assertEquals(BleAvailability.Failed("Bluetooth readiness unavailable"), availability.availability)
        assertTrue(coordinator.hasPhysicalLink)
        assertTrue(events.none { it.toString().contains("sdk-private") })
    }

    @Test
    fun gatewayConnectRejectionAfterReadinessLossDoesNotLeaveStaleCloseIntent() {
        val gateway = FakeGateway().apply {
            connectAccepted = false
            readinessOnConnect = readiness(availability = BleAvailability.PoweredOff)
        }
        val transport = InateckSdkTransport(gateway)
        val coordinator = BleConnectionCoordinator(transport)
        transport.listener = forwardingListener(coordinator, mutableListOf())
        val device = ScannerDevice("id-1", "scanner")

        assertFalse(coordinator.connect(device))
        assertFalse(coordinator.hasPhysicalLink)

        // The failed start was not a physical link, so recovery after the
        // readiness outage must be able to begin a fresh user connection.
        gateway.readinessSnapshot = readiness()
        gateway.connectAccepted = true
        gateway.readinessOnConnect = null
        assertTrue(coordinator.connect(device))
        gateway.connectCompletion?.invoke(Result.success(Unit))
        assertEquals(BleConnectionState.Connected(device), coordinator.connectionState)
    }

    @Test
    fun initialUnknownToReadyRefreshDoesNotStartAConnection() {
        val gateway = FakeGateway().apply {
            readinessSnapshot = readiness(availability = BleAvailability.Unknown)
        }
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        val coordinator = BleConnectionCoordinator(transport)
        transport.listener = forwardingListener(coordinator, events)
        val device = ScannerDevice("id-1", "scanner")

        transport.refreshReadiness()
        assertFalse(coordinator.connect(device))
        assertEquals(0, gateway.connectCalls)
        gateway.readinessSnapshot = readiness()
        transport.refreshReadiness()
        transport.refreshReadiness()

        assertEquals(0, gateway.connectCalls)
        assertEquals(1, events.count { it is BleTransportEvent.AvailabilityChanged })
        assertEquals(BleConnectionState.Idle, coordinator.connectionState)
    }

    @Test
    fun readinessRefreshCannotStopAReentrantNewDiscovery() {
        val gateway = FakeGateway()
        val transport = InateckSdkTransport(gateway)
        val events = mutableListOf<BleTransportEvent>()
        var reentered = false
        transport.listener = object : BleTransportListener {
            override fun onTransportEvent(event: BleTransportEvent) {
                events += event
                if (event is BleTransportEvent.AvailabilityChanged && !reentered) {
                    reentered = true
                    // Replace the old operation from inside the readiness
                    // callback. The outer refresh must not stop this new one.
                    transport.stopDiscovery()
                    gateway.readinessSnapshot = readiness()
                    assertTrue(transport.startDiscovery())
                }
            }
        }

        assertTrue(transport.startDiscovery())
        gateway.readinessSnapshot = readiness(
            availability = BleAvailability.PoweredOff,
            discoveryPermission = BlePermissionState.DENIED,
        )
        transport.refreshReadiness()

        assertEquals(1, gateway.stopDiscoveryCalls)
        assertEquals(2, events.count { it is BleTransportEvent.DiscoveryStarted })
        assertEquals(1, events.count { it is BleTransportEvent.DiscoveryStopped })
        assertFalse(transport.startDiscovery())
    }

    private fun listener(events: MutableList<BleTransportEvent>) = object : BleTransportListener {
        override fun onTransportEvent(event: BleTransportEvent) {
            events += event
        }
    }

    private fun forwardingListener(
        coordinator: BleConnectionCoordinator,
        events: MutableList<BleTransportEvent>,
    ) = object : BleTransportListener {
        override fun onTransportEvent(event: BleTransportEvent) {
            events += event
            coordinator.onTransportEvent(event)
        }
    }

    private fun settings(): List<Map<String, String>> = listOf(
        mapOf("area" to "barcode", "name" to "qrcode_on", "value" to "1"),
        mapOf("area" to "barcode", "name" to "code128_on", "value" to "1"),
    )

    private class FakeGateway : InateckSdkGateway {
        var readinessSnapshot = readiness()
        var throwOnReadiness = false
        override val readiness: BleTransportReadiness
            get() {
                if (throwOnReadiness) error("sdk-private readiness detail")
                return readinessSnapshot
            }
        var discoveryDevice: ((InateckSdkDevice) -> Unit)? = null
        var discoveryFinished: (() -> Unit)? = null
        var stopDiscoveryCalls = 0
        var scanBytes: ((ByteArray) -> Unit)? = null
        var disconnectCallback: ((Boolean) -> Unit)? = null
        var connectCompletion: ((Result<Unit>) -> Unit)? = null
        var disconnectCompletion: ((Result<Unit>) -> Unit)? = null
        var readCompletion: ((Result<List<Map<String, String>>>) -> Unit)? = null
        var writeCompletion: ((Result<Unit>) -> Unit)? = null
        var readCalls = 0
        var connectCalls = 0
        var connectAccepted = true
        var throwOnConnect = false
        var readinessOnConnect: BleTransportReadiness? = null

        override fun startDiscovery(
            onDevice: (InateckSdkDevice) -> Unit,
            onFinished: () -> Unit,
        ): Boolean {
            discoveryDevice = onDevice
            discoveryFinished = onFinished
            return true
        }

        override fun stopDiscovery(): Boolean {
            stopDiscoveryCalls++
            return true
        }

        override fun connect(
            deviceId: String,
            onScanBytes: (ByteArray) -> Unit,
            onDisconnected: (unexpected: Boolean) -> Unit,
            completion: (Result<Unit>) -> Unit,
        ): Boolean {
            connectCalls++
            scanBytes = onScanBytes
            disconnectCallback = onDisconnected
            connectCompletion = completion
            readinessOnConnect?.let { readinessSnapshot = it }
            if (throwOnConnect) throw IllegalStateException("test connection failure")
            return connectAccepted
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

        private fun readiness(
            lifecycle: BleAdapterLifecycleState = BleAdapterLifecycleState.FOREGROUND,
            availability: BleAvailability = BleAvailability.Ready,
            discoveryPermission: BlePermissionState = BlePermissionState.GRANTED,
            connectionPermission: BlePermissionState = BlePermissionState.GRANTED,
        ): BleTransportReadiness = BleTransportReadiness(
            lifecycle = lifecycle,
            availability = availability,
            discoveryPermission = discoveryPermission,
            connectionPermission = connectionPermission,
        )
    }

    private fun readiness(
        lifecycle: BleAdapterLifecycleState = BleAdapterLifecycleState.FOREGROUND,
        availability: BleAvailability = BleAvailability.Ready,
        discoveryPermission: BlePermissionState = BlePermissionState.GRANTED,
        connectionPermission: BlePermissionState = BlePermissionState.GRANTED,
    ): BleTransportReadiness = BleTransportReadiness(
        lifecycle = lifecycle,
        availability = availability,
        discoveryPermission = discoveryPermission,
        connectionPermission = connectionPermission,
    )
}
