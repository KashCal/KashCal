package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for RFC 5545 duration parsing in DateTimeUtils.
 *
 * CalendarProvider stores duration instead of endTs for recurring events.
 * Format: P[n]D for days, PT[n]H[n]M[n]S for hours/minutes/seconds
 */
class DurationParserTest {

    @Test
    fun `parseDurationToMillis returns 5400000 for PT1H30M`() {
        // 1 hour 30 minutes = 90 minutes = 5,400,000 ms
        val result = DateTimeUtils.parseDurationToMillis("PT1H30M")
        assertEquals(5_400_000L, result)
    }

    @Test
    fun `parseDurationToMillis returns 86400000 for P1D`() {
        // 1 day = 86,400,000 ms
        val result = DateTimeUtils.parseDurationToMillis("P1D")
        assertEquals(86_400_000L, result)
    }

    @Test
    fun `parseDurationToMillis returns 1800000 for PT30M`() {
        // 30 minutes = 1,800,000 ms
        val result = DateTimeUtils.parseDurationToMillis("PT30M")
        assertEquals(1_800_000L, result)
    }

    @Test
    fun `parseDurationToMillis returns 3600000 for PT1H`() {
        // 1 hour = 3,600,000 ms
        val result = DateTimeUtils.parseDurationToMillis("PT1H")
        assertEquals(3_600_000L, result)
    }

    @Test
    fun `parseDurationToMillis returns correct value for P2D`() {
        // 2 days = 172,800,000 ms
        val result = DateTimeUtils.parseDurationToMillis("P2D")
        assertEquals(172_800_000L, result)
    }

    @Test
    fun `parseDurationToMillis handles PT2H45M`() {
        // 2 hours 45 minutes = 165 minutes = 9,900,000 ms
        val result = DateTimeUtils.parseDurationToMillis("PT2H45M")
        assertEquals(9_900_000L, result)
    }

    @Test
    fun `parseDurationToMillis handles seconds PT1H30M45S`() {
        // 1 hour 30 minutes 45 seconds = 5,445,000 ms
        val result = DateTimeUtils.parseDurationToMillis("PT1H30M45S")
        assertEquals(5_445_000L, result)
    }

    @Test
    fun `parseDurationToMillis returns null for null input`() {
        val result = DateTimeUtils.parseDurationToMillis(null)
        assertNull(result)
    }

    @Test
    fun `parseDurationToMillis returns null for empty string`() {
        val result = DateTimeUtils.parseDurationToMillis("")
        assertNull(result)
    }

    @Test
    fun `parseDurationToMillis returns null for invalid format`() {
        val result = DateTimeUtils.parseDurationToMillis("invalid")
        assertNull(result)
    }

    @Test
    fun `parseDurationToMillis returns null for malformed duration`() {
        val result = DateTimeUtils.parseDurationToMillis("P")
        assertNull(result)
    }

    @Test
    fun `parseDurationToMillis handles PT0M`() {
        // 0 minutes = 0 ms
        val result = DateTimeUtils.parseDurationToMillis("PT0M")
        assertEquals(0L, result)
    }

    @Test
    fun `parseDurationToMillis handles weeks P1W`() {
        // 1 week = 7 days = 604,800,000 ms
        val result = DateTimeUtils.parseDurationToMillis("P1W")
        assertEquals(604_800_000L, result)
    }

    @Test
    fun `parseDurationToMillis returns null on overflow rather than a garbage negative`() {
        // A week count whose millisecond total overflows Long must fail safe
        // (null) so the caller falls back to endTs — never a negative duration
        // that would place the event's end before its start.
        assertNull(DateTimeUtils.parseDurationToMillis("P999999999999W"))
        assertNull(DateTimeUtils.parseDurationToMillis("PT99999999999999999H"))
    }
}
