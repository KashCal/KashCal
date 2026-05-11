package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
