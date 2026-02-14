package org.onekash.kashcal.data.db.migration

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // ==================== Full Migration Chain ====================

    @Test
    fun `full migration chain 1 to 13 executes without error`() {
        createV1Schema()
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
    }

    // ==================== Migration Chain/Registry Tests ====================

    @Test
    fun `all migrations array contains expected migrations`() {
        assertEquals(11, Migrations.ALL_MIGRATIONS.size)
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
    }

    @Test
    fun `migration versions form valid chain with gaps`() {
        val migrations = Migrations.ALL_MIGRATIONS.toList()
        // Manual: 1→2, 2→3, 4→5, ..., 12→13 (AutoMigration: 3→4)
        assertTrue(migrations[0].endVersion == migrations[1].startVersion) // 2
        assertTrue(migrations[2].startVersion == 4) // gap at 3→4
        for (i in 2 until migrations.size - 1) {
            assertTrue(migrations[i].endVersion == migrations[i + 1].startVersion)
        }
    }
}
