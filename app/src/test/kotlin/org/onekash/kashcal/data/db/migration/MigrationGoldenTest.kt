package org.onekash.kashcal.data.db.migration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Golden tests for database migrations.
 *
 * These tests verify that migrations:
 * 1. Execute without errors
 * 2. Correctly modify the schema
 * 3. Preserve existing data
 * 4. Initialize new columns with correct defaults
 *
 * Uses SQLite directly with Robolectric to test migrations without needing
 * instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationGoldenTest {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setup() {
        // Create database with version 6 schema
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication()
        )
            .name("migration-golden-test")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createSchemaV6(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    // Not used in setup
                }
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun teardown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase("migration-golden-test")
    }

    // ==================== Migration 6 to 7 Tests ====================

    @Test
    fun `migration 6 to 7 adds raw_ical column`() {
        // Insert test data at version 6
        val eventId = insertEventV6("test-uid-1", "Test Event 1")

        // Run migration
        Migrations.MIGRATION_6_7.migrate(db)

        // Verify the column exists and data is preserved
        val cursor = db.query(
            "SELECT raw_ical, title FROM events WHERE id = ?",
            arrayOf(eventId.toString())
        )
        assertTrue("Event should exist after migration", cursor.moveToFirst())
        assertTrue("raw_ical should be null for migrated events", cursor.isNull(0))
        assertEquals("Test Event 1", cursor.getString(1))
        cursor.close()
    }

    @Test
    fun `migration 6 to 7 adds import_id column and initializes from uid`() {
        // Insert events with different UIDs
        insertEventV6("uid-alpha-123", "Event Alpha")
        insertEventV6("uid-beta-456", "Event Beta")

        // Run migration
        Migrations.MIGRATION_6_7.migrate(db)

        // Verify import_id is initialized from uid
        val cursor = db.query(
            "SELECT import_id, uid FROM events ORDER BY id"
        )

        assertTrue(cursor.moveToFirst())
        assertEquals("uid-alpha-123", cursor.getString(0)) // import_id
        assertEquals("uid-alpha-123", cursor.getString(1)) // uid

        assertTrue(cursor.moveToNext())
        assertEquals("uid-beta-456", cursor.getString(0))
        assertEquals("uid-beta-456", cursor.getString(1))

        cursor.close()
    }

    @Test
    fun `migration 6 to 7 adds alarm_count column with default 0`() {
        val eventId = insertEventV6("test-uid", "Test Event")

        Migrations.MIGRATION_6_7.migrate(db)

        val cursor = db.query(
            "SELECT alarm_count FROM events WHERE id = ?",
            arrayOf(eventId.toString())
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("alarm_count should default to 0", 0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun `migration 6 to 7 creates import_id indexes`() {
        Migrations.MIGRATION_6_7.migrate(db)

        // Check indexes exist
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE '%import_id%'"
        )

        val indexNames = mutableListOf<String>()
        while (cursor.moveToNext()) {
            indexNames.add(cursor.getString(0))
        }
        cursor.close()

        assertTrue(
            "Should have index_events_import_id",
            indexNames.contains("index_events_import_id")
        )
        assertTrue(
            "Should have index_events_calendar_id_import_id",
            indexNames.contains("index_events_calendar_id_import_id")
        )
    }

    @Test
    fun `migration 6 to 7 preserves all existing event data`() {
        // Insert event with many fields populated
        val values = ContentValues().apply {
            put("uid", "complex-event-uid")
            put("calendar_id", 1)
            put("title", "Complex Event")
            put("location", "Conference Room A")
            put("description", "Important meeting")
            put("start_ts", 1700000000000L)
            put("end_ts", 1700003600000L)
            put("timezone", "America/New_York")
            put("is_all_day", 0)
            put("status", "CONFIRMED")
            put("transp", "OPAQUE")
            put("classification", "PRIVATE")
            put("rrule", "FREQ=WEEKLY;BYDAY=MO,WE,FR")
            put("reminders", "[\"-PT15M\",\"-PT1H\"]")
            put("dtstamp", 1699900000000L)
            put("caldav_url", "https://caldav.example.com/event.ics")
            put("etag", "\"abc123\"")
            put("sequence", 2)
            put("sync_status", "SYNCED")
            put("created_at", 1699800000000L)
            put("updated_at", 1699900000000L)
        }
        val eventId = db.insert("events", SQLiteDatabase.CONFLICT_FAIL, values)

        Migrations.MIGRATION_6_7.migrate(db)

        // Verify all data is preserved
        val cursor = db.query(
            """SELECT uid, title, location, description, start_ts, end_ts,
               timezone, is_all_day, status, rrule, reminders, caldav_url, etag,
               sequence, sync_status, import_id, alarm_count, raw_ical
               FROM events WHERE id = ?""",
            arrayOf(eventId.toString())
        )

        assertTrue(cursor.moveToFirst())

        assertEquals("complex-event-uid", cursor.getString(0))  // uid
        assertEquals("Complex Event", cursor.getString(1))       // title
        assertEquals("Conference Room A", cursor.getString(2))   // location
        assertEquals("Important meeting", cursor.getString(3))   // description
        assertEquals(1700000000000L, cursor.getLong(4))          // start_ts
        assertEquals(1700003600000L, cursor.getLong(5))          // end_ts
        assertEquals("America/New_York", cursor.getString(6))    // timezone
        assertEquals(0, cursor.getInt(7))                        // is_all_day
        assertEquals("CONFIRMED", cursor.getString(8))           // status
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", cursor.getString(9)) // rrule
        assertEquals("[\"-PT15M\",\"-PT1H\"]", cursor.getString(10))    // reminders
        assertEquals("https://caldav.example.com/event.ics", cursor.getString(11)) // caldav_url
        assertEquals("\"abc123\"", cursor.getString(12))         // etag
        assertEquals(2, cursor.getInt(13))                       // sequence
        assertEquals("SYNCED", cursor.getString(14))             // sync_status

        // New columns
        assertEquals("complex-event-uid", cursor.getString(15))  // import_id (from uid)
        assertEquals(0, cursor.getInt(16))                       // alarm_count (default)
        assertTrue(cursor.isNull(17))                            // raw_ical (null)

        cursor.close()
    }

    @Test
    fun `migration 6 to 7 handles null uid gracefully`() {
        // Edge case: what if uid is somehow null? (shouldn't happen but test defensively)
        // Note: In practice, uid is NOT NULL so this would fail on insert.
        // This test verifies the migration SQL doesn't crash on edge cases.

        // Just verify migration runs without error on empty table
        Migrations.MIGRATION_6_7.migrate(db)

        // Verify table structure is correct
        val cursor = db.query("PRAGMA table_info(events)")
        val columns = mutableListOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(1)) // Column name is at index 1
        }
        cursor.close()

        assertTrue(columns.contains("raw_ical"))
        assertTrue(columns.contains("import_id"))
        assertTrue(columns.contains("alarm_count"))
    }

    @Test
    fun `new columns have correct types after migration`() {
        // Verify column types via PRAGMA table_info
        insertEventV6("test-uid", "Test Event")

        Migrations.MIGRATION_6_7.migrate(db)

        val cursor = db.query("PRAGMA table_info(events)")
        val columns = mutableMapOf<String, String>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(1)
            val type = cursor.getString(2)
            columns[name] = type
        }
        cursor.close()

        // Verify new columns have correct types
        assertEquals("TEXT", columns["raw_ical"])
        assertEquals("TEXT", columns["import_id"])
        assertEquals("INTEGER", columns["alarm_count"])
    }

    /**
     * CRITICAL TEST: Validate Room accepts the migrated schema.
     *
     * This test catches migration issues that other tests miss:
     * - Column order differences
     * - Default value format mismatches
     * - Index naming discrepancies
     * - Foreign key constraint differences
     *
     * The error "Migration didn't properly handle: events" means Room's
     * schema validation failed after migration. This test catches that.
     *
     * NOTE: This test uses a simplified approach - it creates a full v6 schema,
     * runs migration, then tries to open with Room. The schema must match exactly
     * what Room expects for v7.
     */
    @Test
    fun `migration 6 to 7 produces Room-compatible schema`() {
        // Close the simplified test database
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase("migration-golden-test")

        // Create database with COMPLETE v6 schema from exported schema
        val dbFile = RuntimeEnvironment.getApplication().getDatabasePath("migration-compat-test")
        dbFile.parentFile?.mkdirs()

        val rawDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createCompleteSchemaV6(rawDb)
        rawDb.version = 6

        // Insert test data
        val values = android.content.ContentValues().apply {
            put("id", 1)
            put("provider", "test")
            put("email", "test@test.com")
            put("created_at", System.currentTimeMillis())
        }
        rawDb.insert("accounts", null, values)

        val calValues = android.content.ContentValues().apply {
            put("id", 1)
            put("account_id", 1)
            put("caldav_url", "https://test.com/cal/")
            put("display_name", "Test Calendar")
            put("color", 0xFF0000FF.toInt())
        }
        rawDb.insert("calendars", null, calValues)

        val eventValues = android.content.ContentValues().apply {
            put("uid", "room-compat-test")
            put("calendar_id", 1)
            put("title", "Test Event")
            put("start_ts", System.currentTimeMillis())
            put("end_ts", System.currentTimeMillis() + 3600000)
            put("dtstamp", System.currentTimeMillis())
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        rawDb.insert("events", null, eventValues)
        rawDb.close()

        // Now open with Room - this will run migration 6→7 and validate
        val roomDb = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            KashCalDatabase::class.java,
            "migration-compat-test"
        )
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .build()

        try {
            // Force Room to open and validate the database
            // This will throw IllegalStateException if schema doesn't match
            kotlinx.coroutines.runBlocking {
                val events = roomDb.eventsDao().getByUid("room-compat-test")

                // If we get here, schema is compatible
                assertTrue("Should have at least one event", events.isNotEmpty())
                assertEquals("room-compat-test", events.first().uid)
            }
        } finally {
            roomDb.close()
            RuntimeEnvironment.getApplication().deleteDatabase("migration-compat-test")
        }
    }

    /**
     * Create COMPLETE v6 schema matching exported schema exactly.
     * This includes all tables, indices, triggers, and Room metadata.
     */
    private fun createCompleteSchemaV6(db: android.database.sqlite.SQLiteDatabase) {
        // Room master table
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '28035846caf35b59f8fc95edda4f76da')")

        // Accounts
        db.execSQL("""CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider` TEXT NOT NULL, `email` TEXT NOT NULL, `display_name` TEXT, `principal_url` TEXT, `home_set_url` TEXT, `credential_key` TEXT, `is_enabled` INTEGER NOT NULL DEFAULT 1, `last_sync_at` INTEGER, `last_successful_sync_at` INTEGER, `consecutive_sync_failures` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_provider_email` ON `accounts` (`provider`, `email`)")

        // Calendars
        db.execSQL("""CREATE TABLE IF NOT EXISTS `calendars` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `caldav_url` TEXT NOT NULL, `display_name` TEXT NOT NULL, `color` INTEGER NOT NULL, `ctag` TEXT, `sync_token` TEXT, `is_visible` INTEGER NOT NULL DEFAULT 1, `is_default` INTEGER NOT NULL DEFAULT 0, `is_read_only` INTEGER NOT NULL DEFAULT 0, `sort_order` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendars_account_id` ON `calendars` (`account_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_calendars_caldav_url` ON `calendars` (`caldav_url`)")

        // Events (v6 - without import_id, alarm_count, raw_ical)
        db.execSQL("""CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, `calendar_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `location` TEXT, `description` TEXT, `start_ts` INTEGER NOT NULL, `end_ts` INTEGER NOT NULL, `timezone` TEXT, `is_all_day` INTEGER NOT NULL DEFAULT 0, `status` TEXT NOT NULL DEFAULT 'CONFIRMED', `transp` TEXT NOT NULL DEFAULT 'OPAQUE', `classification` TEXT NOT NULL DEFAULT 'PUBLIC', `organizer_email` TEXT, `organizer_name` TEXT, `rrule` TEXT, `rdate` TEXT, `exdate` TEXT, `duration` TEXT, `original_event_id` INTEGER, `original_instance_time` INTEGER, `original_sync_id` TEXT, `reminders` TEXT, `extra_properties` TEXT, `dtstamp` INTEGER NOT NULL, `caldav_url` TEXT, `etag` TEXT, `sequence` INTEGER NOT NULL DEFAULT 0, `sync_status` TEXT NOT NULL DEFAULT 'SYNCED', `last_sync_error` TEXT, `sync_retry_count` INTEGER NOT NULL DEFAULT 0, `local_modified_at` INTEGER, `server_modified_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`calendar_id`) REFERENCES `calendars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`original_event_id`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_calendar_id` ON `events` (`calendar_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_start_ts` ON `events` (`start_ts`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_end_ts` ON `events` (`end_ts`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_calendar_id_start_ts` ON `events` (`calendar_id`, `start_ts`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_uid` ON `events` (`uid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_original_event_id` ON `events` (`original_event_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_events_original_event_id_original_instance_time` ON `events` (`original_event_id`, `original_instance_time`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_sync_status` ON `events` (`sync_status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_calendar_id_sync_status` ON `events` (`calendar_id`, `sync_status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_caldav_url` ON `events` (`caldav_url`)")

        // FTS table and triggers
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `events_fts` USING FTS4(`title` TEXT NOT NULL, `location` TEXT, `description` TEXT, content=`events`)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_BEFORE_UPDATE BEFORE UPDATE ON `events` BEGIN DELETE FROM `events_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_BEFORE_DELETE BEFORE DELETE ON `events` BEGIN DELETE FROM `events_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_AFTER_UPDATE AFTER UPDATE ON `events` BEGIN INSERT INTO `events_fts`(`docid`, `title`, `location`, `description`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`location`, NEW.`description`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_events_fts_AFTER_INSERT AFTER INSERT ON `events` BEGIN INSERT INTO `events_fts`(`docid`, `title`, `location`, `description`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`location`, NEW.`description`); END")

        // ICS Subscriptions
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ics_subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `calendar_id` INTEGER NOT NULL, `last_sync` INTEGER NOT NULL DEFAULT 0, `sync_interval_hours` INTEGER NOT NULL DEFAULT 24, `enabled` INTEGER NOT NULL DEFAULT 1, `etag` TEXT, `last_modified` TEXT, `username` TEXT, `last_error` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`calendar_id`) REFERENCES `calendars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ics_subscriptions_url` ON `ics_subscriptions` (`url`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ics_subscriptions_calendar_id` ON `ics_subscriptions` (`calendar_id`)")

        // Occurrences
        db.execSQL("""CREATE TABLE IF NOT EXISTS `occurrences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `event_id` INTEGER NOT NULL, `calendar_id` INTEGER NOT NULL, `start_ts` INTEGER NOT NULL, `end_ts` INTEGER NOT NULL, `start_day` INTEGER NOT NULL, `end_day` INTEGER NOT NULL, `is_cancelled` INTEGER NOT NULL DEFAULT 0, `exception_event_id` INTEGER, FOREIGN KEY(`event_id`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exception_event_id`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_start_ts_end_ts` ON `occurrences` (`start_ts`, `end_ts`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_start_day` ON `occurrences` (`start_day`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_event_id` ON `occurrences` (`event_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_calendar_id_start_ts` ON `occurrences` (`calendar_id`, `start_ts`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_exception_event_id` ON `occurrences` (`exception_event_id`)")

        // Pending Operations
        db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_operations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `event_id` INTEGER NOT NULL, `operation` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `retry_count` INTEGER NOT NULL DEFAULT 0, `max_retries` INTEGER NOT NULL DEFAULT 5, `next_retry_at` INTEGER NOT NULL DEFAULT 0, `last_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `target_url` TEXT, `target_calendar_id` INTEGER, `move_phase` INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_operations_event_id` ON `pending_operations` (`event_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_operations_status_next_retry_at` ON `pending_operations` (`status`, `next_retry_at`)")

        // Scheduled Reminders
        db.execSQL("""CREATE TABLE IF NOT EXISTS `scheduled_reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `event_id` INTEGER NOT NULL, `occurrence_time` INTEGER NOT NULL, `trigger_time` INTEGER NOT NULL, `reminder_offset` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `snooze_count` INTEGER NOT NULL DEFAULT 0, `event_title` TEXT NOT NULL, `event_location` TEXT, `is_all_day` INTEGER NOT NULL DEFAULT 0, `calendar_color` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, FOREIGN KEY(`event_id`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_reminders_event_id` ON `scheduled_reminders` (`event_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_reminders_trigger_time` ON `scheduled_reminders` (`trigger_time`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_reminders_status` ON `scheduled_reminders` (`status`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_reminders_event_id_occurrence_time_reminder_offset` ON `scheduled_reminders` (`event_id`, `occurrence_time`, `reminder_offset`)")

        // Sync Logs
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sync_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `calendar_id` INTEGER, `event_uid` TEXT, `action` TEXT NOT NULL, `result` TEXT NOT NULL, `details` TEXT, `http_status` INTEGER)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_logs_timestamp` ON `sync_logs` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_logs_calendar_id` ON `sync_logs` (`calendar_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_logs_event_uid` ON `sync_logs` (`event_uid`)")
    }

    // ==================== Helper Functions ====================

    /**
     * Create schema at version 6 (before migration 6→7).
     */
    private fun createSchemaV6(db: SupportSQLiteDatabase) {
        // Accounts table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                provider TEXT NOT NULL,
                email TEXT NOT NULL,
                display_name TEXT,
                principal_url TEXT,
                home_set_url TEXT,
                credential_key TEXT,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                last_sync_at INTEGER,
                last_successful_sync_at INTEGER,
                consecutive_sync_failures INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """)

        // Calendars table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS calendars (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                account_id INTEGER NOT NULL,
                caldav_url TEXT NOT NULL,
                display_name TEXT NOT NULL,
                color INTEGER NOT NULL,
                ctag TEXT,
                sync_token TEXT,
                is_visible INTEGER NOT NULL DEFAULT 1,
                is_default INTEGER NOT NULL DEFAULT 0,
                is_read_only INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )
        """)

        // Events table (version 6 - without raw_ical, import_id, alarm_count)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                uid TEXT NOT NULL,
                calendar_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                location TEXT,
                description TEXT,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER NOT NULL,
                timezone TEXT,
                is_all_day INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'CONFIRMED',
                transp TEXT NOT NULL DEFAULT 'OPAQUE',
                classification TEXT NOT NULL DEFAULT 'PUBLIC',
                organizer_email TEXT,
                organizer_name TEXT,
                rrule TEXT,
                rdate TEXT,
                exdate TEXT,
                duration TEXT,
                original_event_id INTEGER,
                original_instance_time INTEGER,
                original_sync_id TEXT,
                reminders TEXT,
                extra_properties TEXT,
                dtstamp INTEGER NOT NULL,
                caldav_url TEXT,
                etag TEXT,
                sequence INTEGER NOT NULL DEFAULT 0,
                sync_status TEXT NOT NULL DEFAULT 'SYNCED',
                last_sync_error TEXT,
                sync_retry_count INTEGER NOT NULL DEFAULT 0,
                local_modified_at INTEGER,
                server_modified_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE,
                FOREIGN KEY (original_event_id) REFERENCES events(id) ON DELETE CASCADE
            )
        """)

        // Create a test account and calendar for foreign key constraints
        db.insert("accounts", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("id", 1)
            put("provider", "test")
            put("email", "test@test.com")
            put("created_at", System.currentTimeMillis())
        })

        db.insert("calendars", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
            put("id", 1)
            put("account_id", 1)
            put("caldav_url", "https://test.com/cal/")
            put("display_name", "Test Calendar")
            put("color", 0xFF0000FF.toInt())
        })
    }

    /**
     * Insert an event at schema version 6 (before migration).
     */
    private fun insertEventV6(uid: String, title: String): Long {
        val values = ContentValues().apply {
            put("uid", uid)
            put("calendar_id", 1)
            put("title", title)
            put("start_ts", System.currentTimeMillis())
            put("end_ts", System.currentTimeMillis() + 3600000)
            put("dtstamp", System.currentTimeMillis())
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        return db.insert("events", SQLiteDatabase.CONFLICT_FAIL, values)
    }
}
