package org.onekash.kashcal.ui.util

import org.onekash.kashcal.ui.util.MonthPagerUtils.INITIAL_PAGE
import java.util.Calendar

/**
 * Holds the month pager's page range and page-to-month conversions.
 *
 * The range is centered on today's month and spans about 100 years in each direction. Pages are
 * composed lazily, so the range costs no memory.
 *
 * A page index is relative to the month passed as `todayYear`/`todayMonth`:
 * - Page [INITIAL_PAGE] = that month
 * - Page [INITIAL_PAGE] + 1 = the next month
 * - Page [INITIAL_PAGE] - 1 = the previous month
 */
object MonthPagerUtils {
    /** Page index of the reference month. */
    const val INITIAL_PAGE = 1200

    /** Total page count, about 100 years in each direction. */
    const val TOTAL_PAGES = 2400

    /**
     * Returns the (year, month) shown on [page], month 0-based as in `Calendar.JANUARY`.
     *
     * @param todayMonth reference month, 0-based
     */
    fun pageToYearMonth(page: Int, todayYear: Int, todayMonth: Int): Pair<Int, Int> {
        val monthOffset = page - INITIAL_PAGE
        val cal = Calendar.getInstance().apply {
            set(todayYear, todayMonth, 1)
            add(Calendar.MONTH, monthOffset)
        }
        return Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    /** Returns the page showing [targetYear]/[targetMonth]; both months are 0-based. */
    fun yearMonthToPage(targetYear: Int, targetMonth: Int, todayYear: Int, todayMonth: Int): Int {
        val monthsDiff = (targetYear - todayYear) * 12 + (targetMonth - todayMonth)
        return INITIAL_PAGE + monthsDiff
    }
}
