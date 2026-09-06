package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanReducerTest {
    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"
    private val mismatchBarcodePayload = "BCJH-55-81GG@1KVV0C"
    private val firstBoxQr =
        "DAAL134150BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
    private val secondBoxQr =
        "DAAL134140BCJH5581GG020000120000001200A      000000BAB15LAB07   0*"
    private val sharedBoxBarcode = "BCJH-55-81GG@1KVQ0C"

    // Destination Molten. The trailing spaces are record data, so these
    // literals must never be reformatted or trimmed by an editor.
    private val moltenQr1 =
        "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private val moltenQr1Short = moltenQr1.dropLast(4)
    private val moltenQr2 =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val moltenTag1 = "D10E-50-N10B@0UBL00"
    private val moltenTag2FirstBox = "PAF1-15-422@0NKD3C"
    private val moltenTag2SecondBox = "PAF1-15-422@0NLL3C"
    private val moltenTag2OtherPart = "PAF1-15-423@0N5L3C"

    @Before
    fun moltenFixturesKeepTheirPadding() {
        assertEquals(61, moltenQr1.length)
        assertEquals(57, moltenQr1Short.length)
        assertEquals(61, moltenQr2.length)
    }

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
    fun differentBoxQrsWithSameBarcodeAreBothRecorded() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state

        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(firstBoxQr)),
        ).state
        val first = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(sharedBoxBarcode)),
        )
        assertEquals(MatchResult.MATCH, first.state.result)
        assertEquals(1, first.state.matchedCount)
        assertEquals(1, first.effects.filterIsInstance<ScanEffect.RecordMatch>().size)

        state = reducer.reduce(first.state, ScanEvent.ManualNext).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(secondBoxQr)),
        ).state
        val second = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(sharedBoxBarcode)),
        )

        assertEquals(MatchResult.MATCH, second.state.result)
        assertEquals(2, second.state.matchedCount)
        assertEquals(2, second.effects.filterIsInstance<ScanEffect.RecordMatch>().single().matchNumber)
    }

    @Test
    fun sameBoxQrCannotBeCountedTwiceInOneActiveSession() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(firstBoxQr)),
        ).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(sharedBoxBarcode)),
        ).state
        assertEquals(MatchResult.MATCH, state.result)

        state = reducer.reduce(state, ScanEvent.ManualNext).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(firstBoxQr)),
        ).state
        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(sharedBoxBarcode)),
        )

        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)
        assertTrue(duplicate.effects.none { it is ScanEffect.RecordMatch })
    }

    @Test
    fun restoredBoxQrIsDuplicateIgnoringCaseAndSurroundingWhitespace() {
        val reducer = ScanReducer()
        var state = reducer.reduce(
            ScanReducer.initial(
                autoAdvanceEnabled = true,
                existingMatchedCount = 1,
                recordedBoxes = listOfNotNull(
                    RecordedBox.fromPayloads(firstBoxQr, sharedBoxBarcode),
                ),
            ),
            ScanEvent.StartSession,
        ).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(" ${firstBoxQr.lowercase()}\n")),
        ).state
        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(sharedBoxBarcode)),
        )

        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)
        assertTrue(duplicate.effects.none { it is ScanEffect.RecordMatch })
        assertTrue(duplicate.effects.contains(ScanEffect.AutoAdvanceCancelled))
        assertNull(duplicate.state.autoAdvanceSecondsRemaining)
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
    fun cameraRejectsUnrelatedQrWithoutAdvancingOrRecording() {
        val reducer = ScanReducer()
        val waiting = reducer.reduce(
            ScanReducer.initial(matchedCount = 2), ScanEvent.StartSession,
        ).state
        val invalidValues = listOf(
            "https://example.com/tissues",
            qrPayload.dropLast(1),
            qrPayload + "X",
            "X".repeat(66),
            "https://example.com/".padEnd(66, 'x'),
        )
        for (value in invalidValues) {
            val rejected = reducer.reduce(
                waiting,
                ScanEvent.PayloadReceived(ScanPayload.qr(value, InputSource.CAMERA)),
            )
            assertEquals(waiting, rejected.state)
            assertEquals(1, rejected.effects.size)
            assertTrue(rejected.effects.single() is ScanEffect.InvalidScan)
            assertEquals(2, rejected.state.scan.matchedCount)
        }
        val accepted = reducer.reduce(
            waiting,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload, InputSource.CAMERA)),
        )
        assertEquals(ScanPhase.WAITING_CODE_128, accepted.state.phase)
        assertEquals(2, accepted.state.scan.matchedCount)
        assertTrue(accepted.effects.contains(ScanEffect.ScanAccepted))
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
        assertEquals(
            InvalidScanReason.INCOMPLETE_QR_PAYLOAD,
            (invalidQr.effects.single() as ScanEffect.InvalidScan).reason,
        )

        val overlongQr = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(
                ScanPayload.qr(qrPayload + "X", InputSource.BLUETOOTH),
            ),
        )
        assertEquals(waitingQr, overlongQr.state)
        assertEquals(
            InvalidScanReason.OVERLONG_QR_PAYLOAD,
            (overlongQr.effects.single() as ScanEffect.InvalidScan).reason,
        )

        val invalidFieldsQr = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(
                ScanPayload.qr("X".repeat(66), InputSource.BLUETOOTH),
            ),
        )
        assertEquals(waitingQr, invalidFieldsQr.state)
        assertEquals(
            InvalidScanReason.INVALID_PAYLOAD,
            (invalidFieldsQr.effects.single() as ScanEffect.InvalidScan).reason,
        )

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
    fun cameraCode128RequiresBusinessPayloadFormat() {
        val reducer = ScanReducer()
        val waitingQr = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        val waitingCode = reducer.reduce(
            waitingQr,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload, InputSource.CAMERA)),
        ).state
        assertEquals(ScanPhase.WAITING_CODE_128, waitingCode.phase)

        val invalidValues = listOf(
            "BCJH-52-81GG",
            "BCJH-52-81GG@",
            "HELLO-WORLD",
            "1234567890",
            "https://example.com/tissue",
        )
        for (value in invalidValues) {
            val rejected = reducer.reduce(
                waitingCode,
                ScanEvent.PayloadReceived(ScanPayload.code128(value, InputSource.CAMERA)),
            )
            assertEquals(value, waitingCode, rejected.state)
            val invalid = rejected.effects.single() as ScanEffect.InvalidScan
            assertEquals(value, ScanFormat.CODE_128, invalid.expectedFormat)
            assertEquals(value, InvalidScanReason.INVALID_PAYLOAD, invalid.reason)
        }

        val accepted = reducer.reduce(
            waitingCode,
            ScanEvent.PayloadReceived(ScanPayload.code128(barcodePayload, InputSource.CAMERA)),
        )
        assertEquals(ScanPhase.RESULT, accepted.state.phase)
        assertEquals(MatchResult.MATCH, (accepted.state.scan as ScanState.Result).result)
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

    @Test
    fun moltenQrThenTagMatchesWithNineCharPartAndDeliverySummary() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state

        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        assertEquals(ScanPhase.WAITING_CODE_128, state.phase)
        assertEquals(Destination.MOLTEN, state.destination)

        val result = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        )

        assertEquals(MatchResult.MATCH, result.state.result)
        assertEquals(1, result.state.matchedCount)
        val record = result.effects.filterIsInstance<ScanEffect.RecordMatch>().single()
        assertEquals("PAF1-15-422", record.code)
        assertEquals(Destination.MOLTEN, record.destination)
        assertEquals("UAG5560", record.deliveryNumber)
        assertEquals(1, record.boxNumber)
        assertEquals(120, record.cumulativeQuantity)
        assertEquals(MoltenBoxSummary("UAG5560", 1, 120), result.state.moltenResultSummary)
    }

    @Test
    fun moltenSameQrDifferentTagIsSecondBoxWithCumulativeQuantity() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        ).state
        state = reducer.reduce(state, ScanEvent.ManualNext).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state

        val second = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2SecondBox)),
        )

        assertEquals(MatchResult.MATCH, second.state.result)
        assertEquals(2, second.state.matchedCount)
        val record = second.effects.filterIsInstance<ScanEffect.RecordMatch>().single()
        assertEquals(2, record.boxNumber)
        assertEquals(240, record.cumulativeQuantity)
        assertEquals(MoltenBoxSummary("UAG5560", 2, 240), second.state.moltenResultSummary)
    }

    @Test
    fun moltenSameQrAndSameTagIsDuplicate() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        ).state
        state = reducer.reduce(state, ScanEvent.ManualNext).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state

        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        )

        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)
        assertTrue(duplicate.effects.none { it is ScanEffect.RecordMatch })
        assertEquals(1, duplicate.state.recordedBoxes.size)
        assertNull(duplicate.state.moltenResultSummary)
    }

    @Test
    fun moltenQrWithTrailingSpacesStrippedIsAcceptedAndSharesIdentityWithPaddedForm() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1))).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag1)),
        ).state
        assertEquals(MatchResult.MATCH, state.result)
        assertEquals(MoltenBoxSummary("U543820", 1, 2), state.moltenResultSummary)

        state = reducer.reduce(state, ScanEvent.ManualNext).state
        val accepted = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1Short)),
        )
        assertEquals(ScanPhase.WAITING_CODE_128, accepted.state.phase)
        assertEquals(Destination.MOLTEN, accepted.state.destination)

        val duplicate = reducer.reduce(
            accepted.state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag1)),
        )
        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)
    }

    @Test
    fun moltenSessionAcceptsFourTwoFourTagAndSawaiSessionRejectsFourTwoThreeTag() {
        val reducer = ScanReducer()
        var molten = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        molten = reducer.reduce(molten, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1))).state
        val fourTwoFour = reducer.reduce(
            molten,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag1)),
        )
        assertEquals(ScanPhase.RESULT, fourTwoFour.state.phase)
        assertEquals(MatchResult.MATCH, fourTwoFour.state.result)

        var sawai = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        sawai = reducer.reduce(sawai, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        val fourTwoThree = reducer.reduce(
            sawai,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        )
        assertEquals(sawai, fourTwoThree.state)
        assertEquals(
            ScanEffect.InvalidScan(
                ScanFormat.CODE_128,
                InvalidScanReason.INVALID_PAYLOAD,
                moltenTag2FirstBox.length,
            ),
            fourTwoThree.effects.single(),
        )
    }

    @Test
    fun firstAcceptedQrLocksDestinationAndOtherDestinationQrIsRejected() {
        val reducer = ScanReducer()
        var sawai = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        sawai = reducer.reduce(sawai, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        sawai = reducer.reduce(sawai, ScanEvent.RereadQr).state
        assertEquals(Destination.SAWAI, sawai.destination)

        val moltenIntoSawai = reducer.reduce(
            sawai,
            ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1)),
        )
        assertEquals(sawai, moltenIntoSawai.state)
        assertEquals(
            ScanEffect.InvalidScan(
                ScanFormat.QR,
                InvalidScanReason.WRONG_DESTINATION,
                moltenQr1.length,
            ),
            moltenIntoSawai.effects.single(),
        )

        var molten = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        molten = reducer.reduce(molten, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1))).state
        molten = reducer.reduce(molten, ScanEvent.RereadQr).state
        assertEquals(Destination.MOLTEN, molten.destination)

        val sawaiIntoMolten = reducer.reduce(
            molten,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload)),
        )
        assertEquals(molten, sawaiIntoMolten.state)
        assertEquals(
            ScanEffect.InvalidScan(
                ScanFormat.QR,
                InvalidScanReason.WRONG_DESTINATION,
                qrPayload.length,
            ),
            sawaiIntoMolten.effects.single(),
        )
    }

    @Test
    fun destinationLockSurvivesMismatchAndManualNext() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        val mismatch = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2OtherPart)),
        )
        assertEquals(MatchResult.MISMATCH, mismatch.state.result)
        assertEquals(Destination.MOLTEN, mismatch.state.destination)

        state = reducer.reduce(mismatch.state, ScanEvent.ManualNext).state
        assertEquals(ScanPhase.WAITING_QR, state.phase)
        assertEquals(Destination.MOLTEN, state.destination)

        val rejected = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload)),
        )
        assertEquals(state, rejected.state)
        assertEquals(
            InvalidScanReason.WRONG_DESTINATION,
            (rejected.effects.single() as ScanEffect.InvalidScan).reason,
        )
    }

    @Test
    fun restoredMoltenBoxesSeedDuplicateAndDeliveryCounts() {
        val reducer = ScanReducer()
        var state = reducer.reduce(
            ScanReducer.initial(
                existingMatchedCount = 1,
                recordedBoxes = listOfNotNull(
                    RecordedBox.fromPayloads(moltenQr2, moltenTag2FirstBox),
                ),
            ),
            ScanEvent.StartSession,
        ).state
        assertEquals(Destination.MOLTEN, state.destination)

        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        )
        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)

        state = reducer.reduce(duplicate.state, ScanEvent.ManualNext).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        val second = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2SecondBox)),
        )

        assertEquals(MatchResult.MATCH, second.state.result)
        assertEquals(2, second.state.matchedCount)
        assertEquals(MoltenBoxSummary("UAG5560", 2, 240), second.state.moltenResultSummary)
    }

    @Test
    fun unlockedInvalidQrLengthsAreClassifiedAgainstBothRecordLengths() {
        val reducer = ScanReducer()
        val waiting = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        val expectations = listOf(
            "PART:BCJH-52-81GG;QTY:12" to InvalidScanReason.INCOMPLETE_QR_PAYLOAD,
            "X".repeat(60) to InvalidScanReason.INVALID_PAYLOAD,
            "X".repeat(63) to InvalidScanReason.INCOMPLETE_QR_PAYLOAD,
            // Both record lengths are complete lengths, so a payload of
            // exactly 61 or 66 characters was received in full and is simply
            // not a business label.
            "X".repeat(66) to InvalidScanReason.INVALID_PAYLOAD,
            "X".repeat(67) to InvalidScanReason.OVERLONG_QR_PAYLOAD,
        )

        for ((value, reason) in expectations) {
            val label = "length ${value.length}"
            val rejected = reducer.reduce(
                waiting,
                ScanEvent.PayloadReceived(ScanPayload.qr(value)),
            )
            assertEquals(label, waiting, rejected.state)
            val invalid = rejected.effects.single() as ScanEffect.InvalidScan
            assertEquals(label, reason, invalid.reason)
            assertEquals(label, value.length, invalid.observedLength)
        }
    }

    @Test
    fun moltenLockedInvalidQrLengthsUseSixtyOne() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1))).state
        state = reducer.reduce(state, ScanEvent.RereadQr).state
        assertEquals(Destination.MOLTEN, state.destination)

        val overlong = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr("X".repeat(62))),
        )
        assertEquals(state, overlong.state)
        assertEquals(
            ScanEffect.InvalidScan(ScanFormat.QR, InvalidScanReason.OVERLONG_QR_PAYLOAD, 62),
            overlong.effects.single(),
        )

        val incomplete = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.qr("X".repeat(50))),
        )
        assertEquals(state, incomplete.state)
        assertEquals(
            ScanEffect.InvalidScan(ScanFormat.QR, InvalidScanReason.INCOMPLETE_QR_PAYLOAD, 50),
            incomplete.effects.single(),
        )
    }

    @Test
    fun endSessionClearsDestinationAndRecordedBoxes() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr2))).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        ).state
        assertEquals(Destination.MOLTEN, state.destination)
        assertEquals(1, state.recordedBoxes.size)

        val ended = reducer.reduce(state, ScanEvent.EndSession)

        assertEquals(ScanPhase.IDLE, ended.state.phase)
        assertNull(ended.state.destination)
        assertTrue(ended.state.recordedBoxes.isEmpty())
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
