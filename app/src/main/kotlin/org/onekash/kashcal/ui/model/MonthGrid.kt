package org.onekash.kashcal.ui.model

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Calendar

/**
 * Holds a month's day cells, always 6 rows of 7 (42 cells) so the height stays stable while
 * paging.
 *
 * Built for the month view, the year view's mini-months and the month widget.
 *
 * @property month 0-indexed month (January = 0)
 * @property weeks 6 rows of 7 [DayCell]
 */
@Immutable
data class MonthGrid(
    val year: Int,
    val month: Int,
    val weeks: List<List<DayCell>>,
) {
    /** Tells whether a cell belongs to this month or pads it from a neighbouring one. */
    enum class DayPosition {
        /** A day of this month. */
        MonthDate,
        /** A padding day from the previous month. */
        InDate,
        /** A padding day from the next month. */
        OutDate,
    }

    /**
     * Holds one cell of the grid.
     *
     * @property dayOfMonth the day number (1-31); for [DayPosition.InDate] and
     *   [DayPosition.OutDate], the day in the adjacent month
     * @property isWeekend true on Saturday or Sunday
     * @property weekNumber the row's week of the week-based year, from
     *   [DateTimeUtils.getLocaleWeekFields]
     */
    @Immutable
    data class DayCell(
        val dayOfMonth: Int,
        val position: DayPosition,
        val isWeekend: Boolean,
        val weekNumber: Int,
    )

    /**
     * Returns the dayCode (YYYYMMDD) range of the grid, from the top-left cell to the bottom-right
     * one, padding days included.
     */
    fun toDayCodeRange(): Pair<Int, Int> {
        val firstCell = weeks.first().first()
        val lastCell = weeks.last().last()
        val startCode = computeDayCodeForCell(firstCell, year, month)
        val endCode = computeDayCodeForCell(lastCell, year, month)
        return startCode to endCode
    }

    companion object {
        /**
         * Builds the 6-row grid, padding the end with next-month days so every month has the
         * same height.
         *
         * @param month 0-indexed month (January = 0, December = 11)
         * @param firstDayOfWeek a java.util.Calendar constant (1=Sun, 2=Mon, 7=Sat) or 0 for the
         *   locale default
         * @throws IllegalArgumentException if month is not in 0..11
         */
        fun compute(year: Int, month: Int, firstDayOfWeek: Int): MonthGrid {
            require(month in 0..11) { "Month must be 0-11, got $month" }

            // How many InDate cells come before day 1
            val cal = Calendar.getInstance().apply { set(year, month, 1) }
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val gridOffset = DateTimeUtils.getFirstDayOffset(cal, firstDayOfWeek)

            // The previous month's length, for InDate dayOfMonth values
            val prevCal = Calendar.getInstance().apply {
                set(year, month, 1)
                add(Calendar.MONTH, -1)
            }
            val prevMonthDays = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)

            // Weekend detection by column
            val orderedDays = DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)

            val weekFields = DateTimeUtils.getLocaleWeekFields(firstDayOfWeek)

            val weeks = mutableListOf<List<DayCell>>()
            var dayCounter = 1
            var nextMonthDay = 1

            for (week in 0..5) {
                val row = mutableListOf<DayCell>()
                for (col in 0..6) {
                    val cellIndex = week * 7 + col
                    val isWeekend = orderedDays[col] == DayOfWeek.SATURDAY ||
                        orderedDays[col] == DayOfWeek.SUNDAY

                    val cell = when {
                        // InDate: before day 1
                        cellIndex < gridOffset -> {
                            val prevDay = prevMonthDays - gridOffset + cellIndex + 1
                            DayCell(
                                dayOfMonth = prevDay,
                                position = DayPosition.InDate,
                                isWeekend = isWeekend,
                                weekNumber = 0, // placeholder, computed below
                            )
                        }
                        // MonthDate: this month's days
                        dayCounter <= daysInMonth -> {
                            val day = dayCounter++
                            DayCell(
                                dayOfMonth = day,
                                position = DayPosition.MonthDate,
                                isWeekend = isWeekend,
                                weekNumber = 0, // placeholder, computed below
                            )
                        }
                        // OutDate: after last day (pad to 6 rows)
                        else -> {
                            val outDay = nextMonthDay++
                            DayCell(
                                dayOfMonth = outDay,
                                position = DayPosition.OutDate,
                                isWeekend = isWeekend,
                                weekNumber = 0, // placeholder, computed below
                            )
                        }
                    }
                    row.add(cell)
                }

                val weekNumber = computeRowWeekNumber(row, year, month, weekFields)
                val rowWithWeekNum = row.map { it.copy(weekNumber = weekNumber) }
                weeks.add(rowWithWeekNum)
            }

            return MonthGrid(year, month, weeks)
        }

        /**
         * Returns a row's week number from a representative date.
         *
         * A row with MonthDate cells uses its first one; an all-OutDate row uses its first cell
         * (next month). An all-InDate row can't occur (day 1 is in the first row), but would use
         * its first cell.
         */
        private fun computeRowWeekNumber(
            row: List<DayCell>,
            year: Int,
            month: Int,
            weekFields: WeekFields,
        ): Int {
            val monthDateCell = row.firstOrNull { it.position == DayPosition.MonthDate }
            if (monthDateCell != null) {
                val date = LocalDate.of(year, month + 1, monthDateCell.dayOfMonth)
                return date.get(weekFields.weekOfWeekBasedYear())
            }

            val outDateCell = row.firstOrNull { it.position == DayPosition.OutDate }
            if (outDateCell != null) {
                // Next month
                val (nextYear, nextMonth1) = if (month == 11) (year + 1) to 1 else year to (month + 2)
                val date = LocalDate.of(nextYear, nextMonth1, outDateCell.dayOfMonth)
                return date.get(weekFields.weekOfWeekBasedYear())
            }

            // All InDate: can't occur, see the KDoc
            val inDateCell = row.first()
            val (prevYear, prevMonth1) = if (month == 0) (year - 1) to 12 else year to month
            val date = LocalDate.of(prevYear, prevMonth1, inDateCell.dayOfMonth)
            return date.get(weekFields.weekOfWeekBasedYear())
        }

        /**
         * Returns a cell's dayCode (YYYYMMDD, for example 20260315).
         *
         * InDate cells map to the previous month and OutDate cells to the next, across year
         * boundaries (a January grid's InDate cells are in December of the previous year).
         *
         * @param gridMonth the grid's 0-indexed month (January = 0)
         */
        fun computeDayCodeForCell(cell: DayCell, gridYear: Int, gridMonth: Int): Int {
            return when (cell.position) {
                DayPosition.MonthDate -> {
                    // 1-indexed month for dayCode
                    gridYear * 10000 + (gridMonth + 1) * 100 + cell.dayOfMonth
                }
                DayPosition.InDate -> {
                    // Previous month
                    val (prevYear, prevMonth1) = if (gridMonth == 0) {
                        (gridYear - 1) to 12
                    } else {
                        gridYear to gridMonth // 0-indexed gridMonth = 1-indexed prev
                    }
                    prevYear * 10000 + prevMonth1 * 100 + cell.dayOfMonth
                }
                DayPosition.OutDate -> {
                    // Next month
                    val (nextYear, nextMonth1) = if (gridMonth == 11) {
                        (gridYear + 1) to 1
                    } else {
                        gridYear to (gridMonth + 2) // 0-indexed, +2 = 1-indexed next
                    }
                    nextYear * 10000 + nextMonth1 * 100 + cell.dayOfMonth
                }
            }
        }
    }
}
