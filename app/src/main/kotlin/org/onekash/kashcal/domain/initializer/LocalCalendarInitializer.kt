package org.onekash.kashcal.domain.initializer

import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.util.exportNameKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the local account and its built-in calendar exist, so events can be created without
 * any sync account, decides which calendars are local ([isLocalAccount]), and adds, renames,
 * recolors and deletes offline calendars, whose names follow the rule on [createCalendar].
 *
 * Creates a local account (provider [AccountProvider.LOCAL]) and the built-in "Local" calendar
 * at [LOCAL_CALENDAR_URL]. `MainActivity` calls [ensureLocalCalendarExists] on startup through
 * [org.onekash.kashcal.domain.coordinator.EventCoordinator]; [getLocalCalendarId] creates
 * them on demand.
 */
@Singleton
class LocalCalendarInitializer @Inject constructor(
    private val database: KashCalDatabase
) {
    companion object {
        const val LOCAL_EMAIL = "local"
        // Stored display names: the stable DB fallback, never mutated; the user-visible label
        // is localized at display time.
        const val LOCAL_ACCOUNT_DISPLAY_NAME = "On This Device"
        const val LOCAL_CALENDAR_DISPLAY_NAME = "Local"
        const val LOCAL_CALENDAR_URL = "local://default"
        const val LOCAL_CALENDAR_COLOR = 0xFF9E9E9E.toInt() // Material Gray 500

        /** URL prefix of the offline calendars a user adds ([createCalendar]). */
        const val NEW_CALENDAR_URL_PREFIX = "local://calendar/"

        /**
         * Returns true if [account] is an offline account: its provider is
         * [AccountProvider.LOCAL]. Every calendar under it is local, whatever its URL: writes to
         * it queue nothing and request no sync. A missing account is not local.
         */
        fun isLocalAccount(account: Account?): Boolean = account?.provider == AccountProvider.LOCAL
    }

    /**
     * Creates the local account and the built-in calendar if missing, in one transaction, and
     * returns the built-in calendar's ID. Idempotent.
     */
    suspend fun ensureLocalCalendarExists(): Long {
        return database.runInTransaction {
            val accountsDao = database.accountsDao()
            val calendarsDao = database.calendarsDao()

            var localAccount = accountsDao.getByProviderAndEmail(AccountProvider.LOCAL, LOCAL_EMAIL)

            if (localAccount == null) {
                val accountId = accountsDao.insert(
                    Account(
                        provider = AccountProvider.LOCAL,
                        email = LOCAL_EMAIL,
                        displayName = LOCAL_ACCOUNT_DISPLAY_NAME,
                        isEnabled = true
                    )
                )
                localAccount = checkNotNull(accountsDao.getById(accountId)) {
                    "Local account not found after insert: $accountId"
                }
            }

            val localCalendar = calendarsDao.getByCaldavUrl(LOCAL_CALENDAR_URL)

            if (localCalendar == null) {
                val calendarId = calendarsDao.insert(
                    Calendar(
                        accountId = localAccount.id,
                        caldavUrl = LOCAL_CALENDAR_URL,
                        displayName = LOCAL_CALENDAR_DISPLAY_NAME,
                        color = LOCAL_CALENDAR_COLOR,
                        isVisible = true,
                        isDefault = true,
                        isReadOnly = false,
                        sortOrder = 0 // Local calendar appears first
                    )
                )
                calendarId
            } else {
                localCalendar.id
            }
        }
    }

    /** Returns the built-in calendar's ID, creating the account and calendar if missing. */
    suspend fun getLocalCalendarId(): Long {
        val calendarsDao = database.calendarsDao()
        val localCalendar = calendarsDao.getByCaldavUrl(LOCAL_CALENDAR_URL)
        return localCalendar?.id ?: ensureLocalCalendarExists()
    }

    /** Returns true if [calendar] belongs to an offline account ([isLocalAccount]). */
    suspend fun isLocalCalendar(calendar: Calendar): Boolean {
        return isLocalAccount(database.accountsDao().getById(calendar.accountId))
    }

    /**
     * Adds an offline calendar named [name] (trimmed) with [color] under the local account,
     * creating the account and the built-in first if missing, and returns its id.
     *
     * Its URL is `local://calendar/<random UUID>`, never derived from the name, so a rename
     * leaves it alone and a deleted calendar's URL is never reused. The name check and the insert
     * run in one transaction, so two quick creates with one name add one calendar.
     *
     * @param builtInName the built-in calendar's translated label, which the name is checked
     *   against instead of its stored English name
     * @throws LocalCalendarException with [LocalCalendarRefusal.BLANK_NAME] or
     *   [LocalCalendarRefusal.DUPLICATE_NAME] or [LocalCalendarRefusal.SIMILAR_NAME]
     */
    suspend fun createCalendar(name: String, color: Int, builtInName: String): Long {
        return database.runInTransaction {
            ensureLocalCalendarExists()
            val account = checkNotNull(
                database.accountsDao().getByProviderAndEmail(AccountProvider.LOCAL, LOCAL_EMAIL)
            ) { "Local account missing after ensureLocalCalendarExists" }
            val trimmed = checkName(name, account.id, builtInName, renamingId = null)
            database.calendarsDao().insert(
                Calendar(
                    accountId = account.id,
                    caldavUrl = "$NEW_CALENDAR_URL_PREFIX${UUID.randomUUID()}",
                    displayName = trimmed,
                    color = color,
                    isVisible = true,
                    isDefault = false,
                    isReadOnly = false
                )
            )
        }
    }

    /**
     * Renames the offline calendar [calendarId] to [name] (trimmed), checked by the same rule as
     * [createCalendar] in the same transaction. A name equal to the calendar's own stored name is
     * kept as is, even when it now matches [builtInName] after a language change.
     *
     * @throws LocalCalendarException with [LocalCalendarRefusal.NOT_FOUND],
     *   [LocalCalendarRefusal.NOT_LOCAL], [LocalCalendarRefusal.BUILT_IN] (the built-in keeps its
     *   translated name), [LocalCalendarRefusal.BLANK_NAME],
     *   [LocalCalendarRefusal.DUPLICATE_NAME] or [LocalCalendarRefusal.SIMILAR_NAME]
     */
    suspend fun renameCalendar(calendarId: Long, name: String, builtInName: String) {
        database.runInTransaction {
            val calendar = requireUserAddedCalendar(calendarId)
            if (name.trim() == calendar.displayName) return@runInTransaction
            val trimmed = checkName(name, calendar.accountId, builtInName, renamingId = calendar.id)
            database.calendarsDao().updateDisplayName(calendar.id, trimmed)
        }
    }

    /**
     * Sets the color of the offline calendar [calendarId], the built-in included. Writes
     * [Calendar.color], which the drawer, events and widgets read.
     *
     * @throws LocalCalendarException with [LocalCalendarRefusal.NOT_FOUND] or
     *   [LocalCalendarRefusal.NOT_LOCAL]
     */
    suspend fun recolorCalendar(calendarId: Long, color: Int) {
        database.runInTransaction {
            database.calendarsDao().updateColor(requireLocalCalendar(calendarId).id, color)
        }
    }

    /**
     * Throws if [calendarId] can't be deleted: missing, not offline, or the built-in. Lets a caller
     * refuse before it starts work around the delete; [deleteCalendar] checks again.
     *
     * @throws LocalCalendarException with [LocalCalendarRefusal.NOT_FOUND],
     *   [LocalCalendarRefusal.NOT_LOCAL] or [LocalCalendarRefusal.BUILT_IN]
     */
    suspend fun checkDeletable(calendarId: Long) {
        requireUserAddedCalendar(calendarId)
    }

    /**
     * Deletes the offline calendar [calendarId] in one transaction and returns how many events it
     * had ([org.onekash.kashcal.data.db.dao.EventsDao.countUserEventsInCalendar]).
     *
     * The row's cascade removes its recent changes and its events, and through the events their
     * occurrences, attendees, reminder rows and pending cancels. `PendingOperationTriggers` drops
     * the events' queued operations except a self-contained DELETE, such as the one that removes
     * the server copy of an event moved in from a synced calendar. AlarmManager alarms are not
     * touched; the caller cancels them.
     *
     * @throws LocalCalendarException as [checkDeletable]
     */
    suspend fun deleteCalendar(calendarId: Long): Int {
        return database.runInTransaction {
            val calendar = requireUserAddedCalendar(calendarId)
            val count = database.eventsDao().countUserEventsInCalendar(calendar.id)
            database.calendarsDao().deleteById(calendar.id)
            count
        }
    }

    /** Returns offline calendar [calendarId] ([requireLocalCalendar]); throws for the built-in. */
    private suspend fun requireUserAddedCalendar(calendarId: Long): Calendar {
        val calendar = requireLocalCalendar(calendarId)
        if (calendar.caldavUrl == LOCAL_CALENDAR_URL) {
            throw LocalCalendarException(LocalCalendarRefusal.BUILT_IN)
        }
        return calendar
    }

    /** Returns calendar [calendarId]; throws if it is missing or not under an offline account. */
    private suspend fun requireLocalCalendar(calendarId: Long): Calendar {
        val calendar = database.calendarsDao().getById(calendarId)
            ?: throw LocalCalendarException(LocalCalendarRefusal.NOT_FOUND)
        if (!isLocalCalendar(calendar)) throw LocalCalendarException(LocalCalendarRefusal.NOT_LOCAL)
        return calendar
    }

    /**
     * Returns [name] trimmed, or throws if it is blank, matches the name of another calendar of
     * account [accountId] ignoring case ([LocalCalendarRefusal.DUPLICATE_NAME]), or would give the
     * same backup file name as one ([LocalCalendarRefusal.SIMILAR_NAME]; names that clean to
     * nothing are left to [org.onekash.kashcal.calendarbackup.CalendarBackupNames.planBaseNames]).
     * The built-in is matched by [builtInName]. The calendar [renamingId] is left out of the
     * comparison, so a calendar can keep its name in another case or punctuation. Call inside the
     * transaction that writes the name.
     */
    private suspend fun checkName(
        name: String,
        accountId: Long,
        builtInName: String,
        renamingId: Long?
    ): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalCalendarException(LocalCalendarRefusal.BLANK_NAME)
        val taken = database.calendarsDao().getByAccountIdOnce(accountId)
            .filter { it.id != renamingId }
            .map { if (it.caldavUrl == LOCAL_CALENDAR_URL) builtInName.trim() else it.displayName }
        if (taken.any { it.equals(trimmed, ignoreCase = true) }) {
            throw LocalCalendarException(LocalCalendarRefusal.DUPLICATE_NAME)
        }
        val key = exportNameKey(trimmed)
        if (key.isNotEmpty()) {
            taken.firstOrNull { exportNameKey(it) == key }?.let { similar ->
                throw LocalCalendarException(LocalCalendarRefusal.SIMILAR_NAME, conflictingName = similar)
            }
        }
        return trimmed
    }
}
