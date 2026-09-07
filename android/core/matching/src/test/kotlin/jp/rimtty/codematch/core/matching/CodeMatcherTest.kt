package jp.rimtty.codematch.core.matching

import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMatcherTest {
    // Real label data used by the Swift tests and by the shared fixture
    // (destination Sawai, 66 characters).
    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"

    // Destination Molten, 61 characters. The trailing spaces are part of the
    // record, so never let an editor trim these literals.
    private val moltenQrPayload =
        "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    private val moltenBarcodePayload = "D10E-50-N10B@0UBL00"

    // A Molten pair whose part number is nine characters (a 4-2-3 tag).
    private val moltenShortPartQrPayload =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val moltenShortPartBarcodePayload = "PAF1-15-422@0NKD3C"

    // Destination Denso, the real 221-character kanban of box 0140 (part
    // 860150-7722). The runs of spaces are item values (144, 402, 515, 516),
    // so never let an editor collapse them.
    private val densoQrPayload =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
    private val densoBarcodePayload = "860150-7722@1DZ50O"

    // Synthetic kanbans with a different item layout (three items, another
    // header length). Both would be claimed by another destination if Denso
    // were not probed first: 66 characters parse as a Sawai record and 61
    // characters parse as a complete Molten record.
    private val densoSawaiLengthPayload =
        "JAMA5002950000000031521010410112120140      8601507722000000000024"
    private val densoMoltenLengthPayload =
        "JAMA500295000000003104101120715210860150772200000240140123456"

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
    fun nonStandardQrNeverMatchesEvenWhenItContainsThePartNumber() {
        assertEquals(
            MatchResult.MISMATCH,
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
        assertEquals("BCJH-52-81GG", CodeMatcher.formatPartNumber("BCJH5281GG", null))
        // A nine-character Molten part number prints as 4-2-3.
        assertEquals("PAF1-15-422", CodeMatcher.formatPartNumber("PAF115422", null))
        assertEquals("ABC", CodeMatcher.formatPartNumber("ABC", null))
        assertEquals("ABCDEFGHIJK", CodeMatcher.formatPartNumber("ABCDEFGHIJK", null))
        assertEquals("abcd-ef-ghij", CodeMatcher.formatPartNumber("abcdefghij", null))
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
        assertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload, Destination.SAWAI))
        assertTrue(TagBarcodeRecord.isValidScanPayload("KAAA-55-D86B@0Y5U0I", Destination.SAWAI))
        assertTrue(TagBarcodeRecord.isValidScanPayload("kAAA-55-d86b@0y5u0i", Destination.SAWAI))
        assertFalse(TagBarcodeRecord.isValidScanPayload(qrPayload, Destination.SAWAI))
        assertFalse(TagBarcodeRecord.isValidScanPayload("BCJH-52-81GG", Destination.SAWAI))
        assertFalse(TagBarcodeRecord.isValidScanPayload("ABC-12-3456@1", Destination.SAWAI))
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

        assertEquals(2, fixture.schemaVersion)
        assertEquals(47, fixture.cases.size)
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

            // Cases that declare a destination also pin the detector's answer.
            val destination = fixtureCase.destination ?: return@forEach
            assertEquals(
                "Shared fixture destination failed: ${fixtureCase.id}",
                destination,
                CodeMatcher.detectDestination(fixtureCase.qrPayload)?.id
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

    /**
     * The 12 field label pairs (label-NN) from the customer spec must pass the
     * scan boundaries shared by camera and Bluetooth (66-character QR record,
     * 4-2-4@code tag format), and the QR item number must equal the tag part
     * number. Labels 9 and 10 carry a blank suffix.
     */
    @Test
    fun sharedLabelPairsPassBothScanBoundaries() {
        val resource = javaClass.getResourceAsStream("/matching-cases.json")
        assertNotNull("matching-cases.json must be on the test runtime classpath", resource)
        val fixture = SharedFixtureJson.decode(resource!!.bufferedReader().use { it.readText() })
        val labelPairs = fixture.cases.filter { it.id.startsWith("label-") && it.expected == "match" }
        assertEquals(12, labelPairs.size)

        labelPairs.forEach { pair ->
            assertTrue(pair.id, KanbanQrRecord.isValidScanPayload(pair.qrPayload))
            assertTrue(
                pair.id,
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, Destination.SAWAI)
            )
            val record = KanbanQrRecord.parse(pair.qrPayload)
            assertEquals(pair.id, CodeMatcher.partNumberFromBarcode(pair.barcodePayload), record?.partNumber)
            val expectsBlankSuffix = pair.id.startsWith("label-09") || pair.id.startsWith("label-10")
            assertEquals(pair.id, if (expectsBlankSuffix) null else "02", record?.partSuffix)
        }

        // Field parsing of the real records: quantities are stored x100 and
        // the factory code takes the three observed values L / Y / A.
        fun record(prefix: String) =
            KanbanQrRecord.parse(labelPairs.first { it.id.startsWith(prefix) }.qrPayload)
        val label02 = record("label-02")
        assertEquals("DCLP675340", label02?.cardNumber)
        assertEquals(12.0, label02?.deliveryQuantity ?: 0.0, 0.001)
        assertEquals("L", label02?.factoryCode)
        assertEquals("BLBDI", label02?.warehouseCode)
        assertEquals("LLU93", label02?.supplyPointCode)
        val label11 = record("label-11")
        assertEquals(336.0, label11?.deliveryQuantity ?: 0.0, 0.001)
        assertEquals(336.0, label11?.instructedQuantity ?: 0.0, 0.001)
        assertEquals("Y", label11?.factoryCode)
        assertEquals("LYB14", label11?.supplyPointCode)
        val label12 = record("label-12")
        assertEquals(18.0, label12?.deliveryQuantity ?: 0.0, 0.001)
        assertEquals("A", label12?.factoryCode)
        assertEquals("BAB15", label12?.warehouseCode)
        assertEquals("LAB14", label12?.supplyPointCode)
    }

    @Test
    fun moltenRecordParsesAllFieldsFromRealPayloads() {
        assertEquals(61, moltenShortPartQrPayload.length)
        val shortPart = MoltenQrRecord.parse(moltenShortPartQrPayload)
        assertNotNull(shortPart)
        assertEquals("AK6805", shortPart?.ordererCode)
        assertEquals("PAF115422", shortPart?.partNumber)
        assertEquals("UAG5560", shortPart?.deliveryNumber)
        assertEquals("FA2", shortPart?.deliveryDestination)
        assertEquals("P59", shortPart?.tyLocation)
        assertEquals("01FEM", shortPart?.supplyPoint)
        assertEquals(120, shortPart?.packQuantity)
        assertEquals("0908", shortPart?.instructionDate)
        assertEquals("0000", shortPart?.instructionTime)
        assertEquals(moltenShortPartQrPayload, shortPart?.canonicalPayload)

        // The blank TY location and the blank time field become null.
        assertEquals(61, moltenQrPayload.length)
        val blankOptionals = MoltenQrRecord.parse(moltenQrPayload)
        assertNotNull(blankOptionals)
        assertEquals("D10E50N10B", blankOptionals?.partNumber)
        assertEquals("U543820", blankOptionals?.deliveryNumber)
        assertEquals("MB", blankOptionals?.deliveryDestination)
        assertNull(blankOptionals?.tyLocation)
        assertEquals("S6007", blankOptionals?.supplyPoint)
        assertEquals(2, blankOptionals?.packQuantity)
        assertEquals("0908", blankOptionals?.instructionDate)
        assertNull(blankOptionals?.instructionTime)
    }

    @Test
    fun moltenRecordPadsShortPayloadAndCanonicalizesIdentity() {
        val stripped = moltenQrPayload.dropLast(4)
        assertEquals(57, stripped.length)
        assertEquals(moltenQrPayload, MoltenQrRecord.canonicalPayload(stripped))
        assertEquals(
            MoltenQrRecord.parse(moltenQrPayload)?.canonicalPayload,
            MoltenQrRecord.parse(stripped)?.canonicalPayload
        )
        // Lowercase callbacks are uppercased rather than rejected.
        assertEquals(
            moltenQrPayload,
            MoltenQrRecord.parse(moltenQrPayload.lowercase())?.canonicalPayload
        )

        assertNull(MoltenQrRecord.parse(moltenQrPayload.dropLast(5)))
        assertNull(MoltenQrRecord.parse("$moltenQrPayload "))
    }

    @Test
    fun moltenRecordRejectsWrongLengthQuantityDateTimeAndPartField() {
        // Characters 7-16 are left aligned, so a leading space is invalid.
        assertNull(MoltenQrRecord.parse(replaceCharAt(moltenQrPayload, 6, ' ')))
        // Characters 47-53 (pack quantity) are digits only.
        assertNull(MoltenQrRecord.parse(replaceCharAt(moltenQrPayload, 46, 'X')))
        // Characters 54-57 (date) are digits only.
        assertNull(MoltenQrRecord.parse(replaceCharAt(moltenQrPayload, 53, 'X')))
        // Characters 58-61 are either four digits or four spaces.
        val brokenTime = moltenShortPartQrPayload.dropLast(4) + "00 0"
        assertEquals(61, brokenTime.length)
        assertNull(MoltenQrRecord.parse(brokenTime))
        // Characters outside the record alphabet are rejected.
        assertNull(MoltenQrRecord.parse(replaceCharAt(moltenQrPayload, 0, '*')))

        assertFalse(MoltenQrRecord.isValidScanPayload(qrPayload))
        assertFalse(MoltenQrRecord.isValidScanPayload(barcodePayload))
        assertTrue(MoltenQrRecord.isValidScanPayload(moltenQrPayload))
    }

    @Test
    fun detectDestinationSeparatesSawaiAndMoltenAndRejectsOthers() {
        assertEquals(Destination.SAWAI, CodeMatcher.detectDestination(qrPayload))
        assertEquals(Destination.MOLTEN, CodeMatcher.detectDestination(moltenQrPayload))
        // Transport terminators are tolerated on both destinations.
        assertEquals(Destination.SAWAI, CodeMatcher.detectDestination("$qrPayload\r\n"))
        assertEquals(Destination.MOLTEN, CodeMatcher.detectDestination("$moltenQrPayload\r\n"))
        // A Sawai record has no padding at its edges, so added whitespace is
        // tolerated; a Molten record is padded, so the same whitespace overflows it.
        assertEquals(Destination.SAWAI, CodeMatcher.detectDestination(" $qrPayload \n"))
        assertNull(CodeMatcher.detectDestination(" $moltenQrPayload "))

        assertNull(CodeMatcher.detectDestination("PART:BCJH-52-81GG;QTY:12"))
        assertNull(CodeMatcher.detectDestination("X".repeat(66)))
        assertNull(CodeMatcher.detectDestination(barcodePayload))
        // Shorter than the minimum Molten payload: never padded up.
        assertNull(CodeMatcher.detectDestination(moltenQrPayload.dropLast(5)))
        // Leading spaces are data, not padding, so a shifted record is rejected.
        val shifted = " " + moltenQrPayload.dropLast(1)
        assertEquals(61, shifted.length)
        assertNull(CodeMatcher.detectDestination(shifted))

        assertEquals(66, CodeMatcher.expectedQrLength(Destination.SAWAI))
        assertEquals(61, CodeMatcher.expectedQrLength(Destination.MOLTEN))

        // A JAMA self-describing record is Denso whatever its length is, and a
        // Denso kanban has no fixed length to compare an incomplete read with.
        assertEquals(Destination.DENSO, CodeMatcher.detectDestination(densoQrPayload))
        assertEquals(Destination.DENSO, CodeMatcher.detectDestination("$densoQrPayload\r\n"))
        assertEquals(
            Destination.DENSO,
            CodeMatcher.detectDestination(densoQrPayload.lowercase()),
        )
        // A truncated kanban no longer adds up, so it is no destination at all.
        assertNull(CodeMatcher.detectDestination(densoQrPayload.dropLast(1)))
        assertNull(CodeMatcher.expectedQrLength(Destination.DENSO))
    }

    @Test
    fun tagValidationAllowsFourTwoThreeOnlyForMolten() {
        assertTrue(
            TagBarcodeRecord.isValidScanPayload(
                moltenShortPartBarcodePayload,
                Destination.MOLTEN
            )
        )
        assertFalse(
            TagBarcodeRecord.isValidScanPayload(
                moltenShortPartBarcodePayload,
                Destination.SAWAI
            )
        )

        // Denso is deliberately absent: its tags are 6-4, so the 4-2-4 and
        // 4-2-3 payloads below are invalid in a Denso session.
        listOf(Destination.SAWAI, Destination.MOLTEN).forEach { destination ->
            assertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload, destination))
            assertTrue(TagBarcodeRecord.isValidScanPayload(moltenBarcodePayload, destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload("PAF1-15-42@0NKD3C", destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload("PAF1-15-42200@0NKD3C", destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload(moltenQrPayload, destination))
        }
    }

    @Test
    fun boxIdentityIncludesTagOnlyForMolten() {
        // A Sawai slip carries its own card number, so the tag is irrelevant.
        val sawaiIdentity = CodeMatcher.boxIdentity(qrPayload, barcodePayload)
        assertEquals(qrPayload, sawaiIdentity)
        assertEquals(sawaiIdentity, CodeMatcher.boxIdentity(qrPayload, null))
        assertEquals(sawaiIdentity, CodeMatcher.boxIdentity(qrPayload, "BCJH-52-81GG@ZZZZZZ"))

        // A Molten slip repeats per box, so the tag distinguishes the boxes.
        val firstBox = CodeMatcher.boxIdentity(moltenQrPayload, moltenBarcodePayload)
        assertNotNull(firstBox)
        assertNotEquals(
            firstBox,
            CodeMatcher.boxIdentity(moltenQrPayload, "D10E-50-N50B@0UXL0K")
        )
        assertEquals(
            firstBox,
            CodeMatcher.boxIdentity(moltenQrPayload.dropLast(4), moltenBarcodePayload)
        )
        assertNull(CodeMatcher.boxIdentity(moltenQrPayload, null))
        assertNull(CodeMatcher.boxIdentity(moltenQrPayload, "   "))

        assertNull(CodeMatcher.boxIdentity("PART:BCJH-52-81GG;QTY:12", barcodePayload))
        assertNull(CodeMatcher.boxIdentity("", barcodePayload))

        // A Denso kanban carries a per-box serial (item 152), so like Sawai its
        // QR alone identifies the box and the tag never enters the key.
        val densoIdentity = CodeMatcher.boxIdentity(densoQrPayload, densoBarcodePayload)
        assertEquals(densoQrPayload, densoIdentity)
        assertEquals(densoIdentity, CodeMatcher.boxIdentity(densoQrPayload, null))
        assertEquals(
            densoIdentity,
            CodeMatcher.boxIdentity(densoQrPayload, "860150-7722@1DZB0O"),
        )
    }

    @Test
    fun partNumberFromQrIsDestinationAware() {
        assertEquals("BCJH5281GG", CodeMatcher.partNumberFromQr(qrPayload))
        assertEquals("D10E50N10B", CodeMatcher.partNumberFromQr(moltenQrPayload))
        assertEquals("PAF115422", CodeMatcher.partNumberFromQr(moltenShortPartQrPayload))
        // A card-number prefix alone no longer identifies a destination.
        assertNull(CodeMatcher.partNumberFromQr(qrPayload.take(20)))
        assertNull(CodeMatcher.partNumberFromQr(moltenQrPayload.dropLast(5)))
        // A Sawai payload keeps matching when the scanner adds whitespace.
        assertEquals("BCJH5281GG", CodeMatcher.partNumberFromQr(" ${qrPayload.lowercase()}\n"))
        assertEquals(
            MatchResult.MATCH,
            CodeMatcher.compare(" ${qrPayload.lowercase()}\n", barcodePayload)
        )

        // Denso reads item 104 of the JAMA record, not a fixed position.
        assertEquals("8601507722", CodeMatcher.partNumberFromQr(densoQrPayload))
        assertEquals("8601507722", CodeMatcher.partNumberFromQr(densoMoltenLengthPayload))
        assertEquals(
            MatchResult.MATCH,
            CodeMatcher.compare(densoQrPayload, densoBarcodePayload)
        )
        assertEquals(
            MatchResult.MISMATCH,
            CodeMatcher.compare(densoQrPayload, "860150-7791@01335C")
        )
    }

    /**
     * The seven Molten pairs (molten-NN) must pass the scan boundaries shared by
     * camera and Bluetooth (61-character QR record, 4-2-3/4-2-4@code tag format),
     * and the QR part number must equal the tag part number.
     */
    @Test
    fun sharedMoltenFixturesPassBothScanBoundaries() {
        val resource = javaClass.getResourceAsStream("/matching-cases.json")
        assertNotNull("matching-cases.json must be on the test runtime classpath", resource)
        val fixture = SharedFixtureJson.decode(resource!!.bufferedReader().use { it.readText() })
        val moltenPairs =
            fixture.cases.filter { it.id.startsWith("molten-") && it.expected == "match" }
        assertEquals(7, moltenPairs.size)

        moltenPairs.forEach { pair ->
            assertTrue(pair.id, MoltenQrRecord.isValidScanPayload(pair.qrPayload))
            assertTrue(
                pair.id,
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, Destination.MOLTEN)
            )
            assertEquals(pair.id, Destination.MOLTEN, CodeMatcher.detectDestination(pair.qrPayload))
            val record = MoltenQrRecord.parse(pair.qrPayload)
            assertEquals(
                pair.id,
                CodeMatcher.partNumberFromBarcode(pair.barcodePayload),
                record?.partNumber
            )
            // Only the PAF1 series carries a nine-character part number.
            val expectedLength = if (pair.id.contains("PAF1")) 9 else 10
            assertEquals(pair.id, expectedLength, record?.partNumber?.length)
        }
    }

    /**
     * The seven Denso pairs (denso-NN) must pass the scan boundaries shared by
     * camera and Bluetooth (a parseable JAMA kanban record, a 6-4@code tag),
     * and the QR item number must equal the tag part number. Every kanban is a
     * separate box because item 152 differs.
     */
    @Test
    fun sharedDensoFixturesPassBothScanBoundaries() {
        val resource = javaClass.getResourceAsStream("/matching-cases.json")
        assertNotNull("matching-cases.json must be on the test runtime classpath", resource)
        val fixture = SharedFixtureJson.decode(resource!!.bufferedReader().use { it.readText() })
        val densoPairs =
            fixture.cases.filter { it.id.startsWith("denso-") && it.expected == "match" }
        assertEquals(7, densoPairs.size)

        densoPairs.forEach { pair ->
            assertEquals(pair.id, 221, pair.qrPayload.length)
            assertTrue(pair.id, DensoKanbanQrRecord.isValidScanPayload(pair.qrPayload))
            assertTrue(
                pair.id,
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, Destination.DENSO)
            )
            // The same tag is refused in a session locked to another destination.
            assertFalse(
                pair.id,
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, Destination.SAWAI)
            )
            assertFalse(
                pair.id,
                TagBarcodeRecord.isValidScanPayload(pair.barcodePayload, Destination.MOLTEN)
            )
            assertEquals(pair.id, Destination.DENSO, CodeMatcher.detectDestination(pair.qrPayload))
            val record = DensoKanbanQrRecord.parse(pair.qrPayload)
            assertEquals(
                pair.id,
                CodeMatcher.partNumberFromBarcode(pair.barcodePayload),
                record?.partNumber
            )
            assertEquals(pair.id, 10, record?.partNumber?.length)
            // The tag prints the part number 6-4.
            assertEquals(
                pair.id,
                TagBarcodeRecord.parse(pair.barcodePayload)?.partNumber,
                CodeMatcher.formatPartNumber(record?.partNumber.orEmpty(), Destination.DENSO)
            )
        }

        // Seven kanban serials, therefore seven distinct boxes over two parts.
        assertEquals(
            7,
            densoPairs.mapNotNull { DensoKanbanQrRecord.parse(it.qrPayload)?.kanbanSerial }
                .toSet()
                .size
        )
        assertEquals(
            7,
            densoPairs.mapNotNull { CodeMatcher.boxIdentity(it.qrPayload, it.barcodePayload) }
                .toSet()
                .size
        )
        assertEquals(
            2,
            densoPairs.mapNotNull { CodeMatcher.partNumberFromQr(it.qrPayload) }.toSet().size
        )
    }

    @Test
    fun densoRecordParsesAllItemsFromRealPayload() {
        assertEquals(221, densoQrPayload.length)
        val record = DensoKanbanQrRecord.parse(densoQrPayload)

        assertNotNull(record)
        assertEquals("5", record?.version)
        assertEquals("5000001021", record?.preamble)
        assertEquals(21, record?.orderedItems?.size)
        assertEquals(
            listOf(
                "100", "104", "111", "112", "121", "124", "127", "141", "142", "144", "152",
                "402", "515", "516", "519", "520", "521", "526", "523", "522", "401",
            ),
            record?.orderedItems?.map { it.id }
        )
        assertEquals("20", record?.formType)
        assertEquals("8601507722", record?.partNumber)
        assertEquals("00", record?.packagingCode)
        assertEquals(24, record?.packQuantity)
        assertEquals("D850", record?.nextProcess)
        assertEquals("C01008-45", record?.instructionCode)
        assertEquals("0140", record?.kanbanSerial)
        assertEquals("SWS", record?.managementNumber)
        assertEquals("20260908", record?.deliveryDate)
        assertEquals("2026/09/08", record?.formattedDeliveryDate)
        assertEquals("S001", record?.deliveryRun)
        assertEquals(72, record?.instructedQuantity)
        assertEquals("9924543330", record?.itemNumber)
        assertEquals("M6", record?.receivingCode)
        // Blank item values keep their width in the raw item map.
        assertEquals("      ", record?.items?.get("144"))
        assertEquals("M", record?.items?.get("142"))
        assertEquals(densoQrPayload, record?.canonicalPayload)

        // A lowercase callback is uppercased rather than rejected.
        assertEquals(
            densoQrPayload,
            DensoKanbanQrRecord.parse(densoQrPayload.lowercase())?.canonicalPayload
        )
        assertTrue(DensoKanbanQrRecord.isValidScanPayload(densoQrPayload))
        assertEquals(densoQrPayload, DensoKanbanQrRecord.canonicalPayload(densoQrPayload))
        assertFalse(DensoKanbanQrRecord.isValidScanPayload(qrPayload))
        assertFalse(DensoKanbanQrRecord.isValidScanPayload(moltenQrPayload))
        assertFalse(DensoKanbanQrRecord.isValidScanPayload(densoBarcodePayload))
    }

    @Test
    fun densoRecordParsesADifferentItemLayout() {
        // Three items in another order, a different header length, and a kanban
        // serial padded with spaces.
        val record = DensoKanbanQrRecord.parse(densoSawaiLengthPayload)
        assertNotNull(record)
        assertEquals(listOf("152", "104", "112"), record?.orderedItems?.map { it.id })
        assertEquals("8601507722", record?.partNumber)
        assertEquals(24, record?.packQuantity)
        assertEquals("0140", record?.kanbanSerial)
        // Items the layout does not declare are simply absent.
        assertNull(record?.formType)
        assertNull(record?.deliveryDate)
        assertNull(record?.formattedDeliveryDate)
        assertNull(record?.instructedQuantity)

        // Both synthetic payloads are complete records of another destination,
        // so they pin the detection order rather than the lengths.
        assertEquals(66, densoSawaiLengthPayload.length)
        assertTrue(KanbanQrRecord.isValidScanPayload(densoSawaiLengthPayload))
        assertEquals(Destination.DENSO, CodeMatcher.detectDestination(densoSawaiLengthPayload))

        assertEquals(61, densoMoltenLengthPayload.length)
        assertTrue(MoltenQrRecord.isValidScanPayload(densoMoltenLengthPayload))
        assertEquals(Destination.DENSO, CodeMatcher.detectDestination(densoMoltenLengthPayload))
        assertEquals("0140123456", DensoKanbanQrRecord.parse(densoMoltenLengthPayload)?.kanbanSerial)
    }

    @Test
    fun densoRecordRejectsBrokenHeaderOrDataLength() {
        assertNull(DensoKanbanQrRecord.parse(""))
        assertNull(DensoKanbanQrRecord.parse("JAMA"))
        // Another format prefix, a non-numeric version, a non-numeric length.
        assertNull(DensoKanbanQrRecord.parse("JAMB" + densoQrPayload.substring(4)))
        assertNull(DensoKanbanQrRecord.parse("JAMAX" + densoQrPayload.substring(5)))
        assertNull(
            DensoKanbanQrRecord.parse(
                densoQrPayload.substring(0, 5) + "01X9" + densoQrPayload.substring(9)
            )
        )
        // A header longer than the payload.
        assertNull(
            DensoKanbanQrRecord.parse(
                densoQrPayload.substring(0, 5) + "9999" + densoQrPayload.substring(9)
            )
        )
        // 119 - 14 = 105 is a whole number of item definitions, 118 - 14 = 104
        // is not, and 114 - 14 = 100 is but then the lengths no longer add up.
        assertNull(
            DensoKanbanQrRecord.parse(
                densoQrPayload.substring(0, 5) + "0118" + densoQrPayload.substring(9)
            )
        )
        assertNull(
            DensoKanbanQrRecord.parse(
                densoQrPayload.substring(0, 5) + "0114" + densoQrPayload.substring(9)
            )
        )
        // The declared lengths must equal the data section exactly.
        assertNull(DensoKanbanQrRecord.parse(densoQrPayload.dropLast(1)))
        assertNull(DensoKanbanQrRecord.parse(densoQrPayload + "0"))

        // A repeated item id has no defined interpretation.
        val duplicateItemId = "JAMA5" + "0034" + "5000000004" +
            "10410" + "10410" + "11207" + "15210" +
            "8601507722" + "8601507722" + "0000024" + "0140123456"
        assertNull(DensoKanbanQrRecord.parse(duplicateItemId))

        // Required items: 104 (part number), 112 (pack quantity), 152 (serial).
        val withoutPartNumber = "JAMA5" + "0024" + "5000000002" +
            "11207" + "15210" + "0000024" + "0140123456"
        assertNull(DensoKanbanQrRecord.parse(withoutPartNumber))
        val withoutPackQuantity = "JAMA5" + "0024" + "5000000002" +
            "10410" + "15210" + "8601507722" + "0140123456"
        assertNull(DensoKanbanQrRecord.parse(withoutPackQuantity))
        val nonNumericPackQuantity = "JAMA5" + "0029" + "5000000003" +
            "15210" + "10410" + "11212" +
            "0140      " + "8601507722" + "00000000002X"
        assertNull(DensoKanbanQrRecord.parse(nonNumericPackQuantity))
        val blankKanbanSerial = "JAMA5" + "0029" + "5000000003" +
            "15210" + "10410" + "11212" +
            "          " + "8601507722" + "000000000024"
        assertNull(DensoKanbanQrRecord.parse(blankKanbanSerial))

        // A broken kanban is not silently downgraded to another destination.
        assertNull(CodeMatcher.detectDestination(densoQrPayload.dropLast(1)))
        assertNull(CodeMatcher.partNumberFromQr(densoQrPayload.dropLast(1)))
        assertNull(CodeMatcher.boxIdentity(densoQrPayload.dropLast(1), densoBarcodePayload))
    }

    @Test
    fun tagValidationAllowsSixFourOnlyForDenso() {
        assertTrue(TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, Destination.DENSO))
        assertTrue(TagBarcodeRecord.isValidScanPayload("860150-7722@1dz50o", Destination.DENSO))
        // An unlocked session accepts every destination's tag format.
        assertTrue(TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, null))
        assertTrue(TagBarcodeRecord.isValidScanPayload(barcodePayload, null))
        assertTrue(TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, null))

        // 6-4 is refused in a Sawai or Molten session, and 4-2-4 / 4-2-3 are
        // refused in a Denso session.
        assertFalse(TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, Destination.SAWAI))
        assertFalse(TagBarcodeRecord.isValidScanPayload(densoBarcodePayload, Destination.MOLTEN))
        assertFalse(TagBarcodeRecord.isValidScanPayload(barcodePayload, Destination.DENSO))
        assertFalse(
            TagBarcodeRecord.isValidScanPayload(moltenShortPartBarcodePayload, Destination.DENSO)
        )

        val destinations = listOf(Destination.SAWAI, Destination.MOLTEN, Destination.DENSO, null)
        destinations.forEach { destination ->
            assertFalse(TagBarcodeRecord.isValidScanPayload("860150-772@1DZ50O", destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload("86015-7722@1DZ50O", destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload("8601507722@1DZ50O", destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload("860150-7722", destination))
            assertFalse(TagBarcodeRecord.isValidScanPayload(densoQrPayload, destination))
        }
    }

    @Test
    fun formatPartNumberIsDestinationAware() {
        // Only Denso prints 6-4; every other destination keeps 4-2-4 / 4-2-3.
        assertEquals("860150-7722", CodeMatcher.formatPartNumber("8601507722", Destination.DENSO))
        assertEquals("8601-50-7722", CodeMatcher.formatPartNumber("8601507722", Destination.SAWAI))
        assertEquals("8601-50-7722", CodeMatcher.formatPartNumber("8601507722", Destination.MOLTEN))
        assertEquals("8601-50-7722", CodeMatcher.formatPartNumber("8601507722", null))
        assertEquals("BCJH-52-81GG", CodeMatcher.formatPartNumber("BCJH5281GG", Destination.SAWAI))
        assertEquals("PAF1-15-422", CodeMatcher.formatPartNumber("PAF115422", Destination.MOLTEN))
        // A Denso part number of any other length is printed unchanged.
        assertEquals("PAF115422", CodeMatcher.formatPartNumber("PAF115422", Destination.DENSO))
        assertEquals("ABC", CodeMatcher.formatPartNumber("ABC", Destination.DENSO))
        assertEquals(
            "ABCDEFGHIJK",
            CodeMatcher.formatPartNumber("ABCDEFGHIJK", Destination.DENSO)
        )
    }

    private fun replaceCharAt(payload: String, index: Int, character: Char): String =
        payload.substring(0, index) + character + payload.substring(index + 1)

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
        var destination: String? = null
    }
}
