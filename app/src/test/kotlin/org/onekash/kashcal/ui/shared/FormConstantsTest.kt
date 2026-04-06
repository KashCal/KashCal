package org.onekash.kashcal.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FormConstants.
 * Verifies shared constants are correctly defined and consistent.
 */
class FormConstantsTest {

    @Test
    fun `REMINDER_OFF should be negative one`() {
        assertEquals(-1, REMINDER_OFF)
    }

    @Test
    fun `TIMED_REMINDER_OPTIONS should not be empty`() {
        assertTrue(TIMED_REMINDER_OPTIONS.isNotEmpty())
    }

    @Test
    fun `ALL_DAY_REMINDER_OPTIONS should not be empty`() {
        assertTrue(ALL_DAY_REMINDER_OPTIONS.isNotEmpty())
    }

    @Test
    fun `TIMED_REMINDER_OPTIONS should have no duplicate minutes values`() {
        val minutes = TIMED_REMINDER_OPTIONS.map { it.minutes }
        assertEquals(minutes.size, minutes.distinct().size)
    }

    @Test
    fun `ALL_DAY_REMINDER_OPTIONS should have no duplicate minutes values`() {
        val minutes = ALL_DAY_REMINDER_OPTIONS.map { it.minutes }
        assertEquals(minutes.size, minutes.distinct().size)
    }

    @Test
    fun `TIMED_REMINDER_OPTIONS should start with no reminder option`() {
        assertEquals(REMINDER_OFF, TIMED_REMINDER_OPTIONS.first().minutes)
        assertEquals("No reminder", TIMED_REMINDER_OPTIONS.first().label)
    }

    @Test
    fun `ALL_DAY_REMINDER_OPTIONS should start with no reminder option`() {
        assertEquals(REMINDER_OFF, ALL_DAY_REMINDER_OPTIONS.first().minutes)
        assertEquals("No reminder", ALL_DAY_REMINDER_OPTIONS.first().label)
    }

    @Test
    fun `TIMED_REMINDER_OPTIONS should not contain all-day specific options`() {
        val timedMinutes = TIMED_REMINDER_OPTIONS.map { it.minutes }
        // 540 = 9 AM day of event (all-day specific)
        assertFalse("Timed options should not include '9 AM day of event'", timedMinutes.contains(540))
        // Note: 1440 (1 day before) is now included in timed options for longer advance notice
    }

    @Test
    fun `ALL_DAY_REMINDER_OPTIONS should not contain timed-specific options`() {
        val allDayMinutes = ALL_DAY_REMINDER_OPTIONS.map { it.minutes }
        // 5, 10, 15, 30 minutes are timed-specific
        assertFalse("All-day options should not include '5 minutes before'", allDayMinutes.contains(5))
        assertFalse("All-day options should not include '10 minutes before'", allDayMinutes.contains(10))
        assertFalse("All-day options should not include '15 minutes before'", allDayMinutes.contains(15))
        assertFalse("All-day options should not include '30 minutes before'", allDayMinutes.contains(30))
    }

    @Test
    fun `getReminderOptionsForEventType returns timed options for timed events`() {
        val options = getReminderOptionsForEventType(isAllDay = false)
        assertEquals(TIMED_REMINDER_OPTIONS, options)
    }

    @Test
    fun `getReminderOptionsForEventType returns all-day options for all-day events`() {
        val options = getReminderOptionsForEventType(isAllDay = true)
        assertEquals(ALL_DAY_REMINDER_OPTIONS, options)
    }

    @Test
    fun `SYNC_OPTIONS should not be empty`() {
        assertTrue(SYNC_OPTIONS.isNotEmpty())
    }

    @Test
    fun `SYNC_OPTIONS should have no duplicate interval values`() {
        val intervals = SYNC_OPTIONS.map { it.intervalMs }
        assertEquals(intervals.size, intervals.distinct().size)
    }

    @Test
    fun `SYNC_OPTIONS should include manual only option`() {
        val hasManualOnly = SYNC_OPTIONS.any { it.intervalMs == Long.MAX_VALUE }
        assertTrue("SYNC_OPTIONS should include manual only option", hasManualOnly)
    }

    @Test
    fun `SYNC_OPTIONS intervals should be in ascending order`() {
        val intervals = SYNC_OPTIONS.map { it.intervalMs }
        assertEquals(intervals, intervals.sorted())
    }

    // ==================== Email Masking Tests ====================

    @Test
    fun `maskEmail returns empty string for null input`() {
        assertEquals("", maskEmail(null))
    }

    @Test
    fun `maskEmail masks email with standard format`() {
        assertEquals("j***@icloud.com", maskEmail("john@icloud.com"))
        assertEquals("t***@gmail.com", maskEmail("test.user@gmail.com"))
    }

    @Test
    fun `maskEmail preserves short email prefix unchanged`() {
        // atIndex = 1 (not > 1), so returned unchanged
        assertEquals("a@b.com", maskEmail("a@b.com"))
    }

