package jp.rimtty.codematch.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room row for a comparison session. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "startedAt")
    val startedAt: Long,
    @ColumnInfo(name = "endedAt")
    val endedAt: Long?,
    val name: String?,
)

/**
 * Room row for one successful comparison.
 *
 * The composite unique index prevents two concurrent writers from assigning
 * the same sequence within a session. The foreign key is cascading so a
 * discarded/removed session cannot leave orphaned box records behind.
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "sequence"], unique = true),
    ],
)
data class EntryEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val sequence: Long,
    val code: String,
    val matchedAt: Long,
    val qrPayload: String?,
    val barcodePayload: String?,
)

/**
 * Durable logical scan state for the single active session.
 *
 * The session id is both the primary key and a foreign key so a session can
 * never accumulate multiple competing checkpoints and deleting/finishing an
 * empty session cannot leave transient scan state behind. Payloads here are
 * the already accepted values required to resume a comparison; camera frames,
 * raw transport frames, and diagnostics are intentionally absent.
 */
@Entity(
    tableName = "scan_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScanCheckpointEntity(
    @PrimaryKey
    val sessionId: String,
    val version: Int,
    val phase: String,
    val qrPayload: String?,
    val barcodePayload: String?,
    val result: String?,
    val matchedCount: Int,
    val inputSource: String,
    val cameraWasSelectedByUser: Boolean,
)
