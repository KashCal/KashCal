package org.onekash.icaldav.parser

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pre-tests for parsing gaps identified by comparing KashCal's icaldav parser with other CalDAV clients.
 * See: docs/PARSING_GAPS.md
 *
 * These tests document the EXPECTED behavior after fixes are applied.
 * Before implementation, some of these tests will FAIL — that's the point.
 * They verify that the gaps are real and that the fixes work.
 *
 * Gap 1: suppressInvalidProperties (ical4j ContentHandlerContext)
 * Gap 2: NOT A GAP — Outlook relaxation flags already work with ical4j.parsing.relaxed=true
 * Gap 3: Missing UID → should generate UUID instead of dropping event
 * Gap 4: Post-parse repair (missing DTSTART fallback, DTEND < DTSTART swap)
 * Gap 5: Stream preprocessing (malformed UTC offsets, durations)
 */
@DisplayName("Parsing Gaps Pre-Tests")
class ICalParserParsingGapsPreTest {

    private val parser = ICalParser()

    // ==================== Gap 1: suppressInvalidProperties ====================

    @Nested
    @DisplayName("Gap 1: Invalid properties should be suppressed, not kill entire parse")
    inner class SuppressInvalidPropertiesTests {

        @Test
        fun `event with one malformed X-property should still parse`() {
            // Real-world: Nextcloud/SOGo events often have non-standard X- properties
            // with values that ical4j's PropertyBuilder can't handle.
            // Without suppressInvalidProperties, the entire VCALENDAR parse fails.
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:good-event-123
                DTSTART:20231215T100000Z
                DTEND:20231215T110000Z
                SUMMARY:Team Meeting
                X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-APPLE-RADIUS=70.5
                 ;X-TITLE="Home":geo:37.332,-122.031
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result, "Parse should succeed despite malformed X-property")
            val events = result.getOrNull()
            assertNotNull(events, "Events list should not be null")
            assertEquals(1, events.size, "Should parse the event despite bad X-property")
            assertEquals("good-event-123", events[0].uid)
            assertEquals("Team Meeting", events[0].summary)
        }

