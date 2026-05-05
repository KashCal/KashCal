package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.model.WeekdayNum
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ICalGeneratorTest {

    private val generator = ICalGenerator()

    @Test
    fun `generate simple event produces valid iCal`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("BEGIN:VCALENDAR"))
        assertTrue(icalString.contains("END:VCALENDAR"))
        assertTrue(icalString.contains("BEGIN:VEVENT"))
        assertTrue(icalString.contains("END:VEVENT"))
        assertTrue(icalString.contains("UID:test-uid-123"))
        assertTrue(icalString.contains("SUMMARY:Test Event"))
    }

    @Test
    fun `generated iCal has required CalDAV properties`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = null)

        // Required properties for CalDAV
        assertTrue(icalString.contains("VERSION:2.0"))
        assertTrue(icalString.contains("PRODID:"))
        assertTrue(icalString.contains("CALSCALE:GREGORIAN"))
        // METHOD is excluded by default for CalDAV PUT (some servers reject it)
        assertFalse(icalString.contains("METHOD:PUBLISH"))
        assertTrue(icalString.contains("SEQUENCE:"))
        assertTrue(icalString.contains("STATUS:"))
    }

    @Test
    fun `generated iCal with includeMethod has METHOD property`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = ITipMethod.PUBLISH)

        assertTrue(icalString.contains("METHOD:PUBLISH"))
    }

    @Test
    fun `generate all-day event uses DATE format`() {
        val event = createTestEvent(isAllDay = true)

        val icalString = generator.generate(event, method = null)

        // All-day events should use VALUE=DATE format
        assertTrue(icalString.contains("DTSTART;VALUE=DATE:"))
    }

    @Test
    fun `generate timed event includes datetime`() {
        val event = createTestEvent(isAllDay = false)

        val icalString = generator.generate(event, method = null)

        // Timed events should have DTSTART
        assertTrue(icalString.contains("DTSTART"))
    }

    @Test
    fun `generate event with alarm includes VALARM`() {
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-15),
            triggerAbsolute = null,
            triggerRelatedToEnd = false,
            description = "Reminder",
            summary = null,
            repeatCount = 0,
            repeatDuration = null
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("BEGIN:VALARM"))
        assertTrue(icalString.contains("END:VALARM"))
        assertTrue(icalString.contains("ACTION:DISPLAY"))
        assertTrue(icalString.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun `generate event with RECURRENCE-ID includes property`() {
        val recurrenceId = ICalDateTime.parse("20231208T140000Z")
        val event = createTestEvent(recurrenceId = recurrenceId)

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("RECURRENCE-ID"))
        assertTrue(icalString.contains("20231208"))
    }

    @Test
    fun `generate event escapes special characters`() {
        val event = createTestEvent(
            summary = "Meeting, Important",
            description = "Line 1\nLine 2\nWith; semicolon"
        )

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("Meeting\\, Important"))
        assertTrue(icalString.contains("\\n"))
        assertTrue(icalString.contains("\\;"))
    }

    @Test
    fun `generated iCal has line endings`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = null)

        // iCal should have proper line endings
        assertTrue(icalString.contains("\n"))
    }

    @Test
    fun `generate event with RRULE includes recurrence rule`() {
        val rrule = RRule(
            freq = Frequency.WEEKLY,
            interval = 1,
            count = 10,
            byDay = listOf(
                WeekdayNum(DayOfWeek.MONDAY),
                WeekdayNum(DayOfWeek.WEDNESDAY)
            )
        )
        val event = createTestEvent(rrule = rrule)

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("RRULE:"))
        assertTrue(icalString.contains("FREQ=WEEKLY"))
        assertTrue(icalString.contains("COUNT=10"))
        assertTrue(icalString.contains("BYDAY="))
    }

    @Test
    fun `round trip parsing generates equivalent event`() {
        val original = createTestEvent(
            summary = "Round Trip Test",
            description = "Testing parse and generate",
            location = "Office"
        )

        val icalString = generator.generate(original, method = null)
        val parser = ICalParser()
        val result = parser.parseAllEvents(icalString)

        assertTrue(result is ParseResult.Success)
        val parsed = result.getOrNull()!![0]

        assertEquals(original.uid, parsed.uid)
        assertEquals(original.summary, parsed.summary)
        assertEquals(original.description, parsed.description)
        assertEquals(original.location, parsed.location)
    }

    // VTIMEZONE integration tests

    @Test
    fun `generate includes VTIMEZONE by default`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("BEGIN:VTIMEZONE"))
        assertTrue(icalString.contains("TZID:America/New_York"))
        assertTrue(icalString.contains("END:VTIMEZONE"))
    }

    @Test
    fun `generate excludes VTIMEZONE when disabled`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = null, includeVTimezone = false)

        assertFalse(icalString.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun `generate places VTIMEZONE before VEVENT`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, method = null)

        val vtimezoneIndex = icalString.indexOf("BEGIN:VTIMEZONE")
        val veventIndex = icalString.indexOf("BEGIN:VEVENT")

        assertTrue(vtimezoneIndex > 0)
        assertTrue(vtimezoneIndex < veventIndex)
    }

    @Test
    fun `generateBatch deduplicates timezones`() {
        val event1 = createTestEvent(uid = "event-1")
        val event2 = createTestEvent(uid = "event-2")

        val icalString = generator.generateBatch(listOf(event1, event2))

        // Should only have one VTIMEZONE for America/New_York even with two events
        val vtimezoneCount = icalString.split("TZID:America/New_York").size - 1
        assertEquals(1, vtimezoneCount)
    }

    @Test
    fun `generateBatch with multiple timezones includes all`() {
        val nyEvent = createTestEvent(uid = "ny-event")
        val tokyoEvent = createTestEvent(uid = "tokyo-event", timezone = ZoneId.of("Asia/Tokyo"))

        val icalString = generator.generateBatch(listOf(nyEvent, tokyoEvent))

        assertTrue(icalString.contains("TZID:America/New_York"))
        assertTrue(icalString.contains("TZID:Asia/Tokyo"))
    }

    // Apple VALARM extension tests

    @Test
    fun `generate includes Apple VALARM extensions by default`() {
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-15),
            triggerAbsolute = null,
            description = "Reminder",
            summary = null
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("X-WR-ALARMUID:"))
        assertTrue(icalString.contains("X-APPLE-DEFAULT-ALARM:FALSE"))
    }

    @Test
    fun `generate excludes Apple VALARM extensions when disabled`() {
        val generatorNoApple = ICalGenerator(includeAppleExtensions = false)
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-15),
            triggerAbsolute = null,
            description = "Reminder",
            summary = null
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generatorNoApple.generate(event, method = null)

        assertFalse(icalString.contains("X-WR-ALARMUID:"))
        assertFalse(icalString.contains("X-APPLE-DEFAULT-ALARM:"))
    }

    @Test
    fun `generate VALARM always has UID`() {
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-30),
            triggerAbsolute = null,
            description = "Test",
            summary = null
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generator.generate(event, method = null)

        // Should have UID in VALARM
        val valarmSection = icalString.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
        assertTrue(valarmSection.contains("UID:"))
    }

    @Test
    fun `generate VALARM X-WR-ALARMUID matches UID`() {
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-10),
            triggerAbsolute = null,
            uid = "test-alarm-uid-123",
            description = "Test",
            summary = null
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("UID:test-alarm-uid-123"))
        assertTrue(icalString.contains("X-WR-ALARMUID:test-alarm-uid-123"))
    }

    @Test
    fun `generate VALARM omits X-APPLE-DEFAULT-ALARM when defaultAlarm is true`() {
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-15),
            triggerAbsolute = null,
            description = "Default Alarm",
            summary = null,
            defaultAlarm = true
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generator.generate(event, method = null)

        // Should have X-WR-ALARMUID but NOT X-APPLE-DEFAULT-ALARM:FALSE
        assertTrue(icalString.contains("X-WR-ALARMUID:"))
        assertFalse(icalString.contains("X-APPLE-DEFAULT-ALARM:FALSE"))
    }

    @Test
    fun `generate UTC event has no VTIMEZONE`() {
        val utcStart = ICalDateTime.parse("20231215T140000Z")
        val utcEnd = ICalDateTime.parse("20231215T150000Z")
        val event = ICalEvent(
            uid = "utc-event",
            importId = "utc-event",
            summary = "UTC Event",
            description = null,
            location = null,
            dtStart = utcStart,
            dtEnd = utcEnd,
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )

        val icalString = generator.generate(event, method = null)

        assertFalse(icalString.contains("BEGIN:VTIMEZONE"))
    }

    // ============ PRIORITY Tests ============

    @Test
    fun `generate event with priority includes PRIORITY property`() {
        val event = createTestEvent(priority = 1)

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("PRIORITY:1"))
    }

    @Test
    fun `generate event with priority 0 excludes PRIORITY property`() {
        val event = createTestEvent(priority = 0)

        val icalString = generator.generate(event, method = null)

        // Priority 0 means undefined, should not be output
        assertFalse(icalString.contains("PRIORITY:"))
    }

    @Test
    fun `generate event with priority 9 includes PRIORITY property`() {
        val event = createTestEvent(priority = 9)

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("PRIORITY:9"))
    }

    // ============ GEO Tests ============

    @Test
    fun `generate event with geo includes GEO property`() {
        val event = createTestEvent(geo = "37.386013;-122.082932")

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("GEO:37.386013;-122.082932"))
    }

    @Test
    fun `generate event without geo excludes GEO property`() {
        val event = createTestEvent(geo = null)

        val icalString = generator.generate(event, method = null)

        assertFalse(icalString.contains("GEO:"))
    }

    @Test
    fun `generate event with negative geo coordinates`() {
        val event = createTestEvent(geo = "-33.8688;151.2093")

        val icalString = generator.generate(event, method = null)

        assertTrue(icalString.contains("GEO:-33.8688;151.2093"))
    }

    // ============ Helper Functions ============

    private fun createTestEvent(
        uid: String = "test-uid-123",
        summary: String = "Test Event",
        description: String? = "Test description",
        location: String? = "Test location",
        isAllDay: Boolean = false,
        alarms: List<ICalAlarm> = emptyList(),
        recurrenceId: ICalDateTime? = null,
        rrule: RRule? = null,
        timezone: ZoneId = ZoneId.of("America/New_York"),
        priority: Int = 0,
        geo: String? = null
    ): ICalEvent {
        val zone = timezone
        val start = ZonedDateTime.of(2023, 12, 15, 14, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2023, 12, 15, 15, 0, 0, 0, zone)

        return ICalEvent(
            uid = uid,
            importId = if (recurrenceId != null) "$uid:RECID:${recurrenceId.toICalString()}" else uid,
            summary = summary,
            description = description,
            location = location,
            dtStart = ICalDateTime.fromZonedDateTime(start, isAllDay),
            dtEnd = ICalDateTime.fromZonedDateTime(end, isAllDay),
            duration = null,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = emptyList(),
            recurrenceId = recurrenceId,
            alarms = alarms,
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            priority = priority,
            geo = geo,
            rawProperties = emptyMap()
        )
    }
}
