package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.entity.Category

private const val TAG = "Migrations"

/**
 * Lenient JSON reader for the migration-time backfill of the `categories`
 * table. Mirrors `Converters.toStringList` exactly — a null/blank or malformed
 * value yields an empty list rather than throwing — so a single bad
 * `events.categories` blob can never fault the whole migration.
 */
private val migrationJson = Json { ignoreUnknownKeys = true }

private fun parseCategoriesBlob(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return try {
        migrationJson.decodeFromString<List<String>>(value)
    } catch (_: Exception) {
        emptyList()
    }
}

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
     * Check if a table exists.
     */
    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
            return cursor.count > 0
        }
    }

    /**
     * Read the set of column names for a table via PRAGMA table_info.
     * Returns an empty set if the table doesn't exist.
     */
    private fun tableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
        val result = mutableSetOf<String>()
        db.query("PRAGMA table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                result.add(cursor.getString(nameIndex))
            }
        }
        return result
    }

    /**
     * Read the SQLite affinity of a column via PRAGMA table_info, uppercased
     * for canonical comparison (`"INTEGER"`, `"TEXT"`, etc.). Returns null
     * when the column doesn't exist.
     *
     * Used by pre-migration shape checks to detect forked dev DBs where the
     * column was hand-added with the wrong type. Without this guard, an
     * `ALTER TABLE ADD COLUMN` skip via `addColumnIfNotExists` would silently
     * leave the mis-typed column in place and the next launch would 412 on
     * Room's identityHash check far away from the root cause.
     */
    private fun columnTypeOf(db: SupportSQLiteDatabase, table: String, column: String): String? {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val typeIndex = cursor.getColumnIndex("type")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    return cursor.getString(typeIndex)?.uppercase()
                }
            }
        }
        return null
    }

    /**
     * Whether [table]'s primary-key column is declared `COLLATE NOCASE`, read
     * from the stored `CREATE TABLE` SQL in `sqlite_master`. Used to prove a
     * migration produced a case-insensitive PK — a case-sensitive one would
     * silently let cased duplicates split into two rows, which a column-
     * existence check can't detect.
     */
    private fun primaryKeyIsNoCase(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return false
            val sql = cursor.getString(0)
            // Match the PK column's own definition carrying NOCASE, e.g.
            // `name` TEXT NOT NULL COLLATE NOCASE
            return Regex("""COLLATE\s+NOCASE""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
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
     * Migration from version 9 to 10.
     *
     * Adds unique constraint on occurrences (event_id, start_ts) to prevent
     * duplicate occurrences from concurrent sync operations (e.g., birthday sync).
     *
     * Before creating the index, removes any existing duplicates by keeping
     * only the occurrence with the lowest id for each (event_id, start_ts) pair.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        private val INDEX_NAME = "index_occurrences_event_id_start_ts_unique"

        override fun migrate(db: SupportSQLiteDatabase) {
            // Check if index already exists (idempotent migration)
            if (indexExists(db, INDEX_NAME)) {
                Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                return
            }

            try {
                // Count duplicates before deletion for logging
                val duplicateCount = db.query("""
                    SELECT COUNT(*) FROM occurrences
                    WHERE id NOT IN (
                        SELECT MIN(id)
                        FROM occurrences
                        GROUP BY event_id, start_ts
                    )
                """.trimIndent()).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                if (duplicateCount > 0) {
                    Log.i(TAG, "Found $duplicateCount duplicate occurrences to remove")

                    // Delete duplicate occurrences (keep the one with lowest id)
                    // This is required before creating the unique index
                    db.execSQL("""
                        DELETE FROM occurrences
                        WHERE id NOT IN (
                            SELECT MIN(id)
                            FROM occurrences
                            GROUP BY event_id, start_ts
                        )
                    """.trimIndent())
                    Log.i(TAG, "Removed $duplicateCount duplicate occurrences")
                } else {
                    Log.d(TAG, "No duplicate occurrences found")
                }

                // Create unique index to prevent future duplicates
                db.execSQL("""
                    CREATE UNIQUE INDEX $INDEX_NAME
                    ON occurrences (event_id, start_ts)
                """.trimIndent())
                Log.i(TAG, "Created unique index $INDEX_NAME")

                // Verify index was created
                if (indexExists(db, INDEX_NAME)) {
                    Log.d(TAG, "Verified index $INDEX_NAME exists")
                } else {
                    Log.w(TAG, "Index $INDEX_NAME not found after creation - this may cause issues")
                }
            } catch (e: Exception) {
                // Log error but don't fail migration - app can still work
                // The distinctBy in HomeScreen prevents crashes even without the index
                Log.e(TAG, "Error in migration 9->10: ${e.message}", e)
                // Re-throw to fail migration properly - Room will handle it
                throw e
            }
        }
    }

    /**
     * Migration from version 10 to 11.
     *
     * Adds retry lifecycle tracking to pending_operations:
     * - lifetime_reset_at: When user last interacted with event (30-day lifetime cap)
     * - failed_at: When operation entered FAILED status (24h auto-reset)
     *
     * Existing operations get lifetime_reset_at initialized from created_at.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Track when operation lifetime was last reset (user interaction)
                // Default 0 allows us to identify rows needing initialization
                addColumnIfNotExists(
                    db, "pending_operations", "lifetime_reset_at",
                    "INTEGER NOT NULL DEFAULT 0"
                )

                // Initialize lifetime_reset_at from created_at for existing operations
                // Only update rows where lifetime_reset_at is still 0 (idempotent)
                val updatedCount = db.compileStatement("""
                    UPDATE pending_operations
                    SET lifetime_reset_at = created_at
                    WHERE lifetime_reset_at = 0
                """.trimIndent()).executeUpdateDelete()

                if (updatedCount > 0) {
                    Log.i(TAG, "Initialized lifetime_reset_at for $updatedCount existing operations")
                }

                // Track when operation entered FAILED status (for 24h auto-reset)
                // Nullable - only set when operation is in FAILED status
                addColumnIfNotExists(
                    db, "pending_operations", "failed_at",
                    "INTEGER"
                )

                // Verify columns exist
                if (columnExists(db, "pending_operations", "lifetime_reset_at") &&
                    columnExists(db, "pending_operations", "failed_at")) {
                    Log.d(TAG, "Migration 10->11 completed: retry lifecycle columns added")
                } else {
                    Log.w(TAG, "Migration 10->11: column verification failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 10->11: ${e.message}", e)
                throw e  // Let Room handle the failure
            }
        }
    }

    /**
     * Migration from version 11 to 12.
     *
     * Adds sourceCalendarId to pending_operations for cross-account calendar moves.
     * This field preserves "where to delete from" after event.calendarId changes.
     *
     * IMPORTANT: In-flight MOVE operations at phase 0 (DELETE) are marked FAILED
     * because their sourceCalendarId cannot be reliably inferred - the event's
     * calendarId has already been updated to the target calendar.
     *
     * These operations will be retried via 24h auto-reset or Force Sync.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Step 1: Add source_calendar_id column (idempotent)
                addColumnIfNotExists(
                    db, "pending_operations", "source_calendar_id",
                    "INTEGER DEFAULT NULL"
                )

                // Step 2: Handle in-flight MOVE operations at DELETE phase
                //
                // CRITICAL: We CANNOT backfill sourceCalendarId from event.calendarId!
                // Timeline: User moves A→B → event.calendarId = B → queue MOVE(phase 0)
                // At migration time, event.calendarId is ALREADY B (target), not A (source)
                //
                // Solution: Mark as FAILED for user retry (low volume edge case)
                // The 24h auto-reset (MIGRATION_10_11) will pick these up automatically
                val inFlightMoves = db.compileStatement("""
                    UPDATE pending_operations
                    SET status = 'FAILED',
                        last_error = 'Migration 11->12: MOVE requires retry after upgrade',
                        failed_at = ${System.currentTimeMillis()}
                    WHERE operation = 'MOVE'
                    AND move_phase = 0
                    AND source_calendar_id IS NULL
                    AND status != 'FAILED'
                """.trimIndent()).executeUpdateDelete()

                if (inFlightMoves > 0) {
                    Log.w(TAG, "Marked $inFlightMoves in-flight MOVE operations as FAILED for retry")
                } else {
                    Log.d(TAG, "No in-flight MOVE operations to migrate")
                }

                // Step 3: Phase 1 (CREATE) MOVEs don't need sourceCalendarId
                // They filter by targetCalendarId which is already set correctly

                // Step 4: Verify column exists
                if (columnExists(db, "pending_operations", "source_calendar_id")) {
                    Log.d(TAG, "Migration 11->12 completed: source_calendar_id column added")
                } else {
                    Log.w(TAG, "Migration 11->12: column verification FAILED")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 11->12: ${e.message}", e)
                throw e  // Let Room handle the failure
            }
        }
    }

    /**
     * Migration from version 12 to 13.
     *
     * Changes the accounts unique index from (provider, email) to
     * (provider, email, home_set_url) so that the same username on
     * different CalDAV servers creates separate accounts instead of
     * colliding (Issue #69).
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Defensive check: log any CalDAV accounts with NULL home_set_url.
                // These accounts would not be found by the new 3-param lookup.
                // All CalDAV accounts should have home_set_url set during discovery.
                val nullHomeSetCount = db.query(
                    "SELECT COUNT(*) FROM accounts WHERE provider = 'CALDAV' AND home_set_url IS NULL"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (nullHomeSetCount > 0) {
                    Log.w(TAG, "Found $nullHomeSetCount CalDAV account(s) with NULL home_set_url. " +
                        "These accounts may need re-authentication after upgrade.")
                }

                // Step 1: Drop old unique index
                dropIndexIfExists(db, "index_accounts_provider_email")

                // Step 2: Create new unique index including home_set_url
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_provider_email_home_set_url " +
                    "ON accounts (provider, email, home_set_url)"
                )

                // Step 3: Verify new index exists
                if (indexExists(db, "index_accounts_provider_email_home_set_url")) {
                    Log.d(TAG, "Migration 12->13 completed: unique index updated to include home_set_url")
                } else {
                    Log.w(TAG, "Migration 12->13: index verification FAILED")
                }

                // Step 4: Verify old index is gone
                if (indexExists(db, "index_accounts_provider_email")) {
                    Log.w(TAG, "Migration 12->13: old index still exists after drop")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 12->13: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Migration from version 13 to 14.
     *
     * Swaps unique index on exception events from local FK-based
     * UNIQUE(original_event_id, original_instance_time) to RFC 5545 natural key
     * UNIQUE(calendar_id, uid, original_instance_time).
     *
     * The old index uses local IDs and cannot deduplicate orphan exceptions
     * (original_event_id = NULL makes SQLite treat each row as distinct).
     * The new index uses non-NULL columns and correctly identifies exceptions
     * per RFC 5545 §3.8.4.4 + RFC 4791 §4.1.
     *
     * Includes two-step dedup to clean any existing duplicates before creating
     * the unique index.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Step 1: Delete orphan exceptions that have a properly linked counterpart.
                // Keeps the linked version (original_event_id IS NOT NULL) which has the FK to the master.
                val orphanLinkedDedup = db.compileStatement("""
                    DELETE FROM events WHERE id IN (
                        SELECT e1.id FROM events e1
                        INNER JOIN events e2
                            ON e1.calendar_id = e2.calendar_id
                            AND e1.uid = e2.uid
                            AND e1.original_instance_time = e2.original_instance_time
                        WHERE e1.original_event_id IS NULL
                            AND e2.original_event_id IS NOT NULL
                            AND e1.original_instance_time IS NOT NULL
                    )
                """)
                val step1Deleted = orphanLinkedDedup.executeUpdateDelete()
                if (step1Deleted > 0) {
                    Log.d(TAG, "Migration 13->14: dedup step 1 deleted $step1Deleted orphan exceptions with linked counterparts")
                }

                // Step 2: Generic dedup for any remaining duplicates (two orphans,
                // two linked with different masters, etc.). Keeps the highest id
                // (most recently written) per (calendar_id, uid, original_instance_time).
                val genericDedup = db.compileStatement("""
                    DELETE FROM events
                    WHERE original_instance_time IS NOT NULL
                    AND id NOT IN (
                        SELECT MAX(id) FROM events
                        WHERE original_instance_time IS NOT NULL
                        GROUP BY calendar_id, uid, original_instance_time
                    )
                """)
                val step2Deleted = genericDedup.executeUpdateDelete()
                if (step2Deleted > 0) {
                    Log.d(TAG, "Migration 13->14: dedup step 2 deleted $step2Deleted remaining duplicate exceptions")
                }

                // Step 3: Drop old unique index on local FK columns
                dropIndexIfExists(db, "index_events_original_event_id_original_instance_time")

                // Step 4: Create non-unique replacement (still useful for FK lookups)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
                    "ON events (original_event_id, original_instance_time)"
                )

                // Step 5: Drop current non-unique composite index
                dropIndexIfExists(db, "index_events_calendar_id_uid_original_instance_time")

                // Step 6: Create RFC-correct unique index
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_events_calendar_id_uid_original_instance_time " +
                    "ON events (calendar_id, uid, original_instance_time)"
                )

                // Verify
                if (indexExists(db, "index_events_calendar_id_uid_original_instance_time")) {
                    Log.d(TAG, "Migration 13->14 completed: unique index swapped to (calendar_id, uid, original_instance_time)")
                } else {
                    Log.w(TAG, "Migration 13->14: new unique index verification FAILED")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 13->14: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Migration from version 14 to 15.
     *
     * Adds linked operation support for cross-account calendar moves:
     * - linked_move_id: UUID linking CREATE and DELETE operations in cross-account moves
     *
     * DELETE operations with a linkedMoveId are blocked by a guard query until
     * no pending CREATE with the same linkedMoveId exists. This prevents event loss
     * when DELETE runs before CREATE completes on a different account's sync cycle.
     *
     * IMPORTANT: In-flight cross-account moves (existing CREATE + DELETE pairs without
     * linkedMoveId) cannot be safely linked post-hoc because we can't determine which
     * CREATE belongs to which DELETE. These are left as-is and may result in:
     * - Duplication if CREATE succeeds and DELETE fails (recoverable)
     * - Event loss if DELETE runs first (rare: requires DELETE account to sync before
     *   CREATE account, which is unlikely for same-user accounts)
     *
     * New cross-account moves after this migration use the linked mechanism.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        private val INDEX_NAME = "index_pending_operations_linked_move_id"

        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Step 1: Add linked_move_id column (idempotent via helper)
                addColumnIfNotExists(
                    db, "pending_operations", "linked_move_id",
                    "TEXT DEFAULT NULL"
                )

                // Step 2: Create index for efficient guard query in getReadyOperations()
                // The guard query uses: WHERE linked.linked_move_id = po.linked_move_id
                if (!indexExists(db, INDEX_NAME)) {
                    db.execSQL("""
                        CREATE INDEX $INDEX_NAME
                        ON pending_operations(linked_move_id)
                    """.trimIndent())
                    Log.d(TAG, "Created index $INDEX_NAME for linked operation guard query")
                } else {
                    Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                }

                // Step 3: Log any in-flight cross-account moves for visibility
                // These are CREATE+DELETE pairs for the same event without linkedMoveId.
                // We don't modify them - just log for awareness.
                val inFlightCrossAccountMoves = db.query("""
                    SELECT COUNT(DISTINCT po1.event_id) FROM pending_operations po1
                    INNER JOIN pending_operations po2 ON po1.event_id = po2.event_id
                    WHERE po1.operation = 'CREATE'
                      AND po2.operation = 'DELETE'
                      AND po1.status = 'PENDING'
                      AND po2.status = 'PENDING'
                      AND po1.linked_move_id IS NULL
                      AND po2.linked_move_id IS NULL
                """.trimIndent()).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                if (inFlightCrossAccountMoves > 0) {
                    Log.w(TAG, "Found $inFlightCrossAccountMoves in-flight cross-account move(s) " +
                        "without linkedMoveId. These will use legacy (unlinked) behavior.")
                }

                // Step 4: Verify migration completed
                val columnOk = columnExists(db, "pending_operations", "linked_move_id")
                val indexOk = indexExists(db, INDEX_NAME)

                if (columnOk && indexOk) {
                    Log.d(TAG, "Migration 14->15 completed: linked_move_id column and index added")
                } else {
                    Log.w(TAG, "Migration 14->15 verification: column=$columnOk, index=$indexOk")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 14->15: ${e.message}", e)
                throw e  // Let Room handle the failure
            }
        }
    }

    /**
     * Migration from version 15 to 16.
     *
     * Adds columns for three features + one planned feature:
     * - is_notification_muted: Mute reminders per calendar (Issue #137)
     * - local_color_override: User color override for CalDAV calendars (Issue #102)
     * - default_reminder: Default reminder offset per calendar
     * - end_timezone: Different timezone for event end time (Issue #39)
     *
     * All columns use ALTER TABLE ADD COLUMN with defaults - instant, no data rewrite.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Calendar columns
            addColumnIfNotExists(db, "calendars", "is_notification_muted", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "calendars", "local_color_override", "INTEGER")
            addColumnIfNotExists(db, "calendars", "default_reminder", "TEXT")

            // Event columns
            addColumnIfNotExists(db, "events", "end_timezone", "TEXT")
        }
    }

    /**
     * Expected column-name set for the `attendees` table at v17. Used by
     * the drop-rogue-on-shape-mismatch check in MIGRATION_16_17.
     */
    private val EXPECTED_ATTENDEES_COLUMNS = setOf(
        "id",
        "event_id",
        "address",
        "display_name",
        "role",
        "partstat",
        "cutype",
        "rsvp",
        "delegated_from",
        "delegated_to",
        "member",
        "sent_by",
        "schedule_agent",
        "schedule_status",
        "schedule_force_send",
        "sort_order"
    )

    /**
     * Migration from version 16 to 17 — scheduling schema bundle.
     *
     * Schema delta:
     * - `accounts.calendar_user_addresses` (TEXT NOT NULL DEFAULT '[]') —
     *   JSON `List<String>` of CAL-ADDRESS forms from RFC 6638 §2.4.1
     *   `calendar-user-address-set` PROPFIND.
     * - `events.organizer_sent_by` (TEXT) — RFC 5545 §3.2.18.
     * - `events.organizer_schedule_status` (TEXT) — RFC 6638 §7.3.
     * - `attendees` table — child of events with FK CASCADE, 16 columns
     *   covering RFC 5545 §3.8.4.1 ATTENDEE plus RFC 6638 §7 scheduling
     *   parameters.
     *
     * Robustness guarantees:
     * 1. Idempotent — `addColumnIfNotExists`, `CREATE TABLE IF NOT EXISTS`,
     *    `CREATE INDEX IF NOT EXISTS` mean re-runs are safe no-ops.
     * 2. Explicit transaction wrap — defense in depth even though Room
     *    provides an implicit wrap; partial failures roll back.
     * 3. Drop-rogue-on-shape-mismatch — if `attendees` exists with column
     *    set ≠ expected, drop and recreate. Forward-compatible tables
     *    (matching shape) are left alone via IF NOT EXISTS.
     * 4. Post-migration validation — verify all expected columns/tables/
     *    indexes exist; throw `IllegalStateException` if any are missing.
     *    Validation runs INSIDE the transaction, BEFORE
     *    `setTransactionSuccessful()`, so a failed check rolls back rather
     *    than commits a broken schema.
     *
     * SQL strings for the new table and indexes are copied verbatim from
     * Room's autogen `17.json` schema export (with `${TABLE_NAME}`
     * substituted) so the migration's identityHash matches Room's
     * expected hash at startup.
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // 1. Additive column adds (idempotent via addColumnIfNotExists)
                addColumnIfNotExists(
                    db,
                    "accounts",
                    "calendar_user_addresses",
                    "TEXT NOT NULL DEFAULT '[]'"
                )
                addColumnIfNotExists(db, "events", "organizer_sent_by", "TEXT")
                addColumnIfNotExists(db, "events", "organizer_schedule_status", "TEXT")

                // 2. Drop-rogue-on-shape-mismatch: only destroys data on stale
                //    leftovers, never on a forward-compatible table. Empty set
                //    means the table doesn't exist — no drop needed.
                val actualColumns = tableColumns(db, "attendees")
                if (actualColumns.isNotEmpty() && actualColumns != EXPECTED_ATTENDEES_COLUMNS) {
                    Log.w(
                        TAG,
                        "Dropping rogue attendees table — column set mismatch " +
                            "(actual=$actualColumns expected=$EXPECTED_ATTENDEES_COLUMNS)"
                    )
                    db.execSQL("DROP TABLE attendees")
                }

                // 3. Create table + indexes (autogen SQL from 17.json,
                //    wrapped in IF NOT EXISTS for idempotency).
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attendees` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `event_id` INTEGER NOT NULL,
                        `address` TEXT NOT NULL,
                        `display_name` TEXT,
                        `role` TEXT,
                        `partstat` TEXT,
                        `cutype` TEXT,
                        `rsvp` INTEGER,
                        `delegated_from` TEXT NOT NULL DEFAULT '[]',
                        `delegated_to` TEXT NOT NULL DEFAULT '[]',
                        `member` TEXT NOT NULL DEFAULT '[]',
                        `sent_by` TEXT,
                        `schedule_agent` TEXT,
                        `schedule_status` TEXT,
                        `schedule_force_send` TEXT,
                        `sort_order` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`event_id`) REFERENCES `events`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attendees_event_id` " +
                        "ON `attendees` (`event_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attendees_address` " +
                        "ON `attendees` (`address`)"
                )

                // 4. Post-migration validation — runs BEFORE
                //    setTransactionSuccessful() so a thrown exception rolls
                //    back rather than commits a broken schema.
                val missing = buildList {
                    if (!columnExists(db, "accounts", "calendar_user_addresses")) {
                        add("accounts.calendar_user_addresses")
                    }
                    if (!columnExists(db, "events", "organizer_sent_by")) {
                        add("events.organizer_sent_by")
                    }
                    if (!columnExists(db, "events", "organizer_schedule_status")) {
                        add("events.organizer_schedule_status")
                    }
                    if (!tableExists(db, "attendees")) add("attendees table")
                    if (!indexExists(db, "index_attendees_event_id")) {
                        add("index_attendees_event_id")
                    }
                    if (!indexExists(db, "index_attendees_address")) {
                        add("index_attendees_address")
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_16_17 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Expected SQLite affinity for each new column added by MIGRATION_17_18.
     * Drives the pre-migration shape check that rejects forked dev DBs where
     * a column was hand-added with the wrong type.
     */
    private val EXPECTED_V18_COLUMN_TYPES = mapOf(
        Triple("pending_operations", "partstat_only", "INTEGER") to Unit,
        Triple("pending_operations", "partstat_target", "TEXT") to Unit,
        Triple("attendees", "notified_at", "INTEGER") to Unit
    ).keys

    /**
     * Migration from version 17 to 18 — RSVP / invite-notification state.
     *
     * Schema delta — three additive columns. None of these are RFC wire-
     * protocol fields; they are app-internal sync-queue state and
     * notification-dedup state.
     *
     * - `pending_operations.partstat_only` (INTEGER NOT NULL DEFAULT 0) —
     *   internal flag distinguishing an ordinary UPDATE (`0`) from a
     *   PARTSTAT-only RSVP write (`1`) that uses the
     *   `IcsPatcher.patchAttendeeReply` path.
     * - `pending_operations.partstat_target` (TEXT, nullable) — internal
     *   carrier for the target PARTSTAT value the operation should write.
     *   The *value* domain (`ACCEPTED`, `TENTATIVE`, `DECLINED`,
     *   `NEEDS-ACTION`) is RFC 5545 §3.2.12 PARTSTAT, canonicalized to
     *   uppercase via `AttendeeStatus.fromPartstat` at write time. The
     *   *column itself* is internal queue state. NULL when
     *   `partstat_only = 0`.
     * - `attendees.notified_at` (INTEGER, nullable epoch millis) — internal
     *   dedup timestamp marking when the per-invite system notification
     *   fired. NULL = not yet notified.
     *
     * Robustness pattern (mirrors the MIGRATION_16_17 scheduling-schema
     * bundle that preceded it):
     *  1. Explicit `try { ... } finally { db.endTransaction() }` wrap so a
     *     thrown validation exception always rolls back.
     *  2. `addColumnIfNotExists` for every column add (re-run safe).
     *  3. Pre-migration shape check: if a column already exists with the
     *     wrong SQLite affinity (forked dev DB scenario), throw
     *     `IllegalStateException` BEFORE attempting to add — silent skip
     *     would leave a mis-typed column in place and the next launch would
     *     fail Room's identityHash check far away from the root cause.
     *  4. Post-migration validation: collect missing columns; throw with
     *     the missing list IF NOT EMPTY, BEFORE
     *     `setTransactionSuccessful()`, so a thrown check rolls back rather
     *     than commits a broken schema.
     *  5. Validation order is load-bearing — `setTransactionSuccessful()`
     *     is the LAST statement in the try block.
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // 1. Pre-migration shape check — reject forked dev DBs that
                //    hand-added a column with the wrong type. addColumn-
                //    IfNotExists would otherwise silently no-op and leave
                //    the mis-typed column in place.
                val shapeMismatches = mutableListOf<String>()
                for ((table, column, expectedType) in EXPECTED_V18_COLUMN_TYPES) {
                    val actual = columnTypeOf(db, table, column)
                    if (actual != null && actual != expectedType) {
                        shapeMismatches.add(
                            "$table.$column expected $expectedType but found $actual"
                        )
                    }
                }
                if (shapeMismatches.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "MIGRATION_17_18 pre-migration shape check failed: $shapeMismatches"
                    )
                    throw IllegalStateException(
                        "MIGRATION_17_18 pre-migration shape check failed — " +
                            "column type mismatch on ${shapeMismatches.joinToString("; ")}. " +
                            "A previous (likely hand-edited) schema state is incompatible. " +
                            "Reinstall the app to clear the local DB."
                    )
                }

                // 2. Idempotent column adds.
                addColumnIfNotExists(
                    db,
                    "pending_operations",
                    "partstat_only",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfNotExists(
                    db,
                    "pending_operations",
                    "partstat_target",
                    "TEXT"
                )
                addColumnIfNotExists(
                    db,
                    "attendees",
                    "notified_at",
                    "INTEGER"
                )

                // 3. Post-migration validation — runs BEFORE
                //    setTransactionSuccessful() so a thrown exception rolls
                //    back rather than commits a broken schema.
                val missing = buildList {
                    if (!columnExists(db, "pending_operations", "partstat_only")) {
                        add("pending_operations.partstat_only")
                    }
                    if (!columnExists(db, "pending_operations", "partstat_target")) {
                        add("pending_operations.partstat_target")
                    }
                    if (!columnExists(db, "attendees", "notified_at")) {
                        add("attendees.notified_at")
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_17_18 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Migration from version 18 to 19 — scheduling-capability + outbox
     * discovery columns (RFC 6638 §2 / §2.1.1).
     *
     * Schema delta — two additive nullable columns:
     * - `accounts.schedule_outbox_url` (TEXT, nullable) — the principal's
     *   CALDAV:schedule-outbox-URL (RFC 6638 §2.1.1), discovered by PROPFIND.
     *   NULL = not yet discovered or no outbox advertised.
     * - `calendars.auto_schedule_supported` (INTEGER, nullable) — tri-state
     *   capability flag from the RFC 6638 §2 "calendar-auto-schedule" OPTIONS
     *   token on the collection. NULL = unknown / not yet probed, 0 = not
     *   advertised, 1 = advertised.
     *
     * Robustness pattern (mirrors MIGRATION_17_18): explicit transaction wrap,
     * idempotent `addColumnIfNotExists`, post-migration validation BEFORE
     * `setTransactionSuccessful()` so a thrown check rolls back rather than
     * commits a broken schema.
     *
     * No pre-migration type-shape check (unlike MIGRATION_17_18): both columns
     * are brand-new at v19, so the "forked dev DB hand-added the column with
     * the wrong affinity" case that check guards cannot arise here.
     *
     * Both columns are nullable with no DEFAULT, so existing rows take NULL
     * automatically and the next sync's discovery hook populates them.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // Idempotent column adds.
                addColumnIfNotExists(db, "accounts", "schedule_outbox_url", "TEXT")
                addColumnIfNotExists(db, "calendars", "auto_schedule_supported", "INTEGER")

                // Post-migration validation — runs BEFORE setTransactionSuccessful()
                // so a thrown exception rolls back rather than commits a broken schema.
                val missing = buildList {
                    if (!columnExists(db, "accounts", "schedule_outbox_url")) {
                        add("accounts.schedule_outbox_url")
                    }
                    if (!columnExists(db, "calendars", "auto_schedule_supported")) {
                        add("calendars.auto_schedule_supported")
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_18_19 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * v19 → v20: client-outbox iTIP send tracking.
     *
     * Adds two nullable `attendees` columns supporting the client-side
     * `METHOD:REQUEST` outbox send (RFC 6638 §6) on servers that decline to
     * self-schedule:
     * - `itip_request_sequence` (INTEGER) — the event SEQUENCE at which a
     *   REQUEST was last POSTed to this attendee; the idempotency marker that
     *   stops a re-push from re-sending (spamming) the same invitation.
     * - `itip_request_status` (TEXT) — the raw per-recipient request-status the
     *   outbox returned (e.g. `2.0;Success`), kept distinct from the
     *   server-PUT `schedule_status`.
     *
     * Both nullable with no DEFAULT — mirrors `notified_at` (MIGRATION_17_18).
     * Idempotent adds + in-transaction post-validation that rolls back rather
     * than committing a partial/broken schema (same shape as MIGRATION_18_19).
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // Idempotent column adds.
                addColumnIfNotExists(db, "attendees", "itip_request_sequence", "INTEGER")
                addColumnIfNotExists(db, "attendees", "itip_request_status", "TEXT")

                // Post-migration validation — runs BEFORE setTransactionSuccessful()
                // so a thrown exception rolls back rather than commits a broken schema.
                val missing = buildList {
                    if (!columnExists(db, "attendees", "itip_request_sequence")) {
                        add("attendees.itip_request_sequence")
                    }
                    if (!columnExists(db, "attendees", "itip_request_status")) {
                        add("attendees.itip_request_status")
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_19_20 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * v20 → v21: removed-attendee CANCEL queue.
     *
     * Adds the `pending_cancels` table: a guest dropped from an event's
     * attendee set, awaiting an iTIP CANCEL (RFC 5546 §3.2.2.6). A dedicated
     * table (not a column on `attendees`) because the removed attendee row is
     * deleted, and `replaceForEvent` would destroy an attendee-column marker
     * before its CANCEL could be delivered.
     *
     * Idempotent CREATE TABLE / CREATE INDEX (IF NOT EXISTS) + in-transaction
     * post-validation that rolls back rather than committing a partial schema
     * (same shape as MIGRATION_18_19/19_20). The CREATE SQL mirrors Room's
     * generated v21 schema so the migrated DB's identityHash matches the export.
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_cancels` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event_id` INTEGER NOT NULL, " +
                        "`recurrence_id` INTEGER, " +
                        "`address` TEXT NOT NULL, " +
                        "`schedule_agent` TEXT, " +
                        "`schedule_status` TEXT, " +
                        "`sequence` INTEGER NOT NULL, " +
                        "`attempt_count` INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(`event_id`) REFERENCES `events`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_cancels_event_id` " +
                        "ON `pending_cancels` (`event_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_pending_cancels_event_id_recurrence_id_address` " +
                        "ON `pending_cancels` (`event_id`, `recurrence_id`, `address`)"
                )

                // Post-migration validation — runs BEFORE setTransactionSuccessful()
                // so a thrown exception rolls back rather than commits a broken schema.
                val missing = buildList {
                    if (!tableExists(db, "pending_cancels")) add("pending_cancels (table)")
                    else {
                        for (col in listOf(
                            "event_id", "recurrence_id", "address",
                            "schedule_agent", "schedule_status", "sequence", "attempt_count"
                        )) {
                            if (!columnExists(db, "pending_cancels", col)) {
                                add("pending_cancels.$col")
                            }
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_20_21 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * v21 -> v22: add the `categories` tag-metadata table.
     *
     * Same robustness shape as MIGRATION_20_21: one transaction with
     * post-validation that throws *before* setTransactionSuccessful(), so a
     * partial schema rolls back rather than leaving Room to fail its hash check
     * on next launch. The CREATE SQL mirrors Room's generated v22 schema
     * (NOCASE primary key, nullable color, non-null last_used_at) so the
     * migrated identityHash matches the export.
     *
     * Beyond the table it seeds three curated defaults and backfills a row for
     * every tag already present on events:
     * - Seed runs BEFORE backfill; both use INSERT OR IGNORE, so where a
     *   backfilled name collides (case-insensitively) with a seeded default the
     *   seeded row wins and keeps its curated color.
     * - Backfill is done in Kotlin by iterating the events rows (not via a
     *   JSON SQL function, which has no precedent here and varies by SQLite
     *   build): each `categories` blob is parsed with the same lenient
     *   empty-on-malformed semantics as the app's TypeConverter, deduped
     *   case-insensitively (first-seen casing kept), tracking the most recent
     *   use, then inserted with color = NULL (renders via the name-hash color).
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (" +
                        "`name` TEXT NOT NULL COLLATE NOCASE, " +
                        "`color` INTEGER, " +
                        "`last_used_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`name`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_categories_last_used_at` " +
                        "ON `categories` (`last_used_at`)"
                )

                // Seed the curated defaults first (non-null colors). INSERT OR
                // IGNORE so a user who already tagged events "Work" keeps their
                // row and the seed stays deterministic on a re-run.
                val seedNow = System.currentTimeMillis()
                for ((name, color) in Category.DEFAULT_SEEDS) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, ?, ?)",
                        arrayOf<Any?>(name, color, seedNow)
                    )
                }

                // Backfill from existing event tags. Dedup case-insensitively,
                // first-seen casing wins, and track the most recent use so the
                // suggestion ranking is meaningful immediately after upgrade.
                data class Backfilled(val display: String, var lastUsed: Long)
                val byKey = LinkedHashMap<String, Backfilled>()
                db.query(
                    "SELECT categories, COALESCE(local_modified_at, start_ts) AS recency " +
                        "FROM events WHERE categories IS NOT NULL AND categories != ''"
                ).use { cursor ->
                    val categoriesIndex = cursor.getColumnIndexOrThrow("categories")
                    val recencyIndex = cursor.getColumnIndexOrThrow("recency")
                    while (cursor.moveToNext()) {
                        val blob = cursor.getString(categoriesIndex)
                        val recency = if (cursor.isNull(recencyIndex)) 0L else cursor.getLong(recencyIndex)
                        for (raw in parseCategoriesBlob(blob)) {
                            val name = raw.trim()
                            if (name.isEmpty()) continue
                            val key = name.lowercase()
                            val existing = byKey[key]
                            if (existing == null) {
                                byKey[key] = Backfilled(display = name, lastUsed = recency)
                            } else if (recency > existing.lastUsed) {
                                existing.lastUsed = recency
                            }
                        }
                    }
                }
                for (entry in byKey.values) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, NULL, ?)",
                        arrayOf<Any?>(entry.display, entry.lastUsed)
                    )
                }

                // Post-migration validation — runs BEFORE setTransactionSuccessful()
                // so a thrown exception rolls back rather than commits a broken schema.
                val missing = buildList {
                    if (!tableExists(db, "categories")) {
                        add("categories (table)")
                    } else {
                        for (col in listOf("name", "color", "last_used_at")) {
                            if (!columnExists(db, "categories", col)) add("categories.$col")
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_21_22 post-migration validation failed: missing $missing"
                    )
                }
                // A silently case-sensitive PK would let `Work` and `work` split
                // into two rows and defeat the case-insensitive dedup guarantee,
                // which a plain column-existence check would not catch.
                if (!primaryKeyIsNoCase(db, "categories")) {
                    throw IllegalStateException(
                        "MIGRATION_21_22 post-migration validation failed: " +
                            "categories.name is not COLLATE NOCASE"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * v22 -> v23: add the `address_books` CardDAV collection table.
     *
     * Same robustness shape as MIGRATION_20_21/MIGRATION_21_22: one transaction
     * with post-validation that throws *before* setTransactionSuccessful(), so a
     * partial schema rolls back rather than leaving Room to fail its hash check
     * on next launch. The CREATE SQL is copied verbatim from Room's generated
     * v23 schema (`address_books` createSql + both index createSql entries) so
     * the migrated identityHash matches the export. Purely additive — no data to
     * backfill; the empty table is populated by the first contact-sync pull.
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `address_books` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`account_id` INTEGER NOT NULL, `url` TEXT NOT NULL, " +
                        "`display_name` TEXT NOT NULL, `description` TEXT, " +
                        "`vcard_version` TEXT NOT NULL, `ctag` TEXT, `sync_token` TEXT, " +
                        "`is_read_only` INTEGER NOT NULL, `is_sync_enabled` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_address_books_account_id` " +
                        "ON `address_books` (`account_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_address_books_account_id_url` " +
                        "ON `address_books` (`account_id`, `url`)"
                )

                // Post-migration validation — runs BEFORE setTransactionSuccessful()
                // so a thrown exception rolls back rather than commits a broken schema.
                val missing = buildList {
                    if (!tableExists(db, "address_books")) {
                        add("address_books (table)")
                    } else {
                        for (col in listOf("id", "account_id", "url", "vcard_version", "sync_token")) {
                            if (!columnExists(db, "address_books", col)) add("address_books.$col")
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_22_23 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * v23 -> v24: add the `accounts.contact_sync_enabled` column (per-login
     * opt-in for CardDAV contact sync).
     *
     * Same robustness shape as the recent migrations: one transaction, an
     * idempotent `addColumnIfNotExists` add, and post-validation that throws
     * *before* setTransactionSuccessful() so a partial schema rolls back rather
     * than leaving Room to fail its identityHash check on next launch. Purely
     * additive with a `DEFAULT 0` — existing logins keep contact sync off until
     * the user opts in, matching the entity default.
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                addColumnIfNotExists(
                    db,
                    "accounts",
                    "contact_sync_enabled",
                    "INTEGER NOT NULL DEFAULT 0"
                )

                // Post-migration validation — runs BEFORE setTransactionSuccessful()
                // so a thrown exception rolls back rather than commits a broken schema.
                if (!columnExists(db, "accounts", "contact_sync_enabled")) {
                    throw IllegalStateException(
                        "MIGRATION_23_24 post-migration validation failed: " +
                            "missing accounts.contact_sync_enabled"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
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
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24
    )
}
