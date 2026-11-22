package org.onekash.kashcal.ui.print

import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.plannerShownWeekStart
import org.onekash.kashcal.ui.viewmodels.ViewMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Returns the date on screen in [viewMode], which picks the week the print sheet opens on:
 * the shown week's first day in WEEK and PLANNER, the first shown day in DAY and THREE_DAYS, the
 * week the agenda's week bar shows in AGENDA, the selected day in MONTH and MONTH_FULL when it is
 * in the shown month (else that month's first day), and [today] in YEAR and INSIGHTS or when a
 * view has no date to give.
 *
 * @param weekViewPagerPosition a week page in WEEK, a day page in DAY and THREE_DAYS.
 * @param selectedDate epoch ms of the selected day, 0 for none.
 * @param viewingMonth the shown month, 0-based.
 * @param agendaShownDate the first day of the agenda week bar's week, which skips an item that
 *   only peeks into the list's top padding; null when the agenda isn't composed.
 */
fun printAnchorDate(
    viewMode: ViewMode,
    weekViewPagerPosition: Int,
    firstDayOfWeek: Int,
    plannerAnchorDayCode: Int?,
    plannerWeekPagerPosition: Int,
    selectedDate: Long,
    viewingYear: Int,
    viewingMonth: Int,
    agendaShownDate: LocalDate?,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate = when (viewMode) {
    ViewMode.WEEK -> WeekViewUtils.weekPageToStartDate(weekViewPagerPosition, firstDayOfWeek, today)
    ViewMode.PLANNER -> plannerShownWeekStart(plannerAnchorDayCode, plannerWeekPagerPosition, firstDayOfWeek, today)
    ViewMode.DAY, ViewMode.THREE_DAYS ->
        today.plusDays((weekViewPagerPosition - WeekViewUtils.CENTER_DAY_PAGE).toLong())
    ViewMode.MONTH, ViewMode.MONTH_FULL -> {
        val firstOfMonth = LocalDate.of(viewingYear, viewingMonth + 1, 1)
        val selected = selectedDate.takeIf { it != 0L }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        if (selected != null && selected.year == viewingYear && selected.monthValue == viewingMonth + 1) {
            selected
        } else {
            firstOfMonth
        }
    }
    ViewMode.AGENDA -> agendaShownDate ?: today
    ViewMode.YEAR, ViewMode.INSIGHTS -> today
}
