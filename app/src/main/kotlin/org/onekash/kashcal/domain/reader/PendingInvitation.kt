package org.onekash.kashcal.domain.reader

/**
 * Holds one pending CalDAV invitation for the inbox, with everything an [InvitationCard] needs
 * and no further lookups.
 *
 * It carries the event's shown fields, not the event row: the inbox query reads only those
 * columns ([org.onekash.kashcal.data.db.dao.EventsDao.getMasterEventsWithFutureOccurrenceFlow]).
 * [eventId] is what an RSVP sends. [occurrenceStartTs] and [occurrenceEndTs] are the next
 * occurrence's times. [accountId] is the owning account; no screen reads it yet.
 * [organizerLabel] is the organizer's CN, else their address, else the owning account's own
 * attendee address.
 */
data class PendingInvitation(
    val eventId: Long,
    val title: String,
    val location: String?,
    val isAllDay: Boolean,
    val occurrenceStartTs: Long,
    val occurrenceEndTs: Long,
    val accountId: Long,
    val calendarColor: Int,
    val organizerLabel: String
)
