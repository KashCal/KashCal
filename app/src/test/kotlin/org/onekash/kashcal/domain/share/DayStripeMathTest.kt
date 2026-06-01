package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class DayStripeMathTest {

    private val nyc: ZoneId = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 5, 31)

    private fun ts(time: LocalTime, zone: ZoneId = nyc): Long =
        LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `midnight to noon — start fraction 0, width 0_5`() {
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.MIDNIGHT),
            endTs = ts(LocalTime.NOON),
            isAllDay = false,
            zone = nyc,
        )
        assertTrue(pos.visible)
        assertEquals(0f, pos.startFraction, 0.0001f)
        assertEquals(0.5f, pos.widthFraction, 0.0001f)
    }

    @Test
    fun `eleven thirty AM to one PM — typical brunch`() {
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.of(11, 30)),
            endTs = ts(LocalTime.of(13, 0)),
            isAllDay = false,
            zone = nyc,
        )
        assertTrue(pos.visible)
        // 11:30 = 11.5h / 24h = 0.479166...
        assertEquals(0.479166f, pos.startFraction, 0.0001f)
        // 1.5h / 24h = 0.0625
        assertEquals(0.0625f, pos.widthFraction, 0.0001f)
    }

    @Test
    fun `exactly noon start`() {
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.NOON),
            endTs = ts(LocalTime.of(13, 0)),
            isAllDay = false,
            zone = nyc,
        )
        assertEquals(0.5f, pos.startFraction, 0.0001f)
    }

    @Test
    fun `single-minute event has visible=true and tiny but nonzero width`() {
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.of(12, 0)),
            endTs = ts(LocalTime.of(12, 1)),
            isAllDay = false,
            zone = nyc,
        )
        assertTrue(pos.visible)
        // 1 minute / (24*60 minutes) ≈ 0.000694
        assertTrue("width >= 1/(24*60), was ${pos.widthFraction}", pos.widthFraction > 0f)
        assertEquals(1f / (24f * 60f), pos.widthFraction, 0.00001f)
    }

    @Test
    fun `multi-day event hides the stripe`() {
        // 11:30 AM May 31 to 11:30 AM Jun 2 (more than 24h)
        val start = LocalDateTime.of(day, LocalTime.of(11, 30)).atZone(nyc).toInstant().toEpochMilli()
        val end = LocalDateTime.of(day.plusDays(2), LocalTime.of(11, 30)).atZone(nyc).toInstant().toEpochMilli()
        val pos = DayStripeMath.compute(
            startTs = start,
            endTs = end,
            isAllDay = false,
            zone = nyc,
        )
        assertFalse(pos.visible)
    }

    @Test
    fun `all-day event hides the stripe`() {
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.MIDNIGHT),
            endTs = ts(LocalTime.MIDNIGHT) + 24 * 60 * 60 * 1000L - 1,
            isAllDay = true,
            zone = nyc,
        )
        assertFalse(pos.visible)
    }

    @Test
    fun `exactly 24-hour event hides the stripe (multi-day boundary)`() {
        // Midnight May 31 to midnight Jun 1 — boundary case, must not paint
        val start = LocalDateTime.of(day, LocalTime.MIDNIGHT).atZone(nyc).toInstant().toEpochMilli()
        val end = LocalDateTime.of(day.plusDays(1), LocalTime.MIDNIGHT).atZone(nyc).toInstant().toEpochMilli()
        val pos = DayStripeMath.compute(
            startTs = start,
            endTs = end,
            isAllDay = false,
            zone = nyc,
        )
        assertFalse(pos.visible)
    }

    @Test
    fun `same-day event ending at midnight goes all the way to the right`() {
        // 6 PM to 11:59 PM
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.of(18, 0)),
            endTs = ts(LocalTime.of(23, 59)),
            isAllDay = false,
            zone = nyc,
        )
        assertTrue(pos.visible)
        assertEquals(0.75f, pos.startFraction, 0.0001f)
        // (23.9833 - 18) / 24 ≈ 0.2493
        assertTrue(pos.widthFraction > 0.24f && pos.widthFraction < 0.26f)
    }

    @Test
    fun `start clamps to 0 if before midnight in zone (boundary safety)`() {
        // Defensive: malformed event with end before start. Stripe should
        // render at 0 width or simply not panic.
        val pos = DayStripeMath.compute(
            startTs = ts(LocalTime.of(13, 0)),
            endTs = ts(LocalTime.of(11, 30)),
            isAllDay = false,
            zone = nyc,
        )
        // End before start: stripe is invisible (defensive).
        assertFalse(pos.visible)
    }
}
