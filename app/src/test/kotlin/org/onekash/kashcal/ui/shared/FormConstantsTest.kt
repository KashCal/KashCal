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
}
