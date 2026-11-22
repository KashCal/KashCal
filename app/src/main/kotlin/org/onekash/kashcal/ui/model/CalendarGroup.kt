package org.onekash.kashcal.ui.model

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/** Wraps a Room [Calendar] or a [DeviceCalendar] in one shape for calendar pickers and lists. */
@Immutable
sealed class PickerCalendar {
    abstract val id: Long
    abstract val displayName: String
    abstract val color: Int
    abstract val isWritable: Boolean

    /** A Room calendar (local, iCloud, CalDAV, ICS or contact events). */
    @Immutable
    data class Room(val calendar: Calendar) : PickerCalendar() {
        override val id: Long get() = calendar.id
        override val displayName: String get() = calendar.displayName
        override val color: Int get() = calendar.color
        // Ignores calendar.isReadOnly; every isWritable reader reads device groups only.
        override val isWritable: Boolean get() = true
    }

    /** A device calendar from CalendarProvider. */
    @Immutable
    data class Device(val calendar: DeviceCalendar) : PickerCalendar() {
        override val id: Long get() = calendar.id
        override val displayName: String get() = calendar.displayName
        override val color: Int get() = calendar.color
        override val isWritable: Boolean get() = calendar.isWritable
    }
}

/**
 * Holds one account's calendars under an account header.
 *
 * @param accountName the account header text
 * @param accountId the Room account id, or -1 for a device group
 * @param calendars the Room calendars; empty for a device group
 * @param pickerCalendars the calendars as [PickerCalendar], Room or device
 * @param isDeviceSection true for groups built by [fromDeviceCalendars]
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
         * Groups Room calendars by account, each group's calendars sorted by name.
         *
         * @param accounts supply the header names; a group whose account is missing is "Unknown"
         * @param localLabel the header for the LOCAL account, for example "Offline"
         * @param icsLabel the header for the ICS account, for example "Calendar Feeds"
         * @param localizeCalendarName maps a calendar to its user-facing name, for example
         *   [localizedDisplayName], which localizes the local calendar's stored English name.
         *   It should return `calendar.displayName` unchanged for other calendars.
         * @return the groups sorted by account name, with contact event accounts last
         */
        fun fromCalendarsAndAccounts(
            calendars: List<Calendar>,
            accounts: List<Account>,
            localLabel: String,
            icsLabel: String,
            localizeCalendarName: (Calendar) -> String = { it.displayName }
        ): List<CalendarGroup> {
            val accountMap = accounts.associateBy { it.id }

            return calendars
                .groupBy { it.accountId }
                .map { (accountId, accountCalendars) ->
                    val account = accountMap[accountId]
                    val accountName = when (account?.provider) {
                        AccountProvider.LOCAL -> localLabel
                        AccountProvider.ICS -> icsLabel
                        else -> account?.displayName
                            ?: account?.provider?.displayName
                            ?: "Unknown"
                    }
                    val localized = accountCalendars.map { c ->
                        val name = localizeCalendarName(c)
                        if (name == c.displayName) c else c.copy(displayName = name)
                    }
                    val sorted = localized.sortedBy { it.displayName.lowercase() }
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
         * Groups device calendars by account name, each group's calendars sorted by name.
         *
         * @param writableOnly keeps only writable calendars (the default)
         * @param localLabel the header for calendars with an empty account name
         * @return the groups sorted by account name
         */
        fun fromDeviceCalendars(
            deviceCalendars: List<DeviceCalendar>,
            writableOnly: Boolean = true,
            localLabel: String = "Local"
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
                        accountName = accountName.ifEmpty { localLabel },
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

/**
 * Room calendar groups split the way the drawer, the Calendar notifications sheet and
 * [PickerSections] list them:
 * [regular] accounts, then [contacts] (birthdays and anniversaries), then, after the device
 * calendars, [offline] ([AccountProvider.LOCAL], KashCal's own calendars). Groups without
 * calendars are left out.
 */
data class DisplaySections(
    val regular: List<CalendarGroup>,
    val contacts: List<CalendarGroup>,
    val offline: List<CalendarGroup>,
)

/** Splits Room groups into [DisplaySections], keeping their order within each part. */
fun List<CalendarGroup>.toDisplaySections(): DisplaySections {
    val listed = filter { it.calendars.isNotEmpty() }
    return DisplaySections(
        regular = listed.filter {
            it.provider != AccountProvider.CONTACTS && it.provider != AccountProvider.LOCAL
        },
        contacts = listed.filter { it.provider == AccountProvider.CONTACTS },
        offline = listed.filter { it.provider == AccountProvider.LOCAL },
    )
}

/**
 * Sections of a calendar picker, in the drawer's order ([toDisplaySections]): [accounts] (the
 * regular accounts, then the contact event accounts, each in the order given, which
 * [CalendarGroup.fromCalendarsAndAccounts] sorts by name), [device] calendars, then [offline].
 * A divider goes before the device and the offline section only when something is listed above.
 */
data class PickerSections(
    val accounts: List<CalendarGroup>,
    val device: List<CalendarGroup>,
    val offline: List<CalendarGroup>,
) {
    val isEmpty: Boolean get() = accounts.isEmpty() && device.isEmpty() && offline.isEmpty()
    val dividerBeforeDevice: Boolean get() = device.isNotEmpty() && accounts.isNotEmpty()
    val dividerBeforeOffline: Boolean
        get() = offline.isNotEmpty() && (accounts.isNotEmpty() || device.isNotEmpty())
}

/**
 * Builds [PickerSections] from these Room groups and [deviceGroups], keeping the order within
 * each part and leaving out groups without calendars.
 */
fun List<CalendarGroup>.toPickerSections(deviceGroups: List<CalendarGroup>): PickerSections {
    val (regular, contacts, offline) = toDisplaySections()
    return PickerSections(
        accounts = regular + contacts,
        device = deviceGroups.filter { it.pickerCalendars.isNotEmpty() },
        offline = offline,
    )
}

/**
 * Returns the ids of the calendars in [DisplaySections.offline]: the UI's copy of
 * [org.onekash.kashcal.domain.initializer.LocalCalendarInitializer.isLocalAccount], read from
 * [CalendarGroup.provider] because the UI holds providers, not accounts.
 */
fun List<CalendarGroup>.offlineCalendarIds(): Set<Long> =
    toDisplaySections().offline.flatMap { group -> group.calendars.map { it.id } }.toSet()
