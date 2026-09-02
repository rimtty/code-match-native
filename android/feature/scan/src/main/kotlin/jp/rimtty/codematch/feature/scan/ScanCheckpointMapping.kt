package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.ScanCheckpointInputSource
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.InputSource

/** Convert the in-memory state into the small durable checkpoint contract. */
fun ScanSessionState.toScanSessionCheckpoint(
    sessionId: String,
    cameraWasSelectedByUser: Boolean = false,
): ScanSessionCheckpoint? {
    val checkpoint = when (val current = scan) {
        ScanState.Idle -> return null
        is ScanState.WaitingQr -> ScanSessionCheckpoint(
            sessionId = sessionId,
            phase = ScanCheckpointPhase.WAITING_QR,
            matchedCount = current.matchedCount,
            inputSource = inputSource.toCheckpointSource(),
            cameraWasSelectedByUser = cameraWasSelectedByUser,
        )

        is ScanState.WaitingCode128 -> ScanSessionCheckpoint(
            sessionId = sessionId,
            phase = ScanCheckpointPhase.WAITING_CODE_128,
            qrPayload = current.qrPayload,
            matchedCount = current.matchedCount,
            inputSource = inputSource.toCheckpointSource(),
            cameraWasSelectedByUser = cameraWasSelectedByUser,
        )

        is ScanState.Result -> ScanSessionCheckpoint(
            sessionId = sessionId,
            phase = ScanCheckpointPhase.RESULT,
            qrPayload = current.qrPayload,
            barcodePayload = current.barcodePayload,
            result = current.result,
            matchedCount = current.matchedCount,
            inputSource = inputSource.toCheckpointSource(),
            cameraWasSelectedByUser = cameraWasSelectedByUser,
        )
    }
    return checkpoint.takeIf { it.isSupportedAndValid() }
}

/**
 * Rehydrate a coordinator state without replaying a scan event.
 *
 * Countdown is intentionally not restored. A process may have been stopped
 * for an arbitrary amount of time, so resuming at a terminal result must wait
 * for an explicit user action rather than auto-advancing unexpectedly.
 */
fun ScanSessionCheckpoint.toScanSessionState(
    autoAdvanceEnabled: Boolean,
    autoAdvanceDelay: jp.rimtty.codematch.core.model.AutoAdvanceDelay,
): ScanSessionState? {
    if (!isSupportedAndValid()) return null
    val restoredScan = when (phase) {
        ScanCheckpointPhase.WAITING_QR -> ScanState.WaitingQr(matchedCount)
        ScanCheckpointPhase.WAITING_CODE_128 ->
            ScanState.WaitingCode128(qrPayload = qrPayload!!, matchedCount = matchedCount)

        ScanCheckpointPhase.RESULT -> ScanState.Result(
            qrPayload = qrPayload!!,
            barcodePayload = barcodePayload!!,
            result = result!!,
            matchedCount = matchedCount,
        )
    }
    return ScanSessionState(
        scan = restoredScan,
        autoAdvanceEnabled = autoAdvanceEnabled,
        autoAdvanceDelay = autoAdvanceDelay,
        // Never restore an in-flight countdown across process recreation.
        autoAdvanceSecondsRemaining = null,
        inputSource = inputSource.toScannerSource(),
        initialMatchedCount = matchedCount,
    )
}

private fun InputSource.toCheckpointSource(): ScanCheckpointInputSource = when (this) {
    InputSource.CAMERA -> ScanCheckpointInputSource.CAMERA
    InputSource.BLUETOOTH -> ScanCheckpointInputSource.BLUETOOTH
}

private fun ScanCheckpointInputSource.toScannerSource(): InputSource = when (this) {
    ScanCheckpointInputSource.CAMERA -> InputSource.CAMERA
    ScanCheckpointInputSource.BLUETOOTH -> InputSource.BLUETOOTH
}
