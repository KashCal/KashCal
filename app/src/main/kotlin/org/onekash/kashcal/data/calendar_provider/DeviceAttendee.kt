package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * One row of CalendarProvider's `Attendees` table: `_ID`, `ATTENDEE_NAME`, `ATTENDEE_EMAIL`,
 * `ATTENDEE_RELATIONSHIP` and `ATTENDEE_STATUS`.
 *
 * Kept separate from the Room [org.onekash.kashcal.data.db.entity.Attendee] on purpose: that
 * entity carries iTIP fields (schedule agent, request sequence, schedule status) that mean
 * nothing to `CalendarContract.Attendees`. The account's sync adapter delivers invitations;
 * the app only writes provider rows, so this model holds only the provider's columns.
 */
@Immutable
data class DeviceAttendee(
    /** `Attendees._ID`; lets one row, such as the user's RSVP, change without touching the rest. */
    val id: Long,
    /** `Attendees.ATTENDEE_NAME`; may be null or blank. */
    val name: String?,
    /** `Attendees.ATTENDEE_EMAIL`. */
    val email: String?,
    /** `Attendees.ATTENDEE_RELATIONSHIP`, e.g. `RELATIONSHIP_ORGANIZER`. */
    val relationship: Int,
    /** `Attendees.ATTENDEE_STATUS`, e.g. `ATTENDEE_STATUS_ACCEPTED` or `ATTENDEE_STATUS_NONE`. */
    val status: Int
)
