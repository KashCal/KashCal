package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests RDATE expansion in [RRuleExpander]: RDATEs alone and with an RRULE, EXDATE removing RDATE
 * and RRULE dates, range filtering, occurrence properties and sort order.
 *
 * RFC 5545 §3.8.5.2 gathers the RRULE and RDATE dates and removes the EXDATE ones, with DTSTART
 * defining the first instance. RRuleExpander doesn't add DTSTART on its own, so the RDATE-only
 * tests expect only the RDATEs, which departs from the RFC's first instance.
 */
@DisplayName("RRuleExpander RDATE Tests")
class RRuleExpanderRdateTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val zone = ZoneId.of("UTC")

    // Returns all of 2026 in UTC, ending at Dec 31 23:59:59.
    private fun rangeFor2026(): TimeRange {
        val start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant()
        val end = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, zone).toInstant()
        return TimeRange(start, end)
    }

    @Nested
    @DisplayName("RDATE-only Expansion (no RRULE)")
    inner class RdateOnlyExpansion {

        @Test
        fun `expand event with RDATE only creates occurrences`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-only@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE only event
                RDATE:20260120T100000Z,20260125T100000Z,20260130T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // Only the three RDATEs; DTSTART (Jan 15) isn't added.
            assertEquals(3, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260120"))
            assertTrue(dayCodes.contains("20260125"))
            assertTrue(dayCodes.contains("20260130"))
        }

        @Test
        fun `RDATE-only event is recurring`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-recurring@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE only event
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertTrue(event.isRecurring())
            assertNull(event.rrule)
            assertEquals(1, event.rdates.size)
        }

        @Test
        fun `event without RRULE or RDATE returns single event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:no-recurrence@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Single event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            assertEquals(1, occurrences.size)
            assertEquals(event.uid, occurrences[0].uid)
        }
    }

    @Nested
    @DisplayName("RDATE + RRULE Combination")
    inner class RdatePlusRrule {

        @Test
        fun `RDATE adds extra occurrences to RRULE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rrule-rdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Weekly + extra RDATE
                RRULE:FREQ=WEEKLY;COUNT=3
                RDATE:20260210T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // RRULE: Jan 15, Jan 22, Jan 29. RDATE: Feb 10.
            assertEquals(4, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260115")) // RRULE
            assertTrue(dayCodes.contains("20260122")) // RRULE
            assertTrue(dayCodes.contains("20260129")) // RRULE
            assertTrue(dayCodes.contains("20260210")) // RDATE
        }

        @Test
        fun `duplicate RDATE same as RRULE date is deduplicated`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rrule-rdate-dup@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Weekly with duplicate RDATE
                RRULE:FREQ=WEEKLY;COUNT=3
                RDATE:20260122T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // RRULE: Jan 15, Jan 22, Jan 29. The Jan 22 RDATE is dropped as a duplicate.
            assertEquals(3, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertEquals(dayCodes.distinct().size, dayCodes.size)
        }
    }

    @Nested
    @DisplayName("EXDATE Excludes RDATE")
    inner class ExdateExcludesRdate {

        @Test
        fun `EXDATE excludes RDATE occurrence`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-exdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE with EXDATE
                RDATE:20260120T100000Z,20260125T100000Z,20260130T100000Z
                EXDATE:20260125T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // 3 RDATEs minus 1 EXDATE.
            assertEquals(2, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260120"))
            assertFalse(dayCodes.contains("20260125")) // Excluded
            assertTrue(dayCodes.contains("20260130"))
        }

        @Test
        fun `EXDATE excludes both RRULE and RDATE on same date`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rrule-rdate-exdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RRULE + RDATE with EXDATE
                RRULE:FREQ=WEEKLY;COUNT=3
                RDATE:20260210T100000Z
                EXDATE:20260122T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // RRULE: Jan 15, Jan 22 (excluded), Jan 29. RDATE: Feb 10. The EXDATE hits only an
            // RRULE date; no RDATE falls on Jan 22.
            assertEquals(3, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260115"))
            assertFalse(dayCodes.contains("20260122")) // EXDATE
            assertTrue(dayCodes.contains("20260129"))
            assertTrue(dayCodes.contains("20260210"))
        }
    }

    @Nested
    @DisplayName("Range Filtering")
    inner class RangeFiltering {

        @Test
        fun `RDATE outside range is excluded`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-range@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE outside range
                RDATE:20260120T100000Z,20270115T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // Only Jan 20, 2026 is in range; Jan 15, 2027 is not.
            assertEquals(1, occurrences.size)
            assertEquals("20260120", occurrences[0].dtStart.toDayCode())
        }

        @Test
        fun `RDATE before range start is excluded`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-before@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE before range
                RDATE:20251215T100000Z,20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // Only Jan 20, 2026 is in range.
            assertEquals(1, occurrences.size)
        }
    }

    @Nested
    @DisplayName("Occurrence Properties")
    inner class OccurrenceProperties {

        @Test
        fun `RDATE occurrence has correct importId`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-importid@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE importId test
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            assertEquals(1, occurrences.size)
            assertTrue(occurrences[0].importId.contains("OCC:20260120"))
        }

        @Test
        fun `RDATE occurrence preserves master event properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-props@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Important Meeting
                DESCRIPTION:Test description
                LOCATION:Conference Room
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            assertEquals(1, occurrences.size)
            assertEquals("Important Meeting", occurrences[0].summary)
            assertEquals("Test description", occurrences[0].description)
            assertEquals("Conference Room", occurrences[0].location)
        }

        @Test
        fun `RDATE occurrence has no RRULE or RDATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-clean@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Clean occurrence
                RRULE:FREQ=WEEKLY;COUNT=2
                RDATE:20260210T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // No occurrence carries an RRULE, RDATE or EXDATE.
            occurrences.forEach { occ ->
                assertNull(occ.rrule)
                assertTrue(occ.rdates.isEmpty())
                assertTrue(occ.exdates.isEmpty())
            }
        }

        @Test
        fun `RDATE occurrence calculates correct end time from duration`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-duration@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T120000Z
                SUMMARY:2 hour event
                RDATE:20260120T140000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            assertEquals(1, occurrences.size)
            val occ = occurrences[0]

            // The RDATE starts at 14:00 and keeps the master's 2 hours.
            assertNotNull(occ.dtEnd)
            val duration = occ.dtEnd!!.timestamp - occ.dtStart.timestamp
            assertEquals(2 * 60 * 60 * 1000L, duration)
        }
    }

    @Nested
    @DisplayName("Sorting")
    inner class Sorting {

        @Test
        fun `occurrences are sorted by timestamp`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-sort@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Sort test
                RRULE:FREQ=MONTHLY;COUNT=2
                RDATE:20260110T100000Z,20260220T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // Jan 10 (RDATE), Jan 15 (RRULE), Feb 15 (RRULE), Feb 20 (RDATE).
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertEquals(listOf("20260110", "20260115", "20260215", "20260220"), dayCodes)
        }
    }

    @Nested
    @DisplayName("Matching by instant (RFC 5545 §3.8.5.1-2)")
    inner class MatchingByInstant {

        private val newYork = ZoneId.of("America/New_York")

        private fun expandSeries(vararg extra: String, allDay: Boolean = false): List<Long> {
            val start = if (allDay) "DTSTART;VALUE=DATE:20260301" else "DTSTART;TZID=America/New_York:20260301T100000"
            val end = if (allDay) "DTEND;VALUE=DATE:20260302" else "DTEND;TZID=America/New_York:20260301T110000"
            val ics = (listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT",
                "UID:instant-match@test.com", "DTSTAMP:20260101T000000Z", start, end,
                "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Instant matching",
            ) + extra + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")
            val event = (parser.parseAllEvents(ics) as ParseResult.Success).value.first()
            return expander.expand(event, rangeFor2026()).map { it.dtStart.timestamp }
        }

        private fun ny(day: Int, hour: Int): Long =
            ZonedDateTime.of(2026, 3, day, hour, 0, 0, 0, newYork).toInstant().toEpochMilli()

        @Test
        fun `an EXDATE at another time of day removes no occurrence`() {
            assertEquals(listOf(ny(1, 10), ny(2, 10), ny(3, 10)), expandSeries("EXDATE;TZID=America/New_York:20260302T090000"))
        }

        @Test
        fun `an EXDATE in UTC form at the occurrence's instant removes it`() {
            // 2 Mar 2026 10:00 EST is 15:00Z.
            assertEquals(listOf(ny(1, 10), ny(3, 10)), expandSeries("EXDATE:20260302T150000Z"))
        }

        @Test
        fun `an RDATE on a day that already has an occurrence adds its own instance`() {
            assertEquals(
                listOf(ny(1, 10), ny(2, 10), ny(2, 15), ny(3, 10)),
                expandSeries("RDATE;TZID=America/New_York:20260302T150000"),
            )
        }

        @Test
        fun `an EXDATE removes the RRULE occurrence at its instant and keeps the same-day RDATE`() {
            assertEquals(
                listOf(ny(1, 10), ny(2, 15), ny(3, 10)),
                expandSeries("RDATE;TZID=America/New_York:20260302T150000", "EXDATE;TZID=America/New_York:20260302T100000"),
            )
        }

        @Test
        fun `a DATE EXDATE on an all-day series removes that day`() {
            val utc = { d: Int -> ZonedDateTime.of(2026, 3, d, 0, 0, 0, 0, zone).toInstant().toEpochMilli() }
            assertEquals(listOf(utc(1), utc(3)), expandSeries("EXDATE;VALUE=DATE:20260302", allDay = true))
        }
    }
}
