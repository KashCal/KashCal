package org.onekash.kashcal.ui.print

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.viewmodels.ViewMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/** Tests [printAnchorDate], the date the print sheet opens on for each view. */
class PrintAnchorDateTest {

    private val today = LocalDate.of(2026, 10, 26) // a Monday
    private val zone = ZoneId.of("America/New_York")

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun anchor(
        viewMode: ViewMode,
        weekViewPagerPosition: Int = WeekViewUtils.CENTER_WEEK_PAGE,
        firstDayOfWeek: Int = Calendar.SUNDAY,
        plannerAnchorDayCode: Int? = null,
        plannerWeekPagerPosition: Int = 0,
        selectedDate: Long = 0L,
        viewingYear: Int = today.year,
        viewingMonth: Int = today.monthValue - 1,
        agendaShownDate: LocalDate? = null,
    ) = printAnchorDate(
        viewMode = viewMode,
        weekViewPagerPosition = weekViewPagerPosition,
        firstDayOfWeek = firstDayOfWeek,
        plannerAnchorDayCode = plannerAnchorDayCode,
        plannerWeekPagerPosition = plannerWeekPagerPosition,
        selectedDate = selectedDate,
        viewingYear = viewingYear,
        viewingMonth = viewingMonth,
        agendaShownDate = agendaShownDate,
        today = today,
        zone = zone,
    )

    @Test
    fun `the Week view gives the shown week's start, far from today too`() {
        assertEquals(LocalDate.of(2026, 10, 25), anchor(ViewMode.WEEK))
        val tenWeeksOn = anchor(ViewMode.WEEK, weekViewPagerPosition = WeekViewUtils.CENTER_WEEK_PAGE + 10)
        assertEquals(LocalDate.of(2027, 1, 3), tenWeeksOn)
        assertEquals(
            LocalDate.of(2026, 10, 26),
            anchor(ViewMode.WEEK, firstDayOfWeek = Calendar.MONDAY),
        )
    }

    @Test
    fun `the Planner gives its shown week's start`() {
        val anchorDay = DayPagerUtils.localDateToDayCode(LocalDate.of(2026, 10, 25))
        assertEquals(
            LocalDate.of(2026, 11, 15),
            anchor(ViewMode.PLANNER, plannerAnchorDayCode = anchorDay, plannerWeekPagerPosition = WeekViewUtils.CENTER_WEEK_PAGE + 3),
        )
    }

    @Test
    fun `Day and 3-day give the first shown day`() {
        val page = WeekViewUtils.CENTER_DAY_PAGE + 40
        assertEquals(today.plusDays(40), anchor(ViewMode.DAY, weekViewPagerPosition = page))
        assertEquals(today.plusDays(40), anchor(ViewMode.THREE_DAYS, weekViewPagerPosition = page))
    }

    @Test
    fun `Month views give the selected day when it is in the shown month, else the month's first day`() {
        val selected = LocalDate.of(2026, 12, 17)
        assertEquals(selected, anchor(ViewMode.MONTH, selectedDate = ms(selected), viewingYear = 2026, viewingMonth = 11))
        assertEquals(selected, anchor(ViewMode.MONTH_FULL, selectedDate = ms(selected), viewingYear = 2026, viewingMonth = 11))
        assertEquals(
            LocalDate.of(2027, 2, 1),
            anchor(ViewMode.MONTH, selectedDate = ms(selected), viewingYear = 2027, viewingMonth = 1),
        )
        assertEquals(LocalDate.of(2027, 2, 1), anchor(ViewMode.MONTH, selectedDate = 0L, viewingYear = 2027, viewingMonth = 1))
    }

    @Test
    fun `Agenda gives the week its week bar shows, today when there is none`() {
        val shown = LocalDate.of(2026, 11, 15)
        assertEquals(shown, anchor(ViewMode.AGENDA, agendaShownDate = shown))
        assertEquals(today, anchor(ViewMode.AGENDA, agendaShownDate = null))
    }

    @Test
    fun `Year and Insights give today`() {
        assertEquals(today, anchor(ViewMode.YEAR))
        assertEquals(today, anchor(ViewMode.INSIGHTS))
    }
}
