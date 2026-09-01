package jp.rimtty.codematch.scanner.fake

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * A synchronous scanner for JVM/Compose tests.
 *
 * Every state transition is explicit and happens on the calling thread. Tests
 * can inject a clock to make duplicate-callback behavior deterministic without
 * sleeping. The fake models a scanner's transport boundary only; it does not
 * pretend that Bluetooth permissions or camera hardware are available.
 */
class FakeExternalScanner(
    val defaultDevice: ScannerDevice = DEFAULT_DEVICE,
    private val now: () -> Long = { System.currentTimeMillis() },
    val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
) : ExternalScanner {
    /** Alternate constructor for tests that name their injected clock `clock`. */
    constructor(
        clock: () -> Long,
        duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
        device: ScannerDevice = DEFAULT_DEVICE,
    ) : this(device, clock, duplicateWindowMillis)

    private val mutableDevices = mutableListOf<ScannerDevice>()
    private val mutableDiagnosticEvents = mutableListOf<DiagnosticEvent>()
    private var nextSequence = 0L
    private var knownDevice: ScannerDevice? = null
    private var lastDeliveredValue: String? = null
    private var lastDeliveredAt: Long? = null
    private var sessionConfigurationApplied = false
    private var nextConnectionFailure: String? = null
    private var nextConfigurationFailure: String? = null

    private var mutableConnectionState: ConnectionState = ConnectionState.Idle
    private var mutableConfigurationState: ConfigurationState = ConfigurationState.Unavailable
    private var mutableExpectedFormat: ScanFormat? = null

    override val devices: List<ScannerDevice>
        get() = mutableDevices.toList()

    override val connectionState: ConnectionState
        get() = mutableConnectionState

    /** Alias used by UI tests that mirror the Swift service's `state` name. */
    val state: ConnectionState
        get() = connectionState

    override val configurationState: ConfigurationState
        get() = mutableConfigurationState

    /** Alias used by UI tests that mirror the Swift service's configuration name. */
    val configuration: ConfigurationState
        get() = configurationState

    override val diagnosticEvents: List<DiagnosticEvent>
        get() = mutableDiagnosticEvents.toList()

    val recentDiagnostics: List<DiagnosticEvent>
        get() = diagnosticEvents

    override val expectedFormat: ScanFormat?
        get() = mutableExpectedFormat

    val expectedCode: ScanFormat?
        get() = expectedFormat

    val preferredDevice: ScannerDevice?
        get() = knownDevice

    override var listener: ExternalScannerListener? = null

    /** Optional lambda hooks make the fake convenient in small unit tests. */
    var onPayload: ((ScanPayload) -> Unit)? = null
    var onScanPayload: ((ScanPayload) -> Unit)? = null
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onConfigurationStateChanged: ((ConfigurationState) -> Unit)? = null

    /** Arrange for the next connection attempt to fail. */
    fun failNextConnection(reason: String = DEFAULT_CONNECTION_FAILURE) {
        nextConnectionFailure = reason
    }

    /** Arrange for the next session configuration attempt to fail. */
    fun failNextConfiguration(reason: String = DEFAULT_CONFIGURATION_FAILURE) {
        nextConfigurationFailure = reason
    }

    override fun startDiscovery(): Boolean {
        if (mutableConnectionState === ConnectionState.Searching) return false

        transitionConnection(ConnectionState.Searching)
        val device = knownDevice ?: defaultDevice
        appendDevice(device)
        record(DiagnosticCategory.CONNECTION, "Discovery found ${devices.size} scanner(s)")
        // Discovery stays active until the caller explicitly stops it or
        // connects. This mirrors an asynchronous adapter: a scan screen can
        // observe Searching while additional devices would be reported.
        return true
    }

    override fun stopDiscovery(): Boolean {
        if (mutableConnectionState !== ConnectionState.Searching) return false

        transitionConnection(ConnectionState.Idle)
        record(DiagnosticCategory.CONNECTION, "Discovery stopped")
        return true
    }

    override fun connect(device: ScannerDevice): Boolean {
        appendDevice(device)
        knownDevice = device
        transitionConnection(ConnectionState.Connecting(device))

        val connectionFailure = nextConnectionFailure
        nextConnectionFailure = null
        if (connectionFailure != null) {
            transitionConfiguration(ConfigurationState.Unavailable)
            transitionConnection(ConnectionState.Failed(connectionFailure))
            return false
        }

        transitionConnection(ConnectionState.Connected(device))
        val configurationFailure = nextConfigurationFailure
        nextConfigurationFailure = null
        if (configurationFailure != null) {
            transitionConfiguration(ConfigurationState.Failed(configurationFailure))
            return false
        }

        transitionConfiguration(ConfigurationState.Configuring)
        transitionConfiguration(ConfigurationState.Ready)
        sessionConfigurationApplied = false
        record(DiagnosticCategory.CONFIGURATION, "Scanner configuration ready")
        return true
    }

    override fun disconnect(): Boolean {
        val hadConnection = mutableConnectionState is ConnectionState.Connected ||
            mutableConnectionState is ConnectionState.Connecting
        if (!hadConnection && mutableConnectionState !is ConnectionState.Failed) return false

        mutableExpectedFormat = null
        sessionConfigurationApplied = false
        // A new connection/session must be able to deliver the first callback
        // even when it has the same value as the previous connection.
        lastDeliveredValue = null
        lastDeliveredAt = null
        transitionConfiguration(ConfigurationState.Unavailable)
        transitionConnection(ConnectionState.Idle)
        record(DiagnosticCategory.CONNECTION, "Scanner disconnected")
        return true
    }

    override fun reconnectKnownDevice(): Boolean {
        val device = knownDevice ?: return false
        if (isConnected && connectedDevice == device) return true
        return connect(device)
    }

    override fun setExpectedFormat(format: ScanFormat?): Boolean {
        mutableExpectedFormat = format
        if (!isConnected) {
            transitionConfiguration(ConfigurationState.Unavailable)
            return false
        }

        if (format == null) {
            sessionConfigurationApplied = false
            transitionConfiguration(ConfigurationState.Ready)
            record(DiagnosticCategory.CONFIGURATION, "Scanner configuration restored")
            return true
        }

        // QR -> Code 128 is a logical application step. The physical scanner
        // mode remains the same session mode, so do not enqueue another
        // configuration operation for each logical step.
        if (sessionConfigurationApplied) return configurationState.isReady

        val configurationFailure = nextConfigurationFailure
        nextConfigurationFailure = null
        if (configurationFailure != null) {
            transitionConfiguration(ConfigurationState.Failed(configurationFailure))
            return false
        }

        transitionConfiguration(ConfigurationState.Configuring)
        // Mark this before notifying observers. A synchronous observer may
        // immediately re-apply the current logical format from its Ready
        // callback; that must be a no-op rather than a recursive configure.
        sessionConfigurationApplied = true
        transitionConfiguration(ConfigurationState.Ready)
        record(DiagnosticCategory.CONFIGURATION, "Scanner session configuration ready")
        return true
    }

    /**
     * Emit an externally supplied callback. Bluetooth callbacks require a
     * connected, ready scanner; camera callbacks can be used without one.
     */
    fun emitPayload(payload: ScanPayload): Boolean {
        if (payload.source == InputSource.BLUETOOTH && !readyForScanning()) return false

        val value = normalizeTransportTerminators(payload.value)
        if (value.isEmpty()) return false

        val timestamp = payload.timestampMillis.takeIf { it != 0L } ?: now()
        val previousAt = lastDeliveredAt
        if (lastDeliveredValue == value && previousAt != null) {
            val elapsed = timestamp - previousAt
            if (elapsed in 0 until duplicateWindowMillis) return false
        }

        val delivered = payload.copy(value = value, timestampMillis = timestamp)
        lastDeliveredValue = value
        lastDeliveredAt = timestamp
        listener?.onScanPayload(delivered)
        onPayload?.invoke(delivered)
        onScanPayload?.invoke(delivered)
        return true
    }

    fun emitPayload(
        value: String,
        format: ScanFormat = expectedFormat ?: ScanFormat.QR,
        source: InputSource = InputSource.BLUETOOTH,
        timestampMillis: Long = now(),
    ): Boolean = emitPayload(ScanPayload(value, source, format, timestampMillis))

    /** Swift Fake compatibility spelling. */
    fun simulateScan(
        value: String,
        format: ScanFormat = expectedFormat ?: ScanFormat.QR,
        source: InputSource = InputSource.BLUETOOTH,
        timestampMillis: Long = now(),
    ): Boolean = emitPayload(value, format, source, timestampMillis)

    /** Trigger an unexpected connection loss while retaining the known device. */
    fun simulateUnexpectedDisconnect(): Boolean = disconnect()

    /** Expose a sanitized diagnostic hook for adapter tests. */
    fun recordConnectionEvent(message: String) {
        record(DiagnosticCategory.CONNECTION, message)
    }

    /** Expose a sanitized diagnostic hook for adapter tests. */
    fun recordConfigurationEvent(message: String) {
        record(DiagnosticCategory.CONFIGURATION, message)
    }

    fun clearDiagnostics() {
        mutableDiagnosticEvents.clear()
    }

    private fun readyForScanning(): Boolean =
        isConnected && mutableConfigurationState === ConfigurationState.Ready

    private fun appendDevice(device: ScannerDevice) {
        if (mutableDevices.none { it.id == device.id }) mutableDevices += device
    }

    private fun transitionConnection(state: ConnectionState) {
        mutableConnectionState = state
        listener?.onConnectionStateChanged(state)
        onConnectionStateChanged?.invoke(state)
    }

    private fun transitionConfiguration(state: ConfigurationState) {
        mutableConfigurationState = state
        listener?.onConfigurationStateChanged(state)
        onConfigurationStateChanged?.invoke(state)
    }

    private fun record(category: DiagnosticCategory, message: String) {
        // Keep this method private to guarantee that callers cannot accidentally
        // persist a scan value as a diagnostic event.
        nextSequence += 1
        mutableDiagnosticEvents += DiagnosticEvent(
            category,
            message,
            now(),
            nextSequence,
        )
        if (mutableDiagnosticEvents.size > MAX_DIAGNOSTIC_EVENTS) {
            val removeCount = mutableDiagnosticEvents.size - MAX_DIAGNOSTIC_EVENTS
            repeat(removeCount) { mutableDiagnosticEvents.removeAt(0) }
        }
    }

    companion object {
        const val DEFAULT_DUPLICATE_WINDOW_MILLIS: Long = 750L
        const val MAX_DIAGNOSTIC_EVENTS: Int = 20
        const val DEFAULT_CONNECTION_FAILURE: String = "Fake scanner connection failed"
        const val DEFAULT_CONFIGURATION_FAILURE: String = "Fake scanner configuration failed"

        val DEFAULT_DEVICE = ScannerDevice(
            id = "FAKE-BCST-47",
            name = "BCST-47 (Fake)",
        )

        fun normalizeTransportTerminators(rawValue: String): String {
            var value = rawValue
            while (value.isNotEmpty() && (value.last() == '\r' || value.last() == '\n' || value.last() == '\u0000')) {
                value = value.dropLast(1)
            }
            return value
        }
    }
}
