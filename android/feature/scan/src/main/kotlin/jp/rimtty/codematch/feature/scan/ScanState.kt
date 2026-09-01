package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload

/** The logical stages of a comparison session. */
enum class ScanPhase {
    IDLE,
    WAITING_QR,
    WAITING_CODE_128,
    RESULT,
    ;

    companion object {
        val Idle: ScanPhase get() = IDLE
        val WaitingQr: ScanPhase get() = WAITING_QR
        val WaitingCode128: ScanPhase get() = WAITING_CODE_128
        val Result: ScanPhase get() = RESULT
    }
}

/**
 * Immutable business state. Camera lifetime, Bluetooth connection state and
 * countdown scheduling deliberately live outside this type.
 */
sealed interface ScanState {
    val phase: ScanPhase
    val qrPayload: String?
    val barcodePayload: String?
    val result: MatchResult?
    val matchedCount: Int

    data object Idle : ScanState {
        override val phase: ScanPhase = ScanPhase.IDLE
        override val qrPayload: String? = null
        override val barcodePayload: String? = null
        override val result: MatchResult? = null
        override val matchedCount: Int = 0
    }

    data class WaitingQr(
        override val matchedCount: Int = 0,
    ) : ScanState {
        override val phase: ScanPhase = ScanPhase.WAITING_QR
        override val qrPayload: String? = null
        override val barcodePayload: String? = null
        override val result: MatchResult? = null
    }

    data class WaitingCode128(
        override val qrPayload: String,
        override val matchedCount: Int = 0,
    ) : ScanState {
        override val phase: ScanPhase = ScanPhase.WAITING_CODE_128
        override val barcodePayload: String? = null
        override val result: MatchResult? = null
    }

    data class Result(
        override val qrPayload: String,
        override val barcodePayload: String,
        override val result: MatchResult,
        override val matchedCount: Int = 0,
    ) : ScanState {
        override val phase: ScanPhase = ScanPhase.RESULT
    }

    companion object {
        val idle: ScanState get() = Idle
        fun waitingQr(matchedCount: Int = 0): ScanState = WaitingQr(matchedCount)
        fun waitingCode128(qrPayload: String, matchedCount: Int = 0): ScanState =
            WaitingCode128(qrPayload, matchedCount)
    }
}

// Top-level aliases keep the state machine pleasant to use from tests and
// from a future ViewModel without exposing platform-specific UI types.
typealias Idle = ScanState.Idle
typealias WaitingQr = ScanState.WaitingQr
typealias WaitingForQr = ScanState.WaitingQr
typealias WaitingCode128 = ScanState.WaitingCode128
typealias WaitingForCode128 = ScanState.WaitingCode128
typealias ResultState = ScanState.Result

/** Immutable reducer state, including settings that affect countdowns. */
data class ScanSessionState(
    val scan: ScanState = ScanState.Idle,
    val autoAdvanceEnabled: Boolean = false,
    val autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
    val autoAdvanceSecondsRemaining: Int? = null,
    val inputSource: InputSource = InputSource.CAMERA,
    /** Existing matches restored by the session repository before start. */
    val initialMatchedCount: Int = 0,
) {
    val state: ScanState get() = scan
    val phase: ScanPhase get() = scan.phase
    val step: ScanPhase get() = scan.phase
    val qrPayload: String? get() = scan.qrPayload
    val barcodePayload: String? get() = scan.barcodePayload
    val result: MatchResult? get() = scan.result
    val matchResult: MatchResult? get() = scan.result
    val matchedCount: Int get() = scan.matchedCount
    val existingMatchedCount: Int get() = initialMatchedCount
    val expectedFormat: ScanFormat?
        get() = when (scan.phase) {
            ScanPhase.WAITING_QR -> ScanFormat.QR
            ScanPhase.WAITING_CODE_128 -> ScanFormat.CODE_128
            ScanPhase.IDLE, ScanPhase.RESULT -> null
        }
}

typealias ScanReducerState = ScanSessionState
typealias SessionState = ScanSessionState

/** User and scanner events consumed by [ScanReducer]. */
sealed interface ScanEvent {
    data object StartSession : ScanEvent
    data object EndSession : ScanEvent
    data object RereadQr : ScanEvent
    data object ManualNext : ScanEvent
    data object CancelAutoAdvance : ScanEvent
    data object Backgrounded : ScanEvent
    data object Foregrounded : ScanEvent

    data class PayloadReceived(val payload: ScanPayload) : ScanEvent
    data class ScanReceived(val payload: ScanPayload) : ScanEvent
    data class AutoAdvanceTick(val elapsedSeconds: Int = 1) : ScanEvent
    data object AutoAdvanceElapsed : ScanEvent
    data class SetAutoAdvanceEnabled(val enabled: Boolean) : ScanEvent
    data class SetAutoAdvanceDelay(val delay: AutoAdvanceDelay) : ScanEvent

    companion object {
        val Start: ScanEvent get() = StartSession
        val End: ScanEvent get() = EndSession
        val RereadQR: ScanEvent get() = RereadQr
        val Next: ScanEvent get() = ManualNext
        val Background: ScanEvent get() = Backgrounded
        val Foreground: ScanEvent get() = Foregrounded

        fun Scan(payload: ScanPayload): ScanEvent = PayloadReceived(payload)
        fun Payload(payload: ScanPayload): ScanEvent = PayloadReceived(payload)
        fun Tick(elapsedSeconds: Int = 1): ScanEvent = AutoAdvanceTick(elapsedSeconds)
    }
}

typealias ScanAction = ScanEvent

enum class InvalidScanReason {
    SESSION_NOT_STARTED,
    WRONG_ORDER,
    EMPTY_PAYLOAD,
    INVALID_PAYLOAD,
}

/** Side effects are data so platform UI and persistence can handle them later. */
sealed interface ScanEffect {
    data object SessionStarted : ScanEffect
    data object SessionEnded : ScanEffect
    data object ScanAccepted : ScanEffect
    data object StartNextScan : ScanEffect
    data object AutoAdvanceCancelled : ScanEffect
    data object AutoAdvanceCompleted : ScanEffect
    data object StopInput : ScanEffect

    data class ExpectFormat(val format: ScanFormat?) : ScanEffect
    data class ResumeInput(val format: ScanFormat) : ScanEffect
    data class InvalidScan(
        val expectedFormat: ScanFormat?,
        val reason: InvalidScanReason,
    ) : ScanEffect
    data class AutoAdvanceStarted(val seconds: Int) : ScanEffect
    data class CountdownUpdated(val seconds: Int) : ScanEffect
    data class RecordMatch(
        val qrPayload: String,
        val barcodePayload: String,
        val code: String,
        val matchNumber: Int,
    ) : ScanEffect {
        val partNumber: String get() = code
    }
}

typealias RejectScan = ScanEffect.InvalidScan
typealias MatchRecorded = ScanEffect.RecordMatch
typealias ScanCommand = ScanEffect
typealias InputStopped = ScanEffect.StopInput
typealias ResumeExpectedFormat = ScanEffect.ResumeInput

data class ScanReduction(
    val state: ScanSessionState,
    val effects: List<ScanEffect> = emptyList(),
) {
    val newState: ScanSessionState get() = state
    val commands: List<ScanEffect> get() = effects
}
