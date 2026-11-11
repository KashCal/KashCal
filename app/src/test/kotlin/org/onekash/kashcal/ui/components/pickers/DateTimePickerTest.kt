package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the date picker helpers [isMidnightCrossing], [dayCellStyle], [rangePart]
 * and [isOnWheelGrid], and the members of the enums [DateSelectionMode] and
 * [ActiveDateTimeSheet].
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
        // 12:00 AM to 6:00 AM is early morning, not a crossing.
        assertFalse(isMidnightCrossing(0, 0, 6, 0))
    }

    @Test
    fun `isMidnightCrossing returns true when end time equals start time with minutes`() {
        // 10:30 to 10:00: the end is earlier in minutes.
        assertTrue(isMidnightCrossing(10, 30, 10, 0))
    }

    @Test
    fun `isMidnightCrossing returns false when times are equal`() {
        // Same time is not a crossing
        assertFalse(isMidnightCrossing(10, 0, 10, 0))
    }

    @Test
    fun `isMidnightCrossing with minutes at boundary`() {
        // 23:59 to 00:01 crosses midnight.
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
        // Selecting another date must not strip today's marker.
        assertEquals(DayCellStyle.TODAY, dayCellStyle(isToday = true, isSelected = false))
    }

    @Test
    fun `dayCellStyle is SELECTED for a selected day that is not today`() {
        assertEquals(DayCellStyle.SELECTED, dayCellStyle(isToday = false, isSelected = true))
    }

    @Test
    fun `dayCellStyle selected fill wins when today is also selected`() {
        // The selected fill already marks the cell, so today's fill and ring are dropped.
        assertEquals(DayCellStyle.SELECTED, dayCellStyle(isToday = true, isSelected = true))
    }

    // ==================== rangePart Tests ====================

    private val oct27 = java.time.LocalDate.of(2026, 10, 27)
    private val oct31 = java.time.LocalDate.of(2026, 10, 31)

    @Test
    fun `rangePart names the start, the days between and the end`() {
        val range = oct27..oct31
        assertEquals(RangePart.START, rangePart(oct27, range))
        assertEquals(RangePart.MIDDLE, rangePart(oct27.plusDays(2), range))
        assertEquals(RangePart.END, rangePart(oct31, range))
    }

    @Test
    fun `rangePart is NONE outside the range and without one`() {
        assertEquals(RangePart.NONE, rangePart(oct27.minusDays(1), oct27..oct31))
        assertEquals(RangePart.NONE, rangePart(oct31.plusDays(1), oct27..oct31))
        assertEquals(RangePart.NONE, rangePart(oct27, null))
    }

    @Test
    fun `a one-day range is not shaded`() {
        assertEquals(RangePart.NONE, rangePart(oct27, oct27..oct27))
    }

    @Test
    fun `a range across a month boundary shades both months`() {
        val range = java.time.LocalDate.of(2026, 10, 30)..java.time.LocalDate.of(2026, 11, 2)
        assertEquals(RangePart.MIDDLE, rangePart(java.time.LocalDate.of(2026, 10, 31), range))
        assertEquals(RangePart.MIDDLE, rangePart(java.time.LocalDate.of(2026, 11, 1), range))
        assertEquals(RangePart.END, rangePart(java.time.LocalDate.of(2026, 11, 2), range))
    }

    // ==================== isOnWheelGrid Tests ====================
    // isOnWheelGrid gates the inline time area: an on-grid minute shows the 5-minute wheel, an
    // off-grid minute (9:47 typed in the exact-time dialog) shows tappable text, because the
    // wheel would snap the minute to a 5-minute step and overwrite the stored value.

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
        // The wheel has 12 positions (0, 5, ..., 55); every other minute must take the
        // exact-time text and dialog path.
        for (m in 0..59) {
            assertEquals("minute $m", m % 5 == 0, isOnWheelGrid(m))
        }
    }
}
