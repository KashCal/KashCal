package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.getReminderOptionsForEventType

/**
 * Unit tests for ReminderPicker component.
 *
 * These tests verify the BUG FIX: EventFormSheet was previously showing
 * ALL reminder options regardless of event type. Now the correct options
 * are shown based on isAllDay.
 */
class ReminderPickerTest {

    // ==================== BUG REGRESSION TESTS ====================
    // These tests ensure the bug doesn't come back

    @Test
    fun `BUG REGRESSION - timed event should NOT show 9 AM day of event option`() {
        // THE BUG: EventFormSheet showed "9 AM day of event" for timed meetings
        // This makes no sense for a 2pm meeting
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val has9AmOption = timedOptions.any { it.minutes == 540 }
        assertFalse(
            "Timed events should NOT have '9 AM day of event' option (540 minutes)",
            has9AmOption
        )
    }

    @Test
    fun `timed event now includes 1 day before option`() {
        // 1 day before (1440 minutes) is now included in timed options for advance notice
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val has1DayOption = timedOptions.any { it.minutes == 1440 }
        assertTrue(
            "Timed events should now have '1 day before' option (1440 minutes)",
            has1DayOption
        )
    }

    @Test
    fun `BUG REGRESSION - all-day event should NOT show 5 minutes before option`() {
        // 5 minutes before makes no sense for an all-day event
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)
        val has5MinOption = allDayOptions.any { it.minutes == 5 }
        assertFalse(
            "All-day events should NOT have '5 minutes before' option",
            has5MinOption
        )
    }

    @Test
    fun `BUG REGRESSION - all-day event should NOT show 15 minutes before option`() {
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)
        val has15MinOption = allDayOptions.any { it.minutes == 15 }
        assertFalse(
            "All-day events should NOT have '15 minutes before' option",
            has15MinOption
        )
    }

    // ==================== TIMED EVENT OPTIONS ====================
    // Options: None, 15m, 30m, 1h, 4h, 1d, 1w

    @Test
    fun `timed event has No reminder option`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertTrue(options.any { it.minutes == REMINDER_OFF })
    }

    @Test
    fun `timed event has 15 minutes before option`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertTrue(options.any { it.minutes == 15 })
    }

    @Test
    fun `timed event has 1 hour before option`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertTrue(options.any { it.minutes == 60 })
    }

    @Test
    fun `timed event has 4 hours before option`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertTrue(options.any { it.minutes == 240 })
    }

    @Test
    fun `timed event has 1 day before option`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertTrue(options.any { it.minutes == 1440 })
    }

    @Test
    fun `timed event has 1 week before option`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertTrue(options.any { it.minutes == 10080 })
    }

    @Test
    fun `timed event no longer has legacy options in picker`() {
        // Legacy options removed from picker but still work via grandfather clause
        // Note: 30 minutes was restored as a valid option in v14.2.13
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertFalse("0 (at event) removed from picker", options.any { it.minutes == 0 })
        assertFalse("5 minutes removed from picker", options.any { it.minutes == 5 })
        assertFalse("10 minutes removed from picker", options.any { it.minutes == 10 })
        assertFalse("120 (2 hours) removed from picker", options.any { it.minutes == 120 })
    }

    // ==================== ALL-DAY EVENT OPTIONS ====================

    @Test
    fun `all-day event has No reminder option`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertTrue(options.any { it.minutes == REMINDER_OFF })
    }

    @Test
    fun `all-day event has 9 AM day of event option`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertTrue(options.any { it.minutes == 540 })
    }

    @Test
    fun `all-day event has 1 day before option`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertTrue(options.any { it.minutes == 1440 })
    }

    @Test
    fun `all-day event has 12 hours before option`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertTrue(options.any { it.minutes == 720 })
    }

    @Test
    fun `all-day event no longer has 2 days before option in picker`() {
        // 2 days before (2880) removed from picker but still works via grandfather clause
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertFalse("2 days before removed from picker", options.any { it.minutes == 2880 })
    }

    @Test
    fun `all-day event has 1 week before option`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertTrue(options.any { it.minutes == 10080 })
    }

    // ==================== OPTIONS LISTS VERIFICATION ====================

    @Test
    fun `timed options list equals TIMED_REMINDER_OPTIONS`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertEquals(TIMED_REMINDER_OPTIONS, options)
    }

    @Test
    fun `all-day options list equals ALL_DAY_REMINDER_OPTIONS`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertEquals(ALL_DAY_REMINDER_OPTIONS, options)
    }

    @Test
    fun `timed and all-day options are different`() {
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)

        // They should not be equal
        assertFalse(timedOptions == allDayOptions)

        // Both should have No reminder as first option
        assertEquals(REMINDER_OFF, timedOptions.first().minutes)
        assertEquals(REMINDER_OFF, allDayOptions.first().minutes)
    }

    @Test
    fun `timed reminder options are in ascending order by minutes`() {
        val options = TIMED_REMINDER_OPTIONS.filter { it.minutes != REMINDER_OFF }
        assertEquals(options, options.sortedBy { it.minutes })
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `switching from all-day to timed should invalidate 9 AM option`() {
        // If user sets "9 AM day of event" then toggles all-day off,
        // the 540 minute value is not valid for timed events
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val isValidTimedOption = timedOptions.any { it.minutes == 540 }
        assertFalse(
            "540 minutes (9 AM day of event) should not be valid for timed events",
            isValidTimedOption
        )
    }

    @Test
    fun `switching from timed to all-day should invalidate 5 minute option`() {
        // If user sets "5 minutes before" then toggles all-day on,
        // the 5 minute value is not valid for all-day events
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)
        val isValidAllDayOption = allDayOptions.any { it.minutes == 5 }
        assertFalse(
            "5 minutes should not be valid for all-day events",
            isValidAllDayOption
        )
    }

    @Test
    fun `REMINDER_OFF is valid for both event types`() {
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)

        assertTrue(timedOptions.any { it.minutes == REMINDER_OFF })
        assertTrue(allDayOptions.any { it.minutes == REMINDER_OFF })
    }
}
