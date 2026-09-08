package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.DensoKanbanQrRecord
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.MoltenQrRecord
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.Destination
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
                // Ending the session is the only way to change destination.
                destination = null,
                recordedBoxes = emptyList(),
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
                val detected = CodeMatcher.detectDestination(value)
                val invalidReason = invalidPayloadReason(payload, value, current.destination)
                if (value.isEmpty()) {
                    reject(current, ScanFormat.QR, InvalidScanReason.EMPTY_PAYLOAD)
                } else if (invalidReason != null) {
                    reject(
                        current,
                        ScanFormat.QR,
                        invalidReason,
                        observedLength = observedQrLength(value, current.destination),
                    )
                } else if (current.destination != null && detected != current.destination) {
                    // A session matches one destination from its first accepted
                    // QR onwards. Mixing slips would make the box identity, the
                    // box numbering, and the saved session label inconsistent.
                    reject(
                        current,
                        ScanFormat.QR,
                        InvalidScanReason.WRONG_DESTINATION,
                        observedLength = value.length,
                    )
                } else {
                    ScanReduction(
                        state = current.copy(
                            scan = ScanState.WaitingCode128(
                                qrPayload = value,
                                matchedCount = scan.matchedCount,
                            ),
                            autoAdvanceSecondsRemaining = null,
                            destination = current.destination ?: detected,
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
                val invalidReason = invalidPayloadReason(payload, value, current.destination)
                if (value.isEmpty()) {
                    reject(current, ScanFormat.CODE_128, InvalidScanReason.EMPTY_PAYLOAD)
                } else if (invalidReason != null) {
                    reject(
                        current,
                        ScanFormat.CODE_128,
                        invalidReason,
                        observedLength = value.trim().length,
                    )
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
        val comparison = compare(qrPayload, barcodePayload)
        // The box key is destination aware: a Sawai slip and a Denso kanban
        // each identify their own box (a card number and a kanban serial), a
        // Molten slip needs the tag's management code as well.
        val identity = CodeMatcher.boxIdentity(qrPayload, barcodePayload)
        val result = if (comparison == MatchResult.MATCH &&
            identity != null &&
            identity in current.matchedBoxIdentities
        ) {
            MatchResult.DUPLICATE
        } else {
            comparison
        }
        val previousCount = current.scan.matchedCount
        val matchNumber = if (result == MatchResult.MATCH) previousCount + 1 else previousCount
        val remaining = if (result == MatchResult.MATCH && current.autoAdvanceEnabled) {
            current.autoAdvanceDelay.seconds
        } else {
            null
        }
        val code = recordedCode(qrPayload, barcodePayload)
        val recordedBox = if (result == MatchResult.MATCH) {
            RecordedBox.fromPayloads(qrPayload, barcodePayload, code)
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
            recordedBoxes = if (recordedBox != null) {
                current.recordedBoxes + recordedBox
            } else {
                current.recordedBoxes
            },
        )

        val effects = buildList {
            add(ScanEffect.ScanAccepted)
            if (result == MatchResult.MATCH) {
                // Detection already ran when the QR was accepted, so the
                // fallback is unreachable in practice and only keeps the
                // effect's destination non-null.
                val destination = current.destination
                    ?: CodeMatcher.detectDestination(qrPayload)
                    ?: Destination.SAWAI
                val summary = if (destination == Destination.MOLTEN) {
                    recordedBox?.deliveryNumber?.let(next::moltenSummary)
                } else {
                    null
                }
                add(
                    ScanEffect.RecordMatch(
                        qrPayload = qrPayload,
                        barcodePayload = barcodePayload,
                        code = code,
                        matchNumber = matchNumber,
                        destination = destination,
                        // Molten counts boxes per delivery number; Sawai and
                        // Denso keep counting them per part number, as the
                        // history does.
                        boxNumber = summary?.boxNumber
                            ?: next.recordedBoxes.count { it.code == code },
                        deliveryNumber = summary?.deliveryNumber,
                        cumulativeQuantity = summary?.cumulativeQuantity,
                    ),
                )
                if (remaining != null) add(ScanEffect.AutoAdvanceStarted(remaining))
            } else {
                // A mismatch or already-recorded box remains visible until a
                // manual action. Neither is persisted or auto-advanced.
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
        observedLength: Int? = null,
    ): ScanReduction = ScanReduction(
        state = current,
        effects = listOf(ScanEffect.InvalidScan(expectedFormat, reason, observedLength)),
    )

    private fun invalidPayloadReason(
        payload: ScanPayload,
        value: String,
        lockedDestination: Destination?,
    ): InvalidScanReason? {
        // Recognising a QR symbol does not establish that it is a business
        // label. Validate QR content for both camera and Bluetooth before
        // advancing; the comparison fallback must not bypass scan acceptance.
        // The same applies to Code 128 below (#78).
        return when {
            payload.format == ScanFormat.QR -> {
                val length = observedQrLength(value, lockedDestination)
                val expected = lockedDestination?.let(CodeMatcher::expectedQrLength)
                when {
                    // A payload that parses as any of the three destinations'
                    // records is accepted here; the caller decides whether the
                    // session's lock allows that destination.
                    CodeMatcher.detectDestination(value) != null -> null
                    // A Denso kanban declares its own item layout, so its
                    // payload length varies and there is no expected length a
                    // bad read could be measured against.
                    lockedDestination == Destination.DENSO ->
                        InvalidScanReason.INVALID_PAYLOAD
                    // A locked session knows exactly how long its record is.
                    expected != null && length < expected ->
                        InvalidScanReason.INCOMPLETE_QR_PAYLOAD
                    expected != null && length > expected ->
                        InvalidScanReason.OVERLONG_QR_PAYLOAD
                    expected != null -> InvalidScanReason.INVALID_PAYLOAD
                    // A JAMA payload is a Denso kanban that failed to parse, so
                    // the fixed Sawai/Molten lengths below say nothing about it.
                    value.startsWith(
                        DensoKanbanQrRecord.FORMAT_PREFIX,
                        ignoreCase = true,
                    ) -> InvalidScanReason.INVALID_PAYLOAD
                    // Without a lock both fixed-length records are still
                    // possible, so only a length outside 57-66 is certainly
                    // truncated or padded.
                    length < MoltenQrRecord.MINIMUM_SCAN_PAYLOAD_LENGTH ->
                        InvalidScanReason.INCOMPLETE_QR_PAYLOAD
                    length > KanbanQrRecord.REQUIRED_SCAN_PAYLOAD_LENGTH ->
                        InvalidScanReason.OVERLONG_QR_PAYLOAD
                    // 62-65 falls between the two records: too long for Molten
                    // and too short for Sawai, so the Sawai record was cut off.
                    // A complete-length payload that did not parse is invalid.
                    length in (MoltenQrRecord.RECORD_LENGTH + 1) until
                        KanbanQrRecord.REQUIRED_SCAN_PAYLOAD_LENGTH ->
                        InvalidScanReason.INCOMPLETE_QR_PAYLOAD
                    else -> InvalidScanReason.INVALID_PAYLOAD
                }
            }
            // A Code 128 symbol likewise only proves the symbology. Camera and
            // Bluetooth input must both carry the product-tag business format
            // (a 4-2-4 or 4-2-3 part number for Sawai and Molten, 6-4 for
            // Denso, followed by @management code) before comparison runs.
            // Before the lock any of the three is accepted; a QR is always read
            // first, so in practice the destination is known here.
            payload.format == ScanFormat.CODE_128 ->
                if (TagBarcodeRecord.isValidScanPayload(value, lockedDestination)) {
                    null
                } else {
                    InvalidScanReason.INVALID_PAYLOAD
                }
            value.isBlank() -> InvalidScanReason.INVALID_PAYLOAD
            else -> null
        }
    }

    /**
     * Length reported for an invalid QR.
     *
     * A Molten record is space padded and a Denso kanban carries blank item
     * values, so their surrounding spaces are data and must be counted; every
     * other case keeps the trimmed length the Sawai messages have always shown.
     */
    private fun observedQrLength(value: String, lockedDestination: Destination?): Int =
        when (lockedDestination) {
            Destination.MOLTEN, Destination.DENSO -> value.length
            Destination.SAWAI, null -> value.trim().length
        }

    private fun recordedCode(qrPayload: String, barcodePayload: String): String {
        val part = CodeMatcher.partNumberFromBarcode(barcodePayload)
            ?: CodeMatcher.partNumberFromQr(qrPayload)
            ?: qrPayload
        // The QR decides the printed form: only a Denso number is 6-4.
        return CodeMatcher.formatPartNumber(part, CodeMatcher.detectDestination(qrPayload))
    }

    companion object {
        fun initial(
            autoAdvanceEnabled: Boolean = false,
            autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
            matchedCount: Int = 0,
            existingMatchedCount: Int? = null,
            recordedBoxes: Collection<RecordedBox> = emptyList(),
            destination: Destination? = null,
        ): ScanSessionState = ScanSessionState(
            autoAdvanceEnabled = autoAdvanceEnabled,
            autoAdvanceDelay = autoAdvanceDelay,
            initialMatchedCount = (existingMatchedCount ?: matchedCount).coerceAtLeast(0),
            destination = destination
                ?: recordedBoxes.firstNotNullOfOrNull { it.destination },
            recordedBoxes = recordedBoxes.toList(),
        )

        /**
         * Strip the transport terminators (CR, LF, NUL) a scanner adds at
         * either end. Spaces are deliberately kept: a Molten record is space
         * padded, so trimming them would shorten a complete record.
         */
        fun normalizeTransportTerminators(rawValue: String): String =
            CodeMatcher.stripTransportTerminators(rawValue)
    }
}

/** Function-style entry point for callers that do not need a reducer object. */
fun reduceScan(
    state: ScanSessionState,
    event: ScanEvent,
): ScanReduction = ScanReducer().reduce(state, event)
