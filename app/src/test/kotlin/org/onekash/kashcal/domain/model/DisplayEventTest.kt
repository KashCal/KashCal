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
        timezone = "America/New_York",
        eventStartTs = 1700100000000L,
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

    // ========== isDeclinedByMe Property ==========

    @Test
    fun `Room isDeclinedByMe defaults to false`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar)
        assertFalse(display.isDeclinedByMe)
    }

    @Test
    fun `Room isDeclinedByMe is true when constructed with flag`() {
        val display = DisplayEvent.Room(testEvent, testOccurrence, testCalendar, isDeclinedByMe = true)
        assertTrue(display.isDeclinedByMe)
    }

    @Test
    fun `Device isDeclinedByMe is true when selfAttendeeStatus is ATTENDEE_STATUS_DECLINED`() {
        // CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED = 2
        val declinedInstance = testInstance.copy(selfAttendeeStatus = 2)
        val display = DisplayEvent.Device(declinedInstance)
        assertTrue(display.isDeclinedByMe)
    }

    @Test
    fun `Device isDeclinedByMe is false when selfAttendeeStatus is ACCEPTED`() {
        // CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED = 1
        val acceptedInstance = testInstance.copy(selfAttendeeStatus = 1)
        val display = DisplayEvent.Device(acceptedInstance)
        assertFalse(display.isDeclinedByMe)
    }

    @Test
    fun `Device isDeclinedByMe is false when selfAttendeeStatus is NONE`() {
        // CalendarContract.Attendees.ATTENDEE_STATUS_NONE = 0
        val noneInstance = testInstance.copy(selfAttendeeStatus = 0)
        val display = DisplayEvent.Device(noneInstance)
        assertFalse(display.isDeclinedByMe)
    }

    // ========== toEventForShareCard ==========
    //
    // The synthetic Event is fed into the existing share-card pipeline
    // (singleOccurrenceForShare → IcsExporter). It is never persisted,
    // so id and calendarId are 0L; it MUST carry a fresh UID so the
    // recipient sees a brand-new insert; and it MUST carry the device
    // event's raw timezone string verbatim so the share helper's
    // ZoneId.of(...).getOrNull() ?: systemDefault() fallback governs.

    @Test
    fun `toEventForShareCard preserves title`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals("External Meeting", event.title)
    }

    @Test
    fun `toEventForShareCard preserves description`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals("Sync adapter event", event.description)
    }

    @Test
    fun `toEventForShareCard preserves location`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals("Conference Room B", event.location)
    }

    @Test
    fun `toEventForShareCard preserves startTs and endTs from instance`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals(1700100000000L, event.startTs)
        assertEquals(1700103600000L, event.endTs)
    }

    @Test
    fun `toEventForShareCard preserves isAllDay`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertFalse(event.isAllDay)

        val allDay = testInstance.copy(isAllDay = true)
        val allDayEvent = DisplayEvent.Device(allDay).toEventForShareCard()
        assertTrue(allDayEvent.isAllDay)
    }

    @Test
    fun `toEventForShareCard preserves timezone string verbatim`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals("America/New_York", event.timezone)
    }

    @Test
    fun `toEventForShareCard preserves non-IANA timezone string verbatim`() {
        // Some sync adapters store legacy/Outlook timezone names in
        // Events.EVENT_TIMEZONE. The share-card helper handles this
        // downstream via ZoneId.of(...).getOrNull() ?: systemDefault().
        // The mapper must NOT silently rewrite to system default — the
        // caller needs to see the original string.
        val outlook = testInstance.copy(timezone = "Pacific Standard Time")
        val event = DisplayEvent.Device(outlook).toEventForShareCard()
        assertEquals("Pacific Standard Time", event.timezone)
    }

    @Test
    fun `toEventForShareCard preserves null timezone as null`() {
        val noTz = testInstance.copy(timezone = null)
        val event = DisplayEvent.Device(noTz).toEventForShareCard()
        assertNull(event.timezone)
    }

    @Test
    fun `toEventForShareCard preserves empty timezone string as empty`() {
        // CalendarProvider may surface empty strings for some sync adapters.
        // Round-trip the raw value; the share helper's runCatching fallback
        // will substitute the system default at use time.
        val emptyTz = testInstance.copy(timezone = "")
        val event = DisplayEvent.Device(emptyTz).toEventForShareCard()
        assertEquals("", event.timezone)
    }

    @Test
    fun `toEventForShareCard sets id to 0`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals(0L, event.id)
    }

    @Test
    fun `toEventForShareCard sets calendarId to 0`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals(0L, event.calendarId)
    }

    @Test
    fun `toEventForShareCard assigns a fresh UUID, not the device id`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        // Must not be empty, must not be the device-instance id stringified.
        assertTrue("uid should be non-blank", event.uid.isNotBlank())
        assertFalse("uid must not be the device eventId", event.uid == "50")
        assertFalse("uid must not be the instanceId", event.uid == "200")
        // Standard UUID shape: 8-4-4-4-12 hex chars
        assertTrue(
            "uid should look like a UUID, was ${event.uid}",
            event.uid.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        )
    }

    @Test
    fun `toEventForShareCard generates a unique UID per call`() {
        val a = DisplayEvent.Device(testInstance).toEventForShareCard()
        val b = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertFalse("each call must produce a fresh UID", a.uid == b.uid)
    }

    @Test
    fun `toEventForShareCard does not set rrule, originalEventId, or originalInstanceTime`() {
        // singleOccurrenceForShare normalizes a single occurrence anyway;
        // the synthetic Event should not carry series-membership state.
        val recurring = testInstance.copy(
            hasRrule = true,
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            originalId = 999L,
            originalInstanceTime = 1700000000000L,
        )
        val event = DisplayEvent.Device(recurring).toEventForShareCard()
        assertNull(event.rrule)
        assertNull(event.originalEventId)
        assertNull(event.originalInstanceTime)
    }

    @Test
    fun `toEventForShareCard does not set rawIcal, organizer, or caldavUrl`() {
        // Defense-in-depth: the share pipeline strips these too, but
        // there's no reason for the device-event mapper to populate them.
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertNull(event.rawIcal)
        assertNull(event.organizerEmail)
        assertNull(event.organizerName)
        assertNull(event.caldavUrl)
        assertNull(event.etag)
    }

    // ========== Parity with Room share path ==========
    //
    // The synthetic Event flows through the same IcsExporter that Room
    // events use. Any field the Room sheet preserves end-to-end (transp,
    // status, reminders, eventColor) should also flow from the device
    // sheet — otherwise sharing the same event from two different entry
    // points produces different .ics output.

    @Test
    fun `toEventForShareCard maps AVAILABILITY_FREE to TRANSPARENT`() {
        // CalendarContract.Events.AVAILABILITY_FREE = 1.
        val freeInstance = testInstance.copy(availability = 1)
        val event = DisplayEvent.Device(freeInstance).toEventForShareCard()
        assertEquals("TRANSPARENT", event.transp)
    }

    @Test
    fun `toEventForShareCard maps AVAILABILITY_BUSY to OPAQUE`() {
        val busyInstance = testInstance.copy(availability = 0)
        val event = DisplayEvent.Device(busyInstance).toEventForShareCard()
        assertEquals("OPAQUE", event.transp)
    }

    @Test
    fun `toEventForShareCard maps STATUS_TENTATIVE to TENTATIVE`() {
        // CalendarContract.Events.STATUS_TENTATIVE = 0.
        val tentativeInstance = testInstance.copy(status = 0)
        val event = DisplayEvent.Device(tentativeInstance).toEventForShareCard()
        assertEquals("TENTATIVE", event.status)
    }

    @Test
    fun `toEventForShareCard maps STATUS_CONFIRMED to CONFIRMED`() {
        // CalendarContract.Events.STATUS_CONFIRMED = 1.
        val confirmedInstance = testInstance.copy(status = 1)
        val event = DisplayEvent.Device(confirmedInstance).toEventForShareCard()
        assertEquals("CONFIRMED", event.status)
    }

    @Test
    fun `toEventForShareCard preserves per-event color override`() {
        val withColor = testInstance.copy(eventColor = 0xFFFF0000.toInt())
        val event = DisplayEvent.Device(withColor).toEventForShareCard()
        assertEquals(0xFFFF0000.toInt(), event.color)
    }

    @Test
    fun `toEventForShareCard leaves color null when no override is set`() {
        val noOverride = testInstance.copy(eventColor = null)
        val event = DisplayEvent.Device(noOverride).toEventForShareCard()
        assertNull(event.color)
    }

    @Test
    fun `toEventForShareCard converts reminder minutes to ISO durations`() {
        val withReminders = testInstance.copy(reminders = listOf(15, 60, 1440))
        val event = DisplayEvent.Device(withReminders).toEventForShareCard()
        // Hour-form encoding (DST-stable): 1440 min -> -PT24H, not the period -P1D.
        assertEquals(listOf("-PT15M", "-PT1H", "-PT24H"), event.reminders)
    }

    @Test
    fun `toEventForShareCard leaves reminders null when device has none`() {
        val noReminders = testInstance.copy(reminders = emptyList())
        val event = DisplayEvent.Device(noReminders).toEventForShareCard()
        assertNull(event.reminders)
    }

    @Test
    fun `toEventForShareCard normalizes empty description to null`() {
        // CalendarProvider repository surfaces missing DESCRIPTION as ""
        // (cursor.getString(...).orEmpty()). Keeping the empty string would
        // emit a literal `DESCRIPTION:` line in the .ics; Room shares with
        // a null description emit no line. Normalize at this boundary so
        // both share paths produce the same output.
        val empty = testInstance.copy(description = "")
        val event = DisplayEvent.Device(empty).toEventForShareCard()
        assertNull(event.description)
    }

    @Test
    fun `toEventForShareCard normalizes empty location to null`() {
        val empty = testInstance.copy(location = "")
        val event = DisplayEvent.Device(empty).toEventForShareCard()
        assertNull(event.location)
    }

    @Test
    fun `toEventForShareCard preserves non-empty description and location`() {
        val event = DisplayEvent.Device(testInstance).toEventForShareCard()
        assertEquals("Sync adapter event", event.description)
        assertEquals("Conference Room B", event.location)
    }

    // ========== All-day timezone normalization ==========
    //
    // Android's CalendarProvider stores all-day BEGIN as UTC midnight
    // regardless of the row's EVENT_TIMEZONE. KashCal-written rows use
    // EVENT_TIMEZONE='UTC' (consistent with the BEGIN convention), but
    // some sync adapters (Outlook/Exchange bridges) write a non-UTC
    // EVENT_TIMEZONE. If toEventForShareCard surfaces that non-UTC
    // string, normalizeAllDay reinterprets the UTC ms in the wrong
    // zone and the emitted DTSTART/DTEND shift one day.

    @Test
    fun `toEventForShareCard forces timezone UTC for all-day events`() {
        val allDayWithBadTz = testInstance.copy(
            isAllDay = true,
            timezone = "America/New_York",
        )
        val event = DisplayEvent.Device(allDayWithBadTz).toEventForShareCard()
        assertEquals("UTC", event.timezone)
    }

    @Test
    fun `toEventForShareCard forces timezone UTC for all-day events even when device timezone is null`() {
        val allDayNullTz = testInstance.copy(
            isAllDay = true,
            timezone = null,
        )
        val event = DisplayEvent.Device(allDayNullTz).toEventForShareCard()
        assertEquals("UTC", event.timezone)
    }

    @Test
    fun `toEventForShareCard preserves device timezone for non-all-day events`() {
        val timed = testInstance.copy(
            isAllDay = false,
            timezone = "America/New_York",
        )
        val event = DisplayEvent.Device(timed).toEventForShareCard()
        assertEquals("America/New_York", event.timezone)
    }
}
