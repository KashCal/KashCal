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
        reminders: List<Int> = emptyList(),
        categories: List<String> = emptyList()
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
        categories = categories,
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

    @Test
    fun `Device categories delegates to instance categories`() {
        val device = DisplayEvent.Device(createInstance(categories = listOf("Work", "Personal")))
        assertEquals(listOf("Work", "Personal"), device.categories)
    }

    @Test
    fun `Device with no categories returns empty list`() {
        val device = DisplayEvent.Device(createInstance())
        assertTrue(device.categories.isEmpty())
    }

    @Test
    fun `an in-memory tag filter includes device events by the same predicate as room events`() {
        // A DisplayEvent tag filter operates over the shared `categories`
        // surface with no source-specific branch. A mixed list must partition
        // by tag membership identically for Room and Device.
        val taggedDevice: DisplayEvent = DisplayEvent.Device(createInstance(categories = listOf("Work")))
        val untaggedDevice: DisplayEvent = DisplayEvent.Device(createInstance(categories = emptyList()))

        val filtered = listOf(taggedDevice, untaggedDevice)
            .filter { "Work" in it.categories }

        assertEquals(listOf(taggedDevice), filtered)
    }
}
