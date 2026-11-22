package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.testutil.ConnectionSql
import org.onekash.kashcal.testutil.execSQL
import org.onekash.kashcal.testutil.queryRows
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs the manual KashCalDatabase migrations from 1→2 to 27→28 on a database file opened through
 * `AndroidSQLiteDriver` (under Robolectric) and checks tables, columns, indexes, triggers and data;
 * [Migration28To29Test] covers 28→29.
 * The 3→4 AutoMigration isn't run here; [MigrationHashValidationTest] and the Room test in
 * [MigrationGoldenTest] run it.
 *
 * Each test builds the schema the migration needs, runs it inside a transaction as Room does
 * ([ConnectionSql.migrate]) and inspects the result through `sqlite_master`, PRAGMA and
 * queries. The registry tests at the end check `Migrations.ALL_MIGRATIONS`.
 * [MigrationHashValidationTest] checks the results against Room's exported schemas.
 *
 * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationTest {

    private lateinit var db: SQLiteConnection

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        db = ConnectionSql.open(RuntimeEnvironment.getApplication(), DB_NAME)
    }

    @After
    fun tearDown() {
        ConnectionSql.close(RuntimeEnvironment.getApplication(), db, DB_NAME)
        unmockkStatic(Log::class)
    }

    /** Runs [migration] inside a transaction, as Room does; see [ConnectionSql.migrate]. */
    private fun migrate(migration: Migration) = ConnectionSql.migrate(db, migration)

    // ==================== Schema Query Helpers ====================

    private fun tableExists(tableName: String): Boolean {
        db.queryRows("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun columnExists(tableName: String, columnName: String): Boolean {
        db.queryRows("PRAGMA table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    private fun getColumnNames(tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.queryRows("PRAGMA table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex)!!)
            }
        }
        return columns
    }

    private fun indexExists(indexName: String): Boolean {
        db.queryRows("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(indexName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun triggerExists(triggerName: String): Boolean {
        db.queryRows("SELECT name FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(triggerName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun isIndexUnique(indexName: String): Boolean {
        db.queryRows(
            "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(indexName)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            val sql = cursor.getString(0) ?: return false
            return sql.contains("UNIQUE", ignoreCase = true)
        }
    }

    /**
     * Creates the six v1 tables (accounts, calendars, events, occurrences, pending_operations,
     * sync_logs) with a subset of the v1 indexes; tests that need another one create it.
     */
    private fun createV1Schema() {
        db.execSQL("""CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, provider TEXT NOT NULL, email TEXT NOT NULL, display_name TEXT, principal_url TEXT, home_set_url TEXT, credential_key TEXT, is_enabled INTEGER NOT NULL DEFAULT 1, last_sync_at INTEGER, last_successful_sync_at INTEGER, consecutive_sync_failures INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_provider_email ON accounts (provider, email)""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS calendars (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, account_id INTEGER NOT NULL, caldav_url TEXT NOT NULL, display_name TEXT NOT NULL, color INTEGER NOT NULL, ctag TEXT, sync_token TEXT, is_visible INTEGER NOT NULL DEFAULT 1, is_default INTEGER NOT NULL DEFAULT 0, is_read_only INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_calendars_account_id ON calendars (account_id)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS index_calendars_caldav_url ON calendars (caldav_url)""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uid TEXT NOT NULL, calendar_id INTEGER NOT NULL, title TEXT NOT NULL, location TEXT, description TEXT, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, timezone TEXT, is_all_day INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'CONFIRMED', transp TEXT NOT NULL DEFAULT 'OPAQUE', classification TEXT NOT NULL DEFAULT 'PUBLIC', organizer_email TEXT, organizer_name TEXT, rrule TEXT, rdate TEXT, exdate TEXT, duration TEXT, original_event_id INTEGER, original_instance_time INTEGER, original_sync_id TEXT, reminders TEXT, extra_properties TEXT, dtstamp INTEGER NOT NULL, caldav_url TEXT, etag TEXT, sequence INTEGER NOT NULL DEFAULT 0, sync_status TEXT NOT NULL DEFAULT 'SYNCED', last_sync_error TEXT, sync_retry_count INTEGER NOT NULL DEFAULT 0, local_modified_at INTEGER, server_modified_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(calendar_id) REFERENCES calendars(id) ON DELETE CASCADE, FOREIGN KEY(original_event_id) REFERENCES events(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_events_calendar_id ON events (calendar_id)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_events_uid ON events (uid)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_events_original_event_id ON events (original_event_id)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_events_sync_status ON events (sync_status)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_events_caldav_url ON events (caldav_url)""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event_id INTEGER NOT NULL, calendar_id INTEGER NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, start_day INTEGER NOT NULL, end_day INTEGER NOT NULL, is_cancelled INTEGER NOT NULL DEFAULT 0, exception_event_id INTEGER, FOREIGN KEY(event_id) REFERENCES events(id) ON DELETE CASCADE, FOREIGN KEY(exception_event_id) REFERENCES events(id) ON DELETE SET NULL)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_occurrences_event_id ON occurrences (event_id)""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS pending_operations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event_id INTEGER NOT NULL, operation TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', retry_count INTEGER NOT NULL DEFAULT 0, max_retries INTEGER NOT NULL DEFAULT 5, next_retry_at INTEGER NOT NULL DEFAULT 0, last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, calendar_id INTEGER, event_uid TEXT, action TEXT NOT NULL, result TEXT NOT NULL, details TEXT, http_status INTEGER)""")
    }

    // ==================== Migration 1 to 2: ics_subscriptions ====================

    @Test
    fun `migration 1 to 2 creates ics_subscriptions table`() {
        createV1Schema()
        assertFalse(tableExists("ics_subscriptions"))

        migrate(Migrations.MIGRATION_1_2)

        assertTrue(tableExists("ics_subscriptions"))
        val columns = getColumnNames("ics_subscriptions")
        assertTrue("id" in columns)
        assertTrue("url" in columns)
        assertTrue("name" in columns)
        assertTrue("color" in columns)
        assertTrue("calendar_id" in columns)
        assertTrue("last_sync" in columns)
        assertTrue("sync_interval_hours" in columns)
        assertTrue("enabled" in columns)
        assertTrue("etag" in columns)
        assertTrue("last_modified" in columns)
        assertTrue("username" in columns)
        assertTrue("last_error" in columns)
        assertTrue("created_at" in columns)
    }

    @Test
    fun `migration 1 to 2 creates unique indexes`() {
        createV1Schema()

        migrate(Migrations.MIGRATION_1_2)

        assertTrue(indexExists("index_ics_subscriptions_url"))
        assertTrue(indexExists("index_ics_subscriptions_calendar_id"))
    }

    @Test
    fun `migration 1 to 2 is idempotent`() {
        createV1Schema()

        // Run twice - should not crash
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_1_2)

        assertTrue(tableExists("ics_subscriptions"))
    }

    // ==================== Migration 2 to 3: scheduled_reminders ====================

    @Test
    fun `migration 2 to 3 creates scheduled_reminders table with all columns`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)

        migrate(Migrations.MIGRATION_2_3)

        assertTrue(tableExists("scheduled_reminders"))
        val columns = getColumnNames("scheduled_reminders")
        assertTrue("id" in columns)
        assertTrue("event_id" in columns)
        assertTrue("occurrence_time" in columns)
        assertTrue("trigger_time" in columns)
        assertTrue("reminder_offset" in columns)
        assertTrue("status" in columns)
        assertTrue("snooze_count" in columns)
        assertTrue("event_title" in columns)
        assertTrue("event_location" in columns)
        assertTrue("is_all_day" in columns)
        assertTrue("calendar_color" in columns)
        assertTrue("created_at" in columns)
    }

    @Test
    fun `migration 2 to 3 creates all required indexes`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)

        migrate(Migrations.MIGRATION_2_3)

        assertTrue(indexExists("index_scheduled_reminders_event_id"))
        assertTrue(indexExists("index_scheduled_reminders_trigger_time"))
        assertTrue(indexExists("index_scheduled_reminders_status"))
        assertTrue(indexExists("index_scheduled_reminders_unique"))
    }

    // ==================== Migration 4 to 5: move columns ====================

    @Test
    fun `migration 4 to 5 adds target_url and target_calendar_id columns`() {
        createV1Schema()
        // 1→2 and 2→3 skipped: pending_operations doesn't depend on them
        assertFalse(columnExists("pending_operations", "target_url"))

        migrate(Migrations.MIGRATION_4_5)

        assertTrue(columnExists("pending_operations", "target_url"))
        assertTrue(columnExists("pending_operations", "target_calendar_id"))
    }

    // ==================== Migration 5 to 6: move_phase ====================

    @Test
    fun `migration 5 to 6 adds move_phase column with default 0`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)

        migrate(Migrations.MIGRATION_5_6)

        assertTrue(columnExists("pending_operations", "move_phase"))

        // Insert a row and verify default value
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (1, 'MOVE', 0, 0)")
        db.queryRows("SELECT move_phase FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    // ==================== Migration 6 to 7: icaldav columns ====================

    @Test
    fun `migration 6 to 7 adds raw_ical import_id and alarm_count columns`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        migrate(Migrations.MIGRATION_6_7)

        assertTrue(columnExists("events", "raw_ical"))
        assertTrue(columnExists("events", "import_id"))
        assertTrue(columnExists("events", "alarm_count"))
    }

    @Test
    fun `migration 6 to 7 initializes import_id from uid`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        // Insert an event with a known UID
        db.execSQL("""INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)""")
        db.execSQL("""INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Cal', -1)""")
        db.execSQL("""INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES ('test-uid-123', 1, 'Test', 0, 1, 0, 0, 0)""")

        migrate(Migrations.MIGRATION_6_7)

        // Verify import_id was initialized from uid
        db.queryRows("SELECT import_id FROM events WHERE uid = 'test-uid-123'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("test-uid-123", cursor.getString(0))
        }
    }

    @Test
    fun `migration 6 to 7 creates import_id indexes`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        migrate(Migrations.MIGRATION_6_7)

        assertTrue(indexExists("index_events_import_id"))
        assertTrue(indexExists("index_events_calendar_id_import_id"))
    }

    @Test
    fun `migration 6 to 7 creates uniqueness triggers`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        migrate(Migrations.MIGRATION_6_7)

        assertTrue(triggerExists("trigger_master_event_unique_insert"))
        assertTrue(triggerExists("trigger_master_event_unique_update"))
    }

    // ==================== Migration 7 to 8: RFC 5545/7986 fields ====================

    @Test
    fun `migration 7 to 8 adds RFC property columns`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2) // ics_subscriptions needed for boolean index
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)

        migrate(Migrations.MIGRATION_7_8)

        assertTrue(columnExists("events", "priority"))
        assertTrue(columnExists("events", "geo_lat"))
        assertTrue(columnExists("events", "geo_lon"))
        assertTrue(columnExists("events", "color"))
        assertTrue(columnExists("events", "url"))
        assertTrue(columnExists("events", "categories"))
    }

    @Test
    fun `migration 7 to 8 creates boolean indexes`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)

        migrate(Migrations.MIGRATION_7_8)

        assertTrue(indexExists("index_calendars_is_visible"))
        assertTrue(indexExists("index_accounts_is_enabled"))
        assertTrue(indexExists("index_ics_subscriptions_enabled"))
        assertTrue(indexExists("index_occurrences_is_cancelled"))
    }

    @Test
    fun `migration 7 to 8 is idempotent via addColumnIfNotExists`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)

        // Run twice - addColumnIfNotExists should prevent crash
        migrate(Migrations.MIGRATION_7_8)
        migrate(Migrations.MIGRATION_7_8)

        assertTrue(columnExists("events", "priority"))
    }

    // ==================== Migration 8 to 9: composite index ====================

    @Test
    fun `migration 8 to 9 creates exception lookup index`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)

        migrate(Migrations.MIGRATION_8_9)

        assertTrue(indexExists("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 8 to 9 is idempotent`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)

        // Run twice - indexExists check prevents crash
        migrate(Migrations.MIGRATION_8_9)
        migrate(Migrations.MIGRATION_8_9)

        assertTrue(indexExists("index_events_calendar_id_uid_original_instance_time"))
    }

    // ==================== Migration 9 to 10: unique occurrence index ====================

    @Test
    fun `migration 9 to 10 creates unique occurrence index`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)
        migrate(Migrations.MIGRATION_8_9)

        migrate(Migrations.MIGRATION_9_10)

        assertTrue(indexExists("index_occurrences_event_id_start_ts_unique"))
    }

    @Test
    fun `migration 9 to 10 removes duplicate occurrences before creating index`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)
        migrate(Migrations.MIGRATION_8_9)

        // Two identical occurrence rows for one event
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Cal', -1)")
        db.execSQL("INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES (1, 'uid-1', 1, 'Test', 1000, 2000, 0, 0, 0)")
        db.execSQL("INSERT INTO occurrences (event_id, calendar_id, start_ts, end_ts, start_day, end_day) VALUES (1, 1, 1000, 2000, 20240101, 20240101)")
        db.execSQL("INSERT INTO occurrences (event_id, calendar_id, start_ts, end_ts, start_day, end_day) VALUES (1, 1, 1000, 2000, 20240101, 20240101)")

        db.queryRows("SELECT COUNT(*) FROM occurrences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        migrate(Migrations.MIGRATION_9_10)

        // Should have removed the duplicate
        db.queryRows("SELECT COUNT(*) FROM occurrences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    // ==================== Migration 10 to 11: retry lifecycle columns ====================

    @Test
    fun `migration 10 to 11 adds lifetime_reset_at and failed_at columns`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        migrate(Migrations.MIGRATION_10_11)

        assertTrue(columnExists("pending_operations", "lifetime_reset_at"))
        assertTrue(columnExists("pending_operations", "failed_at"))
    }

    @Test
    fun `migration 10 to 11 initializes lifetime_reset_at from created_at`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        // Insert an operation with a known created_at
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (1, 'UPDATE', 12345, 0)")

        migrate(Migrations.MIGRATION_10_11)

        db.queryRows("SELECT lifetime_reset_at FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(12345, cursor.getLong(0))
        }
    }

    @Test
    fun `migration 10 to 11 is idempotent`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)

        // Run twice
        migrate(Migrations.MIGRATION_10_11)
        migrate(Migrations.MIGRATION_10_11)

        assertTrue(columnExists("pending_operations", "lifetime_reset_at"))
        assertTrue(columnExists("pending_operations", "failed_at"))
    }

    // ==================== Migration 11 to 12: source_calendar_id ====================

    @Test
    fun `migration 11 to 12 adds source_calendar_id column`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_10_11)

        migrate(Migrations.MIGRATION_11_12)

        assertTrue(columnExists("pending_operations", "source_calendar_id"))
    }

    @Test
    fun `migration 11 to 12 marks in-flight MOVE ops as FAILED`() {
        createV1Schema()
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_10_11)

        // Insert an in-flight MOVE at phase 0 (DELETE)
        db.execSQL("INSERT INTO pending_operations (event_id, operation, move_phase, status, created_at, updated_at) VALUES (1, 'MOVE', 0, 'PENDING', 0, 0)")
        // Also a normal UPDATE (should not be affected)
        db.execSQL("INSERT INTO pending_operations (event_id, operation, move_phase, status, created_at, updated_at) VALUES (2, 'UPDATE', 0, 'PENDING', 0, 0)")

        migrate(Migrations.MIGRATION_11_12)

        // MOVE at phase 0 should be marked FAILED
        db.queryRows("SELECT status FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("FAILED", cursor.getString(0))
        }

        // UPDATE should remain PENDING
        db.queryRows("SELECT status FROM pending_operations WHERE event_id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
        }
    }

    // ==================== Migration 12 to 13: unique index change ====================

    @Test
    fun `migration 12 to 13 replaces unique index with home_set_url`() {
        createV1Schema()

        migrate(Migrations.MIGRATION_12_13)

        // Old index should be gone
        assertFalse(indexExists("index_accounts_provider_email"))
        // New index should exist
        assertTrue(indexExists("index_accounts_provider_email_home_set_url"))
    }

    @Test
    fun `migration 12 to 13 allows same email on different servers`() {
        createV1Schema()

        migrate(Migrations.MIGRATION_12_13)

        // Insert two accounts with same provider+email but different home_set_url
        db.execSQL("INSERT INTO accounts (provider, email, home_set_url, created_at) VALUES ('CALDAV', 'user@example.com', 'https://server1.com/dav/', 0)")
        db.execSQL("INSERT INTO accounts (provider, email, home_set_url, created_at) VALUES ('CALDAV', 'user@example.com', 'https://server2.com/dav/', 0)")

        // Both should exist
        db.queryRows("SELECT COUNT(*) FROM accounts WHERE email = 'user@example.com'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    // ==================== Migration 13 to 14: exception unique index swap ====================

    /**
     * Runs every manual migration from 1 to 13, after creating the unique index createV1Schema()
     * omits: migration 13→14 drops index_events_original_event_id_original_instance_time.
     */
    private fun setupV13Schema() {
        createV1Schema()
        // createV1Schema() omits Room-generated unique index that migration 13->14 drops
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
            "ON events (original_event_id, original_instance_time)"
        )
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_2_3)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)
        migrate(Migrations.MIGRATION_8_9)
        migrate(Migrations.MIGRATION_9_10)
        migrate(Migrations.MIGRATION_10_11)
        migrate(Migrations.MIGRATION_11_12)
        migrate(Migrations.MIGRATION_12_13)
    }

    /**
     * Builds a minimal v13 schema for the dedup tests: the v1 tables and both indexes 13→14
     * replaces, without the master uniqueness trigger from migration 6→7. That trigger would refuse
     * an orphan exception (original_event_id = NULL) sharing its master's UID, which is the row the
     * dedup removes and which a database from before the trigger may hold.
     */
    private fun setupV13SchemaMinimal() {
        createV1Schema()
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
            "ON events (original_event_id, original_instance_time)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_events_calendar_id_uid_original_instance_time " +
            "ON events (calendar_id, uid, original_instance_time)"
        )
    }

    /** Inserts account 1 and calendar 1 for the migration 13→14 tests; returns the calendar id. */
    private fun insertTestAccountAndCalendar(): Long {
        db.execSQL("INSERT INTO accounts (id, provider, email, home_set_url, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 'https://caldav.icloud.com/', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://caldav.icloud.com/cal/', 'Test', -1)")
        return 1
    }

    @Test
    fun `migration 13 to 14 swaps unique index`() {
        setupV13Schema()

        // Before: original_event_id_original_instance_time is unique
        assertTrue(isIndexUnique("index_events_original_event_id_original_instance_time"))
        // Before: calendar_id_uid_original_instance_time is non-unique
        assertTrue(indexExists("index_events_calendar_id_uid_original_instance_time"))
        assertFalse(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))

        migrate(Migrations.MIGRATION_13_14)

        // After: original_event_id_original_instance_time is non-unique
        assertTrue(indexExists("index_events_original_event_id_original_instance_time"))
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        // After: calendar_id_uid_original_instance_time is unique
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 13 to 14 dedup removes orphan when linked exists`() {
        // The minimal schema has no master uniqueness trigger, so orphan exceptions can be
        // inserted, as in a database from before migration 6→7.
        setupV13SchemaMinimal()
        val calId = insertTestAccountAndCalendar()

        // Insert master event
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (1, 'master-uid', $calId, 'Master', 1000, 2000, 0, 0, 0)"
        )
        // Insert linked exception (has original_event_id)
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (2, 'master-uid', $calId, 'Exception Linked', 3000, 4000, 0, 0, 0, 1, 3000)"
        )
        // Insert orphan exception (same uid + instance_time but original_event_id = NULL)
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (3, 'master-uid', $calId, 'Exception Orphan', 3000, 4000, 0, 0, 0, NULL, 3000)"
        )

        migrate(Migrations.MIGRATION_13_14)

        // Orphan (id=3) deleted, linked (id=2) survives
        db.queryRows("SELECT id FROM events WHERE original_instance_time IS NOT NULL").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        // Master untouched
        db.queryRows("SELECT COUNT(*) FROM events WHERE original_instance_time IS NULL").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 13 to 14 dedup keeps highest id for duplicate orphans`() {
        // Minimal schema so orphans can be inserted; see setupV13SchemaMinimal
        setupV13SchemaMinimal()
        val calId = insertTestAccountAndCalendar()

        // Insert master
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (1, 'master-uid', $calId, 'Master', 1000, 2000, 0, 0, 0)"
        )
        // Two orphan exceptions with same (calendar_id, uid, original_instance_time)
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (2, 'master-uid', $calId, 'Orphan Old', 3000, 4000, 0, 0, 0, NULL, 3000)"
        )
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (3, 'master-uid', $calId, 'Orphan New', 3000, 4000, 0, 0, 0, NULL, 3000)"
        )

        migrate(Migrations.MIGRATION_13_14)

        // Highest id (3) survives
        db.queryRows("SELECT id, title FROM events WHERE original_instance_time IS NOT NULL").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
            assertEquals("Orphan New", cursor.getString(1))
        }
    }

    @Test
    fun `migration 13 to 14 does not delete masters`() {
        setupV13Schema()
        val calId = insertTestAccountAndCalendar()

        // Three masters: original_instance_time IS NULL, which both dedup steps exclude
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (1, 'uid-a', $calId, 'Master A', 1000, 2000, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (2, 'uid-b', $calId, 'Master B', 3000, 4000, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (3, 'uid-c', $calId, 'Master C', 5000, 6000, 0, 0, 0)"
        )

        migrate(Migrations.MIGRATION_13_14)

        db.queryRows("SELECT COUNT(*) FROM events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 13 to 14 is idempotent`() {
        setupV13Schema()

        migrate(Migrations.MIGRATION_13_14)
        migrate(Migrations.MIGRATION_13_14)

        assertTrue(indexExists("index_events_original_event_id_original_instance_time"))
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 13 to 14 handles empty table`() {
        setupV13Schema()

        migrate(Migrations.MIGRATION_13_14)

        db.queryRows("SELECT COUNT(*) FROM events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 13 to 14 new unique index blocks duplicate exceptions`() {
        setupV13Schema()
        val calId = insertTestAccountAndCalendar()

        migrate(Migrations.MIGRATION_13_14)

        // Insert master
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (1, 'master-uid', $calId, 'Master', 1000, 2000, 0, 0, 0)"
        )
        // First exception succeeds
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (2, 'master-uid', $calId, 'Exception', 3000, 4000, 0, 0, 0, 1, 3000)"
        )
        // Second exception with the same (calendar_id, uid, original_instance_time) must throw
        val error = try {
            db.execSQL(
                "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
                "original_event_id, original_instance_time) " +
                "VALUES (3, 'master-uid', $calId, 'Duplicate', 3000, 4000, 0, 0, 0, 1, 3000)"
            )
            null
        } catch (e: SQLiteException) {
            e
        }
        assertTrue("expected a UNIQUE failure, got $error", error?.message.orEmpty().contains("UNIQUE constraint failed"))
    }

    // ==================== Migration 15 to 16: calendar columns + end_timezone ====================

    @Test
    fun `migration 15 to 16 adds calendar columns`() {
        createV1Schema()

        migrate(Migrations.MIGRATION_15_16)

        assertTrue(columnExists("calendars", "is_notification_muted"))
        assertTrue(columnExists("calendars", "local_color_override"))
        assertTrue(columnExists("calendars", "default_reminder"))
    }

    @Test
    fun `migration 15 to 16 adds event end_timezone column`() {
        createV1Schema()

        migrate(Migrations.MIGRATION_15_16)

        assertTrue(columnExists("events", "end_timezone"))
    }

    @Test
    fun `migration 15 to 16 preserves existing data`() {
        createV1Schema()

        // Insert test data before migration
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Work', -16711936)")
        db.execSQL("INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) VALUES (1, 'uid-1', 1, 'Team Meeting', 1000, 2000, 'America/New_York', 0, 0, 0)")

        migrate(Migrations.MIGRATION_15_16)

        // Verify calendar data preserved
        db.queryRows("SELECT display_name, color FROM calendars WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Work", cursor.getString(0))
            assertEquals(-16711936, cursor.getInt(1))
        }

        // Verify event data preserved
        db.queryRows("SELECT title, timezone FROM events WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Team Meeting", cursor.getString(0))
            assertEquals("America/New_York", cursor.getString(1))
        }
    }

    @Test
    fun `migration 15 to 16 is idempotent`() {
        createV1Schema()

        // Run twice - addColumnIfNotExists prevents crash
        migrate(Migrations.MIGRATION_15_16)
        migrate(Migrations.MIGRATION_15_16)

        assertTrue(columnExists("calendars", "is_notification_muted"))
        assertTrue(columnExists("calendars", "local_color_override"))
        assertTrue(columnExists("calendars", "default_reminder"))
        assertTrue(columnExists("events", "end_timezone"))
    }

    @Test
    fun `migration 15 to 16 default values are correct`() {
        createV1Schema()

        migrate(Migrations.MIGRATION_15_16)

        // Insert rows after migration and verify defaults
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Cal', -1)")

        db.queryRows("SELECT is_notification_muted, local_color_override, default_reminder FROM calendars WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))  // is_notification_muted default = 0
            assertTrue(cursor.isNull(1))       // local_color_override default = NULL
            assertTrue(cursor.isNull(2))       // default_reminder default = NULL
        }

        db.execSQL("INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES (1, 'uid-1', 1, 'Test', 0, 1, 0, 0, 0)")

        db.queryRows("SELECT end_timezone FROM events WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))  // end_timezone default = NULL
        }
    }

    // ==================== Migration 16 to 17: scheduling schema bundle ====================

    /**
     * Runs the v1→v16 chain for the MIGRATION_16_17 tests: the same chain as `full migration
     * chain 1 to 16 executes without error`, without its assertions.
     */
    private fun migrateUpToV16() {
        createV1Schema()
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
                "ON events (original_event_id, original_instance_time)"
        )
        migrate(Migrations.MIGRATION_1_2)
        migrate(Migrations.MIGRATION_2_3)
        // 3→4 is AutoMigration (skip in unit tests)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)
        migrate(Migrations.MIGRATION_8_9)
        migrate(Migrations.MIGRATION_9_10)
        migrate(Migrations.MIGRATION_10_11)
        migrate(Migrations.MIGRATION_11_12)
        migrate(Migrations.MIGRATION_12_13)
        migrate(Migrations.MIGRATION_13_14)
        migrate(Migrations.MIGRATION_14_15)
        migrate(Migrations.MIGRATION_15_16)
    }

    @Test
    fun `migration 16 to 17 adds calendar_user_addresses to accounts with default empty array`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)

        assertTrue(columnExists("accounts", "calendar_user_addresses"))

        // An insert that omits the new column gets the default
        db.execSQL("INSERT INTO accounts (provider, email, created_at) VALUES ('CALDAV', 'a@example.com', 0)")
        db.queryRows("SELECT calendar_user_addresses FROM accounts WHERE email = 'a@example.com'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
    }

    @Test
    fun `migration 16 to 17 adds organizer_sent_by and organizer_schedule_status to events`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)

        assertTrue(columnExists("events", "organizer_sent_by"))
        assertTrue(columnExists("events", "organizer_schedule_status"))
    }

    @Test
    fun `migration 16 to 17 creates attendees table with all 16 columns`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)

        assertTrue(tableExists("attendees"))
        val expected = setOf(
            "id", "event_id", "address", "display_name", "role", "partstat",
            "cutype", "rsvp", "delegated_from", "delegated_to", "member",
            "sent_by", "schedule_agent", "schedule_status", "schedule_force_send",
            "sort_order"
        )
        assertEquals(expected, getColumnNames("attendees"))
    }

    @Test
    fun `migration 16 to 17 creates index_attendees_event_id`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)

        assertTrue(indexExists("index_attendees_event_id"))
    }

    @Test
    fun `migration 16 to 17 creates index_attendees_address`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)

        assertTrue(indexExists("index_attendees_address"))
    }

    @Test
    fun `migration 16 to 17 enforces FK CASCADE from attendees to events`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)
        // Foreign keys must be enabled per-connection in SQLite.
        db.execSQL("PRAGMA foreign_keys = ON")

        // Need an account+calendar+event chain to satisfy events FKs.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'fk@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'fk-uid', 1, 'T', 1000, 2000, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (event_id, address) VALUES (1, 'mailto:a@example.com')")
        db.queryRows("SELECT COUNT(*) FROM attendees WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        db.execSQL("DELETE FROM events WHERE id = 1")

        db.queryRows("SELECT COUNT(*) FROM attendees WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 16 to 17 preserves existing account data`() {
        migrateUpToV16()
        // Insert account at v16 state (no calendar_user_addresses column yet).
        db.execSQL(
            "INSERT INTO accounts (id, provider, email, display_name, created_at) " +
                "VALUES (1, 'ICLOUD', 'preserved@test.com', 'Preserved', 12345)"
        )

        migrate(Migrations.MIGRATION_16_17)

        db.queryRows(
            "SELECT email, display_name, calendar_user_addresses FROM accounts WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved@test.com", cursor.getString(0))
            assertEquals("Preserved", cursor.getString(1))
            assertEquals("[]", cursor.getString(2))
        }
    }

    @Test
    fun `migration 16 to 17 preserves existing event data with null organizer extensions`() {
        migrateUpToV16()
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 't@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://y/', 'Cal', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'preserve-uid', 1, 'Preserved Event', 1000, 2000, 'UTC', 0, 0, 0)"
        )

        migrate(Migrations.MIGRATION_16_17)

        db.queryRows(
            "SELECT title, organizer_sent_by, organizer_schedule_status FROM events WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Preserved Event", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun `migration 16 to 17 is idempotent`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)
        // The second run must be a no-op: no exception, schema unchanged
        migrate(Migrations.MIGRATION_16_17)

        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(columnExists("events", "organizer_sent_by"))
        assertTrue(columnExists("events", "organizer_schedule_status"))
        assertTrue(tableExists("attendees"))
        assertTrue(indexExists("index_attendees_event_id"))
        assertTrue(indexExists("index_attendees_address"))
    }

    @Test
    fun `migration 16 to 17 drops stale rogue attendees table with mismatched shape`() {
        migrateUpToV16()
        // Pre-create a 2-column rogue table that doesn't match the expected shape.
        db.execSQL("CREATE TABLE attendees (id INTEGER PRIMARY KEY, junk TEXT)")
        db.execSQL("INSERT INTO attendees (junk) VALUES ('rogue-data')")

        migrate(Migrations.MIGRATION_16_17)

        assertTrue(tableExists("attendees"))
        // Verify the table now has the proper 16-column shape.
        val expected = setOf(
            "id", "event_id", "address", "display_name", "role", "partstat",
            "cutype", "rsvp", "delegated_from", "delegated_to", "member",
            "sent_by", "schedule_agent", "schedule_status", "schedule_force_send",
            "sort_order"
        )
        assertEquals(expected, getColumnNames("attendees"))
        // And the rogue data was dropped (table is empty).
        db.queryRows("SELECT COUNT(*) FROM attendees").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 16 to 17 leaves matching pre-existing attendees table alone`() {
        migrateUpToV16()
        // Pre-create the table with the exact expected shape (mimics a forward-compatible
        // table left from a partial run). Insert a row.
        db.execSQL(
            "CREATE TABLE attendees (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "event_id INTEGER NOT NULL, " +
                "address TEXT NOT NULL, " +
                "display_name TEXT, " +
                "role TEXT, " +
                "partstat TEXT, " +
                "cutype TEXT, " +
                "rsvp INTEGER, " +
                "delegated_from TEXT NOT NULL DEFAULT '[]', " +
                "delegated_to TEXT NOT NULL DEFAULT '[]', " +
                "member TEXT NOT NULL DEFAULT '[]', " +
                "sent_by TEXT, " +
                "schedule_agent TEXT, " +
                "schedule_status TEXT, " +
                "schedule_force_send TEXT, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(event_id) REFERENCES events(id) ON DELETE CASCADE)"
        )
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'r@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://r/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'r-uid', 1, 'T', 1000, 2000, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (event_id, address) VALUES (1, 'mailto:keep@example.com')")

        migrate(Migrations.MIGRATION_16_17)

        // The row from before the migration is still there, so the table wasn't dropped
        db.queryRows("SELECT address FROM attendees WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("mailto:keep@example.com", cursor.getString(0))
        }
    }

    // ==================== Full Migration Chain ====================

    @Test
    fun `full migration chain 1 to 16 executes without error`() {
        createV1Schema()
        // createV1Schema() omits Room-generated unique index; add it for migration 13→14
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
            "ON events (original_event_id, original_instance_time)"
        )
        // Also create ics_subscriptions for migration 7→8 (needs it for boolean index)
        migrate(Migrations.MIGRATION_1_2)

        // Run all migrations sequentially
        migrate(Migrations.MIGRATION_2_3)
        // 3→4 is AutoMigration (skip)
        migrate(Migrations.MIGRATION_4_5)
        migrate(Migrations.MIGRATION_5_6)
        migrate(Migrations.MIGRATION_6_7)
        migrate(Migrations.MIGRATION_7_8)
        migrate(Migrations.MIGRATION_8_9)
        migrate(Migrations.MIGRATION_9_10)
        migrate(Migrations.MIGRATION_10_11)
        migrate(Migrations.MIGRATION_11_12)
        migrate(Migrations.MIGRATION_12_13)
        migrate(Migrations.MIGRATION_13_14)
        migrate(Migrations.MIGRATION_14_15)
        migrate(Migrations.MIGRATION_15_16)

        // Verify final schema has all expected tables
        assertTrue(tableExists("accounts"))
        assertTrue(tableExists("calendars"))
        assertTrue(tableExists("events"))
        assertTrue(tableExists("occurrences"))
        assertTrue(tableExists("pending_operations"))
        assertTrue(tableExists("sync_logs"))
        assertTrue(tableExists("ics_subscriptions"))
        assertTrue(tableExists("scheduled_reminders"))

        // Verify key columns added by later migrations exist
        assertTrue(columnExists("events", "raw_ical"))
        assertTrue(columnExists("events", "import_id"))
        assertTrue(columnExists("events", "priority"))
        assertTrue(columnExists("events", "categories"))
        assertTrue(columnExists("pending_operations", "target_url"))
        assertTrue(columnExists("pending_operations", "move_phase"))
        assertTrue(columnExists("pending_operations", "source_calendar_id"))
        assertTrue(columnExists("pending_operations", "lifetime_reset_at"))
        assertTrue(columnExists("pending_operations", "failed_at"))

        // Verify migration 13→14 index swap
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))

        // Verify migration 15→16 columns
        assertTrue(columnExists("calendars", "is_notification_muted"))
        assertTrue(columnExists("calendars", "local_color_override"))
        assertTrue(columnExists("calendars", "default_reminder"))
        assertTrue(columnExists("events", "end_timezone"))
    }

    @Test
    fun `full migration chain 1 to 17 executes without error`() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)

        // Verify the scheduling schema landed on top of full chain
        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(columnExists("events", "organizer_sent_by"))
        assertTrue(columnExists("events", "organizer_schedule_status"))
        assertTrue(tableExists("attendees"))
        assertTrue(indexExists("index_attendees_event_id"))
        assertTrue(indexExists("index_attendees_address"))
    }

    // ==================== Migration 17 to 18: RSVP/notification dedup state ====================

    /** Runs the v1→v17 chain for the v18 tests. */
    private fun migrateUpToV17() {
        migrateUpToV16()
        migrate(Migrations.MIGRATION_16_17)
    }

    @Test
    fun `migration 17 to 18 adds partstat_only to pending_operations with default 0`() {
        migrateUpToV17()

        migrate(Migrations.MIGRATION_17_18)

        assertTrue(columnExists("pending_operations", "partstat_only"))

        // Default fires when the column is omitted on insert.
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (1, 'UPDATE', 0, 0)")
        db.queryRows("SELECT partstat_only FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 17 to 18 adds partstat_target to pending_operations as nullable TEXT`() {
        migrateUpToV17()

        migrate(Migrations.MIGRATION_17_18)

        assertTrue(columnExists("pending_operations", "partstat_target"))

        // Default is NULL.
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (2, 'UPDATE', 0, 0)")
        db.queryRows("SELECT partstat_target FROM pending_operations WHERE event_id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 17 to 18 adds notified_at to attendees as nullable INTEGER`() {
        migrateUpToV17()

        migrate(Migrations.MIGRATION_17_18)

        assertTrue(columnExists("attendees", "notified_at"))

        // Set up FKs and insert a row that omits notified_at.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'uid', 1, 'T', 1000, 2000, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (event_id, address) VALUES (1, 'mailto:a@example.com')")
        db.queryRows("SELECT notified_at FROM attendees WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 17 to 18 preserves existing pending_operations and attendees rows`() {
        migrateUpToV17()

        // Pre-existing pending_operations row.
        db.execSQL(
            "INSERT INTO pending_operations (id, event_id, operation, created_at, updated_at) " +
                "VALUES (10, 99, 'UPDATE', 12345, 12345)"
        )
        // Pre-existing attendee (with full FK chain).
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'p@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://p/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (99, 'preserve-uid', 1, 'Preserved', 1000, 2000, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (id, event_id, address) VALUES (50, 99, 'mailto:keep@example.com')")

        migrate(Migrations.MIGRATION_17_18)

        // pending_operations row survives, new columns at defaults.
        db.queryRows(
            "SELECT operation, partstat_only, partstat_target FROM pending_operations WHERE id = 10"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("UPDATE", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
        // attendees row survives, new column at default NULL.
        db.queryRows(
            "SELECT address, notified_at FROM attendees WHERE id = 50"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("mailto:keep@example.com", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun `migration 17 to 18 is idempotent`() {
        migrateUpToV17()

        migrate(Migrations.MIGRATION_17_18)
        migrate(Migrations.MIGRATION_17_18)

        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(columnExists("pending_operations", "partstat_target"))
        assertTrue(columnExists("attendees", "notified_at"))
    }

    @Test(expected = IllegalStateException::class)
    fun `migration 17 to 18 pre-migration shape check rejects wrong-type partstat_only`() {
        migrateUpToV17()

        // partstat_only hand-added as TEXT, as in a forked dev DB
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN partstat_only TEXT")

        // The migration must throw IllegalStateException, not silently keep a mis-typed column
        migrate(Migrations.MIGRATION_17_18)
    }

    @Test
    fun `migration 17 to 18 post-migration validation block enumerates each missing column`() {
        // The migration's validation block collects missing columns with `buildList` and throws
        // IllegalStateException listing them. A partial ADD COLUMN can't be forced here (SQLite
        // ALTER TABLE ADD COLUMN completes or rolls back), so the block's throw isn't exercised.
        // The test checks that none of the three columns exist at v17 and all three exist after
        // the migration.
        migrateUpToV17()

        // Sanity: at v17 none of the new columns exist yet.
        assertFalse(columnExists("pending_operations", "partstat_only"))
        assertFalse(columnExists("pending_operations", "partstat_target"))
        assertFalse(columnExists("attendees", "notified_at"))

        // The migration must succeed
        migrate(Migrations.MIGRATION_17_18)

        // All three columns now exist
        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(columnExists("pending_operations", "partstat_target"))
        assertTrue(columnExists("attendees", "notified_at"))
    }

    // ==================== Full migration chain 1 to 18 ====================

    @Test
    fun `full migration chain 1 to 18 executes without error`() {
        migrateUpToV17()
        migrate(Migrations.MIGRATION_17_18)

        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(columnExists("pending_operations", "partstat_target"))
        assertTrue(columnExists("attendees", "notified_at"))

        // Spot-check that earlier-migration columns still exist after chained run.
        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(tableExists("attendees"))
        assertTrue(indexExists("index_attendees_event_id"))
    }

    // ==================== Migration 18 to 19: scheduling capability + outbox ====================

    /** Runs the v1→v18 chain for the v19 tests. */
    private fun migrateUpToV18() {
        migrateUpToV17()
        migrate(Migrations.MIGRATION_17_18)
    }

    @Test
    fun `migration 18 to 19 adds schedule_outbox_url to accounts as nullable TEXT`() {
        migrateUpToV18()

        migrate(Migrations.MIGRATION_18_19)

        assertTrue(columnExists("accounts", "schedule_outbox_url"))

        // Default is NULL when the column is omitted on insert.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.queryRows("SELECT schedule_outbox_url FROM accounts WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 18 to 19 adds auto_schedule_supported to calendars as nullable INTEGER`() {
        migrateUpToV18()

        migrate(Migrations.MIGRATION_18_19)

        assertTrue(columnExists("calendars", "auto_schedule_supported"))

        // Default is NULL (tri-state unknown) when omitted on insert.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.queryRows("SELECT auto_schedule_supported FROM calendars WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 18 to 19 preserves existing account and calendar rows`() {
        migrateUpToV18()

        db.execSQL(
            "INSERT INTO accounts (id, provider, email, created_at, calendar_user_addresses) " +
                "VALUES (7, 'CALDAV', 'keep@test.com', 999, '[\"mailto:keep@test.com\"]')"
        )
        db.execSQL(
            "INSERT INTO calendars (id, account_id, caldav_url, display_name, color) " +
                "VALUES (3, 7, 'https://keep/', 'Keep', 42)"
        )

        migrate(Migrations.MIGRATION_18_19)

        // Account row survives, prior columns intact, new column NULL.
        db.queryRows(
            "SELECT email, calendar_user_addresses, schedule_outbox_url FROM accounts WHERE id = 7"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("keep@test.com", cursor.getString(0))
            assertEquals("[\"mailto:keep@test.com\"]", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
        // Calendar row survives, prior columns intact, new column NULL.
        db.queryRows(
            "SELECT display_name, color, auto_schedule_supported FROM calendars WHERE id = 3"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep", cursor.getString(0))
            assertEquals(42, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun `migration 18 to 19 round-trips tri-state capability values`() {
        migrateUpToV18()
        migrate(Migrations.MIGRATION_18_19)

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL(
            "INSERT INTO calendars (id, account_id, caldav_url, display_name, color, auto_schedule_supported) " +
                "VALUES (1, 1, 'https://yes/', 'Yes', 0, 1), " +
                "(2, 1, 'https://no/', 'No', 0, 0), " +
                "(3, 1, 'https://unknown/', 'Unknown', 0, NULL)"
        )
        db.queryRows("SELECT id, auto_schedule_supported FROM calendars ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(1)) // true
            assertTrue(cursor.moveToNext())
            assertEquals(0, cursor.getInt(1)) // false
            assertTrue(cursor.moveToNext())
            assertTrue(cursor.isNull(1)) // unknown
        }
    }

    @Test
    fun `migration 18 to 19 is idempotent`() {
        migrateUpToV18()

        migrate(Migrations.MIGRATION_18_19)
        migrate(Migrations.MIGRATION_18_19)

        assertTrue(columnExists("accounts", "schedule_outbox_url"))
        assertTrue(columnExists("calendars", "auto_schedule_supported"))
    }

    // ==================== Full migration chain 1 to 19 ====================

    @Test
    fun `full migration chain 1 to 19 executes without error`() {
        migrateUpToV18()
        migrate(Migrations.MIGRATION_18_19)

        assertTrue(columnExists("accounts", "schedule_outbox_url"))
        assertTrue(columnExists("calendars", "auto_schedule_supported"))

        // Spot-check that earlier-migration columns still exist after chained run.
        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(tableExists("attendees"))
    }

    // ==================== Migrations 19 to 23: iTIP, pending_cancels, address_books ==============

    /** Runs the v1→v19 chain for the v20 tests. */
    private fun migrateUpToV19() {
        migrateUpToV18()
        migrate(Migrations.MIGRATION_18_19)
    }

    private fun migrateUpToV20() {
        migrateUpToV19()
        migrate(Migrations.MIGRATION_19_20)
    }

    private fun migrateUpToV21() {
        migrateUpToV20()
        migrate(Migrations.MIGRATION_20_21)
    }

    private fun migrateUpToV22() {
        migrateUpToV21()
        migrate(Migrations.MIGRATION_21_22)
    }

    @Test
    fun `migration 22 to 23 creates address_books table with expected columns`() {
        migrateUpToV22()

        migrate(Migrations.MIGRATION_22_23)

        assertTrue(tableExists("address_books"))
        assertTrue(columnExists("address_books", "id"))
        assertTrue(columnExists("address_books", "account_id"))
        assertTrue(columnExists("address_books", "url"))
        assertTrue(columnExists("address_books", "display_name"))
        assertTrue(columnExists("address_books", "description"))
        assertTrue(columnExists("address_books", "vcard_version"))
        assertTrue(columnExists("address_books", "ctag"))
        assertTrue(columnExists("address_books", "sync_token"))
        assertTrue(columnExists("address_books", "is_read_only"))
        assertTrue(columnExists("address_books", "is_sync_enabled"))
    }

    @Test
    fun `migration 22 to 23 address_books row round-trips`() {
        migrateUpToV22()
        migrate(Migrations.MIGRATION_22_23)

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'a@example.test', 0)")
        db.execSQL(
            "INSERT INTO address_books (account_id, url, display_name, vcard_version, is_read_only, is_sync_enabled) " +
                "VALUES (1, 'https://contacts.example.test/books/home/', 'Home', '3.0', 1, 1)"
        )
        db.queryRows(
            "SELECT url, display_name, vcard_version FROM address_books WHERE account_id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("https://contacts.example.test/books/home/", cursor.getString(0))
            assertEquals("Home", cursor.getString(1))
            assertEquals("3.0", cursor.getString(2))
        }
    }

    @Test
    fun `migration 22 to 23 enforces per-account url uniqueness`() {
        migrateUpToV22()
        migrate(Migrations.MIGRATION_22_23)

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'a@example.test', 0)")
        db.execSQL(
            "INSERT INTO address_books (account_id, url, display_name, vcard_version, is_read_only, is_sync_enabled) " +
                "VALUES (1, 'https://contacts.example.test/dup/', 'One', '3.0', 1, 1)"
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO address_books (account_id, url, display_name, vcard_version, is_read_only, is_sync_enabled) " +
                    "VALUES (1, 'https://contacts.example.test/dup/', 'Two', '3.0', 1, 1)"
            )
        } catch (e: SQLiteException) {
            threw = e.message.orEmpty().contains("UNIQUE constraint failed")
        }
        assertTrue("duplicate (account_id, url) must violate the unique index", threw)
    }

    @Test
    fun `migration 22 to 23 cascades address_books on account delete`() {
        migrateUpToV22()
        migrate(Migrations.MIGRATION_22_23)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (5, 'ICLOUD', 'a@example.test', 0)")
        db.execSQL(
            "INSERT INTO address_books (account_id, url, display_name, vcard_version, is_read_only, is_sync_enabled) " +
                "VALUES (5, 'https://contacts.example.test/books/x/', 'X', '3.0', 1, 1)"
        )
        db.execSQL("DELETE FROM accounts WHERE id = 5")

        db.queryRows("SELECT COUNT(*) FROM address_books WHERE account_id = 5").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 22 to 23 is idempotent`() {
        migrateUpToV22()

        migrate(Migrations.MIGRATION_22_23)
        migrate(Migrations.MIGRATION_22_23)

        assertTrue(tableExists("address_books"))
    }

    @Test
    fun `migration 20 to 21 creates pending_cancels table`() {
        migrateUpToV20()

        migrate(Migrations.MIGRATION_20_21)

        assertTrue(tableExists("pending_cancels"))
        assertTrue(columnExists("pending_cancels", "event_id"))
        assertTrue(columnExists("pending_cancels", "recurrence_id"))
        assertTrue(columnExists("pending_cancels", "address"))
        assertTrue(columnExists("pending_cancels", "schedule_agent"))
        assertTrue(columnExists("pending_cancels", "schedule_status"))
        assertTrue(columnExists("pending_cancels", "sequence"))
        assertTrue(columnExists("pending_cancels", "attempt_count"))
    }

    @Test
    fun `migration 20 to 21 pending_cancels row round-trips`() {
        migrateUpToV20()
        migrate(Migrations.MIGRATION_20_21)

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'u', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO pending_cancels (event_id, recurrence_id, address, schedule_agent, schedule_status, sequence, attempt_count) " +
                "VALUES (1, NULL, 'mailto:gone@example.test', 'CLIENT', NULL, 3, 0)"
        )
        db.queryRows("SELECT address, sequence, attempt_count FROM pending_cancels WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("mailto:gone@example.test", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
    }

    @Test
    fun `migration 20 to 21 is idempotent`() {
        migrateUpToV20()

        migrate(Migrations.MIGRATION_20_21)
        migrate(Migrations.MIGRATION_20_21)

        assertTrue(tableExists("pending_cancels"))
    }

    @Test
    fun `migration 20 to 21 cascades pending_cancels on event delete`() {
        migrateUpToV20()
        migrate(Migrations.MIGRATION_20_21)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (7, 'u7', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO pending_cancels (event_id, recurrence_id, address, schedule_agent, schedule_status, sequence, attempt_count) " +
                "VALUES (7, NULL, 'mailto:gone@example.test', NULL, NULL, 0, 0)"
        )
        db.execSQL("DELETE FROM events WHERE id = 7")

        db.queryRows("SELECT COUNT(*) FROM pending_cancels WHERE event_id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 19 to 20 adds itip_request_sequence to attendees as nullable INTEGER`() {
        migrateUpToV19()

        migrate(Migrations.MIGRATION_19_20)

        assertTrue(columnExists("attendees", "itip_request_sequence"))

        // Default is NULL (never-sent) when the column is omitted on insert.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'u', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (id, event_id, address) VALUES (1, 1, 'mailto:g@example.test')")
        db.queryRows("SELECT itip_request_sequence FROM attendees WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 19 to 20 adds itip_request_status to attendees as nullable TEXT`() {
        migrateUpToV19()

        migrate(Migrations.MIGRATION_19_20)

        assertTrue(columnExists("attendees", "itip_request_status"))

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'u', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (id, event_id, address) VALUES (1, 1, 'mailto:g@example.test')")
        db.queryRows("SELECT itip_request_status FROM attendees WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 19 to 20 preserves existing attendee rows`() {
        migrateUpToV19()

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'p@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://p/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (99, 'preserve-uid', 1, 'Preserved', 1000, 2000, 'UTC', 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO attendees (id, event_id, address, partstat, schedule_agent) " +
                "VALUES (50, 99, 'mailto:keep@example.test', 'NEEDS-ACTION', 'CLIENT')"
        )

        migrate(Migrations.MIGRATION_19_20)

        // Row survives, prior columns intact, new columns NULL.
        db.queryRows(
            "SELECT address, partstat, schedule_agent, itip_request_sequence, itip_request_status " +
                "FROM attendees WHERE id = 50"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("mailto:keep@example.test", cursor.getString(0))
            assertEquals("NEEDS-ACTION", cursor.getString(1))
            assertEquals("CLIENT", cursor.getString(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
    }

    @Test
    fun `migration 19 to 20 is idempotent`() {
        migrateUpToV19()

        migrate(Migrations.MIGRATION_19_20)
        migrate(Migrations.MIGRATION_19_20)

        assertTrue(columnExists("attendees", "itip_request_sequence"))
        assertTrue(columnExists("attendees", "itip_request_status"))
    }

    // ==================== Migration 21 to 22 (categories) ====================

    /** Reads a category row's color, or null if the color is null or the row is absent. */
    private fun categoryColor(name: String): Int? {
        db.queryRows("SELECT color FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getInt(0)
        }
    }

    private fun categoryCount(): Int {
        db.queryRows("SELECT COUNT(*) FROM categories").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun categoryNames(): List<String> {
        val names = mutableListOf<String>()
        db.queryRows("SELECT name FROM categories ORDER BY name").use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0)!!)
        }
        return names
    }

    private fun categoryLastUsed(name: String): Long? {
        db.queryRows("SELECT last_used_at FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    @Test
    fun `migration 21 to 22 creates categories table with expected columns`() {
        migrateUpToV21()

        migrate(Migrations.MIGRATION_21_22)

        assertTrue(tableExists("categories"))
        assertTrue(columnExists("categories", "name"))
        assertTrue(columnExists("categories", "color"))
        assertTrue(columnExists("categories", "last_used_at"))
    }

    @Test
    fun `migration 21 to 22 name primary key is COLLATE NOCASE`() {
        migrateUpToV21()
        migrate(Migrations.MIGRATION_21_22)

        // "Chores", then "chores": a NOCASE PK collapses them to one row
        db.execSQL("INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES ('Chores', 100, 1)")
        db.execSQL("INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES ('chores', 200, 2)")

        val choresRows = categoryNames().filter { it.equals("chores", ignoreCase = true) }
        assertEquals("Chores and chores must collapse to one row", 1, choresRows.size)
    }

    @Test
    fun `migration 21 to 22 seeds Work Personal Family with non-null colors`() {
        migrateUpToV21()

        migrate(Migrations.MIGRATION_21_22)

        assertTrue(categoryNames().containsAll(listOf("Work", "Personal", "Family")))
        assertNotNull("seeded defaults carry curated colors", categoryColor("Work"))
        assertNotNull(categoryColor("Personal"))
        assertNotNull(categoryColor("Family"))
    }

    @Test
    fun `migration 21 to 22 backfills existing event tags with null color`() {
        migrateUpToV21()
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories) " +
                "VALUES (1, 'u1', 1, 'E', 500, 600, 'UTC', 0, 0, 0, '[\"Gym\"]')"
        )

        migrate(Migrations.MIGRATION_21_22)

        assertTrue(categoryNames().contains("Gym"))
        assertNull("backfilled tags render via hash color, so color is NULL", categoryColor("Gym"))
    }

    @Test
    fun `migration 21 to 22 backfill dedups case-insensitively keeping first-seen casing and max recency`() {
        migrateUpToV21()
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        // Two events tag the same logical name with different casing + recency.
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories, local_modified_at) " +
                "VALUES (1, 'u1', 1, 'E1', 0, 0, 'UTC', 0, 0, 0, '[\"Travel\"]', 1000)"
        )
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories, local_modified_at) " +
                "VALUES (2, 'u2', 1, 'E2', 0, 0, 'UTC', 0, 0, 0, '[\"travel\"]', 5000)"
        )

        migrate(Migrations.MIGRATION_21_22)

        val travelRows = categoryNames().filter { it.equals("travel", ignoreCase = true) }
        assertEquals("one row for the deduped name", 1, travelRows.size)
        assertEquals("first-seen casing wins", "Travel", travelRows[0])
        assertEquals("recency is the max across the name's events", 5000L, categoryLastUsed("Travel"))
    }

    @Test
    fun `migration 21 to 22 seed wins over backfill on case-insensitive collision`() {
        migrateUpToV21()
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        // An event pre-tagged "work" (lowercase) must not overwrite the seeded
        // "Work" row's curated color with null.
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories) " +
                "VALUES (1, 'u1', 1, 'E', 0, 0, 'UTC', 0, 0, 0, '[\"work\"]')"
        )

        migrate(Migrations.MIGRATION_21_22)

        val workRows = categoryNames().filter { it.equals("work", ignoreCase = true) }
        assertEquals(1, workRows.size)
        assertNotNull("seeded Work color survives the collision", categoryColor(workRows[0]))
    }

    @Test
    fun `migration 21 to 22 skips a malformed categories blob without faulting`() {
        migrateUpToV21()
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        // A bad JSON blob on one event must not abort the migration; a good one
        // on another still backfills.
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories) " +
                "VALUES (1, 'u1', 1, 'Bad', 0, 0, 'UTC', 0, 0, 0, '{not valid json')"
        )
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories) " +
                "VALUES (2, 'u2', 1, 'Good', 0, 0, 'UTC', 0, 0, 0, '[\"Hobby\"]')"
        )

        migrate(Migrations.MIGRATION_21_22)

        assertTrue("the well-formed tag still backfills", categoryNames().contains("Hobby"))
    }

    @Test
    fun `migration 21 to 22 preserves existing event rows untouched`() {
        migrateUpToV21()
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories) " +
                "VALUES (7, 'keep', 1, 'Keep Me', 111, 222, 'UTC', 0, 0, 0, '[\"Work\",\"Gym\"]')"
        )

        migrate(Migrations.MIGRATION_21_22)

        db.queryRows("SELECT title, start_ts, categories FROM events WHERE id = 7").use { cursor ->
            assertTrue("the event row still exists", cursor.moveToFirst())
            assertEquals("Keep Me", cursor.getString(0))
            assertEquals(111L, cursor.getLong(1))
            assertEquals("its category JSON is untouched", "[\"Work\",\"Gym\"]", cursor.getString(2))
        }
    }

    @Test
    fun `migration 21 to 22 is idempotent`() {
        migrateUpToV21()

        migrate(Migrations.MIGRATION_21_22)
        val countAfterFirst = categoryCount()
        migrate(Migrations.MIGRATION_21_22)

        assertTrue(tableExists("categories"))
        assertEquals("re-running adds no duplicate rows", countAfterFirst, categoryCount())
    }

    // ==================== Full migration chain 1 to 20 ====================

    @Test
    fun `full migration chain 1 to 20 executes without error`() {
        migrateUpToV19()
        migrate(Migrations.MIGRATION_19_20)

        assertTrue(columnExists("attendees", "itip_request_sequence"))
        assertTrue(columnExists("attendees", "itip_request_status"))

        // Spot-check that earlier-migration columns still exist after chained run.
        assertTrue(columnExists("accounts", "schedule_outbox_url"))
        assertTrue(columnExists("attendees", "notified_at"))
        assertTrue(tableExists("attendees"))
    }

    // ==================== Migration 24 to 25 ====================

    private fun migrateUpToV24() {
        migrateUpToV22()
        migrate(Migrations.MIGRATION_22_23)
        migrate(Migrations.MIGRATION_23_24)
    }

    private fun intColumn(sql: String): Int =
        db.queryRows(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun seedV24Calendars() {
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@example.test', 0)")
        db.execSQL(
            "INSERT INTO calendars (id, account_id, caldav_url, display_name, color, sync_token, ctag) " +
                "VALUES (1, 1, 'https://x/token/', 'Token', 11, 'tok', NULL)"
        )
        db.execSQL(
            "INSERT INTO calendars (id, account_id, caldav_url, display_name, color, sync_token, ctag) " +
                "VALUES (2, 1, 'https://x/ctag/', 'Ctag', 22, NULL, 'ct')"
        )
        db.execSQL(
            "INSERT INTO calendars (id, account_id, caldav_url, display_name, color, sync_token, ctag) " +
                "VALUES (3, 1, 'https://x/never/', 'Never', 33, NULL, NULL)"
        )
    }

    private fun insertRecentChange(id: Long, calendarId: Long, uid: String, instanceTs: Long, eventId: Long?) {
        db.execSQL(
            "INSERT INTO recent_changes (id, calendar_id, event_uid, instance_ts, event_id, change_type, " +
                "title, start_ts, end_ts, is_all_day, is_recurring, detected_at) " +
                "VALUES ($id, $calendarId, '$uid', $instanceTs, ${eventId ?: "NULL"}, 'NEW', 'T', 0, 0, 0, 0, 0)"
        )
    }

    @Test
    fun `migration 24 to 25 creates recent_changes with every column and index`() {
        migrateUpToV24()

        migrate(Migrations.MIGRATION_24_25)

        assertTrue(tableExists("recent_changes"))
        for (col in listOf(
            "id", "calendar_id", "event_uid", "instance_ts", "event_id", "change_type", "title",
            "start_ts", "end_ts", "is_all_day", "is_recurring", "detected_at", "changed_fields",
            "previous_start_ts", "previous_is_all_day", "dismissed_at"
        )) {
            assertTrue("recent_changes.$col", columnExists("recent_changes", col))
        }
        for (index in listOf(
            "index_recent_changes_calendar_id",
            "index_recent_changes_event_id",
            "index_recent_changes_detected_at",
            "index_recent_changes_calendar_id_event_uid_instance_ts"
        )) {
            assertTrue(index, indexExists(index))
        }
        assertTrue(columnExists("calendars", "initial_pull_done"))
    }

    @Test
    fun `migration 24 to 25 marks only already-pulled calendars and keeps their other columns`() {
        migrateUpToV24()
        seedV24Calendars()

        migrate(Migrations.MIGRATION_24_25)

        db.queryRows("SELECT id, initial_pull_done, display_name, color, sync_token, ctag FROM calendars ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(1, c.getInt(1)); assertEquals("Token", c.getString(2)); assertEquals(11, c.getInt(3))
            assertEquals("tok", c.getString(4))
            assertTrue(c.moveToNext())
            assertEquals(1, c.getInt(1)); assertEquals("Ctag", c.getString(2)); assertEquals("ct", c.getString(5))
            assertTrue(c.moveToNext())
            assertEquals("a calendar never pulled stays 0", 0, c.getInt(1))
            assertEquals("Never", c.getString(2))
        }
    }

    @Test
    fun `migration 24 to 25 keeps existing events`() {
        migrateUpToV24()
        seedV24Calendars()
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (9, 'keep', 1, 'Kept', 100, 200, 'UTC', 0, 0, 0)"
        )

        migrate(Migrations.MIGRATION_24_25)

        db.queryRows("SELECT uid, title, start_ts, end_ts FROM events WHERE id = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("keep", c.getString(0)); assertEquals("Kept", c.getString(1))
            assertEquals(100L, c.getLong(2)); assertEquals(200L, c.getLong(3))
        }
    }

    @Test
    fun `migration 24 to 25 is idempotent`() {
        migrateUpToV24()
        seedV24Calendars()

        migrate(Migrations.MIGRATION_24_25)
        insertRecentChange(1, 1, "u", 0, null)
        migrate(Migrations.MIGRATION_24_25)

        assertTrue(tableExists("recent_changes"))
        assertEquals("a re-run keeps the rows of a well-formed table", 1, intColumn("SELECT COUNT(*) FROM recent_changes"))
        assertEquals(2, intColumn("SELECT COUNT(*) FROM calendars WHERE initial_pull_done = 1"))
    }

    @Test
    fun `migration 24 to 25 rebuilds a recent_changes table with a stale shape`() {
        migrateUpToV24()
        db.execSQL("CREATE TABLE recent_changes (id INTEGER PRIMARY KEY, calendar_id INTEGER, title TEXT)")
        db.execSQL("CREATE INDEX index_recent_changes_calendar_id ON recent_changes (calendar_id)")

        migrate(Migrations.MIGRATION_24_25)

        assertTrue(columnExists("recent_changes", "event_uid"))
        assertTrue(columnExists("recent_changes", "dismissed_at"))
        assertTrue(indexExists("index_recent_changes_calendar_id_event_uid_instance_ts"))
    }

    @Test
    fun `migration 24 to 25 skips an existing flag column and still backfills`() {
        migrateUpToV24()
        seedV24Calendars()
        db.execSQL("ALTER TABLE calendars ADD COLUMN initial_pull_done INTEGER NOT NULL DEFAULT 0")

        migrate(Migrations.MIGRATION_24_25)

        assertEquals(2, intColumn("SELECT COUNT(*) FROM calendars WHERE initial_pull_done = 1"))
    }

    @Test
    fun `migration 24 to 25 refuses a mistyped flag column and leaves the v24 schema`() {
        migrateUpToV24()
        db.execSQL("ALTER TABLE calendars ADD COLUMN initial_pull_done TEXT")

        var threw = false
        try {
            migrate(Migrations.MIGRATION_24_25)
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue("a TEXT initial_pull_done must fail the shape check", threw)
        // The shape check throws before any table is created.
        assertFalse("no recent_changes table is created", tableExists("recent_changes"))
    }

    @Test
    fun `migration 24 to 25 cascades on calendar delete and nulls event_id on event delete`() {
        migrateUpToV24()
        seedV24Calendars()
        migrate(Migrations.MIGRATION_24_25)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (7, 'u7', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        insertRecentChange(1, 1, "u7", 0, 7)
        insertRecentChange(2, 2, "u8", 0, null)

        db.execSQL("DELETE FROM events WHERE id = 7")
        db.queryRows("SELECT event_id FROM recent_changes WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("deleting the event keeps the row with event_id null", c.isNull(0))
        }

        db.execSQL("DELETE FROM calendars WHERE id = 2")
        assertEquals(0, intColumn("SELECT COUNT(*) FROM recent_changes WHERE id = 2"))
        assertEquals(1, intColumn("SELECT COUNT(*) FROM recent_changes WHERE id = 1"))
    }

    @Test
    fun `migration 24 to 25 enforces one row per calendar, uid and instance`() {
        migrateUpToV24()
        seedV24Calendars()
        migrate(Migrations.MIGRATION_24_25)
        insertRecentChange(1, 1, "same", 0, null)
        insertRecentChange(2, 2, "same", 0, null) // same UID in another calendar is allowed
        insertRecentChange(3, 1, "same", 1000, null) // a changed occurrence of it is allowed

        var threw = false
        try {
            insertRecentChange(4, 1, "same", 0, null)
        } catch (e: SQLiteException) {
            threw = e.message.orEmpty().contains("UNIQUE constraint failed")
        }
        assertTrue("a duplicate (calendar_id, event_uid, instance_ts) must be refused", threw)
    }

    @Test
    fun `full migration chain 1 to 25 executes without error`() {
        migrateUpToV24()

        migrate(Migrations.MIGRATION_24_25)

        assertTrue(tableExists("recent_changes"))
        assertTrue(tableExists("address_books"))
        assertTrue(columnExists("accounts", "contact_sync_enabled"))
    }

    // ==================== Migration 25 -> 26 ====================

    private fun migrateUpToV25() {
        migrateUpToV24()
        migrate(Migrations.MIGRATION_24_25)
    }

    @Test
    fun `migration 25 to 26 adds the import columns, null on existing events`() {
        migrateUpToV25()
        seedV24Calendars()
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (9, 'keep', 1, 'Kept', 100, 200, 'UTC', 55, 0, 0)"
        )

        migrate(Migrations.MIGRATION_25_26)

        assertTrue(columnExists("events", "import_source"))
        assertTrue(columnExists("events", "itip_dtstamp"))
        db.queryRows("SELECT uid, title, start_ts, end_ts, dtstamp, import_source, itip_dtstamp FROM events WHERE id = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("keep", c.getString(0)); assertEquals("Kept", c.getString(1))
            assertEquals(100L, c.getLong(2)); assertEquals(200L, c.getLong(3)); assertEquals(55L, c.getLong(4))
            assertTrue("an event from before v26 is not marked imported", c.isNull(5))
            assertTrue(c.isNull(6))
        }
    }

    @Test
    fun `migration 25 to 26 is idempotent`() {
        migrateUpToV25()
        seedV24Calendars()

        migrate(Migrations.MIGRATION_25_26)
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, " +
                "import_source, itip_dtstamp) VALUES (5, 'imp', 1, 'I', 0, 0, 'UTC', 0, 0, 0, 'ics_file', 77)"
        )
        migrate(Migrations.MIGRATION_25_26)

        db.queryRows("SELECT import_source, itip_dtstamp FROM events WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a re-run keeps the values", "ics_file", c.getString(0)); assertEquals(77L, c.getLong(1))
        }
    }

    @Test
    fun `migration 25 to 26 refuses a mistyped column and leaves the v25 schema`() {
        migrateUpToV25()
        db.execSQL("ALTER TABLE events ADD COLUMN itip_dtstamp TEXT")

        var threw = false
        try {
            migrate(Migrations.MIGRATION_25_26)
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue("a TEXT itip_dtstamp must fail the shape check", threw)
        assertFalse("the shape check throws before any column is added", columnExists("events", "import_source"))
    }

    @Test
    fun `full migration chain 1 to 26 executes without error`() {
        migrateUpToV25()

        migrate(Migrations.MIGRATION_25_26)

        assertTrue(tableExists("recent_changes"))
        assertTrue(columnExists("events", "import_source"))
    }

    // ==================== Migration 26 -> 27 ====================

    private fun migrateUpToV26() {
        migrateUpToV25()
        migrate(Migrations.MIGRATION_25_26)
    }

    private fun insertEvent(id: Long, calendarId: Long = 1) {
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES ($id, 'uid-$id', $calendarId, 'Title $id', 100, 200, 'UTC', 55, 0, 0)"
        )
    }

    private fun insertOp(
        id: Long,
        eventId: Long,
        operation: String,
        status: String = "PENDING",
        targetUrl: String? = null,
        sourceCalendarId: Long? = null,
        linkedMoveId: String? = null,
        partstatOnly: Boolean = false,
        lastError: String? = null,
    ) {
        db.execSQL(
            "INSERT INTO pending_operations (id, event_id, operation, status, target_url, source_calendar_id, " +
                "linked_move_id, partstat_only, last_error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, eventId, operation, status, targetUrl, sourceCalendarId, linkedMoveId, if (partstatOnly) 1 else 0, lastError, id, id)
        )
    }

    private fun opIds(): Set<Long> =
        db.queryRows("SELECT id FROM pending_operations").use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    /** Every column of every row of [table], as text, for an exact before/after comparison. */
    private fun rows(table: String): List<List<String?>> =
        db.queryRows("SELECT * FROM $table ORDER BY rowid").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
        }

    /** Seeds orphans of every shape, and one operation of each type and status on live events. */
    private fun seedV26Queue(): Pair<Set<Long>, Set<Long>> {
        seedV24Calendars()
        insertEvent(1); insertEvent(2); insertEvent(3)
        val live = mutableSetOf<Long>()
        var id = 100L
        for (op in listOf("CREATE", "UPDATE", "DELETE", "MOVE", "ATTENDEE_CANCEL")) {
            for (status in listOf("PENDING", "IN_PROGRESS", "FAILED", "ABANDONED")) {
                insertOp(id, eventId = 1, operation = op, status = status, targetUrl = "https://x/1.ics", sourceCalendarId = 1)
                live += id++
            }
        }
        insertOp(id, eventId = 2, operation = "UPDATE", partstatOnly = true, targetUrl = "https://x/2.ics"); live += id++
        insertOp(id, eventId = 3, operation = "CREATE", linkedMoveId = "live-pair"); live += id++
        insertOp(id, eventId = 3, operation = "DELETE", targetUrl = "https://x/3.ics", sourceCalendarId = 1, linkedMoveId = "live-pair"); live += id++

        val removed = mutableSetOf<Long>()
        // Orphans that need their row, in every status.
        for ((i, op) in listOf("CREATE", "UPDATE", "MOVE", "ATTENDEE_CANCEL").withIndex()) {
            for (status in listOf("PENDING", "IN_PROGRESS", "FAILED", "ABANDONED")) {
                insertOp(id, eventId = 900L + i, operation = op, status = status, targetUrl = "https://x/9$i.ics", sourceCalendarId = 1)
                removed += id++
            }
        }
        insertOp(id, eventId = 910, operation = "UPDATE", partstatOnly = true, targetUrl = "https://x/910.ics"); removed += id++
        insertOp(id, eventId = 911, operation = "DELETE"); removed += id++
        // A DELETE folded into an RSVP: a URL but no source calendar, so the push can't run it.
        insertOp(id, eventId = 912, operation = "DELETE", targetUrl = "https://x/912.ics"); removed += id++
        // A cross-account pair whose new row is gone: both halves go.
        insertOp(id, eventId = 913, operation = "CREATE", linkedMoveId = "orphan-pair"); removed += id++
        insertOp(id, eventId = 913, operation = "DELETE", targetUrl = "https://x/913.ics", sourceCalendarId = 1, linkedMoveId = "orphan-pair"); removed += id++
        // A self-contained move DELETE whose row is gone on purpose: it stays.
        insertOp(id, eventId = 914, operation = "DELETE", status = "ABANDONED", targetUrl = "https://x/914.ics", sourceCalendarId = 1)
        live += id++
        return live to removed
    }

    @Test
    fun `migration 26 to 27 removes orphans that need their row and keeps everything else`() {
        migrateUpToV26()
        val (kept, removed) = seedV26Queue()
        val keptRowsBefore = rows("pending_operations").filter { it[0]!!.toLong() in kept }

        migrate(Migrations.MIGRATION_26_27)

        assertEquals(kept, opIds())
        assertEquals(keptRowsBefore, rows("pending_operations"))
        assertTrue(removed.none { it in opIds() })
    }

    @Test
    fun `migration 26 to 27 leaves events, calendars and accounts unchanged`() {
        migrateUpToV26()
        seedV26Queue()
        val before = listOf("events", "calendars", "accounts", "attendees", "occurrences").map { rows(it) }

        migrate(Migrations.MIGRATION_26_27)

        assertEquals(before, listOf("events", "calendars", "accounts", "attendees", "occurrences").map { rows(it) })
    }

    @Test
    fun `migration 26 to 27 installs the trigger and later deletes drop their operations`() {
        migrateUpToV26()
        seedV26Queue()

        migrate(Migrations.MIGRATION_26_27)
        assertTrue(triggerExists(PendingOperationTriggers.EVENT_DELETE_TRIGGER))
        db.execSQL("DELETE FROM events WHERE id = 2")

        assertTrue(db.queryRows("SELECT 1 FROM pending_operations WHERE event_id = 2").use { !it.moveToFirst() })
    }

    @Test
    fun `migration 26 to 27 is idempotent`() {
        migrateUpToV26()
        val (kept, _) = seedV26Queue()

        migrate(Migrations.MIGRATION_26_27)
        val afterFirst = rows("pending_operations")
        migrate(Migrations.MIGRATION_26_27)

        assertEquals(afterFirst, rows("pending_operations"))
        assertEquals(kept, opIds())
        assertTrue(triggerExists(PendingOperationTriggers.EVENT_DELETE_TRIGGER))
    }

    @Test
    fun `migration 26 to 27 handles a large queue with long errors`() {
        migrateUpToV26()
        seedV24Calendars()
        insertEvent(1)
        val longError = "x".repeat(4_096)
        for (i in 1L..5_000L) {
            insertOp(i, eventId = if (i % 2 == 0L) 1 else 10_000 + i, operation = "UPDATE", status = "ABANDONED", lastError = longError)
        }

        migrate(Migrations.MIGRATION_26_27)

        assertEquals(2_500L, db.queryRows("SELECT COUNT(*) FROM pending_operations").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test
    fun `migration 26 to 27 refuses a queue missing a column the trigger reads and changes nothing`() {
        migrateUpToV26()
        seedV24Calendars()
        insertEvent(1)
        db.execSQL("DROP TABLE pending_operations")
        db.execSQL(
            "CREATE TABLE pending_operations (id INTEGER PRIMARY KEY NOT NULL, event_id INTEGER NOT NULL, " +
                "operation TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL("INSERT INTO pending_operations VALUES (1, 99, 'UPDATE', 0, 0)")

        var threw = false
        try {
            migrate(Migrations.MIGRATION_26_27)
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(setOf(1L), opIds())
        assertFalse(triggerExists(PendingOperationTriggers.EVENT_DELETE_TRIGGER))
    }

    @Test
    fun `full migration chain 1 to 27 executes without error`() {
        migrateUpToV26()

        migrate(Migrations.MIGRATION_26_27)

        assertTrue(triggerExists(PendingOperationTriggers.EVENT_DELETE_TRIGGER))
    }

    // ==================== Migration 27 to 28 (search index dropped) ====================

    private fun migrateUpToV27() {
        migrateUpToV26()
        migrate(Migrations.MIGRATION_26_27)
    }

    /** Creates the v27 FTS4 search index and Room's four sync triggers, as `27.json` lists them. */
    private fun createV27SearchIndex() {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `events_fts` USING FTS4(`title` TEXT NOT NULL, `location` TEXT, " +
                "`description` TEXT, content=`events`)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_BEFORE_UPDATE BEFORE UPDATE ON `events` " +
                "BEGIN DELETE FROM `events_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_BEFORE_DELETE BEFORE DELETE ON `events` " +
                "BEGIN DELETE FROM `events_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_AFTER_UPDATE AFTER UPDATE ON `events` " +
                "BEGIN INSERT INTO `events_fts`(`docid`, `title`, `location`, `description`) " +
                "VALUES (NEW.`rowid`, NEW.`title`, NEW.`location`, NEW.`description`); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_AFTER_INSERT AFTER INSERT ON `events` " +
                "BEGIN INSERT INTO `events_fts`(`docid`, `title`, `location`, `description`) " +
                "VALUES (NEW.`rowid`, NEW.`title`, NEW.`location`, NEW.`description`); END"
        )
    }

    private fun searchIndexObjects(): Long =
        db.queryRows("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%events_fts%' OR sql LIKE '%events_fts%'")
            .use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun `migration 27 to 28 drops the search index and its triggers and keeps every event`() {
        migrateUpToV27()
        createV27SearchIndex()
        seedV24Calendars()
        insertEvent(1); insertEvent(2)
        val before = listOf("events", "calendars", "accounts", "attendees", "occurrences").map { rows(it) }
        assertTrue(searchIndexObjects() > 0)

        migrate(Migrations.MIGRATION_27_28)

        assertEquals(0L, searchIndexObjects())
        assertEquals(before, listOf("events", "calendars", "accounts", "attendees", "occurrences").map { rows(it) })
        assertTrue(triggerExists(PendingOperationTriggers.EVENT_DELETE_TRIGGER))
    }

    @Test
    fun `migration 27 to 28 leaves events writable`() {
        migrateUpToV27()
        createV27SearchIndex()
        seedV24Calendars()
        insertEvent(1)

        migrate(Migrations.MIGRATION_27_28)

        insertEvent(2)
        db.execSQL("UPDATE events SET title = 'Renamed' WHERE id = 1")
        db.execSQL("DELETE FROM events WHERE id = 2")
        val titles = db.queryRows("SELECT title FROM events").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("Renamed"), titles)
    }

    @Test
    fun `migration 27 to 28 accepts a database without the search index and can run again`() {
        migrateUpToV27()
        seedV24Calendars()
        insertEvent(1)
        val before = rows("events")

        migrate(Migrations.MIGRATION_27_28)
        migrate(Migrations.MIGRATION_27_28)

        assertEquals(0L, searchIndexObjects())
        assertEquals(before, rows("events"))
    }

    @Test
    fun `migration 27 to 28 drops any other trigger that names the search index`() {
        migrateUpToV27()
        createV27SearchIndex()
        db.execSQL(
            "CREATE TRIGGER legacy_events_search_sync AFTER INSERT ON `events` " +
                "BEGIN INSERT INTO `events_fts`(`docid`, `title`) VALUES (NEW.`rowid`, NEW.`title`); END"
        )
        seedV24Calendars()

        migrate(Migrations.MIGRATION_27_28)

        assertFalse(triggerExists("legacy_events_search_sync"))
        assertEquals(0L, searchIndexObjects())
        insertEvent(1)
    }

    // ==================== Migration Chain/Registry Tests ====================

    @Test
    fun `all migrations array contains expected migrations`() {
        assertEquals(27, Migrations.ALL_MIGRATIONS.size)
    }

    @Test
    fun `migrations are in correct order`() {
        val migrations = Migrations.ALL_MIGRATIONS.toList()

        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)
        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)
        assertEquals(4, migrations[2].startVersion)
        assertEquals(5, migrations[2].endVersion)
        assertEquals(5, migrations[3].startVersion)
        assertEquals(6, migrations[3].endVersion)
        assertEquals(6, migrations[4].startVersion)
        assertEquals(7, migrations[4].endVersion)
        assertEquals(7, migrations[5].startVersion)
        assertEquals(8, migrations[5].endVersion)
        assertEquals(8, migrations[6].startVersion)
        assertEquals(9, migrations[6].endVersion)
        assertEquals(9, migrations[7].startVersion)
        assertEquals(10, migrations[7].endVersion)
        assertEquals(10, migrations[8].startVersion)
        assertEquals(11, migrations[8].endVersion)
        assertEquals(11, migrations[9].startVersion)
        assertEquals(12, migrations[9].endVersion)
        assertEquals(12, migrations[10].startVersion)
        assertEquals(13, migrations[10].endVersion)
        assertEquals(13, migrations[11].startVersion)
        assertEquals(14, migrations[11].endVersion)
        assertEquals(14, migrations[12].startVersion)
        assertEquals(15, migrations[12].endVersion)
        assertEquals(15, migrations[13].startVersion)
        assertEquals(16, migrations[13].endVersion)
        assertEquals(16, migrations[14].startVersion)
        assertEquals(17, migrations[14].endVersion)
        assertEquals(17, migrations[15].startVersion)
        assertEquals(18, migrations[15].endVersion)
        assertEquals(18, migrations[16].startVersion)
        assertEquals(19, migrations[16].endVersion)
        assertEquals(19, migrations[17].startVersion)
        assertEquals(20, migrations[17].endVersion)
        assertEquals(20, migrations[18].startVersion)
        assertEquals(21, migrations[18].endVersion)
        assertEquals(21, migrations[19].startVersion)
        assertEquals(22, migrations[19].endVersion)
        assertEquals(22, migrations[20].startVersion)
        assertEquals(23, migrations[20].endVersion)
        assertEquals(23, migrations[21].startVersion)
        assertEquals(24, migrations[21].endVersion)
        assertEquals(24, migrations[22].startVersion)
        assertEquals(25, migrations[22].endVersion)
        assertEquals(25, migrations[23].startVersion)
        assertEquals(26, migrations[23].endVersion)
        assertEquals(26, migrations[24].startVersion)
        assertEquals(27, migrations[24].endVersion)
        assertEquals(27, migrations[25].startVersion)
        assertEquals(28, migrations[25].endVersion)
        assertEquals(28, migrations[26].startVersion)
        assertEquals(29, migrations[26].endVersion)
    }

    @Test
    fun `migration versions form valid chain with gaps`() {
        val migrations = Migrations.ALL_MIGRATIONS.toList()
        // Manual: 1→2, 2→3, then 4→5 through 28→29 (3→4 is the AutoMigration)
        assertTrue(migrations[0].endVersion == migrations[1].startVersion) // 2
        assertTrue(migrations[2].startVersion == 4) // gap at 3→4
        for (i in 2 until migrations.size - 1) {
            assertTrue(migrations[i].endVersion == migrations[i + 1].startVersion)
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
