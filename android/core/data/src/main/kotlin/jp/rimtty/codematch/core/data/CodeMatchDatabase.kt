package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Persistent database for on-device comparison history.
 *
 * Version 2 adds one durable logical scan checkpoint per session. Version 3
 * adds the nullable destination locked by a session's first accepted QR, both
 * on the session row and on its checkpoint. `exportSchema = true` is
 * intentional: the generated JSON is the contract used by migration tests and
 * future schema upgrades.
 */
@Database(
    entities = [SessionEntity::class, EntryEntity::class, ScanCheckpointEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class CodeMatchDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun entryDao(): EntryDao

    abstract fun scanCheckpointDao(): ScanCheckpointDao

    companion object {
        const val DATABASE_NAME: String = "codematch.db"

        /** Adds the process-recreation-safe logical scan checkpoint table. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_checkpoints` (
                        `sessionId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `phase` TEXT NOT NULL,
                        `qrPayload` TEXT,
                        `barcodePayload` TEXT,
                        `result` TEXT,
                        `matchedCount` INTEGER NOT NULL,
                        `inputSource` TEXT NOT NULL,
                        `cameraWasSelectedByUser` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the destination lock to sessions and to their checkpoints.
         *
         * Both columns are added as nullable TEXT so existing rows stay valid
         * without a rewrite: a null means the destination was never recorded
         * and is derived again from the accepted QR.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `destination` TEXT")
                db.execSQL("ALTER TABLE `scan_checkpoints` ADD COLUMN `destination` TEXT")
            }
        }
    }
}

/** Application-facing database factory; the app owns the instance lifecycle. */
object CodeMatchDatabaseFactory {
    fun create(
        context: Context,
        name: String = CodeMatchDatabase.DATABASE_NAME,
    ): CodeMatchDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            CodeMatchDatabase::class.java,
            name,
        ).addMigrations(
            CodeMatchDatabase.MIGRATION_1_2,
            CodeMatchDatabase.MIGRATION_2_3,
        ).build()

    /** Factory used by Android tests; data is discarded when the DB is closed. */
    fun inMemory(context: Context): CodeMatchDatabase =
        Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            CodeMatchDatabase::class.java,
        ).build()
}
