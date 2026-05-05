package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ParseResult
import java.time.Duration

/**
 * Roundtrip tests: Parse -> Generate -> Parse
 *
 * These tests verify that events can be parsed, regenerated as iCal,
 * and re-parsed without losing essential data.
 */
@DisplayName("ICalParser Roundtrip Tests")
class ICalParserRoundtripTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    @Nested
    @DisplayName("Simple Event Roundtrip")
    inner class SimpleEventRoundtripTests {

        @Test
        fun `simple event survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-simple-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Simple Meeting
                DESCRIPTION:A test description
                LOCATION:Conference Room A
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            // Generate
            val generatedIcal = generator.generate(event1, method = null)

            // Parse again
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            // Compare essential fields
            assertEquals(event1.uid, event2.uid)
            assertEquals(event1.summary, event2.summary)
            assertEquals(event1.description, event2.description)
            assertEquals(event1.location, event2.location)
            assertEquals(event1.isAllDay, event2.isAllDay)

            // DateTime comparison (timestamp should be close)
            assertEquals(event1.dtStart.timestamp, event2.dtStart.timestamp)
            assertEquals(event1.dtEnd?.timestamp, event2.dtEnd?.timestamp)
        }

        @Test
        fun `event with special characters in text survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-special-chars
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Meeting\, Important
                DESCRIPTION:Line 1\nLine 2\nLine 3
                LOCATION:Room\; Building A
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            // Special chars should be preserved
            assertEquals("Meeting, Important", event2.summary)
            assertTrue(event2.description!!.contains("\n"))
        }

        @Test
        fun `event with Unicode survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-unicode
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Meeting in Tokyo
                LOCATION:Tokyo
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.summary, event2.summary)
            assertEquals(event1.location, event2.location)
        }
    }

    @Nested
    @DisplayName("All-Day Event Roundtrip")
    inner class AllDayEventRoundtripTests {

        @Test
        fun `all-day event survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-allday-1
                DTSTAMP:20231215T100000Z
                DTSTART;VALUE=DATE:20231215
                DTEND;VALUE=DATE:20231216
                SUMMARY:All Day Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            assertTrue(event1.isAllDay)
            assertTrue(event1.dtStart.isDate)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            assertTrue(event2.isAllDay)
            assertTrue(event2.dtStart.isDate)
            assertEquals(event1.dtStart.toDayCode(), event2.dtStart.toDayCode())
        }

        @Test
        fun `multi-day all-day event survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-multiday
                DTSTAMP:20231215T100000Z
                DTSTART;VALUE=DATE:20231215
                DTEND;VALUE=DATE:20231218
                SUMMARY:Three Day Conference
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.dtStart.toDayCode(), event2.dtStart.toDayCode())
            assertEquals(event1.dtEnd?.toDayCode(), event2.dtEnd?.toDayCode())
        }
    }

    @Nested
    @DisplayName("Recurring Event Roundtrip")
    inner class RecurringEventRoundtripTests {

        @Test
        fun `event with simple RRULE survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-rrule-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                RRULE:FREQ=DAILY;COUNT=10
                SUMMARY:Daily Standup
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertNotNull(event1.rrule)
            assertEquals(Frequency.DAILY, event1.rrule!!.freq)
            assertEquals(10, event1.rrule!!.count)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertNotNull(event2.rrule)
            assertEquals(event1.rrule!!.freq, event2.rrule!!.freq)
            assertEquals(event1.rrule!!.count, event2.rrule!!.count)
        }

        @Test
        fun `event with complex RRULE survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-rrule-complex
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=20
                SUMMARY:MWF Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.rrule!!.freq, event2.rrule!!.freq)
            assertEquals(event1.rrule!!.count, event2.rrule!!.count)
            assertEquals(event1.rrule!!.byDay?.size, event2.rrule!!.byDay?.size)
        }

        @Test
        fun `event with EXDATE survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-exdate
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                RRULE:FREQ=DAILY;COUNT=10
                EXDATE:20231217T140000Z
                EXDATE:20231220T140000Z
                SUMMARY:Daily with Exceptions
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(2, event1.exdates.size)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.exdates.size, event2.exdates.size)
        }
    }

    @Nested
    @DisplayName("Event with DURATION Roundtrip")
    inner class DurationRoundtripTests {

        @Test
        fun `event with DURATION survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-duration-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DURATION:PT1H30M
                SUMMARY:90 Minute Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            assertNotNull(event1.duration)
            assertEquals(Duration.ofMinutes(90), event1.duration)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            // DURATION should be preserved
            assertNotNull(event2.duration)
            assertEquals(event1.duration, event2.duration)
        }
    }

    @Nested
    @DisplayName("Event with VALARM Roundtrip")
    inner class AlarmRoundtripTests {

        @Test
        fun `event with single alarm survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-alarm-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Meeting with Reminder
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:15 minute reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(1, event1.alarms.size)
            assertEquals(AlarmAction.DISPLAY, event1.alarms[0].action)
            assertEquals(-15, event1.alarms[0].trigger!!.toMinutes())

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.alarms.size, event2.alarms.size)
            assertEquals(event1.alarms[0].action, event2.alarms[0].action)
            assertEquals(event1.alarms[0].trigger?.toMinutes(), event2.alarms[0].trigger?.toMinutes())
        }

        @Test
        fun `event with multiple alarms survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-multi-alarm
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Important Meeting
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT1H
                DESCRIPTION:1 hour reminder
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:15 minute reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(2, event1.alarms.size)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.alarms.size, event2.alarms.size)
        }
    }

    @Nested
    @DisplayName("Event with Metadata Roundtrip")
    inner class MetadataRoundtripTests {

        @Test
        fun `event with categories survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-categories
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Work Meeting
                CATEGORIES:WORK,IMPORTANT
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertTrue(event1.categories.isNotEmpty())

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.categories.size, event2.categories.size)
        }

        @Test
        fun `event with color survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-color
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Colored Event
                COLOR:red
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals("red", event1.color)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.color, event2.color)
        }

        @Test
        fun `event with URL survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-url
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event with Link
                URL:https://example.com/meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals("https://example.com/meeting", event1.url)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.url, event2.url)
        }

        @Test
        fun `event status survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-status
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Tentative Meeting
                STATUS:TENTATIVE
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(EventStatus.TENTATIVE, event1.status)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.status, event2.status)
        }
    }

    @Nested
    @DisplayName("PRIORITY and GEO Roundtrip")
    inner class PriorityGeoRoundtripTests {

        @Test
        fun `event with priority survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-priority
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:High Priority Meeting
                PRIORITY:1
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(1, event1.priority)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.priority, event2.priority)
        }

        @Test
        fun `event with priority 0 survives roundtrip without PRIORITY property`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-no-priority
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:No Priority Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(0, event1.priority)

            val generatedIcal = generator.generate(event1, method = null)

            // PRIORITY:0 should NOT be in output (0 = undefined)
            assertFalse(generatedIcal.contains("PRIORITY:"))

            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(0, event2.priority)
        }

        @Test
        fun `event with geo survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-geo
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Meeting at Apple Park
                GEO:37.386013;-122.082932
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals("37.386013;-122.082932", event1.geo)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.geo, event2.geo)
        }

        @Test
        fun `event with negative geo coordinates survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-geo-negative
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Meeting in Sydney
                GEO:-33.8688;151.2093
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals("-33.8688;151.2093", event1.geo)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.geo, event2.geo)
        }

        @Test
        fun `event with both priority and geo survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-priority-geo
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Important Meeting at Location
                PRIORITY:2
                GEO:40.7128;-74.0060
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            assertTrue(parseResult1 is ParseResult.Success)
            val event1 = parseResult1.getOrNull()!![0]

            assertEquals(2, event1.priority)
            assertEquals("40.7128;-74.0060", event1.geo)

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            assertTrue(parseResult2 is ParseResult.Success)
            val event2 = parseResult2.getOrNull()!![0]

            assertEquals(event1.priority, event2.priority)
            assertEquals(event1.geo, event2.geo)
        }

        @Test
        fun `event without geo survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-no-geo
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Meeting without Location
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertNull(event1.geo)

            val generatedIcal = generator.generate(event1, method = null)

            // GEO should NOT be in output when null
            assertFalse(generatedIcal.contains("GEO:"))

            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertNull(event2.geo)
        }
    }

    @Nested
    @DisplayName("Modified Instance (RECURRENCE-ID) Roundtrip")
    inner class ModifiedInstanceRoundtripTests {

        @Test
        fun `modified recurring instance survives roundtrip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-recid
                DTSTAMP:20231215T100000Z
                RECURRENCE-ID:20231217T140000Z
                DTSTART:20231217T150000Z
                DTEND:20231217T160000Z
                SUMMARY:Rescheduled Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parseResult1 = parser.parseAllEvents(originalIcal)
            val event1 = parseResult1.getOrNull()!![0]

            assertNotNull(event1.recurrenceId)
            assertTrue(event1.importId.contains("RECID"))

            val generatedIcal = generator.generate(event1, method = null)
            val parseResult2 = parser.parseAllEvents(generatedIcal)
            val event2 = parseResult2.getOrNull()!![0]

            assertNotNull(event2.recurrenceId)
            assertEquals(event1.uid, event2.uid)
        }
    }
}
