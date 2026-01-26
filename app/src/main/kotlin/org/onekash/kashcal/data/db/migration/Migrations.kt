package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migrations"

/**
 * Database migrations for KashCalDatabase.
 *
 * Each migration should be thoroughly tested before release.
 * Migrations are additive - never modify existing migrations.
 */
object Migrations {

    // ==================== Helper Functions ====================

    /**
     * Check if a column exists in a table.
     * Used to make migrations idempotent (safe to run multiple times).
     */
    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Add a column only if it doesn't already exist.
     * Prevents "duplicate column name" errors on partial migrations.
     */
    private fun addColumnIfNotExists(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        if (!columnExists(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            Log.d(TAG, "Added column $table.$column")
        } else {
            Log.d(TAG, "Column $table.$column already exists, skipping")
        }
    }

    /**
     * Check if an index exists.
     */
    private fun indexExists(db: SupportSQLiteDatabase, indexName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(indexName)).use { cursor ->
            return cursor.count > 0
        }
    }

    /**
     * Drop an index if it exists (more robust than DROP INDEX IF EXISTS).
     */
    private fun dropIndexIfExists(db: SupportSQLiteDatabase, indexName: String) {
        if (indexExists(db, indexName)) {
            db.execSQL("DROP INDEX $indexName")
            Log.d(TAG, "Dropped index $indexName")
        } else {
            Log.d(TAG, "Index $indexName doesn't exist, skipping drop")
        }
    }

