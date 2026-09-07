package jp.rimtty.codematch.core.export

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export is meant for offline analysis of raw scans, so this test pins the
 * document shape and, above all, that payloads survive byte for byte: a Molten
 * record's trailing spaces are data and a trimmed payload is a different slip.
 */
class HistoryJsonExporterTest {
    private val exportedAt = Instant.parse("2026-09-07T01:23:45.678Z")

    private val moltenSession = MatchSession(
        id = "session-molten",
        startedAt = Instant.parse("2026-09-07T00:10:00.400Z").toEpochMilli(),
        endedAt = Instant.parse("2026-09-07T00:41:30.900Z").toEpochMilli(),
        name = "モルテン 午前",
        // Deliberately not stored: a session recorded before the destination
        // column existed must still export the destination its QRs identify.
        destination = null,
        entries = listOf(
            MatchEntry(
                id = "entry-1",
                code = "PAF1-15-422",
                matchedAt = Instant.parse("2026-09-07T00:12:00Z").toEpochMilli(),
                qrPayload = MOLTEN_PAF_QR,
                barcodePayload = "PAF1-15-422@0NKD3C",
                sequence = 0L,
            ),
            MatchEntry(
                id = "entry-2",
                code = "PAF1-15-422",
                matchedAt = Instant.parse("2026-09-07T00:19:20Z").toEpochMilli(),
                qrPayload = MOLTEN_PAF_QR,
                barcodePayload = "PAF1-15-422@0NLL3C",
                sequence = 1L,
            ),
            MatchEntry(
                id = "entry-3",
                code = "D10E-50-N10B",
                matchedAt = Instant.parse("2026-09-07T00:33:05Z").toEpochMilli(),
                qrPayload = MOLTEN_TRAILING_SPACE_QR,
                barcodePayload = "D10E-50-N10B@0UBL00",
                sequence = 2L,
            ),
        ),
    )

    private val sawaiLegacySession = MatchSession(
        id = "session-sawai",
        startedAt = Instant.parse("2026-09-06T23:00:00Z").toEpochMilli(),
        endedAt = null,
        name = null,
        destination = Destination.SAWAI,
        entries = listOf(
            MatchEntry(
                id = "entry-legacy",
                code = "BCJH-55-81GG",
                matchedAt = Instant.parse("2026-09-06T23:05:00Z").toEpochMilli(),
                // Recorded before payloads were kept.
                qrPayload = null,
                barcodePayload = null,
                sequence = 0L,
            ),
        ),
    )

    private val densoSession = MatchSession(
        id = "session-denso",
        startedAt = Instant.parse("2026-09-07T02:00:00Z").toEpochMilli(),
        endedAt = Instant.parse("2026-09-07T02:30:00Z").toEpochMilli(),
        name = "デンソー 午後",
        destination = Destination.DENSO,
        entries = listOf(
            MatchEntry(
                id = "entry-denso-1",
                code = "860150-7722",
                matchedAt = Instant.parse("2026-09-07T02:05:00Z").toEpochMilli(),
                qrPayload = DENSO_KANBAN_QR,
                barcodePayload = "860150-7722@1DZ50O",
                sequence = 0L,
            ),
        ),
    )

    @Test
    fun densoSessionExportsItsDestinationIdAndKanbanPayloadByteForByte() {
        val session = export(listOf(densoSession))["sessions"].asJsonArray[0].asJsonObject

        assertEquals("session-denso", session["id"].asString)
        assertEquals("denso", session["destination"].asString)
        val entry = session["entries"].asJsonArray[0].asJsonObject
        assertEquals("860150-7722", entry["code"].asString)
        assertEquals("860150-7722@1DZ50O", entry["barcodePayload"].asString)
        // The blank fixed-width fields inside the kanban are data.
        assertEquals(DENSO_KANBAN_QR, entry["qrPayload"].asString)
        assertEquals(221, entry["qrPayload"].asString.length)
    }

    @Test
    fun documentCarriesSchemaPlatformVersionAndUtcExportTimestamp() {
        val root = export(listOf(moltenSession, sawaiLegacySession))

        assertEquals(1, root["schemaVersion"].asInt)
        assertEquals(HistoryJsonExporter.SCHEMA_VERSION, root["schemaVersion"].asInt)
        assertEquals("android", root["platform"].asString)
        assertEquals("0.1.0 (1)", root["appVersion"].asString)
        // Seconds precision, UTC, with the trailing Z.
        assertEquals("2026-09-07T01:23:45Z", root["exportedAt"].asString)
    }

    @Test
    fun sessionsKeepRepositoryOrderAndResolveTheirDestinationId() {
        val sessions = export(listOf(moltenSession, sawaiLegacySession))["sessions"].asJsonArray

        assertEquals(2, sessions.size())
        val molten = sessions[0].asJsonObject
        val sawai = sessions[1].asJsonObject
        assertEquals("session-molten", molten["id"].asString)
        assertEquals("session-sawai", sawai["id"].asString)
        // Derived from the first entry that kept its QR, exactly like the PDF.
        assertEquals("molten", molten["destination"].asString)
        assertEquals("sawai", sawai["destination"].asString)
        assertEquals("モルテン 午前", molten["name"].asString)
        assertEquals("2026-09-07T00:10:00Z", molten["startedAt"].asString)
        assertEquals("2026-09-07T00:41:30Z", molten["endedAt"].asString)
        assertEquals("2026-09-06T23:00:00Z", sawai["startedAt"].asString)
    }

