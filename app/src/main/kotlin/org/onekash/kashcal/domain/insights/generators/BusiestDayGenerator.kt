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
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

class BusiestDayGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.BUSIEST_DAY

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean =
        nonZeroDays(stats).size >= 2

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val busiest = nonZeroDays(stats).maxBy { it.minutes }
        val dayName = dayCodeToLocalDate(busiest.dayCode).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val hours = InsightsRepository.formatMinutesShort(busiest.minutes)
        val past = isDayPast(busiest.dayCode, now)
        val text = if (past) {
            context.getString(R.string.insight_busiest_day_past, dayName, hours)
        } else {
            context.getString(R.string.insight_busiest_day_future, dayName, hours)
        }
        return Insight(id = id, text = text, icon = InsightIcon.CHART_BAR, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val days = nonZeroDays(stats)
        if (days.size < 2) return 0f
        val maxH = days.maxOf { it.minutes }.toFloat()
        val meanH = days.map { it.minutes }.average().toFloat()
        return if (maxH == 0f) 0f else (maxH - meanH) / maxH
    }
}
