package org.onekash.kashcal.data.db.migration

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.kashCalProductionConfig
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.testutil.queryRows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests that the v28 to v29 upgrade loses nothing, from every start version: a seeded database is
 * migrated to v28 with the shipped migrations, the file is copied, only `MIGRATION_28_29` runs on
 * the copy, and the two files are compared in SQL (the v28 side holds a 2.5 MB `raw_ical`, which
 * no test can read whole through the cursor window). One upgrade, so values the older migrations
 * stamp with the clock are the same on both sides.
 *
 * Every table but `events` and `event_ics` must be unchanged, `events` unchanged in every column
 * it keeps, every event's stored ICS (its own pieces, else its series') must equal its old
 * `raw_ical` whenever that held one, and the indexes and triggers on `events` must be the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EventIcsMigrationEquivalenceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun fresh(name: String): File = instrumentation.targetContext.getDatabasePath(name).also { file ->
        file.parentFile?.mkdirs()
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun helper(file: File) = MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), KashCalDatabase::class)

    /** Seeds [version] in a new file and upgrades it to v28; returns the file. */
    private fun v28From(version: Int, name: String): File = runBlocking {
        val file = fresh(name)
        val helper = helper(file)
        helper.createDatabase(version).use { db ->
            MigrationSeeder.seed(version, oversized = version == 28) { db.execSQL(it) }
        }
        if (version < 28) {
            helper.runMigrationsAndValidate(28, Migrations.ALL_MIGRATIONS.filter { it.endVersion <= 28 }).close()
        }
        file
    }

    private fun copy(from: File, name: String): File {
        val to = fresh(name)
        listOf("", "-wal").forEach { suffix ->
            File(from.path + suffix).takeIf { it.isFile }?.copyTo(File(to.path + suffix), overwrite = true)
        }
        return to
    }

    private fun SQLiteConnection.long(sql: String): Long = queryRows(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun SQLiteConnection.strings(sql: String): List<String> =
        queryRows(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) ?: "null") } }

    private fun SQLiteConnection.columns(schema: String, table: String): List<String> =
        strings("SELECT name FROM $schema.pragma_table_info('$table')")

    /** Returns problems found comparing the migrated main database with the attached v28 `old`. */
    private fun SQLiteConnection.differences(): List<String> {
        val problems = mutableListOf<String>()
        val tables = strings("SELECT name FROM old.sqlite_master WHERE type = 'table' AND name NOT LIKE 'android_%' AND name != 'room_master_table'")
        for (table in tables - "events") {
            val list = columns("old", table).joinToString(", ") { "`$it`" }
            val missing = long("SELECT COUNT(*) FROM (SELECT $list FROM old.`$table` EXCEPT SELECT $list FROM main.`$table`)")
            val added = long("SELECT COUNT(*) FROM (SELECT $list FROM main.`$table` EXCEPT SELECT $list FROM old.`$table`)")
            if (missing != 0L || added != 0L) problems += "$table: $missing rows lost, $added rows added"
        }
        val kept = columns("main", "events")
        if ("raw_ical" in kept) problems += "events still has raw_ical"
        val list = kept.joinToString(", ") { "`$it`" }
        val eventsLost = long("SELECT COUNT(*) FROM (SELECT $list FROM old.events EXCEPT SELECT $list FROM main.events)")
        val eventsAdded = long("SELECT COUNT(*) FROM (SELECT $list FROM main.events EXCEPT SELECT $list FROM old.events)")
        if (eventsLost != 0L || eventsAdded != 0L) problems += "events: $eventsLost rows lost, $eventsAdded rows added"

        // Every old text an event held is what it reads now: its own pieces, else its series'.
        val wrongText = long(
            """
            SELECT COUNT(*) FROM old.events o
            WHERE o.raw_ical IS NOT NULL AND o.raw_ical != '' AND instr(CAST(o.raw_ical AS BLOB), x'00') = 0
            AND o.raw_ical IS NOT COALESCE(
                (SELECT group_concat(content, '') FROM (SELECT content FROM main.event_ics WHERE event_id = o.id ORDER BY part_index)),
                (SELECT group_concat(content, '') FROM (SELECT content FROM main.event_ics WHERE event_id = o.original_event_id ORDER BY part_index))
            )
            """.trimIndent()
        )
        if (wrongText != 0L) problems += "$wrongText events read another text than their old raw_ical"
        val bigPieces = long("SELECT COUNT(*) FROM main.event_ics WHERE length(content) > 262144")
        if (bigPieces != 0L) problems += "$bigPieces pieces over 262,144 characters"

        val shape = "SELECT sql FROM %s.sqlite_master WHERE tbl_name = 'events' AND type IN ('index', 'trigger') AND sql IS NOT NULL ORDER BY sql"
        if (strings(shape.format("old")) != strings(shape.format("main"))) problems += "indexes or triggers on events differ"
        return problems
    }

    @Test
    fun `every start version upgrades to v29 with nothing lost`() = runBlocking {
        val problems = mutableListOf<String>()
        for (version in 1..28) {
            val v28 = v28From(version, "equiv-$version-a")
            val migrated = copy(v28, "equiv-$version-b")
            helper(migrated).runMigrationsAndValidate(29, listOf(Migrations.MIGRATION_28_29)).use { db ->
                db.execSQL("ATTACH DATABASE '${v28.path}' AS old")
                db.differences().forEach { problems += "v$version: $it" }
                db.execSQL("DETACH DATABASE old")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the 2_5 MB copy and the shared one are moved, not duplicated`() = runBlocking {
        val v28 = v28From(28, "equiv-big-a")
        val migrated = copy(v28, "equiv-big-b")
        helper(migrated).runMigrationsAndValidate(29, listOf(Migrations.MIGRATION_28_29)).use { db ->
            // Series 200 shares its text with four occurrences; 230 shares 2.5 MB with one.
            assertEquals(0L, db.long("SELECT COUNT(*) FROM event_ics WHERE event_id IN (201, 202, 203, 204, 231)"))
            assertEquals(10L, db.long("SELECT COUNT(*) FROM event_ics WHERE event_id = 230"))
            assertEquals(1L, db.long("SELECT COUNT(DISTINCT event_id) FROM event_ics WHERE event_id = 205"))
        }
    }

    @Test
    fun `the app's own open upgrades a seeded v28 file`() = runBlocking {
        val v28 = v28From(28, "equiv-production")
        val room = TestDatabases.file(instrumentation.targetContext, v28.name).kashCalProductionConfig().build()
        try {
            room.useReaderConnection { connection ->
                connection.usePrepared("SELECT COUNT(*) FROM pragma_table_info('events') WHERE name = 'raw_ical'") {
                    it.step(); assertEquals(0L, it.getLong(0))
                }
                connection.usePrepared("SELECT COUNT(DISTINCT event_id) FROM event_ics") {
                    it.step(); assertTrue(it.getLong(0) > 0)
                }
            }
            assertEquals(230L, room.eventsDao().getById(230)!!.id)
        } finally {
            room.close()
        }
    }
}
