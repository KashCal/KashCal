package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/** A row of CalendarProvider's Calendars table. */
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
     * `Calendars.OWNER_ACCOUNT`, the owner's email: the organizer address and the "you" identity
     * when reading and writing device-event attendees. Blank when the provider has none (e.g.
     * some local calendars).
     */
    val ownerAccount: String = "",
) {
    /**
     * True at access level `CAL_ACCESS_CONTRIBUTOR` (500) or above. Below it (FREEBUSY 100,
     * READ 200) events can't be created or edited.
     */
    val isWritable: Boolean
        get() = accessLevel >= 500 // CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR

    /**
     * True when adding a guest can send an invitation: the account has a sync adapter. A local
     * account has none, so its guest rows are written but stay inert. Drives the event form's
     * "this calendar can't send invitations" notice. Uses [isLocalAccountType], as
     * [shouldSkipRequestSync] does, so the two can't drift.
     */
    val canDeliverInvites: Boolean
        get() = !isLocalAccountType(accountType)
}
