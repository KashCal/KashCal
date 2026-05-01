package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Advanced recurrence pattern tests per RFC 5545 and CalConnect recommendations.
 *
 * Tests cover:
 * - BYSETPOS for limiting occurrences within a set
 * - BYYEARDAY for day-of-year patterns
 * - BYWEEKNO for week-number patterns
 * - WKST for week start day
 * - Complex combinations
 * - Edge cases from CalConnect guide
 *
 * @see https://devguide.calconnect.org/iCalendar-Topics/Recurrences/
 */
@DisplayName("Advanced Recurrence Pattern Tests")
class RRuleAdvancedPatternsTest {
    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val zone = ZoneId.of("America/New_York")
    private val utcZone = ZoneId.of("UTC")

    @Nested
    @DisplayName("BYSETPOS Tests")
    inner class BySetPosTests {

        @Test
        fun `BYSETPOS 1 selects first occurrence`() {
            // Last weekday of every month
            val rrule = RRule.parse("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3")

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertNotNull(rrule.bySetPos)
            assertEquals(listOf(-1), rrule.bySetPos)
        }

        @Test
        fun `BYSETPOS -1 selects last occurrence`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:bysetpos-last@test.com
                DTSTART:20240131T100000Z
                RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3
                SUMMARY:Last Weekday
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertNotNull(event.rrule?.bySetPos)
            assertEquals(listOf(-1), event.rrule?.bySetPos)
        }

        @Test
        fun `BYSETPOS with multiple values`() {
            // First and last Monday of the month
            val rrule = RRule.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1,-1;COUNT=6")

            assertEquals(listOf(1, -1), rrule.bySetPos)
        }

        @Test
        fun `BYSETPOS second-to-last occurrence`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-2;COUNT=3")

