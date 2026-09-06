package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDeliveryGroupsTest {
    // Trailing spaces are part of the fixed-position record; the length
    // assertions below fail first if an editor ever trims them away.
    private val moltecQr1 =
        "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private val moltecQr2 =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val sawaiQr =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"

    @Test
    fun deliveryGroupsKeepFirstSeenOrderAndSumPackQuantities() {
        assertEquals(61, moltecQr1.length)
        assertEquals(61, moltecQr2.length)
        val entries = listOf(
            moltecEntry("box-1", moltecQr2, "PAF1-15-422@0NKD3C"),
            moltecEntry("box-2", moltecQr1, "D10E-50-N10B@0UBL00"),
            moltecEntry("box-3", moltecQr2, "PAF1-15-422@0NLL3C"),
        )

        val groups = entries.moltecDeliveryGroups()

        assertEquals(listOf("UAG5560", "U543820"), groups.map { it.deliveryNumber })
        assertEquals(listOf("box-1", "box-3"), groups[0].entries.map { it.id })
        assertEquals(2, groups[0].boxCount)
        assertEquals(240, groups[0].cumulativeQuantity)
        assertEquals(1, groups[1].boxCount)
        assertEquals(2, groups[1].cumulativeQuantity)
        assertEquals("PAF115422", groups[0].record.partNumber)
    }

    @Test
    fun sawaiAndUnrecordedPayloadsProduceNoDeliveryGroups() {
        assertEquals(66, sawaiQr.length)
        val entries = listOf(
            moltecEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C"),
            MatchEntry(id = "legacy", code = "BCJH-52-81GG"),
        )

        assertTrue(entries.moltecDeliveryGroups().isEmpty())
    }

    @Test
    fun resolvedDestinationPrefersTheStoredValueThenFallsBackToTheEntries() {
        val legacySawai = MatchSession(
            entries = listOf(moltecEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C")),
        )
        val legacyMoltec = MatchSession(
            entries = listOf(
                MatchEntry(id = "legacy", code = "PAF1-15-422"),
                moltecEntry("box-1", moltecQr2, "PAF1-15-422@0NKD3C"),
            ),
        )
        val stored = MatchSession(
            destination = Destination.MOLTEC,
            entries = listOf(moltecEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C")),
        )

        assertEquals(Destination.SAWAI, legacySawai.resolvedDestination())
        assertEquals(Destination.MOLTEC, legacyMoltec.resolvedDestination())
        assertEquals(Destination.MOLTEC, stored.resolvedDestination())
        assertNull(MatchSession().resolvedDestination())
        assertNull(
            MatchSession(entries = listOf(MatchEntry(id = "legacy", code = "LEGACY")))
                .resolvedDestination(),
        )
    }

    @Test
    fun instructionDateAndTimeAreFormattedAndUnexpectedValuesArePreserved() {
        assertEquals("09/08", formatMoltecDate("0908"))
        assertEquals("1231 ", formatMoltecDate("1231 "))
        assertEquals("098", formatMoltecDate("098"))
        assertEquals("", formatMoltecDate(""))
        assertEquals("00:00", formatMoltecTime("0000"))
        assertEquals("18:45", formatMoltecTime("1845"))
        assertNull(formatMoltecTime(null))
        assertEquals("18:4", formatMoltecTime("18:4"))
    }

    private fun moltecEntry(id: String, qrPayload: String, barcodePayload: String) = MatchEntry(
        id = id,
        code = "IGNORED",
        matchedAt = 1_700_000_000_000L,
        qrPayload = qrPayload,
        barcodePayload = barcodePayload,
    )
}
