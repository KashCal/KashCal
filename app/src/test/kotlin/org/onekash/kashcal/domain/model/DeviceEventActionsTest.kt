package org.onekash.kashcal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance

/**
 * Tests for device event action helpers: toEventForDuplicate() and buildShareText().
 */
class DeviceEventActionsTest {

    private fun createTestInstance(
        title: String = "Team Meeting",
        description: String = "Weekly standup",
        location: String = "Conference Room A",
        startTs: Long = 1700000000000L,
        endTs: Long = 1700003600000L,
        isAllDay: Boolean = false,
        hasRrule: Boolean = false
    ) = DeviceCalendarInstance(
        instanceId = 1L,
        eventId = 100L,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs,
        startDay = 20231114,
        endDay = 20231114,
        isAllDay = isAllDay,
        hasRrule = hasRrule,
        calendarId = 5L,
        calendarDisplayName = "Work Calendar",
        displayColor = 0xFF1976D2.toInt(),
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true
    )

    // ========== toEventForDuplicate ==========

    @Test
    fun `toEventForDuplicate maps Device properties to Event correctly`() {
        val device = DisplayEvent.Device(createTestInstance(
            title = "Team Meeting",
            description = "Weekly standup",
            location = "Conference Room A",
            startTs = 1700000000000L,
            endTs = 1700003600000L,
            isAllDay = false
        ))

        val event = device.toEventForDuplicate()

        assertEquals("Team Meeting", event.title)
        assertEquals("Weekly standup", event.description)
        assertEquals("Conference Room A", event.location)
        assertEquals(1700000000000L, event.startTs)
        assertEquals(1700003600000L, event.endTs)
        assertFalse(event.isAllDay)
    }

    @Test
    fun `toEventForDuplicate uses placeholder UID and calendarId 0`() {
        val device = DisplayEvent.Device(createTestInstance())

        val event = device.toEventForDuplicate()

        assertNotNull("UID should be non-null", event.uid)
        assertTrue("UID should be non-empty", event.uid.isNotEmpty())
        assertEquals("calendarId should be 0 (form assigns real calendar)", 0L, event.calendarId)
    }

    @Test
    fun `toEventForDuplicate maps all-day event correctly`() {
        val device = DisplayEvent.Device(createTestInstance(isAllDay = true))

        val event = device.toEventForDuplicate()

        assertTrue(event.isAllDay)
    }

    // ========== buildShareText ==========

    @Test
    fun `buildShareText includes title and location for timed event`() {
        val device = DisplayEvent.Device(createTestInstance(
            title = "Team Meeting",
            location = "Conference Room A"
        ))

        val text = device.buildShareText(timePattern = "h:mm a")

        assertTrue("Should contain title", text.contains("Team Meeting"))
        assertTrue("Should contain location", text.contains("Location: Conference Room A"))
        assertTrue("Should contain footer", text.contains("Shared from KashCal"))
    }

    @Test
    fun `buildShareText formats all-day event with All day label`() {
        val device = DisplayEvent.Device(createTestInstance(isAllDay = true))

        val text = device.buildShareText(timePattern = "h:mm a")

        assertTrue("Should contain 'All day'", text.contains("All day"))
    }

    @Test
    fun `buildShareText omits location when empty`() {
        val device = DisplayEvent.Device(createTestInstance(location = ""))

        val text = device.buildShareText(timePattern = "h:mm a")

        assertFalse("Should not contain Location line", text.contains("Location:"))
    }
}
