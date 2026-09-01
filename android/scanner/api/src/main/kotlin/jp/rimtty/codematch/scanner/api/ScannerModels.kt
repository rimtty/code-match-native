package jp.rimtty.codematch.scanner.api

/** The input adapter that produced a scan callback. */
enum class InputSource {
    CAMERA,
    BLUETOOTH,
    ;

    companion object {
        // These aliases make call sites read naturally while keeping the
        // serialized/API values stable and idiomatic for Kotlin.
        val Camera: InputSource get() = CAMERA
        val Bluetooth: InputSource get() = BLUETOOTH
    }
}

/**
 * The two symbologies used by a comparison session.
 *
 * A scanner may physically report either value at any time. The scan reducer
 * is responsible for enforcing the logical QR -> Code 128 order.
 */
enum class ScanFormat {
    QR,
    CODE_128,
    ;

    val isQr: Boolean get() = this == QR

    companion object {
        // Compatibility spellings for adapters that call the second format a
        // barcode or use the compact Code128 spelling.
        val BARCODE: ScanFormat get() = CODE_128
        val CODE128: ScanFormat get() = CODE_128
    }
}

typealias ScanInputSource = InputSource
typealias ScanType = ScanFormat
typealias ExpectedCode = ScanFormat

/** A scanner callback with its source and logical symbology attached. */
data class ScanPayload(
    val value: String,
    val source: InputSource,
    val format: ScanFormat,
    val timestampMillis: Long = 0L,
) {
    /** Allows callers that use `type = ...` terminology to construct a value. */
    constructor(
        value: String,
        type: ScanFormat,
        source: InputSource,
        timestampMillis: Long = 0L,
    ) : this(value, source, type, timestampMillis)

    val rawValue: String get() = value
    val type: ScanFormat get() = format
    val kind: ScanFormat get() = format
    val capturedAtMillis: Long get() = timestampMillis

    companion object {
        fun qr(
            value: String,
            source: InputSource = InputSource.CAMERA,
            timestampMillis: Long = 0L,
        ): ScanPayload = ScanPayload(value, source, ScanFormat.QR, timestampMillis)

        fun code128(
            value: String,
            source: InputSource = InputSource.CAMERA,
            timestampMillis: Long = 0L,
        ): ScanPayload = ScanPayload(value, source, ScanFormat.CODE_128, timestampMillis)

        fun barcode(
            value: String,
            source: InputSource = InputSource.CAMERA,
            timestampMillis: Long = 0L,
        ): ScanPayload = code128(value, source, timestampMillis)
    }
}

/** A discoverable scanner device. */
data class ScannerDevice(
    val id: String,
    val name: String,
) {
    val identifier: String get() = id
}

enum class DiagnosticCategory {
    CONNECTION,
    CONFIGURATION,
    ERROR,
}

typealias DiagnosticKind = DiagnosticCategory

/**
 * A sanitized connection/configuration event.
 *
 * Scan text is intentionally not a field on this model. Implementations must
 * never put scan payloads into diagnostic messages.
 */
data class DiagnosticEvent(
    val category: DiagnosticCategory,
    val message: String,
    val timestampMillis: Long = 0L,
    val sequence: Long = 0L,
) {
    constructor(
        timestamp: Long,
        message: String,
        category: DiagnosticCategory = DiagnosticCategory.CONNECTION,
        sequence: Long = 0L,
    ) : this(category, message, timestamp, sequence)

    val kind: DiagnosticCategory get() = category
    val dateMillis: Long get() = timestampMillis
}

/** Connection/discovery state exposed by every scanner adapter. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Searching : ConnectionState
    data class Connecting(val device: ScannerDevice) : ConnectionState
    data class Connected(val device: ScannerDevice) : ConnectionState
    data class Unavailable(val reason: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState

    val connectedDevice: ScannerDevice?
        get() = when (this) {
            is Connected -> device
            else -> null
        }

    val isConnected: Boolean get() = connectedDevice != null

    companion object {
        val IDLE: ConnectionState get() = Idle
        val SEARCHING: ConnectionState get() = Searching
        val idle: ConnectionState get() = Idle
        val searching: ConnectionState get() = Searching
    }
}

/** Scanner symbology/configuration state. */
sealed interface ConfigurationState {
    data object Unavailable : ConfigurationState
    data object Configuring : ConfigurationState
    data object Ready : ConfigurationState
    data class Failed(val reason: String) : ConfigurationState

    val isReady: Boolean get() = this === Ready

    companion object {
        val UNAVAILABLE: ConfigurationState get() = Unavailable
        val CONFIGURING: ConfigurationState get() = Configuring
        val READY: ConfigurationState get() = Ready
        val unavailable: ConfigurationState get() = Unavailable
        val configuring: ConfigurationState get() = Configuring
        val ready: ConfigurationState get() = Ready
    }
}
