package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object TopBarTitleFormatter {

    fun format(
        viewMode: ViewMode,
        viewingYear: Int,
        viewingMonth: Int,
        weekViewPagerPosition: Int,
        firstDayOfWeek: Int,
        weekPrefix: String,
        weekSuffixTemplate: String,
        yearLabel: String,
        locale: Locale = Locale.getDefault(),
        today: LocalDate = LocalDate.now(),
    ): String {
        return when (viewMode) {
            ViewMode.MONTH, ViewMode.MONTH_FULL, ViewMode.AGENDA -> {
                WeekViewUtils.formatMonthYear(LocalDate.of(viewingYear, viewingMonth + 1, 1))
            }
            ViewMode.YEAR -> yearLabel
            ViewMode.WEEK -> {
                val centerDate = WeekViewUtils.weekPageToStartDate(
                    weekViewPagerPosition,
                    firstDayOfWeek,
                )
                val monthYear = WeekViewUtils.formatMonthYear(centerDate)
                val weekLabel = WeekViewUtils.formatWeekLabel(
                    centerDate,
                    firstDayOfWeek,
                    weekPrefix,
                )
                weekSuffixTemplate.format(monthYear, weekLabel)
            }
            ViewMode.THREE_DAYS -> {
                val centerDate = WeekViewUtils.pageToDate(weekViewPagerPosition + 1)
                WeekViewUtils.formatMonthYear(centerDate)
            }
            ViewMode.DAY -> {
                val date = WeekViewUtils.pageToDate(weekViewPagerPosition)
                val skeleton = if (date.year == today.year) "EEEMMMd" else "yEEEMMMd"
                DateTimeFormatter.ofPattern(
                    DateTimeUtils.localizedPattern(skeleton),
                    locale,
                ).format(date)
            }
            ViewMode.INSIGHTS -> ""
        }
    }
}
