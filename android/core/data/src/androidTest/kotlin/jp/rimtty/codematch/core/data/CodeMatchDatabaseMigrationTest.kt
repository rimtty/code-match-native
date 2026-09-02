package jp.rimtty.codematch.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the exported v1 schema and its checkpoint migration. */
@RunWith(AndroidJUnit4::class)
class CodeMatchDatabaseMigrationTest {
    @Test
    fun versionOneMigratesToVersionTwoWithCheckpointTable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeMatchDatabase::class.java,
        )
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val sessionId = "migration-session-${UUID.randomUUID()}"
        val entryId = "migration-entry-${UUID.randomUUID()}"

        try {
            helper.createDatabase(databaseName, 1).apply {
                execSQL(
                    "INSERT INTO sessions(id, startedAt, endedAt, name) " +
                        "VALUES ('$sessionId', 100, NULL, 'v1 session')",
                )
                execSQL(
                    "INSERT INTO entries(id, sessionId, sequence, code, matchedAt, " +
                        "qrPayload, barcodePayload) VALUES " +
                        "('$entryId', '$sessionId', 0, 'ABC1234567', 101, 'qr', 'barcode')",
                )
                close()
            }

            helper.runMigrationsAndValidate(
                databaseName,
                2,
                true,
                CodeMatchDatabase.MIGRATION_1_2,
            ).apply {
                query(
                    "SELECT id, startedAt, endedAt, name FROM sessions WHERE id = ?",
                    arrayOf(sessionId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(sessionId, cursor.getString(0))
                    assertEquals(100L, cursor.getLong(1))
                    assertTrue(cursor.isNull(2))
                    assertEquals("v1 session", cursor.getString(3))
                }
                query(
                    "SELECT id, code, qrPayload, barcodePayload FROM entries WHERE id = ?",
                    arrayOf(entryId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(entryId, cursor.getString(0))
                    assertEquals("ABC1234567", cursor.getString(1))
                    assertEquals("qr", cursor.getString(2))
                    assertEquals("barcode", cursor.getString(3))
                }
                query("SELECT COUNT(*) FROM scan_checkpoints").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                close()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        }
    }
}
