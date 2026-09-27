package org.onekash.kashcal.ui.shared

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the 9 AM reminder labels against the time format (#96).
 *
 * For a timed value, [formatReminderShort] labels 540 "9AM" or "09:00" by `use24Hour`; the other
 * values tested (off, at event, 1d, 1w) don't change with it. [formatReminderOption] takes no time
 * format: the all-day -540 reads "Day of event" and a legacy all-day 540 reads by magnitude.
 * [getAllDayReminderOptions] labels -540 "Day of event" and has one option per
 * [ALL_DAY_REMINDER_MINUTES] entry.
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
    fun `formatReminderOption -540 allDay returns Day of event`() {
        // The 9 AM fire time is conveyed by the sheet hint, so the option label is
        // terse and format-independent.
        assertEquals("Day of event", formatReminderOption(-540, isAllDay = true, resources = resources))
    }

    @Test
    fun `formatReminderOption legacy 540 allDay renders by magnitude (matches fire time), not 9 AM day of`() {
        // A legacy all-day 540 fires 9 hours before midnight, so its label must say that, not
        // "9 AM day of event".
        assertEquals("9 hours before", formatReminderOption(540, isAllDay = true, resources = resources))
    }

    // ==================== getAllDayReminderOptions tests ====================

    @Test
    fun `getAllDayReminderOptions day-of label is Day of event`() {
        val options = getAllDayReminderOptions(resources = resources)
        val dayOfOption = options.find { it.minutes == -540 } // 9 AM day of (after midnight)
        assertEquals("Day of event", dayOfOption?.label)
    }

    @Test
    fun `getAllDayReminderOptions returns same count as ALL_DAY_REMINDER_MINUTES`() {
        val options = getAllDayReminderOptions(resources = resources)
        assertEquals(ALL_DAY_REMINDER_MINUTES.size, options.size)
    }
}
