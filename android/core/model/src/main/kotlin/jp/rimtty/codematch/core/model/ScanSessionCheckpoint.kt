package jp.rimtty.codematch.core.model

/**
 * The durable logical stages that can be resumed after process recreation.
 *
 * IDLE is intentionally not represented: an idle session has no useful scan
 * work to resume, and a checkpoint is removed when the session ends.
 */
enum class ScanCheckpointPhase {
    WAITING_QR,
    WAITING_CODE_128,
    RESULT,
}

/** Input source selected when the checkpoint was written. */
enum class ScanCheckpointInputSource {
    CAMERA,
    BLUETOOTH,
}

/**
 * Versioned, app-private state for one active scan session.
 *
 * This is deliberately limited to the logical state needed to render and
 * resume the flow. Camera frames, raw scanner frames, diagnostics, and other
 * transport state are never part of the checkpoint. QR/barcode values are
 * included only because a comparison cannot be resumed without the accepted
 * values already held by the active scan state.
 *
 * [destination] is nullable and purely additive, so [CURRENT_VERSION] stays 1
 * on purpose: bumping it would make [isSupportedAndValid] discard every
 * checkpoint written by an older build and silently restart those sessions at
 * Waiting QR. A null destination simply means the lock was not recorded, and
 * the scan state derives it again from the accepted QR or the session's boxes.
 */
data class ScanSessionCheckpoint(
    val sessionId: String,
    val phase: ScanCheckpointPhase,
    val qrPayload: String? = null,
    val barcodePayload: String? = null,
    val result: MatchResult? = null,
    val matchedCount: Int = 0,
    val inputSource: ScanCheckpointInputSource = ScanCheckpointInputSource.CAMERA,
    /** Distinguishes an explicit camera choice from an automatic fallback. */
    val cameraWasSelectedByUser: Boolean = false,
    val version: Int = CURRENT_VERSION,
    /** Destination locked by the session's first accepted QR, if known yet. */
    val destination: Destination? = null,
) {
    /**
     * Reject malformed or future records before they reach the state machine.
     * Keeping the validation here lets every persistence implementation share
     * the same safe fallback behavior.
     */
    fun isSupportedAndValid(): Boolean {
        if (version != CURRENT_VERSION || sessionId.isBlank() || matchedCount < 0) {
            return false
        }
        return when (phase) {
            ScanCheckpointPhase.WAITING_QR ->
                qrPayload == null && barcodePayload == null && result == null

            ScanCheckpointPhase.WAITING_CODE_128 ->
                !qrPayload.isNullOrBlank() && barcodePayload == null && result == null

            ScanCheckpointPhase.RESULT ->
                !qrPayload.isNullOrBlank() &&
                    !barcodePayload.isNullOrBlank() &&
                    result != null
        }
    }

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}
