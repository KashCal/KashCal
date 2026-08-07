package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import java.time.Duration
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge case tests for ICalGenerator.
 *
 * Tests:
 * - Line folding with multi-byte characters
 * - Unicode handling
 * - Required field generation
 * - Special character escaping
 */
@DisplayName("ICalGenerator Edge Cases")
class ICalGeneratorEdgeCaseTest {

    private val generator = ICalGenerator()

    @Nested
    @DisplayName("Line Folding - Multi-byte Characters")
    inner class LineFoldingTests {

        @Test
        fun `line folding counts octets not characters - ASCII`() {
            // 80 ASCII characters should trigger folding at 75 octets
            val longSummary = "A".repeat(80)
            val event = createEvent(summary = longSummary)
            val ical = generator.generate(event, method = null)

            // Check that folding occurred (line break followed by space)
            assertTrue(ical.contains("\r\n ") || ical.contains("\n "),
                "Long ASCII line should be folded")
        }

        @Test
        fun `line folding handles Chinese characters correctly`() {
            // Chinese characters are 3 bytes each in UTF-8
            // 25 Chinese characters = 75 bytes, should be at the limit
            val chineseText = "\u4e2d".repeat(26) // 26 * 3 = 78 bytes, exceeds 75
            val event = createEvent(summary = chineseText)
            val ical = generator.generate(event, method = null)

            // Should be folded
            val summaryLine = ical.lines().find { it.startsWith("SUMMARY:") }
            // Folded lines start with space on continuation
            val hasFolding = ical.contains("SUMMARY:") &&
                    ical.lines().any { it.startsWith(" ") && it.contains("\u4e2d") }

            assertTrue(hasFolding || summaryLine?.length ?: 0 <= 75,
                "Chinese text should be properly folded or fit in line")
        }

        @Test
        fun `line folding handles emoji correctly`() {
            // Emoji are 4 bytes in UTF-8
            val emojiText = "\uD83D\uDCC5".repeat(20) // Calendar emoji, 20 * 4 = 80 bytes
            val event = createEvent(summary = "Meeting $emojiText")
            val ical = generator.generate(event, method = null)

            // Should not corrupt emoji
            assertTrue(ical.contains("\uD83D\uDCC5"), "Emoji should be preserved")
        }

        @Test
        fun `line folding does not split multi-byte character`() {
            // Create a string that would cause split in middle of UTF-8 sequence
            // if we counted characters instead of bytes
            val mixed = "A".repeat(73) + "\u4e2d\u4e2d" // 73 ASCII + 2 Chinese (6 bytes)
            val event = createEvent(summary = mixed)
            val ical = generator.generate(event, method = null)

            // Parse back and verify integrity
            assertTrue(ical.contains("\u4e2d"), "Chinese characters should be preserved")
        }

        @Test
        fun `very long description is properly folded`() {
            val longDesc = "Lorem ipsum ".repeat(100)
            val event = createEvent(description = longDesc)
            val ical = generator.generate(event, method = null)

            // Should have multiple fold points
            val foldCount = ical.windowed(2).count { it == "\n " }
            assertTrue(foldCount > 5, "Long description should have multiple folds")
        }

        @Test
        fun `location with special unicode is handled`() {
            val location = "Caf\u00e9 M\u00fcller, Stra\u00dfe 123, \u5317\u4eac"
            val event = createEvent(location = location)
            val ical = generator.generate(event, method = null)

            // Unfold and check content is preserved
            val unfolded = ical.replace("\r\n ", "").replace("\n ", "")
            assertTrue(unfolded.contains("Caf\u00e9"), "German umlaut should be preserved")
            assertTrue(unfolded.contains("\u5317\u4eac"), "Chinese characters should be preserved")
        }
    }

    @Nested
    @DisplayName("Text Escaping")
    inner class TextEscapingTests {

        @Test
        fun `commas are escaped in summary`() {
            val event = createEvent(summary = "Meeting, Important, Urgent")
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("\\,"), "Commas should be escaped")
        }

