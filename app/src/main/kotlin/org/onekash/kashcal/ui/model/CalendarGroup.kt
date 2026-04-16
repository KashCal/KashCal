package org.onekash.kashcal.ui.model

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Unified calendar representation for the calendar picker.
 * Wraps both Room Calendar and DeviceCalendar with common interface.
 */
@Immutable
sealed class PickerCalendar {
    abstract val id: Long
    abstract val displayName: String
    abstract val color: Int
    abstract val isWritable: Boolean

    /** Room calendar (KashCal-managed) */
    @Immutable
    data class Room(val calendar: Calendar) : PickerCalendar() {
        override val id: Long get() = calendar.id
        override val displayName: String get() = calendar.displayName
        override val color: Int get() = calendar.color
        override val isWritable: Boolean get() = true // Room calendars are always writable
    }

    /** Device calendar (from CalendarProvider) */
    @Immutable
    data class Device(val calendar: DeviceCalendar) : PickerCalendar() {
        override val id: Long get() = calendar.id
        override val displayName: String get() = calendar.displayName
        override val color: Int get() = calendar.color
        override val isWritable: Boolean get() = calendar.isWritable
    }
}

/**
 * Groups calendars by account for UI display.
 * Supports both Room and Device calendars via PickerCalendar.
 *
 * @param accountName Display name for the account header
 * @param accountId Account ID for grouping (Room) or synthetic ID (Device)
 * @param calendars List of calendars under this account
 * @param isDeviceSection True for device calendar groups (shows after separator)
 */
@Immutable
data class CalendarGroup(
    val accountName: String,
    val accountId: Long,
    val calendars: List<Calendar> = emptyList(),
    val pickerCalendars: List<PickerCalendar> = emptyList(),
    val isDeviceSection: Boolean = false,
    val provider: AccountProvider? = null
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
                    val accountName = when (account?.provider) {
                        AccountProvider.LOCAL -> "Offline"
                        else -> account?.displayName
                            ?: account?.provider?.displayName
                            ?: "Unknown"
                    }
                    val sorted = accountCalendars.sortedBy { it.displayName.lowercase() }
                    CalendarGroup(
                        accountName = accountName,
                        accountId = accountId,
                        calendars = sorted,
                        pickerCalendars = sorted.map { PickerCalendar.Room(it) },
                        provider = account?.provider
                    )
                }
                .sortedWith(
                    compareBy<CalendarGroup> { it.provider == AccountProvider.CONTACTS }
                        .thenBy { it.accountName.lowercase() }
                )
        }

        /**
         * Groups device calendars by account for UI display.
         * Only includes writable calendars for event creation.
         *
         * @param deviceCalendars List of device calendars
         * @param writableOnly If true, only include writable calendars
         * @return List of CalendarGroup for device calendars, sorted by account name
         */
        fun fromDeviceCalendars(
            deviceCalendars: List<DeviceCalendar>,
            writableOnly: Boolean = true
        ): List<CalendarGroup> {
            val filtered = if (writableOnly) {
                deviceCalendars.filter { it.isWritable }
            } else {
                deviceCalendars
            }

            return filtered
                .groupBy { it.accountName }
                .map { (accountName, calendars) ->
                    CalendarGroup(
                        accountName = accountName.ifEmpty { "Local" },
                        accountId = -1, // Synthetic ID for device groups
                        calendars = emptyList(),
                        pickerCalendars = calendars
                            .sortedBy { it.displayName.lowercase() }
                            .map { PickerCalendar.Device(it) },
                        isDeviceSection = true
                    )
                }
                .sortedBy { it.accountName.lowercase() }
        }
    }
}
