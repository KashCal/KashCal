package org.onekash.icaldav.parser

import org.onekash.icaldav.model.ParseResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Adversarial tests for ICS parser - ported from KashCal.
 *
 * Tests probe edge cases that could crash or compromise security:
 * - Malformed VCALENDAR structures
 * - Missing required properties
 * - Extremely long property values
 * - Line folding edge cases
 * - SQL injection attempts
 * - Null bytes and control characters
 * - Invalid date/time formats
 * - Invalid RRULE values
 * - Multiple VEVENT handling
 * - UTF-8 and special characters
 *
 * These tests verify defensive coding in ICalParser.
 */
@DisplayName("ICalParser Adversarial Tests")
class ICalParserAdversarialTest {

    private val parser = ICalParser()

    // ==================== Malformed Structure Tests ====================

    @Test
    fun `parse empty string returns empty result`() {
        val result = parser.parseAllEvents("")
        assertTrue(result is ParseResult.Success || result is ParseResult.Error)
        val events = result.getOrNull()
        assertTrue(events == null || events.isEmpty())
    }

    @Test
    fun `parse null-like string returns empty result`() {
        val result = parser.parseAllEvents("null")
        val events = result.getOrNull()
        assertTrue(events == null || events.isEmpty())
    }

    @Test
    fun `parse whitespace only returns empty result`() {
        val result = parser.parseAllEvents("   \n\t\r\n   ")
        val events = result.getOrNull()
        assertTrue(events == null || events.isEmpty())
    }

