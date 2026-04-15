package org.onekash.kashcal.util

/**
 * Compute RFC 5545 duration string from start/end timestamps.
 *
 * CalendarProvider requires DURATION instead of DTEND for recurring events.
 * Extracted from HomeViewModel for reuse by DeviceCalendarImporter.
 *
 * @param startTs Start timestamp in epoch millis
 * @param endTs End timestamp in epoch millis
 * @param isAllDay Whether this is an all-day event
 * @return Duration string like "P1D", "PT1H30M", "PT45M"
 */
fun computeDurationString(startTs: Long, endTs: Long, isAllDay: Boolean): String {
    val diffMs = endTs - startTs
    return if (isAllDay) {
        val days = (diffMs / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
        "P${days}D"
    } else {
        val totalMinutes = (diffMs / (60 * 1000)).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        when {
            hours > 0 && minutes > 0 -> "PT${hours}H${minutes}M"
            hours > 0 -> "PT${hours}H"
            else -> "PT${minutes}M"
        }
    }
}
