package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * A single attendee row from CalendarProvider's `Attendees` table.
 *
 * This is a thin, provider-shaped model — deliberately NOT the Room
 * `Attendee` entity. The Room entity carries iTIP wire fields
 * (scheduleAgent, itipRequestSequence, scheduleStatus, …) that are
 * meaningless to `CalendarContract.Attendees`. Device-calendar delivery is
 * the account's sync adapter's job; KashCal only populates provider rows, so
 * this model holds exactly the columns the provider exposes and nothing more.
 *
 * Fields mirror the `CalendarContract.Attendees` projection:
 * `_ID, ATTENDEE_NAME, ATTENDEE_EMAIL, ATTENDEE_RELATIONSHIP, ATTENDEE_STATUS`.
 */
@Immutable
data class DeviceAttendee(
    /** `Attendees._ID` — the provider row id. Needed to update a single row
     *  (e.g. the user's own RSVP status) without touching other attendees. */
    val id: Long,
    /** `Attendees.ATTENDEE_NAME` — display name; may be null/blank. */
    val name: String?,
    /** `Attendees.ATTENDEE_EMAIL` — the attendee's email address. */
    val email: String?,
    /** `Attendees.ATTENDEE_RELATIONSHIP` — raw provider int
     *  (e.g. RELATIONSHIP_ORGANIZER, RELATIONSHIP_ATTENDEE). */
    val relationship: Int,
    /** `Attendees.ATTENDEE_STATUS` — raw provider int
     *  (e.g. ATTENDEE_STATUS_ACCEPTED, ATTENDEE_STATUS_NONE). */
    val status: Int
)
