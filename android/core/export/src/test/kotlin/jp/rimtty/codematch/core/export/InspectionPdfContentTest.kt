package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionPdfContentTest {
    private val utc = ZoneId.of("UTC")

    private val sawaiQr5281 =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val sawaiQr5581 =
        "DCLP675340BCJH5581GG020000120000001200L000000000000BLBDILLU93   0*"
    private val moltenQrD10E =
        "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private val moltenQrPAF1 =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val densoQr0140 = "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"

    @Test
    fun sawaiReportHasHeaderCountsColumnsAndOneRowPerPartNumberWithSuffix() {
        val document = InspectionPdfContent.build(sawaiSession(), AppLanguage.JAPANESE, utc)
        val text = document.texts()

        assertTrue(text.contains("検品レポート"))
        assertTrue(text.contains("セッション名: 朝便"))
        assertTrue(text.contains("仕向地: 澤井製作所"))
        assertTrue(text.contains("検査箱数: 3箱"))
        assertTrue(text.contains("品番数（枝番別）: 2"))
        assertFalse(text.contains("納品番号数"))

        val header = document.blocks.filterIsInstance<InspectionPdfBlock.TableHeader>().single()
        assertEquals(
            listOf("No", "品番", "箱数", "納入数量/箱", "数量計", "確認"),
            header.columns.map { it.title },
        )
        assertEquals(1f, header.columns.map { it.weight }.sum(), 0.001f)

        val rows = document.blocks.filterIsInstance<InspectionPdfBlock.TableRow>()
        assertEquals(
            listOf(
                listOf("1", "BCJH5281GG (02)", "2", "12", "24", ""),
                listOf("2", "BCJH5581GG (02)", "1", "12", "12", ""),
            ),
            rows.map { it.cells },
        )
        assertTrue(rows.all { it.checkboxColumn == it.columns.lastIndex })
        assertFalse("parsed rows print the raw part number", text.contains("BCJH-52-81GG"))
        assertEquals("検品表にあってこの一覧にない品番は、このセッションで照合されていません。", document.footerNote)
        assertTrue(document.generatedNote.startsWith("CodeMatch により生成"))
    }

    @Test
    fun englishSawaiReportUsesEnglishLabels() {
        val document = InspectionPdfContent.build(sawaiSession(), AppLanguage.ENGLISH, utc)
        val text = document.texts()

        assertTrue(text.contains("Inspection Report"))
        assertTrue(text.contains("Ship-to: Sawai Seisakusho"))
        assertTrue(text.contains("Boxes: 3 boxes"))
        assertTrue(text.contains("Part numbers (by suffix): 2"))
        val header = document.blocks.filterIsInstance<InspectionPdfBlock.TableHeader>().single()
        assertEquals(
            listOf("No", "Part no.", "Boxes", "Qty / box", "Total qty", "Check"),
            header.columns.map { it.title },
        )
        assertTrue(document.footerNote.startsWith("Part numbers on the inspection sheet"))
    }

    @Test
    fun moltenReportHasOneRowPerDeliveryNumberWithPartAndDeliveryPoint() {
        val session = MatchSession(
            startedAt = 1_700_000_000_000L,
            endedAt = 1_700_000_120_000L,
            destination = Destination.MOLTEN,
            entries = listOf(
                entry("one", "PAF1-15-422", moltenQrPAF1, "PAF1-15-422@0NKD3C"),
                entry("two", "D10E-50-N10B", moltenQrD10E, "D10E-50-N10B@0UBL00"),
                entry("three", "PAF1-15-422", moltenQrPAF1, "PAF1-15-422@0NLL3C"),
            ),
        )

        val document = InspectionPdfContent.build(session, AppLanguage.JAPANESE, utc)
        val text = document.texts()

        assertTrue(text.contains("仕向地: モルテン"))
        assertTrue(text.contains("検査箱数: 3箱"))
        assertTrue(text.contains("納品番号数: 2"))
        assertFalse(text.contains("品番数"))
        val header = document.blocks.filterIsInstance<InspectionPdfBlock.TableHeader>().single()
        assertEquals(
            listOf("No", "納品番号", "品番", "納入先", "箱数", "収容数/箱", "累計", "確認"),
            header.columns.map { it.title },
        )
        assertEquals(
            listOf(
                listOf("1", "U543820", "D10E50N10B", "MB", "1", "2", "2", ""),
                listOf("2", "UAG5560", "PAF115422", "FA2", "2", "120", "240", ""),
            ),
            document.blocks.filterIsInstance<InspectionPdfBlock.TableRow>().map { it.cells },
        )
    }

    @Test
    fun densoReportHasOneRowPerPartNumberAndNoInstructedQuantity() {
        val session = MatchSession(
            startedAt = 1_700_000_000_000L,
            destination = Destination.DENSO,
            entries = listOf(entry("one", "860150-7722", densoQr0140, "860150-7722@1DZ50O")),
        )

        val document = InspectionPdfContent.build(session, AppLanguage.JAPANESE, utc)
        val text = document.texts()

        assertTrue(text.contains("仕向地: デンソー"))
        assertTrue(text.contains("状態: 照合中"))
        assertTrue(text.contains("品番数: 1"))
        assertFalse(text.contains("枝番別"))
        assertFalse(text.contains("指示数"))
        val header = document.blocks.filterIsInstance<InspectionPdfBlock.TableHeader>().single()
        assertEquals(
            listOf("No", "品番", "箱数", "収容数/箱", "数量計", "確認"),
            header.columns.map { it.title },
        )
        assertEquals(
            listOf(listOf("1", "860150-7722", "1", "24", "24", "")),
            document.blocks.filterIsInstance<InspectionPdfBlock.TableRow>().map { it.cells },
        )
    }

    @Test
    fun legacyBoxWithoutPayloadKeepsARowWithDashQuantities() {
        val session = MatchSession(
            startedAt = 1_700_000_000_000L,
            destination = Destination.SAWAI,
            entries = listOf(
                entry("legacy", "KAAA-55-D86B", null, null),
                entry("one", "BCJH-52-81GG", sawaiQr5281, "BCJH-52-81GG@1N5X0C"),
            ),
        )

        val document = InspectionPdfContent.build(session, AppLanguage.JAPANESE, utc)

        assertTrue(document.texts().contains("検査箱数: 2箱"))
        assertTrue(document.texts().contains("品番数（枝番別）: 2"))
        assertEquals(
            listOf(
                listOf("1", "BCJH5281GG (02)", "1", "12", "12", ""),
                listOf("2", "KAAA-55-D86B", "1", "-", "-", ""),
            ),
            document.blocks.filterIsInstance<InspectionPdfBlock.TableRow>().map { it.cells },
        )
    }

    @Test
    fun emptySessionPrintsTheNoMatchesLineAndNoTable() {
        val document = InspectionPdfContent.build(MatchSession(startedAt = 0L), AppLanguage.JAPANESE, utc)

        assertTrue(document.texts().contains("一致したコードはありません。"))
        assertTrue(document.blocks.none { it is InspectionPdfBlock.TableHeader })
        assertTrue(document.blocks.none { it is InspectionPdfBlock.TableRow })
    }

    private fun sawaiSession() = MatchSession(
        startedAt = 1_700_000_000_000L,
        endedAt = 1_700_000_120_000L,
        name = "朝便",
        destination = Destination.SAWAI,
        entries = listOf(
            entry("one", "BCJH-55-81GG", sawaiQr5581, "BCJH-55-81GG@1KVV0C"),
            entry("two", "BCJH-52-81GG", sawaiQr5281, "BCJH-52-81GG@1N5X0C"),
            entry("three", "BCJH-52-81GG", sawaiQr5281, "BCJH-52-81GG@1N5X0D"),
        ),
    )

    private fun entry(id: String, code: String, qrPayload: String?, barcodePayload: String?) = MatchEntry(
        id = id,
        code = code,
        matchedAt = 1_700_000_001_000L,
        qrPayload = qrPayload,
        barcodePayload = barcodePayload,
    )

    private fun InspectionPdfDocument.texts(): String =
        blocks.filterIsInstance<InspectionPdfBlock.Text>().joinToString("\n") { it.block.text }
}
