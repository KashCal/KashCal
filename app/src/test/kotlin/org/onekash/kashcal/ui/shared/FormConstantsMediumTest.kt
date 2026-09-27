package org.onekash.kashcal.ui.shared

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [formatReminderMedium], the abbreviated-word format of the Settings rows Default event
 * length, Timed alert and All-day alert.
 *
 * Timed values read "30 min", "1 hr", "1 day", "1 wk", "Off" and "At event". All-day 9 AM offsets
 * read as their day or week meaning (900 is "1 day", not "15 hr"), and the day-of offset reads
 * "Day of"; the sheet hint, not the row, shows the 9 AM fire time.
 */
@RunWith(RobolectricTestRunner::class)
class FormConstantsMediumTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ---- timed durations ----

    @Test
    fun `minutes render as N min`() {
        assertEquals("15 min", formatReminderMedium(15, isAllDay = false, resources = resources))
        assertEquals("30 min", formatReminderMedium(30, isAllDay = false, resources = resources))
    }

    @Test
    fun `whole hours render as N hr`() {
        assertEquals("1 hr", formatReminderMedium(60, isAllDay = false, resources = resources))
        assertEquals("4 hr", formatReminderMedium(240, isAllDay = false, resources = resources))
    }

    @Test
    fun `whole days and weeks render abbreviated`() {
        assertEquals("1 day", formatReminderMedium(1440, isAllDay = false, resources = resources))
        assertEquals("1 wk", formatReminderMedium(10080, isAllDay = false, resources = resources))
    }

    @Test
    fun `off renders as Off`() {
        assertEquals("Off", formatReminderMedium(REMINDER_OFF, isAllDay = false, resources = resources))
    }

    @Test
    fun `timed zero is at event`() {
        assertEquals("At event", formatReminderMedium(0, isAllDay = false, resources = resources))
    }

    // ---- all-day 9 AM offsets map to their day/week meaning ----

    @Test
    fun `all-day day-of reads Day of`() {
        assertEquals("Day of", formatReminderMedium(-540, isAllDay = true, resources = resources))
    }

    @Test
    fun `all-day 1 day offset reads 1 day not 15 hr`() {
        // 900 is 9 AM the day before, so it must read 1 day, not its 15-hour magnitude.
        assertEquals("1 day", formatReminderMedium(900, isAllDay = true, resources = resources))
    }

    @Test
    fun `all-day 2 day and 1 week offsets`() {
        assertEquals("2 days", formatReminderMedium(2340, isAllDay = true, resources = resources))
        assertEquals("1 wk", formatReminderMedium(9540, isAllDay = true, resources = resources))
    }
}
