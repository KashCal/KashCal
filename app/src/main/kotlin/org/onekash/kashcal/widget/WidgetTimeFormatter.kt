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
 * Formats the detailed row's time line: a start-end range instead of the compact row's start time.
 *
 * [DateUtils.formatDateRange] does the range formatting (dash glyph, shared am/pm, RTL, locale
 * digits). The 12/24h clock comes from [timePattern], the pattern the compact row uses, so the
 * detailed row honors the in-app time-format override and not only the device setting.
 *
 * Branches:
 * - All-day -> [allDayText].
 * - Started before [dayCode] -> a continuation marker plus the end time, with the end date when
 *   the event ends after [dayCode], so a middle or last day's row reads as "ends at X".
 * - Same-day timed -> a plain start-end range ("9:30 - 10:30 AM").
 * - Starts on [dayCode] but ends on a later day -> the range with dates, so the two times
 *   aren't read as one day's range.
 */
internal fun formatWidgetEventTimeRange(
    context: Context,
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    timePattern: String,
    allDayText: String
): String {
    if (event.isAllDay) return allDayText

    // Without a clock flag DateUtils uses the device's 12/24h setting and ignores the in-app
    // override that the resolved pattern carries.
    val clockFlag = if (timePattern.contains('a')) DateUtils.FORMAT_12HOUR else DateUtils.FORMAT_24HOUR

    // Started before this day: show only the end, through DateUtils so the clock and locale
    // match the range path.
    if (dayCode != event.startDay) {
        val endDay = DateTimeUtils.eventTsToEndDayCode(event.endTs, event.startTs, event.isAllDay)
        // On an interior day of the span a bare time reads as "ends today", so add the date.
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
