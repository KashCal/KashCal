package org.onekash.kashcal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for DayPagerUtils.
 *
 * Tests page ↔ date conversions, DST handling, and year boundary scenarios.
 */
class DayPagerUtilsTest {

    // ==================== Constants Tests ====================

    @Test
    fun `INITIAL_PAGE is reasonable value for today`() {
        assertEquals(36500, DayPagerUtils.INITIAL_PAGE)
    }

    @Test
    fun `TOTAL_PAGES allows ~100 years each direction`() {
        assertEquals(73000, DayPagerUtils.TOTAL_PAGES)
        // 36500 days = 100 years
        val yearsEachDirection = DayPagerUtils.INITIAL_PAGE / 365
        assertTrue(yearsEachDirection >= 99) // ~100 years
    }

    @Test
    fun `DAY_MS is exactly 24 hours`() {
        assertEquals(24 * 60 * 60 * 1000L, DayPagerUtils.DAY_MS)
    }

    // ==================== Page ↔ Date Conversion Tests ====================

    @Test
    fun `pageToDateMs returns todayMs for INITIAL_PAGE`() {
        val todayMs = getFixedTodayMs()
        val result = DayPagerUtils.pageToDateMs(DayPagerUtils.INITIAL_PAGE, todayMs)
        assertEquals(todayMs, result)
    }

    @Test
    fun `pageToDateMs returns tomorrow for INITIAL_PAGE + 1`() {
        val todayMs = getFixedTodayMs()
        val result = DayPagerUtils.pageToDateMs(DayPagerUtils.INITIAL_PAGE + 1, todayMs)
        assertEquals(todayMs + DayPagerUtils.DAY_MS, result)
    }

    @Test
    fun `pageToDateMs returns yesterday for INITIAL_PAGE - 1`() {
        val todayMs = getFixedTodayMs()
        val result = DayPagerUtils.pageToDateMs(DayPagerUtils.INITIAL_PAGE - 1, todayMs)
        assertEquals(todayMs - DayPagerUtils.DAY_MS, result)
    }

    @Test
    fun `dateToPage returns INITIAL_PAGE for todayMs`() {
        val todayMs = getFixedTodayMs()
        val result = DayPagerUtils.dateToPage(todayMs, todayMs)
        assertEquals(DayPagerUtils.INITIAL_PAGE, result)
    }

    @Test
    fun `dateToPage returns INITIAL_PAGE + 1 for tomorrow`() {
        val todayMs = getFixedTodayMs()
        val result = DayPagerUtils.dateToPage(todayMs + DayPagerUtils.DAY_MS, todayMs)
        assertEquals(DayPagerUtils.INITIAL_PAGE + 1, result)
    }

    @Test
    fun `dateToPage returns INITIAL_PAGE - 7 for week ago`() {
        val todayMs = getFixedTodayMs()
        val weekAgoMs = todayMs - (7 * DayPagerUtils.DAY_MS)
        val result = DayPagerUtils.dateToPage(weekAgoMs, todayMs)
        assertEquals(DayPagerUtils.INITIAL_PAGE - 7, result)
    }

    @Test
    fun `pageToDateMs and dateToPage are inverse operations`() {
        val todayMs = getFixedTodayMs()

        // Test several offsets
        listOf(-365, -30, -7, -1, 0, 1, 7, 30, 365).forEach { offset ->
            val page = DayPagerUtils.INITIAL_PAGE + offset
            val dateMs = DayPagerUtils.pageToDateMs(page, todayMs)
            val recoveredPage = DayPagerUtils.dateToPage(dateMs, todayMs)
            assertEquals("Round trip failed for offset $offset", page, recoveredPage)
        }
    }

    // ==================== DayCode Conversion Tests ====================

