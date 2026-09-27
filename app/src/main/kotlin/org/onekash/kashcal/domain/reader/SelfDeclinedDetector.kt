package org.onekash.kashcal.domain.reader

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.identity.matchesAttendee

/**
 * Returns the IDs of events whose owning account has declined them.
 *
 * An attendee row counts only when its address matches the account that owns the event's
 * calendar ([Account.matchesAttendee]), not any configured account. An event in account B's
 * calendar with an attendee sharing account A's address isn't self-declined: it belongs to B.
 *
 * [declinedAttendees] is already filtered to `partstat = 'DECLINED'`
 * ([org.onekash.kashcal.data.db.dao.AttendeesDao.getDeclinedAttendeesForEvents]).
 * [eventIdToCalendarId] maps each event to its calendar. Callers are [DisplayEventRepository],
 * which hides or marks declined events, and the reminder scheduler, which skips their alarms.
 *
 * A lookup miss (unknown event, calendar or account) or an account with no usable address skips
 * the event. The inputs aren't validated.
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
