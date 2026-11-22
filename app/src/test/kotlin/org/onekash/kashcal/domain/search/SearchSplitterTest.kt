package org.onekash.kashcal.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.ZoneId

/**
 * Pins how one search's matches split into Upcoming, Past, a date range and hidden calendars.
 * Room matches arrive dated by the query (next and last rows); device instances arrive one row
 * per occurrence and are dated here by the same past rule.
 */
class SearchSplitterTest {

    private val zone = ZoneId.of("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_709_650_800_000L // 2024-03-05T15:00:00Z
    private val today = 20240305
    private val visible = Calendar(id = 1, accountId = 1, caldavUrl = "local://a", displayName = "Work", color = 0, isVisible = true)
    private val hidden = Calendar(id = 2, accountId = 1, caldavUrl = "local://b", displayName = "Old", color = 0, isVisible = false)
    private val calendars = mapOf(1L to visible, 2L to hidden)

    private fun event(
        id: Long,
        title: String = "Event $id",
        start: Long = now,
        end: Long = start + hour,
        rrule: String? = null,
        originalEventId: Long? = null,
        calendarId: Long = 1,
        allDay: Boolean = false,
    ) = Event(
        id = id, uid = "uid-${originalEventId ?: id}", calendarId = calendarId, title = title,
        startTs = start, endTs = end, dtstamp = 0, rrule = rrule, originalEventId = originalEventId,
        isAllDay = allDay,
    )

    private fun occ(eventId: Long, start: Long, end: Long = start + hour, exceptionEventId: Long? = null, calendarId: Long = 1) =
        Occurrence(
            id = start, eventId = eventId, calendarId = calendarId, startTs = start, endTs = end,
            startDay = dayCode(start), endDay = dayCode(end), exceptionEventId = exceptionEventId,
        )

    private fun dayCode(ms: Long): Int {
        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    private fun match(event: Event, next: Occurrence? = null, last: Occurrence? = null, inRange: Occurrence? = null, hasRows: Boolean = true) =
        RoomSearchMatch(event, next, last, inRange, hasRows)

    private fun instance(
        eventId: Long,
        start: Long,
        end: Long = start + hour,
        originalId: Long? = null,
        rrule: String? = null,
        calendarId: Long = 50,
        allDay: Boolean = false,
        title: String = "Device $eventId",
    ) = DeviceCalendarInstance(
        instanceId = start, eventId = eventId, title = title, description = "", location = "",
        startTs = start, endTs = end, startDay = dayCode(start), endDay = dayCode(end - 1), isAllDay = allDay,
        hasRrule = rrule != null, rrule = rrule, reminders = emptyList(), calendarId = calendarId,
        calendarDisplayName = "Phone", calendarColor = 0, eventColor = null, status = 1, availability = 0,
        hasAlarm = false, selfAttendeeStatus = 0, isWritable = true, originalId = originalId,
        originalInstanceTime = null, timezone = "UTC", eventStartTs = start,
    )

    private fun split(
        room: List<RoomSearchMatch> = emptyList(),
        device: List<DeviceCalendarInstance> = emptyList(),
        deviceInRange: List<DeviceCalendarInstance>? = null,
        hiddenDevice: Set<Long> = emptySet(),
        range: LongRange? = null,
    ) = SearchSplitter.split(room, calendars, device, deviceInRange, hiddenDevice, now, today, zone, range)

    private fun titles(hits: List<SearchHit>) = hits.map { it.displayEvent.title }

    @Test
    fun `one-off events split by whether they ended`() {
        val past = event(1, "Dermatology", start = now - 60 * day)
        val future = event(2, "Dentist", start = now + 3 * day)
        val outcome = split(
            room = listOf(
                match(past, last = occ(1, now - 60 * day)),
                match(future, next = occ(2, now + 3 * day)),
            ),
        )

        assertEquals(listOf("Dentist"), titles(outcome.upcoming))
        assertEquals(listOf("Dermatology"), titles(outcome.past))
        assertEquals(now - 60 * day, outcome.past.single().displayTs)
        assertNull(outcome.range)
    }

    @Test
    fun `Upcoming is soonest first and Past is most recent first`() {
        val outcome = split(
            device = listOf(
                instance(1, now - 30 * day), instance(2, now - 2 * day), instance(3, now - 400 * day),
                instance(4, now + 9 * day), instance(5, now + day),
            ),
        )

        assertEquals(listOf("Device 5", "Device 4"), titles(outcome.upcoming))
        assertEquals(listOf("Device 2", "Device 1", "Device 3"), titles(outcome.past))
    }

    @Test
    fun `a device event in progress is upcoming`() {
        val outcome = split(device = listOf(instance(1, now - hour, end = now + hour)))

        assertEquals(listOf("Device 1"), titles(outcome.upcoming))
        assertTrue(outcome.past.isEmpty())
    }

    @Test
    fun `a device all-day event today is upcoming until the day ends`() {
        val midnight = now - 15 * hour
        val outcome = split(device = listOf(instance(1, midnight, end = midnight + day, allDay = true)))

        assertEquals(listOf("Device 1"), titles(outcome.upcoming))
    }

    @Test
    fun `a device series is listed once on each side at its next and its last occurrence`() {
        val weekly = (-3..3).map { instance(9, now + it * 7 * day + hour, rrule = "FREQ=WEEKLY") }
        val outcome = split(device = weekly)

        assertEquals(1, outcome.upcoming.size)
        assertEquals(now + hour, outcome.upcoming.single().displayTs)
        assertTrue(outcome.upcoming.single().isSeries)
        assertEquals(1, outcome.past.size)
        assertEquals(now - 7 * day + hour, outcome.past.single().displayTs)
    }

    @Test
    fun `a device series with a changed occurrence is grouped by its series`() {
        val outcome = split(
            device = listOf(
                instance(9, now + day, rrule = "FREQ=DAILY"),
                instance(10, now + 2 * day, originalId = 9, title = "Moved"),
                instance(9, now + 3 * day, rrule = "FREQ=DAILY"),
            ),
        )

        assertEquals(1, outcome.upcoming.size)
        assertEquals(now + day, outcome.upcoming.single().displayTs)
    }

    @Test
    fun `an ended series is only past and a series not started is only upcoming`() {
        val ended = event(1, "Ended", start = now - 900 * day, rrule = "FREQ=WEEKLY;COUNT=3")
        val unstarted = event(2, "Unstarted", start = now + 30 * day, rrule = "FREQ=WEEKLY")
        val outcome = split(
            room = listOf(
                match(ended, last = occ(1, now - 886 * day)),
                match(unstarted, next = occ(2, now + 30 * day)),
            ),
        )

        assertEquals(listOf("Unstarted"), titles(outcome.upcoming))
        assertEquals(listOf("Ended"), titles(outcome.past))
        assertTrue(outcome.past.single().isSeries)
    }

    @Test
    fun `a series matched only through a renamed occurrence is listed at that occurrence`() {
        val exception = event(11, "Dermatology follow-up", start = now - 10 * day, originalEventId = 10)
        val outcome = split(
            room = listOf(match(exception, last = occ(10, now - 10 * day, exceptionEventId = 11))),
        )

        assertTrue(outcome.upcoming.isEmpty())
        val hit = outcome.past.single()
        assertEquals("Dermatology follow-up", hit.displayEvent.title)
        assertEquals(now - 10 * day, hit.displayTs)
        assertTrue(hit.isSeries)
    }

    @Test
    fun `a series with two changed occurrences that also match is listed once per side (#149)`() {
        val master = event(10, "Team sync", start = now - 70 * day, rrule = "FREQ=WEEKLY")
        val changedPast = event(11, "Team sync (moved)", start = now - 14 * day, originalEventId = 10)
        val changedFuture = event(12, "Team sync (room B)", start = now + 14 * day, originalEventId = 10)
        val outcome = split(
            room = listOf(
                match(master, next = occ(10, now + 7 * day), last = occ(10, now - 7 * day)),
                match(changedPast, last = occ(10, now - 14 * day, exceptionEventId = 11)),
                match(changedFuture, next = occ(10, now + 14 * day, exceptionEventId = 12)),
            ),
        )

        assertEquals(1, outcome.upcoming.size)
        assertEquals(1, outcome.past.size)
        assertEquals("Team sync", outcome.upcoming.single().displayEvent.title)
        assertEquals(now + 7 * day, outcome.upcoming.single().displayTs)
        assertEquals(now - 7 * day, outcome.past.single().displayTs)
    }

    @Test
    fun `merged series rows take the member with the nearest occurrence`() {
        val master = event(10, "Standup", start = now - 70 * day, rrule = "FREQ=WEEKLY")
        val changed = event(11, "Standup early", start = now + 2 * day, originalEventId = 10)
        val changedPast = event(12, "Standup late", start = now - day, originalEventId = 10)
        val outcome = split(
            room = listOf(
                match(master, next = occ(10, now + 7 * day), last = occ(10, now - 7 * day)),
                match(changed, next = occ(10, now + 2 * day, exceptionEventId = 11)),
                match(changedPast, last = occ(10, now - day, exceptionEventId = 12)),
            ),
        )

        assertEquals("Standup early", outcome.upcoming.single().displayEvent.title)
        assertEquals(now + 2 * day, outcome.upcoming.single().displayTs)
        assertEquals("Standup late", outcome.past.single().displayEvent.title)
        assertEquals(now - day, outcome.past.single().displayTs)
    }

    @Test
    fun `a match with no next and no last row is not listed when it has rows`() {
        // Every occurrence cancelled or changed: the rows exist, none dates the master itself.
        val master = event(10, "All moved", start = now - 70 * day, rrule = "FREQ=WEEKLY;COUNT=2")
        val outcome = split(room = listOf(match(master, hasRows = true)))

        assertTrue(outcome.upcoming.isEmpty())
        assertTrue(outcome.past.isEmpty())
    }

    @Test
    fun `a match with no occurrence row at all is listed at its own start`() {
        val pastNoRows = event(1, "Imported old", start = now - 5 * day, rrule = "FREQ=WEEKLY;COUNT=1")
        val futureNoRows = event(2, "Imported new", start = now + 5 * day)
        val outcome = split(
            room = listOf(match(pastNoRows, hasRows = false), match(futureNoRows, hasRows = false)),
        )

        assertEquals(listOf("Imported new"), titles(outcome.upcoming))
        assertEquals(now + 5 * day, outcome.upcoming.single().displayTs)
        assertEquals(listOf("Imported old"), titles(outcome.past))
        assertEquals(now - 5 * day, (outcome.past.single().displayEvent as DisplayEvent.Room).occurrence.startTs)
    }

    @Test
    fun `matches in hidden calendars go to the hidden lists`() {
        val shown = event(1, "Shown", start = now + day)
        val inHidden = event(2, "In hidden", start = now + 2 * day, calendarId = 2)
        val inHiddenPast = event(3, "In hidden past", start = now - 2 * day, calendarId = 2)
        val outcome = split(
            room = listOf(
                match(shown, next = occ(1, now + day)),
                match(inHidden, next = occ(2, now + 2 * day, calendarId = 2)),
                match(inHiddenPast, last = occ(3, now - 2 * day, calendarId = 2)),
            ),
            device = listOf(instance(7, now + 3 * day, calendarId = 60), instance(8, now + 4 * day, calendarId = 50)),
            hiddenDevice = setOf(60L),
        )

        assertEquals(listOf("Shown", "Device 8"), titles(outcome.upcoming))
        assertTrue(outcome.past.isEmpty())
        assertEquals(listOf("In hidden", "Device 7"), titles(outcome.hiddenUpcoming))
        assertEquals(listOf("In hidden past"), titles(outcome.hiddenPast))
        assertTrue(outcome.hiddenUpcoming.all { it.isHidden })
    }

    @Test
    fun `a range gives one list in date order and leaves Upcoming and Past as they are`() {
        val range = (now - 3 * day)..(now + 3 * day)
        val inside = event(1, "Inside", start = now - 70 * day, rrule = "FREQ=DAILY")
        val outside = event(2, "Outside", start = now + 30 * day)
        val noRows = event(3, "No rows", start = now + day)
        val room = listOf(
            match(inside, next = occ(1, now + hour), last = occ(1, now - day + hour), inRange = occ(1, now - 3 * day + hour)),
            match(outside, next = occ(2, now + 30 * day)),
            match(noRows, hasRows = false),
        )
        val withRange = split(room = room, deviceInRange = listOf(instance(7, now - 2 * day)), range = range)
        val without = split(room = room)

        assertEquals(listOf("Inside", "Device 7", "No rows"), titles(withRange.range!!))
        assertEquals(now - 3 * day + hour, withRange.range!!.first().displayTs)
        assertEquals(titles(without.upcoming), titles(withRange.upcoming))
        assertEquals(titles(without.past), titles(withRange.past))
        assertTrue(withRange.hiddenRange!!.isEmpty())
    }

    @Test
    fun `all-day and timed matches are ordered by their local date, so Past years never repeat`() {
        val ny = ZoneId.of("America/New_York")
        fun timed(date: java.time.LocalDate, hour: Int) = date.atTime(hour, 0).atZone(ny).toInstant().toEpochMilli()
        val utcMidnight = java.time.LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 21:00 on Dec 31 in New York is 02:00 UTC on Jan 1, after the all-day Jan 1's UTC
        // midnight.
        val party = instance(1, timed(java.time.LocalDate.of(2025, 12, 31), 21), title = "Party")
        val newYear = instance(2, utcMidnight, end = utcMidnight + day, allDay = true, title = "New Year")
        val summer = instance(3, timed(java.time.LocalDate.of(2025, 6, 1), 10), title = "Summer")
        val later = java.time.LocalDate.of(2026, 3, 1)
        val outcome = SearchSplitter.split(
            emptyList(), calendars, listOf(party, newYear, summer), null, emptySet(),
            timed(later, 12), 20260301, ny, null,
        )

        assertEquals(listOf("New Year", "Party", "Summer"), titles(outcome.past))
        assertEquals(mapOf(0 to 2026, 1 to 2025), org.onekash.kashcal.ui.screens.pastYearHeaders(outcome.past, ny))
    }
}
