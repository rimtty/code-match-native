package jp.rimtty.codematch.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the exported v1 schema can be opened and validated. */
@RunWith(AndroidJUnit4::class)
class CodeMatchDatabaseMigrationTest {
    @Test
    fun versionOneSchemaOpensAndValidates() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeMatchDatabase::class.java,
        )
        val databaseName = "migration-${UUID.randomUUID()}.db"

        helper.createDatabase(databaseName, 1).close()
        helper.runMigrationsAndValidate(databaseName, 1, true).close()
    }
}