        @Disabled("Unclosed quoted parameter (PARAM=\") breaks ical4j stream parser before property suppression can help")
        @Test
        fun `good event survives when sibling VEVENT has bad property`() {
            // Two events in one VCALENDAR: one clean, one with a malformed property.
            // Both should parse (bad property skipped), not zero events.
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:clean-event
                DTSTART:20231215T100000Z
                SUMMARY:Clean Event
                END:VEVENT
                BEGIN:VEVENT
                UID:messy-event
                DTSTART:20231216T100000Z
                SUMMARY:Messy Event
                X-CUSTOM-BAD;PARAM=":broken-value
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result, "Parse should succeed")
            val events = result.getOrNull()
            assertNotNull(events)
            // With suppressInvalidProperties, both events parse (bad property skipped)
            // Without it, the entire VCALENDAR fails → 0 events
            assertTrue(events.size >= 1, "At least the clean event should survive")
        }

        @Test
        fun `event with malformed VALARM property still parses`() {
            // Some servers emit VALARMs with invalid TRIGGER or ACTION values
            // that cause PropertyBuilder.build() to throw
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:event-with-bad-alarm
                DTSTART:20231215T100000Z
                SUMMARY:Has Bad Alarm
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER;VALUE=DATE-TIME:INVALID
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result, "Should not fail due to bad VALARM trigger")
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size, "Event should parse; bad alarm may be dropped")
            assertEquals("event-with-bad-alarm", events[0].uid)
        }

        @Test
        fun `Nextcloud event with non-standard ATTACH property parses`() {
            // Nextcloud sometimes emits ATTACH with unusual parameters
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Nextcloud//EN
                BEGIN:VEVENT
                UID:nc-event-456
                DTSTART:20231215T100000Z
                SUMMARY:Nextcloud Meeting
                ATTACH;FMTTYPE=application/octet-stream;FILENAME=notes.txt;SIZE=1234:https://cloud.example.com/remote.php/dav/files/user/notes.txt
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size)
            assertEquals("Nextcloud Meeting", events[0].summary)
        }
    }

    // ==================== Gap 2: NOT A GAP ====================
    // Outlook relaxation flags (ical4j.compatibility.outlook, ical4j.validation.relaxed,
    // negative_dst_supported) are NOT needed. The existing ical4j.parsing.relaxed=true
    // already handles Outlook quirks (spaces in BYDAY, quoted TZID).
    // Both test cases pass without changes. See docs/PARSING_GAPS.md.

    // ==================== Gap 3: Missing UID generation ====================

    @Nested
    @DisplayName("Gap 3: Events without UID should get a generated UUID")
    inner class MissingUidTests {

        @Test
        fun `event without UID gets generated UUID instead of being dropped`() {
            // Some CalDAV servers (especially older ones) occasionally omit UID
            // Other CalDAV clients generate a random UUID; KashCal currently drops the event
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                DTSTART:20231215T100000Z
                DTEND:20231215T110000Z
                SUMMARY:Event Without UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result)
            val events = result.getOrNull()
            assertNotNull(events, "Events should not be null")
            assertEquals(1, events.size, "Event should be parsed with generated UID, not dropped")
            assertTrue(events[0].uid.isNotEmpty(), "Generated UID should not be empty")
        }

        @Test
        fun `two events without UID get different generated UIDs`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                DTSTART:20231215T100000Z
                SUMMARY:First No-UID
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20231216T100000Z
                SUMMARY:Second No-UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(2, events.size, "Both events should be parsed")
            assertTrue(
                events[0].uid != events[1].uid,
                "Generated UIDs must be unique: ${events[0].uid} vs ${events[1].uid}"
            )
        }
    }

    // ==================== Gap 4: Post-parse repair ====================

    @Nested
    @DisplayName("Gap 4: Post-parse repair for common server bugs")
    inner class PostParseRepairTests {

        @Test
        fun `event without DTSTART but with DTEND uses DTEND as fallback`() {
            // Common repair: DTSTART = DTEND when DTSTART is missing
            // KashCal currently drops the event entirely
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:no-dtstart-event
                DTEND:20231215T110000Z
                SUMMARY:No Start Time
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size, "Event with DTEND but no DTSTART should be repaired, not dropped")
            assertNotNull(events[0].dtStart, "dtStart should be set from DTEND fallback")
        }

        @Test
        fun `event with DTEND before DTSTART gets times swapped`() {
            // Common repair: swap DTSTART/DTEND when end is before start
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:swapped-times
                DTSTART:20231215T150000Z
                DTEND:20231215T100000Z
                SUMMARY:End Before Start
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size)

            val event = events[0]
            assertTrue(
                event.dtEnd == null || event.dtEnd!!.timestamp >= event.dtStart.timestamp,
                "After repair, dtEnd (${event.dtEnd?.timestamp}) should be >= dtStart (${event.dtStart.timestamp})"
            )
        }

        @Test
        fun `exception event with RRULE has RRULE stripped`() {
            // RFC 5545: Exception events (with RECURRENCE-ID) should NOT have RRULE.
            // Should be stripped. Some servers incorrectly include it.
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:recurring-master
                DTSTART:20231215T100000Z
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Daily
                END:VEVENT
                BEGIN:VEVENT
                UID:recurring-master
                RECURRENCE-ID:20231216T100000Z
                DTSTART:20231216T140000Z
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Modified Instance
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(2, events.size)

            val exception = events.find { it.recurrenceId != null }
            assertNotNull(exception)
            // After repair, exception should not have RRULE
            assertTrue(
                exception.rrule == null,
                "Exception event should not have RRULE (was: ${exception.rrule})"
            )
        }
    }

    // ==================== Gap 5: Stream preprocessing ====================

    @Nested
    @DisplayName("Gap 5: Stream preprocessing for known server bugs")
    inner class StreamPreprocessingTests {

        @Test
        fun `malformed UTC offset with missing leading zeros parses`() {
            // Synology CalDAV sends "+530" instead of "+0530" (3-digit, missing leading zero)
            // Preprocessing pads 3-digit offsets to valid 4-digit HHMM format
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Synology//CalDAV//EN
                BEGIN:VTIMEZONE
                TZID:Asia/Kolkata
                BEGIN:STANDARD
                DTSTART:19700101T000000
                TZOFFSETFROM:+530
                TZOFFSETTO:+530
                END:STANDARD
                END:VTIMEZONE
                BEGIN:VEVENT
                UID:synology-event
                DTSTART;TZID=Asia/Kolkata:20231215T100000
                SUMMARY:Synology Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result, "Malformed UTC offset should be preprocessed and parsed")
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size)
        }

        @Test
        fun `malformed duration with misplaced T designator parses`() {
            // Some servers emit "-PT2D" instead of "-P2D"
            // Preprocessing fixes this before ical4j parsing
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:bad-duration-event
                DTSTART:20231215T100000Z
                SUMMARY:Has Bad Duration Alarm
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT2D
                DESCRIPTION:2 days before
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertIs<ParseResult.Success<*>>(result, "Malformed duration should be preprocessed and parsed")
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size)
        }
    }
}