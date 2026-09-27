package org.onekash.kashcal.domain.initializer

import androidx.room.withTransaction
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the local account and calendar exist, so events can be created without any sync
 * account.
 *
 * Creates a local account (provider [AccountProvider.LOCAL]) and a default "Local" calendar.
 * `MainActivity` calls [ensureLocalCalendarExists] on startup through
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
    }

    /**
     * Creates the local account and calendar if missing, in one transaction, and returns the
     * local calendar ID. Idempotent.
     */
    suspend fun ensureLocalCalendarExists(): Long {
        return database.withTransaction {
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

    /** Returns the local calendar ID, creating the account and calendar if missing. */
    suspend fun getLocalCalendarId(): Long {
        val calendarsDao = database.calendarsDao()
        val localCalendar = calendarsDao.getByCaldavUrl(LOCAL_CALENDAR_URL)
        return localCalendar?.id ?: ensureLocalCalendarExists()
    }

    /** Returns true if [account] is the local account. */
    fun isLocalAccount(account: Account): Boolean {
        return account.provider == AccountProvider.LOCAL && account.email == LOCAL_EMAIL
    }

    /** Returns true if [calendar] is the local calendar. */
    fun isLocalCalendar(calendar: Calendar): Boolean {
        return calendar.caldavUrl == LOCAL_CALENDAR_URL
    }
}
