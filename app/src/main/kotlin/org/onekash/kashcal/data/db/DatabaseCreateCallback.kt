package org.onekash.kashcal.data.db

import android.util.Log
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.onekash.kashcal.data.db.entity.Category
import org.onekash.kashcal.data.db.migration.Migrations

/**
 * Creates the master-uniqueness triggers, the [PendingOperationTriggers] trigger and the starter
 * tags on a fresh install. Upgrades get them from [Migrations.ALL_MIGRATIONS] instead.
 * [kashCalProductionConfig] applies it, so production and the tests that build through that
 * configuration get the same objects; `FreshDatabaseInvariantsTest` checks them.
 *
 * Triggers stand in for a partial unique index: Room can't declare one in `@Index`, so its
 * schema validation fails on it. They enforce one master per UID in a calendar (RFC 4791 §4.1),
 * which stops duplicate masters during iCloud sync (multiple servers may send the same data).
 * Exceptions share the master's UID (`original_event_id IS NOT NULL`) and pass.
 */
object DatabaseCreateCallback : RoomDatabase.Callback() {

    private const val TAG = "DatabaseCreateCallback"

    override suspend fun onCreate(connection: SQLiteConnection) {
        Log.d(TAG, "Creating triggers and starter tags")
        createMasterEventUniqueTriggers(connection)
        PendingOperationTriggers.create(connection)
        seedDefaultCategories(connection)
    }

    /**
     * Seeds the curated starter tags so a new user gets the same [Category.DEFAULT_SEEDS] an
     * upgrading user gets from `MIGRATION_21_22`. `INSERT OR IGNORE` keeps it idempotent and
     * leaves an existing row for the same name alone.
     */
    private fun seedDefaultCategories(connection: SQLiteConnection) {
        val now = System.currentTimeMillis()
        for ((name, color) in Category.DEFAULT_SEEDS) {
            connection.prepare(
                "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, ?, ?)"
            ).use { statement ->
                statement.bindText(1, name)
                statement.bindLong(2, color.toLong())
                statement.bindLong(3, now)
                statement.step()
            }
        }
    }

    /** Creates the triggers that keep (uid, calendar_id) unique among masters. */
    private fun createMasterEventUniqueTriggers(connection: SQLiteConnection) {
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
