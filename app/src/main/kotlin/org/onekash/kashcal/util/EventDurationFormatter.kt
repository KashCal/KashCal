package org.onekash.kashcal.util

/**
 * Returns the RFC 5545 duration between two epoch-millis timestamps: whole days, at least
 * one, for an all-day event ("P1D"), else hours and minutes ("PT1H30M", "PT45M").
 *
 * CalendarProvider requires DURATION instead of DTEND for recurring events.
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
