package org.onekash.kashcal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance

/**
 * Tests for DisplayEvent.Device rrule and reminders properties.
 */
class DisplayEventDeviceTest {

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
        timezone = "America/New_York"
    )

    @Test
    fun `Device rrule delegates to instance rrule`() {
        val device = DisplayEvent.Device(createInstance(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", device.rrule)
    }

    @Test
    fun `Device reminders delegates to instance reminders`() {
        val device = DisplayEvent.Device(createInstance(reminders = listOf(15, 60, 1440)))
        assertEquals(listOf(15, 60, 1440), device.reminders)
    }

    @Test
    fun `Device with null rrule returns null`() {
        val device = DisplayEvent.Device(createInstance(rrule = null))
        assertNull(device.rrule)
    }

    @Test
    fun `Device with empty reminders returns empty list`() {
        val device = DisplayEvent.Device(createInstance(reminders = emptyList()))
        assertTrue(device.reminders.isEmpty())
    }
}
