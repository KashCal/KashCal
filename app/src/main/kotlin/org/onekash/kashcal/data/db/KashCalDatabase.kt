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
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.EventFts
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.Occurrence
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
        Calendar::class,
        Event::class,
        EventFts::class,
        IcsSubscription::class,
        Occurrence::class,
        PendingOperation::class,
        ScheduledReminder::class,
        SyncLog::class
    ],
    version = 6,
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
     * Access to PendingOperation operations (sync queue).
     */
    abstract fun pendingOperationsDao(): PendingOperationsDao

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

        @Volatile
        private var INSTANCE: KashCalDatabase? = null

        /**
         * Database callback to create partial unique index for duplicate prevention.
         *
         * This creates a unique index on (uid, calendar_id) for master events only.
         * Exception events share the master's UID and are excluded via WHERE clause.
         * This is RFC 5545 compliant: master events must have unique UIDs within a calendar.
         */
        private val databaseCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                Log.d(TAG, "Creating partial unique index for master event deduplication")
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_events_uid_calendar_master
                    ON events (uid, calendar_id)
                    WHERE original_event_id IS NULL
                """.trimIndent())
            }

        }

        /**
         * Get database instance for non-DI contexts (e.g., widgets).
         *
         * This uses a singleton pattern to ensure only one database instance exists.
         * For DI-managed components, prefer injecting the database directly.
         *
         * @param context Application context
         * @return Singleton database instance
         */
        fun getInstance(context: android.content.Context): KashCalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: android.content.Context): KashCalDatabase {
            return androidx.room.Room.databaseBuilder(
                context.applicationContext,
                KashCalDatabase::class.java,
                DATABASE_NAME
            )
                .enableMultiInstanceInvalidation()
                .addMigrations(*org.onekash.kashcal.data.db.migration.Migrations.ALL_MIGRATIONS)
                .addCallback(databaseCallback)
                .build()
        }
    }
}
