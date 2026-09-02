package jp.rimtty.codematch.scanner.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * All scanner-specific BLE endpoints are supplied by the caller.
 *
 * The profile contains no framing or vendor command information. A scanner
 * integration can therefore discover its UUIDs and select its write mode at
 * runtime, while the platform transport remains useful for a different
 * scanner without a code change.
 */
data class AndroidBleProtocolProfile(
    val serviceUuid: UUID,
    val readCharacteristicUuid: UUID? = null,
    val writeCharacteristicUuid: UUID? = null,
    val notifyCharacteristicUuid: UUID? = null,
    val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    /** Descriptor UUID used to enable notifications, when one is required. */
    val notificationDescriptorUuid: UUID? = null,
    /** Value written to the notification descriptor, if the platform needs it. */
    val notificationEnableValue: ByteArray? = null,
    /** Framing/decoding is selected by the scanner integration, never guessed here. */
    val notificationDecoder: BleNotificationDecoder? = null,
) {
    init {
        require(writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ||
            writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ||
            writeType == BluetoothGattCharacteristic.WRITE_TYPE_SIGNED
        ) { "Unsupported Bluetooth GATT write type" }
        if (notificationDescriptorUuid != null) {
            require(notifyCharacteristicUuid != null) {
                "A notification descriptor requires a notification characteristic"
            }
        }
        notificationEnableValue?.let { require(it.isNotEmpty()) { "Notification value must not be empty" } }
    }

    companion object {
        /** Convenience for callers that persist UUIDs as strings. */
        fun fromStrings(
            serviceUuid: String,
            readCharacteristicUuid: String? = null,
            writeCharacteristicUuid: String? = null,
            notifyCharacteristicUuid: String? = null,
            writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            notificationDescriptorUuid: String? = null,
            notificationEnableValue: ByteArray? = null,
            notificationDecoder: BleNotificationDecoder? = null,
        ): AndroidBleProtocolProfile = AndroidBleProtocolProfile(
            serviceUuid = UUID.fromString(serviceUuid),
            readCharacteristicUuid = readCharacteristicUuid?.let(UUID::fromString),
            writeCharacteristicUuid = writeCharacteristicUuid?.let(UUID::fromString),
            notifyCharacteristicUuid = notifyCharacteristicUuid?.let(UUID::fromString),
            writeType = writeType,
            notificationDescriptorUuid = notificationDescriptorUuid?.let(UUID::fromString),
            notificationEnableValue = notificationEnableValue,
            notificationDecoder = notificationDecoder,
        )
    }
}

/** A decoded notification. The decoder owns framing and text validation. */
data class BleDecodedNotification(
    val value: String,
    val format: ScanFormat,
    val timestampMillis: Long = 0L,
) {
    init {
        require(value.isNotEmpty()) { "Notification value must not be empty" }
    }
}

/**
 * Scanner-specific notification boundary. The raw bytes are never logged by
 * the platform transport; they are passed only to this injected decoder.
 */
fun interface BleNotificationDecoder {
    fun decode(payload: ByteArray): BleDecodedNotification?
}

/** One cancellable timeout used by the adapter's discovery/connect deadlines. */
fun interface BleTimeoutHandle {
    fun cancel()
}

/** Scheduler seam; tests can advance time without Android loopers or hardware. */
fun interface BleTimeoutScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): BleTimeoutHandle
}

private class HandlerBleTimeoutScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : BleTimeoutScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): BleTimeoutHandle {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis)
        return BleTimeoutHandle { handler.removeCallbacks(runnable) }
    }
}

/** Permission seam kept small so API 31+ checks are deterministic in JVM tests. */
interface BlePermissionChecker {
    fun discoveryPermission(): BlePermissionState
    fun connectionPermission(): BlePermissionState
}

private class AndroidBlePermissionChecker(
    private val context: Context,
) : BlePermissionChecker {
    override fun discoveryPermission(): BlePermissionState = check(Manifest.permission.BLUETOOTH_SCAN)

    override fun connectionPermission(): BlePermissionState = check(Manifest.permission.BLUETOOTH_CONNECT)

    private fun check(permission: String): BlePermissionState = try {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            BlePermissionState.GRANTED
        } else {
            BlePermissionState.DENIED
        }
    } catch (_: SecurityException) {
        BlePermissionState.DENIED
    }
}

