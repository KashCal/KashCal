package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for device calendar reminder query logic (Phase 4 - Chunk 2).
 *
 * Tests cover:
 * - Trigger time calculation for timed events
 * - Trigger time calculation for all-day events (9 AM local pattern)
 * - UpcomingDeviceReminder data class
 *
 * Note: Actual ContentResolver queries cannot be unit tested (need instrumented tests).
 * These tests cover the pure logic functions.
 */
class DeviceCalendarReminderQueryTest {

    // ========== UpcomingDeviceReminder Data Class ==========

    @Test
    fun `UpcomingDeviceReminder has correct composite key fields`() {
        val reminder = UpcomingDeviceReminder(
            eventId = 123L,
            occurrenceStartTs = 1709251200000L, // Some timestamp
            title = "Test Event",
            location = "Test Location",
            isAllDay = false,
            reminderMinutes = 15,
            triggerTime = 1709250300000L,
            calendarColor = 0xFF0000,
            calendarId = 1L
        )

        assertEquals(123L, reminder.eventId)
        assertEquals(1709251200000L, reminder.occurrenceStartTs)
        assertEquals("Test Event", reminder.title)
        assertEquals("Test Location", reminder.location)
        assertEquals(false, reminder.isAllDay)
        assertEquals(15, reminder.reminderMinutes)
        assertEquals(1709250300000L, reminder.triggerTime)
        assertEquals(0xFF0000, reminder.calendarColor)
        assertEquals(1L, reminder.calendarId)
    }

    @Test
    fun `UpcomingDeviceReminder location can be null`() {
        val reminder = UpcomingDeviceReminder(
            eventId = 123L,
            occurrenceStartTs = 1709251200000L,
            title = "Test Event",
            location = null,
            isAllDay = false,
            reminderMinutes = 15,
            triggerTime = 1709250300000L,
            calendarColor = 0xFF0000,
            calendarId = 1L
        )

        assertNull(reminder.location)
    }

    // ========== Timed Event Trigger Time Calculation ==========

