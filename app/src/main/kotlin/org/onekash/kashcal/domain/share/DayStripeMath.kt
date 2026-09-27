package org.onekash.kashcal.domain.share

import java.time.Instant
import java.time.ZoneId

/**
 * Position of an event on a 24-hour day stripe, as fractions of the day in 0..1.
 *
 * @param startFraction left edge of the highlighted segment: 0 is midnight, 1 the next midnight.
 * @param visible false when [DayStripeMath.compute] hides the stripe.
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
 * Places an event's time range, read in its zone, on the share card's one-day stripe.
 *
 * The stripe is hidden for an all-day event, a malformed range (end at or before start), a
 * span of 24 hours or more, and a range whose start and end fall on different dates in the
 * zone. Multi-day events get a date range label instead.
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

        // Crosses midnight in the event's zone.
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
