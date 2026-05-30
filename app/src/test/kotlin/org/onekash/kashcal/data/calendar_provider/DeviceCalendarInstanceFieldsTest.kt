package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DeviceCalendarInstance RRULE and reminders fields.
 */
class DeviceCalendarInstanceFieldsTest {

    private fun createInstance(
        rrule: String? = null,
        reminders: List<Int> = emptyList()
    ) = DeviceCalendarInstance(
        instanceId = 1L,
        eventId = 100L,
        title = "Test Event",
        description = "Description",
        location = "Location",
        startTs = 1000L,
        endTs = 2000L,
        startDay = 20260306,
        endDay = 20260306,
        isAllDay = false,
        hasRrule = rrule != null,
        rrule = rrule,
        reminders = reminders,
        calendarId = 1L,
        calendarDisplayName = "Calendar",
        calendarColor = 0xFF0000,
        eventColor = null,
        status = 1,
        availability = 0,
        hasAlarm = reminders.isNotEmpty(),
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = "America/New_York",
        eventStartTs = 1000L,
    )

    @Test
    fun `instance stores rrule string when present`() {
        val instance = createInstance(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", instance.rrule)
        assertTrue(instance.hasRrule)
    }

    @Test
    fun `instance stores null rrule when not present`() {
        val instance = createInstance(rrule = null)
        assertNull(instance.rrule)
        assertTrue(!instance.hasRrule)
    }

    @Test
    fun `instance stores reminders list when present`() {
        val instance = createInstance(reminders = listOf(15, 60, 1440))
        assertEquals(listOf(15, 60, 1440), instance.reminders)
        assertTrue(instance.hasAlarm)
    }

    @Test
    fun `instance stores empty reminders list when none present`() {
        val instance = createInstance(reminders = emptyList())
        assertTrue(instance.reminders.isEmpty())
    }
}
