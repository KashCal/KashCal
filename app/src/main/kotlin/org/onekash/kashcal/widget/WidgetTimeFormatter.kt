package org.onekash.kashcal.widget

import android.content.Context
import android.text.format.DateUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatWidgetEventTime(
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    timePattern: String,
    allDayText: String
): String {
    if (event.isAllDay) return allDayText
    if (dayCode != event.startDay) return "\u25B8"
    val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
    return Instant.ofEpochMilli(event.startTs)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
        .lowercase(Locale.getDefault())
}

/**
 * Format the detailed-row time line: a start-end range instead of the compact single instant.
 *
 * Range formatting (dash glyph, shared am/pm collapse, RTL, locale digit shaping) is
 * delegated to [DateUtils.formatDateRange] rather than reimplemented. The 12/24h clock
 * is pinned from [timePattern] \u2014 the same resolved pattern the compact row uses \u2014 so the
 * detailed row honors the in-app time-format override, not just the device setting.
 *
 * Branches:
 * - All-day -> [allDayText].
 * - Continuing from a previous day (the event started before [dayCode]) -> a continuation
 *   marker plus the end time, so a row on a middle/last day reads as "ends at X".
 * - Same-day timed -> a plain start-end range ("9:30 - 10:30 AM").
 * - Starts on [dayCode] but ends on a later day -> the range with date context, so the two
 *   times aren't mistaken for a same-day range when they are actually days apart.
 */
internal fun formatWidgetEventTimeRange(
    context: Context,
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    timePattern: String,
    allDayText: String
): String {
    if (event.isAllDay) return allDayText

    // Pin the clock to the resolved pattern (which already folds in the in-app override)
    // rather than letting DateUtils fall back to the device's 12/24h setting alone.
    val clockFlag = if (timePattern.contains('a')) DateUtils.FORMAT_12HOUR else DateUtils.FORMAT_24HOUR

    // Started before this day: show only where it ends. Formatting a bare instant via
    // DateUtils keeps the 12/24h + locale treatment consistent with the range path.
    if (dayCode != event.startDay) {
        val endDay = DateTimeUtils.eventTsToEndDayCode(event.endTs, event.startTs, event.isAllDay)
        // For a span that also ends on a later day (viewed on an interior day), a bare
        // time reads as "ends today" \u2014 add a date token so it's clear the end is elsewhere.
        val endFlags = clockFlag or if (endDay != dayCode) {
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
        } else {
            DateUtils.FORMAT_SHOW_TIME
        }
        val endTime = DateUtils.formatDateTime(context, event.endTs, endFlags)
        return "\u25B8 $endTime"
    }

    val endDay = DateTimeUtils.eventTsToEndDayCode(event.endTs, event.startTs, event.isAllDay)
    // A cross-day range needs a date token, or "9:00 AM - 5:00 PM" looks like one day.
    val flags = clockFlag or if (endDay != event.startDay) {
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
    } else {
        DateUtils.FORMAT_SHOW_TIME
    }
    return DateUtils.formatDateRange(context, event.startTs, event.endTs, flags)
}
