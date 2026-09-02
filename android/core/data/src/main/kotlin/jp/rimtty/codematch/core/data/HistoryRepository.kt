package jp.rimtty.codematch.core.data

import androidx.room.withTransaction
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import jp.rimtty.codematch.core.model.EndSessionOutcome
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.core.model.ScanCheckpointInputSource
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed history API used by the scan and history features.
 *
 * All mutating operations are suspend functions. The caller chooses its
 * lifecycle/dispatcher; Room serializes the transactions that allocate active
 * sessions and per-session sequence numbers.
 */
class HistoryRepository(
    private val database: CodeMatchDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sessionDao: SessionDao = database.sessionDao()
    private val entryDao: EntryDao = database.entryDao()
    private val scanCheckpointDao: ScanCheckpointDao = database.scanCheckpointDao()

    /** Sessions ordered newest first, with entries ordered by scan sequence. */
    val sessions: Flow<List<MatchSession>>
        get() = sessionDao.observeSessionsWithEntries().map { rows ->
            rows.map { it.toModel() }
        }

    /** The active session survives process recreation because it is read from Room. */
    val activeSession: Flow<MatchSession?>
        get() = sessions.map { values -> values.firstOrNull { it.isActive } }

    /** Observe one session, including live entry changes, or null after deletion. */
    fun observeSession(sessionId: String): Flow<MatchSession?> =
        sessionDao.observeWithEntriesById(sessionId).map { row -> row?.toModel() }

    /** Begin a session, or return the existing active session instead of creating a second one. */
    suspend fun beginSession(name: String? = null, at: Long = now()): String =
        database.withTransaction {
            sessionDao.findActive()?.id ?: run {
                val session = SessionEntity(
                    id = UUID.randomUUID().toString(),
                    startedAt = at,
                    endedAt = null,
                    name = normalizeName(name),
                )
                sessionDao.insert(session)
                session.id
            }
        }

    /**
     * Record one successful comparison as one box in the active session.
     *
     * A duplicate part number is intentionally inserted as a new row with a
     * new sequence. If no active session exists, no row is written and null is
     * returned. Otherwise the one-based box number for this part number is
     * returned. When [sessionId] is supplied it must identify that same active
     * session, which prevents a stale screen from writing into a newer one.
     */
    suspend fun recordMatch(
        code: String,
        qrPayload: String? = null,
        barcodePayload: String? = null,
        at: Long = now(),
        sessionId: String? = null,
        checkpoint: ScanSessionCheckpoint? = null,
    ): Int? = database.withTransaction {
        val active = sessionDao.findActive()
            ?: return@withTransaction null
        if (sessionId != null && sessionId != active.id) {
            return@withTransaction null
        }

        val normalizedCode = code.trim()
        val boxNumber = entryDao.countForCode(active.id, normalizedCode) + 1
        val sequence = (entryDao.maxSequence(active.id) ?: -1L) + 1L
        val entry = EntryEntity(
            id = UUID.randomUUID().toString(),
            sessionId = active.id,
            sequence = sequence,
            code = normalizedCode,
            matchedAt = at,
            qrPayload = qrPayload,
            barcodePayload = barcodePayload,
        )
        entryDao.insert(entry)
        // Only a terminal MATCH checkpoint that describes this exact pair may
        // accompany a newly inserted entry. A caller passing a mismatching or
        // otherwise unrelated checkpoint must not make durable state claim a
        // result that was never recorded.
        if (checkpoint != null &&
            checkpoint.sessionId == active.id &&
            checkpoint.phase == ScanCheckpointPhase.RESULT &&
            checkpoint.result == MatchResult.MATCH &&
            checkpoint.qrPayload == qrPayload &&
            checkpoint.barcodePayload == barcodePayload &&
            checkpoint.isSupportedAndValid()
        ) {
            scanCheckpointDao.upsert(
                checkpoint.copy(
                    matchedCount = entryDao.countForSession(active.id),
                ).toEntity(),
            )
        }
        boxNumber
    }

    /** Count boxes for [code] in the active session. */
    suspend fun activeSessionMatchCount(code: String): Int {
        val active = sessionDao.findActive() ?: return 0
        return entryDao.countForCode(active.id, code.trim())
    }

    /**
     * Read the active session's logical scan checkpoint.
     *
     * A malformed or newer-version row is discarded and treated as absent so
     * callers can safely start at Waiting QR while retaining the Room session
     * and its recorded box count.
     */
    suspend fun getScanCheckpoint(sessionId: String): ScanSessionCheckpoint? =
        database.withTransaction {
            val session = sessionDao.findById(sessionId)
            if (session == null || session.endedAt != null) {
                scanCheckpointDao.deleteBySessionId(sessionId)
                return@withTransaction null
            }

            val entity = scanCheckpointDao.findBySessionId(sessionId)
                ?: return@withTransaction null
            val checkpoint = entity.toModel()
            if (checkpoint == null || checkpoint.sessionId != sessionId) {
                scanCheckpointDao.deleteBySessionId(sessionId)
                null
            } else {
                // Entries are the durable source of truth for the count. A
                // checkpoint written by an older build may carry a stale
                // count, but must not make the resumed UI invent a box.
                checkpoint.copy(matchedCount = entryDao.countForSession(sessionId))
            }
        }

    /**
     * Persist one valid logical checkpoint for an active session.
     *
     * The primary key in [ScanCheckpointEntity] makes this an upsert: one
     * session can have exactly one current checkpoint. The count is normalized
     * from the entries table inside the same transaction.
     */
    suspend fun saveScanCheckpoint(checkpoint: ScanSessionCheckpoint): Boolean =
        database.withTransaction {
            val active = sessionDao.findById(checkpoint.sessionId)
            if (active == null || active.endedAt != null || !checkpoint.isSupportedAndValid()) {
                return@withTransaction false
            }
            val normalized = checkpoint.copy(
                matchedCount = entryDao.countForSession(checkpoint.sessionId),
            )
            scanCheckpointDao.upsert(normalized.toEntity())
            true
        }

    /** Remove transient scan state without touching the session or history. */
    suspend fun clearScanCheckpoint(sessionId: String) {
        database.withTransaction {
            scanCheckpointDao.deleteBySessionId(sessionId)
        }
    }

    /** Rename any existing session; blank names are stored as null. */
    suspend fun renameSession(sessionId: String, name: String?) {
        sessionDao.rename(sessionId, normalizeName(name))
    }

    /**
     * Finish the active session identified by [sessionId]. Empty sessions are
     * deleted instead of leaving a zero-box history item.
     */
    suspend fun endSession(
        sessionId: String,
        at: Long = now(),
    ): EndSessionOutcome =
        database.withTransaction {
            val session = sessionDao.findById(sessionId)
                ?: return@withTransaction EndSessionOutcome.NotFound
            finishSession(session, at)
        }

    /** Finish whichever active session exists, if any. */
    suspend fun endActiveSession(at: Long = now()): EndSessionOutcome =
        database.withTransaction {
            val session = sessionDao.findActive()
                ?: return@withTransaction EndSessionOutcome.NotFound
            finishSession(session, at)
        }

    /** Delete one session and cascade-delete its entries. */
    suspend fun deleteSession(sessionId: String): Boolean =
        database.withTransaction {
            if (sessionDao.findById(sessionId) == null) {
                return@withTransaction false
            }
            sessionDao.deleteById(sessionId)
            true
        }

    /** Delete several sessions in one transaction; an empty list is a no-op. */
    suspend fun deleteSessions(sessionIds: Collection<String>): Int {
        val ids = sessionIds.toList()
        if (ids.isEmpty()) return 0
        return database.withTransaction {
            val existing = sessionDao.countByIds(ids)
            sessionDao.deleteByIds(ids)
            existing
        }
    }

    suspend fun getSession(sessionId: String): MatchSession? =
        sessionDao.findWithEntriesById(sessionId)?.toModel()

    private suspend fun finishSession(
        session: SessionEntity,
        at: Long,
    ): EndSessionOutcome {
        session.endedAt?.let { endedAt ->
            scanCheckpointDao.deleteBySessionId(session.id)
            return EndSessionOutcome.AlreadyEnded(session.id, endedAt)
        }
        // Explicitly clear before either finishing or deleting. The foreign
        // key also protects the delete path, while this makes the invariant
        // clear for future schema changes and for non-empty sessions.
        scanCheckpointDao.deleteBySessionId(session.id)
        return if (entryDao.countForSession(session.id) == 0) {
            sessionDao.deleteById(session.id)
            EndSessionOutcome.DeletedEmpty(session.id)
        } else {
            sessionDao.finish(session.id, at)
            EndSessionOutcome.Ended(session.id, at)
        }
    }

    private fun normalizeName(name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() }

    private fun ScanSessionCheckpoint.toEntity(): ScanCheckpointEntity =
        ScanCheckpointEntity(
            sessionId = sessionId,
            version = version,
            phase = phase.name,
            qrPayload = qrPayload,
            barcodePayload = barcodePayload,
            result = result?.name,
            matchedCount = matchedCount,
            inputSource = inputSource.name,
            cameraWasSelectedByUser = cameraWasSelectedByUser,
        )

    private fun ScanCheckpointEntity.toModel(): ScanSessionCheckpoint? =
        runCatching {
            ScanSessionCheckpoint(
                sessionId = sessionId,
                phase = ScanCheckpointPhase.valueOf(phase),
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                result = result?.let(MatchResult::valueOf),
                matchedCount = matchedCount,
                inputSource = ScanCheckpointInputSource.valueOf(inputSource),
                cameraWasSelectedByUser = cameraWasSelectedByUser,
                version = version,
            )
        }.getOrNull()?.takeIf { it.isSupportedAndValid() }

    private fun SessionWithEntries.toModel(): MatchSession =
        MatchSession(
            id = session.id,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            name = session.name,
            entries = entries
                .sortedWith(compareBy<EntryEntity> { it.sequence }
                    .thenBy { it.matchedAt }
                    .thenBy { it.id })
                .map { entry ->
                    MatchEntry(
                        id = entry.id,
                        code = entry.code,
                        matchedAt = entry.matchedAt,
                        qrPayload = entry.qrPayload,
                        barcodePayload = entry.barcodePayload,
                        sequence = entry.sequence,
                    )
                },
        )
}
