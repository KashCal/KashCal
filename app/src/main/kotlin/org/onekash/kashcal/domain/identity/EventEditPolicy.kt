package org.onekash.kashcal.domain.identity

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event

/**
 * Returns true when the user may edit [event] as its organizer through this account.
 *
 * It drives the read-only banner and disabled fields in `EventFormSheet` for an attendee
 * (`HomeViewModel.formIsReadOnly`) and the organizer-only push steps in `PushStrategy`
 * (SCHEDULE-STATUS read-back and outbox cancels). The quick view's Edit or Open label
 * (`EventQuickViewSheet`) derives its own answer from the attendee list. The gate is enforced
 * by the client because server-side enforcement is unreliable, verified across multiple
 * servers.
 *
 * RSVP buttons stay interactive regardless: they're what an attendee can do on someone
 * else's event.
 *
 * | Account | Event ORGANIZER     | canEdit |
 * |---------|---------------------|---------|
 * | null    | anything            | false   |
 * | any     | null/blank          | true    |  (lone-author event: the user is the organizer)
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
