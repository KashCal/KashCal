package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.db.entity.Event

/**
 * Holds one pending CalDAV invitation for the inbox, with everything an [InvitationCard] needs
 * and no further lookups.
 *
 * [occurrenceStartTs] and [occurrenceEndTs] are the next occurrence's times. [accountId] is the
 * owning account; no screen reads it yet. [organizerLabel] is the organizer's CN, else their
 * address, else the owning account's own attendee address.
 */
data class PendingInvitation(
    val event: Event,
    val occurrenceStartTs: Long,
    val occurrenceEndTs: Long,
    val accountId: Long,
    val calendarColor: Int,
    val organizerLabel: String
)
