package org.onekash.kashcal.util

import org.junit.Assert.assertFalse
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

        // Timed event with TZID — post-migration, DTSTART/DTEND carry TZID= parameter.
        assertTrue("DTSTART missing:\n$ics", ics.contains("DTSTART;TZID="))
        assertTrue("DTEND missing:\n$ics", ics.contains("DTEND;TZID="))
    }

    @Test
    fun `buildIcsFromDeviceEvent emits TZID and VTIMEZONE for non-UTC timezone`() {
        val device = DisplayEvent.Device(createInstance())
        val ics = buildIcsFromDeviceEvent(device)

        assertTrue("Missing DTSTART with TZID parameter:\n$ics",
            ics.contains("DTSTART;TZID=America/New_York:"))
        assertTrue("Missing VTIMEZONE block for America/New_York:\n$ics",
            ics.contains("BEGIN:VTIMEZONE") && ics.contains("TZID:America/New_York"))

        // VTIMEZONE must appear before the VEVENT (RFC 5545 §3.6 ordering).
        val iVtimezone = ics.indexOf("BEGIN:VTIMEZONE")
        val iVevent = ics.indexOf("BEGIN:VEVENT")
        assertTrue("VTIMEZONE must precede VEVENT (got $iVtimezone vs $iVevent):\n$ics",
            iVtimezone in 0 until iVevent)
    }

    @Test
    fun `buildIcsFromDeviceEvent with non-IANA timezone falls back to UTC without VTIMEZONE`() {
        // Android CalendarProvider on some devices surfaces Windows TZIDs
        // (e.g. "Pacific Standard Time") or legacy "GMT+05:00". ZoneId.of() throws,
        // so we emit DTSTART as UTC (no TZID= parameter) with no VTIMEZONE block.
        // This is better than the pre-migration behavior (which forced UTC for ALL events).
        val device = DisplayEvent.Device(createInstance().copy(timezone = "Pacific Standard Time"))
        val ics = buildIcsFromDeviceEvent(device)

        // UTC form: DTSTART: with no TZID= parameter, ending in Z
        assertTrue("Expected UTC DTSTART, got:\n$ics",
            ics.lineSequence().any { it.startsWith("DTSTART:") && it.endsWith("Z") })
        // No VTIMEZONE emitted for the invalid TZID
        assertFalse("Should not emit VTIMEZONE for unparseable TZID:\n$ics",
            ics.contains("BEGIN:VTIMEZONE"))
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
