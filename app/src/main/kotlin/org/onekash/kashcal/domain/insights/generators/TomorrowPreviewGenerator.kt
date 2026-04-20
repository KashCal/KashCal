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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class TomorrowPreviewGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.TOMORROW_PREVIEW

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean {
        if (now > stats.periodEnd) return false // Past period
        val tomorrow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
        val tomorrowCode = localDateToDayCode(tomorrow)
        return stats.dailyBreakdown.any { it.dayCode == tomorrowCode }
    }

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val tomorrow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
        val tomorrowCode = localDateToDayCode(tomorrow)
        val tomorrowMinutes = stats.dailyBreakdown.find { it.dayCode == tomorrowCode }?.minutes ?: 0L
        val hours = InsightsRepository.formatMinutesShort(tomorrowMinutes)

        val timed = timedOccurrences(occurrences)
        val tomorrowStart = tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tomorrowEnd = tomorrow.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val firstEvent = timed.filter { it.startTs in tomorrowStart until tomorrowEnd }.minByOrNull { it.startTs }

        val startTimeStr = firstEvent?.let {
            Instant.ofEpochMilli(it.startTs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        }

        val text = if (startTimeStr != null) {
            context.getString(R.string.insight_tomorrow_preview, hours, startTimeStr)
        } else {
            context.getString(R.string.insight_tomorrow_preview_no_start, hours)
        }
        return Insight(id = id, text = text, icon = InsightIcon.TOMORROW, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val tomorrow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
        val tomorrowCode = localDateToDayCode(tomorrow)
        val tomorrowMinutes = stats.dailyBreakdown.find { it.dayCode == tomorrowCode }?.minutes ?: 0L
        return minOf(tomorrowMinutes / 480f, 1f)
    }
}
