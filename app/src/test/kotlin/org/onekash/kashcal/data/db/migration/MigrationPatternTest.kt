package org.onekash.kashcal.data.db.migration

import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.testutil.KotlinSourceText
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests the rules every migration in [Migrations] follows, so a new one that breaks them fails
 * here before it ships:
 * - each `MIGRATION_a_b` is registered once in [Migrations.ALL_MIGRATIONS], in order, with the
 *   versions its name gives, and with Room's generated migrations the chain reaches every
 *   exported schema version one step at a time;
 * - it leaves the transaction to Room: no transaction calls or statements in `Migrations.kt`,
 *   because `Migration.migrate` already runs inside one;
 * - and that transaction undoes everything when a migration throws, on [AndroidSQLiteDriver].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationPatternTest {

    @Test
    fun `every declared migration is registered once, in order, with the versions its name gives`() {
        val declared = Migrations::class.java.declaredFields
            .filter { Migration::class.java.isAssignableFrom(it.type) }
            .associate { field -> field.isAccessible = true; field.name to field.get(Migrations) as Migration }
        assertTrue("no migrations found", declared.isNotEmpty())
        val registered = Migrations.ALL_MIGRATIONS.toList()
        assertEquals("registered twice", registered.size, registered.toSet().size)
        assertEquals("declared but not registered, or the reverse", declared.values.toSet(), registered.toSet())
        for ((name, migration) in declared) {
            assertEquals(name, "MIGRATION_${migration.startVersion}_${migration.endVersion}")
        }
        assertEquals("not in version order", registered.sortedBy { it.startVersion }, registered)
    }

    @Test
    fun `the migrations reach every exported schema version one step at a time`() {
        val manual = Migrations.ALL_MIGRATIONS.associate { it.startVersion to it.endVersion }
        for (from in 1 until CURRENT) {
            val to = manual[from] ?: from.takeIf { generatedMigrationExists(it, it + 1) }?.plus(1)
            assertEquals("no single-step migration from v$from", from + 1, to)
        }
        assertTrue("a migration goes past v$CURRENT", manual.values.all { it <= CURRENT })
    }

    @Test
    fun `the migrations leave the transaction to Room`() {
        val source = MigrationSource.text()
        val code = KotlinSourceText.codeLines(source).joinToString("\n")
        val calls = TRANSACTION_CALL.findAll(code).map { it.value }.toList()
        assertTrue("transaction calls in Migrations.kt: $calls", calls.isEmpty())
        val statements = MigrationSource.sqlArguments(source).filter { TRANSACTION_STATEMENT.matches(it) }
        assertTrue("transaction statements in Migrations.kt: $statements", statements.isEmpty())
    }

    @Test
    fun `the guards see transaction calls and statements`() {
        val sample = """
            fun f() {
                // db.beginTransaction() in a comment is fine
                connection.execSQL("CREATE TRIGGER t AFTER DELETE ON e BEGIN DELETE FROM x; END")
                connection.execSQL("CREATE TRIGGER u AFTER DELETE ON e " + "BEGIN" + " DELETE FROM y; " + "END")
                Log.d(TAG, "COMMIT")
                db.beginTransaction()
                connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
                connection.execSQL("COMMIT")
            }
        """.trimIndent()
        val code = KotlinSourceText.codeLines(sample).joinToString("\n")
        assertEquals(listOf("beginTransaction"), TRANSACTION_CALL.findAll(code).map { it.value }.toList())
        assertEquals(
            listOf("BEGIN IMMEDIATE TRANSACTION", "COMMIT"),
            MigrationSource.sqlArguments(sample).filter { TRANSACTION_STATEMENT.matches(it) },
        )
    }

    @Test
    fun `a migration that throws leaves the database as it was`(): Unit = runBlocking {
        val file = databaseFile("rollback-driver")
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
        val driverHelper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), KashCalDatabase::class
        )
        driverHelper.createDatabase(CURRENT - 1).let { connection ->
            MigrationSeeder.seed(CURRENT - 1) { connection.execSQL(it) }
            connection.close()
        }
        val before = DatabaseDump.of(file)
        try {
            driverHelper.runMigrationsAndValidate(CURRENT, listOf(failingMigration()))
            fail("expected the migration to fail")
        } catch (_: DeliberateFailure) {
        }
        assertEquals(before, DatabaseDump.of(file))
    }

    /** Writes to the schema and the rows, then throws, as a migration that fails halfway does. */
    private fun failingMigration() = object : Migration(CURRENT - 1, CURRENT) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DELETE FROM events")
            connection.execSQL("CREATE TABLE half_done (x INTEGER)")
            connection.execSQL("PRAGMA user_version = $CURRENT")
            throw DeliberateFailure()
        }
    }

    private fun databaseFile(name: String): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name).also { it.parentFile?.mkdirs() }

    private fun generatedMigrationExists(from: Int, to: Int): Boolean = runCatching {
        Class.forName("${KashCalDatabase::class.java.name}_AutoMigration_${from}_${to}_Impl")
    }.isSuccess

    /** Marks the failure the test migration throws, so an unrelated error fails the test. */
    private class DeliberateFailure : RuntimeException("deliberate migration failure")

    private companion object {
        val CURRENT: Int = TestDatabases.exportedSchemaVersion

        val TRANSACTION_CALL = Regex(
            """\b(beginTransaction\w*|setTransactionSuccessful|endTransaction|inTransaction|""" +
                """withTransaction|immediateTransaction|deferredTransaction|exclusiveTransaction|useWriterConnection)\b"""
        )

        /** A whole SQL statement argument that opens, ends or nests a transaction. */
        val TRANSACTION_STATEMENT = Regex(
            """(?i)\s*(BEGIN(\s+(DEFERRED|IMMEDIATE|EXCLUSIVE))?(\s+TRANSACTION)?|COMMIT(\s+TRANSACTION)?|""" +
                """END(\s+TRANSACTION)?|ROLLBACK(\s+TRANSACTION)?(\s+TO\s+.*)?|SAVEPOINT\s+.*|RELEASE(\s+SAVEPOINT)?\s+.*)\s*;?\s*"""
        )
    }
}
