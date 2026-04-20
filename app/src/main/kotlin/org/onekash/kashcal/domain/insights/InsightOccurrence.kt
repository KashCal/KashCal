package org.onekash.kashcal.domain.insights

interface InsightOccurrence {
    val startTs: Long
    val endTs: Long
    val isAllDay: Boolean
    val startDay: Int
    val endDay: Int
    val calendarId: Long
}

data class SimpleOccurrence(
    override val startTs: Long,
    override val endTs: Long,
    override val isAllDay: Boolean,
    override val startDay: Int,
    override val endDay: Int,
    override val calendarId: Long
) : InsightOccurrence
