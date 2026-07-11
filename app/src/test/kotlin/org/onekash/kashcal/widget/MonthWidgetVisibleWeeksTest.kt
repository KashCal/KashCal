package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.model.MonthGrid
import java.util.Calendar

/**
 * The month widget renders only the weeks a month actually spans, dropping trailing rows that are
 * entirely next-month padding. [MonthGrid.compute] always returns a fixed 6-row grid (needed for
 * stable paging in the full-size month view), so the trimming is widget-render-only.
 */
class MonthWidgetVisibleWeeksTest {

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
            // A trailing all-OutDate row must never remain.
            val last = visible.last()
            assertTrue(
                "month $month kept an all-padding trailing row",
                last.any { it.position != MonthGrid.DayPosition.OutDate },
            )
        }
    }
}
