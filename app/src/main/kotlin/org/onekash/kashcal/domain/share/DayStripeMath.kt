package org.onekash.kashcal.domain.share

import java.time.Instant
import java.time.ZoneId

/**
 * Position of an event within a 24-hour day stripe, normalized to [0, 1].
 *
 * @param startFraction left edge of the highlighted segment, 0 = midnight, 1 = next midnight.
 * @param widthFraction fractional width of the highlighted segment.
 * @param visible       false for multi-day, all-day, or malformed (end ≤ start) events.
 */
data class StripePosition(
    val startFraction: Float,
    val widthFraction: Float,
    val visible: Boolean,
) {
    companion object {
        val Hidden = StripePosition(0f, 0f, false)
    }
}

/**
 * Pure math: turns an event's epoch-ms range + timezone into a [StripePosition]
 * for the day-stripe rendering on a share card.
 *
 * Hidden when:
 *  - all-day (the day stripe is meaningless for full-day events)
 *  - multi-day (the stripe represents one day; spans > 24h get a date range
 *    label instead)
 *  - exactly 24h (boundary of multi-day; same rationale)
 *  - malformed (end ≤ start)
 */
object DayStripeMath {

    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    fun compute(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        zone: ZoneId,
    ): StripePosition {
        if (isAllDay) return StripePosition.Hidden
        if (endTs <= startTs) return StripePosition.Hidden
        if (endTs - startTs >= MS_PER_DAY) return StripePosition.Hidden

        val startInstant = Instant.ofEpochMilli(startTs)
        val endInstant = Instant.ofEpochMilli(endTs)

        val startZdt = startInstant.atZone(zone)
        val endZdt = endInstant.atZone(zone)

        // Multi-day: different calendar dates in the event's zone.
        if (startZdt.toLocalDate() != endZdt.toLocalDate()) {
            return StripePosition.Hidden
        }

        val midnight = startZdt.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val msSinceMidnight = startTs - midnight
        val msDuration = endTs - startTs

        val startFraction = (msSinceMidnight.toFloat() / MS_PER_DAY.toFloat())
            .coerceIn(0f, 1f)
        val widthFraction = (msDuration.toFloat() / MS_PER_DAY.toFloat())
            .coerceIn(0f, 1f)

        return StripePosition(
            startFraction = startFraction,
            widthFraction = widthFraction,
            visible = true,
        )
    }
}
