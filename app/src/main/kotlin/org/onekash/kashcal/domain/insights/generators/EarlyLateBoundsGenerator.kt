package org.onekash.kashcal.domain.insights.generators

import android.content.Context
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.insights.Insight
import org.onekash.kashcal.domain.insights.InsightGenerator
import org.onekash.kashcal.domain.insights.InsightIcon
import org.onekash.kashcal.domain.insights.InsightId
import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.PeriodStats
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

class EarlyLateBoundsGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.EARLY_LATE_BOUNDS

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean {
        val (earlyDays, lateDays) = countBoundaryDays(occurrences)
        return earlyDays > 0 || lateDays > 0
    }

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val (earlyDays, lateDays) = countBoundaryDays(occurrences)
        val past = now > periodEnd

        val text = if (earlyDays >= lateDays) {
            if (past) context.getString(R.string.insight_early_bounds_past, earlyDays)
            else context.getString(R.string.insight_early_bounds_future, earlyDays)
        } else {
            if (past) context.getString(R.string.insight_late_bounds_past, lateDays)
            else context.getString(R.string.insight_late_bounds_future, lateDays)
        }
        return Insight(id = id, text = text, icon = InsightIcon.SCHEDULE_BOUNDS, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val (earlyDays, lateDays) = countBoundaryDays(occurrences)
        val boundaryDays = maxOf(earlyDays, lateDays)
        val daysWithEvents = nonZeroDays(stats).size
        return if (daysWithEvents == 0) 0f else boundaryDays.toFloat() / daysWithEvents
    }

    internal fun countBoundaryDays(
        occurrences: List<InsightOccurrence>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, Int> {
        val earlyThreshold = LocalTime.of(8, 0)
        val lateThreshold = LocalTime.of(19, 0)
        val timed = timedOccurrences(occurrences)

        val earlyDays = mutableSetOf<Int>()
        val lateDays = mutableSetOf<Int>()

        for (occ in timed) {
            val startTime = Instant.ofEpochMilli(occ.startTs).atZone(zone).toLocalTime()
            val endTime = Instant.ofEpochMilli(occ.endTs).atZone(zone).toLocalTime()
            val startDay = occ.startDay

            if (startTime.isBefore(earlyThreshold)) {
                earlyDays.add(startDay)
            }
            if (endTime.isAfter(lateThreshold) && occ.endTs != occ.startTs) {
                lateDays.add(occ.endDay)
            }
        }

        return earlyDays.size to lateDays.size
    }
}
