package org.onekash.kashcal.reminder.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

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

/**
 * Unit tests for calculateAllDayTriggerTime() timezone-aware all-day reminder calculations.
 *
 * These tests verify the fix for Bug 1 where all-day reminders were calculated
 * from UTC instead of the user's local timezone.
 *
 * Expected behavior:
 * - "-PT9H" (9 AM day of event): Fire at 9 AM local on the event date
 * - "-P1D" (1 day before): Fire at 9 AM local, one day before
 * - "-P2D", "-P1W" etc.: Fire at 9 AM local, N days before
 */
class CalculateAllDayTriggerTimeTest {

    // ==================== Sub-day offsets (e.g., "9 AM day of event") ====================

    @Test
    fun `9 AM day of event in PST`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT9H")!!  // -32,400,000ms

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Jan 6 09:00 PST = Jan 6 17:00 UTC
        assertEquals(1736182800000L, result)
    }

    @Test
    fun `9 AM day of event in Tokyo (JST = UTC+9)`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Tokyo"))

        // Jan 6 09:00 JST = Jan 6 00:00 UTC
        assertEquals(1736121600000L, result)
    }

    @Test
    fun `9 AM day of event in New York (EST = UTC-5)`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/New_York"))

        // Jan 6 09:00 EST = Jan 6 14:00 UTC = 1736121600000 + 14*3600000 = 1736172000000
        assertEquals(1736172000000L, result)
    }

    @Test
    fun `6 AM day of event in PST`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT6H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Jan 6 06:00 PST = Jan 6 14:00 UTC = 1736121600000 + 14*3600000 = 1736172000000
        assertEquals(1736172000000L, result)
    }

    @Test
    fun `12 noon day of event in PST`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT12H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Jan 6 12:00 PST = Jan 6 20:00 UTC
        assertEquals(1736193600000L, result)
    }

    // ==================== Day-based offsets (fire at 9 AM local) ====================

    @Test
    fun `1 day before in PST fires at 9 AM`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-P1D")!!  // -86,400,000ms

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Jan 5 09:00 PST = Jan 5 17:00 UTC
        assertEquals(1736096400000L, result)
    }

    @Test
    fun `1 day before in Tokyo fires at 9 AM`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-P1D")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Tokyo"))

        // Jan 5 09:00 JST = Jan 5 00:00 UTC
        assertEquals(1736035200000L, result)
    }

    @Test
    fun `2 days before in PST fires at 9 AM`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-P2D")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Jan 4 09:00 PST = Jan 4 17:00 UTC
        assertEquals(1736010000000L, result)
    }

    @Test
    fun `1 week before in PST fires at 9 AM`() {
        // Jan 13, 2025 00:00 UTC
        val utcMidnight = 1736726400000L
        val offset = parseReminderOffset("-P1W")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Jan 6 09:00 PST = Jan 6 17:00 UTC
        assertEquals(1736182800000L, result)
    }

    // ==================== DST transition edge cases ====================

    @Test
    fun `DST spring forward - 9 AM day of event in PST to PDT transition`() {
        // March 9, 2025: PST → PDT (2 AM jumps to 3 AM)
        // All-day event on March 9, 2025
        // March 9, 2025 00:00 UTC
        val marchUtcMidnight = 1741478400000L
        val offset = parseReminderOffset("-PT9H")!!

        val result = calculateAllDayTriggerTime(marchUtcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // March 9 09:00 PDT = March 9 16:00 UTC (7hr offset after DST)
        assertEquals(1741536000000L, result)
    }

    @Test
    fun `DST fall back - 9 AM day of event in PDT to PST transition`() {
        // Nov 2, 2025: PDT → PST (2 AM falls back to 1 AM)
        // Nov 2, 2025 is Sunday (first Sunday of November = DST end)
        // Nov 2, 2025 00:00 UTC
        val novUtcMidnight = 1762041600000L
        val offset = parseReminderOffset("-PT9H")!!

        val result = calculateAllDayTriggerTime(novUtcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // Nov 2 09:00 PST = Nov 2 17:00 UTC (PST is UTC-8 after fall back)
        assertEquals(1762102800000L, result)
    }

    @Test
    fun `DST spring forward - 1 day before fires at correct 9 AM`() {
        // Event on March 10, 2025 (day after DST spring forward)
        // March 10, 2025 00:00 UTC
        val marchUtcMidnight = 1741564800000L
        val offset = parseReminderOffset("-P1D")!!

        val result = calculateAllDayTriggerTime(marchUtcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // March 9 09:00 PDT = March 9 16:00 UTC
        // Note: March 9 is the DST transition day, but 9 AM is after 2 AM so it's PDT
        assertEquals(1741536000000L, result)
    }

    // ==================== Edge cases ====================

    @Test
    fun `zero offset - fires at 9 AM for all-day events`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT0M")!!  // Returns 0

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // For all-day events, zero offset means "day of event" which fires at 9 AM local
        // (same as "-P0D" would behave - day-based offset defaults to 9 AM)
        // Jan 6 09:00 PST = Jan 6 17:00 UTC = 1736182800000
        assertEquals(1736182800000L, result)
    }

    @Test
    fun `UTC timezone - no offset difference`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("UTC"))

        // Jan 6 09:00 UTC
        assertEquals(1736154000000L, result)
    }

    // ==================== Half-hour timezone offsets ====================

    @Test
    fun `9 AM day of event in India (IST UTC+530)`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Kolkata"))

        // Jan 6 09:00 IST = Jan 6 03:30 UTC
        assertEquals(1736134200000L, result)
    }

    @Test
    fun `1 day before in India fires at 9 AM`() {
        // Jan 6, 2025 00:00 UTC
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-P1D")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Kolkata"))

        // Jan 5 09:00 IST = Jan 5 03:30 UTC
        assertEquals(1736047800000L, result)
    }
}
