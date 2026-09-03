package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Deterministic discovery/connection/reconnection coordinator.
 *
 * The platform adapter owns Android Bluetooth callbacks. This class owns the
 * app-visible state and persistence-independent reconnect policy. Call
 * [onTransportEvent] from the adapter's serialized callback context and call
 * [tick] from the host coroutine/timer to perform deadline handling and a
 * scheduled reconnect. Discovery, connection, and reconnect deadlines are
 * respectively 5s, 30s, and 8s by default.
 *
 * Adapters that can tag callbacks should copy [pendingRequestGeneration] and
 * [pendingLinkGeneration] into connection events, and the accepted current
 * tokens into scan/disconnect events. Untagged legacy events are accepted only
 * when their device and current state make them unambiguous.
 */
class BleConnectionCoordinator(
    private val transport: BleTransport,
    private val diagnostics: BleDiagnosticLog = BleDiagnosticLog(),
    private val reconnectDelayMillis: (attempt: Int) -> Long = ::defaultReconnectDelayMillis,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    /** Optional app-private store for a manually retained known scanner. */
    private val knownDeviceStore: KnownDeviceStore? = null,
) : BleTransportListener {
    private enum class DisconnectIntent {
        MANUAL,
        RECONNECT,
    }

    init {
        require(maxReconnectAttempts > 0) { "maxReconnectAttempts must be positive" }
        require(discoveryTimeoutMillis > 0) { "discoveryTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        transport.listener = this
    }

    private val mutableDevices = linkedMapOf<String, BleDiscoveredDevice>()
    private var applicationActive = true
    private var preferredDevice: ScannerDevice? = null
    private var reconnectAttempt = 0
    private var reconnectAtMillis: Long? = null
    private var manualDisconnect = false
    private var disconnectIntent: DisconnectIntent? = null
    private var discoveryStartedAtMillis: Long? = null
    private var connectStartedAtMillis: Long? = null
    private var pendingConnectDevice: ScannerDevice? = null
    private var pendingConnectRequestGeneration: Long? = null
    private var pendingPhysicalLinkGeneration: Long? = null
    private var activeDevice: ScannerDevice? = null
    private var activeRequestGeneration: Long? = null
    private var activeLinkGeneration: Long? = null
    private var nextRequestGeneration = 0L
    private var nextLinkGeneration = 0L
    private var mutableExpectedFormat: ScanFormat? = null
    private var mutableState: BleScannerState = BleScannerState()
    private var listener: BleScannerListener? = null
    private val payloadGate = BleScanPayloadGate(nowMillis = nowMillis)
    private var knownDeviceReadResult: BleKnownDeviceReadResult =
        BleKnownDeviceReadResult.Missing

    init {
        preferredDevice = readKnownDevice()
    }

    val state: BleScannerState get() = mutableState
    val connectionState: BleConnectionState get() = mutableState.connection
    val configurationState: ConfigurationState get() = mutableState.configuration
    val devices: List<ScannerDevice> get() = mutableDevices.values.map(BleDiscoveredDevice::device)
    val expectedFormat: ScanFormat? get() = mutableExpectedFormat
    val pendingReconnectAtMillis: Long? get() = reconnectAtMillis
    val reconnectAttemptCount: Int get() = reconnectAttempt
    val knownDevice: ScannerDevice? get() = preferredDevice
    /** Result of the last identity-aware read from [knownDeviceStore]. */
    val persistedKnownDevice: BleKnownDeviceReadResult get() = knownDeviceReadResult
    /** Token the adapter may attach to the next connection callback. */
    val pendingRequestGeneration: Long? get() = pendingConnectRequestGeneration
    /** Token the adapter may attach to the next physical-link callback. */
    val pendingLinkGeneration: Long? get() = pendingPhysicalLinkGeneration
    /** Last accepted request token, retained to reject stale callbacks. */
    val currentRequestGeneration: Long? get() = activeRequestGeneration
    /** Last accepted physical-link token, retained to reject stale callbacks. */
    val currentLinkGeneration: Long? get() = activeLinkGeneration

    fun setListener(listener: BleScannerListener?) {
        this.listener = listener
    }

    fun startDiscovery(): Boolean {
        if (connectionState == BleConnectionState.Searching) return false
        val readiness = readTransportReadiness()
        val failure = readiness.failureReason(forConnection = false)
        if (failure != null) {
            transition(connection = readiness.asConnectionState(forConnection = false))
            diagnostics.error(failure)
            return false
        }
        transition(connection = BleConnectionState.Searching)
        discoveryStartedAtMillis = nowMillis()
        diagnostics.connection("Discovery requested")
        val accepted = try {
            transport.startDiscovery()
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            discoveryStartedAtMillis = null
            transition(connection = BleConnectionState.Failed("Bluetooth discovery could not start"))
            diagnostics.error("Discovery start failed")
        }
        return accepted
    }

    fun stopDiscovery(): Boolean {
        if (connectionState != BleConnectionState.Searching) return false
        discoveryStartedAtMillis = null
        val accepted = try {
            transport.stopDiscovery()
        } catch (_: Exception) {
            false
        }
        transition(connection = BleConnectionState.Idle)
        diagnostics.connection("Discovery stopped")
        return accepted
    }

    fun connect(device: ScannerDevice): Boolean {
        if (activeDevice != null || pendingConnectDevice != null || disconnectIntent != null) {
            return false
        }
        if (!rememberKnownDevice(device)) {
            transition(
                connection = BleConnectionState.Failed(
                    "Known scanner identity could not be saved",
                ),
                configuration = ConfigurationState.Unavailable,
            )
            return false
        }
        reconnectAtMillis = null
        reconnectAttempt = 0
        manualDisconnect = false
        payloadGate.reset()
        preferredDevice = device
        mutableDevices[device.id] = BleDiscoveredDevice(device)
        return startConnectionAttempt(device, reconnecting = false)
    }

    fun disconnect(): Boolean {
        val device = activeDevice ?: pendingConnectDevice ?: return false
        if (disconnectIntent != null) return true
        manualDisconnect = true
        disconnectIntent = DisconnectIntent.MANUAL
        reconnectAtMillis = null
        reconnectAttempt = 0
        discoveryStartedAtMillis = null
        // Keep the active request/link tokens until the adapter's disconnect
        // callback arrives; that callback is still relevant to this link.
        connectStartedAtMillis = null
        val accepted = try {
            transport.disconnect(device)
        } catch (_: Exception) {
            false
        }
        if (accepted) {
            // The adapter may emit a final Disconnected callback later. Expose
            // an idle state immediately so UI cannot submit more scan input.
            transition(
                connection = BleConnectionState.Idle,
                configuration = ConfigurationState.Unavailable,
            )
            diagnostics.connection("Manual disconnect requested")
        } else {
            // Retain the physical-link identity. A late successful connection
            // is immediately disconnected below instead of becoming Ready.
            transition(
                connection = BleConnectionState.Failed("Bluetooth disconnect could not start"),
                configuration = ConfigurationState.Unavailable,
            )
            diagnostics.error("Disconnect start failed")
        }
        return accepted
    }

    fun reconnectKnownDevice(): Boolean {
        val device = preferredDevice ?: readKnownDevice() ?: return false
        if (disconnectIntent != null) {
            disconnectIntent = DisconnectIntent.RECONNECT
            manualDisconnect = false
            return true
        }
        if (activeDevice != null || pendingConnectDevice != null) {
            if (activeDevice != null && configurationState.isReady) return true
            disconnectIntent = DisconnectIntent.RECONNECT
            manualDisconnect = false
            reconnectAtMillis = null
            val accepted = try {
                transport.disconnect(activeDevice ?: pendingConnectDevice ?: return false)
            } catch (_: Exception) {
                false
            }
            if (!accepted) {
                transition(
                    connection = BleConnectionState.Failed(
                        "Bluetooth reconnect reset could not start",
                    ),
                    configuration = ConfigurationState.Unavailable,
                )
                diagnostics.error("Reconnect reset start failed")
            }
            return accepted
        }
        reconnectAtMillis = null
        return connect(device)
    }

    /**
     * Re-read the persisted identity after a service/process recreation.
     *
     * This only restores an identity candidate. It never claims that the
     * scanner is connected or ready; callers must still perform the normal
     * connect and fresh-settings/recovery handshake.
     */
    fun loadKnownDevice(): ScannerDevice? {
        val device = readKnownDevice()
        if (device != null) preferredDevice = device
        return device
    }

    fun setApplicationActive(active: Boolean, atMillis: Long = nowMillis()) {
        applicationActive = active
        if (!active) {
            reconnectAtMillis = null
            if (connectionState == BleConnectionState.Searching) stopDiscovery()
            return
        }
        if (connectionState is BleConnectionState.Failed ||
            connectionState is BleConnectionState.Unavailable
        ) {
            scheduleReconnect(atMillis)
        }
    }

    /**
     * Physical scanner mode is intentionally fixed for a session. Changing
     * QR→Code128 is a logical app step and does not call GATT.
     */
    fun setExpectedFormat(format: ScanFormat?) {
        mutableExpectedFormat = format
        val mode = BleSymbologyMode.forExpectedFormat(format)
        diagnostics.configuration(
            if (mode == BleSymbologyMode.UNRESTRICTED) {
                "Scanner session mode cleared"
            } else {
                "Scanner session mode is fixed to QR and Code 128"
            },
        )
        emitState()
    }

    /** Host calls this after it has applied/verified a GATT setting command. */
    fun markConfiguration(state: ConfigurationState) {
        transition(configuration = state)
    }

    /**
     * Feed adapter events. Notification payloads are forwarded only as typed
     * [ScanPayload] values; this method never sends them to diagnostics.
     */
    override fun onTransportEvent(event: BleTransportEvent) {
        when (event) {
            BleTransportEvent.DiscoveryStarted -> {
                if (discoveryStartedAtMillis == null) {
                    discoveryStartedAtMillis = nowMillis()
                }
                transition(connection = BleConnectionState.Searching)
            }
            is BleTransportEvent.DeviceFound -> {
                mutableDevices[event.device.device.id] = event.device
                emitState()
            }
            BleTransportEvent.DiscoveryStopped -> {
                discoveryStartedAtMillis = null
                if (connectionState == BleConnectionState.Searching) {
                    transition(connection = BleConnectionState.Idle)
                }
            }
            is BleTransportEvent.Connected -> {
                if (!acceptConnectedEvent(event)) return
                discoveryStartedAtMillis = null
                connectStartedAtMillis = null
                activeRequestGeneration = event.requestGeneration ?: pendingConnectRequestGeneration
                activeLinkGeneration = event.linkGeneration ?: pendingPhysicalLinkGeneration
                pendingConnectDevice = null
                pendingConnectRequestGeneration = null
                pendingPhysicalLinkGeneration = null
                activeDevice = event.device
                preferredDevice = event.device
                mutableDevices[event.device.id] = BleDiscoveredDevice(event.device)
                if (disconnectIntent != null) {
                    // A cancellation request may race the SDK's final connect
                    // callback. Never publish this late link as Connected or
                    // start a settings read; close it first.
                    val accepted = try {
                        transport.disconnect(event.device)
                    } catch (_: Exception) {
                        false
                    }
                    if (!accepted) {
                        transition(
                            connection = BleConnectionState.Failed(
                                "Bluetooth disconnect could not start",
                            ),
                            configuration = ConfigurationState.Unavailable,
                        )
                        diagnostics.error("Late connection reset failed")
                    }
                    return
                }
                reconnectAttempt = 0
                reconnectAtMillis = null
                payloadGate.reset()
                transition(
                    connection = BleConnectionState.Connected(event.device),
                    configuration = ConfigurationState.Configuring,
                )
                diagnostics.connection("Scanner connected")
            }
            is BleTransportEvent.ConnectionFailed -> {
                if (!acceptConnectionFailureEvent(event)) return
                val shouldReconnect = disconnectIntent == DisconnectIntent.RECONNECT
                val wasManual = disconnectIntent == DisconnectIntent.MANUAL || manualDisconnect
                disconnectIntent = null
                manualDisconnect = false
                connectStartedAtMillis = null
                pendingConnectDevice = null
                pendingConnectRequestGeneration = null
                pendingPhysicalLinkGeneration = null
                preferredDevice = event.device
                transition(connection = BleConnectionState.Failed(event.reason))
                diagnostics.error("Scanner connection failed")
                if (shouldReconnect || !wasManual) scheduleReconnect(nowMillis())
            }
            is BleTransportEvent.Disconnected -> {
                if (!acceptDisconnectedEvent(event)) return
                val shouldReconnect = disconnectIntent == DisconnectIntent.RECONNECT
                val wasManual = disconnectIntent == DisconnectIntent.MANUAL || manualDisconnect
                disconnectIntent = null
                manualDisconnect = false
                connectStartedAtMillis = null
                pendingConnectDevice = null
                pendingConnectRequestGeneration = null
                pendingPhysicalLinkGeneration = null
                activeDevice = null
                payloadGate.reset()
                transition(
                    connection = if (wasManual || !event.unexpected) {
                        BleConnectionState.Idle
                    } else {
                        BleConnectionState.Failed("Bluetooth scanner disconnected")
                    },
                    configuration = ConfigurationState.Unavailable,
                )
                diagnostics.connection(
                    if (wasManual || !event.unexpected) {
                        "Scanner disconnected"
                    } else {
                        "Scanner connection lost"
                    },
                )
                if (shouldReconnect || (!wasManual && event.unexpected)) {
                    scheduleReconnect(nowMillis())
                }
            }
            is BleTransportEvent.ScanReceived -> {
                if (!acceptScanEvent(event)) return
                // Deliberately no diagnostics call here: event.payload.value
                // must remain outside the diagnostic stream.
                val timestamp = event.payload.timestampMillis.takeIf { it != 0L } ?: nowMillis()
                val normalized = payloadGate.accept(event.payload.value, timestamp) ?: return
                listener?.onScanPayload(
                    event.payload.copy(
                        value = normalized,
                        timestampMillis = timestamp,
                    ),
                )
            }
            is BleTransportEvent.AvailabilityChanged -> {
                if (event.availability !is BleAvailability.Ready) {
                    discoveryStartedAtMillis = null
                    disconnectIntent = null
                    manualDisconnect = false
                    clearPendingConnection()
                    transition(
                        connection = event.availability.asConnectionState(),
                        configuration = ConfigurationState.Unavailable,
                    )
                    if (applicationActive) scheduleReconnect(nowMillis())
                }
            }
        }
    }

    /**
     * Advances discovery/connection/reconnect deadlines.
     *
     * A deadline is inclusive: a tick at exactly 5s/30s/8s performs the
     * corresponding action. Returns true when a transport operation was
     * started by this tick.
     */
    fun tick(nowMillis: Long): Boolean {
        val discoveryStartedAt = discoveryStartedAtMillis
        if (connectionState == BleConnectionState.Searching &&
            discoveryStartedAt != null &&
            nowMillis - discoveryStartedAt >= discoveryTimeoutMillis
        ) {
            discoveryStartedAtMillis = null
            val stopped = try {
                transport.stopDiscovery()
            } catch (_: Exception) {
                false
            }
            transition(connection = BleConnectionState.Failed("Bluetooth discovery timed out"))
            diagnostics.error("Discovery timed out")
            return stopped
        }

        val connectStartedAt = connectStartedAtMillis
        val connectingDevice = pendingConnectDevice
        if (connectStartedAt != null &&
            connectingDevice != null &&
            nowMillis - connectStartedAt >= connectTimeoutMillis &&
            (connectionState is BleConnectionState.Connecting ||
                connectionState is BleConnectionState.Reconnecting)
        ) {
            disconnectIntent = DisconnectIntent.RECONNECT
            manualDisconnect = false
            transition(
                connection = BleConnectionState.Failed("Bluetooth connection timed out"),
                configuration = ConfigurationState.Unavailable,
            )
            diagnostics.error("Connection timed out")
            val disconnected = try {
                transport.disconnect(connectingDevice)
            } catch (_: Exception) {
                false
            }
            // Do not clear the link generation or schedule a new connection
            // until the adapter proves that the old physical link is closed.
            // If disconnect could not start, a late Connected callback is
            // intercepted above and disconnected before it can become Ready.
            return disconnected
        }

        val dueAt = reconnectAtMillis ?: return false
        if (!applicationActive || nowMillis < dueAt) return false
        reconnectAtMillis = null
        val device = preferredDevice ?: return false
        if (connectionState is BleConnectionState.Connected ||
            connectionState is BleConnectionState.Connecting ||
            connectionState is BleConnectionState.Reconnecting
        ) return false
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(maxReconnectAttempts)
        return startConnectionAttempt(
            device = device,
            reconnecting = true,
            startedAtMillis = nowMillis,
        )
    }

    private fun startConnectionAttempt(
        device: ScannerDevice,
        reconnecting: Boolean,
        startedAtMillis: Long = nowMillis(),
    ): Boolean {
        val readiness = readTransportReadiness()
        val failure = readiness.failureReason(forConnection = true)
        if (failure != null) {
            transition(connection = readiness.asConnectionState(forConnection = true))
            diagnostics.error(failure)
            return false
        }

        val requestGeneration = ++nextRequestGeneration
        pendingConnectRequestGeneration = requestGeneration
        pendingConnectDevice = device
        pendingPhysicalLinkGeneration = ++nextLinkGeneration
        connectStartedAtMillis = startedAtMillis
        transition(
            connection = if (reconnecting) {
                BleConnectionState.Reconnecting(device, reconnectAttempt)
            } else {
                BleConnectionState.Connecting(device)
            },
            configuration = ConfigurationState.Unavailable,
        )
        diagnostics.connection(
            if (reconnecting) "Automatic reconnect requested" else "Connection requested",
        )
        val accepted = try {
            transport.connect(device)
        } catch (_: Exception) {
            false
        }
        if (!accepted && pendingConnectRequestGeneration == requestGeneration) {
            clearPendingConnection()
            transition(
                connection = if (reconnecting) {
                    BleConnectionState.Failed("Bluetooth reconnect could not start")
                } else {
                    BleConnectionState.Failed("Bluetooth connection could not start")
                },
            )
            diagnostics.error(
                if (reconnecting) "Reconnect start failed" else "Connection start failed",
            )
            if (reconnecting) scheduleReconnect(nowMillis())
        }
        return accepted
    }

    private fun clearPendingConnection() {
        connectStartedAtMillis = null
        pendingConnectDevice = null
        pendingConnectRequestGeneration = null
        pendingPhysicalLinkGeneration = null
    }

    private fun readKnownDevice(): ScannerDevice? {
        val store = knownDeviceStore ?: run {
            knownDeviceReadResult = BleKnownDeviceReadResult.Missing
            return null
        }
        val result = runCatching { store.read() }.getOrElse {
            BleKnownDeviceReadResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_READ_FAILED,
            )
        }
        knownDeviceReadResult = result
        return when (result) {
            is BleKnownDeviceReadResult.Found -> result.device
            BleKnownDeviceReadResult.Missing -> null
            is BleKnownDeviceReadResult.Rejected -> {
                // Keep the reason out of the diagnostic text. The reason may
                // originate from a persistence implementation and must never
                // become a channel for scanner settings or callback payloads.
                diagnostics.error("Known scanner identity rejected")
                null
            }
        }
    }

    private fun rememberKnownDevice(device: ScannerDevice): Boolean {
        val store = knownDeviceStore ?: return true
        val result = runCatching { store.save(device) }.getOrElse {
            BleKnownDeviceWriteResult.Rejected(
                BleKnownDeviceRejectionReason.DATASTORE_WRITE_FAILED,
            )
        }
        return when (result) {
            BleKnownDeviceWriteResult.Saved -> {
                knownDeviceReadResult = BleKnownDeviceReadResult.Found(device)
                true
            }
            is BleKnownDeviceWriteResult.Rejected -> {
                diagnostics.error("Known scanner identity could not be saved")
                false
            }
        }
    }

    private fun acceptConnectedEvent(event: BleTransportEvent.Connected): Boolean {
        val expectedDevice = pendingConnectDevice ?: return false
        if (event.device.id != expectedDevice.id) return false
        val expectedRequest = pendingConnectRequestGeneration
        if (event.requestGeneration != null && event.requestGeneration != expectedRequest) {
            return false
        }
        val expectedLink = pendingPhysicalLinkGeneration
        val link = event.linkGeneration
        if (link != null) {
            if (expectedLink != null && link < expectedLink) return false
            if (activeLinkGeneration != null && link <= activeLinkGeneration!!) return false
        }
        return true
    }

    private fun acceptConnectionFailureEvent(event: BleTransportEvent.ConnectionFailed): Boolean {
        val expectedDevice = pendingConnectDevice ?: return false
        if (event.device.id != expectedDevice.id) return false
        if (event.requestGeneration != null &&
            event.requestGeneration != pendingConnectRequestGeneration
        ) return false
        val link = event.linkGeneration
        if (link != null && pendingPhysicalLinkGeneration != null &&
            link < pendingPhysicalLinkGeneration!!
        ) return false
        return true
    }

    private fun acceptDisconnectedEvent(event: BleTransportEvent.Disconnected): Boolean {
        val expectedDevice = activeDevice ?: pendingConnectDevice
        if (expectedDevice == null || event.device.id != expectedDevice.id) return false
        val requestMatches = event.requestGeneration == null ||
            event.requestGeneration == activeRequestGeneration ||
            event.requestGeneration == pendingConnectRequestGeneration
        if (!requestMatches) return false
        val linkMatches = event.linkGeneration == null ||
            event.linkGeneration == activeLinkGeneration ||
            event.linkGeneration == pendingPhysicalLinkGeneration
        return linkMatches
    }

    private fun acceptScanEvent(event: BleTransportEvent.ScanReceived): Boolean {
        val connected = connectionState as? BleConnectionState.Connected ?: return false
        if (event.device != null && event.device.id != connected.device.id) return false
        if (event.requestGeneration != null &&
            event.requestGeneration != activeRequestGeneration
        ) return false
        if (event.linkGeneration != null && event.linkGeneration != activeLinkGeneration) {
            return false
        }
        return true
    }

    private fun scheduleReconnect(nowMillis: Long) {
        if (!applicationActive || manualDisconnect || preferredDevice == null) return
        if (reconnectAttempt >= maxReconnectAttempts) return
        if (reconnectAtMillis != null) return
        val nextAttempt = reconnectAttempt + 1
        val delay = reconnectDelayMillis(nextAttempt)
        require(delay >= 0) { "reconnect delay must not be negative" }
        reconnectAtMillis = nowMillis + delay
    }

    private fun transition(
        connection: BleConnectionState = mutableState.connection,
        configuration: ConfigurationState = mutableState.configuration,
    ) {
        mutableState = mutableState.copy(
            connection = connection,
            configuration = configuration,
            devices = devices,
            expectedFormat = expectedFormat,
            diagnostics = diagnostics.snapshot(),
        )
        emitState()
    }

    private fun emitState() {
        mutableState = mutableState.copy(
            devices = devices,
            expectedFormat = expectedFormat,
            diagnostics = diagnostics.snapshot(),
        )
        listener?.onStateChanged(mutableState)
    }

    /**
     * A readiness getter is platform code. Treat an adapter exception as an
     * unavailable transport instead of allowing it to escape through a UI
     * action or a reconnect ticker.
     */
    private fun readTransportReadiness(): BleTransportReadiness = runCatching {
        transport.readiness
    }.getOrElse {
        BleTransportReadiness(
            availability = BleAvailability.Failed("Bluetooth readiness unavailable"),
        )
    }

    private fun BleTransportReadiness.asConnectionState(
        forConnection: Boolean,
    ): BleConnectionState = when {
        lifecycle == BleAdapterLifecycleState.DESTROYED ->
            BleConnectionState.Unavailable("Bluetooth adapter is closed")
        lifecycle == BleAdapterLifecycleState.BACKGROUND ->
            BleConnectionState.Unavailable("Bluetooth adapter is inactive")
        availability !is BleAvailability.Ready -> availability.asConnectionState()
        !forConnection && discoveryPermission != BlePermissionState.GRANTED ->
            BleConnectionState.Unavailable("Bluetooth discovery permission is required")
        forConnection && connectionPermission != BlePermissionState.GRANTED ->
            BleConnectionState.Unavailable("Bluetooth connection permission is required")
        else -> BleConnectionState.Unavailable("Bluetooth is unavailable")
    }

    private fun BleAvailability.asConnectionState(): BleConnectionState = when (this) {
        BleAvailability.Ready -> BleConnectionState.Idle
        BleAvailability.Unknown -> BleConnectionState.Unavailable("Bluetooth is preparing")
        BleAvailability.PoweredOff -> BleConnectionState.Unavailable("Bluetooth is off")
        BleAvailability.Unauthorized -> BleConnectionState.Unavailable("Bluetooth permission is required")
        BleAvailability.Unsupported -> BleConnectionState.Unavailable("Bluetooth is unsupported")
        is BleAvailability.Failed -> BleConnectionState.Unavailable(reason)
    }

    private companion object {
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 4
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_RECONNECT_DELAY_MILLIS = 8_000L

        fun defaultReconnectDelayMillis(attempt: Int): Long =
            DEFAULT_RECONNECT_DELAY_MILLIS
    }
}
