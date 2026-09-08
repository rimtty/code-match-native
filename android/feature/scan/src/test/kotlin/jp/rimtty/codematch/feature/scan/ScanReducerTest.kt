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

    // A Sawai label with a nine-character part number (2026-09-08 field
    // labels, #129): `DAH4`-shaped card number, left-justified item field with
    // a trailing space, blank suffix, and a 4-2-3 tag.
    private val sawaiShortPartQr =
        "DAH4093540BCJH5281F   0002000000020000H      000000BHB01LHA28   0*"
    private val sawaiShortPartTag = "BCJH-52-81F@01R95K"

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

    // Destination Denso: the real 221-character kanbans of boxes 0140 and 0141
    // of part 860150-7722 and of box 0538 of part 860150-7791. The runs of
    // spaces are blank item values, so these literals must never be trimmed.
    private val densoQrBox1 =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private val densoQrBox2 =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0141SWS    20260908S0010000720000009924543330454333M6"
    private val densoQrOtherPart =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507791000000192D860C01008D86045M      0538SWS    20260908S0010007680000009924543420454342R6"
    private val densoTagBox1 = "860150-7722@1DZ50O"
    private val densoTagBox2 = "860150-7722@1DZB0O"
    private val densoTagOtherPart = "860150-7791@01335C"

    @Before
    fun moltenAndDensoFixturesKeepTheirPadding() {
        assertEquals(61, moltenQr1.length)
        assertEquals(57, moltenQr1Short.length)
        assertEquals(61, moltenQr2.length)
        assertEquals(221, densoQrBox1.length)
        assertEquals(221, densoQrBox2.length)
        assertEquals(221, densoQrOtherPart.length)
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
    fun sawaiAndMoltenSessionsAcceptFourTwoFourAndFourTwoThreeTags() {
        val reducer = ScanReducer()
        var molten = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        molten = reducer.reduce(molten, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1))).state
        val fourTwoFour = reducer.reduce(
            molten,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag1)),
        )
        assertEquals(ScanPhase.RESULT, fourTwoFour.state.phase)
        assertEquals(MatchResult.MATCH, fourTwoFour.state.result)

        // A Sawai label with a nine-character part number matches its 4-2-3
        // tag (#129) and prints the part as 4-2-3.
        var sawai = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        sawai = reducer.reduce(sawai, ScanEvent.PayloadReceived(ScanPayload.qr(sawaiShortPartQr))).state
        assertEquals(Destination.SAWAI, sawai.destination)
        assertEquals(ScanPhase.WAITING_CODE_128, sawai.phase)
        val fourTwoThree = reducer.reduce(
            sawai,
            ScanEvent.PayloadReceived(ScanPayload.code128(sawaiShortPartTag)),
        )
        assertEquals(ScanPhase.RESULT, fourTwoThree.state.phase)
        assertEquals(MatchResult.MATCH, fourTwoThree.state.result)
        assertEquals(1, fourTwoThree.state.matchedCount)

        // A 4-2-3 tag of another part is a valid tag in a Sawai session too,
        // so it reaches the comparison and is a mismatch rather than a
        // rejected scan.
        var tenCharacter = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        tenCharacter = reducer.reduce(tenCharacter, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        val otherPart = reducer.reduce(
            tenCharacter,
            ScanEvent.PayloadReceived(ScanPayload.code128(moltenTag2FirstBox)),
        )
        assertEquals(ScanPhase.RESULT, otherPart.state.phase)
        assertEquals(MatchResult.MISMATCH, otherPart.state.result)
        assertTrue(otherPart.effects.none { it is ScanEffect.InvalidScan })
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

    @Test
    fun densoQrThenSixFourTagMatchesAndCountsPerPartNumber() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state

        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        assertEquals(ScanPhase.WAITING_CODE_128, state.phase)
        assertEquals(Destination.DENSO, state.destination)

        val result = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        )

        assertEquals(MatchResult.MATCH, result.state.result)
        assertEquals(1, result.state.matchedCount)
        val record = result.effects.filterIsInstance<ScanEffect.RecordMatch>().single()
        // A Denso tag prints a 6-4 part number, never the Sawai 4-2-4 form.
        assertEquals("860150-7722", record.code)
        assertEquals(Destination.DENSO, record.destination)
        assertEquals(1, record.boxNumber)
        // Denso counts boxes per part number, so the Molten-only fields stay
        // empty and no delivery summary is shown.
        assertNull(record.deliveryNumber)
        assertNull(record.cumulativeQuantity)
        assertNull(result.state.moltenResultSummary)
    }

    @Test
    fun densoSecondKanbanSamePartIsSecondBox() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        ).state
        state = reducer.reduce(state, ScanEvent.ManualNext).state

        // A different kanban serial (0141) is another box of the same part.
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox2))).state
        val second = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox2)),
        )

        assertEquals(MatchResult.MATCH, second.state.result)
        assertEquals(2, second.state.matchedCount)
        val record = second.effects.filterIsInstance<ScanEffect.RecordMatch>().single()
        assertEquals("860150-7722", record.code)
        assertEquals(2, record.boxNumber)
        assertNull(record.deliveryNumber)
        assertNull(second.state.moltenResultSummary)
    }

    @Test
    fun densoSameKanbanIsDuplicateEvenWithAnotherTag() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        state = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        ).state
        state = reducer.reduce(state, ScanEvent.ManualNext).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state

        // The kanban serial identifies the box, so the same kanban read with
        // another label of the same part is still that one box.
        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox2)),
        )

        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)
        assertTrue(duplicate.effects.none { it is ScanEffect.RecordMatch })
        assertEquals(1, duplicate.state.recordedBoxes.size)
    }

    @Test
    fun densoSessionRejectsSawaiAndMoltenQrWithWrongDestination() {
        val reducer = ScanReducer()
        var denso = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        denso = reducer.reduce(denso, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        denso = reducer.reduce(denso, ScanEvent.RereadQr).state
        assertEquals(Destination.DENSO, denso.destination)

        for (foreign in listOf(qrPayload, moltenQr1)) {
            val rejected = reducer.reduce(
                denso,
                ScanEvent.PayloadReceived(ScanPayload.qr(foreign)),
            )
            assertEquals(denso, rejected.state)
            assertEquals(
                ScanEffect.InvalidScan(
                    ScanFormat.QR,
                    InvalidScanReason.WRONG_DESTINATION,
                    foreign.length,
                ),
                rejected.effects.single(),
            )
        }

        // The reverse direction: a Denso kanban never joins a Sawai session.
        var sawai = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        sawai = reducer.reduce(sawai, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        sawai = reducer.reduce(sawai, ScanEvent.RereadQr).state
        val densoIntoSawai = reducer.reduce(
            sawai,
            ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1)),
        )
        assertEquals(sawai, densoIntoSawai.state)
        assertEquals(
            InvalidScanReason.WRONG_DESTINATION,
            (densoIntoSawai.effects.single() as ScanEffect.InvalidScan).reason,
        )
    }

    @Test
    fun sawaiAndMoltenSessionsRejectSixFourTag() {
        val reducer = ScanReducer()
        var sawai = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        sawai = reducer.reduce(sawai, ScanEvent.PayloadReceived(ScanPayload.qr(qrPayload))).state
        val intoSawai = reducer.reduce(
            sawai,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        )
        assertEquals(sawai, intoSawai.state)
        assertEquals(
            ScanEffect.InvalidScan(
                ScanFormat.CODE_128,
                InvalidScanReason.INVALID_PAYLOAD,
                densoTagBox1.length,
            ),
            intoSawai.effects.single(),
        )

        var molten = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        molten = reducer.reduce(molten, ScanEvent.PayloadReceived(ScanPayload.qr(moltenQr1))).state
        val intoMolten = reducer.reduce(
            molten,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        )
        assertEquals(molten, intoMolten.state)
        assertEquals(
            ScanEffect.InvalidScan(
                ScanFormat.CODE_128,
                InvalidScanReason.INVALID_PAYLOAD,
                densoTagBox1.length,
            ),
            intoMolten.effects.single(),
        )

        // A Denso session is the only one that accepts the 6-4 tag.
        var denso = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        denso = reducer.reduce(denso, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        val accepted = reducer.reduce(
            denso,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        )
        assertEquals(MatchResult.MATCH, accepted.state.result)
    }

    @Test
    fun densoLockedInvalidQrIsInvalidWithoutLengthHint() {
        val reducer = ScanReducer()
        var state = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        state = reducer.reduce(state, ScanEvent.RereadQr).state
        assertEquals(Destination.DENSO, state.destination)

        // A Denso kanban declares its own layout, so no length is "complete"
        // or "too long": every unparseable payload is simply invalid.
        for (value in listOf("X".repeat(50), "X".repeat(66), "X".repeat(240))) {
            val rejected = reducer.reduce(
                state,
                ScanEvent.PayloadReceived(ScanPayload.qr(value)),
            )
            assertEquals(state, rejected.state)
            assertEquals(
                "length ${value.length}",
                ScanEffect.InvalidScan(
                    ScanFormat.QR,
                    InvalidScanReason.INVALID_PAYLOAD,
                    value.length,
                ),
                rejected.effects.single(),
            )
        }
    }

    @Test
    fun unlockedJamaPrefixedInvalidQrIsInvalidNotIncomplete() {
        val reducer = ScanReducer()
        val waiting = reducer.reduce(ScanSessionState(), ScanEvent.StartSession).state

        // A broken JAMA record is a Denso kanban of unknown length, so the
        // 57-66 guidance the two fixed-length records use must not apply.
        val truncated = densoQrBox1.take(40)
        val rejected = reducer.reduce(
            waiting,
            ScanEvent.PayloadReceived(ScanPayload.qr(truncated)),
        )
        assertEquals(waiting, rejected.state)
        assertEquals(
            ScanEffect.InvalidScan(
                ScanFormat.QR,
                InvalidScanReason.INVALID_PAYLOAD,
                truncated.length,
            ),
            rejected.effects.single(),
        )

        // 63 characters sit between the two fixed record lengths and would
        // otherwise be reported as a truncated Sawai slip. The prefix is
        // matched case insensitively, as the record parser is.
        for (value in listOf(densoQrBox1.take(63), densoQrBox1.take(63).lowercase())) {
            val between = reducer.reduce(
                waiting,
                ScanEvent.PayloadReceived(ScanPayload.qr(value)),
            )
            assertEquals(waiting, between.state)
            assertEquals(
                ScanEffect.InvalidScan(
                    ScanFormat.QR,
                    InvalidScanReason.INVALID_PAYLOAD,
                    value.length,
                ),
                between.effects.single(),
            )
        }
    }

    @Test
    fun restoredDensoBoxesSeedDuplicateAndPartCounts() {
        val reducer = ScanReducer()
        var state = reducer.reduce(
            ScanReducer.initial(
                existingMatchedCount = 1,
                recordedBoxes = listOfNotNull(
                    RecordedBox.fromPayloads(densoQrBox1, densoTagBox1),
                ),
            ),
            ScanEvent.StartSession,
        ).state
        assertEquals(Destination.DENSO, state.destination)
        assertEquals("860150-7722", state.recordedBoxes.single().code)

        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox1))).state
        val duplicate = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox1)),
        )
        assertEquals(MatchResult.DUPLICATE, duplicate.state.result)
        assertEquals(1, duplicate.state.matchedCount)

        state = reducer.reduce(duplicate.state, ScanEvent.ManualNext).state
        state = reducer.reduce(state, ScanEvent.PayloadReceived(ScanPayload.qr(densoQrBox2))).state
        val second = reducer.reduce(
            state,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagBox2)),
        )
        assertEquals(MatchResult.MATCH, second.state.result)
        assertEquals(2, second.state.matchedCount)
        assertEquals(
            2,
            second.effects.filterIsInstance<ScanEffect.RecordMatch>().single().boxNumber,
        )

        // Another part number starts its own box count in the same session.
        var next = reducer.reduce(second.state, ScanEvent.ManualNext).state
        next = reducer.reduce(
            next,
            ScanEvent.PayloadReceived(ScanPayload.qr(densoQrOtherPart)),
        ).state
        val otherPart = reducer.reduce(
            next,
            ScanEvent.PayloadReceived(ScanPayload.code128(densoTagOtherPart)),
        )
        val record = otherPart.effects.filterIsInstance<ScanEffect.RecordMatch>().single()
        assertEquals("860150-7791", record.code)
        assertEquals(1, record.boxNumber)
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
