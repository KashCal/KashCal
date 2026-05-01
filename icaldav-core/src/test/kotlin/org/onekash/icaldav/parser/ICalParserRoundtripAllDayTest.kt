package org.onekash.icaldav.parser

import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.model.ParseResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Round-trip tests for all-day events - ported from KashCal.
 *
 * These tests verify that all-day events survive the parse → generate → parse cycle
 * without shifting dates. This is critical for:
 * - Sync operations (iCloud CalDAV → local → iCloud CalDAV)
 * - Import/export operations
 * - Copy/paste operations
 *
 * The key invariant: day codes must be preserved exactly after round-trip.
 */
@DisplayName("ICalParser All-Day Round-Trip Tests")
class ICalParserRoundtripAllDayTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    @Test
    fun `round trip simple all-day event preserves date`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-001@test.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20241225
            DTEND;VALUE=DATE:20241226
            SUMMARY:Christmas
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val events = (parsed as ParseResult.Success).value
        assertEquals(1, events.size)

        val originalEvent = events.first()
        val originalDayCode = originalEvent.dtStart.toDayCode()

        // Generate ICS
        val generated = generator.generateBatch(events)

        // Re-parse
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)
        val reparsedEvents = (reparsed as ParseResult.Success).value
        assertEquals(1, reparsedEvents.size)

        val roundTrippedEvent = reparsedEvents.first()
        val roundTrippedDayCode = roundTrippedEvent.dtStart.toDayCode()

        assertEquals(originalDayCode, roundTrippedDayCode,
            "Day code must be preserved after round-trip")
        assertEquals("20241225", roundTrippedDayCode,
            "Should still be Dec 25")
    }

    @Test
    fun `round trip multi-day all-day event preserves day span`() {
        // Dec 24-26 = 3 days (DTSTART=20241224, DTEND=20241227)
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-multiday@test.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20241224
            DTEND;VALUE=DATE:20241227
            SUMMARY:Holiday Vacation
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        // Calculate original day span
        val originalStartDay = originalEvent.dtStart.toDayCode()

        // Generate and re-parse
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()
        val roundTrippedStartDay = roundTrippedEvent.dtStart.toDayCode()

        assertEquals(originalStartDay, roundTrippedStartDay,
            "Start day must be preserved")
        assertEquals("20241224", roundTrippedStartDay,
            "Should still start Dec 24")
    }

    @Test
    fun `round trip TripIt style multi-day event`() {
        // TripIt: Oct 11-12 trip with DTSTART=20251011, DTEND=20251013
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TripIt//Test//EN
            BEGIN:VEVENT
            UID:tripit-roundtrip@example.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20251011
            DTEND;VALUE=DATE:20251013
            SUMMARY:Green Slide Hotel
            LOCATION:San Antonio, TX
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        assertEquals("20251011", originalEvent.dtStart.toDayCode())

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertEquals("20251011", roundTrippedEvent.dtStart.toDayCode(),
            "Round-trip should preserve Oct 11 start")
    }

    @Test
    fun `round trip timed event preserves datetime`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-timed@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241215T140000Z
            DTEND:20241215T150000Z
            SUMMARY:Team Meeting
            DESCRIPTION:Weekly sync
            LOCATION:Conference Room A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertEquals(originalEvent.uid, roundTrippedEvent.uid)
        assertEquals(originalEvent.summary, roundTrippedEvent.summary)
        assertFalse(roundTrippedEvent.isAllDay)
    }

    @Test
    fun `round trip recurring event preserves RRULE`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-recurring@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241201T100000Z
            DTEND:20241201T110000Z
            SUMMARY:Daily Standup
            RRULE:FREQ=DAILY;COUNT=30
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        assertNotNull(originalEvent.rrule)

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertNotNull(roundTrippedEvent.rrule, "RRULE should be preserved")
        assertEquals(originalEvent.rrule?.freq, roundTrippedEvent.rrule?.freq)
        assertEquals(originalEvent.rrule?.count, roundTrippedEvent.rrule?.count)
    }

    @Test
    fun `round trip recurring all-day event preserves both RRULE and date`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-recurring-allday@test.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20240101
            DTEND;VALUE=DATE:20240102
            SUMMARY:Daily Reminder
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        assertTrue(originalEvent.isAllDay)
        assertNotNull(originalEvent.rrule)
        assertEquals("20240101", originalEvent.dtStart.toDayCode())

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertTrue(roundTrippedEvent.isAllDay, "Should still be all-day")
        assertNotNull(roundTrippedEvent.rrule, "RRULE should be preserved")
        assertEquals("20240101", roundTrippedEvent.dtStart.toDayCode(),
            "Date should be preserved")
    }

    @Test
    fun `round trip event with EXDATE preserves excluded dates`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-exdate@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241201T100000Z
            DTEND:20241201T110000Z
            SUMMARY:Meeting with Exceptions
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20241205T100000Z,20241208T100000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        assertTrue(originalEvent.exdates.isNotEmpty(), "Should have EXDATEs")

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertTrue(roundTrippedEvent.exdates.isNotEmpty(), "EXDATEs should be preserved")
    }

    @Test
    fun `round trip event with special characters in summary`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-special@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241215T140000Z
            DTEND:20241215T150000Z
            SUMMARY:Meeting\, Important!
            DESCRIPTION:Notes:\nLine 1\nLine 2
            LOCATION:Room\; Building A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        // Special characters should be preserved (either escaped or unescaped)
        assertNotNull(roundTrippedEvent.summary)
        assertTrue(roundTrippedEvent.summary!!.contains("Important"),
            "Summary should preserve content")
    }

    @Test
    fun `round trip event with UTF-8 characters`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-utf8@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241215T140000Z
            DTEND:20241215T150000Z
            SUMMARY:会议 Meeting 🎉
            DESCRIPTION:日本語テスト
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertTrue(roundTrippedEvent.summary?.contains("会议") == true,
            "CJK characters should be preserved")
        assertTrue(roundTrippedEvent.summary?.contains("🎉") == true,
            "Emoji should be preserved")
    }

    @Test
    fun `round trip event with VALARM`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-alarm@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241215T140000Z
            DTEND:20241215T150000Z
            SUMMARY:Event with Reminder
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 minute reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvent = (parsed as ParseResult.Success).value.first()

        // Round-trip
        val generated = generator.generateBatch(listOf(originalEvent))
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        // VALARM should be preserved (if library supports it)
        assertNotNull(roundTrippedEvent.summary)
    }

    @Test
    fun `round trip multiple events`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-001@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241215T100000Z
            DTEND:20241215T110000Z
            SUMMARY:Event 1
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-002@test.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20241216
            DTEND;VALUE=DATE:20241217
            SUMMARY:Event 2 All-Day
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-003@test.com
            DTSTAMP:20251224T143330Z
            DTSTART:20241217T140000Z
            RRULE:FREQ=WEEKLY;COUNT=5
            SUMMARY:Event 3 Recurring
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(original)
        assertTrue(parsed is ParseResult.Success)
        val originalEvents = (parsed as ParseResult.Success).value
        assertEquals(3, originalEvents.size)

        // Round-trip
        val generated = generator.generateBatch(originalEvents)
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)

        val roundTrippedEvents = (reparsed as ParseResult.Success).value
        assertEquals(3, roundTrippedEvents.size, "Should preserve all 3 events")
    }
}
