package org.onekash.kashcal.ui.util

import org.onekash.kashcal.ui.util.MonthPagerUtils.INITIAL_PAGE
import java.time.YearMonth

/**
 * Holds the month pager's page range and page-to-month conversions.
 *
 * The range is centered on today's month and reaches every month of the [CalendarSpan]. Pages
 * are composed lazily, so the range costs no memory.
 *
 * A page index is relative to the month passed as `todayYear`/`todayMonth`:
 * - Page [INITIAL_PAGE] = that month
 * - Page [INITIAL_PAGE] + 1 = the next month
 * - Page [INITIAL_PAGE] - 1 = the previous month
 */
object MonthPagerUtils {
    /** Page index of the reference month. */
    const val INITIAL_PAGE = CalendarSpan.MONTH_INITIAL_PAGE

    /** Total page count, covering the [CalendarSpan]. */
    const val TOTAL_PAGES = CalendarSpan.MONTH_TOTAL_PAGES

    /**
     * Returns the (year, month) shown on [page], month 0-based as in `Calendar.JANUARY`.
     *
     * @param todayMonth reference month, 0-based
     */
    fun pageToYearMonth(page: Int, todayYear: Int, todayMonth: Int): Pair<Int, Int> {
        val month = YearMonth.of(todayYear, todayMonth + 1).plusMonths((page - INITIAL_PAGE).toLong())
        return Pair(month.year, month.monthValue - 1)
    }

    /** Returns the page showing [targetYear]/[targetMonth]; both months are 0-based. */
    fun yearMonthToPage(targetYear: Int, targetMonth: Int, todayYear: Int, todayMonth: Int): Int {
        val monthsDiff = (targetYear - todayYear) * 12 + (targetMonth - todayMonth)
        return INITIAL_PAGE + monthsDiff
    }
}
