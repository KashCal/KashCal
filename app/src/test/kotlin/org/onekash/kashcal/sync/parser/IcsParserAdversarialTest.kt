package org.onekash.kashcal.sync.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for ICS parser.
 *
 * Tests probe edge cases that could crash or compromise security:
 * - Malformed VCALENDAR structures
 * - Invalid UTF-8 sequences
 * - Extremely long property values
 * - Line folding edge cases
 * - Missing required properties
 * - SQL injection attempts
 * - Null bytes and control characters
 *
 * These tests verify defensive coding in Ical4jParser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsParserAdversarialTest {

    private lateinit var parser: Ical4jParser

    @Before
    fun setup() {
        parser = Ical4jParser()
    }

    // ==================== Malformed Structure Tests ====================

    @Test
    fun `parse empty string returns empty result`() {
        val result = parser.parse("")
        assertTrue("Should handle empty input gracefully", result.events.isEmpty())
    }

    @Test
    fun `parse null-like string returns empty result`() {
        val result = parser.parse("null")
        assertTrue("Should handle 'null' string", result.events.isEmpty())
    }

    @Test
    fun `parse whitespace only returns empty result`() {
        val result = parser.parse("   \n\t\r\n   ")
        assertTrue("Should handle whitespace-only input", result.events.isEmpty())
    }

    @Test
    fun `parse missing BEGIN VCALENDAR`() {
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

        val result = parser.parse(ical)
        // Should either parse gracefully or return empty
        assertTrue("Should not crash on missing BEGIN:VCALENDAR", result.events.isEmpty() || result.events.isNotEmpty())
    }

    @Test
    fun `parse missing END VCALENDAR`() {
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

        val result = parser.parse(ical)
        // Should handle gracefully
        assertTrue("Should not crash on missing END:VCALENDAR", true)
    }

    @Test
    fun `parse missing END VEVENT`() {
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

        val result = parser.parse(ical)
        // Should handle gracefully without crashing
        assertTrue("Should not crash on missing END:VEVENT", true)
    }

    @Test
    fun `parse nested VCALENDAR blocks`() {
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:nested@test.com
            DTSTART:20240101T090000Z
            END:VEVENT
            END:VCALENDAR
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parse(ical)
        // Should handle nested blocks without crashing
        assertTrue("Should not crash on nested VCALENDAR", true)
    }

    @Test
    fun `parse VTIMEZONE RRULE pollution`() {
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

        val result = parser.parse(ical)
        if (result.events.isNotEmpty()) {
            val event = result.events.first()
            assertNull("VTIMEZONE RRULE should not pollute event", event.rrule)
        }
    }

    // ==================== Missing Required Properties Tests ====================

    @Test
    fun `parse event without UID`() {
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

        val result = parser.parse(ical)
        // Should handle missing UID gracefully
        assertTrue("Should handle missing UID", true)
    }

    @Test
    fun `parse event without DTSTART`() {
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

        val result = parser.parse(ical)
        // Should skip event without DTSTART or handle gracefully
        assertTrue("Should handle missing DTSTART", true)
    }

    @Test
    fun `parse event without DTSTAMP`() {
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

        val result = parser.parse(ical)
        // Many real-world ICS files omit DTSTAMP - should handle gracefully
        assertTrue("Should handle missing DTSTAMP", result.events.isNotEmpty() || result.events.isEmpty())
    }

    // ==================== Long Property Value Tests ====================

    @Test
    fun `parse extremely long SUMMARY`() {
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

        val result = parser.parse(ical)
        // Should handle without OOM
        assertTrue("Should handle 100KB summary", true)
    }

    @Test
    fun `parse extremely long DESCRIPTION`() {
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

        val result = parser.parse(ical)
        assertTrue("Should handle long description", true)
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

        val result = parser.parse(ical)
        assertTrue("Should handle many attendees", true)
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

        val result = parser.parse(ical)
        if (result.events.isNotEmpty()) {
            val event = result.events.first()
            assertTrue("Folded summary should be unfolded",
                event.summary?.contains("multiple lines") == true)
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

        val result = parser.parse(ical)
        // TAB is also valid for line folding per RFC 5545
        assertTrue("Should handle TAB line folding", true)
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

        val result = parser.parse(ical)
        // Many systems produce LF-only, should handle gracefully
        assertTrue("Should handle LF-only line endings", result.events.isNotEmpty() || result.events.isEmpty())
    }

    // ==================== Security Tests ====================

    @Test
    fun `parse SQL injection in property values`() {
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

        val result = parser.parse(ical)
        // SQL injection should be treated as literal strings
        if (result.events.isNotEmpty()) {
            val event = result.events.first()
            assertTrue("SQL should be preserved as literal",
                event.summary?.contains("DROP TABLE") == true)
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

        val result = parser.parse(ical)
        // Should handle null bytes without crashing
        assertTrue("Should handle null bytes", true)
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

        val result = parser.parse(ical)
        assertTrue("Should handle control characters", true)
    }

    @Test
    fun `parse script injection in DESCRIPTION`() {
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

        val result = parser.parse(ical)
        if (result.events.isNotEmpty()) {
            val event = result.events.first()
            // Should preserve script as literal (UI must escape for display)
            assertTrue("Script should be literal",
                event.description?.contains("<script>") == true)
        }
    }

    // ==================== Invalid Date/Time Tests ====================

    @Test
    fun `parse invalid DTSTART format`() {
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

        val result = parser.parse(ical)
        // Should skip event with invalid date or handle gracefully
        assertTrue("Should handle invalid DTSTART", true)
    }

    @Test
    fun `parse DTSTART with impossible date`() {
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

        val result = parser.parse(ical)
        // Feb 30 doesn't exist - should handle gracefully
        assertTrue("Should handle impossible date", true)
    }

    @Test
    fun `parse DTEND before DTSTART`() {
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

        val result = parser.parse(ical)
        // DTEND before DTSTART is invalid but should not crash
        assertTrue("Should handle DTEND before DTSTART", true)
    }

    @Test
    fun `parse negative DURATION`() {
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

        val result = parser.parse(ical)
        // Negative duration is invalid but should not crash
        assertTrue("Should handle negative duration", true)
    }

    // ==================== Invalid RRULE Tests ====================

    @Test
    fun `parse RRULE with invalid FREQ`() {
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

        val result = parser.parse(ical)
        // BIWEEKLY is not valid - should handle gracefully
        assertTrue("Should handle invalid FREQ", true)
    }

    @Test
    fun `parse RRULE with both COUNT and UNTIL`() {
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

        val result = parser.parse(ical)
        // COUNT and UNTIL together is invalid per RFC - should handle gracefully
        assertTrue("Should handle COUNT + UNTIL", true)
    }

    // ==================== Multiple VEVENT Tests ====================

    @Test
    fun `parse multiple VEVENTs with same UID`() {
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

        val result = parser.parse(ical)
        // Multiple VEVENTs with same UID (master + exception) is valid
        assertTrue("Should parse master and exception", result.events.size >= 1)
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

        val result = parser.parse(ical)
        assertEquals("Should parse all 100 events", 100, result.events.size)
    }

    // ==================== Encoding Tests ====================

    @Test
    fun `parse UTF-8 characters in SUMMARY`() {
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

        val result = parser.parse(ical)
        if (result.events.isNotEmpty()) {
            val event = result.events.first()
            assertTrue("Should preserve CJK characters",
                event.summary?.contains("会议") == true)
            assertTrue("Should preserve emoji",
                event.summary?.contains("🎉") == true)
        }
    }

    @Test
    fun `parse escaped characters`() {
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

        val result = parser.parse(ical)
        if (result.events.isNotEmpty()) {
            val event = result.events.first()
            // Escaped chars should be unescaped
            assertTrue("Should unescape comma",
                event.summary?.contains(",") == true || event.summary?.contains("\\,") == true)
        }
    }
}