/** Scan callback seam used by [AndroidBleTransport] and deterministic tests. */
interface BlePlatformScanCallback {
    fun onScanResult(deviceId: String, name: String?, serviceUuids: Set<String>)
    fun onScanFailed()
}

/** GATT callback seam; Android callbacks are translated before crossing it. */
interface BlePlatformGattCallback {
    fun onConnectionStateChanged(gatt: BlePlatformGatt, status: Int, connected: Boolean)
    fun onServicesDiscovered(gatt: BlePlatformGatt, status: Int)
    fun onDescriptorWrite(gatt: BlePlatformGatt, descriptorUuid: UUID, status: Int) {}
    fun onCharacteristicRead(
        gatt: BlePlatformGatt,
        characteristicUuid: UUID,
        status: Int,
        value: ByteArray,
    ) {}

    fun onCharacteristicWrite(
        gatt: BlePlatformGatt,
        characteristicUuid: UUID,
        status: Int,
    ) {}

    fun onCharacteristicChanged(
        gatt: BlePlatformGatt,
        characteristicUuid: UUID,
        value: ByteArray,
    ) {}
}

/**
 * Android-free GATT handle. Production uses [BluetoothGatt] behind this
 * interface, while tests can model synchronous failures and late callbacks.
 */
interface BlePlatformGatt {
    val device: ScannerDevice

    fun discoverServices(): Boolean

    fun hasCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean

    fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean

    fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        writeType: Int,
    ): Boolean

    fun enableNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID?,
        enableValue: ByteArray?,
    ): Boolean

    /** Descriptor selected by the platform, when enabling requires a write. */
    fun notificationDescriptorUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        requestedDescriptorUuid: UUID?,
    ): UUID? = null

    fun disconnect()

    fun close()
}

/**
 * Platform seam for the transport. [AndroidBluetoothPlatform] is the
 * production implementation; a test fake can provide the same Bluetooth
 * manager/adapter/scanner/GATT behavior without a scanner or radio.
 */
interface BlePlatform {
    val availability: BleAvailability

    fun startDiscovery(serviceUuid: UUID, callback: BlePlatformScanCallback): Boolean

    fun stopDiscovery(callback: BlePlatformScanCallback)

    fun connect(device: ScannerDevice, callback: BlePlatformGattCallback): BlePlatformGatt?
}

/**
 * Android Bluetooth implementation of [BlePlatform]. No scanner UUID or
 * scanner framing is present here; both are passed through from the profile.
 */
