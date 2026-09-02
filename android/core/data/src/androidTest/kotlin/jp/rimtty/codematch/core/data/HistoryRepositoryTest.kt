package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.EndSessionOutcome
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.core.model.ScanCheckpointInputSource
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private lateinit var database: CodeMatchDatabase
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        database = CodeMatchDatabaseFactory.inMemory(applicationContext())
        repository = HistoryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun beginSessionTrimsNameAndReusesActiveSession() = runBlocking {
        val id = repository.beginSession(name = "  午前の照合  ", at = 100L)

        assertEquals(id, repository.beginSession(name = "別名", at = 200L))
        val active = repository.activeSession.first()
        assertNotNull(active)
        assertEquals(id, active?.id)
        assertEquals("午前の照合", active?.name)
        assertEquals(active, repository.observeSession(id).first())
    }

    @Test
    fun blankNameIsStoredAsNull() = runBlocking {
        val id = repository.beginSession(name = " \n\t", at = 100L)

        assertNull(repository.observeSession(id).first()?.name)
        assertEquals("", repository.observeSession(id).first()?.displayName)
    }

    @Test
    fun recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates() = runBlocking {
        val id = repository.beginSession(at = 100L)

        assertEquals(1, repository.recordMatch("  BCJH5281GG  ", at = 101L))
        assertEquals(1, repository.recordMatch("OTHER00001", at = 102L))
        assertEquals(2, repository.recordMatch("BCJH5281GG", at = 103L))

        val session = repository.observeSession(id).first()
        assertNotNull(session)
        assertEquals(listOf("BCJH5281GG", "OTHER00001", "BCJH5281GG"), session?.entries?.map { it.code })
        assertEquals(listOf(0L, 1L, 2L), session?.entries?.map { it.sequence })
        assertEquals(listOf("BCJH5281GG", "OTHER00001"), session?.groupedEntries?.map { it.code })
        assertEquals(2, session?.groupedEntries?.first()?.boxCount)
        assertEquals(2, repository.activeSessionMatchCount("  BCJH5281GG "))
    }

    @Test
    fun payloadsArePersistedWithEachDuplicateEntry() = runBlocking {
        val id = repository.beginSession(at = 100L)

        repository.recordMatch(
            code = "ABC1234567",
            qrPayload = "qr-1",
            barcodePayload = "barcode-1",
            at = 101L,
        )
        repository.recordMatch(
            code = "ABC1234567",
            qrPayload = "qr-2",
            barcodePayload = "barcode-2",
            at = 102L,
        )

        val entries = repository.observeSession(id).first()!!.entries
        assertEquals(listOf("qr-1", "qr-2"), entries.map { it.qrPayload })
        assertEquals(listOf("barcode-1", "barcode-2"), entries.map { it.barcodePayload })
    }

    @Test
    fun endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt() = runBlocking {
        val emptyId = repository.beginSession(at = 100L)
        assertEquals(EndSessionOutcome.DeletedEmpty(emptyId), repository.endActiveSession(at = 110L))
        assertNull(repository.observeSession(emptyId).first())
        assertEquals(EndSessionOutcome.NotFound, repository.endActiveSession(at = 120L))

        val endedId = repository.beginSession(at = 200L)
        repository.recordMatch("ABC1234567", at = 201L)
        assertEquals(
            EndSessionOutcome.Ended(endedId, 210L),
            repository.endSession(endedId, at = 210L),
        )
        assertEquals(
            EndSessionOutcome.AlreadyEnded(endedId, 210L),
            repository.endSession(endedId, at = 220L),
        )
        assertFalse(repository.activeSession.first()?.id == endedId)
        assertEquals(210L, repository.observeSession(endedId).first()?.endedAt)
    }

    @Test
    fun sessionsAreNewestFirstAndRenameBlankBecomesNull() = runBlocking {
        val firstId = repository.beginSession(name = "first", at = 100L)
        repository.recordMatch("FIRST00001", at = 101L)
        repository.endActiveSession(at = 110L)

        val secondId = repository.beginSession(name = "second", at = 200L)
        repository.recordMatch("SECOND0001", at = 201L)
        repository.endActiveSession(at = 210L)
        repository.renameSession(secondId, "  ")

        assertEquals(listOf(secondId, firstId), repository.sessions.first().map { it.id })
        assertEquals(null, repository.observeSession(secondId).first()?.name)
        assertEquals("", repository.observeSession(secondId).first()?.displayName)
    }

    @Test
    fun deletingSessionCascadesEntries() = runBlocking {
        val id = repository.beginSession(at = 100L)
        repository.recordMatch("ABC1234567", at = 101L)

        assertTrue(repository.deleteSession(id))
        assertEquals(0, database.entryDao().countForSession(id))
        assertNull(repository.observeSession(id).first())
        assertFalse(repository.deleteSession(id))
    }

    @Test
    fun activeSessionIsRestoredAfterDatabaseRecreation() = runBlocking {
        val context = applicationContext()
        val databaseName = "history-${UUID.randomUUID()}.db"
        val firstDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
        val firstRepository = HistoryRepository(firstDatabase)
        val id = firstRepository.beginSession(name = "persisted", at = 100L)
        firstRepository.recordMatch("ABC1234567", at = 101L)
        firstDatabase.close()

        val restoredDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
        try {
            val restored = HistoryRepository(restoredDatabase).activeSession.first()
            assertNotNull(restored)
            assertEquals(id, restored?.id)
            assertEquals(1, restored?.matchedCount)
            assertTrue(restored?.isActive == true)
        } finally {
            restoredDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun checkpointAndMatchSurviveDatabaseReopenWithEntryCountAsSourceOfTruth() = runBlocking {
        val context = applicationContext()
        val databaseName = "history-checkpoint-${UUID.randomUUID()}.db"
        val checkpointId: String
        try {
            val firstDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val firstRepository = HistoryRepository(firstDatabase)
                checkpointId = firstRepository.beginSession(name = "checkpoint", at = 100L)
                val checkpoint = ScanSessionCheckpoint(
                    sessionId = checkpointId,
                    phase = ScanCheckpointPhase.RESULT,
                    qrPayload = "accepted-qr",
                    barcodePayload = "accepted-barcode",
                    result = MatchResult.MATCH,
                    // Deliberately stale: the repository must normalize this
                    // to the durable entries count in its transaction.
                    matchedCount = 99,
                    inputSource = ScanCheckpointInputSource.BLUETOOTH,
                )
                assertTrue(firstRepository.saveScanCheckpoint(checkpoint))
                assertEquals(
                    1,
                    firstRepository.recordMatch(
                        code = "ABC1234567",
                        qrPayload = checkpoint.qrPayload,
                        barcodePayload = checkpoint.barcodePayload,
                        at = 101L,
                        sessionId = checkpointId,
                        checkpoint = checkpoint,
                    ),
                )
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val reopenedRepository = HistoryRepository(reopenedDatabase)
                val restored = reopenedRepository.getScanCheckpoint(checkpointId)
                assertEquals(
                    ScanSessionCheckpoint(
                        sessionId = checkpointId,
                        phase = ScanCheckpointPhase.RESULT,
                        qrPayload = "accepted-qr",
                        barcodePayload = "accepted-barcode",
                        result = MatchResult.MATCH,
                        matchedCount = 1,
                        inputSource = ScanCheckpointInputSource.BLUETOOTH,
                    ),
                    restored,
                )
                assertEquals(1, reopenedRepository.activeSession.first()?.matchedCount)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun activeSessionAndCheckpointPhasesSurviveIsolatedDatabaseReopen() = runBlocking {
        val context = applicationContext()
        val databaseName = "history-process-relaunch-${UUID.randomUUID()}.db"
        val sessionId: String
        try {
            val firstDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val firstRepository = HistoryRepository(firstDatabase)
                sessionId = firstRepository.beginSession(name = "process-relaunch", at = 100L)
                assertTrue(
                    firstRepository.saveScanCheckpoint(
                        ScanSessionCheckpoint(
                            sessionId = sessionId,
                            phase = ScanCheckpointPhase.WAITING_QR,
                            // The entries table is the source of truth for this
                            // count, so a stale checkpoint value is normalized
                            // before the first simulated relaunch.
                            matchedCount = 99,
                            inputSource = ScanCheckpointInputSource.CAMERA,
                            cameraWasSelectedByUser = true,
                        ),
                    ),
                )
            } finally {
                firstDatabase.close()
            }

            // A new database/repository instance is the safe storage-level
            // analogue of a process relaunch. It uses a random test-only DB,
            // never the app's normal codematch.db.
            val afterWaitingQr = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val repository = HistoryRepository(afterWaitingQr)
                assertEquals(sessionId, repository.activeSession.first()?.id)
                assertEquals(
                    ScanSessionCheckpoint(
                        sessionId = sessionId,
                        phase = ScanCheckpointPhase.WAITING_QR,
                        matchedCount = 0,
                        inputSource = ScanCheckpointInputSource.CAMERA,
                        cameraWasSelectedByUser = true,
                    ),
                    repository.getScanCheckpoint(sessionId),
                )

                assertEquals(
                    1,
                    repository.recordMatch(
                        code = "FIRST00001",
                        qrPayload = "previous-qr",
                        barcodePayload = "previous-barcode",
                        at = 101L,
                        sessionId = sessionId,
                    ),
                )
                assertTrue(
                    repository.saveScanCheckpoint(
                        ScanSessionCheckpoint(
                            sessionId = sessionId,
                            phase = ScanCheckpointPhase.WAITING_CODE_128,
                            qrPayload = "accepted-qr",
                            // Deliberately stale; save normalizes it to one.
                            matchedCount = 99,
                            inputSource = ScanCheckpointInputSource.CAMERA,
                            cameraWasSelectedByUser = true,
                        ),
                    ),
                )
            } finally {
                afterWaitingQr.close()
            }

            val afterWaitingCode = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val repository = HistoryRepository(afterWaitingCode)
                assertEquals(1, repository.activeSession.first()?.matchedCount)
                assertEquals(
                    ScanSessionCheckpoint(
                        sessionId = sessionId,
                        phase = ScanCheckpointPhase.WAITING_CODE_128,
                        qrPayload = "accepted-qr",
                        matchedCount = 1,
                        inputSource = ScanCheckpointInputSource.CAMERA,
                        cameraWasSelectedByUser = true,
                    ),
                    repository.getScanCheckpoint(sessionId),
                )

                val terminalCheckpoint = ScanSessionCheckpoint(
                    sessionId = sessionId,
                    phase = ScanCheckpointPhase.RESULT,
                    qrPayload = "accepted-qr",
                    barcodePayload = "accepted-barcode",
                    result = MatchResult.MATCH,
                    matchedCount = 2,
                    inputSource = ScanCheckpointInputSource.CAMERA,
                    cameraWasSelectedByUser = true,
                )
                assertEquals(
                    1,
                    repository.recordMatch(
                        code = "SECOND00002",
                        qrPayload = terminalCheckpoint.qrPayload,
                        barcodePayload = terminalCheckpoint.barcodePayload,
                        at = 102L,
                        sessionId = sessionId,
                        checkpoint = terminalCheckpoint,
                    ),
                )
            } finally {
                afterWaitingCode.close()
            }

            val afterResult = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val repository = HistoryRepository(afterResult)
                assertEquals(sessionId, repository.activeSession.first()?.id)
                assertEquals(2, repository.activeSession.first()?.matchedCount)
                assertEquals(
                    ScanSessionCheckpoint(
                        sessionId = sessionId,
                        phase = ScanCheckpointPhase.RESULT,
                        qrPayload = "accepted-qr",
                        barcodePayload = "accepted-barcode",
                        result = MatchResult.MATCH,
                        matchedCount = 2,
                        inputSource = ScanCheckpointInputSource.CAMERA,
                        cameraWasSelectedByUser = true,
                    ),
                    repository.getScanCheckpoint(sessionId),
                )
            } finally {
                afterResult.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun nonBlankRenameSurvivesDatabaseReopen() = runBlocking {
        val context = applicationContext()
        val databaseName = "history-rename-${UUID.randomUUID()}.db"
        try {
            val firstDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
            val sessionId = try {
                val firstRepository = HistoryRepository(firstDatabase)
                val id = firstRepository.beginSession(name = "before rename", at = 100L)
                firstRepository.recordMatch("ABC1234567", at = 101L)
                firstRepository.endActiveSession(at = 110L)
                firstRepository.renameSession(id, "  Renamed after reopen  ")
                id
            } finally {
                firstDatabase.close()
            }

            // Recreate the persistent database and verify the normalized
            // non-blank name, rather than only checking the in-memory Flow.
            val restoredDatabase = CodeMatchDatabaseFactory.create(context, databaseName)
            try {
                val restored = HistoryRepository(restoredDatabase).observeSession(sessionId).first()
                assertNotNull(restored)
                assertEquals("Renamed after reopen", restored?.name)
                assertEquals("Renamed after reopen", restored?.displayName)
                assertEquals(1, restored?.matchedCount)
            } finally {
                restoredDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun staleSessionIdCannotWriteIntoNewActiveSession() = runBlocking {
        val firstId = repository.beginSession(at = 100L)
        repository.endActiveSession(at = 101L)
        repository.beginSession(at = 200L)

        assertNull(repository.recordMatch("ABC1234567", sessionId = firstId, at = 201L))
        assertEquals(0, repository.activeSession.first()?.matchedCount)
    }

    @Test
    fun checkpointUpsertKeepsOneRowAndClearsWhenSessionEnds() = runBlocking {
        val id = repository.beginSession(at = 100L)
        val waitingQr = ScanSessionCheckpoint(
            sessionId = id,
            phase = ScanCheckpointPhase.WAITING_QR,
            matchedCount = 99,
            inputSource = ScanCheckpointInputSource.CAMERA,
        )
        assertTrue(repository.saveScanCheckpoint(waitingQr))

        val waitingCode = waitingQr.copy(
            phase = ScanCheckpointPhase.WAITING_CODE_128,
            qrPayload = "accepted-qr",
            inputSource = ScanCheckpointInputSource.BLUETOOTH,
        )
        assertTrue(repository.saveScanCheckpoint(waitingCode))

        assertEquals(waitingCode.copy(matchedCount = 0), repository.getScanCheckpoint(id))
        assertEquals(waitingCode.sessionId, database.scanCheckpointDao().findBySessionId(id)?.sessionId)
        assertEquals(
            EndSessionOutcome.DeletedEmpty(id),
            repository.endSession(id, at = 110L),
        )
        assertNull(database.scanCheckpointDao().findBySessionId(id))
    }

    @Test
    fun invalidCheckpointIsDiscardedWhileSessionCountIsRetained() = runBlocking {
        val id = repository.beginSession(at = 100L)
        repository.recordMatch("ABC1234567", at = 101L)
        database.scanCheckpointDao().upsert(
            ScanCheckpointEntity(
                sessionId = id,
                version = ScanSessionCheckpoint.CURRENT_VERSION + 1,
                phase = ScanCheckpointPhase.RESULT.name,
                qrPayload = "qr",
                barcodePayload = "barcode",
                result = MatchResult.MATCH.name,
                matchedCount = 1,
                inputSource = ScanCheckpointInputSource.CAMERA.name,
                cameraWasSelectedByUser = false,
            ),
        )

        assertNull(repository.getScanCheckpoint(id))
        assertEquals(1, repository.activeSession.first()?.matchedCount)
        assertNull(database.scanCheckpointDao().findBySessionId(id))
    }

    @Test
    fun matchAndTerminalCheckpointAreWrittenAtomically() = runBlocking {
        val id = repository.beginSession(at = 100L)
        val checkpoint = ScanSessionCheckpoint(
            sessionId = id,
            phase = ScanCheckpointPhase.RESULT,
            qrPayload = "accepted-qr",
            barcodePayload = "accepted-barcode",
            result = MatchResult.MATCH,
            matchedCount = 1,
            inputSource = ScanCheckpointInputSource.BLUETOOTH,
        )

        assertEquals(
            1,
            repository.recordMatch(
                code = "ABC1234567",
                qrPayload = checkpoint.qrPayload,
                barcodePayload = checkpoint.barcodePayload,
                at = 101L,
                sessionId = id,
                checkpoint = checkpoint,
            ),
        )
        assertEquals(checkpoint, repository.getScanCheckpoint(id))
        assertEquals(1, database.entryDao().countForSession(id))
        assertEquals(EndSessionOutcome.Ended(id, 110L), repository.endSession(id, at = 110L))
        assertNull(database.scanCheckpointDao().findBySessionId(id))
        assertNull(repository.getScanCheckpoint(id))
    }

    @Test
    fun contradictoryTerminalCheckpointIsNotStoredWithAValidEntry() = runBlocking {
        val id = repository.beginSession(at = 100L)
        val waiting = ScanSessionCheckpoint(
            sessionId = id,
            phase = ScanCheckpointPhase.WAITING_QR,
            inputSource = ScanCheckpointInputSource.CAMERA,
        )
        assertTrue(repository.saveScanCheckpoint(waiting))

        repository.recordMatch(
            code = "ABC1234567",
            qrPayload = "actual-qr",
            barcodePayload = "actual-barcode",
            at = 101L,
            sessionId = id,
            checkpoint = ScanSessionCheckpoint(
                sessionId = id,
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = "other-qr",
                barcodePayload = "other-barcode",
                result = MatchResult.MISMATCH,
                matchedCount = 1,
            ),
        )

        assertEquals(waiting.copy(matchedCount = 1), repository.getScanCheckpoint(id))
        assertEquals(1, database.entryDao().countForSession(id))
    }

    private fun applicationContext(): Context =
        ApplicationProvider.getApplicationContext()
}
