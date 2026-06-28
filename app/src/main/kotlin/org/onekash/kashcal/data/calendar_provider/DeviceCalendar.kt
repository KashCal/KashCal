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
    /**
     * `Calendars.OWNER_ACCOUNT` — the email of the calendar's owner. Used as
     * the organizer address and the "you" identity when reading/writing
     * device-event attendees. Blank when the provider doesn't supply one
     * (e.g. some LOCAL calendars). Trailing/defaulted so existing
     * construction sites stay source-compatible.
     */
    val ownerAccount: String = "",
) {
    /**
     * Calendar is writable if access level >= CONTRIBUTOR (500).
     * Read-only calendars (FREEBUSY=100, READ=200) don't allow event creation/editing.
     */
    val isWritable: Boolean
        get() = accessLevel >= 500 // CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR

    /**
     * Whether adding a guest to an event on this calendar can result in an
     * invitation being sent. True for accounts backed by a sync adapter
     * (Google, Exchange, …); false for LOCAL accounts, which have no adapter —
     * guest rows are written but stay inert. Drives the form's "this calendar
     * can't send invitations" inline notice. Keyed on the same LOCAL signal as
     * the sync-skip check so the two can't drift.
     */
    val canDeliverInvites: Boolean
        get() = !isLocalAccountType(accountType)
}