        @Test
        fun `semicolons are escaped`() {
            val event = createEvent(summary = "Task; Priority: High")
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("\\;"), "Semicolons should be escaped")
        }

        @Test
        fun `newlines are escaped`() {
            val event = createEvent(description = "Line 1\nLine 2\nLine 3")
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("\\n"), "Newlines should be escaped")
            assertFalse(ical.contains("DESCRIPTION:Line 1\nLine 2"),
                "Raw newlines should not appear in description value")
        }

        @Test
        fun `backslashes are escaped`() {
            val event = createEvent(summary = "Path: C:\\Users\\test")
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("\\\\"), "Backslashes should be escaped")
        }

        @Test
        fun `escaping order is correct - backslash first`() {
            // If we escape \n before \\, then "test\\nvalue" becomes "test\nvalue"
            val event = createEvent(summary = "test\\nvalue")
            val ical = generator.generate(event, method = null)

            // Should contain \\n (escaped backslash followed by n), not \n
            assertTrue(ical.contains("\\\\n"), "Backslash-n should become escaped backslash followed by n")
        }

        @Test
        fun `carriage returns do not leak into text values`() {
            // Pasted Windows/web text carries CRLF. A bare CR is a control char
            // excluded from RFC 5545 §3.1 VALUE-CHAR; it must not survive raw.
            val event = createEvent(description = "Line 1\r\nLine 2\rLine 3")
            val ical = generator.generate(event, method = null)

            assertFalse(ical.substringAfter("DESCRIPTION:").substringBefore("\r\n").contains('\r'),
                "No bare CR should remain inside the DESCRIPTION value")
            // CRLF and lone CR both collapse to a single escaped \n line break.
            assertTrue(ical.contains("Line 1\\nLine 2\\nLine 3"),
                "CRLF and lone CR should normalize to a single escaped newline each")
        }

        @Test
        fun `VALARM description escapes special characters`() {
            val event = createEvent(
                alarms = listOf(
                    ICalAlarm(
                        action = AlarmAction.DISPLAY,
                        trigger = java.time.Duration.ofMinutes(-15),
                        triggerAbsolute = null,
                        description = "Call Bob, Alice; bring notes\\files",
                        summary = null,
                    )
                )
            )
            val ical = generator.generate(event, method = null)

            val alarmBlock = ical.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
            assertTrue(alarmBlock.contains("\\,"), "VALARM DESCRIPTION comma should be escaped")
            assertTrue(alarmBlock.contains("\\;"), "VALARM DESCRIPTION semicolon should be escaped")
            assertTrue(alarmBlock.contains("\\\\"), "VALARM DESCRIPTION backslash should be escaped")
        }

        @Test
        fun `VALARM summary escapes special characters`() {
            val event = createEvent(
                alarms = listOf(
                    ICalAlarm(
                        action = AlarmAction.EMAIL,
                        trigger = java.time.Duration.ofMinutes(-15),
                        triggerAbsolute = null,
                        description = "Body",
                        summary = "Re: Lunch, Team; sync",
                    )
                )
            )
            val ical = generator.generate(event, method = null)

            val alarmBlock = ical.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
            assertTrue(alarmBlock.contains("SUMMARY:Re: Lunch\\, Team\\; sync"),
                "VALARM SUMMARY comma and semicolon should be escaped")
        }

        @Test
        fun `VALARM related-to escapes special characters`() {
            // RELATED-TO is TEXT-typed (§3.8.4.5); an opaque UID could carry a
            // separator char that must be escaped so re-parse doesn't mis-split it.
            val event = createEvent(
                alarms = listOf(
                    ICalAlarm(
                        action = AlarmAction.DISPLAY,
                        trigger = java.time.Duration.ofMinutes(-15),
                        triggerAbsolute = null,
                        description = "Snooze",
                        summary = null,
                        relatedTo = "uid;weird,value",
                    )
                )
            )
            val ical = generator.generate(event, method = null)

            val alarmBlock = ical.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
            assertTrue(alarmBlock.contains("RELATED-TO:uid\\;weird\\,value"),
                "VALARM RELATED-TO semicolon and comma should be escaped")
        }
    }

    @Nested
    @DisplayName("Required Fields for iCloud")
    inner class RequiredFieldsTests {

        @Test
        fun `CALSCALE is present`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("CALSCALE:GREGORIAN"))
        }

        @Test
        fun `METHOD is present when requested`() {
            val event = createEvent()
            val ical = generator.generate(event, method = ITipMethod.PUBLISH)

            assertTrue(ical.contains("METHOD:PUBLISH"))
        }

        @Test
        fun `METHOD is absent when not requested`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertFalse(ical.contains("METHOD:"))
        }

        @Test
        fun `STATUS is always present`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("STATUS:CONFIRMED"))
        }

        @Test
        fun `SEQUENCE is always present`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("SEQUENCE:0"))
        }

        @Test
        fun `DTSTAMP is always present and in UTC`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("DTSTAMP:"))
            // Should end with Z for UTC
            val dtstampLine = ical.lines().find { it.startsWith("DTSTAMP:") }
            assertTrue(dtstampLine?.endsWith("Z") == true)
        }

        @Test
        fun `UID is present`() {
            val event = createEvent(uid = "test-uid-12345")
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("UID:test-uid-12345"))
        }

        @Test
        fun `PRODID is present`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("PRODID:"))
        }

        @Test
        fun `VERSION is 2_0`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("VERSION:2.0"))
        }
    }

    @Nested
    @DisplayName("RECURRENCE-ID Handling")
    inner class RecurrenceIdTests {

        @Test
        fun `master event does not have RECURRENCE-ID`() {
            val event = createEvent(recurrenceId = null)
            val ical = generator.generate(event, method = null)

            assertFalse(ical.contains("RECURRENCE-ID:"))
        }

        @Test
        fun `modified instance has RECURRENCE-ID`() {
            val recurrenceId = ICalDateTime(
                timestamp = 1702000000000L,
                timezone = null,
                isUtc = true,
                isDate = false
            )
            val event = createEvent(recurrenceId = recurrenceId)
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("RECURRENCE-ID:"))
        }

        @Test
        fun `modified instance does not have RRULE`() {
            val recurrenceId = ICalDateTime(
                timestamp = 1702000000000L,
                timezone = null,
                isUtc = true,
                isDate = false
            )
            val rrule = RRule(
                freq = Frequency.WEEKLY,
                interval = 1
            )
            val event = createEvent(recurrenceId = recurrenceId, rrule = rrule)
            val ical = generator.generate(event, method = null)

            // Modified instances should NOT have RRULE even if passed
            assertFalse(ical.contains("RRULE:"))
            assertTrue(ical.contains("RECURRENCE-ID:"))
        }
    }

    @Nested
    @DisplayName("DateTime Formatting")
    inner class DateTimeFormattingTests {

        @Test
        fun `UTC datetime ends with Z`() {
            val event = createEvent()
            val ical = generator.generate(event, method = null)

            val dtstartLine = ical.lines().find { it.startsWith("DTSTART:") }
            assertTrue(dtstartLine?.endsWith("Z") == true)
        }

        @Test
        fun `all-day event uses DATE format`() {
            val dt = ICalDateTime(
                timestamp = 1702000000000L,
                timezone = null,
                isUtc = false,
                isDate = true
            )
            val event = createEvent(isAllDay = true, dtStart = dt)
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("DTSTART;VALUE=DATE:"))
            // Should not have time component (no "T" after the colon)
            val dtstartLine = ical.lines().find { it.contains("DTSTART") }
            val valueAfterColon = dtstartLine?.substringAfter(":") ?: ""
            assertFalse(valueAfterColon.contains("T"), "DATE value should not contain time component")
        }

        @Test
        fun `local datetime with TZID is formatted correctly`() {
            val dt = ICalDateTime(
                timestamp = 1702000000000L,
                timezone = ZoneId.of("America/New_York"),
                isUtc = false,
                isDate = false
            )
            val event = createEvent(dtStart = dt)
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("TZID=America/New_York"))
        }
    }

    @Nested
    @DisplayName("VALARM Generation")
    inner class ValarmTests {

        @Test
        fun `display alarm is generated correctly`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                triggerRelatedToEnd = false,
                description = "Reminder",
                summary = null,
                repeatCount = 0,
                repeatDuration = null
            )
            val event = createEvent(alarms = listOf(alarm))
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("BEGIN:VALARM"))
            assertTrue(ical.contains("ACTION:DISPLAY"))
            assertTrue(ical.contains("TRIGGER:-PT15M"))
            assertTrue(ical.contains("END:VALARM"))
        }

        @Test
        fun `trigger related to END is formatted correctly`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-5),
                triggerAbsolute = null,
                triggerRelatedToEnd = true,
                description = "End reminder",
                summary = null,
                repeatCount = 0,
                repeatDuration = null
            )
            val event = createEvent(alarms = listOf(alarm))
            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("TRIGGER;RELATED=END:"))
        }
    }

    // Helper function to create test events
    private fun createEvent(
        uid: String = "test-uid",
        summary: String? = "Test Event",
        description: String? = null,
        location: String? = null,
        isAllDay: Boolean = false,
        dtStart: ICalDateTime = ICalDateTime(
            timestamp = 1702000000000L,
            timezone = null,
            isUtc = true,
            isDate = isAllDay
        ),
        dtEnd: ICalDateTime? = ICalDateTime(
            timestamp = 1702003600000L,
            timezone = null,
            isUtc = true,
            isDate = isAllDay
        ),
        recurrenceId: ICalDateTime? = null,
        rrule: RRule? = null,
        alarms: List<ICalAlarm> = emptyList()
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = description,
            location = location,
            dtStart = dtStart,
            dtEnd = dtEnd,
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
            rawProperties = emptyMap()
        )
    }
}