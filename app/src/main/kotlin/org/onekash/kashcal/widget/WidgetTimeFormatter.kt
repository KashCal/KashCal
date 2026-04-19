package org.onekash.kashcal.widget

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
