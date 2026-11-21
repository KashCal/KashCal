package org.onekash.kashcal.util

/**
 * Returns the RFC 5545 duration between two epoch-millis timestamps: whole days, at least
 * one, for an all-day event ("P1D"), else hours and minutes ("PT1H30M", "PT45M").
 *
 * An all-day [endTs] may be the app's inclusive end (the last millisecond of the last day) or
 * the exclusive next midnight; both give the same day count. Counting the inclusive end as is
 * would drop the last day of every occurrence of a multi-day series (#444).
 *
 * CalendarProvider requires DURATION instead of DTEND for recurring events.
 */
fun computeDurationString(startTs: Long, endTs: Long, isAllDay: Boolean): String {
    return if (isAllDay) {
        val days = ((endTs + 1 - startTs) / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
        "P${days}D"
    } else {
        val totalMinutes = ((endTs - startTs) / (60 * 1000)).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        when {
            hours > 0 && minutes > 0 -> "PT${hours}H${minutes}M"
            hours > 0 -> "PT${hours}H"
            else -> "PT${minutes}M"
        }
    }
}
