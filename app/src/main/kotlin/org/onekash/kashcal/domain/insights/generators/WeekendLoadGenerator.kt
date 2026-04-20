package org.onekash.kashcal.domain.insights.generators

import android.content.Context
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.insights.Insight
import org.onekash.kashcal.domain.insights.InsightGenerator
import org.onekash.kashcal.domain.insights.InsightIcon
import org.onekash.kashcal.domain.insights.InsightId
import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.InsightsRepository
import org.onekash.kashcal.domain.insights.PeriodStats
import javax.inject.Inject

class WeekendLoadGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.WEEKEND_LOAD

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean =
        stats.dailyBreakdown.any { isWeekend(it.dayCode) }

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val weekend = stats.dailyBreakdown.filter { isWeekend(it.dayCode) }
        val weekendMinutes = weekend.sumOf { it.minutes }
        val past = weekend.all { isDayPast(it.dayCode, now) }

        val text = when {
            weekendMinutes == 0L -> if (past) {
                context.getString(R.string.insight_weekend_load_clear_past)
            } else {
                context.getString(R.string.insight_weekend_load_clear_future)
            }
            weekendMinutes < 120L -> {
                val formatted = InsightsRepository.formatMinutesShort(weekendMinutes)
                if (past) context.getString(R.string.insight_weekend_load_light_past, formatted)
                else context.getString(R.string.insight_weekend_load_light_future, formatted)
            }
            else -> {
                val formatted = InsightsRepository.formatMinutesShort(weekendMinutes)
                if (past) context.getString(R.string.insight_weekend_load_busy_past, formatted)
                else context.getString(R.string.insight_weekend_load_busy_future, formatted)
            }
        }
        return Insight(id = id, text = text, icon = InsightIcon.WEEKEND, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val weekendMinutes = stats.dailyBreakdown.filter { isWeekend(it.dayCode) }.sumOf { it.minutes }
        val h = weekendMinutes / 60f
        return if (h == 0f) 0.1f else minOf(h / 8f, 1f)
    }
}
