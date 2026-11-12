package org.onekash.kashcal.ui.util

import java.time.LocalDate
import java.time.YearMonth

/**
 * The dates the date pickers offer and the views navigate to: whole calendar years, from
 * 1 January [SPAN_YEARS] years before today's year to 31 December [SPAN_YEARS] years after it.
 *
 * The month, day-list, Week and Planner pagers are sized from [SPAN_YEARS] to reach both ends
 * from any day of the year, with spare pages past them; the Year pager holds exactly the span's
 * years; Day and 3-day page without a limit. Go to date, `selectDate`,
 * `navigateToDate` and `navigateToMonth` go through [clamp], so a date outside the span lands on
 * its nearest end.
 */
object CalendarSpan {
    /** Years shown before and after today's year. */
    const val SPAN_YEARS = 150

    /**
     * Month pager: two spare years each way, so it covers any month of the span from today's
     * month and every month the day list below it reaches.
     */
    const val MONTH_INITIAL_PAGE = (SPAN_YEARS + 2) * 12
    const val MONTH_TOTAL_PAGES = 2 * MONTH_INITIAL_PAGE + 1

    /** Day list pager: one spare year each way, at 366 days a year. */
    const val DAY_INITIAL_PAGE = (SPAN_YEARS + 1) * 366
    const val DAY_TOTAL_PAGES = 2 * DAY_INITIAL_PAGE + 1

    /** Year pager: exactly the span's years. */
    const val YEAR_CENTER_PAGE = SPAN_YEARS
    const val YEAR_TOTAL_PAGES = 2 * YEAR_CENTER_PAGE + 1

    /**
     * Week and Planner pagers: two spare years each way at 53 weeks a year, which also covers a
     * Planner anchored on a week up to a year before today.
     */
    const val WEEK_CENTER_PAGE = (SPAN_YEARS + 2) * 53
    const val WEEK_TOTAL_PAGES = 2 * WEEK_CENTER_PAGE + 1

    /** Returns the span's first day, 1 January of [today]'s year minus [SPAN_YEARS]. */
    fun firstDay(today: LocalDate = LocalDate.now()): LocalDate = LocalDate.of(today.year - SPAN_YEARS, 1, 1)

    /** Returns the span's last day, 31 December of [today]'s year plus [SPAN_YEARS]. */
    fun lastDay(today: LocalDate = LocalDate.now()): LocalDate = LocalDate.of(today.year + SPAN_YEARS, 12, 31)

    /** Returns the span's first month. */
    fun firstMonth(today: LocalDate = LocalDate.now()): YearMonth = YearMonth.from(firstDay(today))

    /** Returns the span's last month. */
    fun lastMonth(today: LocalDate = LocalDate.now()): YearMonth = YearMonth.from(lastDay(today))

    /** Returns the span's years, for the year wheels. */
    fun yearRange(today: LocalDate = LocalDate.now()): IntRange = firstDay(today).year..lastDay(today).year

    /**
     * Returns whether a date picker showing [shown] may step [step] months (-1 or 1): to a month
     * inside the span, or, from a month outside it (an existing event's date), toward it.
     */
    fun canStepMonth(shown: YearMonth, step: Int, today: LocalDate = LocalDate.now()): Boolean {
        val first = firstMonth(today)
        val last = lastMonth(today)
        val target = shown.plusMonths(step.toLong())
        return when {
            target in first..last -> true
            shown < first -> step > 0
            shown > last -> step < 0
            else -> false
        }
    }

    /**
     * Returns whether a view showing [shown] may step [step] days (-1 or 1): to a day inside the
     * span, or, from a day outside it, toward it.
     */
    fun canStepDay(shown: LocalDate, step: Int, today: LocalDate = LocalDate.now()): Boolean {
        val first = firstDay(today)
        val last = lastDay(today)
        val target = shown.plusDays(step.toLong())
        return when {
            target in first..last -> true
            shown < first -> step > 0
            shown > last -> step < 0
            else -> false
        }
    }

    /** Returns [date], or the span's nearest end when it falls outside. */
    fun clamp(date: LocalDate, today: LocalDate = LocalDate.now()): LocalDate =
        date.coerceIn(firstDay(today), lastDay(today))

    /** Returns [month], or the span's nearest end month when it falls outside. */
    fun clamp(month: YearMonth, today: LocalDate = LocalDate.now()): YearMonth =
        month.coerceIn(firstMonth(today), lastMonth(today))
}
