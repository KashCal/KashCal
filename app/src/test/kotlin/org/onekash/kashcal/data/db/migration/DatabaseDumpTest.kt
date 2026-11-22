package org.onekash.kashcal.data.db.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests that [DatabaseDump] reports every kind of change a migration could make by mistake, so a
 * dump comparison that passes means the databases match, and that [MigrationSeeder] keeps
 * its timestamps out of the window the dump masks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DatabaseDumpTest {

    private lateinit var file: File
    private lateinit var connection: SQLiteConnection

    @Before
    fun setup() {
        file = File(RuntimeEnvironment.getApplication().cacheDir, "dump-test.db").also { it.delete() }
        connection = AndroidSQLiteDriver().open(file.path)
        connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, n INTEGER, v)")
        connection.execSQL("CREATE INDEX t_name ON t (name)")
        connection.execSQL("CREATE TRIGGER t_trigger AFTER DELETE ON t BEGIN SELECT 1; END")
        connection.execSQL("INSERT INTO t VALUES (1, 'Äpfel', 5, 5), (2, NULL, 6, NULL)")
    }

    @After
    fun tearDown() {
        connection.close()
        file.delete()
    }

    private fun dump() = DatabaseDump.of(connection)

    @Test
    fun `a changed value shows`() {
        val before = dump()
        connection.execSQL("UPDATE t SET n = 7 WHERE id = 1")
        assertTrue(DatabaseDump.differences(before, dump()).isNotEmpty())
    }

    @Test
    fun `a value stored as another type shows`() {
        // Column v has no affinity, so SQLite keeps the text '5' as text; a typed column would
        // convert it back to the integer 5 and the databases would be equal.
        val before = dump()
        connection.execSQL("UPDATE t SET v = '5' WHERE id = 1")
        assertTrue(DatabaseDump.differences(before, dump()).isNotEmpty())
    }

    @Test
    fun `a removed row shows`() {
        val before = dump()
        connection.execSQL("DELETE FROM t WHERE id = 2")
        assertTrue(DatabaseDump.differences(before, dump()).isNotEmpty())
    }

    @Test
    fun `a changed trigger shows`() {
        val before = dump()
        connection.execSQL("DROP TRIGGER t_trigger")
        connection.execSQL("CREATE TRIGGER t_trigger AFTER DELETE ON t BEGIN SELECT 2; END")
        assertTrue(DatabaseDump.differences(before, dump()).isNotEmpty())
    }

    @Test
    fun `two statements swapped inside a trigger show`() {
        // Same statements, other order: the pending-operations trigger depends on its order.
        connection.execSQL("DROP TRIGGER t_trigger")
        connection.execSQL("CREATE TRIGGER t_trigger AFTER DELETE ON t BEGIN\n  SELECT 1;\n  SELECT 2;\nEND")
        val before = dump()
        connection.execSQL("DROP TRIGGER t_trigger")
        connection.execSQL("CREATE TRIGGER t_trigger AFTER DELETE ON t BEGIN\n  SELECT 2;\n  SELECT 1;\nEND")
        assertTrue(DatabaseDump.differences(before, dump()).isNotEmpty())
    }

    @Test
    fun `a missing index shows`() {
        val before = dump()
        connection.execSQL("DROP INDEX t_name")
        assertTrue(DatabaseDump.differences(before, dump()).isNotEmpty())
    }

    @Test
    fun `a large value is pinned by its hash`() {
        connection.execSQL("UPDATE t SET name = 'x' || hex(zeroblob(1000)) WHERE id = 1")
        val before = dump()
        assertTrue(before.contains("sha256="))
        connection.execSQL("UPDATE t SET name = 'y' || hex(zeroblob(1000)) WHERE id = 1")
        assertNotEquals(before, dump())
    }

    @Test
    fun `integers inside the time window are masked and others are not`() {
        connection.execSQL("UPDATE t SET n = 1700000000000 WHERE id = 1")
        val text = DatabaseDump.of(connection, timeWindow = 1_699_999_999_000L..1_700_000_001_000L)
        assertTrue(text.contains("n=integer:<NOW>"))
        assertTrue(text.contains("n=integer:6"))
    }

    @Test
    fun `seeded timestamps lie before the seed time limit`() {
        for (version in 1..28) {
            val statements = MigrationSeeder.statements(version).joinToString("\n") { it.sql }
            val tooLate = Regex("""\b1[5-9]\d{11}\b""").findAll(statements)
                .map { it.value.toLong() }.filter { it >= MigrationSeeder.SEED_TIME_LIMIT }.toList()
            assertEquals("schema $version seeds timestamps at or after the limit", emptyList<Long>(), tooLate)
        }
    }
}