    @Test
    fun `maskEmail masks two-char prefix`() {
        // atIndex = 2 (> 1), so masked
        assertEquals("a***@c.com", maskEmail("ab@c.com"))
    }

    @Test
    fun `maskEmail handles empty string`() {
        assertEquals("", maskEmail(""))
    }

    @Test
    fun `maskEmail handles email without at symbol`() {
        assertEquals("notanemail", maskEmail("notanemail"))
    }

    // ==================== Reminder Migration Tests (v16.4.1) ====================

    @Test
    fun `15 minutes is valid for timed events but not all-day`() {
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)

        assertTrue("15 min should be valid for timed", timedOptions.any { it.minutes == 15 })
        assertFalse("15 min should NOT be valid for all-day", allDayOptions.any { it.minutes == 15 })
    }

    @Test
    fun `540 minutes (9 AM) is valid for all-day events but not timed`() {
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)

        assertFalse("540 min should NOT be valid for timed", timedOptions.any { it.minutes == 540 })
        assertTrue("540 min should be valid for all-day", allDayOptions.any { it.minutes == 540 })
    }

    @Test
    fun `REMINDER_OFF is valid for both timed and all-day events`() {
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)

        assertTrue("REMINDER_OFF should be valid for timed", timedOptions.any { it.minutes == REMINDER_OFF })
        assertTrue("REMINDER_OFF should be valid for all-day", allDayOptions.any { it.minutes == REMINDER_OFF })
    }

    @Test
    fun `1440 minutes (1 day) is valid for both timed and all-day events`() {
        val timedOptions = getReminderOptionsForEventType(isAllDay = false)
        val allDayOptions = getReminderOptionsForEventType(isAllDay = true)

        assertTrue("1440 min should be valid for timed", timedOptions.any { it.minutes == 1440 })
        assertTrue("1440 min should be valid for all-day", allDayOptions.any { it.minutes == 1440 })
    }

    // ==================== Event Duration Tests (v20.8.0) ====================

    @Test
    fun `EVENT_DURATION_OPTIONS should not be empty`() {
        assertTrue(EVENT_DURATION_OPTIONS.isNotEmpty())
    }

    @Test
    fun `EVENT_DURATION_OPTIONS should have no duplicate minutes values`() {
        val minutes = EVENT_DURATION_OPTIONS.map { it.minutes }
        assertEquals(minutes.size, minutes.distinct().size)
    }

    @Test
    fun `EVENT_DURATION_OPTIONS should be in ascending order`() {
        val minutes = EVENT_DURATION_OPTIONS.map { it.minutes }
        assertEquals(minutes, minutes.sorted())
    }

    @Test
    fun `EVENT_DURATION_OPTIONS should contain expected values`() {
        val minutes = EVENT_DURATION_OPTIONS.map { it.minutes }
        assertTrue("Should contain 15 minutes", minutes.contains(15))
        assertTrue("Should contain 30 minutes", minutes.contains(30))
        assertTrue("Should contain 60 minutes", minutes.contains(60))
        assertTrue("Should contain 120 minutes", minutes.contains(120))
    }

    @Test
    fun `formatDuration returns correct string for minutes under 60`() {
        assertEquals("15 minutes", formatDuration(15))
        assertEquals("30 minutes", formatDuration(30))
        assertEquals("45 minutes", formatDuration(45))
    }

    @Test
    fun `formatDuration returns correct string for exactly 1 hour`() {
        assertEquals("1 hour", formatDuration(60))
    }

    @Test
    fun `formatDuration returns correct string for whole hours`() {
        assertEquals("2 hours", formatDuration(120))
        assertEquals("3 hours", formatDuration(180))
    }

    @Test
    fun `formatDuration returns correct string for hours and minutes`() {
        assertEquals("1h 30m", formatDuration(90))
        assertEquals("2h 15m", formatDuration(135))
    }

    @Test
    fun `formatDurationShort returns correct string for minutes under 60`() {
        assertEquals("15m", formatDurationShort(15))
        assertEquals("30m", formatDurationShort(30))
    }

    @Test
    fun `formatDurationShort returns correct string for whole hours`() {
        assertEquals("1h", formatDurationShort(60))
        assertEquals("2h", formatDurationShort(120))
    }

    @Test
    fun `formatDurationShort returns correct string for hours and minutes`() {
        assertEquals("1h30m", formatDurationShort(90))
        assertEquals("2h15m", formatDurationShort(135))
    }

    // ==================== Custom Reminders: Duration Helpers (Chunk 1) ====================

    @Test
    fun `componentsToMinutes converts days hours minutes to total minutes`() {
        assertEquals(1590, componentsToMinutes(1, 2, 30))
    }

    @Test
    fun `componentsToMinutes returns zero for all zeros`() {
        assertEquals(0, componentsToMinutes(0, 0, 0))
    }

    @Test
    fun `componentsToMinutes handles days only`() {
        assertEquals(1440, componentsToMinutes(1, 0, 0))
    }

    @Test
    fun `minutesToComponents decomposes total minutes into days hours minutes`() {
        assertEquals(Triple(1, 2, 30), minutesToComponents(1590))
    }

    @Test
    fun `minutesToComponents returns all zeros for zero`() {
        assertEquals(Triple(0, 0, 0), minutesToComponents(0))
    }

    @Test
    fun `minutesToComponents handles minutes only`() {
        assertEquals(Triple(0, 0, 15), minutesToComponents(15))
    }

    @Test
    fun `minutesToComponents handles hours and minutes`() {
        assertEquals(Triple(0, 1, 30), minutesToComponents(90))
    }

    @Test
    fun `roundToWheelStep rounds to nearest 5`() {
        assertEquals(5, roundToWheelStep(7))
        assertEquals(10, roundToWheelStep(8))
        assertEquals(0, roundToWheelStep(0))
        assertEquals(0, roundToWheelStep(2))
        assertEquals(5, roundToWheelStep(3))
        assertEquals(55, roundToWheelStep(57))
    }

    @Test
    fun `formatReminderDuration for timed 15 minutes`() {
        assertEquals("15 minutes before", formatReminderDuration(15, isAllDay = false, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for timed 90 minutes`() {
        assertEquals("1 hour 30 min before", formatReminderDuration(90, isAllDay = false, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for timed at time of event`() {
        assertEquals("At time of event", formatReminderDuration(0, isAllDay = false, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for timed 1 day`() {
        assertEquals("1 day before", formatReminderDuration(1440, isAllDay = false, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for timed 2 days 3 hours`() {
        assertEquals("2 days 3 hours before", formatReminderDuration(3060, isAllDay = false, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for all-day 540 min 12h format`() {
        assertEquals("9 AM day of event", formatReminderDuration(540, isAllDay = true, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for all-day 540 min 24h format`() {
        assertEquals("09:00 day of event", formatReminderDuration(540, isAllDay = true, use24Hour = true))
    }

    @Test
    fun `formatReminderDuration for all-day 1 day before`() {
        assertEquals("1 day before at 9 AM", formatReminderDuration(1440, isAllDay = true, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for all-day 1 day before 24h`() {
        assertEquals("1 day before at 09:00", formatReminderDuration(1440, isAllDay = true, use24Hour = true))
    }

    @Test
    fun `formatReminderDuration for all-day 2 days before`() {
        assertEquals("2 days before at 9 AM", formatReminderDuration(2880, isAllDay = true, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for all-day non-standard falls back`() {
        // 1500 min = 1 day 1 hour - not a standard all-day pattern
        assertEquals("1 day 1 hour before", formatReminderDuration(1500, isAllDay = true, use24Hour = false))
    }

    @Test
    fun `formatReminderDuration for all-day 30 min shows raw duration`() {
        // Sub-hour all-day reminder, not a contextual pattern - falls back to generic
        assertEquals("30 minutes before", formatReminderDuration(30, isAllDay = true, use24Hour = false))
    }

    @Test
    fun `deduplicateAndSortReminders deduplicates and sorts ascending`() {
        assertEquals(listOf(15, 60), deduplicateAndSortReminders(listOf(60, 15, 60)))
    }

    @Test
    fun `deduplicateAndSortReminders handles empty list`() {
        assertEquals(emptyList<Int>(), deduplicateAndSortReminders(emptyList()))
    }

    @Test
    fun `deduplicateAndSortReminders handles single element`() {
        assertEquals(listOf(30), deduplicateAndSortReminders(listOf(30)))
    }

    @Test
    fun `formatReminderSummary shows comma-separated short labels`() {
        assertEquals("15m, 1d", formatReminderSummary(listOf(15, 1440), use24Hour = false))
    }

    @Test
    fun `formatReminderSummary shows None for empty list`() {
        assertEquals("None", formatReminderSummary(emptyList(), use24Hour = false))
    }

    @Test
    fun `formatReminderSummary shows single value`() {
        assertEquals("1h", formatReminderSummary(listOf(60), use24Hour = false))
    }

    @Test
    fun `MAX_REMINDERS is 5`() {
        assertEquals(5, MAX_REMINDERS)
    }

    @Test
    fun `TIMED_PRESET_CHIPS has 4 chips`() {
        assertEquals(4, TIMED_PRESET_CHIPS.size)
        assertEquals(15, TIMED_PRESET_CHIPS[0].minutes)
        assertEquals(30, TIMED_PRESET_CHIPS[1].minutes)
        assertEquals(60, TIMED_PRESET_CHIPS[2].minutes)
        assertEquals(1440, TIMED_PRESET_CHIPS[3].minutes)
    }

    @Test
    fun `ALL_DAY_PRESET_CHIPS has 4 chips`() {
        assertEquals(4, ALL_DAY_PRESET_CHIPS.size)
        assertEquals(540, ALL_DAY_PRESET_CHIPS[0].minutes)
        assertEquals(1440, ALL_DAY_PRESET_CHIPS[1].minutes)
        assertEquals(2880, ALL_DAY_PRESET_CHIPS[2].minutes)
        assertEquals(10080, ALL_DAY_PRESET_CHIPS[3].minutes)
    }
}
