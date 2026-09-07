package jp.rimtty.codematch.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the exported schemas and every hand-written migration. */
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

    @Test
    fun versionTwoMigratesToVersionThreeWithNullableDestinationColumns() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeMatchDatabase::class.java,
        )
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val sessionId = "migration-session-${UUID.randomUUID()}"
        val entryId = "migration-entry-${UUID.randomUUID()}"

        try {
            helper.createDatabase(databaseName, 2).apply {
                execSQL(
                    "INSERT INTO sessions(id, startedAt, endedAt, name) " +
                        "VALUES ('$sessionId', 100, NULL, 'v2 session')",
                )
                execSQL(
                    "INSERT INTO entries(id, sessionId, sequence, code, matchedAt, " +
                        "qrPayload, barcodePayload) VALUES " +
                        "('$entryId', '$sessionId', 0, 'ABC1234567', 101, 'qr', 'barcode')",
                )
                execSQL(
                    "INSERT INTO scan_checkpoints(sessionId, version, phase, qrPayload, " +
                        "barcodePayload, result, matchedCount, inputSource, " +
                        "cameraWasSelectedByUser) VALUES " +
                        "('$sessionId', 1, 'RESULT', 'qr', 'barcode', 'MATCH', 1, 'CAMERA', 0)",
                )
                close()
            }

            helper.runMigrationsAndValidate(
                databaseName,
                3,
                true,
                CodeMatchDatabase.MIGRATION_2_3,
            ).apply {
                query(
                    "SELECT startedAt, name, destination FROM sessions WHERE id = ?",
                    arrayOf(sessionId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(100L, cursor.getLong(0))
                    assertEquals("v2 session", cursor.getString(1))
                    // An upgraded row keeps no destination: it is derived again
                    // from the accepted QR instead of being defaulted here.
                    assertTrue(cursor.isNull(2))
                }
                query(
                    "SELECT code, qrPayload, barcodePayload FROM entries WHERE id = ?",
                    arrayOf(entryId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ABC1234567", cursor.getString(0))
                    assertEquals("qr", cursor.getString(1))
                    assertEquals("barcode", cursor.getString(2))
                }
                query(
                    "SELECT phase, matchedCount, inputSource, destination " +
                        "FROM scan_checkpoints WHERE sessionId = ?",
                    arrayOf(sessionId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("RESULT", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals("CAMERA", cursor.getString(2))
                    assertTrue(cursor.isNull(3))
                }
                close()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionOneMigratesToVersionThreeThroughBothMigrations() {
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
                        "VALUES ('$sessionId', 100, 200, 'v1 session')",
                )
                execSQL(
                    "INSERT INTO entries(id, sessionId, sequence, code, matchedAt, " +
                        "qrPayload, barcodePayload) VALUES " +
                        "('$entryId', '$sessionId', 0, 'ABC1234567', 101, 'qr', 'barcode')",
                )
                close()
            }

            // An install that skipped the intermediate release must reach the
            // same schema as a device that took both steps one after another.
            helper.runMigrationsAndValidate(
                databaseName,
                3,
                true,
                CodeMatchDatabase.MIGRATION_1_2,
                CodeMatchDatabase.MIGRATION_2_3,
            ).apply {
                query(
                    "SELECT startedAt, endedAt, name, destination FROM sessions WHERE id = ?",
                    arrayOf(sessionId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(100L, cursor.getLong(0))
                    assertEquals(200L, cursor.getLong(1))
                    assertEquals("v1 session", cursor.getString(2))
                    assertTrue(cursor.isNull(3))
                }
                query(
                    "SELECT code, qrPayload, barcodePayload FROM entries WHERE id = ?",
                    arrayOf(entryId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ABC1234567", cursor.getString(0))
                    assertEquals("qr", cursor.getString(1))
                    assertEquals("barcode", cursor.getString(2))
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

    @Test
    fun versionThreeMigratesToVersionFourWithTheScanLogTable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeMatchDatabase::class.java,
        )
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val sessionId = "migration-session-${UUID.randomUUID()}"
        val entryId = "migration-entry-${UUID.randomUUID()}"

        try {
            helper.createDatabase(databaseName, 3).apply {
                execSQL(
                    "INSERT INTO sessions(id, startedAt, endedAt, name, destination) " +
                        "VALUES ('$sessionId', 100, NULL, 'v3 session', 'molten')",
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
                4,
                true,
                CodeMatchDatabase.MIGRATION_3_4,
            ).apply {
                query(
                    "SELECT name, destination FROM sessions WHERE id = ?",
                    arrayOf(sessionId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("v3 session", cursor.getString(0))
                    assertEquals("molten", cursor.getString(1))
                }
                query("SELECT COUNT(*) FROM scan_log").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                // The log outlives its session on purpose, so nothing here is
                // a foreign key and a row without a session is valid.
                execSQL(
                    "INSERT INTO scan_log(at, sessionId, source, step, event, reason, " +
                        "destination, qrPayload, barcodePayload, code, boxNumber, message) " +
                        "VALUES (10, NULL, 'camera', 'qr', 'rejected', 'wrong_destination', " +
                        "NULL, 'qr', NULL, NULL, NULL, NULL)",
                )
                query("SELECT id, at, sessionId, event FROM scan_log").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                    assertEquals(10L, cursor.getLong(1))
                    assertTrue(cursor.isNull(2))
                    assertEquals("rejected", cursor.getString(3))
                }
                close()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionOneMigratesToVersionFourThroughEveryMigration() {
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
                        "VALUES ('$sessionId', 100, 200, 'v1 session')",
                )
                execSQL(
                    "INSERT INTO entries(id, sessionId, sequence, code, matchedAt, " +
                        "qrPayload, barcodePayload) VALUES " +
                        "('$entryId', '$sessionId', 0, 'ABC1234567', 101, 'qr', 'barcode')",
                )
                close()
            }

            // An install that skipped both intermediate releases must reach the
            // same schema as a device that took every step in order.
            helper.runMigrationsAndValidate(
                databaseName,
                4,
                true,
                CodeMatchDatabase.MIGRATION_1_2,
                CodeMatchDatabase.MIGRATION_2_3,
                CodeMatchDatabase.MIGRATION_3_4,
            ).apply {
                query(
                    "SELECT startedAt, endedAt, name, destination FROM sessions WHERE id = ?",
                    arrayOf(sessionId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(100L, cursor.getLong(0))
                    assertEquals(200L, cursor.getLong(1))
                    assertEquals("v1 session", cursor.getString(2))
                    assertTrue(cursor.isNull(3))
                }
                query(
                    "SELECT code, qrPayload, barcodePayload FROM entries WHERE id = ?",
                    arrayOf(entryId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ABC1234567", cursor.getString(0))
                    assertEquals("qr", cursor.getString(1))
                    assertEquals("barcode", cursor.getString(2))
                }
                query("SELECT COUNT(*) FROM scan_checkpoints").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                query("SELECT COUNT(*) FROM scan_log").use { cursor ->
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
