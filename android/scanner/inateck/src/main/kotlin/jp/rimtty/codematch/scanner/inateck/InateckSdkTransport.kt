package jp.rimtty.codematch.scanner.inateck

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.BleAvailability
import jp.rimtty.codematch.scanner.ble.BleDiscoveredDevice
import jp.rimtty.codematch.scanner.ble.BleScanCallbackDecoder
import jp.rimtty.codematch.scanner.ble.BleTransport
import jp.rimtty.codematch.scanner.ble.BleTransportEvent
import jp.rimtty.codematch.scanner.ble.BleTransportListener
import jp.rimtty.codematch.scanner.ble.BleTransportReadiness

/**
 * Maps the official Inateck SDK's high-level operations to the BLE safety core.
 *
 * `read` and `write` are logical settings operations here, not raw GATT
 * characteristic access. The SDK owns authentication, command framing, and
 * its FF00 endpoints. This transport never logs vendor replies or scan bytes.
 */
internal class InateckSdkTransport(
    private val gateway: InateckSdkGateway,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val scanDecoder: BleScanCallbackDecoder =
        InateckScanCallbackDecoder,
) : BleTransport {
    private var discovering = false
    private var pendingDevice: ScannerDevice? = null
    private var activeDevice: ScannerDevice? = null
    private var discoveryGeneration = 0L
    private var connectionGeneration = 0L
    private var linkGeneration = 0L
    private var closed = false

    override var listener: BleTransportListener? = null

    override val availability: BleAvailability
        get() = gateway.readiness.availability

    override val readiness: BleTransportReadiness
        get() = gateway.readiness

    val isLinkActive: Boolean
        get() = activeDevice != null || pendingDevice != null

    override fun startDiscovery(): Boolean {
        if (closed || discovering || readiness.failureReason(forConnection = false) != null) {
            return false
        }
        discovering = true
        val discovery = ++discoveryGeneration
        listener?.onTransportEvent(BleTransportEvent.DiscoveryStarted)
        val accepted = gateway.startDiscovery(
            onDevice = { sdkDevice ->
                if (!closed && discovering && discovery == discoveryGeneration) {
                    listener?.onTransportEvent(
                        BleTransportEvent.DeviceFound(
                            BleDiscoveredDevice(
                                ScannerDevice(sdkDevice.id, sdkDevice.name),
                            ),
                        ),
                    )
                }
            },
            onFinished = {
                if (!closed && discovering && discovery == discoveryGeneration) {
                    discovering = false
                    listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
                }
            },
        )
        if (!accepted && discovering) {
            discovering = false
            discoveryGeneration++
            listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
        }
        return accepted
    }

    override fun stopDiscovery(): Boolean {
        if (closed || !discovering) return false
        discovering = false
        discoveryGeneration++
        val accepted = gateway.stopDiscovery()
        listener?.onTransportEvent(BleTransportEvent.DiscoveryStopped)
        return accepted
    }

    override fun connect(device: ScannerDevice): Boolean {
        if (closed || activeDevice != null || pendingDevice != null ||
            readiness.failureReason(forConnection = true) != null
        ) {
            return false
        }
        if (discovering) stopDiscovery()
        val request = ++connectionGeneration
        val link = ++linkGeneration
        pendingDevice = device
        val accepted = gateway.connect(
            deviceId = device.id,
            onScanBytes = { bytes -> acceptScanBytes(device, request, link, bytes) },
            onDisconnected = { unexpected ->
                if (!closed && request == connectionGeneration) {
                    activeDevice = null
                    listener?.onTransportEvent(
                        BleTransportEvent.Disconnected(
                            device = device,
                            unexpected = unexpected,
                            requestGeneration = request,
                            linkGeneration = link,
                        ),
                    )
                }
            },
            completion = { result ->
                if (closed || request != connectionGeneration) return@connect
                if (result.isSuccess) {
                    pendingDevice = null
                    activeDevice = device
                    listener?.onTransportEvent(
                        BleTransportEvent.Connected(
                            device = device,
                            requestGeneration = request,
                            linkGeneration = link,
                        ),
                    )
                } else {
                    pendingDevice = null
                    activeDevice = null
                    listener?.onTransportEvent(
                        BleTransportEvent.ConnectionFailed(
                            device = device,
                            reason = "Inateck scanner connection failed",
                            requestGeneration = request,
                            linkGeneration = link,
                        ),
                    )
                }
            },
        )
        if (!accepted) {
            pendingDevice = null
            connectionGeneration++
        }
        return accepted
    }

    override fun disconnect(device: ScannerDevice): Boolean {
        val target = (activeDevice ?: pendingDevice)?.takeIf { it.id == device.id } ?: return false
        val request = connectionGeneration
        val link = linkGeneration
        val accepted = gateway.disconnect(target.id) completion@{ result ->
            if (!closed && request == connectionGeneration) {
                if (result.isFailure) return@completion
                activeDevice = null
                pendingDevice = null
                connectionGeneration++
                listener?.onTransportEvent(
                    BleTransportEvent.Disconnected(
                        device = device,
                        unexpected = false,
                        requestGeneration = request,
                        linkGeneration = link,
                    ),
                )
            }
        }
        return accepted
    }

    override fun read(
        characteristicUuid: String,
        completion: (Result<ByteArray>) -> Unit,
    ): Boolean {
        if (characteristicUuid != INATECK_SETTINGS_ENDPOINT) {
            return completeReadFailure(completion)
        }
        val device = activeDevice ?: return completeReadFailure(completion)
        val request = connectionGeneration
        return gateway.readSettings(device.id) { result ->
            if (closed || request != connectionGeneration) return@readSettings
            completion(
                result.mapCatching { settings ->
                    settingsEnvelope(settings).toByteArray(StandardCharsets.UTF_8)
                },
            )
        }
    }

    override fun write(
        characteristicUuid: String,
        payload: ByteArray,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        if (characteristicUuid != INATECK_SETTINGS_ENDPOINT) {
            return completeWriteFailure(completion)
        }
        val device = activeDevice ?: return completeWriteFailure(completion)
        val command = strictUtf8(payload) ?: return completeWriteFailure(completion)
        val request = connectionGeneration
        return gateway.writeSettings(device.id, command) { result ->
            if (closed || request != connectionGeneration) return@writeSettings
            completion(result)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        discovering = false
        discoveryGeneration++
        pendingDevice = null
        activeDevice = null
        connectionGeneration++
        listener = null
        gateway.close()
    }

    private fun acceptScanBytes(
        device: ScannerDevice,
        request: Long,
        link: Long,
        bytes: ByteArray,
    ) {
        if (closed || request != connectionGeneration || activeDevice?.id != device.id) return
        val callbackValue = strictUtf8(bytes) ?: return
        val event = BleTransportEvent.ScanReceived.fromRawCallback(
            callbackValue = callbackValue,
            source = InputSource.BLUETOOTH,
            // FF01 does not carry a trustworthy symbology label. QR is only
            // an adapter placeholder; BleScannerSessionCoordinator replaces
            // it with the authoritative logical step before delivery.
            format = ScanFormat.QR,
            timestampMillis = nowMillis(),
            device = device,
            requestGeneration = request,
            linkGeneration = link,
            decoder = scanDecoder,
        ) ?: return
        listener?.onTransportEvent(event)
    }

    private fun settingsEnvelope(settings: List<Map<String, String>>): String {
        require(settings.isNotEmpty()) { "Inateck settings inventory is empty" }
        val root = JsonObject()
        root.add("data", Gson().toJsonTree(settings))
        return Gson().toJson(root)
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun completeReadFailure(completion: (Result<ByteArray>) -> Unit): Boolean {
        completion(Result.failure(IllegalStateException("Inateck scanner is not connected")))
        return false
    }

    private fun completeWriteFailure(completion: (Result<Unit>) -> Unit): Boolean {
        completion(Result.failure(IllegalStateException("Inateck scanner is not connected")))
        return false
    }
}
