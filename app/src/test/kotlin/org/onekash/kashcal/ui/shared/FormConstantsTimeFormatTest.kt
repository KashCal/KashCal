package org.onekash.kashcal.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for time-format-aware reminder formatting (Issue #96).
 *
 * Verifies that formatReminderShort() and formatReminderOption() respect
 * the use24Hour parameter for the 540-minute (9 AM) all-day reminder option.
 */
class FormConstantsTimeFormatTest {

    // ==================== formatReminderShort tests ====================

    @Test
    fun `formatReminderShort 540 with use24Hour true returns 09 colon 00`() {
        assertEquals("09:00", formatReminderShort(540, use24Hour = true))
    }

    @Test
    fun `formatReminderShort 540 with use24Hour false returns 9AM`() {
        assertEquals("9AM", formatReminderShort(540, use24Hour = false))
    }

    @Test
    fun `formatReminderShort 540 default returns 9AM for backward compat`() {
        // Existing behavior must be preserved when use24Hour is not specified
        assertEquals("9AM", formatReminderShort(540))
    }

    @Test
    fun `formatReminderShort other values unaffected by use24Hour`() {
        // Non-540 values should return the same result regardless of use24Hour
        assertEquals("Off", formatReminderShort(REMINDER_OFF, use24Hour = true))
        assertEquals("Off", formatReminderShort(REMINDER_OFF, use24Hour = false))
        assertEquals("At event", formatReminderShort(0, use24Hour = true))
        assertEquals("1d", formatReminderShort(1440, use24Hour = true))
        assertEquals("1w", formatReminderShort(10080, use24Hour = false))
    }

    // ==================== formatReminderOption tests ====================

    @Test
    fun `formatReminderOption 540 allDay use24Hour true returns 09 colon 00 day of event`() {
        assertEquals("09:00 day of event", formatReminderOption(540, isAllDay = true, use24Hour = true))
    }

    @Test
    fun `formatReminderOption 540 allDay use24Hour false returns 9 AM day of event`() {
        assertEquals("9 AM day of event", formatReminderOption(540, isAllDay = true, use24Hour = false))
    }

    @Test
    fun `formatReminderOption 540 allDay default returns 9 AM day of event for backward compat`() {
        // Existing behavior must be preserved
        assertEquals("9 AM day of event", formatReminderOption(540, isAllDay = true))
    }

    // ==================== getAllDayReminderOptions tests ====================

    @Test
    fun `getAllDayReminderOptions use24Hour true has 09 colon 00 label`() {
        val options = getAllDayReminderOptions(use24Hour = true)
        val dayOfOption = options.find { it.minutes == 540 }
        assertEquals("09:00 day of event", dayOfOption?.label)
    }

    @Test
    fun `getAllDayReminderOptions use24Hour false has 9 AM label`() {
        val options = getAllDayReminderOptions(use24Hour = false)
        val dayOfOption = options.find { it.minutes == 540 }
        assertEquals("9 AM day of event", dayOfOption?.label)
    }

    @Test
    fun `getAllDayReminderOptions returns same count as ALL_DAY_REMINDER_OPTIONS`() {
        val options24 = getAllDayReminderOptions(use24Hour = true)
        val options12 = getAllDayReminderOptions(use24Hour = false)
        assertEquals(ALL_DAY_REMINDER_OPTIONS.size, options24.size)
        assertEquals(ALL_DAY_REMINDER_OPTIONS.size, options12.size)
    }

    @Test
    fun `getAllDayReminderOptions non-540 options unchanged`() {
        val options = getAllDayReminderOptions(use24Hour = true)
        // Other options should have same labels regardless of time format
        assertEquals("No reminder", options.find { it.minutes == REMINDER_OFF }?.label)
        assertEquals("12 hours before", options.find { it.minutes == 720 }?.label)
        assertEquals("1 day before", options.find { it.minutes == 1440 }?.label)
        assertEquals("1 week before", options.find { it.minutes == 10080 }?.label)
    }
}
