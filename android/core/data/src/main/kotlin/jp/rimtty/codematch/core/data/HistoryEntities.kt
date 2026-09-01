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