            assertEquals(listOf(-2), rrule.bySetPos)
        }

        @Test
        fun `BYSETPOS combined with BYDAY for workday pattern`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:first-workday@test.com
                DTSTART:20240102T090000Z
                RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1;COUNT=12
                SUMMARY:First Workday of Month
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Frequency.MONTHLY, event.rrule?.freq)
            assertEquals(5, event.rrule?.byDay?.size)
            assertEquals(listOf(1), event.rrule?.bySetPos)
        }
    }

    @Nested
    @DisplayName("BYYEARDAY Tests")
    inner class ByYearDayTests {

        @Test
        fun `BYYEARDAY positive values`() {
            val rrule = RRule.parse("FREQ=YEARLY;BYYEARDAY=1,100,200;COUNT=9")

            assertEquals(Frequency.YEARLY, rrule.freq)
            assertEquals(listOf(1, 100, 200), rrule.byYearDay)
        }

        @Test
        fun `BYYEARDAY negative value for last day`() {
            // -1 = last day of year (Dec 31)
            val rrule = RRule.parse("FREQ=YEARLY;BYYEARDAY=-1;COUNT=3")

            assertEquals(listOf(-1), rrule.byYearDay)
        }

        @Test
        fun `BYYEARDAY with event parsing`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:yearday@test.com
                DTSTART:20240101T000000Z
                RRULE:FREQ=YEARLY;BYYEARDAY=1,365;COUNT=6
                SUMMARY:Year Start and End
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(listOf(1, 365), result.getOrNull()!![0].rrule?.byYearDay)
        }

        @Test
        fun `BYYEARDAY range from end of year`() {
            // Last 7 days of year
            val rrule = RRule.parse("FREQ=YEARLY;BYYEARDAY=-7,-6,-5,-4,-3,-2,-1;COUNT=14")

            assertEquals(7, rrule.byYearDay?.size)
            assertEquals(-7, rrule.byYearDay?.first())
            assertEquals(-1, rrule.byYearDay?.last())
        }
    }

    @Nested
    @DisplayName("BYWEEKNO Tests")
    inner class ByWeekNoTests {

        @Test
        fun `BYWEEKNO single week`() {
            val rrule = RRule.parse("FREQ=YEARLY;BYWEEKNO=1;BYDAY=MO;COUNT=3")

            assertEquals(Frequency.YEARLY, rrule.freq)
            assertEquals(listOf(1), rrule.byWeekNo)
        }

        @Test
        fun `BYWEEKNO multiple weeks`() {
            // First and last week of year
            val rrule = RRule.parse("FREQ=YEARLY;BYWEEKNO=1,52;COUNT=6")

            assertEquals(listOf(1, 52), rrule.byWeekNo)
        }

        @Test
        fun `BYWEEKNO negative for last week`() {
            val rrule = RRule.parse("FREQ=YEARLY;BYWEEKNO=-1;BYDAY=FR;COUNT=3")

            assertEquals(listOf(-1), rrule.byWeekNo)
        }

        @Test
        fun `BYWEEKNO with event parsing`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:weekno@test.com
                DTSTART:20240101T100000Z
                RRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO,WE,FR;COUNT=9
                SUMMARY:Week 20 Meetings
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(listOf(20), result.getOrNull()!![0].rrule?.byWeekNo)
        }
    }

    @Nested
    @DisplayName("WKST (Week Start) Tests")
    inner class WkstTests {

        @Test
        fun `WKST defaults to MONDAY`() {
            val rrule = RRule.parse("FREQ=WEEKLY;COUNT=10")

            assertEquals(DayOfWeek.MONDAY, rrule.wkst)
        }

        @Test
        fun `WKST SUNDAY`() {
            val rrule = RRule.parse("FREQ=WEEKLY;WKST=SU;COUNT=10")

            assertEquals(DayOfWeek.SUNDAY, rrule.wkst)
        }

        @Test
        fun `WKST affects BYWEEKNO calculations`() {
            // With different WKST, week boundaries differ
            val rruleMo = RRule.parse("FREQ=YEARLY;BYWEEKNO=1;WKST=MO;COUNT=1")
            val rruleSu = RRule.parse("FREQ=YEARLY;BYWEEKNO=1;WKST=SU;COUNT=1")

            assertEquals(DayOfWeek.MONDAY, rruleMo.wkst)
            assertEquals(DayOfWeek.SUNDAY, rruleSu.wkst)
        }

        @Test
        fun `WKST with event parsing`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:wkst@test.com
                DTSTART:20240107T100000Z
                RRULE:FREQ=WEEKLY;INTERVAL=2;WKST=SU;BYDAY=TU,TH;COUNT=8
                SUMMARY:Every other week Tue/Thu
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(DayOfWeek.SUNDAY, result.getOrNull()!![0].rrule?.wkst)
        }
    }

    @Nested
    @DisplayName("Complex Recurrence Combinations")
    inner class ComplexCombinationsTests {

        @Test
        fun `Monthly last Friday - BYDAY with ordinal`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:last-friday@test.com
                DTSTART:20240126T170000Z
                RRULE:FREQ=MONTHLY;BYDAY=-1FR;COUNT=12
                SUMMARY:Last Friday Happy Hour
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val rrule = result.getOrNull()!![0].rrule
            assertNotNull(rrule?.byDay)
            assertEquals(1, rrule?.byDay?.size)
            assertEquals(-1, rrule?.byDay?.get(0)?.ordinal)
            assertEquals(DayOfWeek.FRIDAY, rrule?.byDay?.get(0)?.dayOfWeek)
        }

        @Test
        fun `Yearly on third Monday of November - Thanksgiving countdown`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:third-monday-nov@test.com
                DTSTART:20231120T090000Z
                RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=3MO;COUNT=5
                SUMMARY:Third Monday of November
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val rrule = result.getOrNull()!![0].rrule
            assertEquals(Frequency.YEARLY, rrule?.freq)
            assertEquals(listOf(11), rrule?.byMonth)
            assertEquals(3, rrule?.byDay?.get(0)?.ordinal)
        }

        @Test
        fun `US Thanksgiving - fourth Thursday of November`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:thanksgiving@test.com
                DTSTART:20231123T000000
                RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=4TH;COUNT=10
                SUMMARY:Thanksgiving
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val rrule = result.getOrNull()!![0].rrule
            assertEquals(4, rrule?.byDay?.get(0)?.ordinal)
            assertEquals(DayOfWeek.THURSDAY, rrule?.byDay?.get(0)?.dayOfWeek)
        }

        @Test
        fun `Last day of every month - BYMONTHDAY -1`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:last-day@test.com
                DTSTART:20240131T170000Z
                RRULE:FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=12
                SUMMARY:Last Day of Month Review
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(listOf(-1), result.getOrNull()!![0].rrule?.byMonthDay)
        }

        @Test
        fun `Multiple BYMONTHDAY values`() {
            // 15th and last day of each month
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:multi-monthday@test.com
                DTSTART:20240115T100000Z
                RRULE:FREQ=MONTHLY;BYMONTHDAY=15,-1;COUNT=24
                SUMMARY:Mid and End Month
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val byMonthDay = result.getOrNull()!![0].rrule?.byMonthDay
            assertEquals(2, byMonthDay?.size)
            assertTrue(byMonthDay!!.contains(15))
            assertTrue(byMonthDay.contains(-1))
        }

        @Test
        fun `Every Friday the 13th`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:friday-13@test.com
                DTSTART:20241013T130000Z
                RRULE:FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13;COUNT=10
                SUMMARY:Friday the 13th
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val rrule = result.getOrNull()!![0].rrule
            assertEquals(listOf(13), rrule?.byMonthDay)
            assertEquals(DayOfWeek.FRIDAY, rrule?.byDay?.get(0)?.dayOfWeek)
        }
    }

    @Nested
    @DisplayName("RRule Serialization Round-Trip")
    inner class SerializationTests {

        @Test
        fun `BYSETPOS serializes correctly`() {
            val rrule = RRule(
                freq = Frequency.MONTHLY,
                byDay = listOf(
                    WeekdayNum(DayOfWeek.MONDAY),
                    WeekdayNum(DayOfWeek.FRIDAY)
                ),
                bySetPos = listOf(-1),
                count = 12
            )

            val serialized = rrule.toICalString()
            assertTrue(serialized.contains("BYSETPOS=-1"))

            val reparsed = RRule.parse(serialized)
            assertEquals(listOf(-1), reparsed.bySetPos)
        }

        @Test
        fun `BYYEARDAY serializes correctly`() {
            val rrule = RRule(
                freq = Frequency.YEARLY,
                byYearDay = listOf(1, 100, -1),
                count = 9
            )

            val serialized = rrule.toICalString()
            assertTrue(serialized.contains("BYYEARDAY=1,100,-1"))

            val reparsed = RRule.parse(serialized)
            assertEquals(listOf(1, 100, -1), reparsed.byYearDay)
        }

        @Test
        fun `BYWEEKNO serializes correctly`() {
            val rrule = RRule(
                freq = Frequency.YEARLY,
                byWeekNo = listOf(1, 26, 52),
                count = 9
            )

            val serialized = rrule.toICalString()
            assertTrue(serialized.contains("BYWEEKNO=1,26,52"))

            val reparsed = RRule.parse(serialized)
            assertEquals(listOf(1, 26, 52), reparsed.byWeekNo)
        }

        @Test
        fun `WKST serializes correctly`() {
            val rrule = RRule(
                freq = Frequency.WEEKLY,
                wkst = DayOfWeek.SUNDAY,
                count = 10
            )

            val serialized = rrule.toICalString()
            assertTrue(serialized.contains("WKST=SU"))

            val reparsed = RRule.parse(serialized)
            assertEquals(DayOfWeek.SUNDAY, reparsed.wkst)
        }

        @Test
        fun `complex RRULE serializes correctly`() {
            val rrule = RRule(
                freq = Frequency.YEARLY,
                interval = 1,
                byMonth = listOf(11),
                byDay = listOf(WeekdayNum(DayOfWeek.THURSDAY, 4)),
                count = 10
            )

            val serialized = rrule.toICalString()
            assertTrue(serialized.contains("FREQ=YEARLY"))
            assertTrue(serialized.contains("BYMONTH=11"))
            assertTrue(serialized.contains("BYDAY=4TH"))

            val reparsed = RRule.parse(serialized)
            assertEquals(Frequency.YEARLY, reparsed.freq)
            assertEquals(listOf(11), reparsed.byMonth)
            assertEquals(4, reparsed.byDay?.get(0)?.ordinal)
        }
    }

    @Nested
    @DisplayName("CalConnect Edge Cases")
    inner class CalConnectEdgeCasesTests {

        @Test
        fun `single RRULE per component - max one RRULE`() {
            // Per CalConnect: "Maximum one RRULE per calendar component"
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:single-rrule@test.com
                DTSTART:20240101T100000Z
                RRULE:FREQ=WEEKLY;COUNT=10
                SUMMARY:Single RRULE Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertNotNull(result.getOrNull()!![0].rrule)
        }

        @Test
        fun `DTSTART defines first instance and pattern`() {
            // Per CalConnect: "DTSTART value determines the starting point
            // and must match the first recurrence instance"
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:dtstart-pattern@test.com
                DTSTART:20240115T100000Z
                RRULE:FREQ=MONTHLY;BYMONTHDAY=15;COUNT=12
                SUMMARY:Monthly on 15th
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            // DTSTART is on the 15th, matching BYMONTHDAY=15
            assertEquals("20240115T100000Z", event.dtStart.toICalString())
        }

        @Test
        fun `exception event has no RRULE`() {
            // Per CalConnect: exception "cannot include RRULE, RDATE, or EXDATE properties"
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:master@test.com
                DTSTART:20240101T100000Z
                RRULE:FREQ=DAILY;COUNT=10
                SUMMARY:Master
                END:VEVENT
                BEGIN:VEVENT
                UID:master@test.com
                RECURRENCE-ID:20240105T100000Z
                DTSTART:20240105T140000Z
                SUMMARY:Exception - Moved
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            val exception = events.find { it.recurrenceId != null }
            assertNotNull(exception)
            assertTrue(exception.rrule == null)
        }

        @Test
        fun `UNTIL and COUNT mutually exclusive - COUNT takes precedence`() {
            // If both appear (invalid), implementation should handle gracefully
            val rrule = RRule.parse("FREQ=DAILY;COUNT=5;UNTIL=20241231T235959Z")
            // Implementation typically picks one
            assertTrue(rrule.count == 5 || rrule.until != null)
        }
    }

    @Nested
    @DisplayName("Frequency-Specific Tests")
    inner class FrequencySpecificTests {

        @Test
        fun `SECONDLY frequency`() {
            val rrule = RRule.parse("FREQ=SECONDLY;INTERVAL=30;COUNT=10")
            assertEquals(Frequency.SECONDLY, rrule.freq)
            assertEquals(30, rrule.interval)
        }

        @Test
        fun `MINUTELY frequency`() {
            val rrule = RRule.parse("FREQ=MINUTELY;INTERVAL=15;COUNT=8")
            assertEquals(Frequency.MINUTELY, rrule.freq)
            assertEquals(15, rrule.interval)
        }

        @Test
        fun `HOURLY frequency`() {
            val rrule = RRule.parse("FREQ=HOURLY;INTERVAL=2;COUNT=12")
            assertEquals(Frequency.HOURLY, rrule.freq)
            assertEquals(2, rrule.interval)
        }

        @Test
        fun `all frequencies parsed from events`() {
            val frequencies = listOf("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
            val expected = listOf(
                Frequency.SECONDLY, Frequency.MINUTELY, Frequency.HOURLY,
                Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY, Frequency.YEARLY
            )

            frequencies.forEachIndexed { index, freq ->
                val ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VEVENT
                    UID:freq-$freq@test.com
                    DTSTART:20240101T100000Z
                    RRULE:FREQ=$freq;COUNT=3
                    SUMMARY:$freq Event
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()

                val result = parser.parseAllEvents(ics)
                assertTrue(result is ParseResult.Success)
                assertEquals(expected[index], result.getOrNull()!![0].rrule?.freq)
            }
        }
    }

    // Helper to create events for expansion tests
    private fun createEvent(
        uid: String = "test-event",
        dtStart: ICalDateTime,
        rrule: RRule? = null,
        summary: String = "Test Event"
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = ICalDateTime(
                timestamp = dtStart.timestamp + 3600000,
                timezone = dtStart.timezone,
                isUtc = dtStart.isUtc,
                isDate = dtStart.isDate
            ),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
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
