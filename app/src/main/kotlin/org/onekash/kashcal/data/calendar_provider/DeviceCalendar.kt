package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * A device calendar from CalendarProvider.
 * Used in settings UI (calendar selection) and write routing (access level check).
 *
 * Maps to CalendarContract.Calendars columns.
 */
@Immutable
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val color: Int,
    val accountName: String,
    val accountType: String,
    val visible: Boolean,
    val accessLevel: Int,
) {
    /**
     * Calendar is writable if access level >= CONTRIBUTOR (500).
     * Read-only calendars (FREEBUSY=100, READ=200) don't allow event creation/editing.
     */
    val isWritable: Boolean
        get() = accessLevel >= 500 // CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
}
