package org.onekash.kashcal.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale

class MonthGridTest {

    // ==================== Structure Tests ====================

    @Test
    fun `compute returns 6 rows of 7 columns`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        assertEquals(6, grid.weeks.size)
        grid.weeks.forEach { row ->
            assertEquals(7, row.size)
        }
    }

    // ==================== Offset Tests ====================

    @Test
    fun `Jan 2026 Sunday-first -- day 1 at offset 4`() {
        // Jan 1, 2026 is Thursday. Sunday-first offset = 4
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        // First 4 cells are InDate
        for (i in 0..3) {
            assertEquals(MonthGrid.DayPosition.InDate, grid.weeks[0][i].position)
        }
        // Cell at index 4 is day 1
        assertEquals(MonthGrid.DayPosition.MonthDate, grid.weeks[0][4].position)
        assertEquals(1, grid.weeks[0][4].dayOfMonth)
    }

    @Test
    fun `Jan 2026 Monday-first -- day 1 at offset 3`() {
        // Jan 1, 2026 is Thursday. Monday-first offset = 3
        val grid = MonthGrid.compute(2026, 0, Calendar.MONDAY)
        for (i in 0..2) {
            assertEquals(MonthGrid.DayPosition.InDate, grid.weeks[0][i].position)
        }
        assertEquals(MonthGrid.DayPosition.MonthDate, grid.weeks[0][3].position)
        assertEquals(1, grid.weeks[0][3].dayOfMonth)
    }

    @Test
    fun `Jan 2026 Saturday-first -- day 1 at offset 5`() {
        // Jan 1, 2026 is Thursday. Saturday-first offset = 5
        val grid = MonthGrid.compute(2026, 0, Calendar.SATURDAY)
        for (i in 0..4) {
            assertEquals(MonthGrid.DayPosition.InDate, grid.weeks[0][i].position)
        }
        assertEquals(MonthGrid.DayPosition.MonthDate, grid.weeks[0][5].position)
        assertEquals(1, grid.weeks[0][5].dayOfMonth)
    }

    // ==================== Month Length Tests ====================

    @Test
    fun `Feb 2026 -- 28 days, trailing OutDate cells`() {
        val grid = MonthGrid.compute(2026, 1, Calendar.SUNDAY)
        val monthDates = grid.weeks.flatten().filter { it.position == MonthGrid.DayPosition.MonthDate }
        assertEquals(28, monthDates.size)
        val outDates = grid.weeks.flatten().filter { it.position == MonthGrid.DayPosition.OutDate }
        assertTrue("Should have OutDate cells for padding", outDates.isNotEmpty())
    }

    @Test
    fun `Feb 2028 -- leap year has 29 days`() {
        val grid = MonthGrid.compute(2028, 1, Calendar.SUNDAY)
        val monthDates = grid.weeks.flatten().filter { it.position == MonthGrid.DayPosition.MonthDate }
        assertEquals(29, monthDates.size)
    }

    // ==================== Sequential Day Tests ====================

    @Test
    fun `MonthDate cells are sequential 1 to daysInMonth`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY) // January = 31 days
        val monthDays = grid.weeks.flatten()
            .filter { it.position == MonthGrid.DayPosition.MonthDate }
            .map { it.dayOfMonth }
        assertEquals((1..31).toList(), monthDays)
    }

    @Test
    fun `InDate cells have prev month day numbers`() {
        // Jan 2026 Sunday-first: offset=4, so InDate cells are Dec 28,29,30,31
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        val inDates = grid.weeks.flatten()
            .filter { it.position == MonthGrid.DayPosition.InDate }
            .map { it.dayOfMonth }
        assertEquals(listOf(28, 29, 30, 31), inDates)
    }

    @Test
    fun `OutDate cells have next month day numbers`() {
        // Jan 2026 has 31 days, offset=4 (Sunday-first).
        // 4 InDate + 31 MonthDate = 35 cells. 42 - 35 = 7 OutDate cells.
        // OutDate should be Feb 1,2,3,4,5,6,7
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        val outDates = grid.weeks.flatten()
            .filter { it.position == MonthGrid.DayPosition.OutDate }
            .map { it.dayOfMonth }
        assertEquals((1..7).toList(), outDates)
    }

    // ==================== Weekend Detection Tests ====================

    @Test
    fun `weekend detection -- Sunday first`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        // Sunday-first: col 0 = Sunday (weekend), col 6 = Saturday (weekend)
        for (row in grid.weeks) {
            assertTrue("Col 0 should be weekend (Sunday)", row[0].isWeekend)
            assertFalse("Col 1 should not be weekend", row[1].isWeekend)
            assertFalse("Col 5 should not be weekend", row[5].isWeekend)
            assertTrue("Col 6 should be weekend (Saturday)", row[6].isWeekend)
        }
    }

    @Test
    fun `weekend detection -- Monday first`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.MONDAY)
        // Monday-first: col 5 = Saturday (weekend), col 6 = Sunday (weekend)
        for (row in grid.weeks) {
            assertFalse("Col 0 should not be weekend (Monday)", row[0].isWeekend)
            assertTrue("Col 5 should be weekend (Saturday)", row[5].isWeekend)
            assertTrue("Col 6 should be weekend (Sunday)", row[6].isWeekend)
        }
    }

    @Test
    fun `weekend detection -- Saturday first`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.SATURDAY)
        // Saturday-first: col 0 = Saturday (weekend), col 1 = Sunday (weekend)
        for (row in grid.weeks) {
            assertTrue("Col 0 should be weekend (Saturday)", row[0].isWeekend)
            assertTrue("Col 1 should be weekend (Sunday)", row[1].isWeekend)
            assertFalse("Col 2 should not be weekend (Monday)", row[2].isWeekend)
        }
    }

    // ==================== Week Number Tests ====================

    @Test
    fun `week numbers -- Jan 2026`() {
        // Jan 2026, Sunday-first. Verify week numbers match WeekFields computation.
        val grid = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        val weekFields = WeekFields.of(
            java.time.DayOfWeek.SUNDAY,
            WeekFields.of(Locale.getDefault()).minimalDaysInFirstWeek
        )

        // Row 0: first MonthDate is Jan 1
        val jan1WeekNum = LocalDate.of(2026, 1, 1).get(weekFields.weekOfWeekBasedYear())
        assertEquals(jan1WeekNum, grid.weeks[0][4].weekNumber) // Jan 1 at offset 4
    }

    @Test
    fun `all-OutDate rows have correct weekNumber from next-month dates`() {
        // Feb 2015 Sunday-first: Feb 1 is Sunday (offset=0), 28 days = exactly 4 rows.
        // Rows 4 and 5 are all OutDate (March dates).
        val grid = MonthGrid.compute(2015, 1, Calendar.SUNDAY)
        val weekFields = WeekFields.of(
            java.time.DayOfWeek.SUNDAY,
            WeekFields.of(Locale.getDefault()).minimalDaysInFirstWeek
        )

        // Row 4: all OutDate, starts with Mar 1
        assertTrue(grid.weeks[4].all { it.position == MonthGrid.DayPosition.OutDate })
        val mar1WeekNum = LocalDate.of(2015, 3, 1).get(weekFields.weekOfWeekBasedYear())
        assertEquals(mar1WeekNum, grid.weeks[4][0].weekNumber)

        // Row 5: all OutDate, starts with Mar 8
        assertTrue(grid.weeks[5].all { it.position == MonthGrid.DayPosition.OutDate })
        val mar8WeekNum = LocalDate.of(2015, 3, 8).get(weekFields.weekOfWeekBasedYear())
        assertEquals(mar8WeekNum, grid.weeks[5][0].weekNumber)
    }

    // ==================== 0-Indexed Month Convention ====================

    @Test
    fun `month 0 is January, month 11 is December`() {
        val jan = MonthGrid.compute(2026, 0, Calendar.SUNDAY)
        assertEquals(0, jan.month)
        val janMonthDates = jan.weeks.flatten().filter { it.position == MonthGrid.DayPosition.MonthDate }
        assertEquals(31, janMonthDates.size) // January has 31 days

        val dec = MonthGrid.compute(2026, 11, Calendar.SUNDAY)
        assertEquals(11, dec.month)
        val decMonthDates = dec.weeks.flatten().filter { it.position == MonthGrid.DayPosition.MonthDate }
        assertEquals(31, decMonthDates.size) // December has 31 days
    }

    // ==================== Extreme Padding Case ====================

    @Test
    fun `Feb 2015 -- 4 content rows still produces 6`() {
        // Feb 2015: Feb 1 is Sunday, Sunday-first offset=0, 28 days = 4 rows of MonthDate.
        // Should still produce 6 rows total (2 all-OutDate rows).
        val grid = MonthGrid.compute(2015, 1, Calendar.SUNDAY)
        assertEquals(6, grid.weeks.size)

        // Verify rows 0-3 contain MonthDate cells
        for (row in 0..3) {
            assertTrue(
                "Row $row should have MonthDate cells",
                grid.weeks[row].any { it.position == MonthGrid.DayPosition.MonthDate }
            )
        }
        // Verify rows 4-5 are all OutDate
        for (row in 4..5) {
            assertTrue(
                "Row $row should be all OutDate",
                grid.weeks[row].all { it.position == MonthGrid.DayPosition.OutDate }
            )
        }
    }

    // ==================== Bulk Invariant Tests ====================

    @Test
    fun `every month 2020-2030 has correct MonthDate count`() {
        for (year in 2020..2030) {
            for (month in 0..11) {
                for (firstDay in listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.SATURDAY)) {
                    val grid = MonthGrid.compute(year, month, firstDay)
                    val cal = Calendar.getInstance().apply { set(year, month, 1) }
                    val expectedDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val monthDates = grid.weeks.flatten()
                        .filter { it.position == MonthGrid.DayPosition.MonthDate }
                    assertEquals(
                        "Year $year month $month firstDay $firstDay: expected $expectedDays MonthDate cells",
                        expectedDays, monthDates.size
                    )
                }
            }
        }
    }

    @Test
    fun `InDate then MonthDate then OutDate -- no interleaving`() {
        for (year in 2020..2030) {
            for (month in 0..11) {
                val grid = MonthGrid.compute(year, month, Calendar.SUNDAY)
                val flat = grid.weeks.flatten()
                val positions = flat.map { it.position }

                // Find first MonthDate and last MonthDate
                val firstMonth = positions.indexOfFirst { it == MonthGrid.DayPosition.MonthDate }
                val lastMonth = positions.indexOfLast { it == MonthGrid.DayPosition.MonthDate }

                // All before firstMonth should be InDate
                for (i in 0 until firstMonth) {
                    assertEquals(
                        "Year $year month $month: cell $i before first MonthDate should be InDate",
                        MonthGrid.DayPosition.InDate, positions[i]
                    )
                }
                // All between firstMonth and lastMonth should be MonthDate
                for (i in firstMonth..lastMonth) {
                    assertEquals(
                        "Year $year month $month: cell $i between first/last MonthDate should be MonthDate",
                        MonthGrid.DayPosition.MonthDate, positions[i]
                    )
                }
                // All after lastMonth should be OutDate
                for (i in (lastMonth + 1) until 42) {
                    assertEquals(
                        "Year $year month $month: cell $i after last MonthDate should be OutDate",
                        MonthGrid.DayPosition.OutDate, positions[i]
                    )
                }
            }
        }
    }

    @Test
    fun `first MonthDate at index equals getFirstDayOffset`() {
        for (year in 2020..2030) {
            for (month in 0..11) {
                for (firstDay in listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.SATURDAY)) {
                    val grid = MonthGrid.compute(year, month, firstDay)
                    val flat = grid.weeks.flatten()
                    val firstMonthIndex = flat.indexOfFirst { it.position == MonthGrid.DayPosition.MonthDate }

                    val cal = Calendar.getInstance().apply { set(year, month, 1) }
                    val expectedOffset = DateTimeUtils.getFirstDayOffset(cal, firstDay)

                    assertEquals(
                        "Year $year month $month firstDay $firstDay",
                        expectedOffset, firstMonthIndex
                    )
                }
            }
        }
    }

    // ==================== System Default Tests ====================

    @Test
    fun `firstDayOfWeek = 0 resolves system default`() {
        val gridSystem = MonthGrid.compute(2026, 0, 0)
        val resolvedFirst = DateTimeUtils.resolveFirstDayOfWeek(0)
        val gridExplicit = MonthGrid.compute(2026, 0, resolvedFirst)
        assertEquals(gridExplicit, gridSystem)
    }

    // ==================== Input Validation Tests ====================

    @Test(expected = IllegalArgumentException::class)
    fun `invalid month throws IllegalArgumentException -- negative`() {
        MonthGrid.compute(2026, -1, Calendar.SUNDAY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid month throws IllegalArgumentException -- 12`() {
        MonthGrid.compute(2026, 12, Calendar.SUNDAY)
    }

    // ==================== Year Boundary Tests ====================

    @Test
    fun `Dec 2025 OutDate cells are Jan 2026 days`() {
        val grid = MonthGrid.compute(2025, 11, Calendar.SUNDAY)
        val outDates = grid.weeks.flatten()
            .filter { it.position == MonthGrid.DayPosition.OutDate }
            .map { it.dayOfMonth }
        // OutDate cells should start from 1 (Jan 2026)
        assertTrue("OutDate should start from 1", outDates.first() == 1)
        // Should be sequential
        assertEquals(outDates, (1..outDates.size).toList())
    }
}
