package org.onekash.kashcal.ui.shared

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for time-format-aware reminder formatting (Issue #96).
 *
 * Verifies that formatReminderShort() and formatReminderOption() respect
 * the use24Hour parameter for the 540-minute (9 AM) all-day reminder option.
 */
@RunWith(RobolectricTestRunner::class)
class FormConstantsTimeFormatTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ==================== formatReminderShort tests ====================

    @Test
    fun `formatReminderShort 540 with use24Hour true returns 09 colon 00`() {
        assertEquals("09:00", formatReminderShort(540, use24Hour = true, resources = resources))
    }

    @Test
    fun `formatReminderShort 540 with use24Hour false returns 9AM`() {
        assertEquals("9AM", formatReminderShort(540, use24Hour = false, resources = resources))
    }

    @Test
    fun `formatReminderShort other values unaffected by use24Hour`() {
        assertEquals("Off", formatReminderShort(REMINDER_OFF, use24Hour = true, resources = resources))
        assertEquals("Off", formatReminderShort(REMINDER_OFF, use24Hour = false, resources = resources))
        assertEquals("At event", formatReminderShort(0, use24Hour = true, resources = resources))
        assertEquals("1d", formatReminderShort(1440, use24Hour = true, resources = resources))
        assertEquals("1w", formatReminderShort(10080, use24Hour = false, resources = resources))
    }

    // ==================== formatReminderOption tests ====================

    @Test
    fun `formatReminderOption -540 allDay use24Hour true returns 09 colon 00 day of event`() {
        // Signed model: 9 AM day of event is -540 (after midnight).
        assertEquals("09:00 day of event", formatReminderOption(-540, isAllDay = true, use24Hour = true, resources = resources))
    }

    @Test
    fun `formatReminderOption -540 allDay use24Hour false returns 9 AM day of event`() {
        assertEquals("9 AM day of event", formatReminderOption(-540, isAllDay = true, use24Hour = false, resources = resources))
    }

    @Test
    fun `formatReminderOption legacy 540 allDay renders by magnitude (matches fire time), not 9 AM day of`() {
        // A legacy stored 540 now fires 9h BEFORE midnight; its label must reflect that,
        // not the stale "9 AM day of event" (which would be a label/behavior mismatch).
        assertEquals("9 hours before", formatReminderOption(540, isAllDay = true, resources = resources))
    }

    // ==================== getAllDayReminderOptions tests ====================

    @Test
    fun `getAllDayReminderOptions use24Hour true has 09 colon 00 label`() {
        val options = getAllDayReminderOptions(use24Hour = true, resources = resources)
        val dayOfOption = options.find { it.minutes == -540 } // 9 AM day of (after midnight)
        assertEquals("09:00 day of event", dayOfOption?.label)
    }

    @Test
    fun `getAllDayReminderOptions use24Hour false has 9 AM label`() {
        val options = getAllDayReminderOptions(use24Hour = false, resources = resources)
        val dayOfOption = options.find { it.minutes == -540 }
        assertEquals("9 AM day of event", dayOfOption?.label)
    }

    @Test
    fun `getAllDayReminderOptions returns same count as ALL_DAY_REMINDER_MINUTES`() {
        val options24 = getAllDayReminderOptions(use24Hour = true, resources = resources)
        val options12 = getAllDayReminderOptions(use24Hour = false, resources = resources)
        assertEquals(ALL_DAY_REMINDER_MINUTES.size, options24.size)
        assertEquals(ALL_DAY_REMINDER_MINUTES.size, options12.size)
    }
}
