package org.onekash.kashcal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        rrule = "FREQ=WEEKLY;BYDAY=MO",
        reminders = listOf(15, 60),
        calendarId = 5L,
        calendarDisplayName = "Device Calendar",
        calendarColor = 0xFFFF5722.toInt(),
        eventColor = null,
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = "America/New_York"
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
        assertNull(display.eventColor)
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
        assertNull(display.eventColor)
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
    fun `Device variant calendarColor reads instance calendarColor field`() {
        val display = DisplayEvent.Device(testInstance)
        assertEquals(0xFFFF5722.toInt(), display.calendarColor)
        assertNull(display.eventColor)
    }

    @Test
    fun `Device variant eventColor reflects instance eventColor override`() {
        val withOverride = testInstance.copy(eventColor = 0xFFFF0000.toInt())
        val display = DisplayEvent.Device(withOverride)
        assertEquals(0xFFFF5722.toInt(), display.calendarColor)
        assertEquals(0xFFFF0000.toInt(), display.eventColor)
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

    // ========== Color Channel Split (Room) ==========

    @Test
    fun `Room calendarColor reflects calendar even when event has override`() {
        val eventWithColor = testEvent.copy(color = 0xFF00FF00.toInt())
        val display = DisplayEvent.Room(eventWithColor, testOccurrence, testCalendar)
        assertEquals(0xFF2196F3.toInt(), display.calendarColor)   // calendar's color — override ignored
        assertEquals(0xFF00FF00.toInt(), display.eventColor)      // override surfaced via eventColor
    }

    @Test
    fun `Room calendarColor matches calendar when event has no override`() {
        val eventNoColor = testEvent.copy(color = null)
        val display = DisplayEvent.Room(eventNoColor, testOccurrence, testCalendar)
        assertEquals(0xFF2196F3.toInt(), display.calendarColor)
        assertNull(display.eventColor)
    }

    @Test
    fun `Room calendarColor returns 0 when calendar is null`() {
        val eventNoColor = testEvent.copy(color = null)
        val display = DisplayEvent.Room(eventNoColor, testOccurrence, null)
        assertEquals(0, display.calendarColor)
        assertNull(display.eventColor)
    }

    @Test
    fun `Room with override and null calendar surfaces override on eventColor`() {
        val eventWithColor = testEvent.copy(color = 0xFF00FF00.toInt())
        val display = DisplayEvent.Room(eventWithColor, testOccurrence, null)
        assertEquals(0, display.calendarColor)
        assertEquals(0xFF00FF00.toInt(), display.eventColor)
    }

    // ========== isFree Property ==========

    @Test
    fun `Room isFree returns true when transp is TRANSPARENT`() {
        val freeEvent = testEvent.copy(transp = "TRANSPARENT")
        val display = DisplayEvent.Room(freeEvent, testOccurrence, testCalendar)
        assertTrue(display.isFree)
    }

    @Test
    fun `Room isFree returns false when transp is OPAQUE`() {
        val busyEvent = testEvent.copy(transp = "OPAQUE")
        val display = DisplayEvent.Room(busyEvent, testOccurrence, testCalendar)
        assertFalse(display.isFree)
    }

    @Test
    fun `Room isFree defaults to false`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertFalse(display.isFree)
    }

    @Test
    fun `Device isFree returns true when availability is FREE`() {
        val freeInstance = testInstance.copy(availability = 1)
        val display = DisplayEvent.Device(freeInstance)
        assertTrue(display.isFree)
    }

    @Test
    fun `Device isFree returns false when availability is BUSY`() {
        val busyInstance = testInstance.copy(availability = 0)
        val display = DisplayEvent.Device(busyInstance)
        assertFalse(display.isFree)
    }

    @Test
    fun `Device isFree returns false when availability is TENTATIVE`() {
        val tentativeInstance = testInstance.copy(availability = 2)
        val display = DisplayEvent.Device(tentativeInstance)
        assertFalse(display.isFree)
    }
}
