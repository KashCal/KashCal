package org.onekash.kashcal.reminder.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ReminderScheduler reminder offset parsing.
 *
 * Tests verify:
 * - ISO 8601 duration parsing (time-based, day-based, week-based, combined)
 * - Edge cases (zero duration, invalid formats, negative offsets)
 * - iCal VALARM format compatibility
 *
 * Reference: RFC 5545 Section 3.3.6 (Duration)
 */
class ReminderSchedulerTest {

    // ==================== parseIsoDuration Tests ====================

    @Test
    fun `parseIsoDuration handles PT15M - 15 minutes`() {
        val result = parseIsoDuration("PT15M")

        assertEquals(15 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles PT1H - 1 hour`() {
        val result = parseIsoDuration("PT1H")

        assertEquals(60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles PT1H30M - 1 hour 30 minutes`() {
        val result = parseIsoDuration("PT1H30M")

        assertEquals((60 + 30) * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P1D - 1 day`() {
        val result = parseIsoDuration("P1D")

        assertEquals(24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P7D - 7 days`() {
        val result = parseIsoDuration("P7D")

        assertEquals(7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P1W - 1 week`() {
        val result = parseIsoDuration("P1W")

        assertEquals(7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P2W - 2 weeks`() {
        val result = parseIsoDuration("P2W")

        assertEquals(14 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P1DT2H30M - combined day and time`() {
        val result = parseIsoDuration("P1DT2H30M")

        val expected = (24 * 60 * 60 * 1000L) + // 1 day
            (2 * 60 * 60 * 1000L) +             // 2 hours
            (30 * 60 * 1000L)                   // 30 minutes
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration handles PT30S - 30 seconds`() {
        val result = parseIsoDuration("PT30S")

        assertEquals(30 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles PT1H15M30S - hours minutes seconds`() {
        val result = parseIsoDuration("PT1H15M30S")

        val expected = (60 * 60 * 1000L) +  // 1 hour
            (15 * 60 * 1000L) +              // 15 minutes
            (30 * 1000L)                     // 30 seconds
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration handles PT0M - zero minutes (at event time)`() {
        val result = parseIsoDuration("PT0M")

        assertEquals(0L, result)
    }

    @Test
    fun `parseIsoDuration handles PT0S - zero seconds (at event time)`() {
        val result = parseIsoDuration("PT0S")

        assertEquals(0L, result)
    }

    @Test
    fun `parseIsoDuration returns null for empty string`() {
        val result = parseIsoDuration("")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for blank string`() {
        val result = parseIsoDuration("   ")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for missing P prefix`() {
        val result = parseIsoDuration("T15M")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for invalid format`() {
        val result = parseIsoDuration("PXYZ")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration handles large values - P365D`() {
        val result = parseIsoDuration("P365D")

        assertEquals(365L * 24 * 60 * 60 * 1000, result)
    }

    // ==================== parseReminderOffset Tests ====================

    @Test
    fun `parseReminderOffset handles negative PT15M - 15 minutes before`() {
        val result = parseReminderOffset("-PT15M")

        assertEquals(-15 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative PT1H - 1 hour before`() {
        val result = parseReminderOffset("-PT1H")

        assertEquals(-60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative P1D - 1 day before`() {
        val result = parseReminderOffset("-P1D")

        assertEquals(-24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative P1W - 1 week before`() {
        val result = parseReminderOffset("-P1W")

        assertEquals(-7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles positive PT15M - 15 minutes after`() {
        val result = parseReminderOffset("PT15M")

        assertEquals(15 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative P1DT2H - 1 day 2 hours before`() {
        val result = parseReminderOffset("-P1DT2H")

        val expected = -((24 * 60 * 60 * 1000L) + (2 * 60 * 60 * 1000L))
        assertEquals(expected, result)
    }

    @Test
    fun `parseReminderOffset returns null for empty string`() {
        val result = parseReminderOffset("")

        assertNull(result)
    }

    @Test
    fun `parseReminderOffset returns null for blank string`() {
        val result = parseReminderOffset("   ")

        assertNull(result)
    }

    @Test
    fun `parseReminderOffset returns null for invalid format`() {
        val result = parseReminderOffset("invalid")

        assertNull(result)
    }

    // ==================== Common iCloud/CalDAV Reminder Values ====================

    @Test
    fun `parseReminderOffset handles iCloud 10 minutes before`() {
        val result = parseReminderOffset("-PT10M")

        assertEquals(-10 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 30 minutes before`() {
        val result = parseReminderOffset("-PT30M")

        assertEquals(-30 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 1 hour before`() {
        val result = parseReminderOffset("-PT1H")

        assertEquals(-60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 2 hours before`() {
        val result = parseReminderOffset("-PT2H")

        assertEquals(-2 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 1 day before (for all-day events)`() {
        val result = parseReminderOffset("-P1D")

        assertEquals(-24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 2 days before`() {
        val result = parseReminderOffset("-P2D")

        assertEquals(-2 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 1 week before`() {
        val result = parseReminderOffset("-P1W")

        assertEquals(-7 * 24 * 60 * 60 * 1000L, result)
    }

    // ==================== Trigger Time Calculation Tests ====================

    @Test
    fun `trigger time calculation - 15 minutes before event`() {
        val eventStartTs = 1704067200000L // Jan 1, 2024 12:00 UTC
        val offset = parseReminderOffset("-PT15M")!!

        val triggerTime = eventStartTs + offset

        // Trigger should be at 11:45
        assertEquals(eventStartTs - (15 * 60 * 1000), triggerTime)
    }

    @Test
    fun `trigger time calculation - at event time`() {
        val eventStartTs = 1704067200000L
        val offset = parseReminderOffset("-PT0M") ?: 0L

        val triggerTime = eventStartTs + offset

        assertEquals(eventStartTs, triggerTime)
    }

    @Test
    fun `trigger time calculation - 1 day before event`() {
        val eventStartTs = 1704067200000L // Jan 1, 2024 12:00 UTC
        val offset = parseReminderOffset("-P1D")!!

        val triggerTime = eventStartTs + offset

        // Trigger should be Dec 31, 2023 12:00 UTC
        assertEquals(eventStartTs - (24 * 60 * 60 * 1000), triggerTime)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `parseIsoDuration handles P0D - zero days`() {
        val result = parseIsoDuration("P0D")

        assertNull(result) // Zero with no explicit 0M/0S should return null
    }

    @Test
    fun `parseReminderOffset handles double negative gracefully`() {
        // This shouldn't happen in practice, but test robustness
        val result = parseReminderOffset("--PT15M")

        // Expected: parsing should fail (invalid format)
        assertNull(result)
    }

    @Test
    fun `parseIsoDuration handles P1DT0H0M - day with zero time`() {
        val result = parseIsoDuration("P1DT0H0M")

        // Should still parse the day part
        assertEquals(24 * 60 * 60 * 1000L, result)
    }
}

/**
 * Integration tests for ReminderScheduler constants and configuration.
 */
class ReminderSchedulerConstantsTest {

    @Test
    fun `schedule window is 30 days`() {
        // Extended from 7 to 30 days in v16.5.6 to catch far-future events
        assertEquals(30, ReminderScheduler.SCHEDULE_WINDOW_DAYS)
    }

    @Test
    fun `action reminder alarm constant is correct`() {
        assertEquals("org.onekash.kashcal.REMINDER_ALARM", ReminderScheduler.ACTION_REMINDER_ALARM)
    }

    @Test
    fun `extra reminder id constant is correct`() {
        assertEquals("reminder_id", ReminderScheduler.EXTRA_REMINDER_ID)
    }
}
