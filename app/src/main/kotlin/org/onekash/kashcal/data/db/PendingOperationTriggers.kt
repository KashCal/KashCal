package org.onekash.kashcal.data.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Keeps `pending_operations` in step with `events`: deleting an event row, on any route (a user
 * delete, pull, lookback cleanup, a calendar or account removal and its FK cascades), drops the
 * queued operations that need that row.
 *
 * Kept: a self-contained DELETE, one with `target_url` and `source_calendar_id`, which the push
 * runs without the row (a move's DELETE of the original). Dropped first: a DELETE linked by
 * `linked_move_id` to a CREATE on the deleted row. Once no PENDING CREATE shares its id the push
 * treats the linked DELETE as ready
 * ([org.onekash.kashcal.data.db.dao.PendingOperationsDao.getReadyOperations]), so keeping it
 * would delete the original after its copy is gone.
 *
 * Not a foreign key: the self-contained DELETEs must outlive their row. The trigger lives in
 * `sqlite_master`, outside Room's schema check, so a migration that rebuilds `events` or
 * `pending_operations` must drop it first and recreate it after: dropping `events` drops it, and
 * the rename step of a `pending_operations` rebuild fails while it names the dropped table.
 * Migrations keep their own copy of the SQL; a change here needs a migration that installs it.
 * `MigrationGoldenTest` checks the upgraded trigger matches a fresh install's.
 * `EventDeleteDropsQueuedOperationsTest` covers the routes.
 */
object PendingOperationTriggers {

    const val EVENT_DELETE_TRIGGER = "trigger_pending_operations_follow_event_delete"

    private val CREATE_SQL = """
        CREATE TRIGGER $EVENT_DELETE_TRIGGER
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

    /** Creates the trigger, replacing any copy with another definition. */
    fun create(connection: SQLiteConnection) {
        connection.execSQL("DROP TRIGGER IF EXISTS $EVENT_DELETE_TRIGGER")
        connection.execSQL(CREATE_SQL)
    }

    /** Returns a callback that creates only this trigger, for tests that build Room directly. */
    fun callback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            create(connection)
        }
    }
}
