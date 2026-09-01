package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload

/**
 * Deterministic, platform-free reducer for one QR -> Code 128 comparison.
 *
 * No timer, camera, Bluetooth, database, or coroutine is created here. A
 * caller supplies [AutoAdvanceTick] events from its lifecycle-aware scheduler,
 * which makes countdown behavior straightforward to test with virtual time.
 */
class ScanReducer(
    private val compare: (qrPayload: String, barcodePayload: String) -> MatchResult =
        { qrPayload, barcodePayload -> CodeMatcher.compare(qrPayload, barcodePayload) },
) {
    fun reduce(
        current: ScanSessionState,
        event: ScanEvent,
    ): ScanReduction = when (event) {
        ScanEvent.StartSession -> startSession(current)
        ScanEvent.EndSession -> endSession(current)
        ScanEvent.RereadQr -> rereadQr(current)
        ScanEvent.ManualNext -> manualNext(current)
        ScanEvent.CancelAutoAdvance -> cancelAutoAdvance(current)
        ScanEvent.Backgrounded -> cancelForBackground(current)
        ScanEvent.Foregrounded -> resumeAfterForeground(current)
        is ScanEvent.PayloadReceived -> receivePayload(current, event.payload)
        is ScanEvent.ScanReceived -> receivePayload(current, event.payload)
        is ScanEvent.AutoAdvanceTick -> autoAdvanceTick(current, event.elapsedSeconds)
        ScanEvent.AutoAdvanceElapsed -> autoAdvanceTick(current, 1)
        is ScanEvent.SetAutoAdvanceEnabled -> setAutoAdvanceEnabled(current, event.enabled)
        is ScanEvent.SetAutoAdvanceDelay -> setAutoAdvanceDelay(current, event.delay)
    }

    /** Convenience overload for tests that only need the scan state. */
    fun reduce(
        current: ScanState,
        event: ScanEvent,
    ): ScanReduction = reduce(ScanSessionState(scan = current), event)

    private fun startSession(current: ScanSessionState): ScanReduction {
        val restoredMatchedCount = current.initialMatchedCount.coerceAtLeast(0)
        val next = current.copy(
            scan = ScanState.WaitingQr(matchedCount = restoredMatchedCount),
            autoAdvanceSecondsRemaining = null,
        )
        return ScanReduction(
            state = next,
            effects = buildList {
                add(ScanEffect.AutoAdvanceCancelled)
                add(ScanEffect.SessionStarted)
                add(ScanEffect.ExpectFormat(ScanFormat.QR))
            },
        )
    }

    private fun endSession(current: ScanSessionState): ScanReduction {
        if (current.scan === ScanState.Idle && current.autoAdvanceSecondsRemaining == null) {
            return ScanReduction(current, listOf(ScanEffect.ExpectFormat(null)))
        }

        return ScanReduction(
            state = current.copy(
                scan = ScanState.Idle,
                autoAdvanceSecondsRemaining = null,
                inputSource = InputSource.CAMERA,
            ),
            effects = listOf(
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.StopInput,
                ScanEffect.ExpectFormat(null),
                ScanEffect.SessionEnded,
            ),
        )
    }

    private fun receivePayload(
        current: ScanSessionState,
        payload: ScanPayload,
    ): ScanReduction = when (val scan = current.scan) {
        ScanState.Idle -> reject(
            current,
            expectedFormat = ScanFormat.QR,
            reason = InvalidScanReason.SESSION_NOT_STARTED,
        )

        is ScanState.WaitingQr -> {
            if (payload.format != ScanFormat.QR) {
                reject(current, ScanFormat.QR, InvalidScanReason.WRONG_ORDER)
            } else {
                val value = normalizeTransportTerminators(payload.value)
                if (value.isEmpty()) {
                    reject(current, ScanFormat.QR, InvalidScanReason.EMPTY_PAYLOAD)
                } else if (!isValidPayload(payload, value)) {
                    reject(current, ScanFormat.QR, InvalidScanReason.INVALID_PAYLOAD)
                } else {
                    ScanReduction(
                        state = current.copy(
                            scan = ScanState.WaitingCode128(
                                qrPayload = value,
                                matchedCount = scan.matchedCount,
                            ),
                            autoAdvanceSecondsRemaining = null,
                        ),
                        effects = listOf(
                            ScanEffect.ScanAccepted,
                            ScanEffect.ExpectFormat(ScanFormat.CODE_128),
                        ),
                    )
                }
            }
        }

        is ScanState.WaitingCode128 -> {
            if (payload.format != ScanFormat.CODE_128) {
                reject(current, ScanFormat.CODE_128, InvalidScanReason.WRONG_ORDER)
            } else {
                val value = normalizeTransportTerminators(payload.value)
                if (value.isEmpty()) {
                    reject(current, ScanFormat.CODE_128, InvalidScanReason.EMPTY_PAYLOAD)
                } else if (!isValidPayload(payload, value)) {
                    reject(current, ScanFormat.CODE_128, InvalidScanReason.INVALID_PAYLOAD)
                } else {
                    completeComparison(current, scan.qrPayload, value)
                }
            }
        }

        // A result screen deliberately consumes no callbacks. This suppresses
        // duplicate Bluetooth notifications until the user advances/reset.
        is ScanState.Result -> ScanReduction(current)
    }

    private fun completeComparison(
        current: ScanSessionState,
        qrPayload: String,
        barcodePayload: String,
    ): ScanReduction {
        val result = compare(qrPayload, barcodePayload)
        val previousCount = current.scan.matchedCount
        val matchNumber = if (result == MatchResult.MATCH) previousCount + 1 else previousCount
        val remaining = if (result == MatchResult.MATCH && current.autoAdvanceEnabled) {
            current.autoAdvanceDelay.seconds
        } else {
            null
        }
        val next = current.copy(
            scan = ScanState.Result(
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                result = result,
                matchedCount = matchNumber,
            ),
            autoAdvanceSecondsRemaining = remaining,
        )

        val effects = buildList {
            add(ScanEffect.ScanAccepted)
            if (result == MatchResult.MATCH) {
                add(
                    ScanEffect.RecordMatch(
                        qrPayload = qrPayload,
                        barcodePayload = barcodePayload,
                        code = recordedCode(qrPayload, barcodePayload),
                        matchNumber = matchNumber,
                    ),
                )
                if (remaining != null) add(ScanEffect.AutoAdvanceStarted(remaining))
            } else {
                // A mismatch remains visible until a manual action. It is
                // never persisted and never starts an auto-advance countdown.
                add(ScanEffect.AutoAdvanceCancelled)
            }
        }
        return ScanReduction(next, effects)
    }

    private fun rereadQr(current: ScanSessionState): ScanReduction {
        val scan = current.scan
        if (scan !is ScanState.WaitingCode128) return ScanReduction(current)

        return ScanReduction(
            state = current.copy(
                scan = ScanState.WaitingQr(matchedCount = scan.matchedCount),
                autoAdvanceSecondsRemaining = null,
            ),
            effects = listOf(
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.ExpectFormat(ScanFormat.QR),
                ScanEffect.StartNextScan,
            ),
        )
    }

    private fun manualNext(current: ScanSessionState): ScanReduction {
        val scan = current.scan
        if (scan !is ScanState.Result) return ScanReduction(current)

        return ScanReduction(
            state = current.copy(
                scan = ScanState.WaitingQr(matchedCount = scan.matchedCount),
                autoAdvanceSecondsRemaining = null,
            ),
            effects = listOf(
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.ExpectFormat(ScanFormat.QR),
                ScanEffect.StartNextScan,
            ),
        )
    }

    private fun autoAdvanceTick(
        current: ScanSessionState,
        elapsedSeconds: Int,
    ): ScanReduction {
        if (elapsedSeconds <= 0) return ScanReduction(current)
        val scan = current.scan
        if (scan !is ScanState.Result || scan.result != MatchResult.MATCH ||
            !current.autoAdvanceEnabled
        ) {
            return ScanReduction(current)
        }

        val remaining = current.autoAdvanceSecondsRemaining ?: current.autoAdvanceDelay.seconds
        if (remaining > elapsedSeconds) {
            return ScanReduction(
                state = current.copy(autoAdvanceSecondsRemaining = remaining - elapsedSeconds),
                effects = listOf(ScanEffect.CountdownUpdated(remaining - elapsedSeconds)),
            )
        }

        return ScanReduction(
            state = current.copy(
                scan = ScanState.WaitingQr(matchedCount = scan.matchedCount),
                autoAdvanceSecondsRemaining = null,
            ),
            effects = listOf(
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.AutoAdvanceCompleted,
                ScanEffect.ExpectFormat(ScanFormat.QR),
                ScanEffect.StartNextScan,
            ),
        )
    }

    private fun setAutoAdvanceEnabled(
        current: ScanSessionState,
        enabled: Boolean,
    ): ScanReduction {
        if (current.autoAdvanceEnabled == enabled) return ScanReduction(current)

        val shouldStart = enabled && current.scan is ScanState.Result &&
            current.scan.result == MatchResult.MATCH
        val remaining = if (shouldStart) current.autoAdvanceDelay.seconds else null
        val effects = if (shouldStart) {
            listOf(ScanEffect.AutoAdvanceStarted(remaining!!))
        } else if (!enabled && current.autoAdvanceSecondsRemaining != null) {
            listOf(ScanEffect.AutoAdvanceCancelled)
        } else {
            emptyList()
        }
        return ScanReduction(
            state = current.copy(
                autoAdvanceEnabled = enabled,
                autoAdvanceSecondsRemaining = remaining,
            ),
            effects = effects,
        )
    }

    private fun setAutoAdvanceDelay(
        current: ScanSessionState,
        delay: AutoAdvanceDelay,
    ): ScanReduction {
        if (current.autoAdvanceDelay == delay) return ScanReduction(current)

        val shouldRestart = current.autoAdvanceEnabled && current.scan is ScanState.Result &&
            current.scan.result == MatchResult.MATCH
        val remaining = if (shouldRestart) delay.seconds else null
        val effects = if (shouldRestart) {
            listOf(
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.AutoAdvanceStarted(delay.seconds),
            )
        } else {
            emptyList()
        }
        return ScanReduction(
            state = current.copy(
                autoAdvanceDelay = delay,
                autoAdvanceSecondsRemaining = remaining,
            ),
            effects = effects,
        )
    }

    private fun cancelAutoAdvance(current: ScanSessionState): ScanReduction {
        if (current.autoAdvanceSecondsRemaining == null) return ScanReduction(current)
        return ScanReduction(
            state = current.copy(autoAdvanceSecondsRemaining = null),
            effects = listOf(ScanEffect.AutoAdvanceCancelled),
        )
    }

    private fun cancelForBackground(current: ScanSessionState): ScanReduction {
        val effects = buildList {
            if (current.autoAdvanceSecondsRemaining != null) {
                add(ScanEffect.AutoAdvanceCancelled)
            }
            add(ScanEffect.StopInput)
        }
        return ScanReduction(
            state = current.copy(autoAdvanceSecondsRemaining = null),
            effects = effects,
        )
    }

    private fun resumeAfterForeground(current: ScanSessionState): ScanReduction {
        val expectedFormat = current.expectedFormat ?: return ScanReduction(current)
        return ScanReduction(
            state = current,
            effects = listOf(ScanEffect.ResumeInput(expectedFormat)),
        )
    }

    private fun reject(
        current: ScanSessionState,
        expectedFormat: ScanFormat?,
        reason: InvalidScanReason,
    ): ScanReduction = ScanReduction(
        state = current,
        effects = listOf(ScanEffect.InvalidScan(expectedFormat, reason)),
    )

    private fun isValidPayload(payload: ScanPayload, value: String): Boolean {
        // Bluetooth has no reliable symbol-type field in its callback, so its
        // strict business formats prevent QR/Code 128 reverse-order mistakes.
        // CameraX/ML Kit already supplies the symbol type; non-standard QR
        // values remain eligible for CodeMatcher's conservative fallback.
        return when {
            payload.source == InputSource.BLUETOOTH && payload.format == ScanFormat.QR ->
                KanbanQrRecord.isValidScanPayload(value)
            payload.source == InputSource.BLUETOOTH && payload.format == ScanFormat.CODE_128 ->
                TagBarcodeRecord.isValidScanPayload(value)
            else -> value.isNotBlank()
        }
    }

    private fun recordedCode(qrPayload: String, barcodePayload: String): String {
        val part = CodeMatcher.partNumberFromBarcode(barcodePayload)
            ?: CodeMatcher.partNumberFromQr(qrPayload)
            ?: qrPayload
        return CodeMatcher.formatPartNumber(part)
    }

    companion object {
        fun initial(
            autoAdvanceEnabled: Boolean = false,
            autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
            matchedCount: Int = 0,
            existingMatchedCount: Int? = null,
        ): ScanSessionState = ScanSessionState(
            autoAdvanceEnabled = autoAdvanceEnabled,
            autoAdvanceDelay = autoAdvanceDelay,
            initialMatchedCount = (existingMatchedCount ?: matchedCount).coerceAtLeast(0),
        )

        fun normalizeTransportTerminators(rawValue: String): String {
            var value = rawValue
            while (value.isNotEmpty() && (value.last() == '\r' || value.last() == '\n' || value.last() == '\u0000')) {
                value = value.dropLast(1)
            }
            return value
        }
    }
}

/** Function-style entry point for callers that do not need a reducer object. */
fun reduceScan(
    state: ScanSessionState,
    event: ScanEvent,
): ScanReduction = ScanReducer().reduce(state, event)
