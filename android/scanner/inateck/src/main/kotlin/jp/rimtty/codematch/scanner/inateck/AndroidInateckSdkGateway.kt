package jp.rimtty.codematch.scanner.inateck

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.clj.fastble.BleManager
import com.clj.fastble.data.BleDevice
import com.inateck.scanner.ble.BleListManager
import com.inateck.scanner.ble.BleScannerDevice
import com.inateck.scanner.ble.callback.BleScanResultCallBack
import jp.rimtty.codematch.scanner.ble.BleAdapterLifecycleState
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BlePermissionState
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness

/** Production PoC gateway backed by Inateck's published Android 2.0.0 SDK. */
internal class AndroidInateckSdkGateway(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val scanIdleFlushMillis: Long = DEFAULT_SCAN_IDLE_FLUSH_MILLIS,
) : InateckSdkGateway {
    private val application = context.applicationContext as Application
    private val bluetoothManager =
        application.getSystemService(BluetoothManager::class.java)

    private var activeDevice: BleScannerDevice? = null
    private var pendingDevice: BleScannerDevice? = null
    private var activeScanBytes: ((ByteArray) -> Unit)? = null
    private var activeDisconnect: ((Boolean) -> Unit)? = null
    private var manualDisconnectInFlight = false
    private var connectionAttempt = 0L
    private val operationGate = InateckOperationGate()
    private var disconnectingDevice: BleScannerDevice? = null
    private val frameAssembler = InateckScanFrameAssembler()
    private val flushPendingFrame = Runnable {
        frameAssembler.flushPending()?.let(::dispatchScanFrame)
    }
    private var closed = false

    init {
        require(scanIdleFlushMillis > 0L) { "scanIdleFlushMillis must be positive" }
        BleListManager.init(application)
        // The SDK demo enables FastBle logging. Disable it immediately; the
        // scanner PoC also strips android.util.Log calls with R8.
        InateckNotificationBridge.disableVendorLogging()
    }

    override val readiness: BleTransportReadiness
        get() = BleTransportReadiness(
            lifecycle = if (closed) {
                BleAdapterLifecycleState.DESTROYED
            } else {
                BleAdapterLifecycleState.FOREGROUND
            },
            availability = bluetoothAvailability(),
            discoveryPermission = permission(Manifest.permission.BLUETOOTH_SCAN),
            connectionPermission = permission(Manifest.permission.BLUETOOTH_CONNECT),
        )

    override fun startDiscovery(
        onDevice: (InateckSdkDevice) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        if (closed || readiness.failureReason(forConnection = false) != null) return false
        return runCatching {
            BleListManager.scan(
                object : BleScanResultCallBack {
                    override fun onScanStarted(scanResultList: List<BleScannerDevice>) {
                        dispatchDevices(scanResultList, onDevice)
                    }

                    override fun onScanning(device: BleScannerDevice) {
                        device.asGatewayDevice()?.let { discovered ->
                            dispatch { onDevice(discovered) }
                        }
                    }

                    override fun onScanFinished(scanResultList: List<BleScannerDevice>) {
                        dispatchDevices(scanResultList, onDevice)
                        dispatch(onFinished)
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    override fun stopDiscovery(): Boolean {
        if (closed) return false
        return runCatching {
            BleListManager.stopScan()
            true
        }.getOrDefault(false)
    }

    override fun connect(
        deviceId: String,
        onScanBytes: (ByteArray) -> Unit,
        onDisconnected: (unexpected: Boolean) -> Unit,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        if (closed || activeDevice != null || pendingDevice != null || disconnectingDevice != null ||
            readiness.failureReason(forConnection = true) != null
        ) {
            return false
        }
        val device = findDevice(deviceId) ?: return false
        val attempt = ++connectionAttempt
        pendingDevice = device
        frameAssembler.reset()
        mainHandler.removeCallbacks(flushPendingFrame)
        activeScanBytes = onScanBytes
        activeDisconnect = onDisconnected
        installDisconnectHandler(device, attempt, completion)
        var sdkConnectCallbackHandled = false
        var connectCompletionDelivered = false
        return runCatching {
            device.connect { result ->
                dispatch {
                    if (!isCurrentAttempt(device, attempt)) return@dispatch
                    if (sdkConnectCallbackHandled) return@dispatch
                    sdkConnectCallbackHandled = true
                    if (result.isFailure) {
                        invalidateConnectionAttempt(attempt)
                        connectCompletionDelivered = true
                        completion(Result.failure(IllegalStateException("Inateck connection failed")))
                        return@dispatch
                    }
                    InateckNotificationBridge.install(
                        device,
                        object : InateckNotificationBridge.Callback {
                            override fun onReady() {
                                dispatch {
                                    if (isCurrentAttempt(device, attempt)) {
                                        pendingDevice = null
                                        activeDevice = device
                                        if (!connectCompletionDelivered) {
                                            connectCompletionDelivered = true
                                            completion(Result.success(Unit))
                                        }
                                    }
                                }
                            }

                            override fun onFailure() {
                                dispatch {
                                    if (!isCurrentAttempt(device, attempt)) return@dispatch
                                    if (connectCompletionDelivered) return@dispatch
                                    runCatching { InateckNotificationBridge.stop(device) }
                                    invalidateConnectionAttempt(attempt)
                                    runCatching { device.disconnect { _ -> } }
                                    connectCompletionDelivered = true
                                    completion(Result.failure(
                                        IllegalStateException("Inateck notification setup failed"),
                                    ))
                                }
                            }

                            override fun onCommandTraffic() {
                                dispatch { clearPendingScanFrame() }
                            }

                            override fun onBytes(value: ByteArray) {
                                val copy = value.clone()
                                dispatch {
                                    if (!isCurrentAttempt(device, attempt) || activeDevice == null) {
                                        return@dispatch
                                    }
                                    acceptScanChunk(copy)
                                }
                            }
                        },
                    )
                }
            }
            true
        }.getOrElse {
            invalidateConnectionAttempt(attempt)
            false
        }
    }

    override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean {
        if (disconnectingDevice != null) return false
        val device = (activeDevice ?: pendingDevice)?.takeIf { it.mac == deviceId } ?: return false
        if (activeDevice == null && pendingDevice?.mac == device.mac) {
            return cancelPendingConnection(device, completion)
        }
        manualDisconnectInFlight = true
        val disconnectAttempt = ++connectionAttempt
        invalidateOperations()
        disconnectingDevice = device
        clearPendingScanFrame()
        return runCatching {
            device.disconnect { result ->
                dispatch {
                    if (disconnectingDevice?.mac != device.mac ||
                        disconnectAttempt != connectionAttempt
                    ) {
                        return@dispatch
                    }
                    manualDisconnectInFlight = false
                    disconnectingDevice = null
                    if (result.isSuccess) {
                        clearConnectionCallbacks()
                        completion(Result.success(Unit))
                    } else {
                        // A failed disconnect is not proof that the physical
                        // link is down. Preserve link state so a timeout reset
                        // cannot acknowledge and overlap it with a new link.
                        if (activeDevice?.mac == device.mac) {
                            installDisconnectHandler(device, disconnectAttempt) { }
                        }
                        completion(Result.failure(IllegalStateException("Inateck disconnect failed")))
                    }
                }
            }
            true
        }.getOrElse {
            manualDisconnectInFlight = false
            disconnectingDevice = null
            if (activeDevice?.mac == device.mac) {
                installDisconnectHandler(device, disconnectAttempt) { }
            }
            false
        }
    }

    override fun readSettings(
        deviceId: String,
        completion: (Result<List<Map<String, String>>>) -> Unit,
    ): Boolean {
        val device = activeDevice?.takeIf { it.mac == deviceId } ?: return false
        val attempt = connectionAttempt
        val operation = beginOperation(device, attempt) ?: return false
        clearPendingScanFrame()
        return runCatching {
            device.messager.getSettingInfo { result ->
                dispatch {
                    if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                    finishOperation(operation)
                    @Suppress("UNCHECKED_CAST")
                    completion(result.map { it.map { entry -> entry.toMap() } })
                }
            }
            true
        }.getOrElse {
            finishOperation(operation)
            false
        }
    }

    override fun writeSettings(
        deviceId: String,
        commandJson: String,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val device = activeDevice?.takeIf { it.mac == deviceId } ?: return false
        val requested = InateckAreaNameSettingsContract.parseCommand(commandJson) ?: return false
        val attempt = connectionAttempt
        val operation = beginOperation(device, attempt) ?: return false
        clearPendingScanFrame()
        return runCatching {
            device.messager.setSettingInfo(commandJson) { result ->
                dispatch {
                    if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                    if (result.isFailure) {
                        finishOperation(operation)
                        completion(Result.failure(IllegalStateException("Inateck settings write failed")))
                        return@dispatch
                    }
                    // The SDK's public write performs get/set/get but returns
                    // no final inventory. Read once more and require every
                    // requested symbology identity/value to be present before
                    // publishing Ready. The SDK also reports general settings
                    // (for example volume), which are intentionally ignored.
                    device.messager.getSettingInfo { verification ->
                        dispatch {
                            if (!isCurrentOperation(device, attempt, operation)) return@dispatch
                            finishOperation(operation)
                            val actual = verification.getOrNull()?.map { it.toMap() }
                            if (actual != null &&
                                InateckAreaNameSettingsContract.containsRequestedSymbologies(
                                    settings = actual,
                                    requested = requested,
                                )
                            ) {
                                completion(Result.success(Unit))
                            } else {
                                completion(Result.failure(
                                    IllegalStateException("Inateck settings verification failed"),
                                ))
                            }
                        }
                    }
                }
            }
            true
        }.getOrElse {
            finishOperation(operation)
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidateOperations()
        runCatching { BleListManager.stopScan() }
        activeDevice?.let { device ->
            runCatching { InateckNotificationBridge.stop(device) }
            runCatching { device.disconnect { _ -> } }
        }
        pendingDevice?.let { device -> runCatching { device.disconnect { _ -> } } }
        disconnectingDevice?.let { device -> runCatching { device.disconnect { _ -> } } }
        disconnectingDevice = null
        clearConnectionCallbacks()
    }

    /**
     * The SDK's public BleScannerDevice.disconnect() reports success while
     * FastBle is still connecting and therefore does not close that pending
     * BluetoothGatt. Use the SDK's bundled FastBle controller to synchronously
     * destroy its active and temporary link maps before acknowledging reset.
     *
     * This gateway owns the only FastBle scanner stack in the scannerPoc
     * process, so the controller-wide reset cannot affect another client.
     */
    private fun cancelPendingConnection(
        device: BleScannerDevice,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        val attempt = ++connectionAttempt
        invalidateOperations()
        manualDisconnectInFlight = true
        clearPendingScanFrame()
        BleListManager.disconnectHandler = null
        return runCatching {
            BleManager.getInstance().destroy()
            BleListManager.scannerDevices = mutableListOf()
            if (attempt != connectionAttempt || pendingDevice?.mac != device.mac) {
                error("Inateck pending connection changed during reset")
            }
            manualDisconnectInFlight = false
            clearConnectionCallbacks()
            // destroy() retains the FastBle singleton/controller, but the SDK
            // init call reapplies its supported timeout/split configuration.
            BleListManager.init(application)
            InateckNotificationBridge.disableVendorLogging()
            completion(Result.success(Unit))
            true
        }.getOrElse {
            manualDisconnectInFlight = false
            // Do not clear pendingDevice or acknowledge disconnection when a
            // controller reset cannot prove that the BluetoothGatt is closed.
            false
        }
    }

    private fun installDisconnectHandler(
        device: BleScannerDevice,
        attempt: Long,
        connectCompletion: (Result<Unit>) -> Unit,
    ) {
        BleListManager.disconnectHandler = { disconnected, isUserInitiated ->
            dispatch {
                if (disconnected.mac != device.mac || attempt != connectionAttempt) return@dispatch
                val manual = manualDisconnectInFlight || isUserInitiated
                manualDisconnectInFlight = false
                val wasPending = pendingDevice?.mac == device.mac
                val callback = activeDisconnect
                invalidateConnectionAttempt(attempt)
                if (!manual && wasPending) {
                    connectCompletion(Result.failure(IllegalStateException("Inateck connection closed")))
                } else if (!manual) {
                    callback?.invoke(true)
                }
            }
        }
    }

    private fun findDevice(deviceId: String): BleScannerDevice? {
        BleListManager.scannerDevices.firstOrNull { it.mac == deviceId }?.let { return it }
        if (permission(Manifest.permission.BLUETOOTH_CONNECT) != BlePermissionState.GRANTED) {
            return null
        }
        val bluetoothDevice = runCatching {
            bluetoothManager?.adapter?.getRemoteDevice(deviceId)
        }.getOrNull() ?: return null
        return BleScannerDevice(BleDevice(bluetoothDevice)).also { recreated ->
            BleListManager.scannerDevices =
                (BleListManager.scannerDevices + recreated).distinctBy { it.mac }.toMutableList()
        }
    }

    private fun dispatchDevices(
        devices: List<BleScannerDevice>,
        onDevice: (InateckSdkDevice) -> Unit,
    ) {
        devices.distinctBy { it.mac }.forEach { device ->
            device.asGatewayDevice()?.let { discovered ->
                dispatch { onDevice(discovered) }
            }
        }
    }

    private fun BleScannerDevice.asGatewayDevice(): InateckSdkDevice? =
        mac?.takeIf(String::isNotBlank)?.let { stableId ->
            InateckSdkDevice(
                id = stableId,
                name = name?.takeIf(String::isNotBlank) ?: "Inateck scanner",
            )
        }

    private fun bluetoothAvailability(): BleAvailability {
        val adapter = bluetoothManager?.adapter ?: return BleAvailability.Unsupported
        return runCatching {
            if (adapter.isEnabled) BleAvailability.Ready else BleAvailability.PoweredOff
        }.getOrDefault(BleAvailability.Unauthorized)
    }

    private fun permission(name: String): BlePermissionState =
        if (application.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) {
            BlePermissionState.GRANTED
        } else {
            BlePermissionState.DENIED
        }

    private fun clearConnectionCallbacks() {
        clearPendingScanFrame()
        invalidateOperations()
        activeDevice = null
        pendingDevice = null
        activeScanBytes = null
        activeDisconnect = null
        BleListManager.disconnectHandler = null
    }

    private fun isCurrentAttempt(device: BleScannerDevice, attempt: Long): Boolean =
        !closed && disconnectingDevice == null && attempt == connectionAttempt &&
            (pendingDevice?.mac == device.mac || activeDevice?.mac == device.mac)

    private fun invalidateConnectionAttempt(attempt: Long) {
        if (attempt == connectionAttempt) connectionAttempt++
        clearConnectionCallbacks()
    }

    private fun beginOperation(device: BleScannerDevice, attempt: Long): Long? {
        if (activeDevice == null || !isCurrentAttempt(device, attempt)) {
            return null
        }
        return operationGate.begin()
    }

    private fun isCurrentOperation(
        device: BleScannerDevice,
        attempt: Long,
        operation: Long,
    ): Boolean = operationGate.isCurrent(operation) && isCurrentAttempt(device, attempt)

    private fun finishOperation(operation: Long) {
        operationGate.finish(operation)
    }

    private fun invalidateOperations() {
        operationGate.invalidate()
    }

    private fun acceptScanChunk(bytes: ByteArray) {
        mainHandler.removeCallbacks(flushPendingFrame)
        frameAssembler.append(bytes).forEach(::dispatchScanFrame)
        if (frameAssembler.hasPendingBytes) {
            mainHandler.postDelayed(flushPendingFrame, scanIdleFlushMillis)
        }
    }

    private fun dispatchScanFrame(frame: ByteArray) {
        activeScanBytes?.invoke(frame)
    }

    private fun clearPendingScanFrame() {
        mainHandler.removeCallbacks(flushPendingFrame)
        frameAssembler.reset()
    }

    private companion object {
        const val DEFAULT_SCAN_IDLE_FLUSH_MILLIS = 250L
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block() else mainHandler.post(block)
    }
}
