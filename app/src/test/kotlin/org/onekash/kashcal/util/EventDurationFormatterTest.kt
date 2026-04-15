package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EventDurationFormatterTest {

    @Test
    fun `all-day single day returns P1D`() {
        val startTs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val endTs = startTs + 24 * 60 * 60 * 1000 // Jan 2, 2024 00:00 UTC
        assertEquals("P1D", computeDurationString(startTs, endTs, isAllDay = true))
    }

    @Test
    fun `all-day multi day returns PnD`() {
        val startTs = 1704067200000L
        val endTs = startTs + 7 * 24 * 60 * 60 * 1000
        assertEquals("P7D", computeDurationString(startTs, endTs, isAllDay = true))
    }

    @Test
    fun `all-day zero duration coerced to P1D`() {
        val startTs = 1704067200000L
        assertEquals("P1D", computeDurationString(startTs, startTs, isAllDay = true))
    }

    @Test
    fun `timed event hours and minutes`() {
        val startTs = 1704067200000L
        val endTs = startTs + (1 * 60 + 30) * 60 * 1000 // 1h30m
        assertEquals("PT1H30M", computeDurationString(startTs, endTs, isAllDay = false))
    }

    @Test
    fun `timed event hours only`() {
        val startTs = 1704067200000L
        val endTs = startTs + 2 * 60 * 60 * 1000 // 2h
        assertEquals("PT2H", computeDurationString(startTs, endTs, isAllDay = false))
    }

    @Test
    fun `timed event minutes only`() {
        val startTs = 1704067200000L
        val endTs = startTs + 45 * 60 * 1000 // 45m
        assertEquals("PT45M", computeDurationString(startTs, endTs, isAllDay = false))
    }

    @Test
    fun `timed event zero duration returns PT0M`() {
        val startTs = 1704067200000L
        assertEquals("PT0M", computeDurationString(startTs, startTs, isAllDay = false))
    }
}