    @Test
    fun missingValuesAreEmittedAsJsonNullRatherThanOmitted() {
        val sessions = export(listOf(moltenSession, sawaiLegacySession))["sessions"].asJsonArray
        val sawai = sessions[1].asJsonObject
        val entry = sawai["entries"].asJsonArray[0].asJsonObject

        assertTrue(sawai.has("name"))
        assertTrue(sawai["name"].isJsonNull)
        assertTrue(sawai.has("endedAt"))
        assertTrue(sawai["endedAt"].isJsonNull)
        assertTrue(entry.has("qrPayload"))
        assertTrue(entry["qrPayload"].isJsonNull)
        assertTrue(entry.has("barcodePayload"))
        assertTrue(entry["barcodePayload"].isJsonNull)
        assertEquals("BCJH-55-81GG", entry["code"].asString)
        assertEquals("2026-09-06T23:05:00Z", entry["matchedAt"].asString)
    }

    @Test
    fun entriesStayInSequenceOrderAndPayloadsSurviveByteForByte() {
        val entries = export(listOf(moltenSession, sawaiLegacySession))["sessions"]
            .asJsonArray[0].asJsonObject["entries"].asJsonArray

        assertEquals(3, entries.size())
        assertEquals(
            listOf("entry-1", "entry-2", "entry-3"),
            entries.map { it.asJsonObject["id"].asString },
        )
        assertEquals(
            listOf("PAF1-15-422@0NKD3C", "PAF1-15-422@0NLL3C", "D10E-50-N10B@0UBL00"),
            entries.map { it.asJsonObject["barcodePayload"].asString },
        )

        val firstQr = entries[0].asJsonObject["qrPayload"].asString
        val trailingSpaceQr = entries[2].asJsonObject["qrPayload"].asString
        assertEquals(MOLTEN_PAF_QR, firstQr)
        assertEquals(61, firstQr.length)
        // The four trailing spaces are the blank instruction-time field.
        assertEquals(MOLTEN_TRAILING_SPACE_QR, trailingSpaceQr)
        assertEquals(61, trailingSpaceQr.length)
        assertTrue(trailingSpaceQr.endsWith("020908    "))
    }

    @Test
    fun stringsAreEscapedSoAnyRecordedPayloadRoundTrips() {
        val hostile = buildString {
            append("\"quote\" \\slash\\ \ttab \nnewline ")
            append(Char(0x01)).append("ctrl ")
            append(Char(0x0C)).append("formfeed ")
            append("\bbackspace / end  ")
        }
        val session = MatchSession(
            id = "session-escapes",
            startedAt = 0L,
            name = hostile,
            entries = listOf(
                MatchEntry(
                    id = "entry-escapes",
                    code = "CODE",
                    matchedAt = 0L,
                    qrPayload = hostile,
                    barcodePayload = null,
                ),
            ),
        )

        val json = HistoryJsonExporter.build(
            sessions = listOf(session),
            exportedAt = exportedAt,
            appVersion = "0.1.0 (1)",
        )
        // Control characters must never reach the document unescaped; the only
        // raw newlines are the ones the writer uses for indentation.
        assertFalse(json.any { it.isISOControl() && it != '\n' })

        val exported = JsonParser.parseString(json).asJsonObject["sessions"]
            .asJsonArray[0].asJsonObject
        assertEquals(hostile, exported["name"].asString)
        assertEquals(
            hostile,
            exported["entries"].asJsonArray[0].asJsonObject["qrPayload"].asString,
        )
    }

    @Test
    fun emptyHistoryStillProducesAValidDocument() {
        val root = export(emptyList())

        assertEquals(0, root["sessions"].asJsonArray.size())
        assertEquals("2026-09-07T01:23:45Z", root["exportedAt"].asString)
    }

    @Test
    fun platformCanBeOverriddenForACrossPlatformFixture() {
        val json = HistoryJsonExporter.build(
            sessions = emptyList(),
            exportedAt = exportedAt,
            platform = "ios",
            appVersion = "1.0 (4)",
        )

        assertEquals("ios", JsonParser.parseString(json).asJsonObject["platform"].asString)
    }

    @Test
    fun fileNameIsStampedToTheMinuteInTheCallersZone() {
        assertEquals(
            "codematch-history-20260907-1023.json",
            HistoryJsonExporter.fileName(exportedAt, ZoneId.of("Asia/Tokyo")),
        )
        assertEquals(
            "codematch-history-20260907-0123.json",
            HistoryJsonExporter.fileName(exportedAt, ZoneId.of("UTC")),
        )
    }

    private fun export(sessions: List<MatchSession>): JsonObject = JsonParser.parseString(
        HistoryJsonExporter.build(
            sessions = sessions,
            exportedAt = exportedAt,
            appVersion = "0.1.0 (1)",
        ),
    ).asJsonObject

    private companion object {
        /** Real モルテン labels; see shared/test-fixtures/matching-cases.json. */
        const val MOLTEN_PAF_QR =
            "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
        /** A real デンソー kanban; see the same fixture. */
        const val DENSO_KANBAN_QR =
            "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
        const val MOLTEN_TRAILING_SPACE_QR =
            "AK6805D10E50N10B         U543820000MB    S600700000020908    "
    }
}
