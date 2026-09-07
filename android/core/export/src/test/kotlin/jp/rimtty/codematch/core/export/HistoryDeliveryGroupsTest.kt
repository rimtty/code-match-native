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
    private val moltenQr1 =
        "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private val moltenQr2 =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val sawaiQr =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    // A real デンソー kanban; the runs of spaces are blank fields, not padding.
    private val densoQr = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"

    @Test
    fun deliveryGroupsKeepFirstSeenOrderAndSumPackQuantities() {
        assertEquals(61, moltenQr1.length)
        assertEquals(61, moltenQr2.length)
        val entries = listOf(
            moltenEntry("box-1", moltenQr2, "PAF1-15-422@0NKD3C"),
            moltenEntry("box-2", moltenQr1, "D10E-50-N10B@0UBL00"),
            moltenEntry("box-3", moltenQr2, "PAF1-15-422@0NLL3C"),
        )

        val groups = entries.moltenDeliveryGroups()

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
            moltenEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C"),
            MatchEntry(id = "legacy", code = "BCJH-52-81GG"),
        )

        assertTrue(entries.moltenDeliveryGroups().isEmpty())
    }

    @Test
    fun densoEntriesProduceNoDeliveryGroups() {
        assertEquals(221, densoQr.length)
        val denso = moltenEntry("denso", densoQr, "860150-7722@1DZ50O")
        val sawai = moltenEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C")
        val molten = moltenEntry("molten", moltenQr2, "PAF1-15-422@0NKD3C")

        // Delivery numbers are a Molten concept only.
        assertTrue(listOf(denso).moltenDeliveryGroups().isEmpty())
        assertEquals(Destination.DENSO, MatchSession(entries = listOf(denso)).resolvedDestination())

        val record = denso.densoRecord()
        assertEquals("8601507722", record?.partNumber)
        assertEquals("0140", record?.kanbanSerial)
        assertEquals(24, record?.packQuantity)
        assertEquals(72, record?.instructedQuantity)
        assertEquals("2026/09/08", record?.formattedDeliveryDate)
        // A Sawai or Molten entry is never read at Denso field positions.
        assertNull(sawai.densoRecord())
        assertNull(molten.densoRecord())
        assertNull(MatchEntry(id = "legacy", code = "860150-7722").densoRecord())
    }

    @Test
    fun resolvedDestinationPrefersTheStoredValueThenFallsBackToTheEntries() {
        val legacySawai = MatchSession(
            entries = listOf(moltenEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C")),
        )
        val legacyMolten = MatchSession(
            entries = listOf(
                MatchEntry(id = "legacy", code = "PAF1-15-422"),
                moltenEntry("box-1", moltenQr2, "PAF1-15-422@0NKD3C"),
            ),
        )
        val stored = MatchSession(
            destination = Destination.MOLTEN,
            entries = listOf(moltenEntry("sawai", sawaiQr, "BCJH-52-81GG@1N5X0C")),
        )

        assertEquals(Destination.SAWAI, legacySawai.resolvedDestination())
        assertEquals(Destination.MOLTEN, legacyMolten.resolvedDestination())
        assertEquals(Destination.MOLTEN, stored.resolvedDestination())
        assertNull(MatchSession().resolvedDestination())
        assertNull(
            MatchSession(entries = listOf(MatchEntry(id = "legacy", code = "LEGACY")))
                .resolvedDestination(),
        )
    }

    @Test
    fun instructionDateAndTimeAreFormattedAndUnexpectedValuesArePreserved() {
        assertEquals("09/08", formatMoltenDate("0908"))
        assertEquals("1231 ", formatMoltenDate("1231 "))
        assertEquals("098", formatMoltenDate("098"))
        assertEquals("", formatMoltenDate(""))
        assertEquals("00:00", formatMoltenTime("0000"))
        assertEquals("18:45", formatMoltenTime("1845"))
        assertNull(formatMoltenTime(null))
        assertEquals("18:4", formatMoltenTime("18:4"))
    }

    private fun moltenEntry(id: String, qrPayload: String, barcodePayload: String) = MatchEntry(
        id = id,
        code = "IGNORED",
        matchedAt = 1_700_000_000_000L,
        qrPayload = qrPayload,
        barcodePayload = barcodePayload,
    )
}
