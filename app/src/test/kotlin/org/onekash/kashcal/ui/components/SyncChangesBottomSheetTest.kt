package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for SyncChangesBottomSheet date formatting.
 *
 * Tests the formatEventTime() function which:
 * - Shows year only when different from current year (Android DateUtils pattern)
 * - Uses UTC for all-day events to prevent timezone shift
 * - Uses local timezone for timed events
 */
class SyncChangesBottomSheetTest {

    private val currentYear = Year.now().value

    // Helper to create timestamp for all-day event (UTC midnight)
    private fun allDayTimestamp(year: Int, month: Int, day: Int): Long {
        return LocalDate.of(year, month, day)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    // Helper to create timestamp for timed event (local timezone)
    private fun timedTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ========== All-day events ==========

    @Test
    fun `all-day current year shows no year`() {
        val ts = allDayTimestamp(currentYear, 1, 6) // Jan 6
        val result = formatEventTime(ts, isAllDay = true)

        // Should NOT contain year
        assertFalse("Should not contain year for current year", result.contains(currentYear.toString()))
        // Should contain day
        assertTrue("Should contain 'Jan'", result.contains("Jan"))
        assertTrue("Should contain '6'", result.contains("6"))
    }

    @Test
    fun `all-day past year shows year`() {
        val pastYear = currentYear - 1
        val ts = allDayTimestamp(pastYear, 6, 15) // June 15
        val result = formatEventTime(ts, isAllDay = true)

        // Should contain year
        assertTrue("Should contain year for past year", result.contains(pastYear.toString()))
        assertTrue("Should contain 'Jun'", result.contains("Jun"))
    }

    @Test
    fun `all-day future year shows year`() {
        val futureYear = currentYear + 1
        val ts = allDayTimestamp(futureYear, 12, 25) // Dec 25
        val result = formatEventTime(ts, isAllDay = true)

        // Should contain year
        assertTrue("Should contain year for future year", result.contains(futureYear.toString()))
        assertTrue("Should contain 'Dec'", result.contains("Dec"))
    }

    @Test
    fun `all-day December 31 current year shows no year`() {
        val ts = allDayTimestamp(currentYear, 12, 31)
        val result = formatEventTime(ts, isAllDay = true)

        // Should NOT contain year (still current year)
        assertFalse("Dec 31 of current year should not show year", result.contains(currentYear.toString()))
    }

    @Test
    fun `all-day January 1 next year shows year`() {
        val nextYear = currentYear + 1
        val ts = allDayTimestamp(nextYear, 1, 1)
        val result = formatEventTime(ts, isAllDay = true)

        // Should contain year
        assertTrue("Jan 1 of next year should show year", result.contains(nextYear.toString()))
    }

    // ========== Timed events ==========

    @Test
    fun `timed current year shows no year`() {
        val ts = timedTimestamp(currentYear, 3, 15, 14, 30) // March 15 at 2:30 PM
        val result = formatEventTime(ts, isAllDay = false)

        // Should NOT contain year
        assertFalse("Should not contain year for current year", result.contains(currentYear.toString()))
        // Should contain time indicator
        assertTrue("Should contain 'at'", result.contains("at"))
        // Should contain day
        assertTrue("Should contain 'Mar'", result.contains("Mar"))
    }

    @Test
    fun `timed past year shows year`() {
        val pastYear = currentYear - 2
        val ts = timedTimestamp(pastYear, 7, 4, 10, 0) // July 4 at 10:00 AM
        val result = formatEventTime(ts, isAllDay = false)

        // Should contain year
        assertTrue("Should contain year for past year", result.contains(pastYear.toString()))
        assertTrue("Should contain 'at'", result.contains("at"))
    }

    @Test
    fun `timed future year shows year`() {
        val futureYear = currentYear + 2
        val ts = timedTimestamp(futureYear, 11, 11, 11, 11)
        val result = formatEventTime(ts, isAllDay = false)

        // Should contain year
        assertTrue("Should contain year for future year", result.contains(futureYear.toString()))
    }

    // ========== Format structure tests ==========

    @Test
    fun `all-day format is day-of-week comma month day`() {
        // Use a known date: Monday, January 6, 2025
        val ts = allDayTimestamp(2025, 1, 6)
        val result = formatEventTime(ts, isAllDay = true)

        // Expected format: "Mon, Jan 6" (no year if current) or "Mon, Jan 6, 2025" (with year)
        assertTrue("Should start with day of week", result.matches(Regex("^[A-Z][a-z]{2},.*")))
        assertTrue("Should contain month abbreviation", result.contains("Jan"))
        // Should NOT contain time indicator for all-day
        assertFalse("All-day should not contain 'at'", result.contains("at"))
    }

    @Test
    fun `timed format includes time with at`() {
        val ts = timedTimestamp(currentYear, 5, 20, 15, 45) // 3:45 PM
        val result = formatEventTime(ts, isAllDay = false)

        // Should contain "at" and time
        assertTrue("Should contain 'at' for timed events", result.contains("at"))
        assertTrue("Should contain PM or AM", result.contains("PM") || result.contains("AM"))
    }

    // ========== UTC for all-day prevents timezone shift ==========

    @Test
    fun `all-day uses UTC to avoid date shift`() {
        // Jan 6 at UTC midnight
        val ts = allDayTimestamp(currentYear, 1, 6)
        val result = formatEventTime(ts, isAllDay = true)

        // Regardless of local timezone, should show Jan 6
        assertTrue("Should show Jan 6 regardless of timezone", result.contains("Jan") && result.contains("6"))
    }
}
