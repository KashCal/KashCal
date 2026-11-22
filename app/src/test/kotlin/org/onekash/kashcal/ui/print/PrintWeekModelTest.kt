package org.onekash.kashcal.ui.print

import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents.HOUR
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.domain.planner.PlannerTestEvents.device
import org.onekash.kashcal.domain.planner.PlannerTestEvents.room
import org.onekash.kashcal.domain.planner.PlannerTestEvents.roomAllDay
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Tests [buildPrintWeek]: which events print where, the grid's hours and placement (the Week
 * view's own layout), numbering, and the details' text. The system zone is pinned per test,
 * because the model and [WeekViewUtils.positionEventsForDay] both read it.
 */
class PrintWeekModelTest {

    private lateinit var savedZone: TimeZone
    private val ny = ZoneId.of("America/New_York")

    // Sun Oct 25 to Sat Oct 31, 2026.
    private val sunday = LocalDate.of(2026, 10, 25)
    private val friday = sunday.plusDays(5)

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ny))
    }

    @After
    fun tearDown() = TimeZone.setDefault(savedZone)

    private fun formats(pattern: String = "HH:mm") = PrintFormats(
        locale = Locale.US,
        timePattern = pattern,
        weekdayPattern = "EEE",
        shortDatePattern = "MMM d",
        dayHeaderPattern = "EEE, MMM d",
        allDayLabel = "All day",
        join = { a, b -> "$a–$b" },
        moved = { "was $it" },
    )

    private fun build(
        events: List<DisplayEvent>,
        weekStart: LocalDate = sunday,
        grid: Boolean = true,
        details: Boolean = true,
        strip: Boolean = false,
        pattern: String = "HH:mm",
        calendars: List<PrintCalendar> = emptyList(),
        seriesAllDay: Map<Long, Boolean> = emptyMap(),
        title: (DisplayEvent) -> String = { it.title },
    ) = buildPrintWeek(
        events = events,
        weekStart = weekStart,
        includeGrid = grid,
        includeDetails = details,
        showMultiDayTimedInAllDayStrip = strip,
        roomSeriesAllDayById = seriesAllDay,
        calendars = calendars,
        formats = formats(pattern),
        title = title,
    )

    private fun timed(id: Long, date: LocalDate, h: Int, m: Int, endDate: LocalDate, eh: Int, em: Int, title: String = "Event $id") =
        room(id = id, startTs = at(date, h, m, zone = ny), endTs = at(endDate, eh, em, zone = ny), zone = ny, title = title)

    // ==================== Days ====================

    @Test
    fun `the week runs 7 days from its start, with Saturday and Sunday marked weekend`() {
        for (start in listOf(sunday, sunday.plusDays(1), sunday.minusDays(1))) {
            val week = build(emptyList(), weekStart = start)
            assertEquals((0L until 7L).map { start.plusDays(it) }, week.days.map { it.date })
            assertEquals(
                week.days.map { it.date.dayOfWeek.value >= 6 },
                week.days.map { it.isWeekend },
            )
        }
        assertEquals("Sun, Oct 25", build(emptyList()).days.first().heading)
    }

    @Test
    fun `an empty week has 7 free days and the default hours`() {
        val week = build(emptyList())
        assertEquals(7, week.days.size)
        assertTrue(week.days.all { it.parts.isEmpty() && it.stripItems.isEmpty() && it.details.isEmpty() })
        assertEquals(8 to 18, week.hourStart to week.hourEnd)
        assertEquals((9 until 18).toList(), week.hourLabels.map { it.first })
    }

    @Test
    fun `events with no day in the week are dropped`() {
        val before = timed(1, sunday.minusDays(1), 10, 0, sunday.minusDays(1), 11, 0)
        val after = timed(2, sunday.plusDays(7), 0, 30, sunday.plusDays(7), 1, 0)
        val allDayAfter = roomAllDay(3, sunday.plusDays(7), zone = ny)
        val inside = timed(4, sunday, 10, 0, sunday, 11, 0)

        val week = build(listOf(before, after, allDayAfter, inside))
        assertEquals(listOf("Event 4"), week.days.flatMap { it.details }.map { it.title })
    }

    // ==================== Strip vs grid ====================

    @Test
    fun `strip or grid follows the multi-day strip setting, as in the Week view`() {
        val longTrip = timed(1, sunday.plusDays(1), 8, 0, sunday.plusDays(2), 9, 0, title = "Trip")
        val allDay = roomAllDay(2, sunday.plusDays(1), zone = ny)

        val off = build(listOf(longTrip, allDay), strip = false)
        assertEquals(listOf("Event 2"), off.days[1].stripItems.map { it.title })
        assertEquals(listOf("Trip"), off.days[1].parts.map { it.title })

        val on = build(listOf(longTrip, allDay), strip = true)
        assertEquals(setOf("Trip", "Event 2"), on.days[1].stripItems.map { it.title }.toSet())
        assertTrue(on.days[1].parts.isEmpty())
        assertEquals(listOf("Trip"), on.days[2].stripItems.map { it.title })
    }

    @Test
    fun `a multi-day all-day event shows on each day it covers with continuation marks`() {
        val summit = roomAllDay(1, sunday.plusDays(4), zone = ny, days = 2)
        val week = build(listOf(summit))

        val thu = week.days[4].stripItems.single()
        val fri = week.days[5].stripItems.single()
        assertFalse(thu.continuesBefore); assertTrue(thu.continuesAfter)
        assertTrue(fri.continuesBefore); assertFalse(fri.continuesAfter)
        assertTrue(week.days[6].stripItems.isEmpty())
    }

    @Test
    fun `an all-day event from before the week continues into its first day`() {
        val trip = roomAllDay(1, sunday.minusDays(2), zone = ny, days = 4)
        val week = build(listOf(trip))
        val first = week.days[0].stripItems.single()
        assertTrue(first.continuesBefore)
        assertTrue(first.continuesAfter)
        assertFalse(week.days[1].stripItems.single().continuesAfter)
    }

    @Test
    fun `all-day events key on their day code in a western zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val la = ZoneId.of("America/Los_Angeles")
        // Its UTC-midnight start is Saturday evening in Los Angeles.
        val firstDay = roomAllDay(1, sunday, zone = la)

        val week = build(listOf(firstDay))
        assertEquals(listOf("Event 1"), week.days[0].stripItems.map { it.title })
        assertEquals(listOf("Event 1"), week.days[0].details.map { it.title })
        assertTrue(week.days.drop(1).all { it.stripItems.isEmpty() && it.details.isEmpty() })
    }

    // ==================== Grid placement ====================

    @Test
    fun `grid parts match the Week view's cascade placement`() {
        val tuesday = sunday.plusDays(2)
        val events = listOf(
            timed(1, tuesday, 13, 0, tuesday, 17, 0, title = "Offsite"),
            timed(2, tuesday, 14, 0, tuesday, 15, 0, title = "Vendor"),
            timed(3, tuesday, 14, 0, tuesday, 14, 30, title = "1:1"),
            timed(4, tuesday, 14, 15, tuesday, 14, 45, title = "Pick-up"),
        )
        val week = build(events)
        assertPartsMatchWeekView(week, dayIndex = 2, events)
        assertTrue(week.days[2].parts.map { it.depth }.toSet().size > 1)
        // Vendor and 1:1 start together, so they share a level side by side.
        assertTrue(week.days[2].parts.any { it.widthFraction < 1f - it.leftFraction - 1e-4f })
    }

    @Test
    fun `grid placement on the fall-back day matches the Week view`() {
        val nov1 = LocalDate.of(2026, 11, 1)
        // 01:30 happens twice: first in EDT, then an hour later in EST.
        val firstHalfPast = at(nov1, 1, 30, zone = ny)
        val secondHalfPast = firstHalfPast + HOUR
        val events = listOf(
            room(id = 1, startTs = firstHalfPast, endTs = firstHalfPast + HOUR / 2, zone = ny, title = "First 1:30"),
            room(id = 2, startTs = secondHalfPast, endTs = secondHalfPast + HOUR / 2, zone = ny, title = "Second 1:30"),
            room(id = 3, startTs = at(nov1, 9, 0, zone = ny), endTs = at(nov1, 10, 0, zone = ny), zone = ny),
        )
        val week = build(events, weekStart = nov1)
        assertPartsMatchWeekView(week, dayIndex = 0, events, weekStart = nov1)
    }

    private fun assertPartsMatchWeekView(
        week: PrintWeek,
        dayIndex: Int,
        events: List<DisplayEvent>,
        weekStart: LocalDate = sunday,
    ) {
        val hourHeight = (150f / (week.hourEnd - week.hourStart) / 0.15875f).dp
        val expected = WeekViewUtils.positionEventsForDay(
            events = events,
            date = weekStart.plusDays(dayIndex.toLong()),
            dayIndex = dayIndex,
            hourHeight = hourHeight,
            startHour = 0,
            endHour = 24,
            maxVisibleOverlap = Int.MAX_VALUE,
            overlapLayout = WeekViewUtils.OverlapLayout.Cascade(minOffset = (7f * 1.18f * 0.3528f / 0.15875f).dp),
        )
        val parts = week.days[dayIndex].parts
        assertEquals(expected.map { it.displayEvent.title }, parts.map { it.title })
        assertEquals(expected.map { it.startMinutes }, parts.map { it.startMinute })
        assertEquals(expected.map { minOf(it.endMinutes, 24 * 60) }, parts.map { it.endMinute })
        assertEquals(expected.map { it.leftFraction }, parts.map { it.leftFraction })
        assertEquals(expected.map { it.widthFraction }, parts.map { it.widthFraction })
        assertEquals(expected.map { it.cascadeDepth }, parts.map { it.depth })
    }

    @Test
    fun `a covered block's text stops where the block on top starts`() {
        val tuesday = sunday.plusDays(2)
        val offsite = timed(1, tuesday, 13, 0, tuesday, 17, 0, title = "Offsite")
        val vendor = timed(2, tuesday, 14, 0, tuesday, 15, 0, title = "Vendor")

        val parts = build(listOf(offsite, vendor)).days[2].parts.associateBy { it.title }
        assertEquals(13 * 60, parts.getValue("Offsite").startMinute)
        assertEquals(14 * 60, parts.getValue("Offsite").textEndMinute)
        assertEquals(parts.getValue("Vendor").endMinute, parts.getValue("Vendor").textEndMinute)
    }

    @Test
    fun `an event crossing midnight splits into a part per day with continuation marks`() {
        val fado = timed(1, friday, 23, 0, friday.plusDays(1), 1, 30, title = "Fado night")
        val week = build(listOf(fado))

        val fri = week.days[5].parts.single()
        val sat = week.days[6].parts.single()
        assertEquals(23 * 60 to 24 * 60, fri.startMinute to fri.endMinute)
        assertFalse(fri.continuesBefore); assertTrue(fri.continuesAfter)
        assertEquals(0 to 90, sat.startMinute to sat.endMinute)
        assertTrue(sat.continuesBefore); assertFalse(sat.continuesAfter)
        assertEquals(fri.number, sat.number)
        assertEquals("Fri 23:00–Sat 01:30", fri.timeText)
    }

    @Test
    fun `events from the previous week and into the next week keep only their in-week parts`() {
        val nightShift = timed(1, sunday.minusDays(1), 22, 0, sunday, 6, 0, title = "Night shift")
        val redEye = timed(2, sunday.plusDays(6), 23, 30, sunday.plusDays(7), 0, 30, title = "Red-eye")
        val week = build(listOf(nightShift, redEye))

        val shift = week.days[0].parts.single()
        assertEquals(0 to 6 * 60, shift.startMinute to shift.endMinute)
        assertTrue(shift.continuesBefore)
        val eye = week.days[6].parts.single()
        assertTrue(eye.continuesAfter)
        assertEquals(listOf("Night shift"), week.days[0].details.map { it.title })
        assertEquals(listOf("Red-eye"), week.days[6].details.map { it.title })
        assertEquals(2, week.days.sumOf { it.parts.size })
    }

    // ==================== Hours ====================

    @Test
    fun `the hours widen to the week's timed events`() {
        val flight = timed(1, sunday.plusDays(4), 7, 5, sunday.plusDays(4), 15, 25)
        val party = timed(2, sunday.plusDays(6), 19, 0, sunday.plusDays(6), 22, 30)
        assertEquals(7 to 23, build(listOf(flight, party)).let { it.hourStart to it.hourEnd })

        val afterMidnight = timed(3, sunday.plusDays(3), 0, 15, sunday.plusDays(3), 1, 0)
        assertEquals(0, build(listOf(afterMidnight)).hourStart)

        val toMidnight = timed(4, sunday.plusDays(3), 22, 0, sunday.plusDays(4), 0, 0)
        assertEquals(24, build(listOf(toMidnight)).hourEnd)
    }

    @Test
    fun `hour labels follow the time pattern`() {
        assertEquals("09:00", build(emptyList(), pattern = "HH:mm").hourLabels.first().second)
        assertEquals("9 am", build(emptyList(), pattern = "h:mm a").hourLabels.first().second)
    }

    @Test
    fun `hour labels stay right on a spring-forward week start`() {
        // Sun Mar 8 2026: New York clocks jump from 02:00 to 03:00.
        val mar8 = LocalDate.of(2026, 3, 8)
        val late = room(id = 1, startTs = at(mar8, 0, 30, zone = ny), endTs = at(mar8, 1, 0, zone = ny), zone = ny)
        val labels = build(listOf(late), weekStart = mar8, pattern = "h:mm a").hourLabels.toMap()
        assertEquals("2 am", labels[2])
        assertEquals("3 am", labels[3])
    }

    @Test
    fun `a block at the grid's last hour gets room below it`() {
        val reminder = room(id = 1, startTs = at(sunday.plusDays(2), 18, 0, zone = ny), endTs = at(sunday.plusDays(2), 18, 0, zone = ny), zone = ny)
        assertEquals(19, build(listOf(reminder)).hourEnd)
    }

    // ==================== Numbers ====================

    @Test
    fun `with both pages events are numbered in details order, shared with the grid`() {
        val lunch = timed(1, sunday.plusDays(1), 12, 0, sunday.plusDays(1), 13, 0, title = "Lunch")
        val standup = timed(2, sunday.plusDays(1), 9, 0, sunday.plusDays(1), 9, 15, title = "Standup")
        val holiday = roomAllDay(3, sunday.plusDays(1), zone = ny)
        val brunch = timed(4, sunday, 10, 0, sunday, 11, 30, title = "Brunch")

        val week = build(listOf(lunch, standup, holiday, brunch))
        assertTrue(week.numbered)
        val details = week.days.flatMap { it.details }
        assertEquals(listOf("Brunch", "Event 3", "Standup", "Lunch"), details.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), details.map { it.number })
        assertEquals(2, week.days[1].stripItems.single().number)
        assertEquals(mapOf("Standup" to 3, "Lunch" to 4), week.days[1].parts.associate { it.title to it.number })
    }

    @Test
    fun `events starting together are numbered longer first`() {
        val short = timed(1, sunday.plusDays(1), 14, 0, sunday.plusDays(1), 14, 30, title = "Short")
        val long = timed(2, sunday.plusDays(1), 14, 0, sunday.plusDays(1), 15, 0, title = "Long")
        val details = build(listOf(short, long)).days[1].details
        assertEquals(listOf("Long" to 1, "Short" to 2), details.map { it.title to it.number })
    }

    @Test
    fun `with one page alone nothing is numbered`() {
        val lunch = timed(1, sunday.plusDays(1), 12, 0, sunday.plusDays(1), 13, 0)
        for ((grid, details) in listOf(true to false, false to true)) {
            val week = build(listOf(lunch), grid = grid, details = details)
            assertFalse(week.numbered)
            assertNull(week.days[1].parts.single().number)
            assertNull(week.days[1].details.single().number)
        }
    }

    // ==================== Details ====================

    @Test
    fun `detail times are pinned for 24-hour and 12-hour clocks`() {
        val design = timed(1, sunday.plusDays(1), 9, 30, sunday.plusDays(1), 10, 30)
        val fado = timed(2, friday, 23, 0, friday.plusDays(1), 1, 30)

        val h24 = build(listOf(design, fado), pattern = "HH:mm").days
        assertEquals("09:30–10:30", h24[1].details.single().whenText)
        assertEquals("Fri 23:00–Sat 01:30", h24[5].details.single().whenText)

        val h12 = build(listOf(design, fado), pattern = "h:mm a").days
        assertEquals("9:30–10:30 am", h12[1].details.single().whenText)
        assertEquals("Fri 11 pm–Sat 1:30 am", h12[5].details.single().whenText)
        assertEquals("9:30–10:30 am", h12[1].parts.single().timeText)
    }

    @Test
    fun `an event of a week or more names its days by date, never twice the same weekday`() {
        val trip = roomAllDay(1, sunday, zone = ny, days = 8)
        assertEquals("Oct 25–Nov 1", build(listOf(trip)).days[0].details.single().whenText)

        val long = timed(2, sunday, 9, 0, sunday.plusDays(7), 17, 0)
        assertEquals("Oct 25 09:00–Nov 1 17:00", build(listOf(long)).days[0].details.single().whenText)
    }

    @Test
    fun `an event ending at the next midnight stays a one-day range`() {
        val late = timed(1, sunday.plusDays(3), 22, 0, sunday.plusDays(4), 0, 0)
        assertEquals("22:00–00:00", build(listOf(late)).days[3].details.single().whenText)
    }

    @Test
    fun `all-day details read All day, a multi-day one names its days too`() {
        val oneDay = roomAllDay(1, sunday.plusDays(3), zone = ny)
        val summit = roomAllDay(2, sunday.plusDays(4), zone = ny, days = 2)
        val week = build(listOf(oneDay, summit))

        assertEquals("All day" to null, week.days[3].details.single().let { it.whenText to it.whenNote })
        assertEquals("Thu–Fri" to "All day", week.days[4].details.single().let { it.whenText to it.whenNote })
        assertTrue(week.days[5].details.isEmpty())
    }

    @Test
    fun `details carry location, repeats, the moved cue and status`() {
        val wed = sunday.plusDays(3)
        val thuStart = at(sunday.plusDays(4), 11, 0, zone = ny)
        val moved = room(
            id = 10, startTs = at(wed, 11, 0, zone = ny), endTs = at(wed, 12, 0, zone = ny), zone = ny,
            title = "Quarterly planning", seriesId = 9, originalInstanceTime = thuStart,
        )
        val standup = room(
            id = 11, startTs = at(wed, 9, 0, zone = ny), endTs = at(wed, 9, 15, zone = ny), zone = ny,
            title = "Standup", rrule = "FREQ=DAILY", status = "CANCELLED",
        )
        val talk = room(
            id = 12, startTs = at(wed, 12, 0, zone = ny), endTs = at(wed, 13, 0, zone = ny), zone = ny,
            title = "Talk", declined = true,
        )
        val week = build(listOf(moved, standup, talk), seriesAllDay = mapOf(9L to false))
        val byTitle = week.days[3].details.associateBy { it.title }

        assertEquals("was Thu", byTitle.getValue("Quarterly planning").movedText)
        assertTrue(byTitle.getValue("Quarterly planning").repeats)
        assertTrue(byTitle.getValue("Standup").repeats)
        assertTrue(byTitle.getValue("Standup").cancelled)
        assertTrue(byTitle.getValue("Talk").declined)
        assertFalse(byTitle.getValue("Talk").repeats)
        assertNull(byTitle.getValue("Talk").movedText)
    }

    @Test
    fun `the event description is never in the model`() {
        val event = timed(1, sunday.plusDays(1), 9, 30, sunday.plusDays(1), 10, 30)
            .let { it.copy(event = it.event.copy(description = "SECRET-NOTE")) }
        val week = build(listOf(event))
        assertFalse(week.toString().contains("SECRET-NOTE"))
    }

    @Test
    fun `calendar names come from the printed calendars, falling back to the event's`() {
        val birthday = timed(1, sunday.plusDays(2), 0, 0, sunday.plusDays(2), 1, 0)
        val other = device(instanceId = 5, eventId = 50, startTs = at(sunday.plusDays(2), 9, 0, zone = ny), endTs = at(sunday.plusDays(2), 10, 0, zone = ny), zone = ny)
        val calendars = listOf(PrintCalendar(PrintCalendarKey.Room(1L), "Birthdays", 0xFF00897B.toInt()))

        val details = build(listOf(birthday, other), calendars = calendars).days[2].details.associateBy { it.title }
        assertEquals("Birthdays", details.getValue("Event 1").calendarName)
        assertEquals(0xFF00897B.toInt(), details.getValue("Event 1").calendarColor)
        assertEquals("Device", details.getValue("Device 50").calendarName)
    }

    @Test
    fun `the title function writes every printed title`() {
        val lunch = timed(1, sunday.plusDays(1), 12, 0, sunday.plusDays(1), 13, 0, title = "Lunch")
        val holiday = roomAllDay(2, sunday.plusDays(1), zone = ny)
        val week = build(listOf(lunch, holiday), title = { "[${it.title}]" })

        assertEquals("[Lunch]", week.days[1].parts.single().title)
        assertEquals("[Event 2]", week.days[1].stripItems.single().title)
        assertEquals(listOf("[Event 2]", "[Lunch]"), week.days[1].details.map { it.title })
    }

    @Test
    fun `the model carries the legend calendars`() {
        val calendars = listOf(PrintCalendar(PrintCalendarKey.Device(4L), "Kids school", 1))
        val week = build(emptyList(), calendars = calendars)
        assertEquals(calendars, week.calendars)
    }
}
