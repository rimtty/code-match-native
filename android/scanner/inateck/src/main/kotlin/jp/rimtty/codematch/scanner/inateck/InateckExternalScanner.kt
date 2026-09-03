package jp.rimtty.codematch.scanner.inateck

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.BleConnectionCoordinator
import jp.rimtty.codematch.scanner.ble.BleKnownDeviceStore
import jp.rimtty.codematch.scanner.ble.BleScannerSessionCoordinator
import jp.rimtty.codematch.scanner.ble.BleSessionCoordinatorFactory
import jp.rimtty.codematch.scanner.ble.BleSymbologyProfile
import jp.rimtty.codematch.scanner.ble.BleSymbologySession
import jp.rimtty.codematch.scanner.ble.BleSymbologySnapshotStore
import jp.rimtty.codematch.scanner.ble.SelectableBleExternalScanner

/** Fixed identity for recovery data created by the official Android SDK PoC. */
const val INATECK_ANDROID_SDK_PROFILE_IDENTITY =
    "inateck-android-sdk-2.0.0-area-name-v1"

internal const val INATECK_SETTINGS_ENDPOINT = "inateck-sdk:get-set-setting-info"
private const val TICK_INTERVAL_MILLIS = 250L
private const val RESET_RECONNECT_DELAY_MILLIS = 8_000L

/**
 * Official-SDK-backed scanner used only by the `scannerPoc` app variant.
 *
 * The SDK owns discovery, authentication, command framing, and GATT. Existing
 * BLE safety coordinators still own settings snapshot/restore, timeout order,
 * reconnect policy, and payload privacy.
 */
class InateckExternalScanner private constructor(
    private val delegate: SelectableBleExternalScanner,
    private val transport: InateckSdkTransport,
    private val handler: Handler,
    private val nowMillis: () -> Long,
) : ExternalScanner, DefaultLifecycleObserver {
    private var closed = false
    private var resetAcknowledged = false
    private var reconnectScheduled = false

    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            delegate.tick(nowMillis())
            reconcileTransportReset()
            handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        handler.post(ticker)
    }

    override val devices: List<ScannerDevice>
        get() = delegate.devices
    override val connectionState: ConnectionState
        get() = delegate.connectionState
    override val configurationState: ConfigurationState
        get() = delegate.configurationState
    override val diagnosticEvents: List<DiagnosticEvent>
        get() = delegate.diagnosticEvents
    override val connectedDevice: ScannerDevice?
        get() = delegate.connectedDevice
    override val isConnected: Boolean
        get() = delegate.isConnected
    override val isReadyForScanning: Boolean
        get() = delegate.isReadyForScanning
    override val supportsConnectionControls: Boolean = true
    override val expectedFormat: ScanFormat?
        get() = delegate.expectedFormat

    override var listener: ExternalScannerListener?
        get() = delegate.listener
        set(value) {
            delegate.listener = value
        }

    override fun addListener(listener: ExternalScannerListener): Boolean =
        delegate.addListener(listener)

    override fun removeListener(listener: ExternalScannerListener): Boolean =
        delegate.removeListener(listener)

    override fun startDiscovery(): Boolean = delegate.startDiscovery()

    override fun stopDiscovery(): Boolean = delegate.stopDiscovery()

    override fun connect(device: ScannerDevice): Boolean = delegate.connect(device)

    override fun disconnect(): Boolean {
        return delegate.disconnect()
    }

    override fun reconnectKnownDevice(): Boolean = delegate.reconnectKnownDevice()

    override fun setExpectedFormat(format: ScanFormat?): Boolean {
        return delegate.setExpectedFormat(format)
    }

    override fun onStart(owner: LifecycleOwner) {
        delegate.setApplicationActive(true, nowMillis())
    }

    override fun onStop(owner: LifecycleOwner) {
        delegate.setApplicationActive(false, nowMillis())
    }

    fun close() {
        if (closed) return
        closed = true
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        handler.removeCallbacks(ticker)
        delegate.close()
        transport.close()
    }

    private fun reconcileTransportReset() {
        if (!delegate.isAwaitingTransportReset) {
            resetAcknowledged = false
            return
        }
        if (transport.isLinkActive || resetAcknowledged) return
        resetAcknowledged = true
        delegate.onTransportResetCompleted()
        if (!reconnectScheduled) {
            reconnectScheduled = true
            handler.postDelayed(
                {
                    reconnectScheduled = false
                    if (!closed) delegate.reconnectKnownDevice()
                },
                RESET_RECONNECT_DELAY_MILLIS,
            )
        }
    }

    companion object {
        fun create(
            context: Context,
            handler: Handler = Handler(Looper.getMainLooper()),
            nowMillis: () -> Long = { System.currentTimeMillis() },
        ): InateckExternalScanner {
            val applicationContext = context.applicationContext
            val gateway = AndroidInateckSdkGateway(applicationContext, handler)
            val transport = InateckSdkTransport(gateway, nowMillis)
            val snapshotStore = BleSymbologySnapshotStore(
                applicationContext,
                INATECK_ANDROID_SDK_PROFILE_IDENTITY,
            )
            val knownDeviceStore = BleKnownDeviceStore(
                applicationContext,
                INATECK_ANDROID_SDK_PROFILE_IDENTITY,
            )
            val connection = BleConnectionCoordinator(
                transport = transport,
                knownDeviceStore = knownDeviceStore,
                nowMillis = nowMillis,
                discoveryTimeoutMillis = 6_000L,
            )
            val delegate = SelectableBleExternalScanner(
                connectionCoordinator = connection,
                sessionFactory = BleSessionCoordinatorFactory { device ->
                    BleScannerSessionCoordinator(
                        connectionCoordinator = connection,
                        symbologySession = BleSymbologySession(
                            device = device,
                            transport = transport,
                            profile = BleSymbologyProfile(
                                settingsCharacteristicUuid = INATECK_SETTINGS_ENDPOINT,
                                codec = InateckAreaNameSymbologyCodec,
                                identity = INATECK_ANDROID_SDK_PROFILE_IDENTITY,
                            ),
                            snapshotStore = snapshotStore,
                            nowMillis = nowMillis,
                            // SDK 2.0.0 configures a 5-second FastBle timeout.
                            // setSettingInfo performs get/set/get and the
                            // adapter adds one exact readback, so keep the
                            // outer safety deadline beyond all four stages.
                            commandTimeoutMillis = 25_000L,
                            settingsReadTimeoutMillis = 6_000L,
                        ),
                    )
                },
            )
            return InateckExternalScanner(delegate, transport, handler, nowMillis)
        }
    }
}
