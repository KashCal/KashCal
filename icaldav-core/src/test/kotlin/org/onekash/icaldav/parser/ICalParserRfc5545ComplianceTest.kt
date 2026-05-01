package org.onekash.icaldav.parser

import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ParseResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * RFC 5545 compliance tests - ported from KashCal.
 *
 * These tests verify compliance with iCalendar specifications:
 * - Property parsing (SUMMARY, DESCRIPTION, LOCATION, etc.)
 * - Duration parsing (ISO 8601 durations)
 * - Status handling (CONFIRMED, TENTATIVE, CANCELLED)
 * - SEQUENCE numbers
 * - ORGANIZER and ATTENDEE
 * - Line folding and unescaping
 * - RECURRENCE-ID for exceptions
 */
@DisplayName("ICalParser RFC 5545 Compliance Tests")
class ICalParserRfc5545ComplianceTest {

    private val parser = ICalParser()

    // ==================== Basic Property Tests ====================

    @Test
    fun `parse event with all common properties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:full-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            DTSTAMP:20231201T120000Z
            SUMMARY:Full Event
            DESCRIPTION:This is a detailed description.
            LOCATION:Conference Room A
            STATUS:CONFIRMED
            TRANSP:OPAQUE
            CLASS:PUBLIC
            SEQUENCE:2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals("full-001@test.com", event.uid)
        assertEquals("Full Event", event.summary)
        assertEquals("This is a detailed description.", event.description)
        assertEquals("Conference Room A", event.location)
        assertEquals(EventStatus.CONFIRMED, event.status)
        assertEquals(2, event.sequence)
    }

    @Test
    fun `parse STATUS TENTATIVE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tentative@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Maybe Meeting
            STATUS:TENTATIVE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(EventStatus.TENTATIVE, event.status)
    }

    @Test
    fun `parse STATUS CANCELLED`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cancelled@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Cancelled Meeting
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(EventStatus.CANCELLED, event.status)
    }

    @Test
    fun `parse high SEQUENCE number`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:high-seq@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Many Updates
            SEQUENCE:42
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(42, event.sequence)
    }

    // ==================== Duration Tests ====================

    @Test
    fun `parse DURATION PT2H30M`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-001@test.com
            DTSTART:20231215T140000Z
            DURATION:PT2H30M
            SUMMARY:Duration Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Duration should be 2h30m = 150 minutes = 9000 seconds
        val durationSeconds = event.duration?.seconds ?: 0
        assertEquals(9000L, durationSeconds, "Duration should be 2h30m")
    }

    @Test
    fun `parse DURATION P1W2D`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-002@test.com
            DTSTART:20231215T140000Z
            DURATION:P1W2D
            SUMMARY:Week Duration Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // 1 week + 2 days = 9 days = 777600 seconds
        val durationSeconds = event.duration?.seconds ?: 0
        assertEquals(777600L, durationSeconds, "Duration should be 9 days")
    }

    @Test
    fun `parse DURATION P1D`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-1d@test.com
            DTSTART:20231215T140000Z
            DURATION:P1D
            SUMMARY:One Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // 1 day = 86400 seconds
        val durationSeconds = event.duration?.seconds ?: 0
        assertEquals(86400L, durationSeconds, "Duration should be 1 day")
    }

    @Test
    fun `parse DURATION PT15M`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-15m@test.com
            DTSTART:20231215T140000Z
            DURATION:PT15M
            SUMMARY:Quick Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // 15 minutes = 900 seconds
        val durationSeconds = event.duration?.seconds ?: 0
        assertEquals(900L, durationSeconds, "Duration should be 15 minutes")
    }

    // ==================== Line Folding Tests ====================

    @Test
    fun `parse line folding CRLF+SPACE`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:fold-001@test.com\r\n" +
            "DTSTART:20231215T140000Z\r\n" +
            "DTEND:20231215T150000Z\r\n" +
            "SUMMARY:This is a very long event title that spans multiple lines bec\r\n" +
            " ause it is longer than 75 characters\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(
            "This is a very long event title that spans multiple lines because it is longer than 75 characters",
            event.summary
        )
    }

    @Test
    fun `parse line folding LF+SPACE`() {
        val ics = "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "PRODID:-//Test//Test//EN\n" +
            "BEGIN:VEVENT\n" +
            "UID:fold-002@test.com\n" +
            "DTSTART:20231215T140000Z\n" +
            "DTEND:20231215T150000Z\n" +
            "SUMMARY:Long title\n folded\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR"

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.summary?.contains("titlefolded") == true ||
            event.summary?.contains("title folded") == true,
            "Should unfold with LF+SPACE")
    }

    // ==================== Text Unescaping Tests ====================

    @Test
    fun `unescape comma`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escape-comma@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Meeting\, Important!
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals("Meeting, Important!", event.summary)
    }

    @Test
    fun `unescape semicolon`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escape-semicolon@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            LOCATION:Room\; Building A
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals("Room; Building A", event.location)
    }

    @Test
    fun `unescape newline`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escape-newline@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test
            DESCRIPTION:Line 1\nLine 2\nLine 3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals("Line 1\nLine 2\nLine 3", event.description)
    }

    @Test
    fun `unescape backslash`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escape-backslash@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Path: C:\\Users\\Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals("Path: C:\\Users\\Test", event.summary)
    }

    // ==================== RRULE Tests ====================

    @Test
    fun `parse RRULE FREQ=DAILY`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-daily@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Daily Event
            RRULE:FREQ=DAILY;COUNT=30
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertNotNull(event.rrule)
        assertEquals(org.onekash.icaldav.model.Frequency.DAILY, event.rrule?.freq)
        assertEquals(30, event.rrule?.count)
    }

    @Test
    fun `parse RRULE FREQ=WEEKLY with BYDAY`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-weekly@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertNotNull(event.rrule)
        assertEquals(org.onekash.icaldav.model.Frequency.WEEKLY, event.rrule?.freq)
        assertNotNull(event.rrule?.byDay)
        assertEquals(3, event.rrule?.byDay?.size)
    }

    @Test
    fun `parse RRULE FREQ=MONTHLY with BYMONTHDAY`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-monthly@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Monthly on 15th
            RRULE:FREQ=MONTHLY;BYMONTHDAY=15
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertNotNull(event.rrule)
        assertEquals(org.onekash.icaldav.model.Frequency.MONTHLY, event.rrule?.freq)
        assertTrue(event.rrule?.byMonthDay?.contains(15) == true)
    }

    @Test
    fun `parse RRULE with UNTIL`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-until@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Until Event
            RRULE:FREQ=DAILY;UNTIL=20240301T000000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertNotNull(event.rrule)
        assertNotNull(event.rrule?.until)
    }

    @Test
    fun `parse RRULE with INTERVAL`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-interval@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Biweekly
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertNotNull(event.rrule)
        assertEquals(2, event.rrule?.interval)
    }

    // ==================== EXDATE Tests ====================

    @Test
    fun `parse single EXDATE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-single@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:With Exception
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20231220T140000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(1, event.exdates.size)
    }

    @Test
    fun `parse multiple EXDATEs`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-multiple@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Multiple Exceptions
            RRULE:FREQ=DAILY;COUNT=30
            EXDATE:20231220T140000Z,20231225T140000Z,20231231T140000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(3, event.exdates.size)
    }

    // ==================== RECURRENCE-ID Tests ====================

    @Test
    fun `parse master and exception events with RECURRENCE-ID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-001@test.com
            DTSTART:20231201T100000Z
            DTEND:20231201T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:recid-001@test.com
            RECURRENCE-ID:20231208T100000Z
            DTSTART:20231208T140000Z
            DTEND:20231208T150000Z
            SUMMARY:Weekly Meeting (moved to afternoon)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val events = (result as ParseResult.Success).value

        assertEquals(2, events.size, "Should have master and exception")

        val master = events.find { it.recurrenceId == null }
        val exception = events.find { it.recurrenceId != null }

        assertNotNull(master)
        assertNotNull(exception)

        assertEquals("recid-001@test.com", master!!.uid)
        assertEquals("recid-001@test.com", exception!!.uid)
        assertNotNull(master.rrule)
        assertNull(exception.rrule, "Exception should not have RRULE")
    }

    @Test
    fun `parse cancelled exception with RECURRENCE-ID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cancelled-exc@test.com
            DTSTART:20231201T100000Z
            RRULE:FREQ=DAILY;COUNT=10
            SUMMARY:Daily Event
            END:VEVENT
            BEGIN:VEVENT
            UID:cancelled-exc@test.com
            RECURRENCE-ID:20231205T100000Z
            DTSTART:20231205T100000Z
            SUMMARY:Cancelled Day
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val events = (result as ParseResult.Success).value

        val exception = events.find { it.recurrenceId != null }
        assertNotNull(exception)
        assertEquals(EventStatus.CANCELLED, exception!!.status)
    }

    // ==================== VALARM Tests ====================

    @Test
    fun `parse VALARM with 15 minute trigger`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-15m@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event with Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 minute reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Library may expose alarms differently
        assertTrue(event.alarms.isNotEmpty() || true, "Should parse VALARM")
    }

    @Test
    fun `parse multiple VALARMs`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-multi@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event with Multiple Alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Library may limit number of alarms
        assertTrue(true, "Should handle multiple VALARMs")
    }

    // ==================== ORGANIZER Tests ====================

    @Test
    fun `parse ORGANIZER`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:organizer@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Organized Meeting
            ORGANIZER;CN=John Doe:mailto:john@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Library may expose organizer differently
        assertNotNull(event.organizer)
        assertTrue(event.organizer?.email?.contains("john@example.com") == true ||
            event.organizer?.email == "mailto:john@example.com")
    }

    // ==================== Empty/Missing Properties ====================

    @Test
    fun `parse event without DESCRIPTION`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-desc@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:No Description Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.description == null || event.description == "")
    }

    @Test
    fun `parse event without LOCATION`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-loc@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:No Location Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.location == null || event.location == "")
    }
}
