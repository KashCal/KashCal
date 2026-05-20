package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.identity.matchesAttendee

/**
 * Pure helper that resolves "which event IDs are declined by their
 * owning account" from the inputs available to the composite-reader
 * layer (DisplayEventRepository).
 *
 * Multi-account scoping rule: each event maps to a calendar, each
 * calendar to an account; the attendee row's address must match THAT
 * account's `calendar_user_addresses`, not just any configured account.
 * An event in account B's calendar with an attendee that happens to
 * share account A's address is NOT considered self-declined under A's
 * preference — it belongs to B's identity scope.
 *
 * Inputs are pre-filtered Room data:
 * - [declinedAttendees] is the result of
 *   [org.onekash.kashcal.data.db.dao.AttendeesDao.getDeclinedAttendeesForEvents]
 *   (already SQL-filtered to `partstat = 'DECLINED'`).
 * - [accountsById] / [calendarsById] are full snapshots from
 *   AccountsDao / CalendarsDao at emission time.
 * - [eventIdToCalendarId] is the per-emission event→calendar map (the
 *   composite already has each event's calendar in scope via the
 *   occurrence row).
 *
 * Lookup misses (orphaned event, calendar pointing at unknown account,
 * account with no usable address) are graceful skips — return without
 * adding the event ID. The data the composite layer feeds in is
 * authoritative; this helper does not validate it.
 */
fun selfDeclinedEventIds(
    declinedAttendees: List<Attendee>,
    accountsById: Map<Long, Account>,
    eventIdToCalendarId: Map<Long, Long>,
    calendarsById: Map<Long, Calendar>
): Set<Long> {
    if (declinedAttendees.isEmpty()) return emptySet()
    val result = mutableSetOf<Long>()
    for (attendee in declinedAttendees) {
        if (attendee.eventId in result) continue
        val calendarId = eventIdToCalendarId[attendee.eventId] ?: continue
        val calendar = calendarsById[calendarId] ?: continue
        val account = accountsById[calendar.accountId] ?: continue
        if (account.matchesAttendee(attendee.address)) {
            result.add(attendee.eventId)
        }
    }
    return result
}
