package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.entity.Category

private const val TAG = "Migrations"

/**
 * Lenient JSON reader for the `categories` backfill in [Migrations.MIGRATION_21_22]. Like
 * `Converters.toStringList`, a null, blank or malformed value yields an empty list, so one bad
 * `events.categories` blob can't fail the migration.
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
 * Holds the manual migrations for [org.onekash.kashcal.data.db.KashCalDatabase]; 3 to 4 is a Room
 * `AutoMigration` declared there.
 *
 * Never modify a shipped migration; add a new one.
 */
object Migrations {

    // ==================== Helper Functions ====================

    /** Returns whether [table] has [column]; the idempotency checks below use it. */
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

    /** Adds [column] unless it exists, so a re-run after a partial migration can't fail on it. */
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

    private fun indexExists(db: SupportSQLiteDatabase, indexName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(indexName)).use { cursor ->
            return cursor.count > 0
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
            return cursor.count > 0
        }
    }

    /** Returns [tableName]'s column names, or an empty set if the table doesn't exist. */
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
     * Returns the declared type of [column] uppercased (`"INTEGER"`, `"TEXT"`), or null if the
     * column doesn't exist.
     *
     * The pre-migration shape check in [MIGRATION_17_18] uses it to catch a forked dev DB with a
     * hand-added, mis-typed column. [addColumnIfNotExists] would silently skip that column, and
     * Room's schema validation after the migrations would reject the upgrade instead.
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
     * Returns whether [table]'s stored `CREATE TABLE` SQL declares `COLLATE NOCASE` on any
     * column. It stands in for a PK check only on a table whose sole NOCASE column is the
     * primary key, as in `categories`. A case-sensitive PK would silently split cased duplicates
     * into two rows, which a column-existence check can't detect.
     */
    private fun primaryKeyIsNoCase(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return false
            val sql = cursor.getString(0)
            // Matches NOCASE anywhere in the table SQL, e.g. `name` TEXT NOT NULL COLLATE NOCASE
            return Regex("""COLLATE\s+NOCASE""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
        }
    }

    /** Drops [indexName] if it exists, logging whether it did. */
    private fun dropIndexIfExists(db: SupportSQLiteDatabase, indexName: String) {
        if (indexExists(db, indexName)) {
            db.execSQL("DROP INDEX $indexName")
            Log.d(TAG, "Dropped index $indexName")
        } else {
            Log.d(TAG, "Index $indexName doesn't exist, skipping drop")
        }
    }

    /**
     * Migrates 1 to 2: adds the `ics_subscriptions` table, unique on url and on calendar_id, with a
     * cascading FK to calendars.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
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

            // One subscription per URL
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_ics_subscriptions_url
                ON ics_subscriptions (url)
            """.trimIndent())

            // One subscription per calendar
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_ics_subscriptions_calendar_id
                ON ics_subscriptions (calendar_id)
            """.trimIndent())
        }
    }

    /**
     * Migrates 2 to 3: adds the `scheduled_reminders` table, one row per alarm to fire, kept apart
     * from events the way Android CalendarProvider keeps its alarms.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
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

            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_event_id
                ON scheduled_reminders (event_id)
            """.trimIndent())

            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_trigger_time
                ON scheduled_reminders (trigger_time)
            """.trimIndent())

            db.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_status
                ON scheduled_reminders (status)
            """.trimIndent())

            // One reminder per event, occurrence and offset
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_scheduled_reminders_unique
                ON scheduled_reminders (event_id, occurrence_time, reminder_offset)
            """.trimIndent())
        }
    }

    /**
     * Migrates 4 to 5: adds the pending_operations columns that let a MOVE carry all its context
     * from queue time.
     * - target_url: the caldavUrl captured before it is cleared (DELETE and MOVE).
     * - target_calendar_id: the MOVE's destination calendar.
     *
     * Reading caldavUrl from the event at process time loses the event: it is already cleared.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_operations ADD COLUMN target_url TEXT")

            db.execSQL("ALTER TABLE pending_operations ADD COLUMN target_calendar_id INTEGER")
        }
    }

    /**
     * Migrates 5 to 6: adds pending_operations.move_phase (0 = DELETE, 1 = CREATE).
     *
     * Each phase gets its own retry budget, so a CREATE that keeps failing after its DELETE
     * succeeded doesn't run out of retries and lose the event.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_operations ADD COLUMN move_phase INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migrates 6 to 7: adds the events columns the iCalendar parser needs.
     * - raw_ical: the original ICS, kept for round trips, so alarms beyond the first 3 and
     *   properties without a column survive.
     * - import_id: the sync key (uid, or uid:RECID:datetime), which tells apart exceptions that
     *   share the master's UID.
     * - alarm_count: the event's alarm total; above 3,
     *   [org.onekash.kashcal.reminder.scheduler.ReminderScheduler] reads the extra alarms from
     *   raw_ical.
     *
     * It also replaces the partial unique index on master uid with triggers.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE events ADD COLUMN raw_ical TEXT")

            db.execSQL("ALTER TABLE events ADD COLUMN import_id TEXT")

            db.execSQL("ALTER TABLE events ADD COLUMN alarm_count INTEGER NOT NULL DEFAULT 0")

            // Existing rows take the master form, import_id = uid
            db.execSQL("UPDATE events SET import_id = uid WHERE import_id IS NULL")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_import_id ON events (import_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_calendar_id_import_id ON events (calendar_id, import_id)")

            // Older installs created a partial unique index at database creation. Room can't
            // declare partial indexes in @Index, so its schema validation fails on it ("Found has
            // index that Expected doesn't have").
            dropIndexIfExists(db, "index_events_uid_calendar_master")

            // Triggers enforce the same constraint, and Room doesn't validate triggers. They stop
            // duplicate master events from iCloud sync (multiple servers may send the same data).
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
     * Migrates 7 to 8: adds RFC 5545/7986 properties to events and indexes on boolean filters.
     * - priority: 0 = undefined, 1 = highest, 9 = lowest.
     * - geo_lat, geo_lon: coordinates.
     * - color: per-event ARGB override.
     * - url: event link.
     * - categories: tags as a JSON array.
     *
     * Indexed: calendars.is_visible, accounts.is_enabled, ics_subscriptions.enabled and
     * occurrences.is_cancelled.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfNotExists(db, "events", "priority", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "events", "geo_lat", "REAL")
            addColumnIfNotExists(db, "events", "geo_lon", "REAL")
            addColumnIfNotExists(db, "events", "color", "INTEGER")
            addColumnIfNotExists(db, "events", "url", "TEXT")
            addColumnIfNotExists(db, "events", "categories", "TEXT")

            // In case 6 to 7 didn't complete
            dropIndexIfExists(db, "index_events_uid_calendar_master")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_calendars_is_visible ON calendars (is_visible)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_is_enabled ON accounts (is_enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ics_subscriptions_enabled ON ics_subscriptions (enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_occurrences_is_cancelled ON occurrences (is_cancelled)")
        }
    }

    /**
     * Migrates 8 to 9: indexes events on (calendar_id, uid, original_instance_time) for
     * `EventsDao.getExceptionByUidAndInstanceTime`, which finds an exception by its server-stable
     * UID and instance time (RFC 5545) instead of a local ID that can go stale.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        private val INDEX_NAME = "index_events_calendar_id_uid_original_instance_time"

        override fun migrate(db: SupportSQLiteDatabase) {
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
                // The index only speeds up exception lookups, so a failure doesn't fail the
                // migration.
                Log.e(TAG, "Failed to create index $INDEX_NAME: ${e.message}", e)
            }

            if (indexExists(db, INDEX_NAME)) {
                Log.d(TAG, "Verified index $INDEX_NAME exists")
            } else {
                Log.w(TAG, "Index $INDEX_NAME not found after creation attempt")
            }
        }
    }

    /**
     * Migrates 9 to 10: makes occurrences unique on (event_id, start_ts), so concurrent syncs
     * (birthday sync, for example) can't write duplicates.
     *
     * Existing duplicates are deleted first, keeping the lowest id of each pair.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        private val INDEX_NAME = "index_occurrences_event_id_start_ts_unique"

        override fun migrate(db: SupportSQLiteDatabase) {
            if (indexExists(db, INDEX_NAME)) {
                Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                return
            }

            try {
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

                    // The unique index can't be created while duplicates exist
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

                db.execSQL("""
                    CREATE UNIQUE INDEX $INDEX_NAME
                    ON occurrences (event_id, start_ts)
                """.trimIndent())
                Log.i(TAG, "Created unique index $INDEX_NAME")

                if (indexExists(db, INDEX_NAME)) {
                    Log.d(TAG, "Verified index $INDEX_NAME exists")
                } else {
                    Log.w(TAG, "Index $INDEX_NAME not found after creation - this may cause issues")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 9->10: ${e.message}", e)
                // Fails the migration
                throw e
            }
        }
    }

    /**
     * Migrates 10 to 11: adds retry lifecycle columns to pending_operations.
     * - lifetime_reset_at: when the user last touched the event; starts the 30-day lifetime cap.
     *   Existing rows take created_at.
     * - failed_at: when the operation went FAILED; starts the 24-hour auto-reset.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Default 0 marks the rows still to initialize
                addColumnIfNotExists(
                    db, "pending_operations", "lifetime_reset_at",
                    "INTEGER NOT NULL DEFAULT 0"
                )

                // Only rows still at 0, so a re-run is a no-op
                val updatedCount = db.compileStatement("""
                    UPDATE pending_operations
                    SET lifetime_reset_at = created_at
                    WHERE lifetime_reset_at = 0
                """.trimIndent()).executeUpdateDelete()

                if (updatedCount > 0) {
                    Log.i(TAG, "Initialized lifetime_reset_at for $updatedCount existing operations")
                }

                // Null unless the operation is FAILED
                addColumnIfNotExists(
                    db, "pending_operations", "failed_at",
                    "INTEGER"
                )

                if (columnExists(db, "pending_operations", "lifetime_reset_at") &&
                    columnExists(db, "pending_operations", "failed_at")) {
                    Log.d(TAG, "Migration 10->11 completed: retry lifecycle columns added")
                } else {
                    Log.w(TAG, "Migration 10->11: column verification failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 10->11: ${e.message}", e)
                throw e  // Fails the migration
            }
        }
    }

    /**
     * Migrates 11 to 12: adds pending_operations.source_calendar_id, the calendar a cross-account
     * MOVE deletes from once event.calendarId points at the target.
     *
     * In-flight MOVEs at phase 0 (DELETE) are marked FAILED: their source can't be inferred, since
     * the event's calendarId is already the target. The 24-hour auto-reset or Force Sync retries
     * them.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                addColumnIfNotExists(
                    db, "pending_operations", "source_calendar_id",
                    "INTEGER DEFAULT NULL"
                )

                // Don't backfill source_calendar_id from event.calendarId: a move from A to B
                // sets event.calendarId = B before it queues MOVE(phase 0), so the event names the
                // target, not the source.
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

                // Phase 1 (CREATE) MOVEs select by target_calendar_id and need no source.

                if (columnExists(db, "pending_operations", "source_calendar_id")) {
                    Log.d(TAG, "Migration 11->12 completed: source_calendar_id column added")
                } else {
                    Log.w(TAG, "Migration 11->12: column verification FAILED")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 11->12: ${e.message}", e)
                throw e  // Fails the migration
            }
        }
    }

    /**
     * Migrates 12 to 13: widens the accounts unique index from (provider, email) to
     * (provider, email, home_set_url), so one username on two CalDAV servers makes two accounts
     * (#69).
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Every CalDAV account should have home_set_url from discovery. One without it
                // can't be found by the (provider, email, home_set_url) lookup, so it is logged.
                val nullHomeSetCount = db.query(
                    "SELECT COUNT(*) FROM accounts WHERE provider = 'CALDAV' AND home_set_url IS NULL"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (nullHomeSetCount > 0) {
                    Log.w(TAG, "Found $nullHomeSetCount CalDAV account(s) with NULL home_set_url. " +
                        "These accounts may need re-authentication after upgrade.")
                }

                dropIndexIfExists(db, "index_accounts_provider_email")

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_provider_email_home_set_url " +
                    "ON accounts (provider, email, home_set_url)"
                )

                if (indexExists(db, "index_accounts_provider_email_home_set_url")) {
                    Log.d(TAG, "Migration 12->13 completed: unique index updated to include home_set_url")
                } else {
                    Log.w(TAG, "Migration 12->13: index verification FAILED")
                }

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
     * Migrates 13 to 14: moves the exception unique key from the local
     * (original_event_id, original_instance_time) to the RFC 5545 natural key
     * (calendar_id, uid, original_instance_time).
     *
     * The old key can't dedupe orphan exceptions: SQLite treats every NULL original_event_id as
     * distinct. The new key's columns are non-NULL on an exception and identify it per RFC 5545
     * §3.8.4.4 and RFC 4791 §4.1. Existing duplicates are removed in two steps before the unique
     * index is created.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // Step 1: delete orphan exceptions that have a linked counterpart, keeping the
                // linked row (original_event_id IS NOT NULL), which has the FK to the master.
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

                // Step 2: any remaining duplicates (two orphans, two linked to different masters)
                // keep the highest id, the most recently written, per natural key.
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

                dropIndexIfExists(db, "index_events_original_event_id_original_instance_time")

                // Recreated non-unique for FK lookups
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
                    "ON events (original_event_id, original_instance_time)"
                )

                dropIndexIfExists(db, "index_events_calendar_id_uid_original_instance_time")

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_events_calendar_id_uid_original_instance_time " +
                    "ON events (calendar_id, uid, original_instance_time)"
                )

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
     * Migrates 14 to 15: adds pending_operations.linked_move_id, the UUID pairing the CREATE and
     * DELETE of a cross-account move.
     *
     * `PendingOperationsDao.getReadyOperations` holds back a linked DELETE while a PENDING CREATE
     * with the same id exists, so the copy lands before the original is removed.
     *
     * In-flight cross-account CREATE and DELETE pairs are left unlinked: which CREATE belongs to
     * which DELETE can't be told afterwards. They may duplicate the event if the CREATE succeeds
     * and the DELETE fails, or lose it if the DELETE account syncs first, which is unlikely for
     * one user's accounts.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        private val INDEX_NAME = "index_pending_operations_linked_move_id"

        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                addColumnIfNotExists(
                    db, "pending_operations", "linked_move_id",
                    "TEXT DEFAULT NULL"
                )

                // For the getReadyOperations guard: WHERE linked.linked_move_id = po.linked_move_id
                if (!indexExists(db, INDEX_NAME)) {
                    db.execSQL("""
                        CREATE INDEX $INDEX_NAME
                        ON pending_operations(linked_move_id)
                    """.trimIndent())
                    Log.d(TAG, "Created index $INDEX_NAME for linked operation guard query")
                } else {
                    Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                }

                // Logs the unlinked CREATE and DELETE pairs for one event; they aren't modified.
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

                val columnOk = columnExists(db, "pending_operations", "linked_move_id")
                val indexOk = indexExists(db, INDEX_NAME)

                if (columnOk && indexOk) {
                    Log.d(TAG, "Migration 14->15 completed: linked_move_id column and index added")
                } else {
                    Log.w(TAG, "Migration 14->15 verification: column=$columnOk, index=$indexOk")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in migration 14->15: ${e.message}", e)
                throw e  // Fails the migration
            }
        }
    }

    /**
     * Migrates 15 to 16: adds four columns.
     * - calendars.is_notification_muted: mute reminders per calendar (#137); nothing reads it yet.
     * - calendars.local_color_override: the user's color for a CalDAV calendar (#102).
     * - calendars.default_reminder: a calendar's default reminder offset.
     * - events.end_timezone: a separate timezone for the event end (#39).
     *
     * Each is an ALTER TABLE ADD COLUMN, which rewrites no data.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfNotExists(db, "calendars", "is_notification_muted", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "calendars", "local_color_override", "INTEGER")
            addColumnIfNotExists(db, "calendars", "default_reminder", "TEXT")

            addColumnIfNotExists(db, "events", "end_timezone", "TEXT")
        }
    }

    /** Lists the v17 `attendees` columns; [MIGRATION_16_17] drops a table that differs. */
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
     * Migrates 16 to 17: adds the scheduling schema.
     * - `accounts.calendar_user_addresses` (TEXT NOT NULL DEFAULT '[]'): JSON `List<String>` of
     *   the CAL-ADDRESS forms from the RFC 6638 §2.4.1 `calendar-user-address-set` PROPFIND.
     * - `events.organizer_sent_by` (TEXT): RFC 5545 §3.2.18.
     * - `events.organizer_schedule_status` (TEXT): RFC 6638 §7.3.
     * - `attendees`: a child of events with FK CASCADE, 16 columns covering the RFC 5545
     *   §3.8.4.1 ATTENDEE and the RFC 6638 §7 scheduling parameters.
     *
     * Guarantees:
     * 1. Idempotent: `addColumnIfNotExists` and the IF NOT EXISTS creates make a re-run a no-op.
     * 2. Its own transaction, as defense in depth over Room's, so a partial failure rolls back.
     * 3. An `attendees` table whose column set differs from [EXPECTED_ATTENDEES_COLUMNS] is
     *    dropped and recreated; one with the expected columns is kept.
     * 4. Every expected column, table and index is checked inside the transaction, before
     *    `setTransactionSuccessful()`; a missing one throws `IllegalStateException` and rolls
     *    back instead of committing a broken schema.
     *
     * The table and index SQL follows Room's `17.json` schema export (`${TABLE_NAME}`
     * substituted), so Room's schema validation after the migrations passes
     * (`MigrationHashValidationTest`).
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // 1. Column adds
                addColumnIfNotExists(
                    db,
                    "accounts",
                    "calendar_user_addresses",
                    "TEXT NOT NULL DEFAULT '[]'"
                )
                addColumnIfNotExists(db, "events", "organizer_sent_by", "TEXT")
                addColumnIfNotExists(db, "events", "organizer_schedule_status", "TEXT")

                // 2. Drops only a stale leftover, never a table with the expected columns. An
                //    empty set means there is no table.
                val actualColumns = tableColumns(db, "attendees")
                if (actualColumns.isNotEmpty() && actualColumns != EXPECTED_ATTENDEES_COLUMNS) {
                    Log.w(
                        TAG,
                        "Dropping rogue attendees table — column set mismatch " +
                            "(actual=$actualColumns expected=$EXPECTED_ATTENDEES_COLUMNS)"
                    )
                    db.execSQL("DROP TABLE attendees")
                }

                // 3. Table and indexes, Room's 17.json SQL with IF NOT EXISTS
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

                // 4. Validation, before setTransactionSuccessful() so a throw rolls back
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
     * Lists the declared type of each column [MIGRATION_17_18] adds, for its pre-migration shape
     * check.
     */
    private val EXPECTED_V18_COLUMN_TYPES = mapOf(
        Triple("pending_operations", "partstat_only", "INTEGER") to Unit,
        Triple("pending_operations", "partstat_target", "TEXT") to Unit,
        Triple("attendees", "notified_at", "INTEGER") to Unit
    ).keys

    /**
     * Migrates 17 to 18: adds RSVP and invite-notification state. The columns are app-internal
     * queue and dedup state, not RFC wire fields.
     * - `pending_operations.partstat_only` (INTEGER NOT NULL DEFAULT 0): 1 marks a PARTSTAT-only
     *   RSVP write, pushed through `IcsPatcher.patchAttendeeReply`; 0 an ordinary UPDATE.
     * - `pending_operations.partstat_target` (TEXT): the PARTSTAT the operation writes, an
     *   RFC 5545 §3.2.12 value (`ACCEPTED`, `TENTATIVE`, `DECLINED`, `NEEDS-ACTION`) uppercased by
     *   `EventWriter.replyRsvp`. NULL when `partstat_only = 0`.
     * - `attendees.notified_at` (INTEGER epoch millis): when the per-invite system notification
     *   fired, for dedup. NULL = not yet notified.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17], plus a pre-migration shape
     * check: a column that already exists with the wrong type (a forked dev DB) throws
     * `IllegalStateException` before any add ([columnTypeOf] says what a silent skip would cause).
     * `setTransactionSuccessful()` must stay the last statement in the try block, after the
     * validation.
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // 1. Pre-migration shape check
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

                // 2. Column adds
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

                // 3. Validation, before setTransactionSuccessful() so a throw rolls back
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
     * Migrates 18 to 19: adds the scheduling-capability and outbox discovery columns
     * (RFC 6638 §2, §2.1.1).
     * - `accounts.schedule_outbox_url` (TEXT): the principal's CALDAV:schedule-outbox-URL
     *   (RFC 6638 §2.1.1) from PROPFIND. NULL = not yet discovered or no outbox advertised.
     * - `calendars.auto_schedule_supported` (INTEGER): the RFC 6638 §2 "calendar-auto-schedule"
     *   OPTIONS token on the collection. NULL = not yet probed, 0 = not advertised, 1 = advertised.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17]. It has no shape check:
     * both columns are new at v19, so no forked dev DB can have hand-added them. Existing rows take
     * NULL until calendar discovery (`persistSchedulingDiscovery`) fills them in.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                addColumnIfNotExists(db, "accounts", "schedule_outbox_url", "TEXT")
                addColumnIfNotExists(db, "calendars", "auto_schedule_supported", "INTEGER")

                // Validation, before setTransactionSuccessful() so a throw rolls back
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
     * Migrates 19 to 20: adds two nullable `attendees` columns for the client-side
     * `METHOD:REQUEST` outbox send (RFC 6638 §6) on servers that don't schedule themselves.
     * - `itip_request_sequence` (INTEGER): the event SEQUENCE at which a REQUEST was last POSTed
     *   to this attendee; it stops a re-push from sending the same invitation again.
     * - `itip_request_status` (TEXT): the raw per-recipient request-status the outbox returned
     *   (e.g. `2.0;Success`), separate from the server-PUT `schedule_status`.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17].
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                addColumnIfNotExists(db, "attendees", "itip_request_sequence", "INTEGER")
                addColumnIfNotExists(db, "attendees", "itip_request_status", "TEXT")

                // Validation, before setTransactionSuccessful() so a throw rolls back
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
     * Migrates 20 to 21: adds the `pending_cancels` table, one row per guest dropped from an
     * event and awaiting an iTIP CANCEL (RFC 5546 §3.2.2.6).
     *
     * It can't be a column on `attendees`: the removed attendee's row is deleted, so
     * `AttendeesDao.replaceForEvent` would destroy the marker before the CANCEL is delivered.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17]. The CREATE SQL mirrors
     * Room's v21 schema export so Room's schema validation passes.
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

                // Validation, before setTransactionSuccessful() so a throw rolls back
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
     * Migrates 21 to 22: adds the `categories` tag-metadata table.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17]. The CREATE SQL mirrors
     * Room's v22 schema export (NOCASE primary key, nullable color, non-null last_used_at) so
     * Room's schema validation passes.
     *
     * It seeds [Category.DEFAULT_SEEDS] and backfills a row for every tag already on events:
     * - The seed runs before the backfill and both use INSERT OR IGNORE, so a backfilled name
     *   equal to a seeded one in any casing loses: the seeded row keeps its curated color.
     * - The backfill iterates events rows in Kotlin, not a JSON SQL function (none is used
     *   elsewhere, and support varies by SQLite build). Each blob is parsed leniently
     *   ([parseCategoriesBlob]), deduped case-insensitively keeping the first-seen casing, and
     *   inserted with the most recent use and color = NULL, which renders the name-hash color.
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

                // Seeds first; INSERT OR IGNORE leaves an existing row alone on a re-run.
                val seedNow = System.currentTimeMillis()
                for ((name, color) in Category.DEFAULT_SEEDS) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, ?, ?)",
                        arrayOf<Any?>(name, color, seedNow)
                    )
                }

                // The most recent use ranks tag suggestions right after the upgrade.
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

                // Validation, before setTransactionSuccessful() so a throw rolls back
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
                // A case-sensitive PK would split `Work` and `work` into two rows.
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
     * Migrates 22 to 23: adds the `address_books` table of CardDAV collections.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17]. The CREATE SQL is copied
     * from Room's v23 schema export (the table and both index createSql entries) so Room's schema
     * validation passes. There is nothing to backfill; contact sync fills the table.
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

                // Validation, before setTransactionSuccessful() so a throw rolls back
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
     * Migrates 23 to 24: adds `accounts.contact_sync_enabled`, the per-login opt-in for CardDAV
     * contact sync.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17]. With `DEFAULT 0`, matching
     * the entity default, existing logins keep contact sync off until the user opts in.
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

                // Validation, before setTransactionSuccessful() so a throw rolls back
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

    private val RECENT_CHANGES_COLUMNS = listOf(
        "id", "calendar_id", "event_uid", "instance_ts", "event_id", "change_type", "title",
        "start_ts", "end_ts", "is_all_day", "is_recurring", "detected_at", "changed_fields",
        "previous_start_ts", "previous_is_all_day", "dismissed_at"
    )

    private val RECENT_CHANGES_INDEXES = listOf(
        "index_recent_changes_calendar_id",
        "index_recent_changes_event_id",
        "index_recent_changes_detected_at",
        "index_recent_changes_calendar_id_event_uid_instance_ts"
    )

    /**
     * Migrates 24 to 25: adds the `recent_changes` log and `calendars.initial_pull_done`.
     *
     * Same transaction, idempotency and validation as [MIGRATION_16_17], with a shape check
     * first, as in [MIGRATION_17_18]:
     * - An `initial_pull_done` column of another type throws: [addColumnIfNotExists] would skip
     *   it and Room's validation would reject the upgrade anyway.
     * - A `recent_changes` table missing any column (a partial or hand-edited state) is dropped
     *   and rebuilt. Its rows are a derived log, so nothing the user made is lost.
     *
     * The table and index SQL is copied from Room's v25 schema export. A calendar with a stored
     * sync-token or ctag gets `initial_pull_done = 1`, so the first sync after the upgrade records
     * its changes. Every other calendar keeps 0 and its next pull records nothing: one never
     * pulled, and also one already pulled from a server that gives neither a sync-token nor a
     * ctag, since nothing stored tells it apart.
     */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                // 1. Pre-migration shape check
                val flagType = columnTypeOf(db, "calendars", "initial_pull_done")
                if (flagType != null && flagType != "INTEGER") {
                    throw IllegalStateException(
                        "MIGRATION_24_25 pre-migration shape check failed: " +
                            "calendars.initial_pull_done expected INTEGER but found $flagType. " +
                            "Reinstall the app to clear the local DB."
                    )
                }
                if (tableExists(db, "recent_changes") &&
                    !tableColumns(db, "recent_changes").containsAll(RECENT_CHANGES_COLUMNS)
                ) {
                    Log.w(TAG, "MIGRATION_24_25: rebuilding recent_changes with a stale shape")
                    RECENT_CHANGES_INDEXES.forEach { dropIndexIfExists(db, it) }
                    db.execSQL("DROP TABLE recent_changes")
                }

                // 2. Table and indexes
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_changes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`calendar_id` INTEGER NOT NULL, `event_uid` TEXT NOT NULL, " +
                        "`instance_ts` INTEGER NOT NULL, `event_id` INTEGER, " +
                        "`change_type` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`start_ts` INTEGER NOT NULL, `end_ts` INTEGER NOT NULL, " +
                        "`is_all_day` INTEGER NOT NULL, `is_recurring` INTEGER NOT NULL, " +
                        "`detected_at` INTEGER NOT NULL, " +
                        "`changed_fields` TEXT NOT NULL DEFAULT '', " +
                        "`previous_start_ts` INTEGER, `previous_is_all_day` INTEGER, " +
                        "`dismissed_at` INTEGER, " +
                        "FOREIGN KEY(`calendar_id`) REFERENCES `calendars`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`event_id`) REFERENCES `events`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recent_changes_calendar_id` " +
                        "ON `recent_changes` (`calendar_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recent_changes_event_id` " +
                        "ON `recent_changes` (`event_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recent_changes_detected_at` " +
                        "ON `recent_changes` (`detected_at`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_recent_changes_calendar_id_event_uid_instance_ts` " +
                        "ON `recent_changes` (`calendar_id`, `event_uid`, `instance_ts`)"
                )

                // 3. Column add and backfill
                addColumnIfNotExists(
                    db,
                    "calendars",
                    "initial_pull_done",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE calendars SET initial_pull_done = 1 " +
                        "WHERE sync_token IS NOT NULL OR ctag IS NOT NULL"
                )

                // 4. Validation, before setTransactionSuccessful() so a throw rolls back
                val missing = buildList {
                    if (!tableExists(db, "recent_changes")) {
                        add("recent_changes (table)")
                    } else {
                        val columns = tableColumns(db, "recent_changes")
                        RECENT_CHANGES_COLUMNS.filterNot { it in columns }
                            .forEach { add("recent_changes.$it") }
                    }
                    RECENT_CHANGES_INDEXES.filterNot { indexExists(db, it) }.forEach { add(it) }
                    if (!columnExists(db, "calendars", "initial_pull_done")) {
                        add("calendars.initial_pull_done")
                    }
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "MIGRATION_24_25 post-migration validation failed: missing $missing"
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Lists every manual migration in order; add each new one here. */
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
        MIGRATION_23_24,
        MIGRATION_24_25
    )
}
