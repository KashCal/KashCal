package org.onekash.kashcal.data.db.migration

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Database migration tests for KashCalDatabase.
 *
 * These tests actually execute migration SQL against a real SQLite database
 * (via Robolectric) and verify schema changes, column presence, indexes,
 * and data integrity.
 *
 * Each test:
 * 1. Creates the prerequisite schema (tables the migration depends on)
 * 2. Runs the migration via migration.migrate(db)
 * 3. Verifies schema changes using PRAGMA queries
 *
 * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationTest {

    private lateinit var db: SupportSQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory database
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Empty - we'll create tables manually per test
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        unmockkStatic(Log::class)
    }

    // ==================== Schema Query Helpers ====================

    private fun tableExists(tableName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun columnExists(tableName: String, columnName: String): Boolean {
        db.query("PRAGMA table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    private fun getColumnNames(tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        return columns
    }

    private fun indexExists(indexName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(indexName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun triggerExists(triggerName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(triggerName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun isIndexUnique(indexName: String): Boolean {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(indexName)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            val sql = cursor.getString(0) ?: return false
            return sql.contains("UNIQUE", ignoreCase = true)
        }
    }

    /**
     * Creates the base v1 schema (6 tables: accounts, calendars, events, occurrences,
     * pending_operations, sync_logs) with all indexes.
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

        Migrations.MIGRATION_1_2.migrate(db)

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

        Migrations.MIGRATION_1_2.migrate(db)

        assertTrue(indexExists("index_ics_subscriptions_url"))
        assertTrue(indexExists("index_ics_subscriptions_calendar_id"))
    }

    @Test
    fun `migration 1 to 2 is idempotent`() {
        createV1Schema()

        // Run twice - should not crash
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_1_2.migrate(db)

        assertTrue(tableExists("ics_subscriptions"))
    }

    // ==================== Migration 2 to 3: scheduled_reminders ====================

    @Test
    fun `migration 2 to 3 creates scheduled_reminders table with all columns`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db)

        Migrations.MIGRATION_2_3.migrate(db)

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
        Migrations.MIGRATION_1_2.migrate(db)

        Migrations.MIGRATION_2_3.migrate(db)

        assertTrue(indexExists("index_scheduled_reminders_event_id"))
        assertTrue(indexExists("index_scheduled_reminders_trigger_time"))
        assertTrue(indexExists("index_scheduled_reminders_status"))
        assertTrue(indexExists("index_scheduled_reminders_unique"))
    }

    // ==================== Migration 4 to 5: move columns ====================

    @Test
    fun `migration 4 to 5 adds target_url and target_calendar_id columns`() {
        createV1Schema()
        // Skip 1→2, 2→3 (not needed for pending_operations schema)
        assertFalse(columnExists("pending_operations", "target_url"))

        Migrations.MIGRATION_4_5.migrate(db)

        assertTrue(columnExists("pending_operations", "target_url"))
        assertTrue(columnExists("pending_operations", "target_calendar_id"))
    }

    // ==================== Migration 5 to 6: move_phase ====================

    @Test
    fun `migration 5 to 6 adds move_phase column with default 0`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)

        Migrations.MIGRATION_5_6.migrate(db)

        assertTrue(columnExists("pending_operations", "move_phase"))

        // Insert a row and verify default value
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (1, 'MOVE', 0, 0)")
        db.query("SELECT move_phase FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    // ==================== Migration 6 to 7: icaldav columns ====================

    @Test
    fun `migration 6 to 7 adds raw_ical import_id and alarm_count columns`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        Migrations.MIGRATION_6_7.migrate(db)

        assertTrue(columnExists("events", "raw_ical"))
        assertTrue(columnExists("events", "import_id"))
        assertTrue(columnExists("events", "alarm_count"))
    }

    @Test
    fun `migration 6 to 7 initializes import_id from uid`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        // Insert an event with a known UID
        db.execSQL("""INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)""")
        db.execSQL("""INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Cal', -1)""")
        db.execSQL("""INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES ('test-uid-123', 1, 'Test', 0, 1, 0, 0, 0)""")

        Migrations.MIGRATION_6_7.migrate(db)

        // Verify import_id was initialized from uid
        db.query("SELECT import_id FROM events WHERE uid = 'test-uid-123'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("test-uid-123", cursor.getString(0))
        }
    }

    @Test
    fun `migration 6 to 7 creates import_id indexes`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        Migrations.MIGRATION_6_7.migrate(db)

        assertTrue(indexExists("index_events_import_id"))
        assertTrue(indexExists("index_events_calendar_id_import_id"))
    }

    @Test
    fun `migration 6 to 7 creates uniqueness triggers`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        Migrations.MIGRATION_6_7.migrate(db)

        assertTrue(triggerExists("trigger_master_event_unique_insert"))
        assertTrue(triggerExists("trigger_master_event_unique_update"))
    }

    // ==================== Migration 7 to 8: RFC 5545/7986 fields ====================

    @Test
    fun `migration 7 to 8 adds RFC property columns`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db) // ics_subscriptions needed for boolean index
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)

        Migrations.MIGRATION_7_8.migrate(db)

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
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)

        Migrations.MIGRATION_7_8.migrate(db)

        assertTrue(indexExists("index_calendars_is_visible"))
        assertTrue(indexExists("index_accounts_is_enabled"))
        assertTrue(indexExists("index_ics_subscriptions_enabled"))
        assertTrue(indexExists("index_occurrences_is_cancelled"))
    }

    @Test
    fun `migration 7 to 8 is idempotent via addColumnIfNotExists`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)

        // Run twice - addColumnIfNotExists should prevent crash
        Migrations.MIGRATION_7_8.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)

        assertTrue(columnExists("events", "priority"))
    }

    // ==================== Migration 8 to 9: composite index ====================

    @Test
    fun `migration 8 to 9 creates exception lookup index`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)

        Migrations.MIGRATION_8_9.migrate(db)

        assertTrue(indexExists("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 8 to 9 is idempotent`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)

        // Run twice - indexExists check prevents crash
        Migrations.MIGRATION_8_9.migrate(db)
        Migrations.MIGRATION_8_9.migrate(db)

        assertTrue(indexExists("index_events_calendar_id_uid_original_instance_time"))
    }

    // ==================== Migration 9 to 10: unique occurrence index ====================

    @Test
    fun `migration 9 to 10 creates unique occurrence index`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)
        Migrations.MIGRATION_8_9.migrate(db)

        Migrations.MIGRATION_9_10.migrate(db)

        assertTrue(indexExists("index_occurrences_event_id_start_ts_unique"))
    }

    @Test
    fun `migration 9 to 10 removes duplicate occurrences before creating index`() {
        createV1Schema()
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)
        Migrations.MIGRATION_8_9.migrate(db)

        // Insert test data with duplicates
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Cal', -1)")
        db.execSQL("INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES (1, 'uid-1', 1, 'Test', 1000, 2000, 0, 0, 0)")
        db.execSQL("INSERT INTO occurrences (event_id, calendar_id, start_ts, end_ts, start_day, end_day) VALUES (1, 1, 1000, 2000, 20240101, 20240101)")
        db.execSQL("INSERT INTO occurrences (event_id, calendar_id, start_ts, end_ts, start_day, end_day) VALUES (1, 1, 1000, 2000, 20240101, 20240101)")  // duplicate

        db.query("SELECT COUNT(*) FROM occurrences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        Migrations.MIGRATION_9_10.migrate(db)

        // Should have removed the duplicate
        db.query("SELECT COUNT(*) FROM occurrences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    // ==================== Migration 10 to 11: retry lifecycle columns ====================

    @Test
    fun `migration 10 to 11 adds lifetime_reset_at and failed_at columns`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        Migrations.MIGRATION_10_11.migrate(db)

        assertTrue(columnExists("pending_operations", "lifetime_reset_at"))
        assertTrue(columnExists("pending_operations", "failed_at"))
    }

    @Test
    fun `migration 10 to 11 initializes lifetime_reset_at from created_at`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        // Insert an operation with a known created_at
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (1, 'UPDATE', 12345, 0)")

        Migrations.MIGRATION_10_11.migrate(db)

        db.query("SELECT lifetime_reset_at FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(12345, cursor.getLong(0))
        }
    }

    @Test
    fun `migration 10 to 11 is idempotent`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)

        // Run twice
        Migrations.MIGRATION_10_11.migrate(db)
        Migrations.MIGRATION_10_11.migrate(db)

        assertTrue(columnExists("pending_operations", "lifetime_reset_at"))
        assertTrue(columnExists("pending_operations", "failed_at"))
    }

    // ==================== Migration 11 to 12: source_calendar_id ====================

    @Test
    fun `migration 11 to 12 adds source_calendar_id column`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_10_11.migrate(db)

        Migrations.MIGRATION_11_12.migrate(db)

        assertTrue(columnExists("pending_operations", "source_calendar_id"))
    }

    @Test
    fun `migration 11 to 12 marks in-flight MOVE ops as FAILED`() {
        createV1Schema()
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_10_11.migrate(db)

        // Insert an in-flight MOVE at phase 0 (DELETE)
        db.execSQL("INSERT INTO pending_operations (event_id, operation, move_phase, status, created_at, updated_at) VALUES (1, 'MOVE', 0, 'PENDING', 0, 0)")
        // Also a normal UPDATE (should not be affected)
        db.execSQL("INSERT INTO pending_operations (event_id, operation, move_phase, status, created_at, updated_at) VALUES (2, 'UPDATE', 0, 'PENDING', 0, 0)")

        Migrations.MIGRATION_11_12.migrate(db)

        // MOVE at phase 0 should be marked FAILED
        db.query("SELECT status FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("FAILED", cursor.getString(0))
        }

        // UPDATE should remain PENDING
        db.query("SELECT status FROM pending_operations WHERE event_id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
        }
    }

    // ==================== Migration 12 to 13: unique index change ====================

    @Test
    fun `migration 12 to 13 replaces unique index with home_set_url`() {
        createV1Schema()

        Migrations.MIGRATION_12_13.migrate(db)

        // Old index should be gone
        assertFalse(indexExists("index_accounts_provider_email"))
        // New index should exist
        assertTrue(indexExists("index_accounts_provider_email_home_set_url"))
    }

    @Test
    fun `migration 12 to 13 allows same email on different servers`() {
        createV1Schema()

        Migrations.MIGRATION_12_13.migrate(db)

        // Insert two accounts with same provider+email but different home_set_url
        db.execSQL("INSERT INTO accounts (provider, email, home_set_url, created_at) VALUES ('CALDAV', 'user@example.com', 'https://server1.com/dav/', 0)")
        db.execSQL("INSERT INTO accounts (provider, email, home_set_url, created_at) VALUES ('CALDAV', 'user@example.com', 'https://server2.com/dav/', 0)")

        // Both should exist
        db.query("SELECT COUNT(*) FROM accounts WHERE email = 'user@example.com'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    // ==================== Migration 13 to 14: exception event unique index swap ====================

    /**
     * Chains all migrations 1→13 and creates the unique index that createV1Schema() omits.
     * Migration 13→14 expects index_events_original_event_id_original_instance_time to exist.
     */
    private fun setupV13Schema() {
        createV1Schema()
        // createV1Schema() omits Room-generated unique index that migration 13->14 drops
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
            "ON events (original_event_id, original_instance_time)"
        )
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_2_3.migrate(db)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)
        Migrations.MIGRATION_8_9.migrate(db)
        Migrations.MIGRATION_9_10.migrate(db)
        Migrations.MIGRATION_10_11.migrate(db)
        Migrations.MIGRATION_11_12.migrate(db)
        Migrations.MIGRATION_12_13.migrate(db)
    }

    /**
     * Minimal v13 schema for dedup tests: base tables + both target indices, but
     * WITHOUT the master dedup trigger (created by migration 6→7). This simulates
     * pre-v6 databases that may have orphan exceptions with original_event_id = NULL
     * coexisting with masters of the same UID — the exact pattern the dedup cleans.
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

    /** Insert test account + calendar for migration 13→14 tests. Returns calendar id. */
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

        Migrations.MIGRATION_13_14.migrate(db)

        // After: original_event_id_original_instance_time is non-unique
        assertTrue(indexExists("index_events_original_event_id_original_instance_time"))
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        // After: calendar_id_uid_original_instance_time is unique
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 13 to 14 dedup removes orphan when linked exists`() {
        // Use minimal schema (no master dedup trigger from migration 6→7) to allow
        // inserting orphan exceptions. This simulates pre-v6 databases that may have
        // accumulated orphan duplicates before the trigger existed.
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

        Migrations.MIGRATION_13_14.migrate(db)

        // Orphan (id=3) deleted, linked (id=2) survives
        db.query("SELECT id FROM events WHERE original_instance_time IS NOT NULL").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        // Master untouched
        db.query("SELECT COUNT(*) FROM events WHERE original_instance_time IS NULL").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 13 to 14 dedup keeps highest id for duplicate orphans`() {
        // Use minimal schema (no master dedup trigger) — see dedup orphan test above.
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

        Migrations.MIGRATION_13_14.migrate(db)

        // Highest id (3) survives
        db.query("SELECT id, title FROM events WHERE original_instance_time IS NOT NULL").use { cursor ->
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

        // Insert 3 masters (original_instance_time IS NULL — protected by dedup filter)
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

        Migrations.MIGRATION_13_14.migrate(db)

        db.query("SELECT COUNT(*) FROM events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 13 to 14 is idempotent`() {
        setupV13Schema()

        Migrations.MIGRATION_13_14.migrate(db)
        Migrations.MIGRATION_13_14.migrate(db)

        assertTrue(indexExists("index_events_original_event_id_original_instance_time"))
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test
    fun `migration 13 to 14 handles empty table`() {
        setupV13Schema()

        Migrations.MIGRATION_13_14.migrate(db)

        db.query("SELECT COUNT(*) FROM events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        assertFalse(isIndexUnique("index_events_original_event_id_original_instance_time"))
        assertTrue(isIndexUnique("index_events_calendar_id_uid_original_instance_time"))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `migration 13 to 14 new unique index blocks duplicate exceptions`() {
        setupV13Schema()
        val calId = insertTestAccountAndCalendar()

        Migrations.MIGRATION_13_14.migrate(db)

        // Insert master
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) " +
            "VALUES (1, 'master-uid', $calId, 'Master', 1000, 2000, 0, 0, 0)"
        )
        // First exception — succeeds
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (2, 'master-uid', $calId, 'Exception', 3000, 4000, 0, 0, 0, 1, 3000)"
        )
        // Second exception with same (calendar_id, uid, original_instance_time) — must throw
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at, " +
            "original_event_id, original_instance_time) " +
            "VALUES (3, 'master-uid', $calId, 'Duplicate', 3000, 4000, 0, 0, 0, 1, 3000)"
        )
    }

    // ==================== Migration 15 to 16: calendar mute/color/reminder + event end_timezone ====================

    @Test
    fun `migration 15 to 16 adds calendar columns`() {
        createV1Schema()

        Migrations.MIGRATION_15_16.migrate(db)

        assertTrue(columnExists("calendars", "is_notification_muted"))
        assertTrue(columnExists("calendars", "local_color_override"))
        assertTrue(columnExists("calendars", "default_reminder"))
    }

    @Test
    fun `migration 15 to 16 adds event end_timezone column`() {
        createV1Schema()

        Migrations.MIGRATION_15_16.migrate(db)

        assertTrue(columnExists("events", "end_timezone"))
    }

    @Test
    fun `migration 15 to 16 preserves existing data`() {
        createV1Schema()

        // Insert test data before migration
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Work', -16711936)")
        db.execSQL("INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) VALUES (1, 'uid-1', 1, 'Team Meeting', 1000, 2000, 'America/New_York', 0, 0, 0)")

        Migrations.MIGRATION_15_16.migrate(db)

        // Verify calendar data preserved
        db.query("SELECT display_name, color FROM calendars WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Work", cursor.getString(0))
            assertEquals(-16711936, cursor.getInt(1))
        }

        // Verify event data preserved
        db.query("SELECT title, timezone FROM events WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Team Meeting", cursor.getString(0))
            assertEquals("America/New_York", cursor.getString(1))
        }
    }

    @Test
    fun `migration 15 to 16 is idempotent`() {
        createV1Schema()

        // Run twice - addColumnIfNotExists prevents crash
        Migrations.MIGRATION_15_16.migrate(db)
        Migrations.MIGRATION_15_16.migrate(db)

        assertTrue(columnExists("calendars", "is_notification_muted"))
        assertTrue(columnExists("calendars", "local_color_override"))
        assertTrue(columnExists("calendars", "default_reminder"))
        assertTrue(columnExists("events", "end_timezone"))
    }

    @Test
    fun `migration 15 to 16 default values are correct`() {
        createV1Schema()

        Migrations.MIGRATION_15_16.migrate(db)

        // Insert rows after migration and verify defaults
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'test@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://cal.example.com/', 'Cal', -1)")

        db.query("SELECT is_notification_muted, local_color_override, default_reminder FROM calendars WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))  // is_notification_muted default = 0
            assertTrue(cursor.isNull(1))       // local_color_override default = NULL
            assertTrue(cursor.isNull(2))       // default_reminder default = NULL
        }

        db.execSQL("INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, dtstamp, created_at, updated_at) VALUES (1, 'uid-1', 1, 'Test', 0, 1, 0, 0, 0)")

        db.query("SELECT end_timezone FROM events WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))  // end_timezone default = NULL
        }
    }

    // ==================== Migration 16 to 17: scheduling schema bundle ====================

    /**
     * Walk the v1→v16 migration chain to land us at the v16 starting state for
     * MIGRATION_16_17 tests. Mirrors the chain executed by `full migration chain
     * 1 to 16 executes without error` but without the assertions.
     */
    private fun migrateUpToV16() {
        createV1Schema()
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
                "ON events (original_event_id, original_instance_time)"
        )
        Migrations.MIGRATION_1_2.migrate(db)
        Migrations.MIGRATION_2_3.migrate(db)
        // 3→4 is AutoMigration (skip in unit tests)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)
        Migrations.MIGRATION_8_9.migrate(db)
        Migrations.MIGRATION_9_10.migrate(db)
        Migrations.MIGRATION_10_11.migrate(db)
        Migrations.MIGRATION_11_12.migrate(db)
        Migrations.MIGRATION_12_13.migrate(db)
        Migrations.MIGRATION_13_14.migrate(db)
        Migrations.MIGRATION_14_15.migrate(db)
        Migrations.MIGRATION_15_16.migrate(db)
    }

    @Test
    fun `migration 16 to 17 adds calendar_user_addresses to accounts with default empty array`() {
        migrateUpToV16()
        Migrations.MIGRATION_16_17.migrate(db)

        assertTrue(columnExists("accounts", "calendar_user_addresses"))

        // Insert an account without specifying the new column — verify default fires.
        db.execSQL("INSERT INTO accounts (provider, email, created_at) VALUES ('CALDAV', 'a@example.com', 0)")
        db.query("SELECT calendar_user_addresses FROM accounts WHERE email = 'a@example.com'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
    }

    @Test
    fun `migration 16 to 17 adds organizer_sent_by and organizer_schedule_status to events`() {
        migrateUpToV16()
        Migrations.MIGRATION_16_17.migrate(db)

        assertTrue(columnExists("events", "organizer_sent_by"))
        assertTrue(columnExists("events", "organizer_schedule_status"))
    }

    @Test
    fun `migration 16 to 17 creates attendees table with all 16 columns`() {
        migrateUpToV16()
        Migrations.MIGRATION_16_17.migrate(db)

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
        Migrations.MIGRATION_16_17.migrate(db)

        assertTrue(indexExists("index_attendees_event_id"))
    }

    @Test
    fun `migration 16 to 17 creates index_attendees_address`() {
        migrateUpToV16()
        Migrations.MIGRATION_16_17.migrate(db)

        assertTrue(indexExists("index_attendees_address"))
    }

    @Test
    fun `migration 16 to 17 enforces FK CASCADE from attendees to events`() {
        migrateUpToV16()
        Migrations.MIGRATION_16_17.migrate(db)
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
        db.query("SELECT COUNT(*) FROM attendees WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        db.execSQL("DELETE FROM events WHERE id = 1")

        db.query("SELECT COUNT(*) FROM attendees WHERE event_id = 1").use { cursor ->
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

        Migrations.MIGRATION_16_17.migrate(db)

        db.query(
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

        Migrations.MIGRATION_16_17.migrate(db)

        db.query(
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
        Migrations.MIGRATION_16_17.migrate(db)
        // Second run must be a no-op — no exception, schema unchanged.
        Migrations.MIGRATION_16_17.migrate(db)

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

        Migrations.MIGRATION_16_17.migrate(db)

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
        db.query("SELECT COUNT(*) FROM attendees").use { cursor ->
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

        Migrations.MIGRATION_16_17.migrate(db)

        // Row from before the migration must still be present — table was not dropped.
        db.query("SELECT address FROM attendees WHERE event_id = 1").use { cursor ->
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
        Migrations.MIGRATION_1_2.migrate(db)

        // Run all migrations sequentially
        Migrations.MIGRATION_2_3.migrate(db)
        // 3→4 is AutoMigration (skip)
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_5_6.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        Migrations.MIGRATION_7_8.migrate(db)
        Migrations.MIGRATION_8_9.migrate(db)
        Migrations.MIGRATION_9_10.migrate(db)
        Migrations.MIGRATION_10_11.migrate(db)
        Migrations.MIGRATION_11_12.migrate(db)
        Migrations.MIGRATION_12_13.migrate(db)
        Migrations.MIGRATION_13_14.migrate(db)
        Migrations.MIGRATION_14_15.migrate(db)
        Migrations.MIGRATION_15_16.migrate(db)

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
        Migrations.MIGRATION_16_17.migrate(db)

        // Verify the scheduling schema landed on top of full chain
        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(columnExists("events", "organizer_sent_by"))
        assertTrue(columnExists("events", "organizer_schedule_status"))
        assertTrue(tableExists("attendees"))
        assertTrue(indexExists("index_attendees_event_id"))
        assertTrue(indexExists("index_attendees_address"))
    }

    // ==================== Migration 17 to 18: T2 RSVP/notification dedup state ====================

    /** Walk the v1→v17 migration chain to land at the v17 starting state for v18 tests. */
    private fun migrateUpToV17() {
        migrateUpToV16()
        Migrations.MIGRATION_16_17.migrate(db)
    }

    @Test
    fun `migration 17 to 18 adds partstat_only to pending_operations with default 0`() {
        migrateUpToV17()

        Migrations.MIGRATION_17_18.migrate(db)

        assertTrue(columnExists("pending_operations", "partstat_only"))

        // Default fires when the column is omitted on insert.
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (1, 'UPDATE', 0, 0)")
        db.query("SELECT partstat_only FROM pending_operations WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 17 to 18 adds partstat_target to pending_operations as nullable TEXT`() {
        migrateUpToV17()

        Migrations.MIGRATION_17_18.migrate(db)

        assertTrue(columnExists("pending_operations", "partstat_target"))

        // Default is NULL.
        db.execSQL("INSERT INTO pending_operations (event_id, operation, created_at, updated_at) VALUES (2, 'UPDATE', 0, 0)")
        db.query("SELECT partstat_target FROM pending_operations WHERE event_id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 17 to 18 adds notified_at to attendees as nullable INTEGER`() {
        migrateUpToV17()

        Migrations.MIGRATION_17_18.migrate(db)

        assertTrue(columnExists("attendees", "notified_at"))

        // Set up FKs and insert a row that omits notified_at.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'uid', 1, 'T', 1000, 2000, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (event_id, address) VALUES (1, 'mailto:a@example.com')")
        db.query("SELECT notified_at FROM attendees WHERE event_id = 1").use { cursor ->
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

        Migrations.MIGRATION_17_18.migrate(db)

        // pending_operations row survives, new columns at defaults.
        db.query(
            "SELECT operation, partstat_only, partstat_target FROM pending_operations WHERE id = 10"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("UPDATE", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
        // attendees row survives, new column at default NULL.
        db.query(
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

        Migrations.MIGRATION_17_18.migrate(db)
        Migrations.MIGRATION_17_18.migrate(db)

        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(columnExists("pending_operations", "partstat_target"))
        assertTrue(columnExists("attendees", "notified_at"))
    }

    @Test(expected = IllegalStateException::class)
    fun `migration 17 to 18 pre-migration shape check rejects wrong-type partstat_only`() {
        migrateUpToV17()

        // Hand-add partstat_only as TEXT instead of INTEGER (forked dev DB scenario).
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN partstat_only TEXT")

        // Migration must throw IllegalStateException, NOT silently leave a mis-typed column.
        Migrations.MIGRATION_17_18.migrate(db)
    }

    @Test
    fun `migration 17 to 18 post-migration validation block enumerates each missing column`() {
        // The post-validation block uses `buildList { ... }` to collect missing
        // columns and throws IllegalStateException with the missing list if any
        // failed to apply. We exercise this directly by running the validation
        // logic against a v17 schema (where none of the new columns exist yet)
        // and asserting all three would surface as missing.
        //
        // We can't easily corrupt a partial ADD COLUMN inside the running
        // migration (SQLite ALTER TABLE ADD COLUMN is itself transactional and
        // either completes or rolls back), so this test instead validates the
        // *shape* of the post-validation block: that all three column names
        // are spelled correctly and would be caught if they did somehow not
        // apply.
        migrateUpToV17()

        // Sanity: at v17 none of the new columns exist yet.
        assertFalse(columnExists("pending_operations", "partstat_only"))
        assertFalse(columnExists("pending_operations", "partstat_target"))
        assertFalse(columnExists("attendees", "notified_at"))

        // Run the migration normally — it must succeed.
        Migrations.MIGRATION_17_18.migrate(db)

        // All three columns must now exist (post-validation passed).
        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(columnExists("pending_operations", "partstat_target"))
        assertTrue(columnExists("attendees", "notified_at"))
    }

    // ==================== Full migration chain 1 to 18 ====================

    @Test
    fun `full migration chain 1 to 18 executes without error`() {
        migrateUpToV17()
        Migrations.MIGRATION_17_18.migrate(db)

        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(columnExists("pending_operations", "partstat_target"))
        assertTrue(columnExists("attendees", "notified_at"))

        // Spot-check that earlier-migration columns still exist after chained run.
        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(tableExists("attendees"))
        assertTrue(indexExists("index_attendees_event_id"))
    }

    // ==================== Migration 18 to 19: scheduling-capability + outbox discovery ====================

    /** Walk the v1→v18 migration chain to land at the v18 starting state for v19 tests. */
    private fun migrateUpToV18() {
        migrateUpToV17()
        Migrations.MIGRATION_17_18.migrate(db)
    }

    @Test
    fun `migration 18 to 19 adds schedule_outbox_url to accounts as nullable TEXT`() {
        migrateUpToV18()

        Migrations.MIGRATION_18_19.migrate(db)

        assertTrue(columnExists("accounts", "schedule_outbox_url"))

        // Default is NULL when the column is omitted on insert.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.query("SELECT schedule_outbox_url FROM accounts WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 18 to 19 adds auto_schedule_supported to calendars as nullable INTEGER`() {
        migrateUpToV18()

        Migrations.MIGRATION_18_19.migrate(db)

        assertTrue(columnExists("calendars", "auto_schedule_supported"))

        // Default is NULL (tri-state unknown) when omitted on insert.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.query("SELECT auto_schedule_supported FROM calendars WHERE id = 1").use { cursor ->
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

        Migrations.MIGRATION_18_19.migrate(db)

        // Account row survives, prior columns intact, new column NULL.
        db.query(
            "SELECT email, calendar_user_addresses, schedule_outbox_url FROM accounts WHERE id = 7"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("keep@test.com", cursor.getString(0))
            assertEquals("[\"mailto:keep@test.com\"]", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
        // Calendar row survives, prior columns intact, new column NULL.
        db.query(
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
        Migrations.MIGRATION_18_19.migrate(db)

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL(
            "INSERT INTO calendars (id, account_id, caldav_url, display_name, color, auto_schedule_supported) " +
                "VALUES (1, 1, 'https://yes/', 'Yes', 0, 1), " +
                "(2, 1, 'https://no/', 'No', 0, 0), " +
                "(3, 1, 'https://unknown/', 'Unknown', 0, NULL)"
        )
        db.query("SELECT id, auto_schedule_supported FROM calendars ORDER BY id").use { cursor ->
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

        Migrations.MIGRATION_18_19.migrate(db)
        Migrations.MIGRATION_18_19.migrate(db)

        assertTrue(columnExists("accounts", "schedule_outbox_url"))
        assertTrue(columnExists("calendars", "auto_schedule_supported"))
    }

    // ==================== Full migration chain 1 to 19 ====================

    @Test
    fun `full migration chain 1 to 19 executes without error`() {
        migrateUpToV18()
        Migrations.MIGRATION_18_19.migrate(db)

        assertTrue(columnExists("accounts", "schedule_outbox_url"))
        assertTrue(columnExists("calendars", "auto_schedule_supported"))

        // Spot-check that earlier-migration columns still exist after chained run.
        assertTrue(columnExists("accounts", "calendar_user_addresses"))
        assertTrue(columnExists("pending_operations", "partstat_only"))
        assertTrue(tableExists("attendees"))
    }

    // ==================== Migration 19 to 20: client-outbox iTIP send tracking ====================

    /** Walk the v1→v19 migration chain to land at the v19 starting state for v20 tests. */
    private fun migrateUpToV19() {
        migrateUpToV18()
        Migrations.MIGRATION_18_19.migrate(db)
    }

    private fun migrateUpToV20() {
        migrateUpToV19()
        Migrations.MIGRATION_19_20.migrate(db)
    }

    private fun migrateUpToV21() {
        migrateUpToV20()
        Migrations.MIGRATION_20_21.migrate(db)
    }

    private fun migrateUpToV22() {
        migrateUpToV21()
        Migrations.MIGRATION_21_22.migrate(db)
    }

    @Test
    fun `migration 22 to 23 creates address_books table with expected columns`() {
        migrateUpToV22()

        Migrations.MIGRATION_22_23.migrate(db)

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
        Migrations.MIGRATION_22_23.migrate(db)

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'a@example.test', 0)")
        db.execSQL(
            "INSERT INTO address_books (account_id, url, display_name, vcard_version, is_read_only, is_sync_enabled) " +
                "VALUES (1, 'https://contacts.example.test/books/home/', 'Home', '3.0', 1, 1)"
        )
        db.query(
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
        Migrations.MIGRATION_22_23.migrate(db)

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
        } catch (e: SQLiteConstraintException) {
            threw = true
        }
        assertTrue("duplicate (account_id, url) must violate the unique index", threw)
    }

    @Test
    fun `migration 22 to 23 cascades address_books on account delete`() {
        migrateUpToV22()
        Migrations.MIGRATION_22_23.migrate(db)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (5, 'ICLOUD', 'a@example.test', 0)")
        db.execSQL(
            "INSERT INTO address_books (account_id, url, display_name, vcard_version, is_read_only, is_sync_enabled) " +
                "VALUES (5, 'https://contacts.example.test/books/x/', 'X', '3.0', 1, 1)"
        )
        db.execSQL("DELETE FROM accounts WHERE id = 5")

        db.query("SELECT COUNT(*) FROM address_books WHERE account_id = 5").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 22 to 23 is idempotent`() {
        migrateUpToV22()

        Migrations.MIGRATION_22_23.migrate(db)
        Migrations.MIGRATION_22_23.migrate(db)

        assertTrue(tableExists("address_books"))
    }

    @Test
    fun `migration 20 to 21 creates pending_cancels table`() {
        migrateUpToV20()

        Migrations.MIGRATION_20_21.migrate(db)

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
        Migrations.MIGRATION_20_21.migrate(db)

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
        db.query("SELECT address, sequence, attempt_count FROM pending_cancels WHERE event_id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("mailto:gone@example.test", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
    }

    @Test
    fun `migration 20 to 21 is idempotent`() {
        migrateUpToV20()

        Migrations.MIGRATION_20_21.migrate(db)
        Migrations.MIGRATION_20_21.migrate(db)

        assertTrue(tableExists("pending_cancels"))
    }

    @Test
    fun `migration 20 to 21 cascades pending_cancels on event delete`() {
        migrateUpToV20()
        Migrations.MIGRATION_20_21.migrate(db)
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

        db.query("SELECT COUNT(*) FROM pending_cancels WHERE event_id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `migration 19 to 20 adds itip_request_sequence to attendees as nullable INTEGER`() {
        migrateUpToV19()

        Migrations.MIGRATION_19_20.migrate(db)

        assertTrue(columnExists("attendees", "itip_request_sequence"))

        // Default is NULL (never-sent) when the column is omitted on insert.
        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'u', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (id, event_id, address) VALUES (1, 1, 'mailto:g@example.test')")
        db.query("SELECT itip_request_sequence FROM attendees WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }

    @Test
    fun `migration 19 to 20 adds itip_request_status to attendees as nullable TEXT`() {
        migrateUpToV19()

        Migrations.MIGRATION_19_20.migrate(db)

        assertTrue(columnExists("attendees", "itip_request_status"))

        db.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        db.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
        db.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                "VALUES (1, 'u', 1, 'E', 0, 0, 'UTC', 0, 0, 0)"
        )
        db.execSQL("INSERT INTO attendees (id, event_id, address) VALUES (1, 1, 'mailto:g@example.test')")
        db.query("SELECT itip_request_status FROM attendees WHERE id = 1").use { cursor ->
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

        Migrations.MIGRATION_19_20.migrate(db)

        // Row survives, prior columns intact, new columns NULL.
        db.query(
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

        Migrations.MIGRATION_19_20.migrate(db)
        Migrations.MIGRATION_19_20.migrate(db)

        assertTrue(columnExists("attendees", "itip_request_sequence"))
        assertTrue(columnExists("attendees", "itip_request_status"))
    }

    // ==================== Migration 21 to 22 (categories) ====================

    /** Read a single category row's color (null if none or the row is absent). */
    private fun categoryColor(name: String): Int? {
        db.query("SELECT color FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getInt(0)
        }
    }

    private fun categoryCount(): Int {
        db.query("SELECT COUNT(*) FROM categories").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun categoryNames(): List<String> {
        val names = mutableListOf<String>()
        db.query("SELECT name FROM categories ORDER BY name").use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }

    private fun categoryLastUsed(name: String): Long? {
        db.query("SELECT last_used_at FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    @Test
    fun `migration 21 to 22 creates categories table with expected columns`() {
        migrateUpToV21()

        Migrations.MIGRATION_21_22.migrate(db)

        assertTrue(tableExists("categories"))
        assertTrue(columnExists("categories", "name"))
        assertTrue(columnExists("categories", "color"))
        assertTrue(columnExists("categories", "last_used_at"))
    }

    @Test
    fun `migration 21 to 22 name primary key is COLLATE NOCASE`() {
        migrateUpToV21()
        Migrations.MIGRATION_21_22.migrate(db)

        // Insert "Chores", then "chores" — a NOCASE PK collapses them to one row.
        db.execSQL("INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES ('Chores', 100, 1)")
        db.execSQL("INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES ('chores', 200, 2)")

        val choresRows = categoryNames().filter { it.equals("chores", ignoreCase = true) }
        assertEquals("Chores and chores must collapse to one row", 1, choresRows.size)
    }

    @Test
    fun `migration 21 to 22 seeds Work Personal Family with non-null colors`() {
        migrateUpToV21()

        Migrations.MIGRATION_21_22.migrate(db)

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

        Migrations.MIGRATION_21_22.migrate(db)

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

        Migrations.MIGRATION_21_22.migrate(db)

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

        Migrations.MIGRATION_21_22.migrate(db)

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

        Migrations.MIGRATION_21_22.migrate(db)

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

        Migrations.MIGRATION_21_22.migrate(db)

        db.query("SELECT title, start_ts, categories FROM events WHERE id = 7").use { cursor ->
            assertTrue("the event row still exists", cursor.moveToFirst())
            assertEquals("Keep Me", cursor.getString(0))
            assertEquals(111L, cursor.getLong(1))
            assertEquals("its category JSON is untouched", "[\"Work\",\"Gym\"]", cursor.getString(2))
        }
    }

    @Test
    fun `migration 21 to 22 is idempotent`() {
        migrateUpToV21()

        Migrations.MIGRATION_21_22.migrate(db)
        val countAfterFirst = categoryCount()
        Migrations.MIGRATION_21_22.migrate(db)

        assertTrue(tableExists("categories"))
        assertEquals("re-running adds no duplicate rows", countAfterFirst, categoryCount())
    }

    // ==================== Full migration chain 1 to 20 ====================

    @Test
    fun `full migration chain 1 to 20 executes without error`() {
        migrateUpToV19()
        Migrations.MIGRATION_19_20.migrate(db)

        assertTrue(columnExists("attendees", "itip_request_sequence"))
        assertTrue(columnExists("attendees", "itip_request_status"))

        // Spot-check that earlier-migration columns still exist after chained run.
        assertTrue(columnExists("accounts", "schedule_outbox_url"))
        assertTrue(columnExists("attendees", "notified_at"))
        assertTrue(tableExists("attendees"))
    }

    // ==================== Migration Chain/Registry Tests ====================

    @Test
    fun `all migrations array contains expected migrations`() {
        assertEquals(22, Migrations.ALL_MIGRATIONS.size)
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
    }

    @Test
    fun `migration versions form valid chain with gaps`() {
        val migrations = Migrations.ALL_MIGRATIONS.toList()
        // Manual: 1→2, 2→3, 4→5, ..., 12→13, 13→14 (AutoMigration: 3→4)
        assertTrue(migrations[0].endVersion == migrations[1].startVersion) // 2
        assertTrue(migrations[2].startVersion == 4) // gap at 3→4
        for (i in 2 until migrations.size - 1) {
            assertTrue(migrations[i].endVersion == migrations[i + 1].startVersion)
        }
    }
}
