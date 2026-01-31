package org.onekash.kashcal.ui.model

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Groups calendars by account for UI display.
 *
 * @param accountName Display name for the account header
 * @param accountId Account ID for grouping
 * @param calendars List of calendars under this account
 */
data class CalendarGroup(
    val accountName: String,
    val accountId: Long,
    val calendars: List<Calendar>
) {
    companion object {
        /**
         * Groups calendars by account for UI display.
         * Called from ViewModels to transform data layer output into UI state.
         *
         * @param calendars List of calendars to group
         * @param accounts List of accounts for display names
         * @return List of CalendarGroup sorted by account name
         */
        fun fromCalendarsAndAccounts(
            calendars: List<Calendar>,
            accounts: List<Account>
        ): List<CalendarGroup> {
            val accountMap = accounts.associateBy { it.id }

            return calendars
                .groupBy { it.accountId }
                .map { (accountId, accountCalendars) ->
                    val account = accountMap[accountId]
                    val accountName = account?.displayName
                        ?: account?.provider?.displayName
                        ?: "Unknown"
                    CalendarGroup(
                        accountName = accountName,
                        accountId = accountId,
                        calendars = accountCalendars.sortedBy { it.displayName.lowercase() }
                    )
                }
                .sortedBy { it.accountName.lowercase() }
        }
    }
}
