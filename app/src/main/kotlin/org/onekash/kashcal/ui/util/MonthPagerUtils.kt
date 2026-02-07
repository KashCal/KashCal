package org.onekash.kashcal.ui.util

import java.util.Calendar

/**
 * Utility constants and functions for the month pager.
 *
 * The month pager uses a page range centered on today's month to allow
 * navigation ~100 years in each direction. Pages are lazily composed,
 * so the range has zero memory cost.
 *
 * Page index is relative to "today's month" at app launch:
 * - Page [INITIAL_PAGE] = current month
 * - Page [INITIAL_PAGE] + 1 = next month
 * - Page [INITIAL_PAGE] - 1 = previous month
 */
object MonthPagerUtils {
    /** Initial page index (represents current month) */
    const val INITIAL_PAGE = 1200

    /** Total number of pages (~100 years each direction) */
    const val TOTAL_PAGES = 2400

    /**
     * Convert a page index to year and month.
     *
     * @param page Page index from pager
     * @param todayYear The year at app launch
     * @param todayMonth The month at app launch (Calendar.JANUARY = 0)
     * @return Pair of (year, month) where month uses Calendar constants (0-based)
     */
    fun pageToYearMonth(page: Int, todayYear: Int, todayMonth: Int): Pair<Int, Int> {
        val monthOffset = page - INITIAL_PAGE
        val cal = Calendar.getInstance().apply {
            set(todayYear, todayMonth, 1)
            add(Calendar.MONTH, monthOffset)
        }
        return Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    /**
     * Convert a target year and month to page index.
     *
     * @param targetYear The target year
     * @param targetMonth The target month (Calendar.JANUARY = 0)
     * @param todayYear The year at app launch
     * @param todayMonth The month at app launch (Calendar.JANUARY = 0)
     * @return Page index for the target month
     */
    fun yearMonthToPage(targetYear: Int, targetMonth: Int, todayYear: Int, todayMonth: Int): Int {
        val monthsDiff = (targetYear - todayYear) * 12 + (targetMonth - todayMonth)
        return INITIAL_PAGE + monthsDiff
    }
}
