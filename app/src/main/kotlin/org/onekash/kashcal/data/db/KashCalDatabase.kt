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
import org.onekash.kashcal.data.db.dao.RecentChangesDao
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
import org.onekash.kashcal.data.db.entity.RecentChange
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.db.entity.SyncLog

/**
 * Stores the Room-backed calendar data offline-first: local, iCloud, CalDAV and ICS
 * subscription calendars. Device-calendar events stay in CalendarProvider.
 *
 * Tables:
 * - accounts: sync accounts; credentials are stored in
 *   [org.onekash.kashcal.data.credential.UnifiedCredentialManager], not here
 * - address_books: CardDAV address-book collections
 * - attendees: per-event ATTENDEE rows
 * - calendars: calendar collections
 * - categories: per-tag color and recency
 * - events: masters and exceptions
 * - events_fts: FTS4 search index over events
 * - ics_subscriptions: ICS feed subscriptions
 * - occurrences: materialized RRULE expansions
 * - pending_cancels: removed attendees awaiting an iTIP CANCEL
 * - pending_operations: sync queue
 * - recent_changes: what CalDAV and iCloud pulls changed, for the Recent changes sheet
 * - scheduled_reminders: alarms scheduled for reminder notifications
 * - sync_logs: sync debug and audit trail
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
        RecentChange::class,
        ScheduledReminder::class,
        SyncLog::class
    ],
    version = 25,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 3, to = 4)
    ]
)
@TypeConverters(Converters::class)
abstract class KashCalDatabase : RoomDatabase() {

    abstract fun accountsDao(): AccountsDao

    abstract fun addressBookDao(): AddressBookDao

    abstract fun calendarsDao(): CalendarsDao

    abstract fun eventsDao(): EventsDao

    abstract fun occurrencesDao(): OccurrencesDao

    abstract fun attendeesDao(): AttendeesDao

    abstract fun pendingOperationsDao(): PendingOperationsDao

    abstract fun pendingCancelsDao(): PendingCancelsDao

    abstract fun syncLogsDao(): SyncLogsDao

    abstract fun icsSubscriptionsDao(): IcsSubscriptionsDao

    abstract fun scheduledRemindersDao(): ScheduledRemindersDao

    abstract fun categoryDao(): CategoryDao

    abstract fun recentChangesDao(): RecentChangesDao

    /**
     * Runs [block] in a transaction and returns its result.
     *
     * Room's `withTransaction` is inline, so unit tests can't mock it; this open wrapper can be
     * mocked and behaves the same in production.
     */
    open suspend fun <R> runInTransaction(block: suspend () -> R): R = withTransaction(block)

    companion object {
        private const val TAG = "KashCalDatabase"

        const val DATABASE_NAME = "kashcal.db"

        /**
         * Returns a create callback for test databases: it installs the triggers that keep a
         * master's uid unique per calendar and seeds the default tags. Production uses its own
         * copy in [org.onekash.kashcal.di.DatabaseModule]; keep the two in step.
         *
         * Triggers stand in for a partial unique index because Room doesn't validate triggers.
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

        /** Seeds [Category.DEFAULT_SEEDS], like the production create callback. */
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
