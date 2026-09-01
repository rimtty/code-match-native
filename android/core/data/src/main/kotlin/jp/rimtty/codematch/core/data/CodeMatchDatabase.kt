package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Persistent database for on-device comparison history.
 *
 * Version 1 is the initial schema. `exportSchema = true` is intentional: the
 * generated JSON is the contract used by migration tests and future schema
 * upgrades.
 */
@Database(
    entities = [SessionEntity::class, EntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CodeMatchDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun entryDao(): EntryDao

    companion object {
        const val DATABASE_NAME: String = "codematch.db"
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
        ).build()

    /** Factory used by Android tests; data is discarded when the DB is closed. */
    fun inMemory(context: Context): CodeMatchDatabase =
        Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            CodeMatchDatabase::class.java,
        ).build()
}
