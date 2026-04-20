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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

class LongestFreeGenerator @Inject constructor() : InsightGenerator {
    override val id = InsightId.LONGEST_FREE

    override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean {
        val block = findLongestFreeBlock(stats, occurrences)
        return block != null && block.durationMinutes >= 30
    }

    override fun generate(
        stats: PeriodStats, occurrences: List<InsightOccurrence>,
        now: Long, periodStart: Long, periodEnd: Long, context: Context
    ): Insight {
        val block = findLongestFreeBlock(stats, occurrences)!!
        val dayName = block.day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val startTime = block.startTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val endTime = block.endTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val duration = InsightsRepository.formatMinutesShort(block.durationMinutes.toLong())
        val past = isDayPast(localDateToDayCode(block.day), now)
        val text = if (past) {
            context.getString(R.string.insight_longest_free_past, dayName, startTime, endTime, duration)
        } else {
            context.getString(R.string.insight_longest_free_future, dayName, startTime, endTime, duration)
        }
        return Insight(id = id, text = text, icon = InsightIcon.FREE_TIME, surpriseScore = 0f)
    }

    override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float {
        val block = findLongestFreeBlock(stats, occurrences) ?: return 0f
        return minOf(block.durationMinutes / 240f, 1f)
    }

    internal data class FreeBlock(val day: LocalDate, val startTime: LocalTime, val endTime: LocalTime) {
        val durationMinutes: Int get() = ((endTime.toSecondOfDay() - startTime.toSecondOfDay()) / 60)
    }

    internal fun findLongestFreeBlock(
        stats: PeriodStats,
        occurrences: List<InsightOccurrence>,
        zone: ZoneId = ZoneId.systemDefault()
    ): FreeBlock? {
        val workStart = LocalTime.of(8, 0)
        val workEnd = LocalTime.of(20, 0)
        val timed = timedOccurrences(occurrences)

        var longestBlock: FreeBlock? = null

        for (dayHours in stats.dailyBreakdown) {
            val date = dayCodeToLocalDate(dayHours.dayCode)
            val dayStartMs = date.atTime(workStart).atZone(zone).toInstant().toEpochMilli()
            val dayEndMs = date.atTime(workEnd).atZone(zone).toInstant().toEpochMilli()

            val dayEvents = timed
                .filter { it.endTs > dayStartMs && it.startTs < dayEndMs }
                .sortedBy { it.startTs }

            var gapStart = dayStartMs
            for (event in dayEvents) {
                val eventStart = maxOf(event.startTs, dayStartMs)
                if (eventStart > gapStart) {
                    val block = createBlock(date, gapStart, eventStart, zone, workStart, workEnd)
                    if (block != null && (longestBlock == null || block.durationMinutes > longestBlock.durationMinutes)) {
                        longestBlock = block
                    }
                }
                gapStart = maxOf(gapStart, minOf(event.endTs, dayEndMs))
            }

            if (dayEndMs > gapStart) {
                val block = createBlock(date, gapStart, dayEndMs, zone, workStart, workEnd)
                if (block != null && (longestBlock == null || block.durationMinutes > longestBlock.durationMinutes)) {
                    longestBlock = block
                }
            }
        }

        return longestBlock
    }

    private fun createBlock(
        date: LocalDate, startMs: Long, endMs: Long,
        zone: ZoneId, workStart: LocalTime, workEnd: LocalTime
    ): FreeBlock? {
        val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalTime()
        val end = Instant.ofEpochMilli(endMs).atZone(zone).toLocalTime()
        val clampedStart = maxOf(start, workStart)
        val clampedEnd = minOf(end, workEnd)
        if (clampedEnd <= clampedStart) return null
        return FreeBlock(date, clampedStart, clampedEnd)
    }

    private fun maxOf(a: LocalTime, b: LocalTime): LocalTime = if (a.isAfter(b)) a else b
    private fun minOf(a: LocalTime, b: LocalTime): LocalTime = if (a.isBefore(b)) a else b
}
