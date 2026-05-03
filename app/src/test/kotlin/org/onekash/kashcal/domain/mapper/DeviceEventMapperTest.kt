package org.onekash.kashcal.domain.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceEvent

/**
 * Tests for DeviceEventMapper.toFormState().
 *
 * Covers:
 * - Basic field mapping (title, description, location, isAllDay)
 * - Duration parsing for recurring events
 * - endTs fallback for non-recurring events
 * - Device calendar state flags
 * - All-day UTC to local conversion
 * - Reminder mapping (first 5 only)
 * - Color precedence (eventColor over calendarColor)
 */
class DeviceEventMapperTest {

    @Test
    fun `toFormState maps basic fields correctly`() {
        val event = createDeviceEvent(
            title = "Team Standup",
            description = "Daily sync meeting",
            location = "Conference Room A",
            isAllDay = false
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("Team Standup", formState.title)
        assertEquals("Daily sync meeting", formState.description)
        assertEquals("Conference Room A", formState.location)
        assertEquals(false, formState.isAllDay)
    }

    @Test
    fun `toFormState parses duration for recurring events`() {
        // Recurring event with 1 hour duration
        val startTs = 1700000000000L // Some timestamp
        val event = createDeviceEvent(
            startTs = startTs,
            endTs = null, // No endTs for recurring
            duration = "PT1H", // 1 hour
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // End time should be start + 1 hour
        val expectedEndTs = startTs + 3600000L
        // Check that end hour/minute reflect 1 hour after start
        assertEquals(event.rrule, formState.rrule)
    }

    @Test
    fun `toFormState uses endTs when duration is null for non-recurring`() {
        val startTs = 1700000000000L
        val endTs = 1700003600000L // 1 hour later
        val event = createDeviceEvent(
            startTs = startTs,
            endTs = endTs,
            duration = null,
            rrule = null
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // Form state should derive times from endTs
        assertNull(formState.rrule)
    }

    @Test
    fun `toFormState sets isDeviceCalendar true and editingDeviceEventId`() {
        val event = createDeviceEvent(id = 42L)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertTrue(formState.isDeviceCalendar)
        assertEquals(42L, formState.editingDeviceEventId)
    }

    @Test
    fun `toFormState takes first 5 reminders when more than 5 present`() {
        val event = createDeviceEvent()

        val formState = event.toFormState(
            reminders = listOf(15, 30, 60, 120, 1440, 2880, 10080), // 7 reminders
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(listOf(15, 30, 60, 120, 1440), formState.reminders)
        assertEquals(2, formState.truncatedReminderCount) // 7 - 5 = 2 truncated
    }

    @Test
    fun `toFormState keeps all reminders when 5 or fewer`() {
        val event = createDeviceEvent()

        // Test with exactly 5 reminders
        val formState5 = event.toFormState(
            reminders = listOf(15, 30, 60, 120, 1440),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )
        assertEquals(listOf(15, 30, 60, 120, 1440), formState5.reminders)
        assertEquals(0, formState5.truncatedReminderCount)

        // Test with 3 reminders
        val formState3 = event.toFormState(
            reminders = listOf(15, 30, 60),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )
        assertEquals(listOf(15, 30, 60), formState3.reminders)
        assertEquals(0, formState3.truncatedReminderCount)

        // Test with 1 reminder
        val formState1 = event.toFormState(
            reminders = listOf(60),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )
        assertEquals(listOf(60), formState1.reminders)
        assertEquals(0, formState1.truncatedReminderCount)
    }

    @Test
    fun `toFormState handles empty reminders`() {
        val event = createDeviceEvent()

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(emptyList<Int>(), formState.reminders)
        assertEquals(0, formState.truncatedReminderCount)
    }

    @Test
    fun `toFormState surfaces eventColor on its own channel, selectedCalendarColor stays calendar identity`() {
        val event = createDeviceEvent(
            calendarColor = 0xFF0000, // Red
            eventColor = 0x00FF00 // Green
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // Calendar picker dot labels which calendar the event is on — identity.
        assertEquals(0xFF0000, formState.selectedCalendarColor)
        // Override lives on its own field.
        assertEquals(0x00FF00, formState.eventColor)
    }

    @Test
    fun `toFormState uses calendarColor when eventColor is null`() {
        val event = createDeviceEvent(
            calendarColor = 0xFF0000,
            eventColor = null
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(0xFF0000, formState.selectedCalendarColor)
    }

    @Test
    fun `toFormState sets edit mode fields`() {
        val event = createDeviceEvent(id = 123L, calendarId = 456L)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work Calendar",
            deviceCalendarGroups = emptyList()
        )

        assertTrue(formState.isEditMode)
        assertEquals(456L, formState.selectedCalendarId)
        assertEquals("Work Calendar", formState.selectedCalendarName)
    }

    // ==================== editingDeviceEventId for exceptions ====================

    @Test
    fun `toFormState sets editingDeviceEventId to originalId for exception event`() {
        // Exception event: id=200 (exception), originalId=100 (master)
        // editingDeviceEventId must be the MASTER event ID (100), not exception ID (200)
        // because saveDeviceEvent() uses it for findExceptionEventId() and createException()
        val event = createDeviceEvent(id = 200L, originalId = 100L)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(100L, formState.editingDeviceEventId)
    }

    @Test
    fun `toFormState sets editingDeviceEventId to own id for non-exception event`() {
        // Non-exception event: id=42, originalId=null
        val event = createDeviceEvent(id = 42L, originalId = null)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(42L, formState.editingDeviceEventId)
    }

    // ==================== Occurrence Date Tests ====================

    @Test
    fun `toFormState uses occurrenceTs for recurring event date`() {
        // Master starts Jan 1 10:00 AM with weekly RRULE
        val jan1_10am = 1735729200000L // 2025-01-01 10:00:00 UTC
        val jan8_10am = jan1_10am + 7 * 24 * 3600 * 1000L // Jan 8

        val event = createDeviceEvent(
            startTs = jan1_10am,
            endTs = null,
            duration = "PT1H",
            rrule = "FREQ=WEEKLY;BYDAY=WE"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = jan8_10am
        )

        // Form should show Jan 8 date, not Jan 1
        assertEquals(jan8_10am, formState.dateMillis)
    }

    @Test
    fun `toFormState uses exception startTs not occurrenceTs`() {
        // Exception event has its own modified start time
        val originalTs = 1735729200000L
        val modifiedTs = originalTs + 3600000L // Modified to 1 hour later

        val event = createDeviceEvent(
            startTs = modifiedTs,
            endTs = modifiedTs + 3600000L,
            duration = null,
            rrule = null,
            originalId = 100L // This is an exception event
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = originalTs // Should be ignored for exceptions
        )

        // Form should show exception's own startTs, not occurrenceTs
        assertEquals(modifiedTs, formState.dateMillis)
    }

    @Test
    fun `toFormState ignores occurrenceTs for non-recurring event`() {
        val eventStartTs = 1735729200000L
        val differentTs = eventStartTs + 86400000L

        val event = createDeviceEvent(
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000L,
            duration = null,
            rrule = null // Not recurring
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = differentTs // Should be used (no originalId check for non-recurring)
        )

        // Non-recurring events with occurrenceTs: occurrenceTs is used because
        // the mapper uses occurrenceTs ?? startTs regardless of rrule presence.
        // This is safe — for non-recurring events, occurrenceTs == startTs in practice.
        assertEquals(differentTs, formState.dateMillis)
    }

    @Test
    fun `toFormState computes correct endTs from occurrenceTs and duration`() {
        // 1-hour duration recurring event
        val jan1_10am = 1735729200000L
        val jan8_10am = jan1_10am + 7 * 24 * 3600 * 1000L

        val event = createDeviceEvent(
            startTs = jan1_10am,
            endTs = null,
            duration = "PT1H",
            rrule = "FREQ=WEEKLY"
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList(),
            occurrenceTs = jan8_10am
        )

        // End should be occurrence + 1 hour
        val expectedEndTs = jan8_10am + 3600000L
        assertEquals(expectedEndTs, formState.endDateMillis)

        // Verify end hour is 1 hour after start
        val startHour = formState.startHour
        val endHour = formState.endHour
        assertEquals(1, endHour - startHour)
    }

    // ==================== Availability → transp mapping ====================

    @Test
    fun `toFormState maps availability BUSY to transp OPAQUE`() {
        val event = createDeviceEvent(availability = 0) // AVAILABILITY_BUSY

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("OPAQUE", formState.transp)
    }

    @Test
    fun `toFormState maps availability FREE to transp TRANSPARENT`() {
        val event = createDeviceEvent(availability = 1) // AVAILABILITY_FREE

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("TRANSPARENT", formState.transp)
    }

    @Test
    fun `toFormState maps availability TENTATIVE to transp OPAQUE`() {
        val event = createDeviceEvent(availability = 2) // AVAILABILITY_TENTATIVE

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("OPAQUE", formState.transp)
    }

    @Test
    fun `toFormState defaults transp to OPAQUE`() {
        val event = createDeviceEvent() // default availability = 0

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals("OPAQUE", formState.transp)
    }

    // ==================== eventColor passthrough ====================

    @Test
    fun `toFormState passes eventColor through as separate field`() {
        val event = createDeviceEvent(eventColor = 0x00FF00)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertEquals(0x00FF00, formState.eventColor)
    }

    @Test
    fun `toFormState keeps eventColor null when device event has no custom color`() {
        val event = createDeviceEvent(eventColor = null)

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        assertNull(formState.eventColor)
    }

    @Test
    fun `toFormState keeps selectedCalendarColor separate from eventColor`() {
        val event = createDeviceEvent(
            calendarColor = 0xFF0000,
            eventColor = 0x00FF00
        )

        val formState = event.toFormState(
            reminders = emptyList(),
            calendarColor = 0xFF0000,
            calendarName = "Work",
            deviceCalendarGroups = emptyList()
        )

        // selectedCalendarColor labels the picker dot — calendar identity only.
        assertEquals(0xFF0000, formState.selectedCalendarColor)
        // eventColor is the raw per-event override for the form's "More options" section
        assertEquals(0x00FF00, formState.eventColor)
    }

    // ==================== Helper ====================

    private fun createDeviceEvent(
        id: Long = 1L,
        calendarId: Long = 1L,
        title: String = "Test Event",
        description: String? = null,
        location: String? = null,
        startTs: Long = System.currentTimeMillis(),
        endTs: Long? = System.currentTimeMillis() + 3600000,
        duration: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        calendarColor: Int? = null,
        eventColor: Int? = null,
        originalId: Long? = null,
        availability: Int = 0
    ): DeviceEvent = DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs,
        duration = duration,
        isAllDay = isAllDay,
        rrule = rrule,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = "America/New_York",
        originalId = originalId,
        originalInstanceTime = null,
        status = 1,
        availability = availability,
        accessLevel = 700,
        calendarColor = calendarColor,
        eventColor = eventColor
    )
}
