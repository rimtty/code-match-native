package jp.rimtty.codematch.core.data

import androidx.room.withTransaction
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import jp.rimtty.codematch.core.model.EndSessionOutcome
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
        boxNumber
    }

    /** Count boxes for [code] in the active session. */
    suspend fun activeSessionMatchCount(code: String): Int {
        val active = sessionDao.findActive() ?: return 0
        return entryDao.countForCode(active.id, code.trim())
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
            return EndSessionOutcome.AlreadyEnded(session.id, endedAt)
        }
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
