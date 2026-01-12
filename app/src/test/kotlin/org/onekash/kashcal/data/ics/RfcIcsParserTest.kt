package org.onekash.kashcal.data.ics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Comprehensive unit tests for RfcIcsParser.
 *
 * Tests cover:
 * - Basic ICS parsing and validation
 * - Line unfolding per RFC 5545
 * - Datetime parsing (UTC, TZID, floating, DATE-only)
 * - Duration parsing
 * - RRULE/EXDATE extraction
 * - VALARM/reminder parsing
 * - Text unescaping
 * - Edge cases (cancelled events, missing properties)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RfcIcsParserTest {

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L
    }

    // ========== Basic Parsing ==========

    @Test
    fun `parse simple event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:simple-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("simple-001@test.com", event.uid)
        assertEquals("Test Event", event.title)
        assertFalse(event.isAllDay)
        assertEquals("CONFIRMED", event.status)
    }

    @Test
    fun `parse multiple events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:event-1@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event 1
            END:VEVENT
            BEGIN:VEVENT
            UID:event-2@test.com
            DTSTART:20231216T100000Z
            DTEND:20231216T110000Z
            SUMMARY:Event 2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(2, events.size)
        assertEquals("Event 1", events[0].title)
        assertEquals("Event 2", events[1].title)
    }

    @Test
    fun `parse event with all properties`() {
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Full Event", event.title)
        assertEquals("This is a detailed description.", event.description)
        assertEquals("Conference Room A", event.location)
        assertEquals("CONFIRMED", event.status)
        assertEquals("OPAQUE", event.transp)
        assertEquals("PUBLIC", event.classification)
        assertEquals(2, event.sequence)
    }

    @Test
    fun `skip cancelled events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cancelled-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Cancelled Event
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            UID:confirmed-001@test.com
            DTSTART:20231216T100000Z
            DTEND:20231216T110000Z
            SUMMARY:Confirmed Event
            STATUS:CONFIRMED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        assertEquals("Confirmed Event", events.first().title)
    }

    @Test
    fun `skip event without summary`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:no-summary@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:with-summary@test.com
            DTSTART:20231216T100000Z
            DTEND:20231216T110000Z
            SUMMARY:Has Summary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        assertEquals("Has Summary", events.first().title)
    }

    // ========== Line Unfolding ==========

    @Test
    fun `unfold CRLF with space`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
                "UID:fold-001@test.com\r\n" +
                "DTSTART:20231215T140000Z\r\n" +
                "DTEND:20231215T150000Z\r\n" +
                "SUMMARY:This is a very long event title that spans multiple lines bec\r\n" +
                " ause it is longer than 75 characters\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR"

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        assertEquals(
            "This is a very long event title that spans multiple lines because it is longer than 75 characters",
            events.first().title
        )
    }

    @Test
    fun `unfold LF with space`() {
        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n" +
                "UID:fold-002@test.com\n" +
                "DTSTART:20231215T140000Z\n" +
                "DTEND:20231215T150000Z\n" +
                "SUMMARY:Long title\n folded\n" +
                "END:VEVENT\nEND:VCALENDAR"

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        assertEquals("Long titlefolded", events.first().title)
    }

    // ========== Datetime Parsing ==========

    @Test
    fun `parse UTC datetime`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:utc-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:UTC Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        // ICU canonicalizes "UTC" to "Etc/UTC"
        assertTrue(event.timezone == "UTC" || event.timezone == "Etc/UTC")
    }

    @Test
    fun `parse datetime with TZID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:tzid-001@test.com
            DTSTART;TZID=America/New_York:20231215T140000
            DTEND;TZID=America/New_York:20231215T150000
            SUMMARY:New York Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("America/New_York", event.timezone)
    }

    @Test
    fun `parse all-day event DATE format`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:allday-001@test.com
            DTSTART;VALUE=DATE:20231225
            DTEND;VALUE=DATE:20231226
            SUMMARY:Christmas
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event.isAllDay)
        assertEquals("Christmas", event.title)
    }

    @Test
    fun `parse multi-day all-day event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:multiday-001@test.com
            DTSTART;VALUE=DATE:20231224
            DTEND;VALUE=DATE:20231227
            SUMMARY:Holiday Vacation
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event.isAllDay)
        // End should be adjusted (DTEND is exclusive for all-day)
        assertTrue(event.endTs > event.startTs)
    }

    @Test
    fun `all-day date parsed as UTC to avoid timezone shift`() {
        // Dec 25, 2025 should stay Dec 25 regardless of device timezone
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:tz-test-001@test.com
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:Christmas 2025
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event.isAllDay)

        // Dec 25, 2025 00:00:00 UTC = 1766620800000 ms
        // Verify the date is stored as UTC midnight (not shifted by device timezone)
        val expectedStartMs = 1766620800000L
        assertEquals(
            "All-day date should be parsed as UTC midnight to avoid timezone shifts",
            expectedStartMs,
            event.startTs
        )

        // Verify it's actually Dec 25 when formatted in UTC
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = event.startTs
        assertEquals("Year should be 2025", 2025, calendar.get(java.util.Calendar.YEAR))
        assertEquals("Month should be December (11)", 11, calendar.get(java.util.Calendar.MONTH))
        assertEquals("Day should be 25", 25, calendar.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `TripIt style multi-day event parsed correctly`() {
        // Simulates TripIt: Oct 11-12 trip with DTSTART=20251011, DTEND=20251013
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TripIt//Test//EN
            BEGIN:VEVENT
            UID:tripit-test@example.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20251011
            DTEND;VALUE=DATE:20251013
            SUMMARY:Green Slide Hotel
            LOCATION:San Antonio, TX
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertTrue(event.isAllDay)

        // Verify start is Oct 11, 2025 UTC
        val startCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        startCal.timeInMillis = event.startTs
        assertEquals(2025, startCal.get(java.util.Calendar.YEAR))
        assertEquals(9, startCal.get(java.util.Calendar.MONTH)) // October = 9
        assertEquals(11, startCal.get(java.util.Calendar.DAY_OF_MONTH))

        // Verify end is Oct 12, 2025 23:59:59.999 UTC (adjusted from exclusive DTEND)
        val endCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        endCal.timeInMillis = event.endTs
        assertEquals(2025, endCal.get(java.util.Calendar.YEAR))
        assertEquals(9, endCal.get(java.util.Calendar.MONTH)) // October = 9
        assertEquals(12, endCal.get(java.util.Calendar.DAY_OF_MONTH))

        // Should span 2 days (Oct 11-12)
        val daySpan = ((event.endTs - event.startTs) / (24 * 60 * 60 * 1000)) + 1
        assertEquals("Should be a 2-day event", 2L, daySpan)
    }

    @Test
    fun `parse floating datetime`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:floating-001@test.com
            DTSTART:20231215T140000
            DTEND:20231215T150000
            SUMMARY:Floating Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertNull(event.timezone) // Floating time has no timezone
    }

    // ========== Duration Parsing ==========

    @Test
    fun `parse event with duration instead of DTEND`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:duration-001@test.com
            DTSTART:20231215T140000Z
            DURATION:PT2H30M
            SUMMARY:Duration Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        // Duration is 2h30m = 9000 seconds = 9000000 milliseconds
        val durationMs = event.endTs - event.startTs
        assertEquals(9000000L, durationMs)
    }

    @Test
    fun `parse duration with days and weeks`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:duration-002@test.com
            DTSTART:20231215T140000Z
            DURATION:P1W2D
            SUMMARY:Week Duration Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        // 1 week + 2 days = 9 days = 9 * 24 * 3600 * 1000 ms
        val expectedDurationMs = 9L * 24 * 3600 * 1000
        val actualDurationMs = event.endTs - event.startTs
        assertEquals(expectedDurationMs, actualDurationMs)
    }

    @Test
    fun `parse event with no DTEND or DURATION defaults to 1 hour`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:noend-001@test.com
            DTSTART:20231215T140000Z
            SUMMARY:No End Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        // Default duration is 1 hour = 3600000 ms
        val durationMs = event.endTs - event.startTs
        assertEquals(3600000L, durationMs)
    }

    // ========== RRULE and EXDATE ==========

    @Test
    fun `parse event with RRULE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:rrule-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", event.rrule)
    }

    @Test
    fun `parse event with EXDATE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:exdate-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Recurring with Exception
            RRULE:FREQ=DAILY
            EXDATE:20231220T140000Z,20231225T140000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertNotNull(event.exdate)
        assertTrue(event.exdate!!.contains("20231220T140000Z"))
    }

    // ========== Text Unescaping ==========

    @Test
    fun `unescape special characters`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:escape-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Meeting\, Important!
            DESCRIPTION:Line 1\nLine 2\nLine 3
            LOCATION:Room\; Building A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Meeting, Important!", event.title)
        assertEquals("Line 1\nLine 2\nLine 3", event.description)
        assertEquals("Room; Building A", event.location)
    }

    @Test
    fun `unescape backslash`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:backslash-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Path: C:\\Users\\Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Path: C:\\Users\\Test", event.title)
    }

    // ========== VALARM / Reminders ==========

    @Test
    fun `parse VALARM with trigger`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:alarm-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event with Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertNotNull(event.reminders)
        assertEquals(1, event.reminders!!.size)
        assertEquals("-PT15M", event.reminders!![0])
    }

    @Test
    fun `parse multiple VALARMs limited to 2`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:alarm-002@test.com
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertNotNull(event.reminders)
        // Should only have 2 reminders (max)
        assertEquals(2, event.reminders!!.size)
    }

    // ========== Validation ==========

    @Test
    fun `isValidIcs returns true for valid ICS`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertTrue(RfcIcsParser.isValidIcs(ics))
    }

    @Test
    fun `isValidIcs returns false for missing VCALENDAR`() {
        val ics = """
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
        """.trimIndent()

        assertFalse(RfcIcsParser.isValidIcs(ics))
    }

    @Test
    fun `isValidIcs returns false for empty content`() {
        assertFalse(RfcIcsParser.isValidIcs(""))
    }

    @Test
    fun `isValidIcs returns true for VTODO only`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:todo@test.com
            SUMMARY:Todo Item
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        assertTrue(RfcIcsParser.isValidIcs(ics))
    }

    // ========== Calendar Name ==========

    @Test
    fun `getCalendarName extracts X-WR-CALNAME`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-CALNAME:My Calendar
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val name = RfcIcsParser.getCalendarName(ics)
        assertEquals("My Calendar", name)
    }

    @Test
    fun `getCalendarName falls back to PRODID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Google Inc//Calendar//EN
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val name = RfcIcsParser.getCalendarName(ics)
        assertEquals("-//Google Inc//Calendar//EN", name)
    }

    // ========== Source Tracking ==========

    @Test
    fun `event source identifier contains subscription ID and UID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:source-test-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Source Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        // caldavUrl is used to store source identifier
        assertNotNull(event.caldavUrl)
        assertTrue(event.caldavUrl!!.contains("ics_subscription"))
        assertTrue(event.caldavUrl!!.contains(SUBSCRIPTION_ID.toString()))
        assertTrue(event.caldavUrl!!.contains("source-test-001@test.com"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `parse empty ICS returns empty list`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `parse malformed ICS gracefully`() {
        val ics = "This is not valid ICS content"

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `parse event generates UID if missing`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:No UID Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertNotNull(event.uid)
        assertTrue(event.uid.isNotBlank())
    }

    @Test
    fun `all events have SYNCED status`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:sync-001@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(org.onekash.kashcal.data.db.entity.SyncStatus.SYNCED, event.syncStatus)
    }
}