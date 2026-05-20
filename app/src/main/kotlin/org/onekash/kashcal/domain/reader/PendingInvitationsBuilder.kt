package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Pure helper that materializes the inbox's [PendingInvitation] list from
 * already-Room-filtered inputs.
 *
 * Inputs:
 * - [eventsWithNext]: master events with at least one future, non-cancelled
 *   occurrence (SQL-filtered upstream by
 *   [org.onekash.kashcal.data.db.dao.EventsDao.getMasterEventsWithFutureOccurrenceFlow]).
 * - [needsActionAttendees]: ATTENDEE rows for those event IDs whose
 *   `partstat = NEEDS-ACTION` (SQL-filtered upstream by
 *   [org.onekash.kashcal.data.db.dao.AttendeesDao.getNeedsActionAttendeesForEvents]).
 * - [accountsById] / [calendarsById]: full snapshots at emission time.
 *
 * Decision policy applied here (Kotlin-side, since canonical-address
 * matching needs [Account.matchesAttendee]):
 * 1. Each event maps to its owning account via `event.calendarId →
 *    calendar.accountId`. Lookup misses are skipped.
 * 2. The owning account must have a NEEDS-ACTION attendee row for that
 *    event whose address canonicalizes to one of the account's addresses.
 * 3. Events the owning account organizes are excluded — that is, when
 *    `event.organizerEmail` is non-blank AND matches the owning account.
 *    Blank/null organizer is allowed through (defensive: a true invitation
 *    has ORGANIZER on the wire, but if the row is missing we still want
 *    the user's response surface to work).
 * 4. Output is sorted ascending by occurrence start.
 *
 * Multi-account scoping: an event in account B's calendar with an
 * attendee whose address happens to also be one of account A's addresses
 * does NOT show up in A's inbox — it is checked against B (the owning
 * account), and only B's identity matters.
 */
fun buildPendingInvitations(
    eventsWithNext: List<EventWithNextOccurrence>,
    needsActionAttendees: List<Attendee>,
    accountsById: Map<Long, Account>,
    calendarsById: Map<Long, Calendar>
): List<PendingInvitation> {
    if (eventsWithNext.isEmpty()) return emptyList()

    val attendeesByEvent: Map<Long, List<Attendee>> =
        needsActionAttendees.groupBy { it.eventId }

    val out = mutableListOf<PendingInvitation>()
    for (ewn in eventsWithNext) {
        val event = ewn.event
        if (event.originalEventId != null) continue
        val calendar = calendarsById[event.calendarId] ?: continue
        val account = accountsById[calendar.accountId] ?: continue

        val rows = attendeesByEvent[event.id].orEmpty()
        val ownerAttendee = rows.firstOrNull { account.matchesAttendee(it.address) }
            ?: continue

        val organizerEmail = event.organizerEmail
        if (!organizerEmail.isNullOrBlank() && account.matchesAttendee(organizerEmail)) {
            continue
        }

        val nextStart = ewn.nextOccurrenceTs ?: event.startTs
        val duration = (event.endTs - event.startTs).coerceAtLeast(0L)
        val nextEnd = nextStart + duration

        out += PendingInvitation(
            event = event,
            occurrenceStartTs = nextStart,
            occurrenceEndTs = nextEnd,
            accountId = account.id,
            calendarColor = calendar.localColorOverride ?: calendar.color,
            organizerLabel = organizerLabel(event.organizerName, organizerEmail, ownerAttendee)
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
