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

class HeaviestUpcomingGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.HEAVIEST_UPCOMING

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean {
        val futureDays = stats.dailyBreakdown.filter { !isDayPast(it.dayCode, now) && it.minutes > 0 }
        return futureDays.size >= 2
    }

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val futureDays = stats.dailyBreakdown.filter { !isDayPast(it.dayCode, now) && it.minutes > 0 }
        val heaviest = futureDays.maxBy { it.minutes }
        val dayName = dayCodeToLocalDate(heaviest.dayCode).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val hours = InsightsRepository.formatMinutesShort(heaviest.minutes)
        val text = context.getString(R.string.insight_heaviest_upcoming, dayName, hours)
        return Insight(id = id, text = text, icon = InsightIcon.HEAVY, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val futureDays = stats.dailyBreakdown.filter { !isDayPast(it.dayCode, now) && it.minutes > 0 }
        if (futureDays.size < 2) return 0f
        val maxH = futureDays.maxOf { it.minutes }.toFloat()
        val meanH = futureDays.map { it.minutes }.average().toFloat()
        return if (maxH == 0f) 0f else (maxH - meanH) / maxH
    }
}
