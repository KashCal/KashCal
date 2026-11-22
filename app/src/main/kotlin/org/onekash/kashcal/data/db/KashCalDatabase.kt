package org.onekash.kashcal.data.db

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.room3.ColumnTypeConverters
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import org.onekash.kashcal.data.db.converter.Converters
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AddressBookDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.CategoryDao
import org.onekash.kashcal.data.db.dao.EventIcsDao
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
import org.onekash.kashcal.data.db.entity.EventIcsPart
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.PendingCancel
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.RecentChange
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.db.entity.SyncLog
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
 * - event_ics: stored ICS of events, in pieces ([EventIcsStore])
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
        EventIcsPart::class,
        IcsSubscription::class,
        Occurrence::class,
        PendingCancel::class,
        PendingOperation::class,
        RecentChange::class,
        ScheduledReminder::class,
        SyncLog::class
    ],
    version = 29,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 3, to = 4)
    ]
)
@ColumnTypeConverters(Converters::class)
abstract class KashCalDatabase : RoomDatabase() {

    abstract fun accountsDao(): AccountsDao

    abstract fun addressBookDao(): AddressBookDao

    abstract fun calendarsDao(): CalendarsDao

    abstract fun eventsDao(): EventsDao

    abstract fun occurrencesDao(): OccurrencesDao

    /** Use [EventIcsStore]; only it reads or writes the pieces. */
    abstract fun eventIcsDao(): EventIcsDao

    abstract fun attendeesDao(): AttendeesDao

    abstract fun pendingOperationsDao(): PendingOperationsDao

    abstract fun pendingCancelsDao(): PendingCancelsDao

    abstract fun syncLogsDao(): SyncLogsDao

    abstract fun icsSubscriptionsDao(): IcsSubscriptionsDao

    abstract fun scheduledRemindersDao(): ScheduledRemindersDao

    abstract fun categoryDao(): CategoryDao

    abstract fun recentChangesDao(): RecentChangesDao

    /**
     * Runs [block] in a transaction and returns its result. App code opens its transactions
     * here; DAO `@Transaction` methods open their own. A failure thrown out of [block] rolls back
     * everything it wrote; a nested call joins the outer transaction on the same connection.
     *
     * A cancellation of the caller seen before the commit rolls the transaction back: the
     * outermost call checks the caller's job before it commits, because the writer connection
     * runs under Room's own job, not the caller's (`TransactionAtomicityTest`). A cancelled block
     * still runs to its end first. The connection is confined to this coroutine, so
     * [block] must not hand database work to another coroutine (`launch`, `async`). Open so unit
     * tests can stub it.
     */
    open suspend fun <R> runInTransaction(block: suspend () -> R): R {
        val context = currentCoroutineContext()
        // A nested call uses the outer transaction's Transactor directly: going through
        // useWriterConnection again would start an invalidation refresh that waits for the
        // writer the outer transaction holds.
        context[AppTransaction]?.takeIf { it.database === this }?.let { outer ->
            return outer.transactor.immediateTransaction { block() }
        }
        val caller = context[Job]
        caller?.ensureActive()
        return try {
            useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    withContext(AppTransaction(this@KashCalDatabase, transactor)) {
                        block().also { caller?.ensureActive() }
                    }
                }
            }
        } catch (e: CancellationException) {
            // A cancellation that lands after the commit skips the refresh useWriterConnection
            // runs on return, so observers would miss the committed rows until the next write.
            invalidationTracker.refreshAsync()
            throw e
        }
    }

    /** Returns whether the calling coroutine runs inside this database's [runInTransaction]. */
    open suspend fun isInAppTransaction(): Boolean = currentCoroutineContext()[AppTransaction]?.database === this

    /** Marks a coroutine inside [runInTransaction] of [database], holding its [transactor]. */
    private class AppTransaction(
        val database: KashCalDatabase,
        val transactor: Transactor,
    ) : AbstractCoroutineContextElement(AppTransaction) {
        companion object Key : CoroutineContext.Key<AppTransaction>
    }

    companion object {
        const val DATABASE_NAME = "kashcal.db"

        /**
         * Returns the [DatabaseCreateCallback] production applies through
         * [kashCalProductionConfig], for tests that build Room with their own options.
         */
        fun testCallback(): RoomDatabase.Callback = DatabaseCreateCallback
    }
}