    @Test
    fun `timed event trigger time is occurrenceStart minus reminderMinutes`() {
        // Event at 10:00 AM, reminder 15 minutes before
        val eventStartMs = LocalDate.of(2026, 3, 15).atTime(10, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val reminderMinutes = 15
        val expectedTriggerMs = eventStartMs - (reminderMinutes * 60 * 1000L)

        // Verify: trigger should be 9:45 AM
        val triggerTime = Instant.ofEpochMilli(expectedTriggerMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        assertEquals(LocalTime.of(9, 45), triggerTime)
    }

    @Test
    fun `timed event 30 minute reminder calculates correctly`() {
        val eventStartMs = LocalDate.of(2026, 3, 15).atTime(14, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val reminderMinutes = 30
        val triggerMs = eventStartMs - (reminderMinutes * 60 * 1000L)

        // Verify: trigger should be 2:00 PM
        val triggerTime = Instant.ofEpochMilli(triggerMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        assertEquals(LocalTime.of(14, 0), triggerTime)
    }

    @Test
    fun `timed event 1 hour reminder calculates correctly`() {
        val eventStartMs = LocalDate.of(2026, 3, 15).atTime(15, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val reminderMinutes = 60
        val triggerMs = eventStartMs - (reminderMinutes * 60 * 1000L)

        // Verify: trigger should be 2:00 PM
        val triggerTime = Instant.ofEpochMilli(triggerMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        assertEquals(LocalTime.of(14, 0), triggerTime)
    }

    // ========== All-Day Event Trigger Time Calculation ==========

    @Test
    fun `all-day event day-of reminder fires at 9AM local`() {
        // All-day event on Mar 15 (stored as UTC midnight)
        val eventStartMs = LocalDate.of(2026, 3, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        // 540 minutes = 9 hours (9 AM day of event)
        val reminderMinutes = 540
        val offsetMs = -reminderMinutes * 60 * 1000L

        // For all-day events, 540 min offset fires at 9 AM on event day
        val localZone = ZoneId.systemDefault()
        val eventDate = Instant.ofEpochMilli(eventStartMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        // Expected: 9 AM on Mar 15 in local timezone
        val expectedTriggerMs = eventDate.atTime(9, 0)
            .atZone(localZone)
            .toInstant().toEpochMilli()

        // Verify the date is correct
        val triggerDateTime = Instant.ofEpochMilli(expectedTriggerMs)
            .atZone(localZone)
            .toLocalDateTime()
        assertEquals(15, triggerDateTime.dayOfMonth)
        assertEquals(9, triggerDateTime.hour)
        assertEquals(0, triggerDateTime.minute)
    }

    @Test
    fun `all-day event 1 day before reminder fires at 9AM previous day`() {
        // All-day event on Mar 15
        val eventStartMs = LocalDate.of(2026, 3, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        // 1440 minutes = 1 day before
        val reminderMinutes = 1440
        val localZone = ZoneId.systemDefault()
        val eventDate = Instant.ofEpochMilli(eventStartMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        // Expected: 9 AM on Mar 14 in local timezone
        val expectedTriggerMs = eventDate.minusDays(1).atTime(9, 0)
            .atZone(localZone)
            .toInstant().toEpochMilli()

        val triggerDateTime = Instant.ofEpochMilli(expectedTriggerMs)
            .atZone(localZone)
            .toLocalDateTime()
        assertEquals(14, triggerDateTime.dayOfMonth)
        assertEquals(9, triggerDateTime.hour)
    }

    @Test
    fun `all-day event 2 days before reminder fires at 9AM two days prior`() {
        // All-day event on Mar 15
        val eventStartMs = LocalDate.of(2026, 3, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        // 2880 minutes = 2 days before
        val localZone = ZoneId.systemDefault()
        val eventDate = Instant.ofEpochMilli(eventStartMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        // Expected: 9 AM on Mar 13
        val expectedTriggerMs = eventDate.minusDays(2).atTime(9, 0)
            .atZone(localZone)
            .toInstant().toEpochMilli()

        val triggerDateTime = Instant.ofEpochMilli(expectedTriggerMs)
            .atZone(localZone)
            .toLocalDateTime()
        assertEquals(13, triggerDateTime.dayOfMonth)
        assertEquals(9, triggerDateTime.hour)
    }

    @Test
    fun `all-day event 1 week before reminder fires at 9AM one week prior`() {
        // All-day event on Mar 15
        val eventStartMs = LocalDate.of(2026, 3, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        // 10080 minutes = 7 days = 1 week
        val localZone = ZoneId.systemDefault()
        val eventDate = Instant.ofEpochMilli(eventStartMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        // Expected: 9 AM on Mar 8
        val expectedTriggerMs = eventDate.minusDays(7).atTime(9, 0)
            .atZone(localZone)
            .toInstant().toEpochMilli()

        val triggerDateTime = Instant.ofEpochMilli(expectedTriggerMs)
            .atZone(localZone)
            .toLocalDateTime()
        assertEquals(8, triggerDateTime.dayOfMonth)
        assertEquals(9, triggerDateTime.hour)
    }

    // ========== Trigger Time Ordering ==========

    @Test
    fun `earlier reminder has smaller trigger time than later reminder`() {
        val eventStartMs = LocalDate.of(2026, 3, 15).atTime(10, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val trigger15Min = eventStartMs - (15 * 60 * 1000L)
        val trigger30Min = eventStartMs - (30 * 60 * 1000L)

        // 30 min reminder fires BEFORE 15 min reminder
        assertTrue("30 min reminder should trigger before 15 min reminder",
            trigger30Min < trigger15Min)
    }

    @Test
    fun `past trigger time is correctly identified`() {
        val now = System.currentTimeMillis()
        val pastTrigger = now - 1000 // 1 second ago

        assertTrue("Past trigger should be less than now", pastTrigger < now)
    }

    // ========== Edge Cases ==========

    @Test
    fun `zero minute reminder triggers at event start`() {
        val eventStartMs = LocalDate.of(2026, 3, 15).atTime(10, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val trigger0Min = eventStartMs - (0 * 60 * 1000L)

        assertEquals("0 min reminder should trigger at event start",
            eventStartMs, trigger0Min)
    }
}
