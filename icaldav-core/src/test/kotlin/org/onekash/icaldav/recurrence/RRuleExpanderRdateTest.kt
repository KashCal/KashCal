package org.onekash.icaldav.recurrence

import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for RDATE expansion in RRuleExpander.
 *
 * Tests verify the RFC 5545 recurrence formula:
 * RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 */
@DisplayName("RRuleExpander RDATE Tests")
class RRuleExpanderRdateTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val zone = ZoneId.of("UTC")

    // Helper to create time range
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

            // Should have 3 occurrences from RDATE
            assertEquals(3, occurrences.size)

            // Verify dates
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

            // RRULE generates: Jan 15, Jan 22, Jan 29 (3 weekly)
            // RDATE adds: Feb 10
            // Total: 4 occurrences
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

            // RRULE: Jan 15, Jan 22, Jan 29
            // RDATE: Jan 22 (duplicate - should be deduplicated)
            // Total: 3 (not 4)
            assertEquals(3, occurrences.size)

            // Verify no duplicates
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

            // 3 RDATEs minus 1 EXDATE = 2 occurrences
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

            // RRULE: Jan 15, Jan 22 (excluded), Jan 29
            // RDATE: Feb 10
            // Total: 3
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

            // Only Jan 20, 2026 is in range; Jan 15, 2027 is not
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

            // Only Jan 20, 2026 is in range
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

            // All occurrences should have no RRULE/RDATE
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

            // RDATE starts at 14:00, should end at 16:00 (2 hour duration)
            assertNotNull(occ.dtEnd)
            val duration = occ.dtEnd!!.timestamp - occ.dtStart.timestamp
            assertEquals(2 * 60 * 60 * 1000L, duration) // 2 hours in ms
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

            // Expected order: Jan 10 (RDATE), Jan 15 (RRULE), Feb 15 (RRULE), Feb 20 (RDATE)
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertEquals(listOf("20260110", "20260115", "20260215", "20260220"), dayCodes)
        }
    }
}
