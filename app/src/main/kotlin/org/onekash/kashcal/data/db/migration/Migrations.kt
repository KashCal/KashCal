package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.PendingOperationTriggers
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
 * Never change what a shipped migration does; add a new one.
 */
object Migrations {

    // ==================== Helper Functions ====================

    /** Returns whether [table] has [column]; the idempotency checks below use it. */
    private fun columnExists(connection: SQLiteConnection, table: String, column: String): Boolean {
        connection.prepare("PRAGMA table_info($table)").use { statement ->
            val nameIndex = statement.columnIndex("name")
            while (statement.step()) {
                if (statement.getText(nameIndex) == column) {
                    return true
                }
            }
        }
        return false
    }

    /** Adds [column] unless it exists, so a re-run after a partial migration can't fail on it. */
    private fun addColumnIfNotExists(
        connection: SQLiteConnection,
        table: String,
        column: String,
        definition: String
    ) {
        if (!columnExists(connection, table, column)) {
            connection.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            Log.d(TAG, "Added column $table.$column")
        } else {
            Log.d(TAG, "Column $table.$column already exists, skipping")
        }
    }

    private fun indexExists(connection: SQLiteConnection, indexName: String): Boolean {
        connection.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name=?").use { statement ->
            statement.bindText(1, indexName)
            return statement.step()
        }
    }

    private fun tableExists(connection: SQLiteConnection, tableName: String): Boolean {
        connection.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
            statement.bindText(1, tableName)
            return statement.step()
        }
    }

    /** Returns [tableName]'s column names, or an empty set if the table doesn't exist. */
    private fun tableColumns(connection: SQLiteConnection, tableName: String): Set<String> {
        val result = mutableSetOf<String>()
        connection.prepare("PRAGMA table_info($tableName)").use { statement ->
            val nameIndex = statement.columnIndex("name")
            while (statement.step()) {
                result.add(statement.getText(nameIndex))
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
    private fun columnTypeOf(connection: SQLiteConnection, table: String, column: String): String? {
        connection.prepare("PRAGMA table_info($table)").use { statement ->
            val nameIndex = statement.columnIndex("name")
            val typeIndex = statement.columnIndex("type")
            while (statement.step()) {
                if (statement.getText(nameIndex) == column) {
                    return statement.textOrNull(typeIndex)?.uppercase()
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
    private fun primaryKeyIsNoCase(connection: SQLiteConnection, table: String): Boolean {
        connection.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
            statement.bindText(1, table)
            if (!statement.step() || statement.isNull(0)) return false
            val sql = statement.getText(0)
            // Matches NOCASE anywhere in the table SQL, e.g. `name` TEXT NOT NULL COLLATE NOCASE
            return Regex("""COLLATE\s+NOCASE""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
        }
    }

    /** Drops [indexName] if it exists, logging whether it did. */
    private fun dropIndexIfExists(connection: SQLiteConnection, indexName: String) {
        if (indexExists(connection, indexName)) {
            connection.execSQL("DROP INDEX $indexName")
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("""
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
            connection.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_ics_subscriptions_url
                ON ics_subscriptions (url)
            """.trimIndent())

            // One subscription per calendar
            connection.execSQL("""
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("""
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

            connection.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_event_id
                ON scheduled_reminders (event_id)
            """.trimIndent())

            connection.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_trigger_time
                ON scheduled_reminders (trigger_time)
            """.trimIndent())

            connection.execSQL("""
                CREATE INDEX IF NOT EXISTS index_scheduled_reminders_status
                ON scheduled_reminders (status)
            """.trimIndent())

            // One reminder per event, occurrence and offset
            connection.execSQL("""
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE pending_operations ADD COLUMN target_url TEXT")

            connection.execSQL("ALTER TABLE pending_operations ADD COLUMN target_calendar_id INTEGER")
        }
    }

    /**
     * Migrates 5 to 6: adds pending_operations.move_phase (0 = DELETE, 1 = CREATE).
     *
     * Each phase gets its own retry budget, so a CREATE that keeps failing after its DELETE
     * succeeded doesn't run out of retries and lose the event.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE pending_operations ADD COLUMN move_phase INTEGER NOT NULL DEFAULT 0")
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE events ADD COLUMN raw_ical TEXT")

            connection.execSQL("ALTER TABLE events ADD COLUMN import_id TEXT")

            connection.execSQL("ALTER TABLE events ADD COLUMN alarm_count INTEGER NOT NULL DEFAULT 0")

            // Existing rows take the master form, import_id = uid
            connection.execSQL("UPDATE events SET import_id = uid WHERE import_id IS NULL")

            connection.execSQL("CREATE INDEX IF NOT EXISTS index_events_import_id ON events (import_id)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_events_calendar_id_import_id ON events (calendar_id, import_id)")

            // Older installs created a partial unique index at database creation. Room can't
            // declare partial indexes in @Index, so its schema validation fails on it ("Found has
            // index that Expected doesn't have").
            dropIndexIfExists(connection, "index_events_uid_calendar_master")

            // Triggers enforce the same constraint, and Room doesn't validate triggers. They stop
            // duplicate master events from iCloud sync (multiple servers may send the same data).
            connection.execSQL("""
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

            connection.execSQL("""
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
        override suspend fun migrate(connection: SQLiteConnection) {
            addColumnIfNotExists(connection, "events", "priority", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(connection, "events", "geo_lat", "REAL")
            addColumnIfNotExists(connection, "events", "geo_lon", "REAL")
            addColumnIfNotExists(connection, "events", "color", "INTEGER")
            addColumnIfNotExists(connection, "events", "url", "TEXT")
            addColumnIfNotExists(connection, "events", "categories", "TEXT")

            // In case 6 to 7 didn't complete
            dropIndexIfExists(connection, "index_events_uid_calendar_master")

            connection.execSQL("CREATE INDEX IF NOT EXISTS index_calendars_is_visible ON calendars (is_visible)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_is_enabled ON accounts (is_enabled)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_ics_subscriptions_enabled ON ics_subscriptions (enabled)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_occurrences_is_cancelled ON occurrences (is_cancelled)")
        }
    }

    /**
     * Migrates 8 to 9: indexes events on (calendar_id, uid, original_instance_time) for
     * `EventsDao.getExceptionByUidAndInstanceTime`, which finds an exception by its server-stable
     * UID and instance time (RFC 5545) instead of a local ID that can go stale.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        private val INDEX_NAME = "index_events_calendar_id_uid_original_instance_time"

        override suspend fun migrate(connection: SQLiteConnection) {
            if (indexExists(connection, INDEX_NAME)) {
                Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                return
            }

            try {
                connection.execSQL("""
                    CREATE INDEX $INDEX_NAME
                    ON events (calendar_id, uid, original_instance_time)
                """.trimIndent())
                Log.d(TAG, "Created index $INDEX_NAME for UID-based exception lookup")
            } catch (e: Exception) {
                // The index only speeds up exception lookups, so a failure doesn't fail the
                // migration.
                Log.e(TAG, "Failed to create index $INDEX_NAME: ${e.message}", e)
            }

            if (indexExists(connection, INDEX_NAME)) {
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

        override suspend fun migrate(connection: SQLiteConnection) {
            if (indexExists(connection, INDEX_NAME)) {
                Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                return
            }

            try {
                val duplicateCount = count(connection, """
                    SELECT COUNT(*) FROM occurrences
                    WHERE id NOT IN (
                        SELECT MIN(id)
                        FROM occurrences
                        GROUP BY event_id, start_ts
                    )
                """.trimIndent()).toInt()

                if (duplicateCount > 0) {
                    Log.i(TAG, "Found $duplicateCount duplicate occurrences to remove")

                    // The unique index can't be created while duplicates exist
                    connection.execSQL("""
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

                connection.execSQL("""
                    CREATE UNIQUE INDEX $INDEX_NAME
                    ON occurrences (event_id, start_ts)
                """.trimIndent())
                Log.i(TAG, "Created unique index $INDEX_NAME")

                if (indexExists(connection, INDEX_NAME)) {
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
        override suspend fun migrate(connection: SQLiteConnection) {
            try {
                // Default 0 marks the rows still to initialize
                addColumnIfNotExists(
                    connection, "pending_operations", "lifetime_reset_at",
                    "INTEGER NOT NULL DEFAULT 0"
                )

                // Only rows still at 0, so a re-run is a no-op
                val updatedCount = changedRows(connection, """
                    UPDATE pending_operations
                    SET lifetime_reset_at = created_at
                    WHERE lifetime_reset_at = 0
                """.trimIndent())

                if (updatedCount > 0) {
                    Log.i(TAG, "Initialized lifetime_reset_at for $updatedCount existing operations")
                }

                // Null unless the operation is FAILED
                addColumnIfNotExists(
                    connection, "pending_operations", "failed_at",
                    "INTEGER"
                )

                if (columnExists(connection, "pending_operations", "lifetime_reset_at") &&
                    columnExists(connection, "pending_operations", "failed_at")) {
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
        override suspend fun migrate(connection: SQLiteConnection) {
            try {
                addColumnIfNotExists(
                    connection, "pending_operations", "source_calendar_id",
                    "INTEGER DEFAULT NULL"
                )

                // Don't backfill source_calendar_id from event.calendarId: a move from A to B
                // sets event.calendarId = B before it queues MOVE(phase 0), so the event names the
                // target, not the source.
                val inFlightMoves = changedRows(connection, """
                    UPDATE pending_operations
                    SET status = 'FAILED',
                        last_error = 'Migration 11->12: MOVE requires retry after upgrade',
                        failed_at = ${System.currentTimeMillis()}
                    WHERE operation = 'MOVE'
                    AND move_phase = 0
                    AND source_calendar_id IS NULL
                    AND status != 'FAILED'
                """.trimIndent())

                if (inFlightMoves > 0) {
                    Log.w(TAG, "Marked $inFlightMoves in-flight MOVE operations as FAILED for retry")
                } else {
                    Log.d(TAG, "No in-flight MOVE operations to migrate")
                }

                // Phase 1 (CREATE) MOVEs select by target_calendar_id and need no source.

                if (columnExists(connection, "pending_operations", "source_calendar_id")) {
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
        override suspend fun migrate(connection: SQLiteConnection) {
            try {
                // Every CalDAV account should have home_set_url from discovery. One without it
                // can't be found by the (provider, email, home_set_url) lookup, so it is logged.
                val nullHomeSetCount = count(
                    connection,
                    "SELECT COUNT(*) FROM accounts WHERE provider = 'CALDAV' AND home_set_url IS NULL"
                ).toInt()
                if (nullHomeSetCount > 0) {
                    Log.w(TAG, "Found $nullHomeSetCount CalDAV account(s) with NULL home_set_url. " +
                        "These accounts may need re-authentication after upgrade.")
                }

                dropIndexIfExists(connection, "index_accounts_provider_email")

                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_provider_email_home_set_url " +
                    "ON accounts (provider, email, home_set_url)"
                )

                if (indexExists(connection, "index_accounts_provider_email_home_set_url")) {
                    Log.d(TAG, "Migration 12->13 completed: unique index updated to include home_set_url")
                } else {
                    Log.w(TAG, "Migration 12->13: index verification FAILED")
                }

                if (indexExists(connection, "index_accounts_provider_email")) {
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
        override suspend fun migrate(connection: SQLiteConnection) {
            try {
                // Step 1: delete orphan exceptions that have a linked counterpart, keeping the
                // linked row (original_event_id IS NOT NULL), which has the FK to the master.
                val step1Deleted = changedRows(connection, """
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
                if (step1Deleted > 0) {
                    Log.d(TAG, "Migration 13->14: dedup step 1 deleted $step1Deleted orphan exceptions with linked counterparts")
                }

                // Step 2: any remaining duplicates (two orphans, two linked to different masters)
                // keep the highest id, the most recently written, per natural key.
                val step2Deleted = changedRows(connection, """
                    DELETE FROM events
                    WHERE original_instance_time IS NOT NULL
                    AND id NOT IN (
                        SELECT MAX(id) FROM events
                        WHERE original_instance_time IS NOT NULL
                        GROUP BY calendar_id, uid, original_instance_time
                    )
                """)
                if (step2Deleted > 0) {
                    Log.d(TAG, "Migration 13->14: dedup step 2 deleted $step2Deleted remaining duplicate exceptions")
                }

                dropIndexIfExists(connection, "index_events_original_event_id_original_instance_time")

                // Recreated non-unique for FK lookups
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time " +
                    "ON events (original_event_id, original_instance_time)"
                )

                dropIndexIfExists(connection, "index_events_calendar_id_uid_original_instance_time")

                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_events_calendar_id_uid_original_instance_time " +
                    "ON events (calendar_id, uid, original_instance_time)"
                )

                if (indexExists(connection, "index_events_calendar_id_uid_original_instance_time")) {
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

        override suspend fun migrate(connection: SQLiteConnection) {
            try {
                addColumnIfNotExists(
                    connection, "pending_operations", "linked_move_id",
                    "TEXT DEFAULT NULL"
                )

                // For the getReadyOperations guard: WHERE linked.linked_move_id = po.linked_move_id
                if (!indexExists(connection, INDEX_NAME)) {
                    connection.execSQL("""
                        CREATE INDEX $INDEX_NAME
                        ON pending_operations(linked_move_id)
                    """.trimIndent())
                    Log.d(TAG, "Created index $INDEX_NAME for linked operation guard query")
                } else {
                    Log.d(TAG, "Index $INDEX_NAME already exists, skipping")
                }

                // Logs the unlinked CREATE and DELETE pairs for one event; they aren't modified.
                val inFlightCrossAccountMoves = count(connection, """
                    SELECT COUNT(DISTINCT po1.event_id) FROM pending_operations po1
                    INNER JOIN pending_operations po2 ON po1.event_id = po2.event_id
                    WHERE po1.operation = 'CREATE'
                      AND po2.operation = 'DELETE'
                      AND po1.status = 'PENDING'
                      AND po2.status = 'PENDING'
                      AND po1.linked_move_id IS NULL
                      AND po2.linked_move_id IS NULL
                """.trimIndent()).toInt()

                if (inFlightCrossAccountMoves > 0) {
                    Log.w(TAG, "Found $inFlightCrossAccountMoves in-flight cross-account move(s) " +
                        "without linkedMoveId. These will use legacy (unlinked) behavior.")
                }

                val columnOk = columnExists(connection, "pending_operations", "linked_move_id")
                val indexOk = indexExists(connection, INDEX_NAME)

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
     * - calendars.is_notification_muted: mute a calendar's notifications (#137).
     * - calendars.local_color_override: the user's color for a CalDAV calendar (#102).
     * - calendars.default_reminder: a calendar's default reminder offset.
     * - events.end_timezone: a separate timezone for the event end (#39).
     *
     * Each is an ALTER TABLE ADD COLUMN, which rewrites no data.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addColumnIfNotExists(connection, "calendars", "is_notification_muted", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(connection, "calendars", "local_color_override", "INTEGER")
            addColumnIfNotExists(connection, "calendars", "default_reminder", "TEXT")

            addColumnIfNotExists(connection, "events", "end_timezone", "TEXT")
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
     * 2. Runs inside the one transaction Room opens around the whole upgrade, so a throw rolls
     *    the database back to the version the upgrade started from.
     * 3. An `attendees` table whose column set differs from [EXPECTED_ATTENDEES_COLUMNS] is
     *    dropped and recreated; one with the expected columns is kept.
     * 4. Every expected column, table and index is checked before the migration returns; a
     *    missing one throws `IllegalStateException`, and Room rolls the transaction back instead
     *    of committing a broken schema.
     *
     * The table and index SQL follows Room's `17.json` schema export (`${TABLE_NAME}`
     * substituted), so Room's schema validation after the migrations passes
     * (`MigrationHashValidationTest`).
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // 1. Column adds
            addColumnIfNotExists(
                connection,
                "accounts",
                "calendar_user_addresses",
                "TEXT NOT NULL DEFAULT '[]'"
            )
            addColumnIfNotExists(connection, "events", "organizer_sent_by", "TEXT")
            addColumnIfNotExists(connection, "events", "organizer_schedule_status", "TEXT")

            // 2. Drops only a stale leftover, never a table with the expected columns. An
            //    empty set means there is no table.
            val actualColumns = tableColumns(connection, "attendees")
            if (actualColumns.isNotEmpty() && actualColumns != EXPECTED_ATTENDEES_COLUMNS) {
                Log.w(
                    TAG,
                    "Dropping rogue attendees table — column set mismatch " +
                        "(actual=$actualColumns expected=$EXPECTED_ATTENDEES_COLUMNS)"
                )
                connection.execSQL("DROP TABLE attendees")
            }

            // 3. Table and indexes, Room's 17.json SQL with IF NOT EXISTS
            connection.execSQL(
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
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_attendees_event_id` " +
                    "ON `attendees` (`event_id`)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_attendees_address` " +
                    "ON `attendees` (`address`)"
            )

            // 4. Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!columnExists(connection, "accounts", "calendar_user_addresses")) {
                    add("accounts.calendar_user_addresses")
                }
                if (!columnExists(connection, "events", "organizer_sent_by")) {
                    add("events.organizer_sent_by")
                }
                if (!columnExists(connection, "events", "organizer_schedule_status")) {
                    add("events.organizer_schedule_status")
                }
                if (!tableExists(connection, "attendees")) add("attendees table")
                if (!indexExists(connection, "index_attendees_event_id")) {
                    add("index_attendees_event_id")
                }
                if (!indexExists(connection, "index_attendees_address")) {
                    add("index_attendees_address")
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_16_17 post-migration validation failed: missing $missing"
                )
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
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // 1. Pre-migration shape check
            val shapeMismatches = mutableListOf<String>()
            for ((table, column, expectedType) in EXPECTED_V18_COLUMN_TYPES) {
                val actual = columnTypeOf(connection, table, column)
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
                connection,
                "pending_operations",
                "partstat_only",
                "INTEGER NOT NULL DEFAULT 0"
            )
            addColumnIfNotExists(
                connection,
                "pending_operations",
                "partstat_target",
                "TEXT"
            )
            addColumnIfNotExists(
                connection,
                "attendees",
                "notified_at",
                "INTEGER"
            )

            // 3. Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!columnExists(connection, "pending_operations", "partstat_only")) {
                    add("pending_operations.partstat_only")
                }
                if (!columnExists(connection, "pending_operations", "partstat_target")) {
                    add("pending_operations.partstat_target")
                }
                if (!columnExists(connection, "attendees", "notified_at")) {
                    add("attendees.notified_at")
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_17_18 post-migration validation failed: missing $missing"
                )
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
        override suspend fun migrate(connection: SQLiteConnection) {
            addColumnIfNotExists(connection, "accounts", "schedule_outbox_url", "TEXT")
            addColumnIfNotExists(connection, "calendars", "auto_schedule_supported", "INTEGER")

            // Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!columnExists(connection, "accounts", "schedule_outbox_url")) {
                    add("accounts.schedule_outbox_url")
                }
                if (!columnExists(connection, "calendars", "auto_schedule_supported")) {
                    add("calendars.auto_schedule_supported")
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_18_19 post-migration validation failed: missing $missing"
                )
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
        override suspend fun migrate(connection: SQLiteConnection) {
            addColumnIfNotExists(connection, "attendees", "itip_request_sequence", "INTEGER")
            addColumnIfNotExists(connection, "attendees", "itip_request_status", "TEXT")

            // Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!columnExists(connection, "attendees", "itip_request_sequence")) {
                    add("attendees.itip_request_sequence")
                }
                if (!columnExists(connection, "attendees", "itip_request_status")) {
                    add("attendees.itip_request_status")
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_19_20 post-migration validation failed: missing $missing"
                )
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
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
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_cancels_event_id` " +
                    "ON `pending_cancels` (`event_id`)"
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_pending_cancels_event_id_recurrence_id_address` " +
                    "ON `pending_cancels` (`event_id`, `recurrence_id`, `address`)"
            )

            // Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!tableExists(connection, "pending_cancels")) add("pending_cancels (table)")
                else {
                    for (col in listOf(
                        "event_id", "recurrence_id", "address",
                        "schedule_agent", "schedule_status", "sequence", "attempt_count"
                    )) {
                        if (!columnExists(connection, "pending_cancels", col)) {
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `categories` (" +
                    "`name` TEXT NOT NULL COLLATE NOCASE, " +
                    "`color` INTEGER, " +
                    "`last_used_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`name`))"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_categories_last_used_at` " +
                    "ON `categories` (`last_used_at`)"
            )

            // Seeds first; INSERT OR IGNORE leaves an existing row alone on a re-run.
            val seedNow = System.currentTimeMillis()
            for ((name, color) in Category.DEFAULT_SEEDS) {
                execSQL(
                    connection,
                    "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, ?, ?)",
                    arrayOf<Any?>(name, color, seedNow)
                )
            }

            // The most recent use ranks tag suggestions right after the upgrade.
            data class Backfilled(val display: String, var lastUsed: Long)
            val byKey = LinkedHashMap<String, Backfilled>()
            connection.prepare(
                "SELECT categories, COALESCE(local_modified_at, start_ts) AS recency " +
                    "FROM events WHERE categories IS NOT NULL AND categories != ''"
            ).use { statement ->
                val categoriesIndex = statement.columnIndex("categories")
                val recencyIndex = statement.columnIndex("recency")
                while (statement.step()) {
                    val blob = statement.textOrNull(categoriesIndex)
                    val recency = if (statement.isNull(recencyIndex)) 0L else statement.getLong(recencyIndex)
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
                execSQL(
                    connection,
                    "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, NULL, ?)",
                    arrayOf<Any?>(entry.display, entry.lastUsed)
                )
            }

            // Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!tableExists(connection, "categories")) {
                    add("categories (table)")
                } else {
                    for (col in listOf("name", "color", "last_used_at")) {
                        if (!columnExists(connection, "categories", col)) add("categories.$col")
                    }
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_21_22 post-migration validation failed: missing $missing"
                )
            }
            // A case-sensitive PK would split `Work` and `work` into two rows.
            if (!primaryKeyIsNoCase(connection, "categories")) {
                throw IllegalStateException(
                    "MIGRATION_21_22 post-migration validation failed: " +
                        "categories.name is not COLLATE NOCASE"
                )
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
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `address_books` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`account_id` INTEGER NOT NULL, `url` TEXT NOT NULL, " +
                    "`display_name` TEXT NOT NULL, `description` TEXT, " +
                    "`vcard_version` TEXT NOT NULL, `ctag` TEXT, `sync_token` TEXT, " +
                    "`is_read_only` INTEGER NOT NULL, `is_sync_enabled` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_address_books_account_id` " +
                    "ON `address_books` (`account_id`)"
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_address_books_account_id_url` " +
                    "ON `address_books` (`account_id`, `url`)"
            )

            // Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!tableExists(connection, "address_books")) {
                    add("address_books (table)")
                } else {
                    for (col in listOf("id", "account_id", "url", "vcard_version", "sync_token")) {
                        if (!columnExists(connection, "address_books", col)) add("address_books.$col")
                    }
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_22_23 post-migration validation failed: missing $missing"
                )
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
        override suspend fun migrate(connection: SQLiteConnection) {
            addColumnIfNotExists(
                connection,
                "accounts",
                "contact_sync_enabled",
                "INTEGER NOT NULL DEFAULT 0"
            )

            // Validation; a throw rolls back Room's transaction
            if (!columnExists(connection, "accounts", "contact_sync_enabled")) {
                throw IllegalStateException(
                    "MIGRATION_23_24 post-migration validation failed: " +
                        "missing accounts.contact_sync_enabled"
                )
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
        override suspend fun migrate(connection: SQLiteConnection) {
            // 1. Pre-migration shape check
            val flagType = columnTypeOf(connection, "calendars", "initial_pull_done")
            if (flagType != null && flagType != "INTEGER") {
                throw IllegalStateException(
                    "MIGRATION_24_25 pre-migration shape check failed: " +
                        "calendars.initial_pull_done expected INTEGER but found $flagType. " +
                        "Reinstall the app to clear the local DB."
                )
            }
            if (tableExists(connection, "recent_changes") &&
                !tableColumns(connection, "recent_changes").containsAll(RECENT_CHANGES_COLUMNS)
            ) {
                Log.w(TAG, "MIGRATION_24_25: rebuilding recent_changes with a stale shape")
                RECENT_CHANGES_INDEXES.forEach { dropIndexIfExists(connection, it) }
                connection.execSQL("DROP TABLE recent_changes")
            }

            // 2. Table and indexes
            connection.execSQL(
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
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recent_changes_calendar_id` " +
                    "ON `recent_changes` (`calendar_id`)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recent_changes_event_id` " +
                    "ON `recent_changes` (`event_id`)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recent_changes_detected_at` " +
                    "ON `recent_changes` (`detected_at`)"
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_recent_changes_calendar_id_event_uid_instance_ts` " +
                    "ON `recent_changes` (`calendar_id`, `event_uid`, `instance_ts`)"
            )

            // 3. Column add and backfill
            addColumnIfNotExists(
                connection,
                "calendars",
                "initial_pull_done",
                "INTEGER NOT NULL DEFAULT 0"
            )
            connection.execSQL(
                "UPDATE calendars SET initial_pull_done = 1 " +
                    "WHERE sync_token IS NOT NULL OR ctag IS NOT NULL"
            )

            // 4. Validation; a throw rolls back Room's transaction
            val missing = buildList {
                if (!tableExists(connection, "recent_changes")) {
                    add("recent_changes (table)")
                } else {
                    val columns = tableColumns(connection, "recent_changes")
                    RECENT_CHANGES_COLUMNS.filterNot { it in columns }
                        .forEach { add("recent_changes.$it") }
                }
                RECENT_CHANGES_INDEXES.filterNot { indexExists(connection, it) }.forEach { add(it) }
                if (!columnExists(connection, "calendars", "initial_pull_done")) {
                    add("calendars.initial_pull_done")
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_24_25 post-migration validation failed: missing $missing"
                )
            }
        }
    }

    /** The `events` columns [MIGRATION_25_26] adds, with the SQLite type each must have. */
    private val IMPORT_COLUMNS = linkedMapOf(
        "import_source" to "TEXT",
        "itip_dtstamp" to "INTEGER"
    )

    /**
     * Migrates 25 to 26: adds `events.import_source` and `events.itip_dtstamp` for ICS file
     * imports.
     *
     * Same transaction, idempotency and validation as [MIGRATION_24_25]. A same-named column of
     * another type throws before anything changes: [addColumnIfNotExists] would skip it and Room's
     * validation would reject the upgrade anyway. Both columns start null on every existing row:
     * events imported before v26 carry fresh UIDs, so nothing stored can mark them.
     */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // 1. Pre-migration shape check
            for ((column, type) in IMPORT_COLUMNS) {
                val found = columnTypeOf(connection, "events", column)
                if (found != null && found != type) {
                    throw IllegalStateException(
                        "MIGRATION_25_26 pre-migration shape check failed: " +
                            "events.$column expected $type but found $found. " +
                            "Reinstall the app to clear the local DB."
                    )
                }
            }

            // 2. Column adds; no backfill
            for ((column, type) in IMPORT_COLUMNS) {
                addColumnIfNotExists(connection, "events", column, type)
            }

            // 3. Validation; a throw rolls back Room's transaction
            val missing = IMPORT_COLUMNS.keys.filterNot { columnExists(connection, "events", it) }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_25_26 post-migration validation failed: missing events.$missing"
                )
            }
        }
    }

    // An operation the push can run without its event row: a DELETE carrying the server URL and
    // the calendar it is scoped to (PushStrategy.pushForCalendar). PendingOperationTriggers keeps
    // the same ones.
    private const val SELF_CONTAINED_DELETE =
        "operation = 'DELETE' AND target_url IS NOT NULL AND source_calendar_id IS NOT NULL"

    private const val EVENT_MISSING =
        "NOT EXISTS (SELECT 1 FROM events e WHERE e.id = pending_operations.event_id)"

    // A cross-account DELETE whose CREATE lost its row. Without the CREATE the push would treat
    // it as ready and delete the original, so it goes with the CREATE.
    private const val LINKED_TO_ORPHANED_CREATE = """
        operation = 'DELETE' AND linked_move_id IS NOT NULL AND $EVENT_MISSING
        AND linked_move_id IN (
            SELECT c.linked_move_id FROM pending_operations c
            WHERE c.operation = 'CREATE' AND c.linked_move_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM events e WHERE e.id = c.event_id)
        )
    """

    // The v27 trigger as this migration installs it. A later change to
    // PendingOperationTriggers must not change what a v26 upgrade runs, so this copy stays fixed.
    private const val EVENT_DELETE_TRIGGER_V27 = "trigger_pending_operations_follow_event_delete"
    private val EVENT_DELETE_TRIGGER_V27_SQL = """
        CREATE TRIGGER $EVENT_DELETE_TRIGGER_V27
        AFTER DELETE ON events
        BEGIN
            DELETE FROM pending_operations
            WHERE event_id = OLD.id
              AND operation = 'DELETE'
              AND linked_move_id IS NOT NULL
              AND linked_move_id IN (
                  SELECT linked_move_id FROM pending_operations
                  WHERE event_id = OLD.id AND operation = 'CREATE' AND linked_move_id IS NOT NULL
              );
            DELETE FROM pending_operations
            WHERE event_id = OLD.id
              AND NOT (operation = 'DELETE' AND target_url IS NOT NULL AND source_calendar_id IS NOT NULL);
        END
    """.trimIndent()
    private val EVENT_DELETE_TRIGGER_V27_COLUMNS =
        listOf("event_id", "operation", "target_url", "source_calendar_id", "linked_move_id")

    /**
     * v26 to v27: creates the [PendingOperationTriggers] trigger and
     * removes the queued operations it would have removed: those whose event row is gone and
     * that the push can't run without it, plus the DELETE linked to such a CREATE.
     *
     * It touches nothing else. Operations whose event exists are copied to a temp table first and
     * compared with EXCEPT afterwards; no step reads a whole-table value, which Android's cursor
     * window (2 MB) could refuse. It throws on a missing table or column, or when the result fails
     * that comparison (the app has no destructive fallback, so a throw keeps the database from
     * opening; Room's rollback leaves it at the version the upgrade started from). A re-run
     * removes nothing more.
     */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // 1. Pre-migration shape check
            val missingTables = listOf("events", "pending_operations").filterNot { tableExists(connection, it) }
            val missingColumns = EVENT_DELETE_TRIGGER_V27_COLUMNS
                .filterNot { columnExists(connection, "pending_operations", it) }
            if (missingTables.isNotEmpty() || missingColumns.isNotEmpty()) {
                throw IllegalStateException(
                    "MIGRATION_26_27 pre-migration shape check failed: missing tables $missingTables, " +
                        "pending_operations columns $missingColumns. Reinstall the app to clear the local DB."
                )
            }

            // 2. What must not change, and what must remain without a row
            connection.execSQL("DROP TABLE IF EXISTS temp.po_keep")
            connection.execSQL(
                "CREATE TEMP TABLE po_keep AS SELECT * FROM pending_operations " +
                    "WHERE event_id IN (SELECT id FROM events)"
            )
            val eventsBefore = count(connection, "SELECT COUNT(*) FROM events")
            val orphansKept = count(
                connection,
                "SELECT COUNT(*) FROM pending_operations WHERE $EVENT_MISSING AND $SELF_CONTAINED_DELETE " +
                    "AND NOT ($LINKED_TO_ORPHANED_CREATE)"
            )
            val removedByType = mutableMapOf<String, Long>()
            connection.prepare(
                "SELECT operation, COUNT(*) FROM pending_operations WHERE ($LINKED_TO_ORPHANED_CREATE) " +
                    "OR ($EVENT_MISSING AND NOT ($SELF_CONTAINED_DELETE)) GROUP BY operation"
            ).use { c -> while (c.step()) removedByType[c.getText(0)] = c.getLong(1) }

            // 3. Cleanup: the linked DELETEs first, while their CREATEs still identify them
            connection.execSQL("DELETE FROM pending_operations WHERE $LINKED_TO_ORPHANED_CREATE")
            connection.execSQL("DELETE FROM pending_operations WHERE $EVENT_MISSING AND NOT ($SELF_CONTAINED_DELETE)")

            // 4. The trigger, so later deletes keep the queue clean
            connection.execSQL("DROP TRIGGER IF EXISTS $EVENT_DELETE_TRIGGER_V27")
            connection.execSQL(EVENT_DELETE_TRIGGER_V27_SQL)

            // 5. Validation; a throw rolls back Room's transaction
            val keptNow = count(connection, "SELECT COUNT(*) FROM pending_operations WHERE event_id IN (SELECT id FROM events)")
            val keptBefore = count(connection, "SELECT COUNT(*) FROM temp.po_keep")
            val changed = count(
                connection,
                "SELECT COUNT(*) FROM (SELECT * FROM temp.po_keep EXCEPT SELECT * FROM pending_operations)"
            )
            val orphansNow = count(connection, "SELECT COUNT(*) FROM pending_operations WHERE $EVENT_MISSING")
            val eventsNow = count(connection, "SELECT COUNT(*) FROM events")
            if (keptNow != keptBefore || changed != 0L || orphansNow != orphansKept || eventsNow != eventsBefore ||
                count(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = '$EVENT_DELETE_TRIGGER_V27'") != 1L
            ) {
                throw IllegalStateException(
                    "MIGRATION_26_27 post-migration validation failed: kept $keptNow of $keptBefore, " +
                        "changed $changed, orphans $orphansNow (expected $orphansKept), " +
                        "events $eventsNow of $eventsBefore"
                )
            }
            connection.execSQL("DROP TABLE temp.po_keep")

            Log.i(TAG, "MIGRATION_26_27 removed queued operations whose event is gone: $removedByType")
        }
    }

    private const val FTS_TABLE_V27 = "events_fts"

    /** The shadow tables FTS4 keeps for [FTS_TABLE_V27]; dropping the virtual table drops them. */
    private val FTS_SHADOW_TABLES_V27 = listOf("content", "segments", "segdir", "docsize", "stat").map { "${FTS_TABLE_V27}_$it" }

    /**
     * v27 to v28: drops the FTS4 search index, since search matches events with LIKE on the
     * platform's rule ([org.onekash.kashcal.data.db.dao.EventSearchSql]).
     *
     * Drops every trigger whose SQL names `events_fts` (Room's four sync triggers and any other),
     * then the virtual table, then any FTS4 shadow table left behind by a virtual table that was
     * already gone. A database without the index is a valid start, so a re-run does nothing.
     * Before returning it checks that nothing in sqlite_master names `events_fts` and that the
     * events count is unchanged; it throws only then, and Room's rollback leaves the database at
     * the version the upgrade started from.
     */
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val eventsBefore = count(connection, "SELECT COUNT(*) FROM events")
            val triggers = mutableListOf<String>()
            connection.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND instr(sql, ?) > 0"
            ).use { c ->
                c.bindText(1, FTS_TABLE_V27)
                while (c.step()) triggers += c.getText(0)
            }
            triggers.forEach { connection.execSQL("DROP TRIGGER IF EXISTS `$it`") }
            connection.execSQL("DROP TABLE IF EXISTS `$FTS_TABLE_V27`")
            FTS_SHADOW_TABLES_V27.forEach { connection.execSQL("DROP TABLE IF EXISTS `$it`") }

            val leftOver = count(
                connection,
                // instr, not LIKE: '_' in the name would be a LIKE wildcard.
                "SELECT COUNT(*) FROM sqlite_master WHERE instr(name, '$FTS_TABLE_V27') > 0 " +
                    "OR instr(sql, '$FTS_TABLE_V27') > 0"
            )
            val eventsNow = count(connection, "SELECT COUNT(*) FROM events")
            if (leftOver != 0L || eventsNow != eventsBefore) {
                throw IllegalStateException(
                    "MIGRATION_27_28 post-migration validation failed: $leftOver objects still name " +
                        "$FTS_TABLE_V27, events $eventsNow of $eventsBefore"
                )
            }

            Log.i(TAG, "MIGRATION_27_28 dropped the search index and ${triggers.size} triggers")
        }
    }

    /**
     * v28 to v29: moves each stored ICS out of `events.raw_ical` into `event_ics`, once per
     * calendar object resource (RFC 4791 §4.1), split into pieces of 262,144 characters (Unicode
     * code points, as `substr` counts them; `EventIcsStore.PART_CHARS`), then rebuilds `events`
     * without the column, so no row can outgrow the 2 MB cursor window again.
     *
     * Owners: a series row with an ICS keeps it; a pull placeholder series (no URL, the synthetic
     * marker in `extra_properties`; JSON1 is missing on API 31, hence the LIKE) without one takes
     * its lowest-id occurrence's; an occurrence keeps its own pieces only when its text differs
     * from its series', or its series has none. An occurrence equal to its series' gets none and
     * reads the series'. An empty text, or one holding U+0000 (SQLite's `length` and `substr`
     * stop there, and RFC 5545 §3.1 allows no such character), counts as none.
     *
     * The copy is checked before anything is dropped: each piece equals its `substr` slice, the
     * piece count and the UTF-8 byte length match, and every occurrence without pieces had its
     * series' text. The rebuild follows SQLite's "Making Other Kinds Of Table Schema Changes":
     * the index and trigger SQL on `events` is saved from `sqlite_master`, a new table without
     * `raw_ical` is filled by column name, the old one dropped, the new one renamed, and the saved
     * SQL run again. Foreign keys must be off (Room turns them on only after the migrations), the
     * `PRAGMA foreign_key_check` rows must be the same before and after (old violations stay as
     * they were), and the `sqlite_sequence` value for `events` is carried over, so a new row
     * never takes the id of a deleted one a queued DELETE still names. Any failure throws, and
     * Room's rollback leaves the database at v28.
     */
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `event_ics` (`event_id` INTEGER NOT NULL, `part_index` INTEGER NOT NULL, " +
                    "`content` TEXT NOT NULL, PRIMARY KEY(`event_id`, `part_index`), FOREIGN KEY(`event_id`) " +
                    "REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            connection.execSQL("DELETE FROM `event_ics`")

            // Each owner and the row whose raw_ical it takes.
            connection.execSQL("DROP TABLE IF EXISTS temp.ics_owner")
            connection.execSQL(
                "CREATE TEMP TABLE ics_owner (owner_id INTEGER PRIMARY KEY NOT NULL, source_id INTEGER NOT NULL)"
            )
            connection.execSQL(
                "INSERT INTO temp.ics_owner (owner_id, source_id) " +
                    "SELECT id, id FROM events WHERE original_event_id IS NULL " +
                    "AND raw_ical IS NOT NULL AND raw_ical != '' AND instr(CAST(raw_ical AS BLOB), x'00') = 0"
            )
            connection.execSQL(
                "INSERT INTO temp.ics_owner (owner_id, source_id) " +
                    "SELECT owner_id, source_id FROM (SELECT m.id AS owner_id, " +
                    "(SELECT o.id FROM events o WHERE o.original_event_id = m.id " +
                    "AND o.raw_ical IS NOT NULL AND o.raw_ical != '' AND instr(CAST(o.raw_ical AS BLOB), x'00') = 0 " +
                    "ORDER BY o.id LIMIT 1) AS source_id " +
                    "FROM events m WHERE m.original_event_id IS NULL AND m.caldav_url IS NULL " +
                    "AND m.extra_properties LIKE '%\"X-KASHCAL-SYNTHETIC-MASTER\":\"true\"%' " +
                    "AND m.id NOT IN (SELECT owner_id FROM temp.ics_owner)) WHERE source_id IS NOT NULL"
            )
            connection.execSQL(
                "INSERT INTO temp.ics_owner (owner_id, source_id) " +
                    "SELECT e.id, e.id FROM events e WHERE e.original_event_id IS NOT NULL " +
                    "AND e.raw_ical IS NOT NULL AND e.raw_ical != '' AND instr(CAST(e.raw_ical AS BLOB), x'00') = 0 " +
                    "AND e.raw_ical IS NOT (SELECT s.raw_ical FROM temp.ics_owner so JOIN events s ON s.id = so.source_id " +
                    "WHERE so.owner_id = e.original_event_id)"
            )

            connection.execSQL(
                "INSERT INTO `event_ics` (event_id, part_index, content) " +
                    "WITH RECURSIVE split(event_id, part_index, rest) AS (" +
                    "SELECT o.owner_id, 0, e.raw_ical FROM temp.ics_owner o JOIN events e ON e.id = o.source_id " +
                    "UNION ALL " +
                    "SELECT event_id, part_index + 1, substr(rest, 262145) FROM split WHERE length(rest) > 262144" +
                    ") SELECT event_id, part_index, substr(rest, 1, 262144) FROM split"
            )

            val wrongCount = count(
                connection,
                "SELECT COUNT(*) FROM temp.ics_owner o JOIN events e ON e.id = o.source_id " +
                    "WHERE (SELECT COUNT(*) FROM `event_ics` p WHERE p.event_id = o.owner_id) " +
                    "!= (length(e.raw_ical) + 262143) / 262144"
            )
            val wrongPiece = count(
                connection,
                "SELECT COUNT(*) FROM `event_ics` p JOIN temp.ics_owner o ON o.owner_id = p.event_id " +
                    "JOIN events e ON e.id = o.source_id " +
                    "WHERE p.content IS NOT substr(e.raw_ical, p.part_index * 262144 + 1, 262144)"
            )
            val wrongBytes = count(
                connection,
                "SELECT COUNT(*) FROM temp.ics_owner o JOIN events e ON e.id = o.source_id " +
                    "WHERE (SELECT SUM(length(CAST(p.content AS BLOB))) FROM `event_ics` p WHERE p.event_id = o.owner_id) " +
                    "IS NOT length(CAST(e.raw_ical AS BLOB))"
            )
            val lostOccurrence = count(
                connection,
                "SELECT COUNT(*) FROM events e WHERE e.original_event_id IS NOT NULL " +
                    "AND e.raw_ical IS NOT NULL AND e.raw_ical != '' AND instr(CAST(e.raw_ical AS BLOB), x'00') = 0 " +
                    "AND e.id NOT IN (SELECT owner_id FROM temp.ics_owner) " +
                    "AND e.raw_ical IS NOT (SELECT s.raw_ical FROM temp.ics_owner so JOIN events s ON s.id = so.source_id " +
                    "WHERE so.owner_id = e.original_event_id)"
            )
            if (wrongCount != 0L || wrongPiece != 0L || wrongBytes != 0L || lostOccurrence != 0L) {
                throw IllegalStateException(
                    "MIGRATION_28_29 copy check failed: $wrongCount owners with the wrong piece count, " +
                        "$wrongPiece pieces unlike their text, $wrongBytes owners with other byte lengths, " +
                        "$lostOccurrence occurrences without their text"
                )
            }
            val owners = count(connection, "SELECT COUNT(*) FROM temp.ics_owner")
            val pieces = count(connection, "SELECT COUNT(*) FROM `event_ics`")
            val withNul = count(
                connection,
                "SELECT COUNT(*) FROM events WHERE raw_ical IS NOT NULL AND instr(CAST(raw_ical AS BLOB), x'00') > 0"
            )
            connection.execSQL("DROP TABLE temp.ics_owner")

            // The rebuild. With foreign keys on, dropping events would cascade into every child.
            // Violations as sorted "table rowid parent fkid" lines.
            fun foreignKeyViolations(): List<String> = buildList {
                connection.prepare("PRAGMA foreign_key_check").use { c ->
                    while (c.step()) {
                        add((0 until c.getColumnCount()).joinToString(" ") { if (c.isNull(it)) "null" else c.getText(it) })
                    }
                }
            }.sorted()
            if (count(connection, "PRAGMA foreign_keys") != 0L) {
                throw IllegalStateException("MIGRATION_28_29 needs foreign keys off to rebuild events")
            }
            val violationsBefore = foreignKeyViolations()
            val sequenceBefore = connection.prepare("SELECT seq FROM sqlite_sequence WHERE name = 'events'").use {
                if (it.step()) it.getLong(0) else null
            }
            val eventsBefore = count(connection, "SELECT COUNT(*) FROM events")
            val savedSql = mutableListOf<String>()
            connection.prepare(
                "SELECT sql FROM sqlite_master WHERE tbl_name = 'events' AND type IN ('index', 'trigger') " +
                    "AND sql IS NOT NULL ORDER BY type, name"
            ).use { while (it.step()) savedSql += it.getText(0) }

            connection.execSQL("DROP TABLE IF EXISTS `events_new`")
            connection.execSQL(
                "CREATE TABLE `events_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, " +
                    "`import_id` TEXT, `calendar_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `location` TEXT, " +
                    "`description` TEXT, `start_ts` INTEGER NOT NULL, `end_ts` INTEGER NOT NULL, `timezone` TEXT, " +
                    "`end_timezone` TEXT, `is_all_day` INTEGER NOT NULL DEFAULT 0, " +
                    "`status` TEXT NOT NULL DEFAULT 'CONFIRMED', `transp` TEXT NOT NULL DEFAULT 'OPAQUE', " +
                    "`classification` TEXT NOT NULL DEFAULT 'PUBLIC', `organizer_email` TEXT, `organizer_name` TEXT, " +
                    "`organizer_sent_by` TEXT, `organizer_schedule_status` TEXT, `rrule` TEXT, `rdate` TEXT, " +
                    "`exdate` TEXT, `duration` TEXT, `original_event_id` INTEGER, `original_instance_time` INTEGER, " +
                    "`original_sync_id` TEXT, `reminders` TEXT, `alarm_count` INTEGER NOT NULL DEFAULT 0, " +
                    "`extra_properties` TEXT, `dtstamp` INTEGER NOT NULL, `caldav_url` TEXT, `etag` TEXT, " +
                    "`sequence` INTEGER NOT NULL DEFAULT 0, `sync_status` TEXT NOT NULL DEFAULT 'SYNCED', " +
                    "`last_sync_error` TEXT, `sync_retry_count` INTEGER NOT NULL DEFAULT 0, `local_modified_at` INTEGER, " +
                    "`server_modified_at` INTEGER, `import_source` TEXT, `itip_dtstamp` INTEGER, " +
                    "`priority` INTEGER NOT NULL DEFAULT 0, `geo_lat` REAL, `geo_lon` REAL, `color` INTEGER, " +
                    "`url` TEXT, `categories` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`calendar_id`) REFERENCES `calendars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`original_event_id`) REFERENCES `events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            val kept = tableColumns(connection, "events_new").filter { it in tableColumns(connection, "events") }
                .joinToString(", ") { "`$it`" }
            connection.execSQL("INSERT INTO `events_new` ($kept) SELECT $kept FROM `events`")
            connection.execSQL("DROP TABLE `events`")
            connection.execSQL("ALTER TABLE `events_new` RENAME TO `events`")
            savedSql.forEach { connection.execSQL(it) }
            if (sequenceBefore != null) {
                if (count(connection, "SELECT COUNT(*) FROM sqlite_sequence WHERE name = 'events'") == 0L) {
                    execSQL(connection, "INSERT INTO sqlite_sequence (name, seq) VALUES ('events', ?)", arrayOf(sequenceBefore))
                } else {
                    execSQL(connection, "UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = 'events'", arrayOf(sequenceBefore))
                }
            }

            val violationsAfter = foreignKeyViolations()
            val eventsAfter = count(connection, "SELECT COUNT(*) FROM events")
            if (violationsAfter != violationsBefore || eventsAfter != eventsBefore || columnExists(connection, "events", "raw_ical")) {
                throw IllegalStateException(
                    "MIGRATION_28_29 rebuild check failed: events $eventsAfter of $eventsBefore, " +
                        "foreign key violations ${violationsBefore.size} before and ${violationsAfter.size} after"
                )
            }

            Log.i(TAG, "MIGRATION_28_29 stored $owners ICS texts in $pieces pieces; $withNul with U+0000 left out; " +
                "rebuilt events (${savedSql.size} indexes and triggers)")
        }
    }

    /** Returns the first column of [sql]'s first row as a number, or 0 when it has no row. */
    private fun count(connection: SQLiteConnection, sql: String): Long =
        connection.prepare(sql).use { if (it.step()) it.getLong(0) else 0L }

    /**
     * Runs the UPDATE or DELETE [sql] and returns the rows it changed; rows that triggers change
     * aren't counted.
     */
    private fun changedRows(connection: SQLiteConnection, sql: String): Int {
        connection.execSQL(sql)
        return count(connection, "SELECT changes()").toInt()
    }

    /**
     * Runs [sql] with [args] bound in order: null as NULL, a String as text, an Int or Long as an
     * integer.
     */
    private fun execSQL(connection: SQLiteConnection, sql: String, args: Array<Any?>) {
        connection.prepare(sql).use { statement ->
            args.forEachIndexed { i, arg ->
                when (arg) {
                    null -> statement.bindNull(i + 1)
                    is String -> statement.bindText(i + 1, arg)
                    is Int -> statement.bindLong(i + 1, arg.toLong())
                    is Long -> statement.bindLong(i + 1, arg)
                    else -> throw IllegalArgumentException("unsupported bind type ${arg::class}")
                }
            }
            statement.step()
        }
    }

    /** Returns the index of the result column [name]; it throws when there is none. */
    private fun SQLiteStatement.columnIndex(name: String): Int =
        getColumnNames().indexOf(name).also { require(it >= 0) { "no column $name" } }

    private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

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
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29
    )
}
