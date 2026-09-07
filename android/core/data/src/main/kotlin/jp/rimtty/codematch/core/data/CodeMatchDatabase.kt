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
 * on the session row and on its checkpoint. Version 4 adds the capped
 * on-device scan log. `exportSchema = true` is intentional: the generated JSON
 * is the contract used by migration tests and future schema upgrades.
 */
@Database(
    entities = [
        SessionEntity::class,
        EntryEntity::class,
        ScanCheckpointEntity::class,
        ScanLogEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CodeMatchDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun entryDao(): EntryDao

    abstract fun scanCheckpointDao(): ScanCheckpointDao

    abstract fun scanLogDao(): ScanLogDao

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

        /**
         * Adds the capped on-device scan log.
         *
         * The table is deliberately independent of `sessions`: a rejection can
         * be recorded before a session exists, and removing a session must not
         * delete the log that explains it. Rows are trimmed by insertion order
         * (see `ScanLogDao.trimToLatest`), while the `at` index keeps the
         * time-ordered reads used by the export cheap.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `sessionId` TEXT,
                        `source` TEXT NOT NULL,
                        `step` TEXT NOT NULL,
                        `event` TEXT NOT NULL,
                        `reason` TEXT,
                        `destination` TEXT,
                        `qrPayload` TEXT,
                        `barcodePayload` TEXT,
                        `code` TEXT,
                        `boxNumber` INTEGER,
                        `message` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_log_at` ON `scan_log` (`at`)",
                )
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
            CodeMatchDatabase.MIGRATION_3_4,
        ).build()

    /** Factory used by Android tests; data is discarded when the DB is closed. */
    fun inMemory(context: Context): CodeMatchDatabase =
        Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            CodeMatchDatabase::class.java,
        ).build()
}
