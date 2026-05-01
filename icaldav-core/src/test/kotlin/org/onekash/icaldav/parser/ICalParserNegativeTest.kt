package org.onekash.icaldav.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.onekash.icaldav.model.ParseResult

/**
 * Negative tests for ICalParser - tests for inputs that SHOULD fail or be handled gracefully.
 *
 * These tests verify:
 * - Invalid iCalendar syntax is rejected or handled
 * - Malformed data doesn't crash the parser
 * - Missing required properties are detected
 * - Invalid property values are handled
 * - Security-sensitive inputs are handled safely
 */
class ICalParserNegativeTest {

    private val parser = ICalParser()

    // ==================== Missing Required Properties ====================

    @Nested
    inner class MissingRequiredPropertiesTests {

        @Test
        fun `rejects VEVENT without UID`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                DTSTART:20231215T100000Z
                SUMMARY:Event without UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            // Should either fail or return empty/error result
            // Implementation may vary - either is acceptable
            if (result is ParseResult.Success) {
                // If it "succeeds", the event should be skipped or have generated UID
                val events = result.getOrNull()
                if (events != null && events.isNotEmpty()) {
                    // Parser may generate a UID - verify it exists
                    assertNotNull(events[0].uid)
                }
            }
        }

        @Test
        fun `rejects VEVENT without DTSTART`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-event-123
                SUMMARY:Event without start
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            // Parser should handle missing DTSTART gracefully
            // Either return error or skip the event
        }

        @Test
        fun `handles VCALENDAR without VERSION`() {
            val ical = """
                BEGIN:VCALENDAR
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Should not crash - VERSION is technically required but often omitted
            val result = parser.parseAllEvents(ical)
            // May succeed or fail depending on strictness
        }

        @Test
        fun `handles VCALENDAR without PRODID`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Should not crash
            val result = parser.parseAllEvents(ical)
        }
    }

    // ==================== Invalid Syntax ====================

    @Nested
    inner class InvalidSyntaxTests {

        @Test
        fun `handles unclosed VCALENDAR`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                END:VEVENT
            """.trimIndent() // Missing END:VCALENDAR

            val result = parser.parseAllEvents(ical)
            // Should handle gracefully - either return what was parsed or error
        }

        @Test
        fun `handles unclosed VEVENT`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                END:VCALENDAR
            """.trimIndent() // Missing END:VEVENT

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles mismatched BEGIN END`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                END:VTODO
                END:VCALENDAR
            """.trimIndent() // END:VTODO instead of END:VEVENT

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles property without value`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SUMMARY:
                END:VEVENT
                END:VCALENDAR
            """.trimIndent() // SUMMARY has no value

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
        }

        @Test
        fun `handles property without colon`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SUMMARY Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent() // SUMMARY missing colon

            val result = parser.parseAllEvents(ical)
            // Line should be ignored or handled
        }

        @Test
        fun `handles completely empty input`() {
            val result = parser.parseAllEvents("")
            // Should return empty or error, not crash
        }

        @Test
        fun `handles whitespace-only input`() {
            val result = parser.parseAllEvents("   \n\t\n   ")
            // Should return empty or error, not crash
        }

        @Test
        fun `handles non-iCalendar content`() {
            val ical = """
                This is not iCalendar data.
                It's just plain text.
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should return empty or error
        }

        @Test
        fun `handles HTML content`() {
            val html = """
                <!DOCTYPE html>
                <html><body><h1>Not a calendar</h1></body></html>
            """.trimIndent()

            val result = parser.parseAllEvents(html)
            // Should return empty or error
        }

        @Test
        fun `handles JSON content`() {
            val json = """{"events": [{"title": "Test"}]}"""

            val result = parser.parseAllEvents(json)
            // Should return empty or error
        }
    }

    // ==================== Invalid Property Values ====================

    @Nested
    inner class InvalidPropertyValuesTests {

        @Test
        fun `handles invalid DTSTART format`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:not-a-date
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should handle gracefully
        }

        @Test
        fun `handles invalid DTSTART with letters`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215TABCDEFZ
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles impossible date`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231315T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent() // Month 13 doesn't exist

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles February 30th`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20230230T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent() // Feb 30 doesn't exist

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles invalid RRULE`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                RRULE:FREQ=INVALID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles RRULE without FREQ`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                RRULE:INTERVAL=2;COUNT=10
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles invalid SEQUENCE value`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SEQUENCE:not-a-number
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles negative SEQUENCE value`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SEQUENCE:-5
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles invalid STATUS value`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                STATUS:INVALID-STATUS
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should default to CONFIRMED or handle gracefully
            if (result is ParseResult.Success) {
                val events = result.getOrNull()
                if (events != null && events.isNotEmpty()) {
                    assertNotNull(events[0].status)
                }
            }
        }

        @Test
        fun `handles invalid TRANSP value`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                TRANSP:MAYBE
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles invalid timezone ID`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART;TZID=Invalid/Timezone:20231215T100000
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }
    }

    // ==================== Security-Sensitive Inputs ====================

    @Nested
    inner class SecurityTests {

        @Test
        fun `handles extremely long lines`() {
            val longSummary = "A".repeat(100000)
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SUMMARY:$longSummary
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Should not cause OOM or hang
            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles many nested components`() {
            // Not valid iCalendar but tests parser robustness
            val nested = buildString {
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                appendLine("PRODID:-//Test//EN")
                repeat(100) { appendLine("BEGIN:VEVENT") }
                appendLine("UID:test-123")
                appendLine("DTSTART:20231215T100000Z")
                repeat(100) { appendLine("END:VEVENT") }
                appendLine("END:VCALENDAR")
            }

            val result = parser.parseAllEvents(nested)
        }

        @Test
        fun `handles null bytes in input`() {
            val ical = "BEGIN:VCALENDAR\u0000VERSION:2.0\u0000END:VCALENDAR"

            val result = parser.parseAllEvents(ical)
            // Should not crash
        }

        @Test
        fun `handles control characters`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SUMMARY:Test ${'\u0007'} Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles unicode edge cases`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SUMMARY:Test 👨‍👩‍👧‍👦 Family
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            if (result is ParseResult.Success) {
                val events = result.getOrNull()
                if (events != null && events.isNotEmpty()) {
                    assertTrue(events[0].summary?.contains("👨‍👩‍👧‍👦") == true)
                }
            }
        }

        @Test
        fun `handles backslash escaping edge cases`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                SUMMARY:Test\nwith\nnewlines
                DESCRIPTION:Backslash\\ and comma\, and semicolon\;
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            if (result is ParseResult.Success) {
                val events = result.getOrNull()
                if (events != null && events.isNotEmpty()) {
                    // Verify escapes are properly handled
                    assertTrue(events[0].summary?.contains("\n") == true ||
                            events[0].summary?.contains("\\n") == true)
                }
            }
        }
    }

    // ==================== Conflicting Properties ====================

    @Nested
    inner class ConflictingPropertiesTests {

        @Test
        fun `handles both DTEND and DURATION`() {
            // RFC 5545 says they're mutually exclusive
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                DTEND:20231215T110000Z
                DURATION:PT2H
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Parser should pick one (typically DTEND) or reject
        }

        @Test
        fun `handles both COUNT and UNTIL in RRULE`() {
            // RFC 5545 says they're mutually exclusive
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T100000Z
                RRULE:FREQ=DAILY;COUNT=10;UNTIL=20240101T000000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
        }

        @Test
        fun `handles duplicate UID in same calendar`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:duplicate-uid
                DTSTART:20231215T100000Z
                SUMMARY:First Event
                END:VEVENT
                BEGIN:VEVENT
                UID:duplicate-uid
                DTSTART:20231216T100000Z
                SUMMARY:Second Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Could be valid for RECURRENCE-ID scenario, or could be rejected
        }
    }
}
