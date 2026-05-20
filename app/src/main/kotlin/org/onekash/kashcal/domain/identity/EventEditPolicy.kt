package org.onekash.kashcal.domain.identity

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event

/**
 * Centralizes the "can the current user edit this event as its organizer?"
 * predicate. Single home for the rule that drives:
 *
 * - the read-only banner + disabled fields in `EventFormSheet` when the
 *   user is an attendee, not the organizer (client-enforced read-only
 *   gate — server-side enforcement is unreliable, verified across
 *   multiple servers),
 * - whether the in-app editor surfaces "Save" or "Done" affordances.
 *
 * RSVP buttons remain interactive regardless of this predicate — they're
 * what an attendee CAN do on someone else's event.
 *
 * Decision table:
 *
 * | Account | Event ORGANIZER     | canEdit |
 * |---------|---------------------|---------|
 * | null    | anything            | false   |
 * | any     | null/blank          | true    |  (lone-author event — user is implicitly the organizer)
 * | any     | matches account     | true    |
 * | any     | doesn't match       | false   |
 */
fun Account?.canEditAsOrganizer(event: Event): Boolean {
    if (this == null) return false
    val organizer = event.organizerEmail
    // Lone-author events have no ORGANIZER property; the user owns them.
    if (organizer.isNullOrBlank()) return true
    return this.matchesAttendee(organizer)
}
