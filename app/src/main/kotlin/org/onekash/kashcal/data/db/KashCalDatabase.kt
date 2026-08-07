package org.onekash.kashcal.data.db

import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import org.onekash.kashcal.data.db.converter.Converters
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AddressBookDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.CategoryDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.PendingCancelsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.AddressBook
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Category
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.EventFts
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.PendingCancel
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.db.entity.SyncLog

/**
 * KashCal Room Database.
 *
 * Central database for calendar data with offline-first architecture.
 * Supports CalDAV sync with iCloud and local calendars.
 *
 * Tables:
 * - accounts: CalDAV account credentials
 * - calendars: Calendar collections
 * - events: Calendar events (masters + exceptions)
 * - events_fts: FTS4 full-text search index for events (v4)
 * - occurrences: Materialized RRULE expansions
 * - pending_operations: Offline-first sync queue
 * - sync_logs: Debug/audit trail
 * - ics_subscriptions: ICS feed subscriptions (v2)
 * - scheduled_reminders: Alarm tracking for notifications (v3)
 *
 * @see <a href="https://developer.android.com/training/data-storage/room">Room Documentation</a>
 */
@Database(
    entities = [
        Account::class,
        AddressBook::class,
        Attendee::class,
        Calendar::class,
        Category::class,
        Event::class,
        EventFts::class,
        IcsSubscription::class,
        Occurrence::class,
        PendingCancel::class,
        PendingOperation::class,
        ScheduledReminder::class,
        SyncLog::class
    ],
    version = 24,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 3, to = 4)
    ]
)
@TypeConverters(Converters::class)
abstract class KashCalDatabase : RoomDatabase() {

    /**
     * Access to Account operations.
     */
    abstract fun accountsDao(): AccountsDao

    /**
     * Access to CardDAV address-book collection operations.
     */
    abstract fun addressBookDao(): AddressBookDao

    /**
     * Access to Calendar operations.
     */
    abstract fun calendarsDao(): CalendarsDao

    /**
     * Access to Event operations.
     */
    abstract fun eventsDao(): EventsDao

    /**
     * Access to Occurrence operations (materialized RRULE expansions).
     */
    abstract fun occurrencesDao(): OccurrencesDao

    /**
     * Access to Attendee operations (per-event ATTENDEE rows).
     */
    abstract fun attendeesDao(): AttendeesDao

    /**
     * Access to PendingOperation operations (sync queue).
     */
    abstract fun pendingOperationsDao(): PendingOperationsDao

    /**
     * Access to PendingCancel operations (removed attendees awaiting iTIP CANCEL).
     */
    abstract fun pendingCancelsDao(): PendingCancelsDao

    /**
     * Access to SyncLog operations (debugging).
     */
    abstract fun syncLogsDao(): SyncLogsDao

    /**
     * Access to IcsSubscription operations (ICS feed subscriptions).
     */
    abstract fun icsSubscriptionsDao(): IcsSubscriptionsDao

    /**
     * Access to ScheduledReminder operations (reminder notifications).
     */
    abstract fun scheduledRemindersDao(): ScheduledRemindersDao

    /**
     * Access to Category operations (per-tag color + recency metadata).
     */
    abstract fun categoryDao(): CategoryDao

    /**
     * Non-inline wrapper for Room's withTransaction.
     *
     * Room's withTransaction is inline, making it impossible to mock in unit tests.
     * This wrapper is not inline, allowing it to be mocked while preserving
     * the same transactional behavior in production.
     *
     * @param block The suspend block to run within a transaction
     * @return The result of the block
     */
    open suspend fun <R> runInTransaction(block: suspend () -> R): R = withTransaction(block)

    companion object {
        private const val TAG = "KashCalDatabase"

        /**
         * Database file name.
         */
        const val DATABASE_NAME = "kashcal.db"

        /**
         * Database callback to create triggers for master event duplicate prevention.
         * Uses triggers instead of partial unique index (Room doesn't validate triggers).
         */
        fun testCallback(): RoomDatabase.Callback = databaseCallback

        private val databaseCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                Log.d(TAG, "Creating triggers for master event deduplication")
                createMasterEventUniqueTriggers(db)
                seedDefaultCategories(db)
            }
        }

        /**
         * Seed the curated starter tags on a fresh install so a new user lands on
         * the same Work/Personal/Family set the v21→v22 migration gives an
         * upgrading user. Mirrors the production callback in the DI module.
         */
        private fun seedDefaultCategories(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            for ((name, color) in Category.DEFAULT_SEEDS) {
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, ?, ?)",
                    arrayOf<Any?>(name, color, now)
                )
            }
        }

        private fun createMasterEventUniqueTriggers(db: SupportSQLiteDatabase) {
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
}
