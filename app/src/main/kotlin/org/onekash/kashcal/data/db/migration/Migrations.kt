package org.onekash.kashcal.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for KashCalDatabase.
 *
 * Each migration should be thoroughly tested before release.
 * Migrations are additive - never modify existing migrations.
 */
object Migrations {

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
     * All migrations in order.
     * Add new migrations to this list as they are created.
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_4_5,
        MIGRATION_5_6
    )
}