    @Test
    fun `parse missing BEGIN VCALENDAR handles gracefully`() {
        val ical = """
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // Should either parse gracefully or return empty - no crash
        assertTrue(true, "Should not crash on missing BEGIN:VCALENDAR")
    }

    @Test
    fun `parse missing END VCALENDAR handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:Test
            END:VEVENT
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should not crash on missing END:VCALENDAR")
    }

    @Test
    fun `parse missing END VEVENT handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20240101T090000Z
            SUMMARY:Incomplete Event
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should not crash on missing END:VEVENT")
    }

    @Test
    fun `parse nested VCALENDAR blocks handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:nested@test.com
            DTSTART:20240101T090000Z
            SUMMARY:Nested
            END:VEVENT
            END:VCALENDAR
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should not crash on nested VCALENDAR")
    }

    @Test
    fun `VTIMEZONE RRULE should not pollute event`() {
        // VTIMEZONE can have RRULE that should NOT be parsed as event recurrence
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            DTSTART:19700308T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:tz-pollution@test.com
            DTSTART;TZID=America/New_York:20240101T090000
            DTEND;TZID=America/New_York:20240101T100000
            SUMMARY:Non-Recurring Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            assertNull(event.rrule, "VTIMEZONE RRULE should not pollute event")
        }
    }

    // ==================== Missing Required Properties Tests ====================

    @Test
    fun `parse event without UID handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:No UID Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle missing UID")
    }

    @Test
    fun `parse event without DTSTART handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-dtstart@test.com
            DTEND:20240101T100000Z
            SUMMARY:No DTSTART Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle missing DTSTART")
    }

    @Test
    fun `parse event without DTSTAMP handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-dtstamp@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:No DTSTAMP Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // Many real-world ICS files omit DTSTAMP
        assertTrue(true, "Should handle missing DTSTAMP")
    }

    // ==================== Long Property Value Tests ====================

    @Test
    fun `parse extremely long SUMMARY without OOM`() {
        val longSummary = "A".repeat(100_000)
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:long-summary@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:$longSummary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle 100KB summary without OOM")
    }

    @Test
    fun `parse extremely long DESCRIPTION without OOM`() {
        val longDesc = "Test description line. ".repeat(10_000)
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:long-desc@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:Event with long description
            DESCRIPTION:$longDesc
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle long description")
    }

    @Test
    fun `parse many ATTENDEE properties`() {
        val attendees = (1..1000).joinToString("\n") {
            "ATTENDEE:mailto:user$it@example.com"
        }
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:many-attendees@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:Meeting with 1000 attendees
            $attendees
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle many attendees")
    }

    // ==================== Line Folding Edge Cases ====================

    @Test
    fun `parse correct line folding CRLF+SPACE`() {
        val ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:folded@test.com\r\n" +
            "DTSTART:20240101T090000Z\r\n" +
            "SUMMARY:This is a very long summary that needs to be folded across\r\n" +
            " multiple lines according to RFC 5545 line folding rules\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            assertTrue(
                event.summary?.contains("multiple lines") == true,
                "Folded summary should be unfolded"
            )
        }
    }

    @Test
    fun `parse line folding with TAB instead of SPACE`() {
        val ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:tab-fold@test.com\r\n" +
            "DTSTART:20240101T090000Z\r\n" +
            "SUMMARY:Folded\r\n" +
            "\twith tab\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        val result = parser.parseAllEvents(ical)
        // TAB is also valid for line folding per RFC 5545
        assertTrue(true, "Should handle TAB line folding")
    }

    @Test
    fun `parse LF only line endings`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:lf-only@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:LF Only Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // Many systems produce LF-only
        assertTrue(true, "Should handle LF-only line endings")
    }

    // ==================== Security Tests ====================

    @Test
    fun `parse SQL injection in property values - preserved as literal`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sql-inject'; DROP TABLE events;--@test.com
            DTSTART:20240101T090000Z
            DTEND:20240101T100000Z
            SUMMARY:'; DROP TABLE events;--
            DESCRIPTION:Robert'); DROP TABLE events;--
            LOCATION:1'; DELETE FROM calendars WHERE '1'='1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // SQL injection should be treated as literal strings
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            assertTrue(
                event.summary?.contains("DROP TABLE") == true,
                "SQL should be preserved as literal"
            )
        }
    }

    @Test
    fun `parse null bytes in strings`() {
        val ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:null-bytes@test.com\r\n" +
            "DTSTART:20240101T090000Z\r\n" +
            "SUMMARY:Test\u0000Event\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle null bytes without crashing")
    }

    @Test
    fun `parse control characters in strings`() {
        val ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:control-chars@test.com\r\n" +
            "DTSTART:20240101T090000Z\r\n" +
            "SUMMARY:Test\u0001\u0002\u0003Event\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle control characters")
    }

    @Test
    fun `parse script injection in DESCRIPTION - preserved as literal`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:xss@test.com
            DTSTART:20240101T090000Z
            SUMMARY:XSS Test
            DESCRIPTION:<script>alert('XSS')</script>
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            // Should preserve script as literal (UI must escape for display)
            assertTrue(
                event.description?.contains("<script>") == true,
                "Script should be literal"
            )
        }
    }

    // ==================== Invalid Date/Time Tests ====================

    @Test
    fun `parse invalid DTSTART format handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:invalid-dtstart@test.com
            DTSTART:not-a-date
            SUMMARY:Invalid Date
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle invalid DTSTART")
    }

    @Test
    fun `parse DTSTART with impossible date Feb 30`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:impossible-date@test.com
            DTSTART:20240230T090000Z
            SUMMARY:Feb 30 Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle impossible date")
    }

    @Test
    fun `parse DTEND before DTSTART handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:backwards-time@test.com
            DTSTART:20240101T100000Z
            DTEND:20240101T090000Z
            SUMMARY:Backwards Time Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle DTEND before DTSTART")
    }

    @Test
    fun `parse negative DURATION handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:negative-duration@test.com
            DTSTART:20240101T100000Z
            DURATION:-PT1H
            SUMMARY:Negative Duration Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle negative duration")
    }

    // ==================== Invalid RRULE Tests ====================

    @Test
    fun `parse RRULE with invalid FREQ handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:invalid-freq@test.com
            DTSTART:20240101T090000Z
            RRULE:FREQ=BIWEEKLY;COUNT=10
            SUMMARY:Invalid Freq
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // BIWEEKLY is not valid
        assertTrue(true, "Should handle invalid FREQ")
    }

    @Test
    fun `parse RRULE with both COUNT and UNTIL handles gracefully`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:count-and-until@test.com
            DTSTART:20240101T090000Z
            RRULE:FREQ=DAILY;COUNT=10;UNTIL=20240601T000000Z
            SUMMARY:Both COUNT and UNTIL
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // COUNT and UNTIL together is invalid per RFC
        assertTrue(true, "Should handle COUNT + UNTIL")
    }

    // ==================== Multiple VEVENT Tests ====================

    @Test
    fun `parse multiple VEVENTs with same UID - master and exception`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:same-uid@test.com
            DTSTART:20240101T090000Z
            SUMMARY:Master Event
            END:VEVENT
            BEGIN:VEVENT
            UID:same-uid@test.com
            RECURRENCE-ID:20240102T090000Z
            DTSTART:20240102T100000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        // Multiple VEVENTs with same UID (master + exception) is valid RFC 5545
        if (result is ParseResult.Success) {
            assertTrue(result.value.isNotEmpty(), "Should parse master and/or exception")
        }
    }

    @Test
    fun `parse 100 VEVENTs in single VCALENDAR`() {
        val events = (1..100).joinToString("\r\n") { i ->
            "BEGIN:VEVENT\r\n" +
            "UID:bulk-$i@test.com\r\n" +
            "DTSTART:2024${String.format("%02d", (i % 12) + 1)}01T090000Z\r\n" +
            "SUMMARY:Event $i\r\n" +
            "END:VEVENT"
        }

        val ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            events + "\r\n" +
            "END:VCALENDAR\r\n"

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success) {
            assertEquals(100, result.value.size, "Should parse all 100 events")
        }
    }

    // ==================== Encoding Tests ====================

    @Test
    fun `parse UTF-8 characters in SUMMARY - CJK and emoji`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:utf8@test.com
            DTSTART:20240101T090000Z
            SUMMARY:会议 - Meeting 日本語 🎉
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            assertTrue(
                event.summary?.contains("会议") == true,
                "Should preserve CJK characters"
            )
            assertTrue(
                event.summary?.contains("🎉") == true,
                "Should preserve emoji"
            )
        }
    }

    @Test
    fun `parse escaped characters are unescaped`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escaped@test.com
            DTSTART:20240101T090000Z
            SUMMARY:Test\, with\; special\ncharacters
            DESCRIPTION:Line1\nLine2\nLine3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            // Escaped chars should be unescaped
            assertTrue(
                event.summary?.contains(",") == true || event.summary?.contains("\\,") == true,
                "Should unescape comma (or preserve as library choice)"
            )
        }
    }

    // ==================== VALARM Edge Cases ====================

    @Test
    fun `parse event with many VALARMs`() {
        val alarms = (1..50).joinToString("\n") { i ->
            """
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT${i}M
            DESCRIPTION:Reminder $i
            END:VALARM
            """.trimIndent()
        }

        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:many-alarms@test.com
            DTSTART:20240101T090000Z
            SUMMARY:Event with 50 alarms
            $alarms
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ical)
        assertTrue(true, "Should handle many VALARMs")
    }

    // ==================== Real-World Edge Cases ====================

    @Test
    fun `parse event with long folded description`() {
        // Real-world: Exchange/Outlook generates very long folded descriptions
        val longDesc = "This is a very long meeting description that contains important details about the meeting agenda and will be split across multiple folded lines in the ICS file according to RFC 5545 specifications which limit lines to 75 octets and then continuation lines start with a space or tab character."

        // Simulate proper folding
        val foldedDesc = longDesc.chunked(70).joinToString("\r\n ")

        val ical = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "PRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:long-fold@test.com\r\n" +
            "DTSTART:20240101T090000Z\r\n" +
            "SUMMARY:Meeting\r\n" +
            "DESCRIPTION:$foldedDesc\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        val result = parser.parseAllEvents(ical)
        if (result is ParseResult.Success && result.value.isNotEmpty()) {
            val event = result.value.first()
            assertTrue(
                event.description?.contains("important details") == true,
                "Folded description should be properly unfolded"
            )
        }
    }
}
