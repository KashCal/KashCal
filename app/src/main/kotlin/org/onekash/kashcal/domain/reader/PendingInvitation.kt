package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.db.entity.Event

/**
 * One pending CalDAV invitation surfaced in the inbox.
 *
 * Carries enough state to render an [InvitationCard] without further
 * lookups: the underlying [event] for title/location/RSVP write,
 * the next-occurrence times so the card shows the right date/range, the
 * owning [accountId] so multi-account UI can disambiguate (currently
 * unused for grouping but kept for v2), the calendar's display [color],
 * and the human-readable [organizerLabel] (CN if present, else address).
 */
data class PendingInvitation(
    val event: Event,
    val occurrenceStartTs: Long,
    val occurrenceEndTs: Long,
    val accountId: Long,
    val calendarColor: Int,
    val organizerLabel: String
)
