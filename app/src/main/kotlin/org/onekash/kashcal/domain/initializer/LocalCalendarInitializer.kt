package org.onekash.kashcal.domain.initializer

import androidx.room.withTransaction
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures a local calendar always exists for offline-first operation.
 *
 * KashCal is offline-first - users can always create events locally
 * without setting up any sync accounts. This initializer creates:
 * - A local account (provider = AccountProvider.LOCAL)
 * - A "Local" calendar as default
 *
 * Called on app startup via Hilt.
 */
@Singleton
class LocalCalendarInitializer @Inject constructor(
    private val database: KashCalDatabase
) {
    companion object {
        const val LOCAL_EMAIL = "local"
        const val LOCAL_ACCOUNT_DISPLAY_NAME = "On This Device"
        const val LOCAL_CALENDAR_DISPLAY_NAME = "Local"
        const val LOCAL_CALENDAR_URL = "local://default"
        const val LOCAL_CALENDAR_COLOR = 0xFF9E9E9E.toInt() // Material Gray 500
    }

    /**
     * Ensures local account and calendar exist.
     *
     * Safe to call multiple times - only creates if not exists.
     * Uses transaction for atomicity.
     *
     * @return The local calendar ID
     */
    suspend fun ensureLocalCalendarExists(): Long {
        return database.withTransaction {
            val accountsDao = database.accountsDao()
            val calendarsDao = database.calendarsDao()

            // Check if local account exists
            var localAccount = accountsDao.getByProviderAndEmail(AccountProvider.LOCAL, LOCAL_EMAIL)

            if (localAccount == null) {
                // Create local account
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

            // Check if local calendar exists
            var localCalendar = calendarsDao.getByCaldavUrl(LOCAL_CALENDAR_URL)

            if (localCalendar == null) {
                // Create local calendar
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

    /**
     * Get the local calendar ID, creating if necessary.
     */
    suspend fun getLocalCalendarId(): Long {
        val calendarsDao = database.calendarsDao()
        val localCalendar = calendarsDao.getByCaldavUrl(LOCAL_CALENDAR_URL)
        return localCalendar?.id ?: ensureLocalCalendarExists()
    }

    /**
     * Check if an account is the local account.
     */
    fun isLocalAccount(account: Account): Boolean {
        return account.provider == AccountProvider.LOCAL && account.email == LOCAL_EMAIL
    }

    /**
     * Check if a calendar is the local calendar.
     */
    fun isLocalCalendar(calendar: Calendar): Boolean {
        return calendar.caldavUrl == LOCAL_CALENDAR_URL
    }
}
