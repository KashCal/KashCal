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

class MeetingFreeDaysGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.MEETING_FREE_DAYS

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean =
        stats.dailyBreakdown.size >= 2

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val freeDays = stats.dailyBreakdown.count { it.minutes == 0L }
        val past = now > periodEnd
        val text = if (past) {
            context.getString(R.string.insight_meeting_free_days_past, freeDays)
        } else {
            context.getString(R.string.insight_meeting_free_days_future, freeDays)
        }
        return Insight(id = id, text = text, icon = InsightIcon.FREE_DAY, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val totalDays = stats.dailyBreakdown.size
        if (totalDays == 0) return 0f
        val freeFraction = stats.dailyBreakdown.count { it.minutes == 0L }.toFloat() / totalDays
        return minOf(kotlin.math.abs(freeFraction - 0.3f) / 0.7f, 1f)
    }
}
