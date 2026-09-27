package org.onekash.kashcal.domain.insights

interface InsightOccurrence {
    val startTs: Long
    val endTs: Long
    val isAllDay: Boolean
    val startDay: Int
    val endDay: Int
    val calendarId: Long
    /**
     * RFC 5545 TRANSP value: "OPAQUE" (busy) or "TRANSPARENT" (free). Insights stats may
     * ignore it; share-availability skips free-marked events when computing open blocks.
     */
    val transparency: String
}

data class SimpleOccurrence(
    override val startTs: Long,
    override val endTs: Long,
    override val isAllDay: Boolean,
    override val startDay: Int,
    override val endDay: Int,
    override val calendarId: Long,
    override val transparency: String = "OPAQUE"
) : InsightOccurrence
