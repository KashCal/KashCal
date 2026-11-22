package org.onekash.kashcal.data.db.migration

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.testutil.ConnectionSql
import org.onekash.kashcal.testutil.queryRows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs `MIGRATION_28_29` on v28 databases Room's helper builds from `28.json` and checks which
 * events own an ICS afterwards, that their pieces join to the old `raw_ical`, and that the
 * rebuild of `events` keeps every row, index, trigger, foreign-key state and the id counter.
 * Large texts are compared in SQL: Robolectric enforces the 2 MB cursor window, so a test can't
 * read one whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class Migration28To29Test {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file: File = instrumentation.targetContext.getDatabasePath("migration-28-29").apply {
        parentFile?.mkdirs()
        listOf("", "-journal", "-wal", "-shm").forEach { File(path + it).delete() }
    }
    private val helper = MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), KashCalDatabase::class)
    private val v28: SQLiteConnection = runBlocking { helper.createDatabase(28) }.apply {
        execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'caldav', 'a@example.test', 0)")
        execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://dav.example.test/c/', 'C', 0)")
    }
    private var migrated: SQLiteConnection? = null

    @After
    fun tearDown() {
        migrated?.close()
        runCatching { v28.close() }
    }

    /** Inserts event [id]; [rawIcal] is a SQL expression. */
    private fun event(
        id: Long,
        rawIcal: String,
        series: Long? = null,
        url: String? = "https://dav.example.test/c/$id.ics",
        extra: String? = null,
        uid: String = "uid-${series ?: id}",
    ) {
        v28.execSQL(
            "INSERT INTO events (id, uid, import_id, calendar_id, title, start_ts, end_ts, original_event_id, " +
                "original_instance_time, caldav_url, extra_properties, raw_ical, dtstamp, created_at, updated_at) VALUES " +
                "($id, '$uid', '$uid', 1, 'E$id', ${id * 1000}, ${id * 1000 + 1}, ${series ?: "NULL"}, " +
                "${series?.let { id * 1000 } ?: "NULL"}, ${url?.let { "'$it'" } ?: "NULL"}, " +
                "${extra?.let { "'$it'" } ?: "NULL"}, $rawIcal, 0, 0, 0)"
        )
    }

    private fun migrate(): SQLiteConnection = runBlocking {
        v28.close()
        helper.runMigrationsAndValidate(29, listOf(Migrations.MIGRATION_28_29))
    }.also { migrated = it }

    private fun SQLiteConnection.long(sql: String): Long = queryRows(sql).use { it.moveToFirst(); it.getLong(0) }

    /** Returns each owner's piece count. */
    private fun SQLiteConnection.owners(): Map<Long, Long> =
        queryRows("SELECT event_id, COUNT(*) FROM event_ics GROUP BY event_id").use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
        }

    /** Returns whether [owner]'s pieces joined in order equal [expectedSql], compared in SQL. */
    private fun SQLiteConnection.joinedEquals(owner: Long, expectedSql: String): Boolean = long(
        "SELECT (SELECT group_concat(content, '') FROM (SELECT content FROM event_ics " +
            "WHERE event_id = $owner ORDER BY part_index)) IS ($expectedSql)"
    ) == 1L

    @Test
    fun `a series stores its resource once and occurrences sharing it store none`() {
        event(1, "'BODY-1'")
        event(2, "'BODY-1'", series = 1)
        event(3, "'BODY-1'", series = 1)
        event(4, "'BODY-1'", series = 1)
        val db = migrate()
        assertEquals(mapOf(1L to 1L), db.owners())
        assertTrue(db.joinedEquals(1, "'BODY-1'"))
    }

    @Test
    fun `a pull placeholder takes its lowest occurrence's ICS and a differing one keeps its own`() {
        event(10, "NULL", url = null, extra = """{"X-KASHCAL-SYNTHETIC-MASTER":"true"}""")
        event(12, "'ORPHAN-B'", series = 10)
        event(11, "'ORPHAN-A'", series = 10)
        val db = migrate()
        assertEquals(mapOf(10L to 1L, 12L to 1L), db.owners())
        assertTrue(db.joinedEquals(10, "'ORPHAN-A'"))
        assertTrue(db.joinedEquals(12, "'ORPHAN-B'"))
    }

    @Test
    fun `a series without ICS that is not a placeholder takes nothing from its occurrences`() {
        event(20, "NULL", url = null)
        event(21, "'FILE-COPY'", series = 20, url = null)
        val db = migrate()
        assertEquals(mapOf(21L to 1L), db.owners())
    }

    @Test
    fun `an occurrence without ICS under a series with one stores none`() {
        event(30, "'SERIES'")
        event(31, "NULL", series = 30)
        assertEquals(mapOf(30L to 1L), migrate().owners())
    }

    @Test
    fun `an imported occurrence whose text differs keeps its own`() {
        event(40, "'SERIES'", url = null)
        event(41, "'CHANGED IN A NEWER FILE'", series = 40, url = null)
        val db = migrate()
        assertEquals(mapOf(40L to 1L, 41L to 1L), db.owners())
        assertTrue(db.joinedEquals(41, "'CHANGED IN A NEWER FILE'"))
    }

    @Test
    fun `an occurrence whose series row is gone keeps its own`() {
        event(50, "'DANGLING'", series = 999)
        assertEquals(mapOf(50L to 1L), migrate().owners())
    }

    @Test
    fun `empty text and text holding U+0000 count as none and the migration succeeds`() {
        event(60, "''")
        event(61, "'BEGIN' || char(0) || 'END'")
        event(62, "'KEPT'")
        assertEquals(mapOf(62L to 1L), migrate().owners())
    }

    @Test
    fun `pieces hold 262,144 characters as the store's do`() {
        event(70, "replace(hex(zeroblob(${EventIcsStore.PART_CHARS / 2})), '00', 'ab') || 'z'")
        val db = migrate()
        assertEquals(mapOf(70L to 2L), db.owners())
        assertEquals(EventIcsStore.PART_CHARS.toLong(), db.long("SELECT length(content) FROM event_ics WHERE event_id = 70 AND part_index = 0"))
        assertEquals("z", db.queryRows("SELECT content FROM event_ics WHERE event_id = 70 AND part_index = 1").use { it.moveToFirst(); it.getString(0) })
    }

    @Test
    fun `a 2_5 MB text is split and joins back whole`() {
        val big = "'BEGIN:VCALENDAR' || replace(hex(zeroblob(1250000)), '00', 'äb') || 'END:VCALENDAR'"
        event(80, big)
        event(81, big, series = 80)
        val db = migrate()
        assertEquals(mapOf(80L to 10L), db.owners())
        assertTrue(db.joinedEquals(80, big))
        assertTrue(db.long("SELECT MAX(length(CAST(content AS BLOB))) FROM event_ics") <= 1_048_576)
    }

    @Test
    fun `four-byte characters split at code points and join back whole`() {
        val emoji = "replace(hex(zeroblob(${EventIcsStore.PART_CHARS})), '00', '😀') || 'x'"
        event(90, emoji)
        val db = migrate()
        assertEquals(mapOf(90L to 2L), db.owners())
        assertTrue(db.joinedEquals(90, emoji))
        assertEquals(EventIcsStore.PART_CHARS.toLong(), db.long("SELECT length(content) FROM event_ics WHERE event_id = 90 AND part_index = 0"))
    }

    private fun SQLiteConnection.eventRows(columns: String): List<List<String?>> =
        queryRows("SELECT $columns FROM events ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
        }

    private fun SQLiteConnection.names(type: String): List<String> =
        queryRows("SELECT name FROM sqlite_master WHERE type = '$type' AND tbl_name = 'events' AND sql IS NOT NULL ORDER BY name")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)!!) } }

    @Test
    fun `every event row is kept with every column but raw_ical`() {
        event(1, "'BODY-1'")
        event(2, "'BODY-1'", series = 1)
        event(3, "NULL", url = null)
        val columns = "id, uid, import_id, calendar_id, title, start_ts, end_ts, original_event_id, " +
            "original_instance_time, caldav_url, extra_properties, dtstamp, created_at, updated_at"
        val before = v28.eventRows(columns)
        val db = migrate()
        assertEquals(before, db.eventRows(columns))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM pragma_table_info('events') WHERE name = 'raw_ical'"))
    }

    @Test
    fun `the indexes and triggers on events are recreated`() {
        listOf(
            "CREATE TRIGGER t_insert BEFORE INSERT ON events WHEN NEW.original_event_id IS NULL BEGIN SELECT 1; END",
            "CREATE TRIGGER t_delete AFTER DELETE ON events BEGIN SELECT 1; END",
        ).forEach { v28.execSQL(it) }
        val indexes = v28.names("index")
        val db = migrate()
        assertEquals(indexes, db.names("index"))
        assertEquals(listOf("t_delete", "t_insert"), db.names("trigger"))
    }

    @Test
    fun `an exception whose series row is gone keeps its dangling link`() {
        event(50, "'DANGLING'", series = 999)
        v28.execSQL("INSERT INTO occurrences (event_id, calendar_id, start_ts, end_ts, start_day, end_day) VALUES (998, 1, 0, 1, 1, 1)")
        val db = migrate()
        assertEquals(999L, db.long("SELECT original_event_id FROM events WHERE id = 50"))
        assertEquals(2L, db.long("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test
    fun `the id counter is carried over, past the largest id`() {
        event(1, "NULL")
        v28.execSQL("UPDATE sqlite_sequence SET seq = 500 WHERE name = 'events'")
        val db = migrate()
        assertEquals(500L, db.long("SELECT seq FROM sqlite_sequence WHERE name = 'events'"))
        db.execSQL("INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES ('n', 1, 'N', 0, 1, 0, 0, 0)")
        assertEquals(501L, db.long("SELECT MAX(id) FROM events"))
    }

    @Test
    fun `the id counter is kept when every event was deleted`() {
        event(1, "NULL")
        v28.execSQL("UPDATE sqlite_sequence SET seq = 500 WHERE name = 'events'")
        v28.execSQL("DELETE FROM events")
        val db = migrate()
        assertEquals(500L, db.long("SELECT seq FROM sqlite_sequence WHERE name = 'events'"))
        db.execSQL("INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES ('n', 1, 'N', 0, 1, 0, 0, 0)")
        assertEquals(501L, db.long("SELECT MAX(id) FROM events"))
    }

    @Test
    fun `the rebuild refuses to run with foreign keys on and leaves v28`() {
        event(1, "'BODY-1'")
        v28.execSQL("PRAGMA foreign_keys = ON")
        val error = runCatching { ConnectionSql.migrate(v28, Migrations.MIGRATION_28_29) }.exceptionOrNull()
        assertTrue("expected a refusal, got $error", error?.message.orEmpty().contains("foreign keys off"))
        assertEquals(1L, v28.long("SELECT COUNT(*) FROM pragma_table_info('events') WHERE name = 'raw_ical'"))
        assertEquals(0L, v28.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'event_ics'"))
    }

    @Test
    fun `deleting a migrated series deletes its pieces`() {
        event(1, "'BODY-1'")
        val db = migrate()
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM events WHERE id = 1")
        assertEquals(0L, db.long("SELECT COUNT(*) FROM event_ics"))
    }
}
