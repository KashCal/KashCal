package org.onekash.kashcal.domain.insights

import androidx.compose.runtime.Immutable

enum class AnalysisPeriod {
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH
}

enum class TemporalClass {
    FUTURE,
    PAST,
    IN_PROGRESS
}

enum class InsightId {
    BUSIEST_DAY,
    LIGHTEST_DAY,
    LONGEST_FREE,
    WEEKEND_LOAD,
    BACK_TO_BACK,
    CALENDAR_DOMINANT,
    EARLY_LATE_BOUNDS,
    MEETING_FREE_DAYS,
    TOMORROW_PREVIEW,
    HEAVIEST_UPCOMING,
    NEXT_FREE_BLOCK
}

enum class InsightIcon {
    CHART_BAR,
    CHART_LOW,
    FREE_TIME,
    WEEKEND,
    LINK,
    DOMINANT,
    SCHEDULE_BOUNDS,
    FREE_DAY,
    TOMORROW,
    HEAVY,
    NEXT_FREE
}

@Immutable
data class Insight(
    val id: InsightId,
    val text: String,
    val icon: InsightIcon,
    val surpriseScore: Float
)

@Immutable
data class CalendarHours(
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
    val minutes: Long
)

@Immutable
data class DayHours(
    val dayCode: Int,
    val minutes: Long,
    val isInMonth: Boolean = true
)

@Immutable
data class PeriodStats(
    val totalMinutes: Long,
    val allDayCount: Int,
    val calendarBreakdown: List<CalendarHours>,
    val dailyBreakdown: List<DayHours>,
    val periodStart: Long,
    val periodEnd: Long
) {
    companion object {
        val EMPTY = PeriodStats(
            totalMinutes = 0,
            allDayCount = 0,
            calendarBreakdown = emptyList(),
            dailyBreakdown = emptyList(),
            periodStart = 0,
            periodEnd = 0
        )
    }
}
