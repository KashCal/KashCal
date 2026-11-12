package org.onekash.kashcal.ui.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.localDate
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.PropertyRuns
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar

/**
 * Every day of the [CalendarSpan], from any today, sits on a page of every pager that shows it
 * and maps back to that day: the Month view's day list, the month pager, the Year pager, the Week
 * pager and the Planner (anchored up to a year before today). Outside the span,
 * [CalendarSpan.clamp] lands on its nearest end.
 */
class CalendarSpanPropertiesTest {

    private val todays = Arb.localDate(LocalDate.of(2020, 1, 1), LocalDate.of(2035, 12, 31))

    /** Day offsets past the span's first day; longer than any span, cut to its last day. */
    private val offsets = Arb.int(0..110_000)
    private val firstDays = Arb.element(Calendar.SUNDAY, Calendar.MONDAY, Calendar.SATURDAY)
    private val anchorAges = Arb.int(0..52)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun spanDay(today: LocalDate, offset: Int): LocalDate =
        minOf(CalendarSpan.firstDay(today).plusDays(offset.toLong()), CalendarSpan.lastDay(today))

    /** Checks [date] on every pager, counted from [today]. */
    private fun assertOnEveryPager(today: LocalDate, date: LocalDate, firstDay: Int, anchorWeeksBack: Int) {
        val label = "$date from $today"

        val dayPage = DayPagerUtils.dateToPage(ms(date), ms(today))
        assertTrue("day page $dayPage, $label", dayPage in 0 until DayPagerUtils.TOTAL_PAGES)
        assertEquals("day page back, $label", ms(date), DayPagerUtils.pageToDateMs(dayPage, ms(today)))

        val monthPage = MonthPagerUtils.yearMonthToPage(date.year, date.monthValue - 1, today.year, today.monthValue - 1)
        assertTrue("month page $monthPage, $label", monthPage in 0 until MonthPagerUtils.TOTAL_PAGES)
        assertEquals(
            "month page back, $label",
            date.year to date.monthValue - 1,
            MonthPagerUtils.pageToYearMonth(monthPage, today.year, today.monthValue - 1),
        )

        val yearPage = YearPagerUtils.yearToPage(date.year, today.year)
        assertTrue("year page $yearPage, $label", yearPage in 0 until YearPagerUtils.TOTAL_PAGES)
        assertEquals("year page back, $label", date.year, YearPagerUtils.pageToYear(yearPage, today.year))

        val weekPage = WeekViewUtils.dateToWeekPage(date, firstDay, referenceDate = today)
        assertTrue("week page $weekPage, $label", weekPage in 0 until WeekViewUtils.TOTAL_WEEK_PAGES)
        val weekStart = WeekViewUtils.weekPageToStartDate(weekPage, firstDay, referenceDate = today)
        assertTrue("week of $weekStart holds $label", !date.isBefore(weekStart) && date.isBefore(weekStart.plusDays(7)))

        val anchor = DayPagerUtils.localDateToDayCode(
            WeekViewUtils.getWeekStart(today.minusWeeks(anchorWeeksBack.toLong()), firstDay)
        )
        val plannerPage = plannerPageForDate(anchor, date, firstDay)
        assertTrue("Planner page $plannerPage, anchor $anchor, $label", plannerPage in 0 until WeekViewUtils.TOTAL_WEEK_PAGES)
        val plannerStart = plannerWeekStart(anchor, plannerPage, firstDay)
        assertTrue("Planner week of $plannerStart holds $label", !date.isBefore(plannerStart) && date.isBefore(plannerStart.plusDays(7)))
    }

