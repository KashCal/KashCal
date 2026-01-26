package org.onekash.kashcal.sync.parser.migration

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ICalEventMapper: verifies that icaldav ICalEvent maps correctly
 * to KashCal Event entity.
 *
 * These tests ensure that the icaldav → KashCal mapping is accurate
 * for all supported iCalendar patterns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ParserComparisonTest {

    private val parser = ICalParser()

    @Test
    fun `simple event maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:simple-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Simple Meeting
            DESCRIPTION:A simple test event
            LOCATION:Room 101
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue("Parse should succeed", result is ParseResult.Success)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, "http://example.com/event.ics", "etag123")

        assertEquals("simple-event-1", entity.uid)
        assertEquals("Simple Meeting", entity.title)
        assertEquals("A simple test event", entity.description)
        assertEquals("Room 101", entity.location)
        assertEquals(1L, entity.calendarId)
        assertEquals("http://example.com/event.ics", entity.caldavUrl)
        assertEquals("etag123", entity.etag)
        assertFalse(entity.isAllDay)
        assertEquals("CONFIRMED", entity.status)

        // Use comparator for comprehensive check
        ParsedEventComparator.assertMappingEquivalent(icalEvent, entity, "simple event")
    }

    @Test
    fun `all-day event maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-event-1
            DTSTAMP:20231215T100000Z
            DTSTART;VALUE=DATE:20231215
            DTEND;VALUE=DATE:20231216
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertTrue("Should be all-day", entity.isAllDay)
        assertEquals("All Day Event", entity.title)

        // All-day endTs is adjusted: exclusive → inclusive (minus 1ms)
        // Original DTEND is 20231216 (start of Dec 16)
        // Entity endTs should be last millisecond of Dec 15
        assertTrue(entity.endTs < icalEvent.dtEnd!!.timestamp)
    }

    @Test
    fun `weekly recurring event maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:weekly-recurring-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertNotNull("RRULE should be mapped", entity.rrule)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", entity.rrule)

        ParsedEventComparator.assertMappingEquivalent(icalEvent, entity, "weekly recurring")
    }

    @Test
    fun `monthly by setpos maps correctly to entity`() {
        // Second Tuesday of every month
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:monthly-setpos-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231212T140000Z
            DTEND:20231212T150000Z
            SUMMARY:Monthly Review
            RRULE:FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals("FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2", entity.rrule)
    }

    @Test
    fun `exception event with recurrence-id maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exception-event-1
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231220T140000Z
            DTSTART:20231220T150000Z
            DTEND:20231220T160000Z
            SUMMARY:Rescheduled Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        // RECURRENCE-ID → originalInstanceTime
        assertNotNull("originalInstanceTime should be set", entity.originalInstanceTime)
        assertEquals(icalEvent.recurrenceId!!.timestamp, entity.originalInstanceTime)

        // importId should contain RECID
        assertTrue("importId should contain RECID", entity.importId!!.contains("RECID"))

        ParsedEventComparator.assertMappingEquivalent(icalEvent, entity, "exception event")
    }

    @Test
    fun `exdate maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Daily Event
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20231217T140000Z
            EXDATE:20231220T140000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertNotNull("exdate should be mapped", entity.exdate)
        // EXDATEs stored as comma-separated timestamps
        val exdates = entity.exdate!!.split(",")
        assertEquals("Should have 2 EXDATEs", 2, exdates.size)

        // Timestamps should match
        val icalExdates = icalEvent.exdates.map { it.timestamp.toString() }
        exdates.forEach { ts ->
            assertTrue("EXDATE $ts should be in parsed list", icalExdates.contains(ts))
        }
    }

    @Test
    fun `utc datetime maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:utc-datetime-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:UTC Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        // UTC times have no timezone ID
        assertNull("UTC event should have null timezone", entity.timezone)

        // Timestamps should match exactly
        assertEquals(icalEvent.dtStart.timestamp, entity.startTs)
        assertEquals(icalEvent.dtEnd!!.timestamp, entity.endTs)
    }

    @Test
    fun `tzid datetime maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tzid-datetime-1
            DTSTAMP:20231215T100000Z
            DTSTART;TZID=America/New_York:20231215T090000
            DTEND;TZID=America/New_York:20231215T100000
            SUMMARY:New York Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals("America/New_York", entity.timezone)
        assertEquals(icalEvent.dtStart.timestamp, entity.startTs)
    }

    @Test
    fun `cancelled status maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cancelled-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Cancelled Meeting
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals(EventStatus.CANCELLED, icalEvent.status)
        assertEquals("CANCELLED", entity.status)
    }

    @Test
    fun `tentative status maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tentative-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Maybe Meeting
            STATUS:TENTATIVE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals(EventStatus.TENTATIVE, icalEvent.status)
        assertEquals("TENTATIVE", entity.status)
    }

    @Test
    fun `empty ics returns empty list`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue("Parse should succeed", result is ParseResult.Success)
        val events = result.getOrNull()!!
        assertTrue("Should be empty", events.isEmpty())
    }

    @Test
    fun `event with alarms maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event with Alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 minute reminder
            END:VALARM
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-PT5M
            DESCRIPTION:5 minute reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals(2, icalEvent.alarms.size)
        assertNotNull("reminders should be mapped", entity.reminders)
        assertEquals(2, entity.reminders!!.size)
        assertEquals(2, entity.alarmCount)

        // Verify trigger durations are mapped
        assertTrue(entity.reminders!!.any { it == "-PT30M" })
        assertTrue(entity.reminders!!.any { it == "-PT5M" })
    }

    @Test
    fun `event with organizer maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:organizer-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Organized Event
            ORGANIZER;CN=Boss Person:mailto:boss@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals("boss@example.com", entity.organizerEmail)
        assertEquals("Boss Person", entity.organizerName)
    }

    @Test
    fun `event with duration calculates endTs correctly`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DURATION:PT2H
            SUMMARY:Two Hour Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        // endTs should be startTs + 2 hours
        val expectedEndTs = entity.startTs + 2 * 60 * 60 * 1000
        assertEquals(expectedEndTs, entity.endTs)
    }

    @Test
    fun `event transparency maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:transp-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Free Time
            TRANSP:TRANSPARENT
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals("TRANSPARENT", entity.transp)
    }

    @Test
    fun `event sequence maps correctly to entity`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sequence-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Updated Event
            SEQUENCE:3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertEquals(3, entity.sequence)
    }

    @Test
    fun `event with extra X-properties preserves them`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:xprops-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event with X-Props
            X-CUSTOM-FIELD:custom-value
            X-ANOTHER-FIELD:another-value
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        val icalEvent = result.getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(icalEvent, ical, 1L, null, null)

        assertNotNull("extraProperties should not be null", entity.extraProperties)
        assertTrue(entity.extraProperties!!.containsKey("X-CUSTOM-FIELD"))
        assertTrue(entity.extraProperties!!.containsKey("X-ANOTHER-FIELD"))
    }

    @Test
    fun `isException helper works correctly`() {
        val masterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            RRULE:FREQ=DAILY;COUNT=10
            SUMMARY:Master Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val exceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-event-1
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231217T140000Z
            DTSTART:20231217T150000Z
            DTEND:20231217T160000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val masterEvent = parser.parseAllEvents(masterIcal).getOrNull()!![0]
        val exceptionEvent = parser.parseAllEvents(exceptionIcal).getOrNull()!![0]

        assertFalse("Master should not be exception", ICalEventMapper.isException(masterEvent))
        assertTrue("Exception should be exception", ICalEventMapper.isException(exceptionEvent))
    }

    @Test
    fun `getImportId helper works correctly`() {
        val masterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-event-uid
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Master Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val exceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-event-uid
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231217T140000Z
            DTSTART:20231217T150000Z
            DTEND:20231217T160000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val masterEvent = parser.parseAllEvents(masterIcal).getOrNull()!![0]
        val exceptionEvent = parser.parseAllEvents(exceptionIcal).getOrNull()!![0]

        assertEquals("test-event-uid", ICalEventMapper.getImportId(masterEvent))
        assertTrue(
            "Exception importId should contain RECID",
            ICalEventMapper.getImportId(exceptionEvent).contains("RECID")
        )
    }
}
