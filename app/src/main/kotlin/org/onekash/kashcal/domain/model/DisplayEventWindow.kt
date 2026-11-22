package org.onekash.kashcal.domain.model

/**
 * Returns true when the event has time in the half-open window [startMs]..[endMs]: an all-day
 * event by day code ([firstDayCode]..[lastDayCode]), since its start is UTC midnight; a
 * zero-length event when it starts inside the window; any other event when it ends after
 * [startMs] and starts before [endMs], so one that only touches an edge is outside.
 */
fun DisplayEvent.overlapsWindow(startMs: Long, endMs: Long, firstDayCode: Int, lastDayCode: Int): Boolean = when {
    isAllDay -> startDay <= lastDayCode && endDay >= firstDayCode
    endTs == startTs -> startTs >= startMs && startTs < endMs
    else -> endTs > startMs && startTs < endMs
}
