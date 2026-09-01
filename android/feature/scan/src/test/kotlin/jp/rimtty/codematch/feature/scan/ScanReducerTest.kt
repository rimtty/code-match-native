package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanReducerTest {
    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"
    private val mismatchBarcodePayload = "BCJH-55-81GG@1KVV0C"

    @Test
    fun startSessionMovesIdleToWaitingQr() {
        val reduction = ScanReducer().reduce(
            ScanSessionState(),
            ScanEvent.StartSession,
        )

        assertEquals(ScanPhase.WAITING_QR, reduction.state.phase)
        assertTrue(reduction.state.scan is ScanState.WaitingQr)
        assertEquals(0, reduction.state.matchedCount)
        assertTrue(reduction.effects.contains(ScanEffect.SessionStarted))
        assertTrue(reduction.effects.contains(ScanEffect.ExpectFormat(ScanFormat.QR)))
    }

    @Test
    fun startSessionRestoresInjectedMatchedCount() {
        val reduction = ScanReducer().reduce(
            ScanReducer.initial(existingMatchedCount = 7),
            ScanEvent.StartSession,
        )

        assertEquals(7, reduction.state.matchedCount)
        assertEquals(7, (reduction.state.scan as ScanState.WaitingQr).matchedCount)
    }

    @Test
    fun cameraQrThenCode128ProducesMatchAndRecordEffect() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state

        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        assertEquals(ScanPhase.WAITING_CODE_128, state.phase)
        assertEquals(qrPayload, state.qrPayload)

        val result = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload)),
        )
        state = result.state

        assertEquals(ScanPhase.RESULT, state.phase)
        assertEquals(MatchResult.MATCH, state.result)
        assertEquals(1, state.matchedCount)
        val record = result.effects.filterIsInstance<ScanEffect.RecordMatch>().single()
        assertEquals(qrPayload, record.qrPayload)
        assertEquals(barcodePayload, record.barcodePayload)
        assertEquals("BCJH-52-81GG", record.code)
        assertEquals(1, record.matchNumber)
    }

    @Test
    fun mismatchRemainsVisibleAndNeverProducesRecordEffect() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        val result = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(mismatchBarcodePayload)),
        )

        assertEquals(MatchResult.MISMATCH, result.state.result)
        assertEquals(0, result.state.matchedCount)
        assertTrue(result.effects.none { it is ScanEffect.RecordMatch })
        assertNull(result.state.autoAdvanceSecondsRemaining)
    }

    @Test
    fun reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState() {
        val reducer = ScanReducer()
        val waitingQr = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state

        val reverse = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload)),
        )
        assertEquals(waitingQr, reverse.state)
        assertEquals(
            ScanEffect.InvalidScan(ScanFormat.QR, InvalidScanReason.WRONG_ORDER),
            reverse.effects.single(),
        )

        val invalid = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(ScanPayload.qr("")),
        )
        assertEquals(waitingQr, invalid.state)
        assertEquals(
            ScanEffect.InvalidScan(ScanFormat.QR, InvalidScanReason.EMPTY_PAYLOAD),
            invalid.effects.single(),
        )

        val waitingCode = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload)),
        ).state
        val qrAgain = reducer.reduce(
            waitingCode,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload)),
        )
        assertEquals(waitingCode, qrAgain.state)
        assertEquals(
            ScanEffect.InvalidScan(ScanFormat.CODE_128, InvalidScanReason.WRONG_ORDER),
            qrAgain.effects.single(),
        )
    }

    @Test
    fun bluetoothRequiresBusinessPayloadFormats() {
        val reducer = ScanReducer()
        val waitingQr = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        val invalidQr = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(
                ScanPayload.qr("PART:BCJH-52-81GG;QTY:12", InputSource.BLUETOOTH),
            ),
        )
        assertEquals(waitingQr, invalidQr.state)
        assertEquals(InvalidScanReason.INVALID_PAYLOAD, (invalidQr.effects.single() as ScanEffect.InvalidScan).reason)

        val waitingCode = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH)),
        ).state
        val invalidBarcode = reducer.reduce(
            waitingCode,
            ScanEvent.PayloadReceived(
                ScanPayload.code128("BCJH-52-81GG", InputSource.BLUETOOTH),
            ),
        )
        assertEquals(waitingCode, invalidBarcode.state)
        assertEquals(
            InvalidScanReason.INVALID_PAYLOAD,
            (invalidBarcode.effects.single() as ScanEffect.InvalidScan).reason,
        )
    }

    @Test
    fun rereadQrReturnsToQrAndPreservesMatchedCount() {
        val reducer = ScanReducer()
        var state = ScanReducer.initial()
        state = reducer.reduce(state, ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload))).state
        state = reducer.reduce(state, ScanEvent.ManualNext).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        assertEquals(1, state.matchedCount)

        val reread = reducer.reduce(state, ScanEvent.RereadQr)
        assertTrue(reread.state.scan is ScanState.WaitingQr)
        assertEquals(1, reread.state.matchedCount)
        assertNull(reread.state.qrPayload)
        assertTrue(reread.effects.contains(ScanEffect.StartNextScan))
    }

    @Test
    fun manualNextAndEndCancelCountdown() {
        val reducer = ScanReducer()
        var state = ScanReducer.initial(autoAdvanceEnabled = true)
        state = reducer.reduce(state, ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload))).state
        assertEquals(3, state.autoAdvanceSecondsRemaining)

        val manual = reducer.reduce(state, ScanEvent.ManualNext)
        assertEquals(ScanPhase.WAITING_QR, manual.state.phase)
        assertNull(manual.state.autoAdvanceSecondsRemaining)
        assertTrue(manual.effects.contains(ScanEffect.AutoAdvanceCancelled))

        state = reducer.reduce(state, ScanEvent.StartSession).state
        val ended = reducer.reduce(state, ScanEvent.EndSession)
        assertEquals(ScanPhase.IDLE, ended.state.phase)
        assertNull(ended.state.autoAdvanceSecondsRemaining)
        assertTrue(ended.effects.contains(ScanEffect.SessionEnded))
        assertTrue(ended.effects.contains(ScanEffect.ExpectFormat(null)))
    }

    @Test
    fun autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks() {
        for (delay in AutoAdvanceDelay.entries) {
            val reducer = ScanReducer()
            var state = ScanReducer.initial(
                autoAdvanceEnabled = true,
                autoAdvanceDelay = delay,
            )
            state = reducer.reduce(state, ScanEvent.StartSession).state
            state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
            state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload))).state

            assertEquals(delay.seconds, state.autoAdvanceSecondsRemaining)
            if (delay.seconds > 1) {
                state = reducer.reduce(state, ScanEvent.AutoAdvanceTick(delay.seconds - 1)).state
                assertEquals(1, state.autoAdvanceSecondsRemaining)
                assertEquals(ScanPhase.RESULT, state.phase)
            }
            val elapsed = reducer.reduce(state, ScanEvent.AutoAdvanceTick()).state
            assertEquals(ScanPhase.WAITING_QR, elapsed.phase)
            assertNull(elapsed.autoAdvanceSecondsRemaining)
            assertEquals(1, elapsed.matchedCount)
        }
    }

    @Test
    fun turningAutoAdvanceOffCancelsAndKeepsMatchResult() {
        val reducer = ScanReducer()
        var state = matchedState(reducer, autoAdvanceEnabled = true)
        val disabled = reducer.reduce(state, ScanEvent.SetAutoAdvanceEnabled(false))
        state = disabled.state
        assertFalse(state.autoAdvanceEnabled)
        assertNull(state.autoAdvanceSecondsRemaining)
        assertEquals(ScanPhase.RESULT, state.phase)
        assertTrue(disabled.effects.contains(ScanEffect.AutoAdvanceCancelled))

        val noRestart = reducer.reduce(state, ScanEvent.AutoAdvanceTick(10))
        assertEquals(state, noRestart.state)
        assertTrue(noRestart.effects.isEmpty())
    }

    @Test
    fun changingDelayRestartsOnlyAnActiveMatchCountdown() {
        val reducer = ScanReducer()
        var state = matchedState(reducer, autoAdvanceEnabled = true)
        val changed = reducer.reduce(state, ScanEvent.SetAutoAdvanceDelay(AutoAdvanceDelay.FIVE_SECONDS))
        state = changed.state
        assertEquals(AutoAdvanceDelay.FIVE_SECONDS, state.autoAdvanceDelay)
        assertEquals(5, state.autoAdvanceSecondsRemaining)
        assertTrue(changed.effects.contains(ScanEffect.AutoAdvanceCancelled))
        assertTrue(changed.effects.contains(ScanEffect.AutoAdvanceStarted(5)))

        val mismatchState = matchedState(reducer, autoAdvanceEnabled = false, mismatch = true)
        val mismatchChanged = reducer.reduce(
            mismatchState,
            ScanEvent.SetAutoAdvanceEnabled(true),
        )
        assertNull(mismatchChanged.state.autoAdvanceSecondsRemaining)
        assertTrue(mismatchChanged.effects.isEmpty())
    }

    @Test
    fun backgroundCancelsCountdownWithoutDiscardingResult() {
        val reducer = ScanReducer()
        val state = matchedState(reducer, autoAdvanceEnabled = true)
        val backgrounded = reducer.reduce(state, ScanEvent.Backgrounded)
        assertEquals(ScanPhase.RESULT, backgrounded.state.phase)
        assertEquals(MatchResult.MATCH, backgrounded.state.result)
        assertNull(backgrounded.state.autoAdvanceSecondsRemaining)
        assertTrue(backgrounded.effects.contains(ScanEffect.AutoAdvanceCancelled))
        assertTrue(backgrounded.effects.contains(ScanEffect.StopInput))
    }

    @Test
    fun foregroundResumesExpectedFormatWithoutChangingTheCurrentStep() {
        val reducer = ScanReducer()
        val waitingQr = reducer.reduce(ScanReducer.initial(), ScanEvent.StartSession).state
        val resumedQr = reducer.reduce(waitingQr, ScanEvent.Foregrounded)
        assertEquals(waitingQr, resumedQr.state)
        assertEquals(
            listOf(ScanEffect.ResumeInput(ScanFormat.QR)),
            resumedQr.effects,
        )

        val waitingCode = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload)),
        ).state
        val resumedCode = reducer.reduce(waitingCode, ScanEvent.Foregrounded)
        assertEquals(waitingCode, resumedCode.state)
        assertEquals(
            listOf(ScanEffect.ResumeInput(ScanFormat.CODE_128)),
            resumedCode.effects,
        )
    }

    @Test
    fun duplicatePayloadAfterResultIsIgnored() {
        val reducer = ScanReducer()
        val state = matchedState(reducer)
        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload)),
        )
        assertEquals(state, duplicate.state)
        assertTrue(duplicate.effects.isEmpty())
    }

    private fun matchedState(
        reducer: ScanReducer,
        autoAdvanceEnabled: Boolean = false,
        mismatch: Boolean = false,
    ): ScanSessionState {
        var state = ScanReducer.initial(autoAdvanceEnabled = autoAdvanceEnabled)
        state = reducer.reduce(state, ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        return reducer.reduce(
            state,
            ScanEvent.PayloadReceived(
                ScanPayload.code128(
                    if (mismatch) mismatchBarcodePayload else barcodePayload,
                ),
            ),
        ).state
    }
}
