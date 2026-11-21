package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.db.dao.PendingInvitationRow
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Builds the inbox's [PendingInvitation] list, sorted by next occurrence start.
 *
 * Inputs are already filtered in SQL:
 * - [invitationRows]: masters and one-off events with an occurrence not yet ended and not
 *   cancelled, excluding PENDING_DELETE rows
 *   ([org.onekash.kashcal.data.db.dao.EventsDao.getMasterEventsWithFutureOccurrenceFlow]).
 * - [needsActionAttendees]: those events' `partstat = NEEDS-ACTION` attendee rows
 *   ([org.onekash.kashcal.data.db.dao.AttendeesDao.getNeedsActionAttendeesForEventsFlow]).
 * - [accountsById] and [calendarsById]: every account and calendar at emission time.
 *
 * The rest needs canonical address matching ([Account.matchesAttendee]), so it runs here:
 * 1. Each event maps to its owning account through its calendar; a lookup miss skips it.
 * 2. The owning account must have a NEEDS-ACTION attendee row for the event.
 * 3. Events the owning account organizes are excluded: a non-blank `organizerEmail` that
 *    matches it. A blank or null organizer passes, so the RSVP surface still works for an
 *    invitation whose ORGANIZER is missing.
 *
 * Only the owning account's identity counts: an event in account B's calendar whose attendee
 * shares one of account A's addresses is checked against B and never shows in A's inbox.
 */
fun buildPendingInvitations(
    invitationRows: List<PendingInvitationRow>,
    needsActionAttendees: List<Attendee>,
    accountsById: Map<Long, Account>,
    calendarsById: Map<Long, Calendar>
): List<PendingInvitation> {
    if (invitationRows.isEmpty()) return emptyList()

    val attendeesByEvent: Map<Long, List<Attendee>> =
        needsActionAttendees.groupBy { it.eventId }

    val out = mutableListOf<PendingInvitation>()
    for (row in invitationRows) {
        if (row.originalEventId != null) continue
        val calendar = calendarsById[row.calendarId] ?: continue
        val account = accountsById[calendar.accountId] ?: continue

        val attendeeRows = attendeesByEvent[row.id].orEmpty()
        val ownerAttendee = attendeeRows.firstOrNull { account.matchesAttendee(it.address) }
            ?: continue

        val organizerEmail = row.organizerEmail
        if (!organizerEmail.isNullOrBlank() && account.matchesAttendee(organizerEmail)) {
            continue
        }

        val nextStart = row.nextOccurrenceTs ?: row.startTs
        val duration = (row.endTs - row.startTs).coerceAtLeast(0L)
        val nextEnd = nextStart + duration

        out += PendingInvitation(
            eventId = row.id,
            title = row.title,
            location = row.location,
            isAllDay = row.isAllDay,
            occurrenceStartTs = nextStart,
            occurrenceEndTs = nextEnd,
            accountId = account.id,
            calendarColor = calendar.localColorOverride ?: calendar.color,
            organizerLabel = organizerLabel(row.organizerName, organizerEmail, ownerAttendee)
        )
    }
    return out.sortedBy { it.occurrenceStartTs }
}

private fun organizerLabel(
    organizerName: String?,
    organizerEmail: String?,
    fallback: Attendee
): String {
    val name = organizerName?.trim()?.takeIf { it.isNotEmpty() }
    if (name != null) return name
    val email = organizerEmail?.trim()?.takeIf { it.isNotEmpty() }
        ?.let(AddressNormalizer::stripMailto)
    if (!email.isNullOrEmpty()) return email
    return AddressNormalizer.stripMailto(fallback.address)
}
