package org.onekash.kashcal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence

/**
 * Unit tests for DisplayEvent sealed interface.
 *
 * Tests property delegation for both Room and Device variants.
 */
class DisplayEventTest {

    // ========== Test Fixtures ==========

    private val testEvent = Event(
        id = 1L,
        uid = "test-uid@kashcal",
        calendarId = 10L,
        title = "Team Standup",
        location = "Room 301",
        description = "Daily standup meeting",
        startTs = 1700000000000L,
        endTs = 1700003600000L,
        dtstamp = 1700000000000L,
        isAllDay = false,
        rrule = "FREQ=DAILY;COUNT=5"
    )

    private val testOccurrence = Occurrence(
        id = 100L,
        eventId = 1L,
        calendarId = 10L,
        startTs = 1700086400000L,
        endTs = 1700090000000L,
        startDay = 20231116,
        endDay = 20231116
    )

    private val testCalendar = Calendar(
        id = 10L,
        accountId = 1L,
        caldavUrl = "https://example.com/cal/",
        displayName = "Work Calendar",
        color = 0xFF2196F3.toInt(),
        isReadOnly = false
    )

    private val testInstance = DeviceCalendarInstance(
        instanceId = 200L,
        eventId = 50L,
        title = "External Meeting",
        description = "Sync adapter event",
        location = "Conference Room B",
        startTs = 1700100000000L,
        endTs = 1700103600000L,
        startDay = 20231117,
        endDay = 20231117,
        isAllDay = false,
        hasRrule = true,
        calendarId = 5L,
        calendarDisplayName = "Device Calendar",
        displayColor = 0xFFFF5722.toInt(),
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true
    )

    // ========== Room Variant Tests ==========

    @Test
    fun `Room variant delegates title to Event`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals("Team Standup", display.title)
    }

    @Test
    fun `Room variant delegates description to Event`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals("Daily standup meeting", display.description)
    }

    @Test
    fun `Room variant delegates location to Event`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals("Room 301", display.location)
    }

    @Test
    fun `Room variant delegates startTs to Occurrence`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals(testOccurrence.startTs, display.startTs)
    }

    @Test
    fun `Room variant delegates endTs to Occurrence`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals(testOccurrence.endTs, display.endTs)
    }

    @Test
    fun `Room variant delegates startDay to Occurrence`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals(20231116, display.startDay)
    }

    @Test
    fun `Room variant delegates endDay to Occurrence`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals(20231116, display.endDay)
    }

    @Test
    fun `Room variant delegates isAllDay to Event`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertFalse(display.isAllDay)
    }

    @Test
    fun `Room variant hasRrule checks Event rrule not null`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertTrue(display.hasRrule)

        val noRruleEvent = testEvent.copy(rrule = null)
        val displayNoRrule = DisplayEvent.Room(noRruleEvent, testOccurrence, testCalendar)
        assertFalse(displayNoRrule.hasRrule)
    }

    @Test
    fun `Room variant delegates calendarColor to Calendar`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals(0xFF2196F3.toInt(), display.calendarColor)
    }

    @Test
    fun `Room variant delegates calendarName to Calendar`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertEquals("Work Calendar", display.calendarName)
    }

    @Test
    fun `Room variant isReadOnly matches Calendar isReadOnly`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertFalse(display.isReadOnly)

        val readOnlyCal = testCalendar.copy(isReadOnly = true)
        val readOnlyDisplay = DisplayEvent.Room(testEvent, testOccurrence, readOnlyCal)
        assertTrue(readOnlyDisplay.isReadOnly)
    }

    @Test
    fun `Room variant with null calendar uses defaults`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, null)
        assertEquals(0, display.calendarColor)
        assertEquals("", display.calendarName)
        assertFalse(display.isReadOnly)
    }

    // ========== Device Variant Tests ==========

    @Test
    fun `Device variant delegates title to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals("External Meeting", display.title)
    }

    @Test
    fun `Device variant delegates description to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals("Sync adapter event", display.description)
    }

    @Test
    fun `Device variant delegates location to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals("Conference Room B", display.location)
    }

    @Test
    fun `Device variant delegates startTs to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals(1700100000000L, display.startTs)
    }

    @Test
    fun `Device variant delegates endTs to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals(1700103600000L, display.endTs)
    }

    @Test
    fun `Device variant delegates day codes to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals(20231117, display.startDay)
        assertEquals(20231117, display.endDay)
    }

    @Test
    fun `Device variant delegates isAllDay to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertFalse(display.isAllDay)
    }

    @Test
    fun `Device variant delegates hasRrule to Instance`() {
        val display = DisplayEvent.Device(testInstance)
        assertTrue(display.hasRrule)
    }

    @Test
    fun `Device variant calendarColor uses displayColor`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals(0xFFFF5722.toInt(), display.calendarColor)
    }

    @Test
    fun `Device variant calendarName uses calendarDisplayName`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals("Device Calendar", display.calendarName)
    }

    @Test
    fun `Device variant isReadOnly is inverse of isWritable`() {
        val display = DisplayEvent.Device(testInstance)
        assertFalse(display.isReadOnly) // isWritable = true

        val readOnlyInstance = testInstance.copy(isWritable = false)
        val readOnlyDisplay = DisplayEvent.Device(readOnlyInstance)
        assertTrue(readOnlyDisplay.isReadOnly)
    }
}
