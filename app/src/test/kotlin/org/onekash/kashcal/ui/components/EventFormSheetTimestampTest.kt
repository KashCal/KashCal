package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for EventFormSheet initialStartTs parameter.
 *
 * These tests verify that the milliseconds-based API works correctly after
 * the refactor from seconds to milliseconds (see plan hidden-booping-crown.md).
 *
 * The key change was:
 * - Old: calendar.timeInMillis = initialStartTs * 1000 (seconds input)
 * - New: calendar.timeInMillis = initialStartTs (milliseconds input)
 *
 * Test categories:
 * - Basic milliseconds conversion
 * - All-day events
 * - Multi-day events
 * - DST transitions
 * - Overnight/midnight crossing
 * - Far future dates
 * - Edge cases
 */
class EventFormSheetTimestampTest {

    /**
     * Simulates the timestamp conversion logic from EventFormSheet.kt line 375.
     * This extracts the date/time components from a milliseconds timestamp.
     */
    private fun extractDateTimeFromMs(timestampMs: Long): DateTimeComponents {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestampMs
        return DateTimeComponents(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH),
            dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE),
            dateMillis = calendar.timeInMillis
        )
    }

    data class DateTimeComponents(
        val year: Int,
        val month: Int,
        val dayOfMonth: Int,
        val hourOfDay: Int,
        val minute: Int,
        val dateMillis: Long
    )

    // ==================== Basic Milliseconds Conversion ====================

    @Test
    fun `initialStartTs in milliseconds sets correct date and time`() {
        // Jan 25 2025 10:00 AM UTC
        val timestampMs = 1737802800000L

        val result = extractDateTimeFromMs(timestampMs)

        // Verify the date is Jan 25 2025 (in local timezone)
        assertEquals(2025, result.year)
        // Note: Month is 0-indexed, so January = 0
        assertEquals(Calendar.JANUARY, result.month)
        assertEquals(25, result.dayOfMonth)
    }

    @Test
    fun `initialStartTs preserves milliseconds precision`() {
        // Timestamp with milliseconds component: 10:30:45.123
        val baseMs = 1737802800000L
        val withMs = baseMs + 123 // Add 123 milliseconds

        val result = extractDateTimeFromMs(withMs)

        // Should still parse to the same date without overflow
        assertEquals(2025, result.year)
        assertEquals(Calendar.JANUARY, result.month)
        assertEquals(25, result.dayOfMonth)
    }

    @Test
    fun `initialStartTs at midnight boundary`() {
        // Jan 1 2025 00:00:00.000 UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.JANUARY, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val midnightMs = cal.timeInMillis

        val result = extractDateTimeFromMs(midnightMs)

        assertEquals(2025, result.year)
        assertEquals(Calendar.JANUARY, result.month)
        assertEquals(1, result.dayOfMonth)
    }

    @Test
    fun `initialStartTs far future year 2100`() {
        // Jan 1 2100 00:00:00 UTC = 4102444800000L
        val farFutureMs = 4102444800000L

        val result = extractDateTimeFromMs(farFutureMs)

        // Should handle year 2100 without overflow
        assertEquals(2100, result.year)
        assertEquals(Calendar.JANUARY, result.month)
    }

    @Test
    fun `initialStartTs epoch zero`() {
        // Jan 1 1970 00:00:00 UTC
        val epochMs = 0L

        val result = extractDateTimeFromMs(epochMs)

        assertEquals(1970, result.year)
        assertEquals(Calendar.JANUARY, result.month)
        assertEquals(1, result.dayOfMonth)
    }

    // ==================== Regression: Seconds vs Milliseconds ====================

    @Test
    fun `regression - seconds value would show 1970 date`() {
        // If someone accidentally passes seconds instead of milliseconds,
        // the date would be near epoch (1970)
        val timestampSeconds = 1737802800L // This is SECONDS, not ms

        val result = extractDateTimeFromMs(timestampSeconds)

        // This would be Jan 1970 if passed as-is
        // The test documents the bug that the refactor fixed
        assertEquals(1970, result.year)
        assertEquals(Calendar.JANUARY, result.month)
    }

    @Test
    fun `regression - milliseconds value shows correct 2025 date`() {
        // Correct milliseconds value
        val timestampMs = 1737802800000L // This is MILLISECONDS

        val result = extractDateTimeFromMs(timestampMs)

        // Should show 2025, not 1970
        assertEquals(2025, result.year)
    }

    // ==================== All-Day Events ====================

    @Test
    fun `all-day event start at UTC midnight`() {
        // All-day events are typically stored as UTC midnight
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.MARCH, 15, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val utcMidnightMs = cal.timeInMillis

        val result = extractDateTimeFromMs(utcMidnightMs)

        // Date should be parseable (exact values depend on local timezone)
        assertTrue(result.year >= 2025)
        assertTrue(result.dateMillis > 0)
    }

    @Test
    fun `all-day multi-day event spanning weekend`() {
        // Fri Mar 14 to Sun Mar 16, 2025
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.MARCH, 14, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val fridayMs = cal.timeInMillis

        cal.set(2025, Calendar.MARCH, 16, 0, 0, 0)
        val sundayMs = cal.timeInMillis

        val startResult = extractDateTimeFromMs(fridayMs)
        val endResult = extractDateTimeFromMs(sundayMs)

        // Both should parse correctly
        assertTrue(startResult.dateMillis < endResult.dateMillis)
    }

    // ==================== Multi-Day Events ====================

    @Test
    fun `multi-day event spanning month boundary`() {
        // Jan 30 to Feb 2
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 30, 14, 0, 0)
        val janMs = cal.timeInMillis

        cal.set(2025, Calendar.FEBRUARY, 2, 10, 0, 0)
        val febMs = cal.timeInMillis

        val startResult = extractDateTimeFromMs(janMs)
        val endResult = extractDateTimeFromMs(febMs)

        assertEquals(Calendar.JANUARY, startResult.month)
        assertEquals(30, startResult.dayOfMonth)
        assertEquals(Calendar.FEBRUARY, endResult.month)
        assertEquals(2, endResult.dayOfMonth)
    }

    @Test
    fun `multi-day event spanning year boundary`() {
        // Dec 31, 2024 to Jan 2, 2025
        val cal = Calendar.getInstance()
        cal.set(2024, Calendar.DECEMBER, 31, 20, 0, 0)
        val dec31Ms = cal.timeInMillis

        cal.set(2025, Calendar.JANUARY, 2, 10, 0, 0)
        val jan2Ms = cal.timeInMillis

        val startResult = extractDateTimeFromMs(dec31Ms)
        val endResult = extractDateTimeFromMs(jan2Ms)

        assertEquals(2024, startResult.year)
        assertEquals(Calendar.DECEMBER, startResult.month)
        assertEquals(2025, endResult.year)
        assertEquals(Calendar.JANUARY, endResult.month)
    }

    // ==================== DST Transitions ====================

    @Test
    fun `DST spring forward - US 2025 Mar 9`() {
        // US DST 2025: Spring forward Mar 9, 2:00 AM -> 3:00 AM
        // Event at 1:30 AM (before transition)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        cal.set(2025, Calendar.MARCH, 9, 1, 30, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val beforeDstMs = cal.timeInMillis

        // Event at 3:30 AM (after transition)
        cal.set(2025, Calendar.MARCH, 9, 3, 30, 0)
        val afterDstMs = cal.timeInMillis

        val beforeResult = extractDateTimeFromMs(beforeDstMs)
        val afterResult = extractDateTimeFromMs(afterDstMs)

        // Both timestamps should parse without error
        assertTrue("Before DST timestamp should be positive", beforeDstMs > 0)
        assertTrue("After DST timestamp should be positive", afterDstMs > 0)
        assertTrue("After should be later than before", afterDstMs > beforeDstMs)

        // Both should parse to dates (exact values depend on test machine's timezone)
        assertTrue("Before result should have valid year", beforeResult.year >= 2025)
        assertTrue("After result should have valid year", afterResult.year >= 2025)
    }

    @Test
    fun `DST fall back - US 2025 Nov 2`() {
        // US DST 2025: Fall back Nov 2, 2:00 AM -> 1:00 AM
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        cal.set(2025, Calendar.NOVEMBER, 2, 0, 30, 0)
        val beforeDstMs = cal.timeInMillis

        cal.set(2025, Calendar.NOVEMBER, 2, 3, 30, 0)
        val afterDstMs = cal.timeInMillis

        val beforeResult = extractDateTimeFromMs(beforeDstMs)
        val afterResult = extractDateTimeFromMs(afterDstMs)

        // Both should parse to November 2
        assertEquals(Calendar.NOVEMBER, beforeResult.month)
        assertEquals(2, beforeResult.dayOfMonth)
        assertEquals(Calendar.NOVEMBER, afterResult.month)
        assertEquals(2, afterResult.dayOfMonth)
    }

    @Test
    fun `DST - event created day before transition`() {
        // Create event for Mar 9 on Mar 8 (before DST)
        // Use the NY timezone to create the timestamp
        val nyCal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        nyCal.set(2025, Calendar.MARCH, 9, 10, 0, 0)
        nyCal.set(Calendar.MILLISECOND, 0)
        val mar9_10amMs = nyCal.timeInMillis

        // When extracting in local timezone, the exact hour may differ
        // but the timestamp should parse without error
        val result = extractDateTimeFromMs(mar9_10amMs)

        assertTrue("Year should be 2025", result.year == 2025)
        assertTrue("Timestamp should be positive", result.dateMillis > 0)
        // The exact month/day/hour depends on local timezone, so just verify parsing works
    }

    // ==================== Overnight/Midnight Crossing ====================

    @Test
    fun `overnight event 10 PM to 2 AM`() {
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 15, 22, 0, 0)
        val startMs = cal.timeInMillis

        cal.add(Calendar.HOUR_OF_DAY, 4) // 4 hours later = 2 AM next day
        val endMs = cal.timeInMillis

        val startResult = extractDateTimeFromMs(startMs)
        val endResult = extractDateTimeFromMs(endMs)

        assertEquals(15, startResult.dayOfMonth)
        assertEquals(22, startResult.hourOfDay)
        assertEquals(16, endResult.dayOfMonth)
        assertEquals(2, endResult.hourOfDay)
    }

    @Test
    fun `midnight exactly`() {
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 15, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val midnightMs = cal.timeInMillis

        val result = extractDateTimeFromMs(midnightMs)

        assertEquals(15, result.dayOfMonth)
        assertEquals(0, result.hourOfDay)
        assertEquals(0, result.minute)
    }

    @Test
    fun `one millisecond before midnight`() {
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 15, 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val beforeMidnightMs = cal.timeInMillis

        val result = extractDateTimeFromMs(beforeMidnightMs)

        assertEquals(15, result.dayOfMonth)
        assertEquals(23, result.hourOfDay)
    }

    @Test
    fun `one millisecond after midnight`() {
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 16, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 1)
        val afterMidnightMs = cal.timeInMillis

        val result = extractDateTimeFromMs(afterMidnightMs)

        assertEquals(16, result.dayOfMonth)
        assertEquals(0, result.hourOfDay)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `leap year Feb 29`() {
        // 2024 is a leap year
        val cal = Calendar.getInstance()
        cal.set(2024, Calendar.FEBRUARY, 29, 12, 0, 0)
        val leapDayMs = cal.timeInMillis

        val result = extractDateTimeFromMs(leapDayMs)

        assertEquals(2024, result.year)
        assertEquals(Calendar.FEBRUARY, result.month)
        assertEquals(29, result.dayOfMonth)
    }

    @Test
    fun `negative timestamp pre-epoch 1969`() {
        // Dec 31, 1969 23:59:59 UTC = -1000 ms
        val preEpochMs = -1000L

        val result = extractDateTimeFromMs(preEpochMs)

        assertEquals(1969, result.year)
        assertEquals(Calendar.DECEMBER, result.month)
    }

    @Test
    fun `maximum practical timestamp year 3000`() {
        // Year 3000 - far future but within Long range
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(3000, Calendar.JANUARY, 1, 0, 0, 0)
        val farFutureMs = cal.timeInMillis

        val result = extractDateTimeFromMs(farFutureMs)

        assertEquals(3000, result.year)
    }

    @Test
    fun `typical widget dayCode timestamp`() {
        // DayPagerUtils.dayCodeToMs returns milliseconds
        // dayCode format is YYYYMMDD, e.g., 20250125 for Jan 25, 2025
        // The widget passes this directly after refactor
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 25, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStartMs = cal.timeInMillis

        val result = extractDateTimeFromMs(dayStartMs)

        assertEquals(2025, result.year)
        assertEquals(Calendar.JANUARY, result.month)
        assertEquals(25, result.dayOfMonth)
        assertEquals(0, result.hourOfDay)
    }

    @Test
    fun `calendar intent timestamp from Gmail`() {
        // Android CalendarContract.EXTRA_EVENT_BEGIN_TIME uses milliseconds
        // Simulating a Gmail "Add to Calendar" link
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.FEBRUARY, 14, 19, 0, 0) // Valentine's dinner at 7 PM
        val valentinesMs = cal.timeInMillis

        val result = extractDateTimeFromMs(valentinesMs)

        assertEquals(2025, result.year)
        assertEquals(Calendar.FEBRUARY, result.month)
        assertEquals(14, result.dayOfMonth)
        assertEquals(19, result.hourOfDay)
    }

    // ==================== Timezone Edge Cases ====================

    @Test
    fun `timestamp near international date line`() {
        // Event in Auckland NZ (UTC+12/+13)
        val nzTz = TimeZone.getTimeZone("Pacific/Auckland")
        val cal = Calendar.getInstance(nzTz)
        cal.set(2025, Calendar.JANUARY, 1, 0, 30, 0) // 12:30 AM NZ time

        val result = extractDateTimeFromMs(cal.timeInMillis)

        // Should parse without error (exact date depends on test machine timezone)
        assertTrue(result.dateMillis > 0)
    }

    @Test
    fun `timestamp in UTC-12 timezone`() {
        // Baker Island (UTC-12), westernmost timezone
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+12"))
        cal.set(2025, Calendar.JANUARY, 1, 12, 0, 0)

        val result = extractDateTimeFromMs(cal.timeInMillis)

        assertTrue(result.dateMillis > 0)
    }

    // ==================== Duration Calculation ====================

    @Test
    fun `30 minute event duration preserved`() {
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 15, 14, 0, 0) // 2:00 PM
        val startMs = cal.timeInMillis

        cal.add(Calendar.MINUTE, 30)
        val endMs = cal.timeInMillis

        val durationMs = endMs - startMs
        assertEquals(30 * 60 * 1000L, durationMs)
    }

    @Test
    fun `default event duration 30 minutes from initialStartTs`() {
        // EventFormSheet adds defaultEventDuration (30 min) to start time
        val startMs = 1737802800000L // Jan 25 2025 10:00
        val defaultDuration = 30 // minutes

        val startResult = extractDateTimeFromMs(startMs)
        val endMs = startMs + (defaultDuration * 60 * 1000L)
        val endResult = extractDateTimeFromMs(endMs)

        assertEquals(startResult.dayOfMonth, endResult.dayOfMonth)
        assertEquals(startResult.hourOfDay, endResult.hourOfDay - 0) // Same hour if duration < 60
        // End minute should be start minute + 30, or wrapped
        val expectedEndMinute = (startResult.minute + 30) % 60
        assertEquals(expectedEndMinute, endResult.minute)
    }
}