    @Test
    fun `every day of the span is on a page of every pager`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(2_000), todays, offsets, firstDays, anchorAges) { today, offset, firstDay, age ->
            assertOnEveryPager(today, spanDay(today, offset), firstDay, age)
        }
    }

    @Test
    fun `the span's first and last day are on a page of every pager from any today`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(500), todays, firstDays) { today, firstDay ->
            for (edge in listOf(CalendarSpan.firstDay(today), CalendarSpan.lastDay(today))) {
                assertOnEveryPager(today, edge, firstDay, anchorWeeksBack = 52)
            }
        }
    }

    @Test
    fun `the month pager reaches every month the day list reaches`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(500), todays) { today ->
            for (page in listOf(0, DayPagerUtils.TOTAL_PAGES - 1)) {
                val day = java.time.Instant.ofEpochMilli(DayPagerUtils.pageToDateMs(page, ms(today))).atZone(zone).toLocalDate()
                val monthPage = MonthPagerUtils.yearMonthToPage(day.year, day.monthValue - 1, today.year, today.monthValue - 1)
                assertTrue("day page $page is $day; month page $monthPage, from $today", monthPage in 0 until MonthPagerUtils.TOTAL_PAGES)
            }
        }
    }

    @Test
    fun `the span is whole years, 150 each side of today's year`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(500), todays) { today ->
            assertEquals(LocalDate.of(today.year - 150, 1, 1), CalendarSpan.firstDay(today))
            assertEquals(LocalDate.of(today.year + 150, 12, 31), CalendarSpan.lastDay(today))
            assertEquals(YearMonth.of(today.year - 150, 1), CalendarSpan.firstMonth(today))
            assertEquals(YearMonth.of(today.year + 150, 12), CalendarSpan.lastMonth(today))
            assertEquals((today.year - 150)..(today.year + 150), CalendarSpan.yearRange(today))
            // The Year view shows exactly the span's years.
            assertEquals(CalendarSpan.yearRange(today).first, YearPagerUtils.pageToYear(0, today.year))
            assertEquals(CalendarSpan.yearRange(today).last, YearPagerUtils.pageToYear(YearPagerUtils.TOTAL_PAGES - 1, today.year))
        }
    }

    @Test
    fun `clamp keeps a date inside the span and moves one outside to the nearest end`(): Unit = runBlocking {
        val anyDates = Arb.localDate(LocalDate.of(1500, 1, 1), LocalDate.of(2600, 12, 31))
        checkAll(PropertyRuns.config(2_000), todays, anyDates) { today, date ->
            val first = CalendarSpan.firstDay(today)
            val last = CalendarSpan.lastDay(today)
            val expected = when {
                date.isBefore(first) -> first
                date.isAfter(last) -> last
                else -> date
            }
            assertEquals("$date from $today", expected, CalendarSpan.clamp(date, today))
            assertEquals("month of $date from $today", YearMonth.from(expected), CalendarSpan.clamp(YearMonth.from(date), today))
        }
    }

    @Test
    fun `canStepDay steps inside the span and from outside it only toward the span`(): Unit = runBlocking {
        val anyDates = Arb.localDate(LocalDate.of(1500, 1, 1), LocalDate.of(2600, 12, 31))
        checkAll(PropertyRuns.config(2_000), todays, anyDates, Arb.element(-1, 1)) { today, date, step ->
            val first = CalendarSpan.firstDay(today)
            val last = CalendarSpan.lastDay(today)
            val target = date.plusDays(step.toLong())
            val expected = when {
                !target.isBefore(first) && !target.isAfter(last) -> true
                date.isBefore(first) -> step > 0
                date.isAfter(last) -> step < 0
                else -> false
            }
            assertEquals("$date step $step from $today", expected, CalendarSpan.canStepDay(date, step, today))
        }
    }

    @Test
    fun `canStepDay stops at the span's first and last day`() {
        val today = LocalDate.of(2026, 11, 11)
        val first = CalendarSpan.firstDay(today)
        val last = CalendarSpan.lastDay(today)
        assertTrue(CalendarSpan.canStepDay(first, 1, today))
        assertEquals(false, CalendarSpan.canStepDay(first, -1, today))
        assertTrue(CalendarSpan.canStepDay(last, -1, today))
        assertEquals(false, CalendarSpan.canStepDay(last, 1, today))
        assertTrue(CalendarSpan.canStepDay(today, 1, today))
        assertTrue(CalendarSpan.canStepDay(today, -1, today))
    }

    @Test
    fun `the span stays after the 1582 Julian to Gregorian change`() {
        val first = CalendarSpan.firstDay(LocalDate.now())
        assertTrue("$first", first.isAfter(LocalDate.of(1583, 1, 1)))
        // Margin: the span's start keeps clear of 1583 for centuries of todays.
        assertTrue(ChronoUnit.YEARS.between(LocalDate.of(1583, 1, 1), first) > 200)
    }
}
