package org.onekash.kashcal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests [MonthPagerUtils]: its constants, page and year/month conversions, and the range bounds,
 * including distant years such as 1976 and 1930 staying reachable (#53).
 */
class MonthPagerUtilsTest {

    // ==================== Constants Tests ====================

    @Test
    fun `INITIAL_PAGE is center of range`() {
        assertEquals(MonthPagerUtils.TOTAL_PAGES / 2, MonthPagerUtils.INITIAL_PAGE)
    }

    @Test
    fun `TOTAL_PAGES is 152 years of months each side of today's month`() {
        assertEquals(1824, MonthPagerUtils.INITIAL_PAGE)
        assertEquals(3649, MonthPagerUtils.TOTAL_PAGES)
    }

    @Test
    fun `range matches day pager range`() {
        // The month pager covers the day pager's range, in 365-day years (the day pager's
        // reach in calendar years); CalendarSpanPropertiesTest checks it month by month.
        val dayPagerYears = DayPagerUtils.INITIAL_PAGE / 365
        val monthPagerYears = MonthPagerUtils.INITIAL_PAGE / 12
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

    // ==================== Distant years (#53) ====================

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
        // 96 years back, inside the span.
        val page = MonthPagerUtils.yearMonthToPage(1930, Calendar.JANUARY, 2026, Calendar.FEBRUARY)
        assertTrue("Page $page should be >= 0 (1930 should be reachable)", page >= 0)
        assertTrue("Page $page should be < TOTAL_PAGES", page < MonthPagerUtils.TOTAL_PAGES)
    }

    @Test
    fun `January 1876, the span's first month, is reachable from December 2026`() {
        // December is the furthest month of the year from the span's first January.
        val page = MonthPagerUtils.yearMonthToPage(1876, Calendar.JANUARY, 2026, Calendar.DECEMBER)
        assertEquals(13, page)
    }

    @Test
    fun `December 2176, the span's last month, is reachable from January 2026`() {
        val page = MonthPagerUtils.yearMonthToPage(2176, Calendar.DECEMBER, 2026, Calendar.JANUARY)
        assertEquals(MonthPagerUtils.TOTAL_PAGES - 14, page)
    }

    // ==================== Boundary Tests ====================

    @Test
    fun `page 0 maps to valid date`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(0, 2026, Calendar.FEBRUARY)
        // Page 0 = 1824 months before Feb 2026 = Feb 1874
        assertEquals(1874, year)
        assertEquals(Calendar.FEBRUARY, month)
    }

    @Test
    fun `last page maps to valid date`() {
        val (year, month) = MonthPagerUtils.pageToYearMonth(
            MonthPagerUtils.TOTAL_PAGES - 1, 2026, Calendar.FEBRUARY
        )
        // Page 3648 = 1824 months after Feb 2026 = Feb 2178
        assertEquals(2178, year)
        assertEquals(Calendar.FEBRUARY, month)
    }
}
