package org.onekash.kashcal.domain.insights.generators

import org.onekash.kashcal.domain.insights.DayHours
import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.PeriodStats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun timedOccurrences(occurrences: List<InsightOccurrence>): List<InsightOccurrence> =
    occurrences.filter { !it.isAllDay && it.startTs != it.endTs }

internal fun nonZeroDays(stats: PeriodStats): List<DayHours> =
    stats.dailyBreakdown.filter { it.minutes > 0 }

internal fun isDayPast(dayCode: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayDate = dayCodeToLocalDate(dayCode)
    return dayDate.isBefore(today)
}

internal fun dayCodeToLocalDate(dayCode: Int): LocalDate {
    val year = dayCode / 10000
    val month = (dayCode % 10000) / 100
    val day = dayCode % 100
    return LocalDate.of(year, month, day)
}

internal fun localDateToDayCode(date: LocalDate): Int =
    date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

internal fun isWeekend(dayCode: Int): Boolean {
    val date = dayCodeToLocalDate(dayCode)
    return date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
}