    /**
     * Migration from version 1 to 2.
     *
     * Adds ICS subscription support:
     * - Creates ics_subscriptions table
     * - Adds unique indexes for url and calendar_id
     * - Sets up foreign key to calendars table
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create ics_subscriptions table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS ics_subscriptions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    calendar_id INTEGER NOT NULL,
                    last_sync INTEGER NOT NULL DEFAULT 0,
                    sync_interval_hours INTEGER NOT NULL DEFAULT 24,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    etag TEXT,
                    last_modified TEXT,
                    username TEXT,
                    last_error TEXT,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (calendar_id) REFERENCES calendars(id) ON DELETE CASCADE
                )
            """.trimIndent())

            // Create unique index on URL (prevents duplicate subscriptions)
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_ics_subscriptions_url
                ON ics_subscriptions (url)
            """.trimIndent())

            // Create unique index on calendar_id (one subscription per calendar)
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_ics_subscriptions_calendar_id
                ON ics_subscriptions (calendar_id)
            """.trimIndent())
        }
    }

    /**
     * Migration from version 2 to 3.
     *
     * Adds reminder notification support:
     * - Creates scheduled_reminders table for alarm tracking
     * - Follows Android CalendarProvider pattern (separate table for alarm instances)
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create scheduled_reminders table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS scheduled_reminders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    event_id INTEGER NOT NULL,
                    occurrence_time INTEGER NOT NULL,
                    trigger_time INTEGER NOT NULL,
                    reminder_offset TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    snooze_count INTEGER NOT NULL DEFAULT 0,
                    event_title TEXT NOT NULL,
                    event_location TEXT,
                    is_all_day INTEGER NOT NULL DEFAULT 0,
                    calendar_color INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
                )
            """.trimIndent())

            // Index on event_id (for cascade delete and queries)
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_event_id
                ON scheduled_reminders (event_id)
            """.trimIndent())

            // Index on trigger_time (for efficient alarm scheduling)
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_trigger_time
                ON scheduled_reminders (trigger_time)
            """.trimIndent())

            // Index on status (for querying pending/snoozed reminders)
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_status
                ON scheduled_reminders (status)
            """.trimIndent())

            // Unique index to prevent duplicate reminders for same event/occurrence/offset
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_scheduled_reminders_unique
                ON scheduled_reminders (event_id, occurrence_time, reminder_offset)
            """.trimIndent())
        }
    }

    /**
     * Migration from version 4 to 5.
     *
     * Adds calendar move support to pending_operations:
     * - target_url: Captures caldavUrl before it's cleared (for DELETE/MOVE)
     * - target_calendar_id: Target calendar for MOVE operations
     *
     * Fixes bug where calendar move would lose the event because:
     * 1. caldavUrl was cleared before DELETE processed
     * 2. queueOperation didn't allow both DELETE + CREATE
     *
     * Solution: New OPERATION_MOVE type that stores all context at queue time.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add target_url column for DELETE/MOVE operations
            db.execSQL("ALTER TABLE pending_operations ADD COLUMN target_url TEXT")

            // Add target_calendar_id column for MOVE operations
            db.execSQL("ALTER TABLE pending_operations ADD COLUMN target_calendar_id INTEGER")
        }
    }

    /**
     * Migration from version 5 to 6.
     *
     * Adds MOVE operation phase tracking to pending_operations:
     * - move_phase: Phase of MOVE operation (0 = DELETE, 1 = CREATE)
     *
     * Each phase gets independent 5-retry budget to prevent event loss when
     * DELETE succeeds but CREATE fails repeatedly.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add move_phase column for MOVE operation phase tracking
            db.execSQL("ALTER TABLE pending_operations ADD COLUMN move_phase INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 6 to 7.
     *
     * Adds icaldav library migration support:
     * - raw_ical: Original ICS data for round-trip preservation
     * - import_id: Unique identifier for sync (uid or uid:RECID:datetime)
     * - alarm_count: Total alarm count for optimization
     *
     * These columns enable:
     * - Preserving alarms beyond the first 3
     * - Preserving attendees and other properties not stored in columns
     * - Distinguishing exception events that share the same UID
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add raw_ical column for round-trip preservation
            db.execSQL("ALTER TABLE events ADD COLUMN raw_ical TEXT")

            // Add import_id column for unique event lookup during sync
            db.execSQL("ALTER TABLE events ADD COLUMN import_id TEXT")

            // Add alarm_count column for optimization
            db.execSQL("ALTER TABLE events ADD COLUMN alarm_count INTEGER NOT NULL DEFAULT 0")

            // Initialize import_id from uid for existing events (master events)
            db.execSQL("UPDATE events SET import_id = uid WHERE import_id IS NULL")

            // Create indexes for import_id lookups
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_import_id ON events (import_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_calendar_id_import_id ON events (calendar_id, import_id)")

            // Drop partial unique index created by databaseCallback.onCreate()
            // Room doesn't support partial indexes in @Index annotations, causing
            // schema validation to fail (Found has index that Expected doesn't have)
            // Use robust drop that verifies index exists first
            dropIndexIfExists(db, "index_events_uid_calendar_master")

            // Replace with trigger to enforce same constraint (Room doesn't validate triggers)
            // This prevents duplicate master events from iCloud sync (multiple servers may send same data)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS trigger_master_event_unique_insert
                BEFORE INSERT ON events
                WHEN NEW.original_event_id IS NULL
                BEGIN
                    SELECT RAISE(ABORT, 'UNIQUE constraint failed: duplicate master event uid in calendar')
                    WHERE EXISTS (
                        SELECT 1 FROM events
                        WHERE uid = NEW.uid
                        AND calendar_id = NEW.calendar_id
                        AND original_event_id IS NULL
                    );
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS trigger_master_event_unique_update
                BEFORE UPDATE ON events
                WHEN NEW.original_event_id IS NULL
                BEGIN
                    SELECT RAISE(ABORT, 'UNIQUE constraint failed: duplicate master event uid in calendar')
                    WHERE EXISTS (
                        SELECT 1 FROM events
                        WHERE uid = NEW.uid
                        AND calendar_id = NEW.calendar_id
                        AND original_event_id IS NULL
                        AND id != NEW.id
                    );
                END
            """.trimIndent())
        }
    }

    /**
     * Migration from version 7 to 8.
     *
     * Adds RFC 5545/7986 extended properties to events:
     * - priority: Event priority (0=undefined, 1=highest, 9=lowest)
     * - geo_lat, geo_lon: Geographic location coordinates
     * - color: Per-event color override (ARGB)
     * - url: Event link
     * - categories: Event tags/labels (JSON array)
     *
     * Also adds boolean indexes for query optimization:
     * - calendars.is_visible
     * - accounts.is_enabled
     * - ics_subscriptions.enabled
     * - occurrences.is_cancelled
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // New Event columns (RFC 5545/7986)
            // Use addColumnIfNotExists to handle partial migrations safely
            addColumnIfNotExists(db, "events", "priority", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "events", "geo_lat", "REAL")
            addColumnIfNotExists(db, "events", "geo_lon", "REAL")
            addColumnIfNotExists(db, "events", "color", "INTEGER")
            addColumnIfNotExists(db, "events", "url", "TEXT")
            addColumnIfNotExists(db, "events", "categories", "TEXT")

            // Also ensure the old problematic index is dropped (in case v6->7 didn't complete)
            dropIndexIfExists(db, "index_events_uid_calendar_master")

            // Boolean indexes for query optimization
            db.execSQL("CREATE INDEX IF NOT EXISTS index_calendars_is_visible ON calendars (is_visible)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_is_enabled ON accounts (is_enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ics_subscriptions_enabled ON ics_subscriptions (enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_occurrences_is_cancelled ON occurrences (is_cancelled)")
        }
    }

    /**
     * Migration from version 8 to 9.
     *
     * Adds composite index for RFC 5545 compliant exception event lookup.
     * Enables efficient getExceptionByUidAndInstanceTime() queries using
     * server-stable identifiers (UID + originalInstanceTime) instead of
     * local database IDs that can become stale.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        private val INDEX_NAME = "index_events_calendar_id_uid_original_instance_time"

        override fun migrate(db: SupportSQLiteDatabase) {
            // Check if index already exists (idempotent migration)
            if (indexExists(db, INDEX_NAME)) {
                Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                return
            }

            try {
                db.execSQL("""
                    CREATE INDEX $INDEX_NAME
                    ON events (calendar_id, uid, original_instance_time)
                """.trimIndent())
                Log.d(TAG, "Created index $INDEX_NAME for UID-based exception lookup")
            } catch (e: Exception) {
                // Log but don't fail - index is an optimization, not critical
                // The app will still work, just with slower exception lookups
                Log.e(TAG, "Failed to create index $INDEX_NAME: ${e.message}", e)
            }

            // Verify index was created
            if (indexExists(db, INDEX_NAME)) {
                Log.d(TAG, "Verified index $INDEX_NAME exists")
            } else {
                Log.w(TAG, "Index $INDEX_NAME not found after creation attempt")
            }
        }
    }

    /**
     * All migrations in order.
     * Add new migrations to this list as they are created.
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9
    )
}
