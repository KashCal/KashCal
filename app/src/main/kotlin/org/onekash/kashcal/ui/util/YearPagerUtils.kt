package org.onekash.kashcal.ui.util

/**
 * Holds the Year view pager's page range and page-to-year conversions. Page [CENTER_PAGE] is
 * the reference year, today's; the pager shows exactly the [CalendarSpan]'s years.
 */
object YearPagerUtils {
    /** Page index of the reference year. */
    const val CENTER_PAGE = CalendarSpan.YEAR_CENTER_PAGE

    /** Total page count, one page per year of the span. */
    const val TOTAL_PAGES = CalendarSpan.YEAR_TOTAL_PAGES

    /** Returns the year shown on [page]. */
    fun pageToYear(page: Int, todayYear: Int): Int = todayYear + (page - CENTER_PAGE)

    /** Returns the page showing [year]. */
    fun yearToPage(year: Int, todayYear: Int): Int = CENTER_PAGE + (year - todayYear)
}
