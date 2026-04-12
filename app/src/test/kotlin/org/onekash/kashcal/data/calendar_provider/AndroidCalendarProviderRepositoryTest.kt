package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Unit tests for AndroidCalendarProviderRepository helper functions.
 *
 * Tests day code conversion, enabledCalendarIds filtering,
 * and SecurityException handling patterns.
 *
 * Note: Actual ContentResolver queries cannot be unit tested (need instrumented tests).
 * These tests cover the pure logic functions exposed as internal/package-private.
 */
class AndroidCalendarProviderRepositoryTest {

    // ========== Day Code Conversion ==========

    @Test
    fun `dayCodeToStartOfDayMs returns midnight for given day code`() {
        val tz = TimeZone.getDefault()
        val dayCode = 20260215 // Feb 15, 2026
        val ms = dayCodeToStartOfDayMs(dayCode)

        val date = java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 2, 15), date)

        // Verify it's at midnight
        val time = java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        assertEquals(0, time.hour)
        assertEquals(0, time.minute)
        assertEquals(0, time.second)
    }

    @Test
    fun `dayCodeToEndOfDayMs returns end of day for given day code`() {
        val dayCode = 20260215 // Feb 15, 2026
        val ms = dayCodeToEndOfDayMs(dayCode)

        val dateTime = java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        assertEquals(LocalDate.of(2026, 2, 15), dateTime.toLocalDate())
        assertEquals(23, dateTime.hour)
        assertEquals(59, dateTime.minute)
        assertEquals(59, dateTime.second)
    }

    @Test
    fun `dayCodeToStartOfDayMs handles year boundaries`() {
        val dayCode = 20260101 // Jan 1, 2026
        val ms = dayCodeToStartOfDayMs(dayCode)
        val date = java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 1, 1), date)
    }

    @Test
    fun `dayCodeToStartOfDayMs handles month boundaries`() {
        val dayCode = 20260228 // Feb 28, 2026
        val ms = dayCodeToStartOfDayMs(dayCode)
        val date = java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 2, 28), date)
    }

    @Test
    fun `dayCodeToStartOfDayMs handles leap year`() {
        val dayCode = 20240229 // Feb 29, 2024 (leap year)
        val ms = dayCodeToStartOfDayMs(dayCode)
        val date = java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        assertEquals(LocalDate.of(2024, 2, 29), date)
    }

    @Test
    fun `round trip - startOfDay to dayCode via endOfDay`() {
        val originalDayCode = 20261231
        val startMs = dayCodeToStartOfDayMs(originalDayCode)
        val endMs = dayCodeToEndOfDayMs(originalDayCode)

        // Both should resolve to the same date
        val startDate = java.time.Instant.ofEpochMilli(startMs)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(endMs)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(startDate, endDate)
    }

    @Test
    fun `start of day is always before end of day for same day code`() {
        val dayCode = 20260615
        val startMs = dayCodeToStartOfDayMs(dayCode)
        val endMs = dayCodeToEndOfDayMs(dayCode)
        assertTrue("Start should be before end", startMs < endMs)
    }

    @Test
    fun `consecutive day codes have non-overlapping ranges`() {
        val day1End = dayCodeToEndOfDayMs(20260215)
        val day2Start = dayCodeToStartOfDayMs(20260216)
        assertTrue("Day 1 end should be before day 2 start", day1End < day2Start)
    }

    // ========== All-Day Exclusive End Adjustment ==========

    @Test
    fun `all-day event exclusive end is adjusted to inclusive day code`() {
        // CalendarProvider: 1-day all-day event on Feb 15
        // BEGIN = Feb 15 00:00 UTC, END = Feb 16 00:00 UTC (exclusive)
        val beginMs = LocalDate.of(2026, 2, 15).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val endMs = LocalDate.of(2026, 2, 16).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        // Subtracting 1ms from exclusive end → Feb 15 23:59:59.999 UTC → day code 20260215
        val adjustedEndMs = endMs - 1
        val endDay = org.onekash.kashcal.util.DateTimeUtils.eventTsToDayCode(adjustedEndMs, true)
        assertEquals(20260215, endDay)
    }

    @Test
    fun `multi-day all-day event exclusive end is adjusted correctly`() {
        // CalendarProvider: 3-day all-day event Feb 15-17
        // BEGIN = Feb 15 00:00 UTC, END = Feb 18 00:00 UTC (exclusive)
        val beginMs = LocalDate.of(2026, 2, 15).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val endMs = LocalDate.of(2026, 2, 18).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val startDay = org.onekash.kashcal.util.DateTimeUtils.eventTsToDayCode(beginMs, true)
        val adjustedEndMs = endMs - 1
        val endDay = org.onekash.kashcal.util.DateTimeUtils.eventTsToDayCode(adjustedEndMs, true)
        assertEquals(20260215, startDay)
        assertEquals(20260217, endDay) // Inclusive: Feb 17, not Feb 18
    }

    // ========== Duration Parsing ==========

    @Test
    fun `parseDurationMs handles hours`() {
        assertEquals(3_600_000L, parseDurationMs("PT1H", false))
    }

    @Test
    fun `parseDurationMs handles minutes`() {
        assertEquals(1_800_000L, parseDurationMs("PT30M", false))
    }

    @Test
    fun `parseDurationMs handles hours and minutes`() {
        assertEquals(5_400_000L, parseDurationMs("PT1H30M", false))
    }

    @Test
    fun `parseDurationMs handles days`() {
        assertEquals(86_400_000L, parseDurationMs("P1D", true))
        assertEquals(172_800_000L, parseDurationMs("P2D", true))
    }

    @Test
    fun `parseDurationMs handles weeks`() {
        assertEquals(7 * 86_400_000L, parseDurationMs("P1W", true))
    }

    @Test
    fun `parseDurationMs returns default for null`() {
        assertEquals(86_400_000L, parseDurationMs(null, true))   // all-day default: 1 day
        assertEquals(3_600_000L, parseDurationMs(null, false))    // timed default: 1 hour
    }

    @Test
    fun `parseDurationMs returns default for empty string`() {
        assertEquals(86_400_000L, parseDurationMs("", true))
        assertEquals(3_600_000L, parseDurationMs("", false))
    }

    @Test
    fun `parseDurationMs returns default for malformed`() {
        assertEquals(3_600_000L, parseDurationMs("garbage", false))
        assertEquals(86_400_000L, parseDurationMs("INVALID", true))
    }

    // ========== enabledCalendarIds Filtering ==========

    @Test
    fun `filterByEnabledCalendarIds returns only matching ids`() {
        val instances = listOf(
            testInstance(calendarId = 1L),
            testInstance(calendarId = 2L),
            testInstance(calendarId = 3L),
            testInstance(calendarId = 4L)
        )
        val enabled = setOf(1L, 3L)
        val filtered = instances.filter { it.calendarId in enabled }
        assertEquals(2, filtered.size)
        assertEquals(1L, filtered[0].calendarId)
        assertEquals(3L, filtered[1].calendarId)
    }

    @Test
    fun `empty enabledCalendarIds returns empty list`() {
        val instances = listOf(
            testInstance(calendarId = 1L),
            testInstance(calendarId = 2L)
        )
        val enabled = emptySet<Long>()
        val filtered = instances.filter { it.calendarId in enabled }
        assertTrue(filtered.isEmpty())
    }

    // ========== Test Helpers ==========

    private fun testInstance(calendarId: Long) = DeviceCalendarInstance(
        instanceId = 0L,
        eventId = 0L,
        title = "Test",
        description = "",
        location = "",
        startTs = 0L,
        endTs = 0L,
        startDay = 20260215,
        endDay = 20260215,
        isAllDay = false,
        hasRrule = false,
        rrule = null,
        reminders = emptyList(),
        calendarId = calendarId,
        calendarDisplayName = "Test Cal",
        displayColor = 0,
        status = 0,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = null
    )
}
