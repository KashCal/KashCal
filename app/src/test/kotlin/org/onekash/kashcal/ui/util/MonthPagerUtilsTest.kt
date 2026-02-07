package org.onekash.kashcal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for MonthPagerUtils.
 *
 * Tests constants, page ↔ year/month conversions, and boundary scenarios
 * including the Issue #53 regression (years before 1976 not accessible).
 */
class MonthPagerUtilsTest {

    // ==================== Constants Tests ====================

    @Test
    fun `INITIAL_PAGE is center of range`() {
        assertEquals(MonthPagerUtils.TOTAL_PAGES / 2, MonthPagerUtils.INITIAL_PAGE)
    }

    @Test
    fun `TOTAL_PAGES allows ~100 years each direction`() {
        assertEquals(2400, MonthPagerUtils.TOTAL_PAGES)
        val yearsEachDirection = MonthPagerUtils.INITIAL_PAGE / 12
        assertEquals(100, yearsEachDirection)
    }

    @Test
    fun `range matches day pager range`() {
        // Month pager should cover the same ~100 year range as day pager
        val dayPagerYears = DayPagerUtils.INITIAL_PAGE / 365  // ~100
        val monthPagerYears = MonthPagerUtils.INITIAL_PAGE / 12  // 100
        // Month pager should be at least as wide as day pager
        assertTrue(
            "Month pager ($monthPagerYears yr) should cover at least as much as day pager ($dayPagerYears yr)",
            monthPagerYears >= dayPagerYears
        )
    }

    // ==================== pageToYearMonth Tests ====================

    @Test
    fun `INITIAL_PAGE maps to today's month`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.INITIAL_PAGE, 2026, Calendar.FEBRUARY
        )
        assertEquals(2026, year)
        assertEquals(Calendar.FEBRUARY, month)
    }

    @Test
    fun `INITIAL_PAGE + 1 maps to next month`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.INITIAL_PAGE + 1, 2026, Calendar.FEBRUARY
        )
        assertEquals(2026, year)
        assertEquals(Calendar.MARCH, month)
    }

    @Test
    fun `INITIAL_PAGE - 1 maps to previous month`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.INITIAL_PAGE - 1, 2026, Calendar.FEBRUARY
        )
        assertEquals(2026, year)
        assertEquals(Calendar.JANUARY, month)
    }

    @Test
    fun `page wraps year correctly forward`() {
        // Feb 2026 + 11 months = Jan 2027
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.INITIAL_PAGE + 11, 2026, Calendar.FEBRUARY
        )
        assertEquals(2027, year)
        assertEquals(Calendar.JANUARY, month)
    }

    @Test
    fun `page wraps year correctly backward`() {
        // Feb 2026 - 2 months = Dec 2025
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.INITIAL_PAGE - 2, 2026, Calendar.FEBRUARY
        )
        assertEquals(2025, year)
        assertEquals(Calendar.DECEMBER, month)
    }

    // ==================== yearMonthToPage Tests ====================

    @Test
    fun `today's month maps to INITIAL_PAGE`() {
        val page = MonthPagerUtils.yearMonthToPage(2026, Calendar.FEBRUARY, 2026, Calendar.FEBRUARY)
        assertEquals(MonthPagerUtils.INITIAL_PAGE, page)
    }

    @Test
    fun `next month maps to INITIAL_PAGE + 1`() {
        val page = MonthPagerUtils.yearMonthToPage(2026, Calendar.MARCH, 2026, Calendar.FEBRUARY)
        assertEquals(MonthPagerUtils.INITIAL_PAGE + 1, page)
    }

    @Test
    fun `previous year same month maps correctly`() {
        val page = MonthPagerUtils.yearMonthToPage(2025, Calendar.FEBRUARY, 2026, Calendar.FEBRUARY)
        assertEquals(MonthPagerUtils.INITIAL_PAGE - 12, page)
    }

    // ==================== Round-trip Tests ====================

    @Test
    fun `pageToYearMonth and yearMonthToPage are inverse`() {
        val todayYear = 2026
        val todayMonth = Calendar.FEBRUARY

        for (offset in listOf(-1200, -600, -12, -1, 0, 1, 12, 600, 1199)) {
            val page = MonthPagerUtils.INITIAL_PAGE + offset
            val (year, month) = MonthPagerUtils.pageToYearMonth(page, todayYear, todayMonth)
            val roundTrip = MonthPagerUtils.yearMonthToPage(year, month, todayYear, todayMonth)
            assertEquals("Round-trip failed for offset $offset", page, roundTrip)
        }
    }

    // ==================== Issue #53 Regression Tests ====================

    @Test
    fun `February 1976 is reachable from February 2026`() {
        val page = MonthPagerUtils.yearMonthToPage(1976, Calendar.FEBRUARY, 2026, Calendar.FEBRUARY)
        assertTrue("Page $page should be >= 0", page >= 0)
        assertTrue("Page $page should be < TOTAL_PAGES", page < MonthPagerUtils.TOTAL_PAGES)
    }

    @Test
    fun `January 1978 is reachable from February 2026`() {
        val page = MonthPagerUtils.yearMonthToPage(1978, Calendar.JANUARY, 2026, Calendar.FEBRUARY)
        assertTrue("Page $page should be >= 0", page >= 0)
        assertTrue("Page $page should be < TOTAL_PAGES", page < MonthPagerUtils.TOTAL_PAGES)
    }

    @Test
    fun `January 1930 is within range from February 2026`() {
        // 96 years back - within 100-year range
        val page = MonthPagerUtils.yearMonthToPage(1930, Calendar.JANUARY, 2026, Calendar.FEBRUARY)
        assertTrue("Page $page should be >= 0 (1930 should be reachable)", page >= 0)
        assertTrue("Page $page should be < TOTAL_PAGES", page < MonthPagerUtils.TOTAL_PAGES)
    }

    @Test
    fun `year 1926 is at boundary from 2026`() {
        // Exactly 100 years back from Feb 2026 = Feb 1926
        val page = MonthPagerUtils.yearMonthToPage(1926, Calendar.FEBRUARY, 2026, Calendar.FEBRUARY)
        assertEquals("100 years back should be page 0", 0, page)
    }

    @Test
    fun `year 2126 is at boundary from 2026`() {
        // Exactly 100 years forward from Feb 2026 = Feb 2126
        val page = MonthPagerUtils.yearMonthToPage(2126, Calendar.FEBRUARY, 2026, Calendar.FEBRUARY)
        // Page 2400 is out of bounds (0-indexed, max is 2399), but Feb 2126 = page 2400
        // The last valid page is Jan 2126 = page 2399
        assertEquals(MonthPagerUtils.TOTAL_PAGES, page)
    }

    // ==================== Boundary Tests ====================

    @Test
    fun `page 0 maps to valid date`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(0, 2026, Calendar.FEBRUARY)
        // Page 0 = 1200 months before Feb 2026 = Feb 1926
        assertEquals(1926, year)
        assertEquals(Calendar.FEBRUARY, month)
    }

    @Test
    fun `last page maps to valid date`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.TOTAL_PAGES - 1, 2026, Calendar.FEBRUARY
        )
        // Page 2399 = 1199 months after Feb 2026 = Jan 2126
        assertEquals(2126, year)
        assertEquals(Calendar.JANUARY, month)
    }
}
