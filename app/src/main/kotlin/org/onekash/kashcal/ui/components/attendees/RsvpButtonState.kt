package org.onekash.kashcal.ui.components.attendees

/**
 * Visibility predicates for the RSVP surfaces, kept free of Compose so they can be unit-tested.
 */

/**
 * Returns whether the Respond UI renders.
 *
 * Only when the user is on the attendee list (a chip with `isYou = true`) and isn't the
 * organizer. The synthesized organizer chip carries PARTSTAT `Accepted`, so an organizer would
 * otherwise pass the first check; organizers respond by editing the event, not by RSVPing.
 *
 * @param currentUserPartstat the user's PARTSTAT, or null when the user isn't on the list.
 * @param isOrganizer true when the user is the event's ORGANIZER.
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
 * Returns whether the "applies to the whole series" caption renders below the Respond pills.
 *
 * An RSVP on a master applies to the series: declining the Friday occurrence of a weekly
 * meeting declines every Friday. The caption says so before the tap. It shows only when
 * [shouldShowRespondSection] does and [isRecurring] is true.
 *
 * @param currentUserPartstat see [shouldShowRespondSection].
 * @param isOrganizer see [shouldShowRespondSection].
 * @param isRecurring true when the RSVP applies to a series: a recurring master. Callers pass
 *   false for an exception, whose RSVP writes only that occurrence.
 */
fun shouldShowSeriesRsvpDisclosure(
    currentUserPartstat: AttendeeStatus?,
    isOrganizer: Boolean,
    isRecurring: Boolean
): Boolean {
    if (!shouldShowRespondSection(currentUserPartstat, isOrganizer)) return false
    return isRecurring
}