    @Test
    fun `msToDayCode returns correct YYYYMMDD format`() {
        // Jan 15, 2026 00:00:00 UTC
        val jan15_2026 = LocalDate.of(2026, 1, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val dayCode = DayPagerUtils.msToDayCode(jan15_2026)
        assertEquals(20260115, dayCode)
    }

    @Test
    fun `dayCodeToLocalDate parses YYYYMMDD correctly`() {
        val localDate = DayPagerUtils.dayCodeToLocalDate(20260115)
        assertEquals(LocalDate.of(2026, 1, 15), localDate)
    }

    @Test
    fun `dayCodeToMs and msToDayCode are inverse operations`() {
        val dayCode = 20260115
        val ms = DayPagerUtils.dayCodeToMs(dayCode)
        val recoveredDayCode = DayPagerUtils.msToDayCode(ms)
        assertEquals(dayCode, recoveredDayCode)
    }

    // ==================== Year Boundary Tests ====================

    @Test
    fun `page conversion works across year boundary Dec 31 to Jan 1`() {
        // Use Dec 31, 2025 as "today"
        val dec31_2025 = LocalDate.of(2025, 12, 31)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        // Tomorrow (Jan 1, 2026) should be page + 1
        val jan1Page = DayPagerUtils.INITIAL_PAGE + 1
        val jan1Ms = DayPagerUtils.pageToDateMs(jan1Page, dec31_2025)

        // Verify it's in January
        val dayCode = DayPagerUtils.msToDayCode(jan1Ms)
        assertTrue("Expected January 2026, got dayCode $dayCode", dayCode >= 20260101)
    }

    @Test
    fun `dayCode handles leap year Feb 29`() {
        // 2024 is a leap year
        val feb29_2024 = LocalDate.of(2024, 2, 29)
        val dayCode = feb29_2024.year * 10000 + feb29_2024.monthValue * 100 + feb29_2024.dayOfMonth
        assertEquals(20240229, dayCode)

        val recoveredDate = DayPagerUtils.dayCodeToLocalDate(dayCode)
        assertEquals(feb29_2024, recoveredDate)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `dateToPage handles mid-day timestamps`() {
        val todayMs = getFixedTodayMs()
        // Add 12 hours to today
        val midDayMs = todayMs + (12 * 60 * 60 * 1000L)

        val page = DayPagerUtils.dateToPage(midDayMs, todayMs)
        assertEquals(DayPagerUtils.INITIAL_PAGE, page) // Should still be today
    }

    @Test
    fun `page range covers at least 100 years in each direction`() {
        val todayMs = getFixedTodayMs()

        // 100 years ago (page 0)
        val page0Ms = DayPagerUtils.pageToDateMs(0, todayMs)
        val page0DayCode = DayPagerUtils.msToDayCode(page0Ms)

        // 100 years from now (page 72999)
        val lastPageMs = DayPagerUtils.pageToDateMs(DayPagerUtils.TOTAL_PAGES - 1, todayMs)
        val lastPageDayCode = DayPagerUtils.msToDayCode(lastPageMs)

        // Verify significant year range
        val startYear = page0DayCode / 10000
        val endYear = lastPageDayCode / 10000
        assertTrue("Year range should span at least 100 years", endYear - startYear >= 100)
    }

    // ==================== Bug Regression Tests ====================

    /**
     * Bug #1: Integer division truncation for negative partial days.
     * Input: 6 hours before midnight (yesterday at 6 PM)
     * Expected: page for yesterday
     * Bug: Truncation gave page for today (truncates -0.25 to 0)
     */
    @Test
    fun `dateToPage handles evening timestamp - negative partial day regression`() {
        val todayMs = getFixedTodayMs()  // Jan 15 midnight
        val yesterdayEvening = todayMs - (6 * 60 * 60 * 1000L)  // 6 hours before = Jan 14 6PM

        val page = DayPagerUtils.dateToPage(yesterdayEvening, todayMs)

        assertEquals(
            "Yesterday evening should map to yesterday's page",
            DayPagerUtils.INITIAL_PAGE - 1,
            page
        )
    }

    @Test
    fun `dateToPage handles 1 hour before midnight`() {
        val todayMs = getFixedTodayMs()
        val justBeforeMidnight = todayMs - (1 * 60 * 60 * 1000L)  // 1 hour before

        val page = DayPagerUtils.dateToPage(justBeforeMidnight, todayMs)

        assertEquals(DayPagerUtils.INITIAL_PAGE - 1, page)  // Should be yesterday
    }

    /**
     * Bug #2: DST spring forward (23-hour day).
     * March 10, 2024 = DST starts in US, day has only 23 hours.
     * Using millisecond arithmetic would give wrong day.
     */
    @Test
    fun `pageToDateMs handles DST spring forward - 23 hour day regression`() {
        // March 10, 2024 is DST start day in US (23 hours)
        val march10_2024 = LocalDate.of(2024, 3, 10)
            .atStartOfDay(ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

        // Go back 2 days from March 10 -> should be March 8
        val march8Page = DayPagerUtils.INITIAL_PAGE - 2
        val result = DayPagerUtils.pageToDateMs(march8Page, march10_2024)

        // Verify we got March 8, not March 7 (which would happen with ms arithmetic)
        val resultDate = LocalDate.of(2024, 3, 10).minusDays(2)
        assertEquals(LocalDate.of(2024, 3, 8), resultDate)

        // Verify via dayCode
        val resultDayCode = DayPagerUtils.msToDayCode(result)
        assertEquals(20240308, resultDayCode)
    }

    /**
     * Bug #2: DST fall back (25-hour day).
     * Nov 3, 2024 = DST ends in US, day has 25 hours.
     * Using millisecond arithmetic would give wrong day.
     */
    @Test
    fun `pageToDateMs handles DST fall back - 25 hour day regression`() {
        // Nov 3, 2024 is DST end day in US (25 hours)
        val nov3_2024 = LocalDate.of(2024, 11, 3)
            .atStartOfDay(ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

        // Go back 2 days from Nov 3 -> should be Nov 1
        val nov1Page = DayPagerUtils.INITIAL_PAGE - 2
        val result = DayPagerUtils.pageToDateMs(nov1Page, nov3_2024)

        // Verify via dayCode
        val resultDayCode = DayPagerUtils.msToDayCode(result)
        assertEquals(20241101, resultDayCode)
    }

    @Test
    fun `dateToPage and pageToDateMs round trip across DST boundary`() {
        // Test round trip across DST spring forward
        val march10_2024 = LocalDate.of(2024, 3, 10)
            .atStartOfDay(ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

        // Go back 5 days and verify round trip
        val targetPage = DayPagerUtils.INITIAL_PAGE - 5
        val targetMs = DayPagerUtils.pageToDateMs(targetPage, march10_2024)
        val recoveredPage = DayPagerUtils.dateToPage(targetMs, march10_2024)

        assertEquals(targetPage, recoveredPage)
    }

    // ==================== Pre-1970 Date Tests (Issue #53) ====================

    @Test
    fun `dateToPage handles pre-1970 date with negative epoch millis`() {
        val todayMs = getFixedTodayMs()

        // Apollo 11 — July 20, 1969
        val apollo11 = LocalDate.of(1969, 7, 20)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertTrue("Pre-1970 date should have negative millis", apollo11 < 0)

        val page = DayPagerUtils.dateToPage(apollo11, todayMs)
        assertTrue("Pre-1970 page should be before INITIAL_PAGE", page < DayPagerUtils.INITIAL_PAGE)

        // Round-trip
        val recoveredMs = DayPagerUtils.pageToDateMs(page, todayMs)
        val recoveredPage = DayPagerUtils.dateToPage(recoveredMs, todayMs)
        assertEquals("Round trip failed for pre-1970 date", page, recoveredPage)
    }

    @Test
    fun `msToDayCode handles pre-1970 date`() {
        val dec31_1969 = LocalDate.of(1969, 12, 31)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val dayCode = DayPagerUtils.msToDayCode(dec31_1969)
        assertEquals(19691231, dayCode)
    }

    @Test
    fun `pageToDateMs and dateToPage round trip for pre-1970 dates`() {
        val todayMs = getFixedTodayMs()

        // Test several pre-1970 dates: 1969, 1960, 1940, 1926
        listOf(
            LocalDate.of(1969, 7, 20),
            LocalDate.of(1960, 1, 1),
            LocalDate.of(1940, 6, 15),
            LocalDate.of(1926, 3, 10)
        ).forEach { date ->
            val dateMs = date.atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val page = DayPagerUtils.dateToPage(dateMs, todayMs)
            val recoveredMs = DayPagerUtils.pageToDateMs(page, todayMs)
            val recoveredPage = DayPagerUtils.dateToPage(recoveredMs, todayMs)

            assertEquals("Round trip failed for $date", page, recoveredPage)

            // Verify dayCode is correct
            val dayCode = DayPagerUtils.msToDayCode(recoveredMs)
            val expectedDayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
            assertEquals("DayCode mismatch for $date", expectedDayCode, dayCode)
        }
    }

    // ==================== Helper Functions ====================

    /**
     * Get a fixed "today" timestamp for deterministic tests.
     * Jan 15, 2026 00:00:00 local time.
     */
    private fun getFixedTodayMs(): Long {
        return LocalDate.of(2026, 1, 15)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
