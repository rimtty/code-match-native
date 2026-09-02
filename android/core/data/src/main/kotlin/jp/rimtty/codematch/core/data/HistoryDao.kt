package jp.rimtty.codematch.core.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Session queries used by [HistoryRepository]. */
@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Transaction
    @Query(
        """
        SELECT * FROM sessions
        ORDER BY startedAt DESC, id DESC
        """
    )
    fun observeSessionsWithEntries(): Flow<List<SessionWithEntries>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE endedAt IS NULL
        ORDER BY startedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findActive(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun findById(sessionId: String): SessionEntity?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun findWithEntriesById(sessionId: String): SessionWithEntries?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun observeWithEntriesById(sessionId: String): Flow<SessionWithEntries?>

    @Query("UPDATE sessions SET name = :name WHERE id = :sessionId")
    suspend fun rename(sessionId: String, name: String?)

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :sessionId")
    suspend fun finish(sessionId: String, endedAt: Long)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("DELETE FROM sessions WHERE id IN (:sessionIds)")
    suspend fun deleteByIds(sessionIds: List<String>)

    @Query("SELECT COUNT(*) FROM sessions WHERE id IN (:sessionIds)")
    suspend fun countByIds(sessionIds: List<String>): Int
}

/** Room relation used to load a session and its ordered box rows together. */
data class SessionWithEntries(
    @Embedded
    val session: SessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId",
    )
    val entries: List<EntryEntity>,
)

/** Entry queries used inside repository transactions. */
@Dao
interface EntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: EntryEntity)

    @Query("SELECT MAX(sequence) FROM entries WHERE sessionId = :sessionId")
    suspend fun maxSequence(sessionId: String): Long?

    @Query("SELECT COUNT(*) FROM entries WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM entries
        WHERE sessionId = :sessionId AND code = :code
        """
    )
    suspend fun countForCode(sessionId: String, code: String): Int
}

/** Room access for the one logical scan checkpoint owned by a session. */
@Dao
interface ScanCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: ScanCheckpointEntity)

    @Query("SELECT * FROM scan_checkpoints WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findBySessionId(sessionId: String): ScanCheckpointEntity?

    @Query("DELETE FROM scan_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
