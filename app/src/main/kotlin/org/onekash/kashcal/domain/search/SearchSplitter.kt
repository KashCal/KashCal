package org.onekash.kashcal.domain.search

import kotlinx.collections.immutable.toPersistentList
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import java.time.ZoneId

/**
 * Splits one search's matches into Upcoming, Past, an optional date range and hidden calendars.
 *
 * A match is upcoming at its earliest occurrence not yet ended and past at its latest ended
 * one, by [DateTimeUtils.isEventPast]: an event in progress, or an all-day event today, is
 * upcoming. A series with both is listed once on each side. A series' members (the master and
 * its changed occurrences, or a device series and its exceptions) share one row per side, dated
 * by the member with the nearest occurrence there, so a series is never listed once per change
 * (#149). A Room match with no occurrence row at all is dated by its own start and end.
 *
 * Upcoming and the range are soonest first; Past is most recent first. A match in a calendar
 * hidden in the app ([Calendar.isVisible] false, or a device calendar in the hidden set) goes to
 * the hidden lists instead.
 */
object SearchSplitter {

    private class Candidate(val seriesKey: String, val hit: SearchHit)

    fun split(
        room: List<RoomSearchMatch>,
        calendars: Map<Long, Calendar>,
        device: List<DeviceCalendarInstance>,
        deviceInRange: List<DeviceCalendarInstance>?,
        hiddenDeviceCalendarIds: Set<Long>,
        now: Long,
        todayDayCode: Int,
        zone: ZoneId,
        range: LongRange?,
        timing: SearchTiming = SearchTiming(0, 0, 0),
    ): SearchOutcome {
        val upcoming = mutableListOf<Candidate>()
        val past = mutableListOf<Candidate>()
        val inRange = mutableListOf<Candidate>()

        for (match in room) {
            val event = match.event
            val calendar = calendars[event.calendarId]
            val key = "room:${event.originalEventId ?: event.id}"
            fun candidate(occurrence: Occurrence) = Candidate(
                key,
                SearchHit(
                    displayEvent = DisplayEvent.Room(event, occurrence, calendar, zone = zone),
                    displayTs = occurrence.startTs,
                    isSeries = !event.rrule.isNullOrBlank() || event.originalEventId != null,
                    isHidden = calendar?.isVisible == false,
                ),
            )
            if (match.hasOccurrences) {
                match.next?.let { upcoming += candidate(it) }
                match.last?.let { past += candidate(it) }
                match.inRange?.let { inRange += candidate(it) }
            } else {
                val own = Occurrence(
                    eventId = event.id,
                    calendarId = event.calendarId,
                    startTs = event.startTs,
                    endTs = event.endTs,
                    startDay = DateTimeUtils.eventTsToDayCode(event.startTs, event.isAllDay, zone),
                    endDay = DateTimeUtils.eventTsToEndDayCode(event.endTs, event.startTs, event.isAllDay, zone),
                )
                val ended = DateTimeUtils.isEventPast(own.endTs, own.endDay, event.isAllDay, now, todayDayCode)
                (if (ended) past else upcoming) += candidate(own)
                if (range != null && own.startTs <= range.last && own.endTs >= range.first) inRange += candidate(own)
            }
        }

        fun deviceCandidate(instance: DeviceCalendarInstance) = Candidate(
            "device:${instance.originalId ?: instance.eventId}",
            SearchHit(
                displayEvent = DisplayEvent.Device(instance),
                displayTs = instance.startTs,
                isSeries = instance.hasRrule || instance.originalId != null,
                isHidden = instance.calendarId in hiddenDeviceCalendarIds,
            ),
        )
        for (instance in device) {
            val ended = DateTimeUtils.isEventPast(instance.endTs, instance.endDay, instance.isAllDay, now, todayDayCode)
            (if (ended) past else upcoming) += deviceCandidate(instance)
        }
        deviceInRange?.forEach { inRange += deviceCandidate(it) }

        val order = byDate(zone)
        val upcomingRows = nearest(upcoming, order)
        val pastRows = nearest(past, order.reversed())
        val rangeRows = if (range != null) nearest(inRange, order) else null
        return SearchOutcome(
            upcoming = upcomingRows.filterNot { it.isHidden }.toPersistentList(),
            past = pastRows.filterNot { it.isHidden }.toPersistentList(),
            hiddenUpcoming = upcomingRows.filter { it.isHidden }.toPersistentList(),
            hiddenPast = pastRows.filter { it.isHidden }.toPersistentList(),
            range = rangeRows?.filterNot { it.isHidden }?.toPersistentList(),
            hiddenRange = rangeRows?.filter { it.isHidden }?.toPersistentList(),
            timing = timing,
        )
    }

    /**
     * Orders hits soonest first by when they happen on the calendar in [zone]: an all-day
     * event at the start of its day there, though it is stored at UTC midnight, so it sorts
     * with that day's timed events and a list's years run in order.
     */
    fun byDate(zone: ZoneId): Comparator<SearchHit> = compareBy<SearchHit> { hit ->
        val e = hit.displayEvent
        if (e.isAllDay) {
            DateTimeUtils.eventTsToLocalDate(hit.displayTs, true, zone).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            hit.displayTs
        }
    }.thenBy { it.displayEvent.title }

    /** Keeps one row per series, the first by [order], and orders the rows by it. */
    private fun nearest(candidates: List<Candidate>, order: Comparator<SearchHit>): List<SearchHit> =
        candidates
            .groupBy { it.seriesKey }
            .map { (_, members) -> members.map { it.hit }.sortedWith(order).first() }
            .sortedWith(order)
}
