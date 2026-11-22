package org.onekash.kashcal.domain.search

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.testutil.PropertyRuns
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Checks [SearchSplitter.split] over generated Room matches: calendars shown and hidden, series
 * with changed occurrences in the series' calendar, one-off events, a random now, and occurrence
 * rows (timed and all-day, some cancelled, some linked to a changed occurrence). Each match is
 * dated from the rows by the rule of
 * [org.onekash.kashcal.data.db.dao.EventSearchSql.nextAndLast], and the properties check the
 * split per series (the master's id, or the changed occurrence's original_event_id). Each case
 * is built from one generated seed and prints in full on failure.
 */
class SearchSplitterPropertiesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val base = LocalDate.of(2026, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Holds one occurrence row as the occurrences table stores it. */
    private data class Row(
        val eventId: Long,
        val exceptionEventId: Long?,
        val calendarId: Long,
        val start: Long,
        val end: Long,
        val endDay: Int,
        val cancelled: Boolean,
    )

    private class Case(
        val seed: Long,
        val now: Long,
        val today: LocalDate,
        val calendars: List<Calendar>,
        val events: List<Event>,
        val rows: List<Row>,
        val matched: List<Event>,
    ) {
        val todayCode: Int = DayPagerUtils.localDateToDayCode(today)

        override fun toString(): String = buildString {
            append("Case(seed=$seed, now=${Instant.ofEpochMilli(now)}, today=$today)\n")
            calendars.forEach { append("  calendar ${it.id} visible=${it.isVisible}\n") }
            events.forEach { e ->
                append("  event ${e.id} original=${e.originalEventId} calendar=${e.calendarId}")
                append(" rrule=${e.rrule} allDay=${e.isAllDay} title=${e.title}")
                append(" ${Instant.ofEpochMilli(e.startTs)}..${Instant.ofEpochMilli(e.endTs)}")
                append(if (e in matched) " matched\n" else "\n")
            }
            rows.forEach { r ->
                append("  row event=${r.eventId} exception=${r.exceptionEventId}")
                append(" ${Instant.ofEpochMilli(r.start)}..${Instant.ofEpochMilli(r.end)}")
                append(" endDay=${r.endDay} cancelled=${r.cancelled}\n")
            }
        }
    }

    private val cases = Arb.long(0L, Long.MAX_VALUE).map { buildCase(it) }

    private fun dayCode(date: LocalDate) = DayPagerUtils.localDateToDayCode(date)

    private fun buildCase(seed: Long): Case {
        val r = Random(seed)
        val now = base + r.nextLong(-400 * day, 400 * day)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val calendars = (1..r.nextInt(1, 4)).map {
            Calendar(
                id = it.toLong(), accountId = 1, caldavUrl = "local://$it", displayName = "Calendar $it",
                color = 0, isVisible = r.nextInt(3) != 0,
            )
        }

        // Timed spans start on a half-hour step from now, so some end at now itself; all-day
        // spans start at UTC midnight a few days around today and end 1 ms before their DTEND.
        fun span(allDay: Boolean): Triple<Long, Long, Int> = if (allDay) {
            val start = today.plusDays(r.nextLong(-4, 5)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = start + r.nextLong(1, 4) * day - 1
            Triple(start, end, DateTimeUtils.eventTsToEndDayCode(end, start, true, zone))
        } else {
            val start = now + r.nextLong(-200, 201) * 30 * minute
            val end = start + r.nextLong(0, 97) * 30 * minute
            Triple(start, end, DateTimeUtils.eventTsToEndDayCode(end, start, false, zone))
        }

        val titles = listOf("Dentist", "Standup", "Team sync")
        val events = mutableListOf<Event>()
        val rows = mutableListOf<Row>()
        var nextId = 1L
        repeat(r.nextInt(1, 7)) {
            val calendarId = calendars[r.nextInt(calendars.size)].id
            val isSeries = r.nextBoolean()
            val masterAllDay = r.nextInt(4) == 0
            val (start, end, endDay) = span(masterAllDay)
            val masterId = nextId++
            val master = Event(
                id = masterId, uid = "uid-$masterId", calendarId = calendarId, title = titles.random(r),
                startTs = start, endTs = end, dtstamp = 0, rrule = if (isSeries) "FREQ=DAILY" else null,
                isAllDay = masterAllDay,
            )
            events += master
            if (isSeries) {
                repeat(r.nextInt(0, 5)) {
                    val (s, e, d) = span(masterAllDay)
                    rows += Row(master.id, null, calendarId, s, e, d, cancelled = r.nextInt(5) == 0)
                }
                repeat(r.nextInt(0, 3)) {
                    val allDay = r.nextInt(4) == 0
                    val (s, e, d) = span(allDay)
                    val exception = Event(
                        id = nextId++, uid = master.uid, calendarId = calendarId, title = titles.random(r),
                        startTs = s, endTs = e, dtstamp = 0, originalEventId = master.id, isAllDay = allDay,
                    )
                    events += exception
                    // A changed occurrence dates by the series row linked to it, if any.
                    if (r.nextInt(4) != 0) {
                        rows += Row(master.id, exception.id, calendarId, s, e, d, cancelled = r.nextInt(5) == 0)
                    }
                }
            } else if (r.nextInt(4) != 0) {
                rows += Row(master.id, null, calendarId, start, end, endDay, cancelled = r.nextInt(8) == 0)
            }
        }
        // One row per series and start, as the table keeps it.
        val distinctRows = rows.distinctBy { it.eventId to it.start }
        val matched = events.filter { r.nextInt(10) < 7 }.shuffled(r)
        return Case(seed, now, today, calendars, events, distinctRows, matched)
    }

    /** Returns the rows that date [event] in the search query: its own, or the one linked to it. */
    private fun ownRows(case: Case, event: Event): List<Row> = case.rows.filter {
        if (event.originalEventId == null) {
            it.eventId == event.id && it.exceptionEventId == null
        } else {
            it.exceptionEventId == event.id
        }
    }

    /** Returns whether any row, cancelled or linked, belongs to [event]. */
    private fun hasRows(case: Case, event: Event): Boolean =
        case.rows.any { it.eventId == event.id || it.exceptionEventId == event.id }

    /** Returns whether a span ended: a timed one before now, an all-day one before today. */
    private fun ended(case: Case, allDay: Boolean, end: Long, endDay: Int): Boolean =
        if (allDay) endDay < case.todayCode else end < case.now

    private fun ownEnded(case: Case, event: Event): Boolean = if (event.isAllDay) {
        dayCode(Instant.ofEpochMilli(event.endTs).atZone(ZoneOffset.UTC).toLocalDate()) < case.todayCode
    } else {
        event.endTs < case.now
    }

    private fun occurrence(case: Case, event: Event, row: Row) = Occurrence(
        id = case.rows.indexOf(row) + 1L,
        eventId = row.eventId,
        calendarId = row.calendarId,
        startTs = row.start,
        endTs = row.end,
        startDay = DateTimeUtils.eventTsToDayCode(row.start, event.isAllDay, zone),
        endDay = row.endDay,
        isCancelled = row.cancelled,
        exceptionEventId = row.exceptionEventId,
    )

    /** Dates each match as the search SQL does: next open, last ended, cancelled rows skipped. */
    private fun matches(case: Case): List<RoomSearchMatch> = case.matched.map { event ->
        val live = ownRows(case, event).filterNot { it.cancelled }
        val (done, open) = live.partition { ended(case, event.isAllDay, it.end, it.endDay) }
        RoomSearchMatch(
            event = event,
            next = open.minByOrNull { it.start }?.let { occurrence(case, event, it) },
            last = done.maxByOrNull { it.start }?.let { occurrence(case, event, it) },
            inRange = null,
            hasOccurrences = hasRows(case, event),
        )
    }

    private fun split(case: Case): SearchOutcome = SearchSplitter.split(
        room = matches(case),
        calendars = case.calendars.associateBy { it.id },
        device = emptyList(),
        deviceInRange = null,
        hiddenDeviceCalendarIds = emptySet(),
        now = case.now,
        todayDayCode = case.todayCode,
        zone = zone,
        range = null,
    )

    private fun SearchHit.room(): Event = (displayEvent as DisplayEvent.Room).event

    private fun SearchHit.seriesKey(): Long = room().let { it.originalEventId ?: it.id }

    private fun SearchHit.describe(): String =
        "event ${room().id} '${displayEvent.title}' at ${Instant.ofEpochMilli(displayTs)} allDay=${displayEvent.isAllDay}"

    @Test
    fun `a series is upcoming when a member has an open row and past when one has an ended row`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(200), cases) { case ->
            val outcome = split(case)
            val visible = case.calendars.associate { it.id to it.isVisible }
            for ((key, members) in case.matched.groupBy { it.originalEventId ?: it.id }) {
                val expectUpcoming = members.any { m ->
                    ownRows(case, m).any { !it.cancelled && !ended(case, m.isAllDay, it.end, it.endDay) } ||
                        (!hasRows(case, m) && !ownEnded(case, m))
                }
                val expectPast = members.any { m ->
                    ownRows(case, m).any { !it.cancelled && ended(case, m.isAllDay, it.end, it.endDay) } ||
                        (!hasRows(case, m) && ownEnded(case, m))
                }
                val shown = visible.getValue(members.first().calendarId)
                fun listed(hits: List<SearchHit>) = hits.any { it.seriesKey() == key }
                assertEquals("series $key in upcoming\n$case", expectUpcoming && shown, listed(outcome.upcoming))
                assertEquals("series $key in hidden upcoming\n$case", expectUpcoming && !shown, listed(outcome.hiddenUpcoming))
                assertEquals("series $key in past\n$case", expectPast && shown, listed(outcome.past))
                assertEquals("series $key in hidden past\n$case", expectPast && !shown, listed(outcome.hiddenPast))
            }
            val matchedKeys = case.matched.map { it.originalEventId ?: it.id }.toSet()
            val listedKeys = (outcome.upcoming + outcome.past + outcome.hiddenUpcoming + outcome.hiddenPast)
                .map { it.seriesKey() }.toSet()
            assertTrue("listed series not matched: ${listedKeys - matchedKeys}\n$case", matchedKeys.containsAll(listedKeys))
        }
    }

    @Test
    fun `a series is listed at most once on each side (#149)`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(200), cases) { case ->
            val outcome = split(case)
            for ((side, hits) in listOf("upcoming" to outcome.upcoming + outcome.hiddenUpcoming, "past" to outcome.past + outcome.hiddenPast)) {
                val repeated = hits.groupBy { it.seriesKey() }.filterValues { it.size > 1 }
                assertTrue(
                    "series listed more than once in $side: ${repeated.mapValues { (_, v) -> v.map { it.describe() } }}\n$case",
                    repeated.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `upcoming is soonest first and past most recent first`(): Unit = runBlocking {
        val order = SearchSplitter.byDate(zone)
        checkAll(PropertyRuns.config(200), cases) { case ->
            val outcome = split(case)
            val lists = listOf(
                Triple("upcoming", outcome.upcoming, order),
                Triple("hidden upcoming", outcome.hiddenUpcoming, order),
                Triple("past", outcome.past, order.reversed()),
                Triple("hidden past", outcome.hiddenPast, order.reversed()),
            )
            for ((name, hits, expected) in lists) {
                hits.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "$name out of order: ${a.describe()} before ${b.describe()}\n$case",
                        expected.compare(a, b) <= 0,
                    )
                }
            }
        }
    }

    @Test
    fun `hidden calendars fill only the hidden lists`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(200), cases) { case ->
            val outcome = split(case)
            val visible = case.calendars.associate { it.id to it.isVisible }
            for (hit in outcome.upcoming + outcome.past) {
                assertTrue("shown list holds ${hit.describe()} from a hidden calendar\n$case", visible.getValue(hit.room().calendarId))
                assertTrue("shown list holds a hit marked hidden: ${hit.describe()}\n$case", !hit.isHidden)
            }
            for (hit in outcome.hiddenUpcoming + outcome.hiddenPast) {
                assertTrue("hidden list holds ${hit.describe()} from a shown calendar\n$case", !visible.getValue(hit.room().calendarId))
                assertTrue("hidden list holds a hit not marked hidden: ${hit.describe()}\n$case", hit.isHidden)
            }
        }
    }
}
