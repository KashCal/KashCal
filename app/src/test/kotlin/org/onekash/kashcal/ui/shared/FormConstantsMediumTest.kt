package org.onekash.kashcal.ui.shared

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [formatReminderMedium] — the abbreviated-word duration format used by
 * the Settings row values (Default event length, Timed alert, All-day alert).
 *
 * Style: "30 min", "1 hr", "1 day" / "2 days", "1 wk". All-day 9 AM offsets map to
 * their day/week meaning (900 = "1 day", not "15 hr"), and the day-of case reads
 * "Day of" (the 9 AM fire time is shown by the sheet hint, not the row).
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
        // 900 = "9 AM the day before" — must read as 1 day, not its raw 15h magnitude.
        assertEquals("1 day", formatReminderMedium(900, isAllDay = true, resources = resources))
    }

    @Test
    fun `all-day 2 day and 1 week offsets`() {
        assertEquals("2 days", formatReminderMedium(2340, isAllDay = true, resources = resources))
        assertEquals("1 wk", formatReminderMedium(9540, isAllDay = true, resources = resources))
    }
}
