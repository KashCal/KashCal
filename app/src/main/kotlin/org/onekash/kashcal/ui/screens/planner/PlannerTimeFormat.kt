package org.onekash.kashcal.ui.screens.planner

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.onekash.kashcal.domain.planner.MovedCue
import org.onekash.kashcal.ui.util.DayPagerUtils

/**
 * Formats a Planner row's time range, shorter than the other views' "4:30 pm - 6:00 pm": in
 * 12-hour time a marker both ends share is written once, at the end ("4:30–6 pm"), and a time on
 * the hour drops its minutes ("4–5 pm"). A range that starts and ends in the same minute is one
 * time. 24-hour time keeps its minutes. [join] puts the two ends together, so each language can
 * use its own range joiner. Both ends share a marker when both are before noon or both from noon.
 */
internal fun plannerTimeRange(
    startTs: Long,
    endTs: Long,
    timePattern: String,
    join: (start: String, end: String) -> String,
    locale: Locale,
    zone: ZoneId,
): String {
    // Instants, not clock times: midnight to midnight, or an hour across the autumn clock
    // change, starts and ends at the same clock time but is still a range.
    if (Math.floorDiv(startTs, MINUTE_MS) == Math.floorDiv(endTs, MINUTE_MS)) {
        return plannerTime(endTs, timePattern, locale, zone)
    }
    val start = localTime(startTs, zone)
    val end = localTime(endTs, zone)
    if (!isTwelveHour(timePattern)) return join(format(start, timePattern, locale), format(end, timePattern, locale))
    val sharedMarker = (start.hour < 12) == (end.hour < 12)
    val startPattern = endPattern(start, timePattern).let { if (sharedMarker) withoutMarker(it) else it }
    return join(format(start, startPattern, locale), format(end, endPattern(end, timePattern), locale))
}

/** Returns where a moved occurrence was: a time, a short weekday, or a short date. */
internal fun movedCueLabel(
    cue: MovedCue,
    timePattern: String,
    locale: Locale,
    weekday: DateTimeFormatter,
    shortDate: DateTimeFormatter,
    zone: ZoneId,
): String = when (cue) {
    is MovedCue.Time -> plannerTime(cue.originalMs, timePattern, locale, zone)
    is MovedCue.Day -> weekday.format(DayPagerUtils.dayCodeToLocalDate(cue.originalDayCode))
    is MovedCue.Date -> shortDate.format(DayPagerUtils.dayCodeToLocalDate(cue.originalDayCode))
}

/** Formats one time as [plannerTimeRange] writes a range's end: "5 pm", "5:30 pm", "17:00". */
internal fun plannerTime(ts: Long, timePattern: String, locale: Locale, zone: ZoneId): String {
    val time = localTime(ts, zone)
    return format(time, endPattern(time, timePattern), locale)
}

private fun localTime(ts: Long, zone: ZoneId): LocalTime =
    Instant.ofEpochMilli(ts).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)

private fun isTwelveHour(pattern: String) = pattern.contains('a')

/** Drops ":mm" from a 12-hour pattern for a time on the hour. */
private fun endPattern(time: LocalTime, pattern: String): String =
    if (isTwelveHour(pattern) && time.minute == 0) pattern.replace(":mm", "") else pattern

private fun withoutMarker(pattern: String): String = pattern.replace(MARKER, "").trim()

// Lowercase like the other views' times ("9:00 am").
private fun format(time: LocalTime, pattern: String, locale: Locale): String =
    time.format(DateTimeFormatter.ofPattern(pattern, locale)).lowercase(locale)

private const val MINUTE_MS = 60_000L
private val MARKER = Regex("""\s*a\s*""")
