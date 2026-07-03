package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar as JavaCalendar

/**
 * Unit tests for DateTimePicker component helper functions.
 */
class DateTimePickerTest {

    // ==================== isMidnightCrossing Tests ====================

    @Test
    fun `isMidnightCrossing returns false for normal same-day range`() {
        // 10:00 AM to 2:00 PM
        assertFalse(isMidnightCrossing(10, 0, 14, 0))
    }

    @Test
    fun `isMidnightCrossing returns true when end is before start`() {
        // 10:00 PM to 2:00 AM (next day)
        assertTrue(isMidnightCrossing(22, 0, 2, 0))
    }

    @Test
    fun `isMidnightCrossing returns true for 11 PM to 1 AM`() {
        assertTrue(isMidnightCrossing(23, 0, 1, 0))
    }

    @Test
    fun `isMidnightCrossing returns false for midnight start to later time`() {
        // 12:00 AM to 6:00 AM - not a crossing, just early morning
        assertFalse(isMidnightCrossing(0, 0, 6, 0))
    }

    @Test
    fun `isMidnightCrossing returns true when end time equals start time with minutes`() {
        // 10:30 to 10:00 - end is "before" start in minutes
        assertTrue(isMidnightCrossing(10, 30, 10, 0))
    }

    @Test
    fun `isMidnightCrossing returns false when times are equal`() {
        // Same time is not a crossing
        assertFalse(isMidnightCrossing(10, 0, 10, 0))
    }

    @Test
    fun `isMidnightCrossing with minutes at boundary`() {
        // 23:59 to 00:01 - crosses midnight
        assertTrue(isMidnightCrossing(23, 59, 0, 1))
    }

    // ==================== DateSelectionMode Tests ====================

    @Test
    fun `DateSelectionMode has START and END values`() {
        assertEquals(2, DateSelectionMode.values().size)
        assertTrue(DateSelectionMode.values().contains(DateSelectionMode.START))
        assertTrue(DateSelectionMode.values().contains(DateSelectionMode.END))
    }

    // ==================== ActiveDateTimeSheet Tests ====================

    @Test
    fun `ActiveDateTimeSheet has all expected values`() {
        assertEquals(3, ActiveDateTimeSheet.values().size)
        assertTrue(ActiveDateTimeSheet.values().contains(ActiveDateTimeSheet.NONE))
        assertTrue(ActiveDateTimeSheet.values().contains(ActiveDateTimeSheet.START))
        assertTrue(ActiveDateTimeSheet.values().contains(ActiveDateTimeSheet.END))
    }

    // ==================== dayCellStyle Tests ====================

    @Test
    fun `dayCellStyle is PLAIN for an ordinary day`() {
        assertEquals(DayCellStyle.PLAIN, dayCellStyle(isToday = false, isSelected = false))
    }

    @Test
    fun `dayCellStyle is TODAY when today is not the selected day`() {
        // The reported gap: selecting another date must NOT strip today's marker.
        assertEquals(DayCellStyle.TODAY, dayCellStyle(isToday = true, isSelected = false))
    }

    @Test
    fun `dayCellStyle is SELECTED for a selected day that is not today`() {
        assertEquals(DayCellStyle.SELECTED, dayCellStyle(isToday = false, isSelected = true))
    }

    @Test
    fun `dayCellStyle selected fill wins when today is also selected`() {
        // Filled selection makes the cell unmistakable, so the today ring is dropped.
        assertEquals(DayCellStyle.SELECTED, dayCellStyle(isToday = true, isSelected = true))
    }

    // ==================== isOnWheelGrid Tests ====================
    // isOnWheelGrid is the gate for the inline time area: an on-grid minute shows
    // the 5-minute wheel, an off-grid minute (e.g. 9:47 typed via the exact-time
    // dialog) shows tappable text instead, because mounting the wheel would snap
    // the minute to the nearest 5-minute step and clobber the stored value.

    @Test
    fun `isOnWheelGrid is true for every multiple of five`() {
        for (m in 0..55 step 5) {
            assertTrue("minute $m should be on-grid", isOnWheelGrid(m))
        }
    }

    @Test
    fun `isOnWheelGrid is false for off-grid minutes`() {
        assertFalse(isOnWheelGrid(1))
        assertFalse(isOnWheelGrid(47))
        assertFalse(isOnWheelGrid(59))
    }

    @Test
    fun `isOnWheelGrid rejects every non-multiple-of-five across the hour`() {
        // The wheel can render exactly 12 positions (0,5,...,55); everything else
        // must route to the exact-time text/dialog path.
        for (m in 0..59) {
            assertEquals("minute $m", m % 5 == 0, isOnWheelGrid(m))
        }
    }

    // ==================== isSameCalendarDay Tests ====================

    private fun dayMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        JavaCalendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(JavaCalendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `isSameCalendarDay is true for two instants on the same day`() {
        val a = JavaCalendar.getInstance().apply { timeInMillis = dayMillis(2026, JavaCalendar.JUNE, 26, 0, 1) }
        val b = JavaCalendar.getInstance().apply { timeInMillis = dayMillis(2026, JavaCalendar.JUNE, 26, 23, 59) }
        assertTrue(isSameCalendarDay(a, b))
    }

    @Test
    fun `isSameCalendarDay is false across midnight`() {
        // Today sampled at 11:59 PM must NOT match tomorrow's cell.
        val lateToday = JavaCalendar.getInstance().apply { timeInMillis = dayMillis(2026, JavaCalendar.JUNE, 26, 23, 59) }
        val tomorrow = JavaCalendar.getInstance().apply { timeInMillis = dayMillis(2026, JavaCalendar.JUNE, 27, 0, 0) }
        assertFalse(isSameCalendarDay(lateToday, tomorrow))
    }

    @Test
    fun `isSameCalendarDay is false for same day-of-year in different years`() {
        val a = JavaCalendar.getInstance().apply { timeInMillis = dayMillis(2026, JavaCalendar.MARCH, 1) }
        val b = JavaCalendar.getInstance().apply { timeInMillis = dayMillis(2025, JavaCalendar.MARCH, 1) }
        assertFalse(isSameCalendarDay(a, b))
    }
}
