package org.onekash.kashcal.util

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.domain.model.DisplayEvent

/**
 * Tests for device event ICS export.
 */
class DeviceEventIcsExportTest {

    private fun createInstance(
        eventId: Long = 100L,
        title: String = "Team Meeting",
        description: String? = "Weekly standup",
        location: String? = "Room 101",
        startTs: Long = 1709740800000L, // 2024-03-06 12:00 UTC
        endTs: Long = 1709744400000L,   // 2024-03-06 13:00 UTC
        isAllDay: Boolean = false,
        rrule: String? = null
    ) = DeviceCalendarInstance(
        instanceId = 1L,
        eventId = eventId,
        title = title,
        description = description ?: "",
        location = location ?: "",
        startTs = startTs,
        endTs = endTs,
        startDay = 20240306,
        endDay = 20240306,
        isAllDay = isAllDay,
        hasRrule = rrule != null,
        rrule = rrule,
        reminders = emptyList(),
        calendarId = 1L,
        calendarDisplayName = "Calendar",
        displayColor = 0xFF0000,
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = "America/New_York"
    )

    @Test
    fun `buildIcsFromDeviceEvent produces valid VCALENDAR wrapper`() {
        val device = DisplayEvent.Device(createInstance())
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("PRODID:"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
    }

    @Test
    fun `buildIcsFromDeviceEvent includes VEVENT with UID`() {
        val device = DisplayEvent.Device(createInstance(eventId = 123L))
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("UID:device-123@kashcal"))
        assertTrue(ics.contains("END:VEVENT"))
    }

    @Test
    fun `buildIcsFromDeviceEvent includes DTSTART and DTEND`() {
        val device = DisplayEvent.Device(createInstance())
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("DTSTART:"))
        assertTrue(ics.contains("DTEND:"))
    }

    @Test
    fun `buildIcsFromDeviceEvent handles all-day with VALUE DATE`() {
        val device = DisplayEvent.Device(createInstance(
            startTs = 1709683200000L, // 2024-03-06 00:00 UTC
            endTs = 1709769599999L,   // 2024-03-06 23:59:59 UTC
            isAllDay = true
        ))
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("DTSTART;VALUE=DATE:"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:"))
    }

    @Test
    fun `buildIcsFromDeviceEvent includes SUMMARY with title`() {
        val device = DisplayEvent.Device(createInstance(title = "Important Meeting"))
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("SUMMARY:Important Meeting"))
    }

    @Test
    fun `buildIcsFromDeviceEvent includes RRULE when present`() {
        val device = DisplayEvent.Device(createInstance(rrule = "FREQ=WEEKLY;BYDAY=MO"))
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"))
    }

    @Test
    fun `buildIcsFromDeviceEvent includes DESCRIPTION when present`() {
        val device = DisplayEvent.Device(createInstance(description = "Meeting notes"))
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("DESCRIPTION:Meeting notes"))
    }

    @Test
    fun `buildIcsFromDeviceEvent includes LOCATION when present`() {
        val device = DisplayEvent.Device(createInstance(location = "Conference Room A"))
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue(ics.contains("LOCATION:Conference Room A"))
    }
}