private class AndroidBluetoothPlatform(
    private val context: Context,
) : BlePlatform {
    private val bluetoothManager: BluetoothManager?
        get() = runCatching {
            context.getSystemService(BluetoothManager::class.java)
        }.getOrNull()

    private val bluetoothAdapter: BluetoothAdapter?
        @SuppressLint("MissingPermission")
        get() = runCatching { bluetoothManager?.adapter }.getOrNull()

    override val availability: BleAvailability
        @SuppressLint("MissingPermission")
        get() {
            val adapter = bluetoothAdapter ?: return BleAvailability.Unsupported
            return try {
                if (adapter.isEnabled) BleAvailability.Ready else BleAvailability.PoweredOff
            } catch (_: SecurityException) {
                BleAvailability.Unauthorized
            }
        }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(serviceUuid: UUID, callback: BlePlatformScanCallback): Boolean {
        val scanner = runCatching { bluetoothAdapter?.bluetoothLeScanner }.getOrNull()
            ?: return false
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return runCatching {
            scanner.startScan(listOf(filter), settings, AndroidScanCallback(callback))
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery(callback: BlePlatformScanCallback) {
        val scanner = runCatching { bluetoothAdapter?.bluetoothLeScanner }.getOrNull() ?: return
        // The callback passed to startScan is retained by the transport; this
        // platform creates an equivalent callback only when needed by tests.
        // Android requires the exact callback instance, so callers normally
        // use AndroidBluetoothPlatform.start/stop through its callback map.
        callbackRegistry[callback]?.let { scanCallback ->
            runCatching { scanner.stopScan(scanCallback) }
            callbackRegistry.remove(callback)
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: ScannerDevice, callback: BlePlatformGattCallback): BlePlatformGatt? {
        val adapter = bluetoothAdapter ?: return null
        val bluetoothDevice = runCatching { adapter.getRemoteDevice(device.id) }.getOrNull() ?: return null
        return runCatching {
            var wrapper: AndroidBluetoothGatt? = null
            val androidCallback = object : BluetoothGattCallback() {
                private fun wrap(gatt: BluetoothGatt): AndroidBluetoothGatt =
                    wrapper ?: AndroidBluetoothGatt(device, gatt, callback).also { wrapper = it }

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    callback.onConnectionStateChanged(
                        wrap(gatt),
                        status,
                        newState == BluetoothProfile.STATE_CONNECTED,
                    )
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    callback.onServicesDiscovered(wrap(gatt), status)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    callback.onDescriptorWrite(wrap(gatt), descriptor.uuid, status)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    callback.onCharacteristicRead(
                        wrap(gatt),
                        characteristic.uuid,
                        status,
                        characteristic.value?.clone() ?: ByteArray(0),
                    )
                }

                @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    callback.onCharacteristicRead(wrap(gatt), characteristic.uuid, status, value.clone())
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    callback.onCharacteristicWrite(wrap(gatt), characteristic.uuid, status)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    callback.onCharacteristicChanged(
                        wrap(gatt),
                        characteristic.uuid,
                        characteristic.value?.clone() ?: ByteArray(0),
                    )
                }

                @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    callback.onCharacteristicChanged(wrap(gatt), characteristic.uuid, value.clone())
                }
            }
            val gatt = bluetoothDevice.connectGatt(
                context,
                false,
                androidCallback,
                BluetoothDevice.TRANSPORT_LE,
            ) ?: return@runCatching null
            wrapper ?: AndroidBluetoothGatt(device, gatt, callback)
        }.getOrNull()
    }

    private val callbackRegistry = mutableMapOf<BlePlatformScanCallback, AndroidScanCallback>()

    @SuppressLint("MissingPermission")
    private inner class AndroidScanCallback(
        private val callback: BlePlatformScanCallback,
    ) : ScanCallback() {
        init {
            callbackRegistry[callback] = this
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = runCatching { device.name }.getOrNull()
            val services = result.scanRecord?.serviceUuids
                ?.mapNotNull { it?.uuid?.toString() }
                ?.toSet()
                ?: emptySet()
            callback.onScanResult(device.address, name, services)
        }

        override fun onScanFailed(errorCode: Int) {
            callback.onScanFailed()
        }
    }
}

@SuppressLint("MissingPermission")
private class AndroidBluetoothGatt(
    override val device: ScannerDevice,
    private val gatt: BluetoothGatt,
    private val callback: BlePlatformGattCallback,
) : BlePlatformGatt {
    override fun discoverServices(): Boolean = runCatching { gatt.discoverServices() }.getOrDefault(false)

    override fun hasCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean = runCatching {
        gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid) != null
    }.getOrDefault(false)

    override fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean {
        val characteristic = findCharacteristic(serviceUuid, characteristicUuid) ?: return false
        return runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
    }

    override fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        writeType: Int,
    ): Boolean {
        val characteristic = findCharacteristic(serviceUuid, characteristicUuid) ?: return false
        return runCatching {
            characteristic.writeType = writeType
            characteristic.value = payload.clone()
            gatt.writeCharacteristic(characteristic)
        }.getOrDefault(false)
    }

    override fun enableNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID?,
        enableValue: ByteArray?,
    ): Boolean {
        val characteristic = findCharacteristic(serviceUuid, characteristicUuid) ?: return false
        val localEnabled = runCatching { gatt.setCharacteristicNotification(characteristic, true) }
            .getOrDefault(false)
        if (!localEnabled) return false
        val descriptor = characteristic.descriptors.firstOrNull { descriptorUuid == null || it.uuid == descriptorUuid }
            ?: return true
        return runCatching {
            descriptor.value = enableValue?.clone()
                ?: BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone()
            gatt.writeDescriptor(descriptor)
        }.getOrDefault(false)
    }

    override fun notificationDescriptorUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        requestedDescriptorUuid: UUID?,
    ): UUID? = findCharacteristic(serviceUuid, characteristicUuid)
        ?.descriptors
        ?.firstOrNull { requestedDescriptorUuid == null || it.uuid == requestedDescriptorUuid }
        ?.uuid

    override fun disconnect() {
        runCatching { gatt.disconnect() }
    }

    override fun close() {
        runCatching { gatt.close() }
    }

    private fun findCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic? =
        runCatching { gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid) }.getOrNull()
}

