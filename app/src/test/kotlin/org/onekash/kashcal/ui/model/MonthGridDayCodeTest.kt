package org.onekash.kashcal.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class MonthGridDayCodeTest {

    // ==================== computeDayCodeForCell ====================

    @Test
    fun `computeDayCodeForCell correct for MonthDate`() {
        // March 2026, day 15 -> 20260315
        val cell = MonthGrid.DayCell(
            dayOfMonth = 15,
            position = MonthGrid.DayPosition.MonthDate,
            isWeekend = false,
            weekNumber = 11
        )
        assertEquals(20260315, MonthGrid.computeDayCodeForCell(cell, 2026, 2)) // month 2 = March (0-indexed)
    }

    @Test
    fun `computeDayCodeForCell handles InDate across year boundary`() {
        // January 2026 grid, InDate day 31 -> December 31, 2025 = 20251231
        val cell = MonthGrid.DayCell(
            dayOfMonth = 31,
            position = MonthGrid.DayPosition.InDate,
            isWeekend = false,
            weekNumber = 1
        )
        assertEquals(20251231, MonthGrid.computeDayCodeForCell(cell, 2026, 0)) // month 0 = January
    }

    @Test
    fun `computeDayCodeForCell handles OutDate across year boundary`() {
        // December 2026 grid, OutDate day 1 -> January 1, 2027 = 20270101
        val cell = MonthGrid.DayCell(
            dayOfMonth = 1,
            position = MonthGrid.DayPosition.OutDate,
            isWeekend = false,
            weekNumber = 1
        )
        assertEquals(20270101, MonthGrid.computeDayCodeForCell(cell, 2026, 11)) // month 11 = December
    }

    @Test
    fun `computeDayCodeForCell handles OutDate in normal month`() {
        // February 2026 grid, OutDate day 1 -> March 1, 2026 = 20260301
        val cell = MonthGrid.DayCell(
            dayOfMonth = 1,
            position = MonthGrid.DayPosition.OutDate,
            isWeekend = false,
            weekNumber = 10
        )
        assertEquals(20260301, MonthGrid.computeDayCodeForCell(cell, 2026, 1)) // month 1 = February
    }

    @Test
    fun `computeDayCodeForCell handles InDate in normal month`() {
        // March 2026 grid, InDate day 28 -> February 28, 2026 = 20260228
        val cell = MonthGrid.DayCell(
            dayOfMonth = 28,
            position = MonthGrid.DayPosition.InDate,
            isWeekend = false,
            weekNumber = 9
        )
        assertEquals(20260228, MonthGrid.computeDayCodeForCell(cell, 2026, 2)) // month 2 = March
    }

    // ==================== toDayCodeRange ====================

    @Test
    fun `toDayCodeRange returns correct bounds for month with InDate and OutDate`() {
        // March 2026, Sunday start: March 1 is Sunday, so no InDate.
        // Last row may be all-OutDate.
        val grid = MonthGrid.compute(2026, 2, Calendar.SUNDAY)
        val (start, end) = grid.toDayCodeRange()

        // First cell should be first day visible in grid
        val firstCell = grid.weeks.first().first()
        val expectedStart = MonthGrid.computeDayCodeForCell(firstCell, 2026, 2)
        assertEquals(expectedStart, start)

        // Last cell should be last day visible in grid
        val lastCell = grid.weeks.last().last()
        val expectedEnd = MonthGrid.computeDayCodeForCell(lastCell, 2026, 2)
        assertEquals(expectedEnd, end)

        // Verify start <= end
        assert(start <= end) { "Start ($start) should be <= end ($end)" }
    }

    @Test
    fun `toDayCodeRange covers InDate from previous month`() {
        // February 2026, Sunday start: Feb 1 is Sunday, so InDate days from January
        // Actually Feb 1, 2026 is a Sunday, so grid offset = 0. Use a month where offset > 0.
        // April 2026: April 1 is Wednesday. With Sunday start, offset = 3 (Sun, Mon, Tue from March).
        val grid = MonthGrid.compute(2026, 3, Calendar.SUNDAY) // April
        val (start, _) = grid.toDayCodeRange()

        // First cell should be InDate from March
        val firstCell = grid.weeks.first().first()
        assertEquals(MonthGrid.DayPosition.InDate, firstCell.position)
        // Start should be a March date
        assert(start < 20260401) { "Start ($start) should be before April 1" }
    }
}
