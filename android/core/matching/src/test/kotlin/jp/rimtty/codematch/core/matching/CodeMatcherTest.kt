package jp.rimtty.codematch.core.matching

import jp.rimtty.codematch.core.model.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMatcherTest {
    // Real label data used by the Swift tests and by the shared fixture.
    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"

    @Test
    fun normalizeUppercasesAndKeepsOnlyAsciiLettersAndDigits() {
        assertEquals("ABC123", CodeMatcher.normalize(" a-B_c 123!"))
        assertEquals("SS", CodeMatcher.normalize("ß"))
        assertEquals("", CodeMatcher.normalize("日本語・🙂"))
    }

    @Test
    fun partNumberFromBarcodeUsesTextBeforeFirstAt() {
        assertEquals("BCJH5281GG", CodeMatcher.partNumberFromBarcode(barcodePayload))
        assertEquals("KAAA55D86B", CodeMatcher.partNumberFromBarcode("KAAA-55-D86B@0Y5U0I"))
        assertEquals("BCJH5281GG", CodeMatcher.partNumberFromBarcode("BCJH-52-81GG"))
        assertEquals("LEFT", CodeMatcher.partNumberFromBarcode("left@RIGHT@THIRD"))
        assertNull(CodeMatcher.partNumberFromBarcode("@ABC123"))
        assertNull(CodeMatcher.partNumberFromBarcode(""))
    }

    @Test
    fun partNumberFromQrReadsTheStandardCardAndItemPositions() {
        assertEquals("BCJH5281GG", CodeMatcher.partNumberFromQr(qrPayload))
        assertEquals(
            "DFR55581GA",
            CodeMatcher.partNumberFromQr(
                "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
            )
        )
        assertNull(CodeMatcher.partNumberFromQr("HELLO WORLD 1234567890"))
        assertNull(CodeMatcher.partNumberFromQr("SHORT"))
        assertNull(CodeMatcher.partNumberFromQr("ABCD123456invalid!"))
    }

    @Test
    fun compareMatchesRealPairAndIgnoresManagementCode() {
        assertEquals(MatchResult.MATCH, CodeMatcher.compare(qrPayload, barcodePayload))
        assertEquals(
            MatchResult.MATCH,
            CodeMatcher.compare(qrPayload, "BCJH-52-81GG@ZZZZZZ")
        )
        assertEquals(
            MatchResult.MISMATCH,
            CodeMatcher.compare(qrPayload, "BCJH-55-81GG@1KVV0C")
        )
    }

    @Test
    fun compareUsesOnlyConservativeContainmentForNonStandardQr() {
        assertEquals(
            MatchResult.MATCH,
            CodeMatcher.compare("PART:BCJH-52-81GG;QTY:12", barcodePayload)
        )
        assertEquals(
            MatchResult.MISMATCH,
            CodeMatcher.compare("PART:DFR5-55-8SDA;QTY:30", barcodePayload)
        )
        assertEquals(
            MatchResult.MISMATCH,
            CodeMatcher.compare("PART:ABC-12;QTY:30", "ABC-12@1")
        )
    }

    @Test
    fun emptyOrUnparseableValuesMismatch() {
        assertEquals(MatchResult.MISMATCH, CodeMatcher.compare("", ""))
        assertEquals(MatchResult.MISMATCH, CodeMatcher.compare(qrPayload, ""))
        assertEquals(MatchResult.MISMATCH, CodeMatcher.compare("", "ABC-12-3456@1"))
    }

    @Test
    fun formatPartNumberUsesTheFourTwoFourDisplayShape() {
        assertEquals("BCJH-52-81GG", CodeMatcher.formatPartNumber("BCJH5281GG"))
        assertEquals("ABC", CodeMatcher.formatPartNumber("ABC"))
        assertEquals("abcd-ef-ghij", CodeMatcher.formatPartNumber("abcdefghij"))
    }

    @Test
    fun kanbanRecordParsesAllFields() {
        val record = KanbanQrRecord.parse(qrPayload)

        assertNotNull(record)
        assertEquals("DCLP675300", record?.cardNumber)
        assertEquals("BCJH5281GG", record?.partNumber)
        assertEquals("02", record?.partSuffix)
        assertEquals(12.0, record?.deliveryQuantity ?: Double.NaN, 0.0)
        assertEquals(12.0, record?.instructedQuantity ?: Double.NaN, 0.0)
        assertEquals("L", record?.factoryCode)
        assertEquals("BLBDI", record?.warehouseCode)
        assertEquals("LLU92", record?.supplyPointCode)
    }

    @Test
    fun kanbanRecordHandlesBlankSuffixAndParsesQuantities() {
        val payload =
            "DAYA005100DFR55581GA  0001000000010000Y      000000BYBYTLYB16   0*"
        val record = KanbanQrRecord.parse(payload)

        assertNotNull(record)
        assertEquals("DFR55581GA", record?.partNumber)
        assertNull(record?.partSuffix)
        assertEquals(100.0, record?.deliveryQuantity ?: Double.NaN, 0.0)
        assertEquals("Y", record?.factoryCode)
        assertEquals("BYBYT", record?.warehouseCode)
        assertEquals("LYB16", record?.supplyPointCode)
    }

    @Test
    fun kanbanRecordRejectsNonStandardPayloadsAndChecksStrictScanLength() {
        assertNull(KanbanQrRecord.parse("PART:BCJH-52-81GG;QTY:12"))
        assertNull(KanbanQrRecord.parse("SHORT"))
        assertTrue(KanbanQrRecord.isValidScanPayload(qrPayload))
        assertFalse(KanbanQrRecord.isValidScanPayload(barcodePayload))
        assertFalse(KanbanQrRecord.isValidScanPayload(qrPayload.dropLast(1)))
        assertTrue(KanbanQrRecord.isValidScanPayload("$qrPayload "))
    }

    @Test
    fun tagRecordValidationRejectsReverseOrderAndWrongShape() {
        assertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload))
        assertTrue(TagBarcodeRecord.isValidScanPayload("KAAA-55-D86B@0Y5U0I"))
        assertTrue(TagBarcodeRecord.isValidScanPayload("kAAA-55-d86b@0y5u0i"))
        assertFalse(TagBarcodeRecord.isValidScanPayload(qrPayload))
        assertFalse(TagBarcodeRecord.isValidScanPayload("BCJH-52-81GG"))
        assertFalse(TagBarcodeRecord.isValidScanPayload("ABC-12-3456@1"))
    }

    @Test
    fun tagRecordParsingKeepsPartAndManagementCodeSeparate() {
        val record = TagBarcodeRecord.parse(barcodePayload)
        assertEquals("BCJH-52-81GG", record?.partNumber)
        assertEquals("1N5X0C", record?.managementCode)

        val noCode = TagBarcodeRecord.parse("BCJH-52-81GG")
        assertEquals("BCJH-52-81GG", noCode?.partNumber)
        assertNull(noCode?.managementCode)
        assertNull(TagBarcodeRecord.parse("  "))
        assertNull(TagBarcodeRecord.parse(" @ABC"))
        assertEquals(
            "second@third",
            TagBarcodeRecord.parse("first@second@third")?.managementCode
        )
    }

    @Test
    fun sharedMatchingFixturesHaveTheSameResultsAsSwift() {
        val resource = javaClass.getResourceAsStream("/matching-cases.json")
        assertNotNull("matching-cases.json must be on the test runtime classpath", resource)
        val json = resource!!.bufferedReader().use { it.readText() }
        val fixture = SharedFixtureJson.decode(json)

        assertEquals(1, fixture.schemaVersion)
        assertEquals(5, fixture.cases.size)
        assertEquals(
            "Shared fixture IDs must be unique",
            fixture.cases.size,
            fixture.cases.map { it.id }.toSet().size
        )
        assertTrue("Shared fixture IDs must not be empty", fixture.cases.all { it.id.isNotEmpty() })

        fixture.cases.forEach { fixtureCase ->
            val expected = when (fixtureCase.expected) {
                "match" -> MatchResult.MATCH
                "mismatch" -> MatchResult.MISMATCH
                else -> error("Unknown shared fixture result for ${fixtureCase.id}")
            }
            assertEquals(
                "Shared fixture failed: ${fixtureCase.id}",
                expected,
                CodeMatcher.compare(
                    qrPayload = fixtureCase.qrPayload,
                    barcodePayload = fixtureCase.barcodePayload
                )
            )
        }

        // The shared case deliberately contains two empty values. It must be
        // treated as a failed comparison, not silently skipped by the loader.
        val emptyCase = fixture.cases.single { it.id == "empty-values" }
        assertEquals("", emptyCase.qrPayload)
        assertEquals("", emptyCase.barcodePayload)
        assertEquals("mismatch", emptyCase.expected)
        assertEquals(
            MatchResult.MISMATCH,
            CodeMatcher.compare(emptyCase.qrPayload, emptyCase.barcodePayload)
        )
    }

    private object SharedFixtureJson {
        private val gson = com.google.gson.Gson()

        fun decode(json: String): SharedFixture =
            gson.fromJson(json, SharedFixture::class.java)
                ?: error("Shared fixture JSON must contain an object")
    }

    // Mutable defaults let Gson use the generated no-arg constructors without
    // relying on constructor parameter names or final-field reflection.
    private class SharedFixture {
        var schemaVersion: Int = 0
        var cases: List<SharedFixtureCase> = emptyList()
    }

    private class SharedFixtureCase {
        var id: String = ""
        var qrPayload: String = ""
        var barcodePayload: String = ""
        var expected: String = ""
    }
}
