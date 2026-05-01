package org.onekash.icaldav.parser

import org.onekash.icaldav.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ICalParserTest {

    private val parser = ICalParser()

    @Test
    fun `parse simple event`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test Event
            DESCRIPTION:This is a test description
            LOCATION:Test Location
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()
        assertNotNull(events)
        assertEquals(1, events!!.size)

        val event = events[0]
        assertEquals("test-event-123", event.uid)
        assertEquals("test-event-123", event.importId)
        assertEquals("Test Event", event.summary)
        assertEquals("This is a test description", event.description)
        assertEquals("Test Location", event.location)
        assertFalse(event.isAllDay)
    }

    @Test
    fun `parse all-day event`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-event-123
            DTSTAMP:20231215T100000Z
            DTSTART;VALUE=DATE:20231215
            DTEND;VALUE=DATE:20231216
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        assertEquals(1, events.size)

        val event = events[0]
        assertTrue(event.isAllDay)
        assertEquals("20231215", event.dtStart.toDayCode())
    }

    @Test
    fun `parse event with RRULE`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertNotNull(event.rrule)
        assertEquals(Frequency.WEEKLY, event.rrule!!.freq)
        assertEquals(10, event.rrule!!.count)
        assertNotNull(event.rrule!!.byDay)
        assertEquals(3, event.rrule!!.byDay!!.size)
    }

    @Test
    fun `parse event with RECURRENCE-ID creates unique importId`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-123
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231208T140000Z
            DTSTART:20231209T150000Z
            DTEND:20231209T160000Z
            SUMMARY:Modified Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertEquals("recurring-123", event.uid)
        // importId should include RECURRENCE-ID
        assertTrue(event.importId.contains("RECID"))
        assertTrue(event.importId.contains("20231208"))
        assertNotNull(event.recurrenceId)
    }

    @Test
    fun `parse event with EXDATE`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231201T140000Z
            RRULE:FREQ=DAILY;COUNT=30
            EXDATE:20231208T140000Z,20231215T140000Z
            SUMMARY:Daily Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertEquals(2, event.exdates.size)
    }

    @Test
    fun `parse event with VALARM`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:event-with-alarm
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:Meeting with Reminder
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Event reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertEquals(1, event.alarms.size)
        val alarm = event.alarms[0]
        assertEquals(AlarmAction.DISPLAY, alarm.action)
        assertNotNull(alarm.trigger)
        assertEquals(-15, alarm.trigger!!.toMinutes())
    }

    @Test
    fun `parse multiple events in single file`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:Event One
            END:VEVENT
            BEGIN:VEVENT
            UID:event-2
            DTSTAMP:20231215T100000Z
            DTSTART:20231216T140000Z
            SUMMARY:Event Two
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        assertEquals(2, events.size)
        assertEquals("event-1", events[0].uid)
        assertEquals("event-2", events[1].uid)
    }

    @Test
    fun `extractUid returns correct UID`() {
        val icalData = """
            BEGIN:VEVENT
            UID:simple-uid-123
            SUMMARY:Test
            END:VEVENT
        """.trimIndent()

        val uid = parser.extractUid(icalData)
        assertEquals("simple-uid-123", uid)
    }

    @Test
    fun `parse escaped text correctly`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escaped-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:Meeting\, Important
            DESCRIPTION:Line 1\nLine 2\nLine 3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals("Meeting, Important", event.summary)
        assertTrue(event.description!!.contains("\n"))
    }

    // ============ PRIORITY Tests ============

    @Test
    fun `parse event with PRIORITY`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            PRIORITY:1
            SUMMARY:High Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(1, event.priority)
    }

    @Test
    fun `parse event with PRIORITY 9 (lowest)`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:low-priority-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            PRIORITY:9
            SUMMARY:Low Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(9, event.priority)
    }

    @Test
    fun `parse event without PRIORITY defaults to 0`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-priority-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:No Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(0, event.priority)
    }

    @Test
    fun `parse event with invalid PRIORITY coerces to valid range`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:invalid-priority-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            PRIORITY:15
            SUMMARY:Invalid Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(9, event.priority) // Coerced to max valid value
    }

    // ============ GEO Tests ============

    @Test
    fun `parse event with GEO`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:geo-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            GEO:37.386013;-122.082932
            SUMMARY:Event at Apple Park
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals("37.386013;-122.082932", event.geo)
    }

    @Test
    fun `parse event without GEO defaults to null`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-geo-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:No Location Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertNull(event.geo)
    }

    @Test
    fun `parse event with negative GEO coordinates`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:negative-geo-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            GEO:-33.8688;151.2093
            SUMMARY:Event in Sydney
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals("-33.8688;151.2093", event.geo)
    }
}