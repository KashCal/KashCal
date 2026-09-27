package org.onekash.kashcal.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class MonthGridDayCodeTest {

    // ==================== computeDayCodeForCell ====================

    @Test
    fun `computeDayCodeForCell correct for MonthDate`() {
        // March 2026 (month 2, 0-indexed), day 15 -> 20260315
        val cell = MonthGrid.DayCell(
            dayOfMonth = 15,
            position = MonthGrid.DayPosition.MonthDate,
            isWeekend = false,
            weekNumber = 11
        )
        assertEquals(20260315, MonthGrid.computeDayCodeForCell(cell, 2026, 2)) // 2 = March
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
        // December 2026 grid (month 11), OutDate day 1 -> January 1, 2027 = 20270101
        val cell = MonthGrid.DayCell(
            dayOfMonth = 1,
            position = MonthGrid.DayPosition.OutDate,
            isWeekend = false,
            weekNumber = 1
        )
        assertEquals(20270101, MonthGrid.computeDayCodeForCell(cell, 2026, 11)) // December
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
        // March 2026 with a Sunday start: March 1 is a Sunday, so despite the test name there
        // is no InDate, and the last row is all OutDate.
        val grid = MonthGrid.compute(2026, 2, Calendar.SUNDAY)
        val (start, end) = grid.toDayCodeRange()

        // Start is the first cell's day.
        val firstCell = grid.weeks.first().first()
        val expectedStart = MonthGrid.computeDayCodeForCell(firstCell, 2026, 2)
        assertEquals(expectedStart, start)

        // End is the last cell's day.
        val lastCell = grid.weeks.last().last()
        val expectedEnd = MonthGrid.computeDayCodeForCell(lastCell, 2026, 2)
        assertEquals(expectedEnd, end)

        assert(start <= end) { "Start ($start) should be <= end ($end)" }
    }

    @Test
    fun `toDayCodeRange covers InDate from previous month`() {
        // April 1, 2026 is a Wednesday, so with a Sunday start the first 3 cells (Sun, Mon,
        // Tue) are March InDates.
        val grid = MonthGrid.compute(2026, 3, Calendar.SUNDAY) // April
        val (start, _) = grid.toDayCodeRange()

        // The first cell is a March InDate.
        val firstCell = grid.weeks.first().first()
        assertEquals(MonthGrid.DayPosition.InDate, firstCell.position)
        // Start is a March date.
        assert(start < 20260401) { "Start ($start) should be before April 1" }
    }
}
