package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_MINUTES
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_MINUTES

/**
 * Unit tests for ReminderPicker component.
 *
 * These tests verify the BUG FIX: EventFormSheet was previously showing
 * ALL reminder options regardless of event type. Now the correct options
 * are shown based on isAllDay.
 */
class ReminderPickerTest {

    // ==================== BUG REGRESSION TESTS ====================

    @Test
    fun `BUG REGRESSION - timed event should NOT show 9 AM day of event option`() {
        assertFalse(
            "Timed events should NOT have '9 AM day of event' option (540 minutes)",
            TIMED_REMINDER_MINUTES.contains(540)
        )
    }

    @Test
    fun `timed event now includes 1 day before option`() {
        assertTrue(
            "Timed events should now have '1 day before' option (1440 minutes)",
            TIMED_REMINDER_MINUTES.contains(1440)
        )
    }

    @Test
    fun `BUG REGRESSION - all-day event should NOT show 5 minutes before option`() {
        assertFalse(
            "All-day events should NOT have '5 minutes before' option",
            ALL_DAY_REMINDER_MINUTES.contains(5)
        )
    }

    @Test
    fun `BUG REGRESSION - all-day event should NOT show 15 minutes before option`() {
        assertFalse(
            "All-day events should NOT have '15 minutes before' option",
            ALL_DAY_REMINDER_MINUTES.contains(15)
        )
    }

    // ==================== TIMED EVENT OPTIONS ====================

    @Test
    fun `timed event has No reminder option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(REMINDER_OFF))
    }

    @Test
    fun `timed event has at time of event option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(0))
    }

    @Test
    fun `timed event has 5 minutes before option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(5))
    }

    @Test
    fun `timed event has 15 minutes before option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(15))
    }

    @Test
    fun `timed event has 1 hour before option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(60))
    }

    @Test
    fun `timed event has 4 hours before option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(240))
    }

    @Test
    fun `timed event has 1 day before option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(1440))
    }

    @Test
    fun `timed event has 1 week before option`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(10080))
    }

    @Test
    fun `timed event no longer has legacy options in picker`() {
        assertFalse("10 minutes removed from picker", TIMED_REMINDER_MINUTES.contains(10))
        assertFalse("120 (2 hours) removed from picker", TIMED_REMINDER_MINUTES.contains(120))
    }

    // ==================== ALL-DAY EVENT OPTIONS ====================

    @Test
    fun `all-day event has No reminder option`() {
        assertTrue(ALL_DAY_REMINDER_MINUTES.contains(REMINDER_OFF))
    }

    @Test
    fun `all-day event has 9 AM day of event option`() {
        // Signed offset: 9 AM day of fires after midnight -> -540.
        assertTrue(ALL_DAY_REMINDER_MINUTES.contains(-540))
    }

    @Test
    fun `all-day event has 1 day before option`() {
        // 9 AM the day before = 15h before midnight -> 900.
        assertTrue(ALL_DAY_REMINDER_MINUTES.contains(900))
    }

    @Test
    fun `all-day event has 2 days before option`() {
        assertTrue(ALL_DAY_REMINDER_MINUTES.contains(2340))
    }

    @Test
    fun `all-day event has 1 week before option`() {
        assertTrue(ALL_DAY_REMINDER_MINUTES.contains(9540))
    }

    @Test
    fun `all-day event no longer has legacy 12 hours before (720) option`() {
        assertFalse("legacy 720 removed", ALL_DAY_REMINDER_MINUTES.contains(720))
    }

    // ==================== OPTIONS LISTS VERIFICATION ====================

    @Test
    fun `timed and all-day options are different`() {
        assertFalse(TIMED_REMINDER_MINUTES == ALL_DAY_REMINDER_MINUTES)

        assertEquals(REMINDER_OFF, TIMED_REMINDER_MINUTES.first())
        assertEquals(REMINDER_OFF, ALL_DAY_REMINDER_MINUTES.first())
    }

    @Test
    fun `timed reminder options are in ascending order by minutes`() {
        val withoutOff = TIMED_REMINDER_MINUTES.filter { it != REMINDER_OFF }
        assertEquals(withoutOff, withoutOff.sorted())
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `switching from all-day to timed should invalidate 9 AM option`() {
        assertFalse(
            "540 minutes (9 AM day of event) should not be valid for timed events",
            TIMED_REMINDER_MINUTES.contains(540)
        )
    }

    @Test
    fun `switching from timed to all-day should invalidate 5 minute option`() {
        assertFalse(
            "5 minutes should not be valid for all-day events",
            ALL_DAY_REMINDER_MINUTES.contains(5)
        )
    }

    @Test
    fun `REMINDER_OFF is valid for both event types`() {
        assertTrue(TIMED_REMINDER_MINUTES.contains(REMINDER_OFF))
        assertTrue(ALL_DAY_REMINDER_MINUTES.contains(REMINDER_OFF))
    }
}
