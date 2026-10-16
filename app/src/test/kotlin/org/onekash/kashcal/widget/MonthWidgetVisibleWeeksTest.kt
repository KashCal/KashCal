package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.model.MonthGrid
import java.util.Calendar

/**
 * Tests [visibleWeeks]: the month widget renders only the weeks a month spans, dropping trailing
 * rows that are all next-month padding. [MonthGrid.compute] always returns 6 rows, fixed for the
 * full-size month view's paging, so the trimming is widget-only.
 */
class MonthWidgetVisibleWeeksTest {

    @Test
    fun `fixed counts render exactly the requested rows and include today`() {
        for (firstDay in listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.SATURDAY)) {
            for (month in 1..12) {
                val grid = MonthGrid.compute(2026, month - 1, firstDay)
                for (day in 1..java.time.YearMonth.of(2026, month).lengthOfMonth()) {
                    val today = java.time.LocalDate.of(2026, month, day)
                    for (count in 1..6) {
                        val rows = visibleWeeks(grid, count, today)
                        val expectedCount = minOf(count, visibleWeeks(grid).size)
                        assertEquals(expectedCount, rows.size)
                        assertTrue(rows.flatten().any {
                            it.position == MonthGrid.DayPosition.MonthDate && it.dayOfMonth == day
                        })
                        assertEquals(grid.weeks.indexOf(rows.first()) + expectedCount - 1, grid.weeks.indexOf(rows.last()))
                    }
                }
            }
        }
    }

    @Test
    fun `other months start at the first row and stop at the month boundary`() {
        val grid = MonthGrid.compute(2026, 1, Calendar.SUNDAY)
        val today = java.time.LocalDate.of(2026, 10, 16)
        for (count in 1..6) {
            assertEquals(visibleWeeks(grid).take(count), visibleWeeks(grid, count, today))
        }
    }

    @Test
    fun `invalid counts fall back to automatic`() {
        val grid = MonthGrid.compute(2026, 1, Calendar.SUNDAY)
        assertEquals(visibleWeeks(grid), visibleWeeks(grid, -1))
        assertEquals(visibleWeeks(grid), visibleWeeks(grid, 7))
    }

    @Test
    fun `keeps all 6 weeks for a month that spans six`() {
        // May 2026 spans 6 weeks.
        val grid = MonthGrid.compute(2026, 4, Calendar.SUNDAY)
        assertEquals(6, visibleWeeks(grid).size)
    }

    @Test
    fun `trims to 5 weeks for a typical month`() {
        // January 2026 spans 5 weeks; the 6th grid row is all next-month padding.
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        assertEquals(5, visibleWeeks(grid).size)
    }

    @Test
    fun `trims to 4 weeks for a short month`() {
        // February 2026 (28 days, starts on the first weekday column) spans exactly 4 weeks.
        val grid = MonthGrid.compute(2026, 1, Calendar.SUNDAY)
        assertEquals(4, visibleWeeks(grid).size)
    }

    @Test
    fun `never drops a row that contains a day of this month`() {
        for (month in 0..11) {
            val grid = MonthGrid.compute(2026, month, Calendar.SUNDAY)
            val visible = visibleWeeks(grid)
            val monthDatesShown = visible.sumOf { row ->
                row.count { it.position == MonthGrid.DayPosition.MonthDate }
            }
            val monthDatesTotal = grid.weeks.sumOf { row ->
                row.count { it.position == MonthGrid.DayPosition.MonthDate }
            }
            assertEquals("month $month lost days", monthDatesTotal, monthDatesShown)
            // No trailing all-OutDate row remains.
            val last = visible.last()
            assertTrue(
                "month $month kept an all-padding trailing row",
                last.any { it.position != MonthGrid.DayPosition.OutDate },
            )
        }
    }
}
