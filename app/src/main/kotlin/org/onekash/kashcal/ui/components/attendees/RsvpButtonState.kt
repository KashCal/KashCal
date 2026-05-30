package org.onekash.kashcal.ui.components.attendees

/**
 * Pure visibility predicates for the RSVP surfaces. The composables
 * consume these helpers; this file is the single home for the rules
 * so they can be unit-tested without Compose semantics.
 */

/**
 * Should the Respond UI render at all?
 *
 * Visible only when the user is on the event's attendee list (i.e.,
 * the chip surface would emit a chip with `isYou = true`). For events
 * the user organized but isn't listed as an attendee on, the
 * synthesized organizer chip's PARTSTAT is `Accepted` — we still
 * don't show RSVP buttons there, because organizers respond by
 * editing the event, not by RSVPing to themselves.
 *
 * @param currentUserPartstat The current user's PARTSTAT, or null when
 *   the user isn't on the attendee list.
 * @param isOrganizer True when the current user is the event's ORGANIZER.
 *   Drives the organizer-suppression rule above.
 */
fun shouldShowRespondSection(
    currentUserPartstat: AttendeeStatus?,
    isOrganizer: Boolean
): Boolean {
    if (currentUserPartstat == null) return false
    if (isOrganizer) return false
    return true
}

/**
 * Should the "applies to the whole series" disclosure caption render
 * directly below the Respond pills?
 *
 * Series-level RSVP is a T2 lock — tapping Decline on the Friday
 * occurrence of a weekly meeting declines every Friday, not just this
 * one. The caption gives the user that context before they tap so the
 * series-wide effect doesn't surprise them after sync.
 *
 * Visible only when (a) the Respond section itself is visible, AND
 * (b) the event has a recurrence rule (master series or one of its
 * exceptions — both apply at series level).
 *
 * @param currentUserPartstat See [shouldShowRespondSection].
 * @param isOrganizer See [shouldShowRespondSection].
 * @param isRecurring True when the event is part of a recurring series
 *   (RRULE-driven master, or an exception to one).
 */
fun shouldShowSeriesRsvpDisclosure(
    currentUserPartstat: AttendeeStatus?,
    isOrganizer: Boolean,
    isRecurring: Boolean
): Boolean {
    if (!shouldShowRespondSection(currentUserPartstat, isOrganizer)) return false
    return isRecurring
}
