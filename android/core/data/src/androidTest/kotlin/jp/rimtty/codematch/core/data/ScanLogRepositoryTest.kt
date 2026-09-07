package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.ScanLogEvent
import jp.rimtty.codematch.core.model.ScanLogEventKind
import jp.rimtty.codematch.core.model.ScanLogReason
import jp.rimtty.codematch.core.model.ScanLogSource
import jp.rimtty.codematch.core.model.ScanLogStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanLogRepositoryTest {
    private lateinit var database: CodeMatchDatabase
    private lateinit var repository: ScanLogRepository

    @Before
    fun setUp() {
        database = CodeMatchDatabaseFactory.inMemory(applicationContext())
        repository = ScanLogRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun everyFieldSurvivesTheRoundTripInInsertionOrder() = runBlocking {
        repository.record(
            event(
                at = 100L,
                kind = ScanLogEventKind.MATCH,
                destination = Destination.MOLTEN,
                // The trailing spaces of a Molten record are data.
                qrPayload = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000",
                barcodePayload = "PAF1-15-422@0NKD3C",
                code = "PAF1-15-422",
                boxNumber = 3,
            ),
        )
        repository.record(
            event(
                at = 200L,
                kind = ScanLogEventKind.REJECTED,
                reason = ScanLogReason.WRONG_DESTINATION,
            ),
        )

        val exported = repository.export()
        assertEquals(2, exported.size)
        val match = exported.first()
        assertEquals(100L, match.atEpochMillis)
        assertEquals("session-1", match.sessionId)
        assertEquals(ScanLogSource.BLUETOOTH, match.source)
        assertEquals(ScanLogStep.BARCODE, match.step)
        assertEquals(ScanLogEventKind.MATCH, match.event)
        assertNull(match.reason)
        assertEquals(Destination.MOLTEN, match.destination)
        assertEquals(
            "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000",
            match.qrPayload,
        )
        assertEquals("PAF1-15-422@0NKD3C", match.barcodePayload)
        assertEquals("PAF1-15-422", match.code)
        assertEquals(3, match.boxNumber)
        assertNull(match.message)

        val rejected = exported.last()
        assertEquals(ScanLogReason.WRONG_DESTINATION, rejected.reason)
        assertNull(rejected.destination)
        assertNull(rejected.code)
        assertNull(rejected.boxNumber)
    }

    @Test
    fun theCountFlowFollowsInsertionsAndClearEmptiesTheLog() = runBlocking {
        assertEquals(0, repository.count.first())

        repeat(3) { index -> repository.record(event(at = index.toLong())) }
        assertEquals(3, repository.count.first())

        repository.clear()
        assertEquals(0, repository.count.first())
        assertEquals(emptyList<ScanLogEvent>(), repository.export())
    }

    @Test
    fun writingPastTheLimitDropsTheOldestEventsOnly() = runBlocking {
        // A five-row cap exercises the same SQL the 5,000-row default uses
        // without writing five thousand rows on a device.
        val capped = ScanLogRepository(database, limit = 5)

        repeat(8) { index -> capped.record(event(at = index.toLong())) }

        assertEquals(5, capped.count.first())
        assertEquals(
            listOf(3L, 4L, 5L, 6L, 7L),
            capped.export().map { it.atEpochMillis },
        )
    }

    @Test
    fun theDefaultLimitIsTheFiveThousandEventsBothAppsPromise() {
        assertEquals(5_000, ScanLogRepository.DEFAULT_LIMIT)
    }

    private fun event(
        at: Long,
        kind: String = ScanLogEventKind.QR_ACCEPTED,
        reason: String? = null,
        destination: Destination? = null,
        qrPayload: String? = null,
        barcodePayload: String? = null,
        code: String? = null,
        boxNumber: Int? = null,
    ): ScanLogEvent = ScanLogEvent(
        atEpochMillis = at,
        sessionId = "session-1",
        source = ScanLogSource.BLUETOOTH,
        step = ScanLogStep.BARCODE,
        event = kind,
        reason = reason,
        destination = destination,
        qrPayload = qrPayload,
        barcodePayload = barcodePayload,
        code = code,
        boxNumber = boxNumber,
    )

    private fun applicationContext(): Context = ApplicationProvider.getApplicationContext()
}