/**
 * Generic Android BLE transport behind the SDK-neutral [BleTransport]
 * boundary. It owns only platform lifetime and GATT dispatch; protocol UUIDs
 * and notification framing come from [AndroidBleProtocolProfile].
 */
class AndroidBleTransport private constructor(
    private val profile: AndroidBleProtocolProfile,
    private val scheduler: BleTimeoutScheduler,
    private val permissionChecker: BlePermissionChecker,
    private val platform: BlePlatform,
    private val nowMillis: () -> Long,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) : BleTransport {
    /** Production constructor backed by Android BluetoothManager/Adapter/GATT. */
    constructor(
        context: Context,
        profile: AndroidBleProtocolProfile,
        scheduler: BleTimeoutScheduler = HandlerBleTimeoutScheduler(),
        permissionChecker: BlePermissionChecker = AndroidBlePermissionChecker(context),
        platform: BlePlatform = AndroidBluetoothPlatform(context),
        nowMillis: () -> Long = { System.currentTimeMillis() },
    ) : this(profile, scheduler, permissionChecker, platform, nowMillis, Unit)

    /** Framework-free constructor for Robolectric/seam tests and host wiring. */
    constructor(
        profile: AndroidBleProtocolProfile,
        scheduler: BleTimeoutScheduler,
        permissionChecker: BlePermissionChecker,
        platform: BlePlatform,
        nowMillis: () -> Long = { System.currentTimeMillis() },
    ) : this(profile, scheduler, permissionChecker, platform, nowMillis, Unit)

    private companion object {
        const val GATT_SUCCESS = 0
        const val DISCOVERY_TIMEOUT_MILLIS = 5_000L
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
    }

    private data class DiscoveryContext(
        val generation: Long,
        val callback: BlePlatformScanCallback,
        var timeout: BleTimeoutHandle? = null,
    )

    private data class ConnectionContext(
        val device: ScannerDevice,
        val requestGeneration: Long,
        val linkGeneration: Long,
        var gatt: BlePlatformGatt? = null,
        var servicesReady: Boolean = false,
        var notificationEnablePending: Boolean = false,
        var notificationDescriptorUuid: UUID? = null,
        var timeout: BleTimeoutHandle? = null,
    )

    private data class PendingRead(
        val requestGeneration: Long,
        val characteristicUuid: UUID,
        val completion: (Result<ByteArray>) -> Unit,
    )

    private data class PendingWrite(
        val requestGeneration: Long,
        val characteristicUuid: UUID,
        val completion: (Result<Unit>) -> Unit,
    )

    private val lock = Any()
    private var mutableLifecycle = BleAdapterLifecycleState.FOREGROUND
    private var discoveryGeneration = 0L
    private var requestGeneration = 0L
    private var linkGeneration = 0L
    private var discovery: DiscoveryContext? = null
    private var connection: ConnectionContext? = null
    private var pendingRead: PendingRead? = null
    private var pendingWrite: PendingWrite? = null

    override var listener: BleTransportListener? = null

    override val availability: BleAvailability
        get() = synchronized(lock) { runCatching { platform.availability }.getOrDefault(BleAvailability.Unknown) }

    override val readiness: BleTransportReadiness
        get() = synchronized(lock) {
            BleTransportReadiness(
                lifecycle = mutableLifecycle,
                availability = availability,
                discoveryPermission = runCatching { permissionChecker.discoveryPermission() }
                    .getOrDefault(BlePermissionState.UNKNOWN),
                connectionPermission = runCatching { permissionChecker.connectionPermission() }
                    .getOrDefault(BlePermissionState.UNKNOWN),
            )
        }

    fun setLifecycleState(state: BleAdapterLifecycleState) {
        synchronized(lock) {
            if (mutableLifecycle == BleAdapterLifecycleState.DESTROYED) return
            if (mutableLifecycle == state) return
            mutableLifecycle = state
            if (state != BleAdapterLifecycleState.FOREGROUND) {
                stopDiscoveryLocked()
                resetConnectionLocked(unexpected = false, reason = "Bluetooth adapter is inactive")
            }
        }
    }

    /** Convenience bridge for Activity/Process lifecycle callbacks. */
    fun setApplicationActive(active: Boolean) {
        setLifecycleState(
            if (active) BleAdapterLifecycleState.FOREGROUND else BleAdapterLifecycleState.BACKGROUND,
        )
    }

    override fun startDiscovery(): Boolean = synchronized(lock) {
        if (discovery != null) return false
        if (mutableLifecycle != BleAdapterLifecycleState.FOREGROUND) return false
        if (readiness.failureReason(forConnection = false) != null) return false

        val generation = ++discoveryGeneration
        val callback = DiscoveryCallback(generation)
        val context = DiscoveryContext(generation, callback)
        discovery = context
        val started = runCatching { platform.startDiscovery(profile.serviceUuid, callback) }.getOrDefault(false)
        if (!started || discovery?.generation != generation) {
            if (discovery?.generation == generation) {
                discovery = null
                runCatching { platform.stopDiscovery(callback) }
            }
            return false
        }
        context.timeout = scheduler.schedule(DISCOVERY_TIMEOUT_MILLIS) {
            synchronized(lock) {
                if (discovery?.generation == generation) stopDiscoveryLocked()
            }
        }
        emit(BleTransportEvent.DiscoveryStarted)
        true
    }

    override fun stopDiscovery(): Boolean = synchronized(lock) {
        if (discovery == null) return false
        stopDiscoveryLocked()
        true
    }

    override fun connect(device: ScannerDevice): Boolean = synchronized(lock) {
        if (connection != null) return false
        if (mutableLifecycle != BleAdapterLifecycleState.FOREGROUND) return false
        if (readiness.failureReason(forConnection = true) != null) return false

        val request = ++requestGeneration
        val link = ++linkGeneration
        val context = ConnectionContext(device, request, link)
        connection = context
        val callback = ConnectionCallback(request, link)
        context.timeout = scheduler.schedule(CONNECT_TIMEOUT_MILLIS) {
            synchronized(lock) {
                if (connection?.requestGeneration == request && connection?.linkGeneration == link) {
                    failConnectionLocked(context, "Bluetooth connection timed out")
                }
            }
        }
        val gatt = runCatching { platform.connect(device, callback) }.getOrNull()
        if (gatt == null) {
            if (connection?.requestGeneration == request) {
                failConnectionLocked(context, "Bluetooth connection could not start")
            }
            return false
        }
        if (connection?.requestGeneration == request && context.gatt == null) {
            context.gatt = gatt
        } else if (connection?.requestGeneration != request) {
            // A synchronous fake/platform callback may have completed and
            // replaced this link while connect() was still returning.
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        true
    }

    override fun disconnect(device: ScannerDevice): Boolean = synchronized(lock) {
        val current = connection ?: return false
        if (current.device.id != device.id) return false
        resetConnectionLocked(unexpected = false, reason = "Scanner disconnected")
        true
    }

    override fun write(
        characteristicUuid: String,
        payload: ByteArray,
        completion: (Result<Unit>) -> Unit,
    ): Boolean = synchronized(lock) {
        val uuid = parseUuid(characteristicUuid) ?: return completeWriteFailure(completion, "Invalid GATT characteristic")
        if (profile.writeCharacteristicUuid != null && profile.writeCharacteristicUuid != uuid) {
            return completeWriteFailure(completion, "Bluetooth GATT write characteristic is not configured")
        }
        val current = connection
        val gatt = current?.gatt
        if (current == null || !current.servicesReady || gatt == null) {
            return completeWriteFailure(completion, "Bluetooth GATT is not ready")
        }
        if (pendingWrite != null) {
            return completeWriteFailure(completion, "A Bluetooth GATT write is already in flight")
        }
        val pending = PendingWrite(current.requestGeneration, uuid, completion)
        pendingWrite = pending
        val accepted = try {
            gatt.writeCharacteristic(profile.serviceUuid, uuid, payload.clone(), profile.writeType)
        } catch (_: Exception) {
            false
        }
        if (!accepted && pendingWrite === pending) {
            pendingWrite = null
            completion(Result.failure(IllegalStateException("Bluetooth GATT write could not start")))
        }
        accepted
    }

    override fun read(
        characteristicUuid: String,
        completion: (Result<ByteArray>) -> Unit,
    ): Boolean = synchronized(lock) {
        val uuid = parseUuid(characteristicUuid) ?: return completeReadFailure(completion, "Invalid GATT characteristic")
        if (profile.readCharacteristicUuid != null && profile.readCharacteristicUuid != uuid) {
            return completeReadFailure(completion, "Bluetooth GATT read characteristic is not configured")
        }
        val current = connection
        val gatt = current?.gatt
        if (current == null || !current.servicesReady || gatt == null) {
            return completeReadFailure(completion, "Bluetooth GATT is not ready")
        }
        if (pendingRead != null) {
            return completeReadFailure(completion, "A Bluetooth GATT read is already in flight")
        }
        val pending = PendingRead(current.requestGeneration, uuid, completion)
        pendingRead = pending
        val accepted = try {
            gatt.readCharacteristic(profile.serviceUuid, uuid)
        } catch (_: Exception) {
            false
        }
        if (!accepted && pendingRead === pending) {
            pendingRead = null
            completion(Result.failure(IllegalStateException("Bluetooth GATT read could not start")))
        }
        accepted
    }

    /** Releases callbacks and the GATT link. Safe to call repeatedly. */
    fun close() {
        synchronized(lock) {
            if (mutableLifecycle == BleAdapterLifecycleState.DESTROYED) return
            mutableLifecycle = BleAdapterLifecycleState.DESTROYED
            stopDiscoveryLocked()
            resetConnectionLocked(unexpected = false, reason = "Bluetooth adapter is closed")
        }
    }

    private fun stopDiscoveryLocked() {
        val current = discovery ?: return
        discovery = null
        current.timeout?.cancel()
        runCatching { platform.stopDiscovery(current.callback) }
        emit(BleTransportEvent.DiscoveryStopped)
    }

    private fun resetConnectionLocked(unexpected: Boolean, reason: String) {
        val current = connection ?: return
        connection = null
        current.timeout?.cancel()
        completePendingReadLocked(current.requestGeneration, "Bluetooth GATT connection closed")
        completePendingWriteLocked(current.requestGeneration, "Bluetooth GATT connection closed")
        // Keep this order: a BluetoothGatt is disconnected before it is closed,
        // and the reference is then reset so old callbacks cannot be accepted.
        current.gatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        emit(
            BleTransportEvent.Disconnected(
                device = current.device,
                reason = reason,
                unexpected = unexpected,
                linkGeneration = current.linkGeneration,
                requestGeneration = current.requestGeneration,
            ),
        )
    }

    private fun failConnectionLocked(current: ConnectionContext, reason: String) {
        if (connection?.requestGeneration != current.requestGeneration ||
            connection?.linkGeneration != current.linkGeneration
        ) return
        connection = null
        current.timeout?.cancel()
        completePendingReadLocked(current.requestGeneration, "Bluetooth GATT connection failed")
        completePendingWriteLocked(current.requestGeneration, "Bluetooth GATT connection failed")
        current.gatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        emit(
            BleTransportEvent.ConnectionFailed(
                device = current.device,
                reason = reason,
                linkGeneration = current.linkGeneration,
                requestGeneration = current.requestGeneration,
            ),
        )
    }

    private fun completePendingReadLocked(request: Long, reason: String) {
        val pending = pendingRead ?: return
        if (pending.requestGeneration != request) return
        pendingRead = null
        pending.completion(Result.failure(IllegalStateException(reason)))
    }

    private fun completePendingWriteLocked(request: Long, reason: String) {
        val pending = pendingWrite ?: return
        if (pending.requestGeneration != request) return
        pendingWrite = null
        pending.completion(Result.failure(IllegalStateException(reason)))
    }

    private fun completeWriteFailure(completion: (Result<Unit>) -> Unit, reason: String): Boolean {
        completion(Result.failure(IllegalStateException(reason)))
        return false
    }

    private fun completeReadFailure(completion: (Result<ByteArray>) -> Unit, reason: String): Boolean {
        completion(Result.failure(IllegalStateException(reason)))
        return false
    }

    private fun parseUuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

    private fun emit(event: BleTransportEvent) {
        // This method intentionally has no raw-byte/string payload path.
        listener?.onTransportEvent(event)
    }

    private inner class DiscoveryCallback(
        private val generation: Long,
    ) : BlePlatformScanCallback {
        override fun onScanResult(deviceId: String, name: String?, serviceUuids: Set<String>) {
            synchronized(lock) {
                if (discovery?.generation != generation || mutableLifecycle != BleAdapterLifecycleState.FOREGROUND) return
                val safeName = name?.takeIf(String::isNotBlank) ?: deviceId
                emit(
                    BleTransportEvent.DeviceFound(
                        BleDiscoveredDevice(
                            device = ScannerDevice(deviceId, safeName),
                            serviceUuids = serviceUuids,
                        ),
                    ),
                )
            }
        }

        override fun onScanFailed() {
            synchronized(lock) {
                if (discovery?.generation != generation) return
                stopDiscoveryLocked()
                emit(BleTransportEvent.AvailabilityChanged(BleAvailability.Failed("Bluetooth discovery failed")))
            }
        }
    }

    private inner class ConnectionCallback(
        private val request: Long,
        private val link: Long,
    ) : BlePlatformGattCallback {
        private fun current(): ConnectionContext? = connection?.takeIf {
            it.requestGeneration == request && it.linkGeneration == link
        }

        override fun onConnectionStateChanged(gatt: BlePlatformGatt, status: Int, connected: Boolean) {
            synchronized(lock) {
                val current = current() ?: run {
                    runCatching { gatt.close() }
                    return
                }
                if (current.gatt == null) current.gatt = gatt
                if (!connected) {
                    resetConnectionLocked(
                        unexpected = true,
                        reason = if (status == GATT_SUCCESS) "Scanner disconnected" else "Bluetooth GATT disconnected",
                    )
                    return
                }
                if (status != GATT_SUCCESS) {
                    failConnectionLocked(current, "Bluetooth GATT connection failed")
                    return
                }
                val accepted = runCatching { gatt.discoverServices() }.getOrDefault(false)
                if (!accepted) failConnectionLocked(current, "Bluetooth service discovery could not start")
            }
        }

        override fun onServicesDiscovered(gatt: BlePlatformGatt, status: Int) {
            synchronized(lock) {
                val current = current() ?: run {
                    runCatching { gatt.close() }
                    return
                }
                if (status != GATT_SUCCESS) {
                    failConnectionLocked(current, "Bluetooth service discovery failed")
                    return
                }
                val endpointsPresent = listOfNotNull(
                    profile.readCharacteristicUuid,
                    profile.writeCharacteristicUuid,
                    profile.notifyCharacteristicUuid,
                ).all { characteristic ->
                    runCatching {
                        gatt.hasCharacteristic(profile.serviceUuid, characteristic)
                    }.getOrDefault(false)
                }
                if (!endpointsPresent) {
                    failConnectionLocked(current, "Bluetooth scanner service is incomplete")
                    return
                }
                current.gatt = gatt
                current.servicesReady = true
                val notifyUuid = profile.notifyCharacteristicUuid
                if (notifyUuid == null) {
                    publishConnectedLocked(current)
                    return
                }
                val descriptorUuid = runCatching {
                    gatt.notificationDescriptorUuid(
                        serviceUuid = profile.serviceUuid,
                        characteristicUuid = notifyUuid,
                        requestedDescriptorUuid = profile.notificationDescriptorUuid,
                    )
                }.getOrNull()
                if (profile.notificationDescriptorUuid != null && descriptorUuid == null) {
                    failConnectionLocked(current, "Bluetooth scanner notification descriptor is missing")
                    return
                }
                current.notificationDescriptorUuid = descriptorUuid
                current.notificationEnablePending = descriptorUuid != null
                val enabled = runCatching {
                    gatt.enableNotifications(
                        serviceUuid = profile.serviceUuid,
                        characteristicUuid = notifyUuid,
                        descriptorUuid = profile.notificationDescriptorUuid,
                        enableValue = profile.notificationEnableValue,
                    )
                }.getOrDefault(false)
                if (!enabled) {
                    current.notificationEnablePending = false
                    failConnectionLocked(current, "Bluetooth scanner notifications could not start")
                } else if (descriptorUuid == null) {
                    // A platform may configure notifications locally without a
                    // descriptor callback. In that case the link is usable now.
                    current.notificationEnablePending = false
                    publishConnectedLocked(current)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BlePlatformGatt, descriptorUuid: UUID, status: Int) {
            synchronized(lock) {
                val current = current() ?: return
                if (!current.notificationEnablePending ||
                    current.notificationDescriptorUuid != descriptorUuid
                ) return
                current.notificationEnablePending = false
                if (status == GATT_SUCCESS) {
                    publishConnectedLocked(current)
                } else {
                    failConnectionLocked(current, "Bluetooth scanner notifications could not start")
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BlePlatformGatt,
            characteristicUuid: UUID,
            status: Int,
            value: ByteArray,
        ) {
            synchronized(lock) {
                val current = current() ?: return
                val pending = pendingRead ?: return
                if (pending.requestGeneration != current.requestGeneration ||
                    pending.characteristicUuid != characteristicUuid
                ) return
                pendingRead = null
                if (status == GATT_SUCCESS) {
                    pending.completion(Result.success(value.clone()))
                } else {
                    pending.completion(Result.failure(IllegalStateException("Bluetooth GATT read failed")))
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BlePlatformGatt,
            characteristicUuid: UUID,
            status: Int,
        ) {
            synchronized(lock) {
                val current = current() ?: return
                val pending = pendingWrite ?: return
                if (pending.requestGeneration != current.requestGeneration ||
                    pending.characteristicUuid != characteristicUuid
                ) return
                pendingWrite = null
                if (status == GATT_SUCCESS) {
                    pending.completion(Result.success(Unit))
                } else {
                    pending.completion(Result.failure(IllegalStateException("Bluetooth GATT write failed")))
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BlePlatformGatt,
            characteristicUuid: UUID,
            value: ByteArray,
        ) {
            synchronized(lock) {
                val current = current() ?: return
                if (!current.servicesReady || profile.notifyCharacteristicUuid != characteristicUuid) return
                val decoded = runCatching {
                    profile.notificationDecoder?.decode(value.clone())
                }.getOrNull() ?: return
                val payload = ScanPayload(
                    value = decoded.value,
                    source = InputSource.BLUETOOTH,
                    format = decoded.format,
                    timestampMillis = decoded.timestampMillis.takeIf { it != 0L } ?: nowMillis(),
                )
                // Only the typed payload leaves this boundary. The raw value
                // is never included in a diagnostic or failure message.
                emit(
                    BleTransportEvent.ScanReceived(
                        payload = payload,
                        device = current.device,
                        linkGeneration = current.linkGeneration,
                        requestGeneration = current.requestGeneration,
                    ),
                )
            }
        }

        private fun publishConnectedLocked(current: ConnectionContext) {
            if (connection !== current || !current.servicesReady || current.notificationEnablePending) return
            current.timeout?.cancel()
            emit(
                BleTransportEvent.Connected(
                    device = current.device,
                    linkGeneration = current.linkGeneration,
                    requestGeneration = current.requestGeneration,
                ),
            )
        }
    }
}

/** Alias emphasizing that this class implements the transport boundary. */
typealias AndroidBleAdapter = AndroidBleTransport

/** Short name for hosts that do not need to distinguish platform profiles. */
typealias BleProtocolProfile = AndroidBleProtocolProfile
