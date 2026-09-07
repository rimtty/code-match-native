package jp.rimtty.codematch.core.model

/**
 * One line of the on-device scan log.
 *
 * Unlike the Bluetooth diagnostic log — which never carries a scanned value —
 * this record deliberately keeps the raw QR and Code 128 payloads so a field
 * problem can be reproduced from the exact bytes the scanner delivered. The
 * log is stored on the device only and leaves it exclusively when the operator
 * shares or saves it from Settings.
 *
 * The field set, the string vocabularies below, and the exported JSON Lines
 * document are a cross-platform contract shared with the iOS `ScanLogEvent`;
 * change them on both platforms at once.
 *
 * [sessionId] is filled in by the host that owns the active session, so the
 * producer of an event does not have to know it.
 */
data class ScanLogEvent(
    val atEpochMillis: Long,
    val sessionId: String?,
    /** [ScanLogSource]: which input produced the event. */
    val source: String,
    /** [ScanLogStep]: the phase the payload was handled in. */
    val step: String,
    /** [ScanLogEventKind]. */
    val event: String,
    /** [ScanLogReason]; set for a rejection, null otherwise. */
    val reason: String? = null,
    val destination: Destination? = null,
    val qrPayload: String? = null,
    val barcodePayload: String? = null,
    val code: String? = null,
    val boxNumber: Int? = null,
    /** The message shown to the operator; Android leaves this null. */
    val message: String? = null,
)

/** Values of [ScanLogEvent.source]. */
object ScanLogSource {
    const val CAMERA: String = "camera"
    const val BLUETOOTH: String = "bluetooth"
}

/** Values of [ScanLogEvent.step]. */
object ScanLogStep {
    const val QR: String = "qr"
    const val BARCODE: String = "barcode"
    const val RESULT: String = "result"

    /** Session lifecycle events, which belong to no scan step. */
    const val NONE: String = "none"
}

/** Values of [ScanLogEvent.event]. */
object ScanLogEventKind {
    const val SESSION_START: String = "session_start"
    const val SESSION_END: String = "session_end"
    const val QR_ACCEPTED: String = "qr_accepted"
    const val BARCODE_CANDIDATE: String = "barcode_candidate"
    const val BARCODE_ACCEPTED: String = "barcode_accepted"
    const val MATCH: String = "match"
    const val MISMATCH: String = "mismatch"
    const val DUPLICATE: String = "duplicate"
    const val REJECTED: String = "rejected"
}

/**
 * Values of [ScanLogEvent.reason].
 *
 * The two apps reject on partly different grounds, so each platform emits the
 * subset its own scan flow can produce.
 */
object ScanLogReason {
    const val INVALID_FORMAT: String = "invalid_format"
    const val WRONG_ORDER: String = "wrong_order"
    const val WRONG_DESTINATION: String = "wrong_destination"
    const val WRONG_SYMBOLOGY: String = "wrong_symbology"
    const val RESULT_PENDING: String = "result_pending"
    const val INCOMPLETE: String = "incomplete"
    const val OVERLONG: String = "overlong"
    const val INVALID: String = "invalid"
    const val EMPTY: String = "empty"
    const val SESSION_NOT_STARTED: String = "session_not_started"
    const val SOURCE_MISMATCH: String = "source_mismatch"
}
