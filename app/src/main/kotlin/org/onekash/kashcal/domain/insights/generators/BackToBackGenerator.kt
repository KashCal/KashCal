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

class BackToBackGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.BACK_TO_BACK

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean =
        countBackToBack(occurrences) > 0

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val count = countBackToBack(occurrences)
        val past = now > periodEnd
        val text = if (past) {
            context.getString(R.string.insight_back_to_back_past, count)
        } else {
            context.getString(R.string.insight_back_to_back_future, count)
        }
        return Insight(id = id, text = text, icon = InsightIcon.LINK, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val count = countBackToBack(occurrences)
        return minOf(count / 5f, 1f)
    }

    internal fun countBackToBack(occurrences: List<InsightOccurrence>): Int {
        val timed = timedOccurrences(occurrences).sortedBy { it.startTs }
        var count = 0
        for (i in 0 until timed.size - 1) {
            val gap = timed[i + 1].startTs - timed[i].endTs
            if (gap in 0 until BACK_TO_BACK_THRESHOLD_MS) {
                count++
            }
        }
        return count
    }

    companion object {
        const val BACK_TO_BACK_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
    }
}
