package org.onekash.kashcal.domain.insights.generators

import android.content.Context
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.insights.Insight
import org.onekash.kashcal.domain.insights.InsightGenerator
import org.onekash.kashcal.domain.insights.InsightIcon
import org.onekash.kashcal.domain.insights.InsightId
import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.PeriodStats
import javax.inject.Inject

class CalendarDominantGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.CALENDAR_DOMINANT

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean {
        if (stats.calendarBreakdown.size < 2) return false
        if (stats.totalMinutes == 0L) return false
        val topShare = stats.calendarBreakdown.maxOf { it.minutes }.toFloat() / stats.totalMinutes
        return topShare > 0.6f
    }

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val top = stats.calendarBreakdown.maxBy { it.minutes }
        val percent = (top.minutes * 100 / stats.totalMinutes).toInt()
        val past = now > periodEnd
        val text = if (past) {
            context.getString(R.string.insight_calendar_dominant_past, top.calendarName, percent)
        } else {
            context.getString(R.string.insight_calendar_dominant_future, top.calendarName, percent)
        }
        return Insight(id = id, text = text, icon = InsightIcon.DOMINANT, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        if (stats.calendarBreakdown.size < 2 || stats.totalMinutes == 0L) return 0f
        val share = stats.calendarBreakdown.maxOf { it.minutes }.toFloat() / stats.totalMinutes
        return if (share <= 0.6f) 0f else (share - 0.6f) / 0.4f
    }
}
