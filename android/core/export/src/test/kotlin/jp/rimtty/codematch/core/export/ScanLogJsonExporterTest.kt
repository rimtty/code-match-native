package jp.rimtty.codematch.core.export

import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.ScanLogEvent
import jp.rimtty.codematch.core.model.ScanLogEventKind
import jp.rimtty.codematch.core.model.ScanLogReason
import jp.rimtty.codematch.core.model.ScanLogSource
import jp.rimtty.codematch.core.model.ScanLogStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLogJsonExporterTest {
    private val header = ScanLogJsonExporter.ExportHeader(
        appVersion = "1.0 (7)",
        exportedAt = Instant.parse("2026-09-08T04:05:06.789Z"),
    )

    @Test
    fun headerLineDescribesTheExportAndPrecedesOneLinePerEvent() {
        val document = ScanLogJsonExporter.buildJsonLines(
            events = listOf(matchEvent, rejectedEvent),
            header = header,
        )

        val lines = document.trimEnd('\n').split("\n")
        assertEquals(3, lines.size)
        assertEquals(
            """{"type":"header","schemaVersion":1,"platform":"android",""" +
                """"appVersion":"1.0 (7)","exportedAt":"2026-09-08T04:05:06Z",""" +
                """"eventCount":2,"limit":5000}""",
            lines[0],
        )
        // Key order and explicit nulls are part of the cross-platform contract.
        assertEquals(
            """{"at":"2026-09-08T04:05:06.789Z","session":"session-1",""" +
                """"source":"bluetooth","step":"barcode","event":"match","reason":null,""" +
                """"destination":"sawai","qr":"QR-1","barcode":"BCJH-52-81GG@1N5X0C",""" +
                """"code":"BCJH-52-81GG","boxNumber":2,"message":null}""",
            lines[1],
        )
        assertEquals(
            """{"at":"2026-09-08T04:05:07.000Z","session":null,"source":"camera",""" +
                """"step":"qr","event":"rejected","reason":"wrong_destination",""" +
                """"destination":null,"qr":"QR-2","barcode":null,"code":null,""" +
                """"boxNumber":null,"message":null}""",
            lines[2],
        )
    }

    @Test
    fun anEmptyLogStillProducesAHeaderWithZeroEvents() {
        val document = ScanLogJsonExporter.buildJsonLines(events = emptyList(), header = header)

        assertEquals(1, document.trimEnd('\n').split("\n").size)
        assertTrue(document.contains("\"eventCount\":0"))
    }

    @Test
    fun quotesBackslashesAndNewlinesInAPayloadStayOnOneLineAndRoundTrip() {
        val payload = "AB\"C\\D\nE\tF"
        val document = ScanLogJsonExporter.buildJsonLines(
            events = listOf(rejectedEvent.copy(qrPayload = payload)),
            header = header,
        )

        val lines = document.trimEnd('\n').split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("""\"C\\D\nE\tF"""))
        val parsed = JsonParser.parseString(lines[1]).asJsonObject
        assertEquals(payload, parsed.get("qr").asString)
    }

    @Test
    fun trailingSpacesOfAMoltenRecordSurviveTheExport() {
        val padded = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
        val document = ScanLogJsonExporter.buildJsonLines(
            events = listOf(matchEvent.copy(qrPayload = padded)),
            header = header,
        )

        val parsed = JsonParser.parseString(document.trimEnd('\n').split("\n")[1]).asJsonObject
        assertEquals(padded, parsed.get("qr").asString)
    }

    @Test
    fun fileNameIsStampedInTheCallersZone() {
        assertEquals(
            "codematch-scan-log-20260908-1305.jsonl",
            ScanLogJsonExporter.fileName(
                Instant.parse("2026-09-08T04:05:06Z"),
                ZoneId.of("Asia/Tokyo"),
            ),
        )
        assertEquals(
            "codematch-scan-log-20260908-0405.jsonl",
            ScanLogJsonExporter.fileName(
                Instant.parse("2026-09-08T04:05:06Z"),
                ZoneId.of("UTC"),
            ),
        )
    }

    private val matchEvent = ScanLogEvent(
        atEpochMillis = Instant.parse("2026-09-08T04:05:06.789Z").toEpochMilli(),
        sessionId = "session-1",
        source = ScanLogSource.BLUETOOTH,
        step = ScanLogStep.BARCODE,
        event = ScanLogEventKind.MATCH,
        destination = Destination.SAWAI,
        qrPayload = "QR-1",
        barcodePayload = "BCJH-52-81GG@1N5X0C",
        code = "BCJH-52-81GG",
        boxNumber = 2,
    )

    private val rejectedEvent = ScanLogEvent(
        atEpochMillis = Instant.parse("2026-09-08T04:05:07Z").toEpochMilli(),
        sessionId = null,
        source = ScanLogSource.CAMERA,
        step = ScanLogStep.QR,
        event = ScanLogEventKind.REJECTED,
        reason = ScanLogReason.WRONG_DESTINATION,
        qrPayload = "QR-2",
    )
}
