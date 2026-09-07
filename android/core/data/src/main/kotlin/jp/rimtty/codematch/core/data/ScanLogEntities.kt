package jp.rimtty.codematch.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One recorded scan-log line.
 *
 * The table lives in the same excluded-from-backup database as the history so
 * the raw payloads it keeps for debugging never reach cloud backup or a
 * device-to-device transfer. The row has no foreign key to `sessions`: a
 * rejection can happen before a session exists, and deleting a session must
 * not silently erase the log that explains what went wrong in it.
 *
 * [at] is indexed because the log is always read and trimmed in time order.
 */
@Entity(
    tableName = "scan_log",
    indices = [Index(value = ["at"])],
)
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val at: Long,
    val sessionId: String?,
    val source: String,
    val step: String,
    val event: String,
    val reason: String?,
    val destination: String?,
    val qrPayload: String?,
    val barcodePayload: String?,
    val code: String?,
    val boxNumber: Int?,
    val message: String?,
)

/** Room access for the capped scan log. */
@Dao
interface ScanLogDao {
    @Insert
    suspend fun insert(event: ScanLogEntity): Long

    /**
     * Drop everything older than the newest [limit] rows.
     *
     * The autoincrement id is the insertion order, so ordering by it keeps the
     * newest rows even when two events share a millisecond.
     */
    @Query(
        """
        DELETE FROM scan_log
        WHERE id NOT IN (SELECT id FROM scan_log ORDER BY id DESC LIMIT :limit)
        """
    )
    suspend fun trimToLatest(limit: Int)

    @Query("SELECT COUNT(*) FROM scan_log")
    fun count(): Flow<Int>

    @Query("SELECT * FROM scan_log ORDER BY id ASC")
    suspend fun all(): List<ScanLogEntity>

    @Query("DELETE FROM scan_log")
    suspend fun clear()
}
