package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionReportContentTest {
    // Real label payloads; see shared/test-fixtures/matching-cases.json.
    private val sawaiQr5281 =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val sawaiQr5581 =
        "DCLP675340BCJH5581GG020000120000001200L000000000000BLBDILLU93   0*"
    private val sawaiQrNoSuffix =
        "DAYA004770DFR55281GA  0001000000010000Y      000000BYBYTLYB15   0*"
    private val moltenQrD10E =
        "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private val moltenQrPAF1 =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val densoQr0140 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private val densoQr0141 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0141SWS    20260908S0010000720000009924543330454333M6"

    @Test
    fun sawaiRowsAreKeyedByPartNumberAndSuffixAndSortedByPartNumber() {
        val session = MatchSession(
            startedAt = 0L,
            destination = Destination.SAWAI,
            entries = listOf(
                entry("one", "BCJH-55-81GG", sawaiQr5581, "BCJH-55-81GG@1KVV0C"),
                entry("two", "BCJH-52-81GG", sawaiQr5281, "BCJH-52-81GG@1N5X0C"),
                entry("three", "BCJH-52-81GG", sawaiQr5281, "BCJH-52-81GG@1N5X0D"),
                entry("four", "DFR5-52-81GA", sawaiQrNoSuffix, "DFR5-52-81GA@001F2S"),
            ),
        )
        val expectedQuantity = KanbanQrRecord.parse(sawaiQr5281)?.deliveryQuantity
        requireNotNull(expectedQuantity)

        val report = InspectionReportContent.build(session)

        assertEquals(InspectionLayout.SAWAI, report.layout)
        assertEquals(listOf("BCJH5281GG (02)", "BCJH5581GG (02)", "DFR55281GA"), report.rows.map { it.keyText })
        val first = report.rows[0]
        assertEquals(2, first.boxCount)
        assertEquals(expectedQuantity, first.quantityPerBox)
        assertEquals(expectedQuantity * 2, first.totalQuantity)
        assertNull(first.partNumber)
        assertNull(first.deliveryDestination)
        assertTrue(report.rows.none { it.isUnparsed })
        assertEquals(session.matchedCount, report.rows.sumOf { it.boxCount })
    }

    @Test
    fun moltenRowsAreOnePerDeliveryNumberWithRawPartAndDeliveryPoint() {
        val session = MatchSession(
            startedAt = 0L,
            destination = Destination.MOLTEN,
            entries = listOf(
                entry("one", "PAF1-15-422", moltenQrPAF1, "PAF1-15-422@0NKD3C"),
                entry("two", "PAF1-15-422", moltenQrPAF1, "PAF1-15-422@0NLL3C"),
                entry("three", "D10E-50-N10B", moltenQrD10E, "D10E-50-N10B@0UBL00"),
            ),
        )

        val report = InspectionReportContent.build(session)

        assertEquals(InspectionLayout.MOLTEN, report.layout)
        assertEquals(listOf("U543820", "UAG5560"), report.rows.map { it.keyText })
        val paf1 = report.rows[1]
        assertEquals("PAF115422", paf1.partNumber)
        assertEquals("FA2", paf1.deliveryDestination)
        assertEquals(2, paf1.boxCount)
        assertEquals(120.0, paf1.quantityPerBox)
        assertEquals(240.0, paf1.totalQuantity)
        val d10e = report.rows[0]
        assertEquals("D10E50N10B", d10e.partNumber)
        assertEquals("MB", d10e.deliveryDestination)
        assertEquals(1, d10e.boxCount)
        assertEquals(2.0, d10e.totalQuantity)
    }

    @Test
    fun densoRowsAreOnePerPartNumberFormattedSixFour() {
        val session = MatchSession(
            startedAt = 0L,
            destination = Destination.DENSO,
            entries = listOf(
                entry("one", "860150-7722", densoQr0140, "860150-7722@1DZ50O"),
                entry("two", "860150-7722", densoQr0141, "860150-7722@1DZ50P"),
            ),
        )

        val report = InspectionReportContent.build(session)

        assertEquals(InspectionLayout.DENSO, report.layout)
        assertEquals(1, report.rowCount)
        val row = report.rows.single()
        assertEquals("860150-7722", row.keyText)
        assertEquals(2, row.boxCount)
        assertEquals(24.0, row.quantityPerBox)
        assertEquals(48.0, row.totalQuantity)
    }

    @Test
    fun boxesWithoutAParsableQrTrailAsUnparsedRowsSoNoBoxIsDropped() {
        val session = MatchSession(
            startedAt = 0L,
            destination = Destination.SAWAI,
            entries = listOf(
                entry("legacy", "ZZZZ-00-0000", qrPayload = null, barcodePayload = null),
                entry("one", "BCJH-52-81GG", sawaiQr5281, "BCJH-52-81GG@1N5X0C"),
                entry("garbled", "AAAA-11-1111", "legacy payload", "AAAA-11-1111@X"),
            ),
        )

        val report = InspectionReportContent.build(session)

        assertEquals(listOf("BCJH5281GG (02)", "AAAA-11-1111", "ZZZZ-00-0000"), report.rows.map { it.keyText })
        val legacy = report.rows.last()
        assertTrue(legacy.isUnparsed)
        assertEquals(1, legacy.boxCount)
        assertNull(legacy.quantityPerBox)
        assertNull(legacy.totalQuantity)
        assertEquals(session.matchedCount, report.rows.sumOf { it.boxCount })
    }

    @Test
    fun sessionWithoutDestinationFallsBackToSawaiLayoutWithEveryBoxUnparsed() {
        val session = MatchSession(
            startedAt = 0L,
            entries = listOf(entry("one", "PART-1", qrPayload = null, barcodePayload = null)),
        )

        val report = InspectionReportContent.build(session)

        assertEquals(InspectionLayout.SAWAI, report.layout)
        assertTrue(report.rows.all { it.isUnparsed })
    }

    @Test
    fun aDensoKanbanIsNeverReadAsASawaiSlip() {
        // The lenient Sawai parser accepts a JAMA payload as a card number; the
        // report must key on the destination, not on which parser succeeds.
        val session = MatchSession(
            startedAt = 0L,
            destination = Destination.SAWAI,
            entries = listOf(entry("one", "860150-7722", densoQr0140, "860150-7722@1DZ50O")),
        )

        val report = InspectionReportContent.build(session)

        assertEquals(listOf("860150-7722"), report.rows.map { it.keyText })
        assertTrue(report.rows.single().isUnparsed)
    }

    private fun entry(
        id: String,
        code: String,
        qrPayload: String?,
        barcodePayload: String?,
    ) = MatchEntry(
        id = id,
        code = code,
        matchedAt = 1_700_000_000_000L,
        qrPayload = qrPayload,
        barcodePayload = barcodePayload,
    )
}
