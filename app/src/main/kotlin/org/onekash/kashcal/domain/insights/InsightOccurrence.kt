package org.onekash.kashcal.domain.insights

interface InsightOccurrence {
    val startTs: Long
    val endTs: Long
    val isAllDay: Boolean
    val startDay: Int
    val endDay: Int
    val calendarId: Long
    /**
     * RFC 5545 TRANSP value: "OPAQUE" (busy) or "TRANSPARENT" (free).
     * Consumers that only care about busy-time totals (e.g., Insights stats)
     * may ignore this field; share-availability uses it to skip free-marked
     * events when computing the user's shareable open blocks.
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
