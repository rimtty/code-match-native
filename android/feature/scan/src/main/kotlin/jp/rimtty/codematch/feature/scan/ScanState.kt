package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.MoltenQrRecord
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.Destination
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

/**
 * One box already recorded as a match in the active session.
 *
 * [identity] is the destination-aware box key: the Sawai slip QR identifies its
 * own box, while a Molten slip repeats for every box of the part and needs the
 * product tag as well. The Molten-only fields carry the slip's delivery number
 * and pack quantity so the box number and the cumulative quantity can be
 * derived without re-parsing every stored payload.
 */
data class RecordedBox(
    val identity: String,
    val code: String,
    val destination: Destination?,
    val deliveryNumber: String? = null,
    val packQuantity: Int? = null,
) {
    companion object {
        /**
         * Build a box from the payloads that produced a match, or null when
         * the pair has no box identity (an unknown QR, or a Molten slip
         * without its tag). [code] defaults to the formatted part number the
         * reducer would have recorded.
         */
        fun fromPayloads(
            qrPayload: String,
            barcodePayload: String?,
            code: String? = null,
        ): RecordedBox? {
            val identity = CodeMatcher.boxIdentity(qrPayload, barcodePayload) ?: return null
            val destination = CodeMatcher.detectDestination(qrPayload)
            val molten = if (destination == Destination.MOLTEN) {
                MoltenQrRecord.parse(qrPayload)
            } else {
                null
            }
            return RecordedBox(
                identity = identity,
                code = code?.trim()?.takeIf { it.isNotEmpty() }
                    ?: formattedPartNumber(qrPayload, barcodePayload),
                destination = destination,
                deliveryNumber = molten?.deliveryNumber,
                packQuantity = molten?.packQuantity,
            )
        }

        private fun formattedPartNumber(qrPayload: String, barcodePayload: String?): String {
            val part = barcodePayload?.let(CodeMatcher::partNumberFromBarcode)
                ?: CodeMatcher.partNumberFromQr(qrPayload)
                ?: qrPayload
            return CodeMatcher.formatPartNumber(part)
        }
    }
}

/** Per-delivery-number progress shown on a Molten match result. */
data class MoltenBoxSummary(
    val deliveryNumber: String,
    val boxNumber: Int,
    val cumulativeQuantity: Int,
)

/** Immutable reducer state, including settings that affect countdowns. */
data class ScanSessionState(
    val scan: ScanState = ScanState.Idle,
    val autoAdvanceEnabled: Boolean = false,
    val autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
    val autoAdvanceSecondsRemaining: Int? = null,
    val inputSource: InputSource = InputSource.CAMERA,
    /** Existing matches restored by the session repository before start. */
    val initialMatchedCount: Int = 0,
    /**
     * Destination locked by the first accepted QR of the session.
     *
     * Only [ScanEvent.EndSession] clears it: a mismatch, a QR reread, and the
     * manual next action all stay inside the same session, so a slip of the
     * other destination must keep being rejected until the operator ends it.
     */
    val destination: Destination? = null,
    /** Boxes already recorded in this session, restored ones included. */
    val recordedBoxes: List<RecordedBox> = emptyList(),
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

    /** Box keys already recorded in this session; the duplicate rule's input. */
    val matchedBoxIdentities: Set<String>
        get() = recordedBoxes.mapTo(linkedSetOf()) { it.identity }

    /**
     * Boxes and cumulative pack quantity recorded so far for one Molten
     * delivery number. Sawai boxes are counted per part number instead and
     * never contribute here.
     */
    fun moltenSummary(deliveryNumber: String): MoltenBoxSummary {
        val boxes = recordedBoxes.filter {
            it.destination == Destination.MOLTEN && it.deliveryNumber == deliveryNumber
        }
        return MoltenBoxSummary(
            deliveryNumber = deliveryNumber,
            boxNumber = boxes.size,
            cumulativeQuantity = boxes.sumOf { it.packQuantity ?: 0 },
        )
    }

    /**
     * The summary for the box shown on a Molten match result, or null for any
     * other state. [recordedBoxes] already contains the box just recorded, so
     * the numbers describe the visible result rather than the previous one.
     */
    val moltenResultSummary: MoltenBoxSummary?
        get() {
            val current = scan as? ScanState.Result ?: return null
            if (current.result != MatchResult.MATCH) return null
            val record = MoltenQrRecord.parse(current.qrPayload) ?: return null
            return moltenSummary(record.deliveryNumber)
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
    INCOMPLETE_QR_PAYLOAD,
    OVERLONG_QR_PAYLOAD,
    INVALID_PAYLOAD,

    /** A valid QR of the destination this session is not locked to. */
    WRONG_DESTINATION,
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
        /** Character count only; the scanned value never enters diagnostics. */
        val observedLength: Int? = null,
    ) : ScanEffect
    data class AutoAdvanceStarted(val seconds: Int) : ScanEffect
    data class CountdownUpdated(val seconds: Int) : ScanEffect
    data class RecordMatch(
        val qrPayload: String,
        val barcodePayload: String,
        val code: String,
        val matchNumber: Int,
        /** Destination this session is locked to; persisted with the session. */
        val destination: Destination,
        /**
         * Box number inside the session: boxes of the same delivery number for
         * [Destination.MOLTEN], boxes of the same part number for
         * [Destination.SAWAI].
         */
        val boxNumber: Int,
        /** Molten only: the slip's delivery number. */
        val deliveryNumber: String? = null,
        /** Molten only: pack quantity summed over the delivery number's boxes. */
        val cumulativeQuantity: Int? = null,
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
