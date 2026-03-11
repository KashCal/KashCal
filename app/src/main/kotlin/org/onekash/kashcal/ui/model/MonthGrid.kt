package org.onekash.kashcal.ui.model

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale

/**
 * Pre-computed month grid for calendar display.
 * Always 6 rows x 7 columns (42 cells) for stable height during paging.
 *
 * Shared between CalendarGrid (full-size month view) and future year overview (mini-months).
 *
 * @property year Calendar year
 * @property month 0-indexed month (January = 0)
 * @property weeks 6 rows of 7 DayCell each
 */
@Immutable
data class MonthGrid(
    val year: Int,
    val month: Int,
    val weeks: List<List<DayCell>>,
) {
    /**
     * Position of a day cell within the month grid.
     */
    enum class DayPosition {
        /** Day belongs to this month */
        MonthDate,
        /** Padding day from previous month */
        InDate,
        /** Padding day from next month */
        OutDate,
    }

    /**
     * A single cell in the month grid.
     *
     * @property dayOfMonth Day number (1-31). For InDate/OutDate, this is the actual day from the adjacent month.
     * @property position Whether this cell is current month, previous month, or next month
     * @property isWeekend True if this cell falls on Saturday or Sunday
     * @property weekNumber Week-of-year number for this row (WeekFields-based)
     */
    @Immutable
    data class DayCell(
        val dayOfMonth: Int,
        val position: DayPosition,
        val isWeekend: Boolean,
        val weekNumber: Int,
    )

    /**
     * Get the dayCode range (YYYYMMDD) covered by this grid,
     * from the first cell (top-left, possibly InDate) to the last cell (bottom-right, possibly OutDate).
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
         * Compute a 6-row month grid (EndOfGrid style — uniform height).
         *
         * @param year Calendar year
         * @param month 0-indexed month (January = 0, December = 11)
         * @param firstDayOfWeek java.util.Calendar constant (1=Sun, 2=Mon, 7=Sat) or 0=system default
         * @return MonthGrid with 6 rows x 7 columns = 42 cells
         * @throws IllegalArgumentException if month is not in 0..11
         */
        fun compute(year: Int, month: Int, firstDayOfWeek: Int): MonthGrid {
            require(month in 0..11) { "Month must be 0-11, got $month" }

            // Grid offset: how many InDate cells before day 1
            val cal = Calendar.getInstance().apply { set(year, month, 1) }
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val gridOffset = DateTimeUtils.getFirstDayOffset(cal, firstDayOfWeek)

            // Previous month's day count (for InDate dayOfMonth values)
            val prevCal = Calendar.getInstance().apply {
                set(year, month, 1)
                add(Calendar.MONTH, -1)
            }
            val prevMonthDays = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)

            // Weekend detection by column position
            val orderedDays = DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)

            // WeekFields for week number calculation
            val resolvedFirst = DateTimeUtils.resolveFirstDayOfWeek(firstDayOfWeek)
            val dow = when (resolvedFirst) {
                Calendar.SUNDAY -> DayOfWeek.SUNDAY
                Calendar.MONDAY -> DayOfWeek.MONDAY
                Calendar.SATURDAY -> DayOfWeek.SATURDAY
                else -> DayOfWeek.MONDAY
            }
            val weekFields = WeekFields.of(dow, WeekFields.of(Locale.getDefault()).minimalDaysInFirstWeek)

            // Build 6 rows x 7 columns
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

                // Compute week number for this row using a representative date
                val weekNumber = computeRowWeekNumber(row, year, month, weekFields)
                val rowWithWeekNum = row.map { it.copy(weekNumber = weekNumber) }
                weeks.add(rowWithWeekNum)
            }

            return MonthGrid(year, month, weeks)
        }

        /**
         * Compute the week number for a row using a representative date.
         *
         * For rows containing MonthDate cells, uses the first MonthDate date.
         * For all-OutDate rows, uses the first OutDate date (next month).
         * For all-InDate rows (shouldn't happen in practice), uses the first InDate date.
         */
        private fun computeRowWeekNumber(
            row: List<DayCell>,
            year: Int,
            month: Int,
            weekFields: WeekFields,
        ): Int {
            // Find a representative cell and its actual date
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

            // All InDate (shouldn't happen, but handle gracefully)
            val inDateCell = row.first()
            val (prevYear, prevMonth1) = if (month == 0) (year - 1) to 12 else year to month
            val date = LocalDate.of(prevYear, prevMonth1, inDateCell.dayOfMonth)
            return date.get(weekFields.weekOfWeekBasedYear())
        }

        /**
         * Convert a DayCell to its dayCode (YYYYMMDD) given the grid's year and month.
         *
         * Handles InDate (previous month) and OutDate (next month) boundary crossing,
         * including year boundaries (e.g., Jan grid InDate → December of prev year).
         *
         * @param cell The day cell from the grid
         * @param gridYear The grid's year
         * @param gridMonth The grid's 0-indexed month (January = 0)
         * @return dayCode in YYYYMMDD format (e.g., 20260315)
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
                        gridYear to gridMonth // gridMonth is 0-indexed, so gridMonth == 1-indexed prev month
                    }
                    prevYear * 10000 + prevMonth1 * 100 + cell.dayOfMonth
                }
                DayPosition.OutDate -> {
                    // Next month
                    val (nextYear, nextMonth1) = if (gridMonth == 11) {
                        (gridYear + 1) to 1
                    } else {
                        gridYear to (gridMonth + 2) // gridMonth is 0-indexed, +2 gives 1-indexed next month
                    }
                    nextYear * 10000 + nextMonth1 * 100 + cell.dayOfMonth
                }
            }
        }
    }
}
