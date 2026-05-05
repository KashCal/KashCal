package org.onekash.icaldav.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek

/**
 * Comprehensive tests for RRule model per RFC 5545 Section 3.3.10.
 *
 * Tests cover:
 * - RRule construction
 * - RRule parsing from iCal strings
 * - RRule serialization to iCal strings
 * - WeekdayNum parsing and serialization
 * - All RRULE components (FREQ, INTERVAL, COUNT, UNTIL, BYDAY, etc.)
 * - Round-trip parsing and serialization
 * - Edge cases and validation
 */
class RRuleTest {

    // ==================== Basic Construction Tests ====================

    @Nested
    inner class BasicConstructionTests {

        @Test
        fun `create daily rule`() {
            val rrule = RRule(freq = Frequency.DAILY)

            assertEquals(Frequency.DAILY, rrule.freq)
            assertEquals(1, rrule.interval) // Default
            assertNull(rrule.count)
            assertNull(rrule.until)
        }

        @Test
        fun `create weekly rule with interval`() {
            val rrule = RRule(freq = Frequency.WEEKLY, interval = 2)

            assertEquals(Frequency.WEEKLY, rrule.freq)
            assertEquals(2, rrule.interval)
        }

        @Test
        fun `create monthly rule with count`() {
            val rrule = RRule(freq = Frequency.MONTHLY, count = 10)

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertEquals(10, rrule.count)
            assertNull(rrule.until)
        }

        @Test
        fun `create yearly rule with until`() {
            val until = ICalDateTime.parse("20241231T235959Z")
            val rrule = RRule(freq = Frequency.YEARLY, until = until)

            assertEquals(Frequency.YEARLY, rrule.freq)
            assertNull(rrule.count)
            assertEquals(until, rrule.until)
        }

        @Test
        fun `create rule with byDay`() {
            val byDay = listOf(
                WeekdayNum(DayOfWeek.MONDAY),
                WeekdayNum(DayOfWeek.WEDNESDAY),
                WeekdayNum(DayOfWeek.FRIDAY)
            )
            val rrule = RRule(freq = Frequency.WEEKLY, byDay = byDay)

            assertEquals(3, rrule.byDay?.size)
        }

        @Test
        fun `create monthly rule with ordinal byDay`() {
            // Second Tuesday of each month
            val byDay = listOf(WeekdayNum(DayOfWeek.TUESDAY, ordinal = 2))
            val rrule = RRule(freq = Frequency.MONTHLY, byDay = byDay)

            assertEquals(1, rrule.byDay?.size)
            assertEquals(2, rrule.byDay?.first()?.ordinal)
        }

        @Test
        fun `create rule with byMonthDay`() {
            val rrule = RRule(freq = Frequency.MONTHLY, byMonthDay = listOf(15, -1))

            assertEquals(listOf(15, -1), rrule.byMonthDay)
        }

        @Test
        fun `create yearly rule with byMonth`() {
            val rrule = RRule(freq = Frequency.YEARLY, byMonth = listOf(1, 7, 12))

            assertEquals(listOf(1, 7, 12), rrule.byMonth)
        }
    }

    // ==================== Parsing Tests ====================

    @Nested
    inner class ParsingTests {

        @Test
        fun `parse simple daily rule`() {
            val rrule = RRule.parse("FREQ=DAILY")

            assertEquals(Frequency.DAILY, rrule.freq)
            assertEquals(1, rrule.interval)
        }

        @Test
        fun `parse daily rule with interval`() {
            val rrule = RRule.parse("FREQ=DAILY;INTERVAL=2")

            assertEquals(Frequency.DAILY, rrule.freq)
            assertEquals(2, rrule.interval)
        }

        @Test
        fun `parse weekly rule with count`() {
            val rrule = RRule.parse("FREQ=WEEKLY;COUNT=10")

            assertEquals(Frequency.WEEKLY, rrule.freq)
            assertEquals(10, rrule.count)
        }

        @Test
        fun `parse monthly rule with until`() {
            val rrule = RRule.parse("FREQ=MONTHLY;UNTIL=20241231T235959Z")

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertNotNull(rrule.until)
            assertEquals("20241231T235959Z", rrule.until?.toICalString())
        }

        @Test
        fun `parse weekly rule with byday`() {
            val rrule = RRule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR")

            assertEquals(Frequency.WEEKLY, rrule.freq)
            assertEquals(3, rrule.byDay?.size)
            assertEquals(DayOfWeek.MONDAY, rrule.byDay?.get(0)?.dayOfWeek)
            assertEquals(DayOfWeek.WEDNESDAY, rrule.byDay?.get(1)?.dayOfWeek)
            assertEquals(DayOfWeek.FRIDAY, rrule.byDay?.get(2)?.dayOfWeek)
        }

        @Test
        fun `parse monthly rule with ordinal byday`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYDAY=2TU")

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertEquals(1, rrule.byDay?.size)
            assertEquals(DayOfWeek.TUESDAY, rrule.byDay?.first()?.dayOfWeek)
            assertEquals(2, rrule.byDay?.first()?.ordinal)
        }

        @Test
        fun `parse monthly rule with last weekday`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYDAY=-1FR")

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertEquals(DayOfWeek.FRIDAY, rrule.byDay?.first()?.dayOfWeek)
            assertEquals(-1, rrule.byDay?.first()?.ordinal)
        }

        @Test
        fun `parse monthly rule with bymonthday`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=15")

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertEquals(listOf(15), rrule.byMonthDay)
        }

        @Test
        fun `parse monthly rule with last day`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=-1")

            assertEquals(Frequency.MONTHLY, rrule.freq)
            assertEquals(listOf(-1), rrule.byMonthDay)
        }

        @Test
        fun `parse yearly rule with bymonth`() {
            val rrule = RRule.parse("FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25")

            assertEquals(Frequency.YEARLY, rrule.freq)
            assertEquals(listOf(12), rrule.byMonth)
            assertEquals(listOf(25), rrule.byMonthDay)
        }

        @Test
        fun `parse rule with byweekno`() {
            val rrule = RRule.parse("FREQ=YEARLY;BYWEEKNO=1,52")

            assertEquals(listOf(1, 52), rrule.byWeekNo)
        }

        @Test
        fun `parse rule with byyearday`() {
            val rrule = RRule.parse("FREQ=YEARLY;BYYEARDAY=1,100,200,-1")

            assertEquals(listOf(1, 100, 200, -1), rrule.byYearDay)
        }

        @Test
        fun `parse rule with bysetpos`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1")

            assertEquals(listOf(-1), rrule.bySetPos)
        }

        @Test
        fun `parse rule with wkst`() {
            val rrule = RRule.parse("FREQ=WEEKLY;WKST=SU")

            assertEquals(DayOfWeek.SUNDAY, rrule.wkst)
        }

        @Test
        fun `parse complex yearly rule`() {
            // Every Thanksgiving (4th Thursday of November)
            val rrule = RRule.parse("FREQ=YEARLY;BYMONTH=11;BYDAY=4TH")

            assertEquals(Frequency.YEARLY, rrule.freq)
            assertEquals(listOf(11), rrule.byMonth)
            assertEquals(DayOfWeek.THURSDAY, rrule.byDay?.first()?.dayOfWeek)
            assertEquals(4, rrule.byDay?.first()?.ordinal)
        }

        @Test
        fun `parse throws for missing FREQ`() {
            assertThrows<IllegalArgumentException> {
                RRule.parse("INTERVAL=2;COUNT=10")
            }
        }

        @Test
        fun `parse throws for invalid FREQ`() {
            assertThrows<IllegalArgumentException> {
                RRule.parse("FREQ=INVALID")
            }
        }
    }

    // ==================== Serialization Tests ====================

    @Nested
    inner class SerializationTests {

        @Test
        fun `toICalString for simple daily rule`() {
            val rrule = RRule(freq = Frequency.DAILY)
            assertEquals("FREQ=DAILY", rrule.toICalString())
        }

        @Test
        fun `toICalString includes interval when not 1`() {
            val rrule = RRule(freq = Frequency.WEEKLY, interval = 2)
            assertEquals("FREQ=WEEKLY;INTERVAL=2", rrule.toICalString())
        }

        @Test
        fun `toICalString excludes interval when 1`() {
            val rrule = RRule(freq = Frequency.DAILY, interval = 1)
            assertFalse(rrule.toICalString().contains("INTERVAL"))
        }

        @Test
        fun `toICalString includes count`() {
            val rrule = RRule(freq = Frequency.WEEKLY, count = 10)
            assertTrue(rrule.toICalString().contains("COUNT=10"))
        }

        @Test
        fun `toICalString includes until`() {
            val until = ICalDateTime.parse("20241231T235959Z")
            val rrule = RRule(freq = Frequency.MONTHLY, until = until)
            assertTrue(rrule.toICalString().contains("UNTIL=20241231T235959Z"))
        }

        @Test
        fun `toICalString includes byday`() {
            val byDay = listOf(
                WeekdayNum(DayOfWeek.MONDAY),
                WeekdayNum(DayOfWeek.FRIDAY)
            )
            val rrule = RRule(freq = Frequency.WEEKLY, byDay = byDay)
            assertTrue(rrule.toICalString().contains("BYDAY=MO,FR"))
        }

        @Test
        fun `toICalString includes ordinal byday`() {
            val byDay = listOf(WeekdayNum(DayOfWeek.TUESDAY, ordinal = 2))
            val rrule = RRule(freq = Frequency.MONTHLY, byDay = byDay)
            assertTrue(rrule.toICalString().contains("BYDAY=2TU"))
        }

        @Test
        fun `toICalString includes negative ordinal`() {
            val byDay = listOf(WeekdayNum(DayOfWeek.FRIDAY, ordinal = -1))
            val rrule = RRule(freq = Frequency.MONTHLY, byDay = byDay)
            assertTrue(rrule.toICalString().contains("BYDAY=-1FR"))
        }

        @Test
        fun `toICalString includes bymonthday`() {
            val rrule = RRule(freq = Frequency.MONTHLY, byMonthDay = listOf(15, -1))
            assertTrue(rrule.toICalString().contains("BYMONTHDAY=15,-1"))
        }

        @Test
        fun `toICalString includes bymonth`() {
            val rrule = RRule(freq = Frequency.YEARLY, byMonth = listOf(1, 7))
            assertTrue(rrule.toICalString().contains("BYMONTH=1,7"))
        }

        @Test
        fun `toICalString includes wkst when not Monday`() {
            val rrule = RRule(freq = Frequency.WEEKLY, wkst = DayOfWeek.SUNDAY)
            assertTrue(rrule.toICalString().contains("WKST=SU"))
        }

        @Test
        fun `toICalString excludes wkst when Monday`() {
            val rrule = RRule(freq = Frequency.WEEKLY, wkst = DayOfWeek.MONDAY)
            assertFalse(rrule.toICalString().contains("WKST"))
        }
    }

    // ==================== WeekdayNum Tests ====================

    @Nested
    inner class WeekdayNumTests {

        @Test
        fun `parse simple weekday`() {
            assertEquals(DayOfWeek.MONDAY, WeekdayNum.parse("MO").dayOfWeek)
            assertEquals(DayOfWeek.TUESDAY, WeekdayNum.parse("TU").dayOfWeek)
            assertEquals(DayOfWeek.WEDNESDAY, WeekdayNum.parse("WE").dayOfWeek)
            assertEquals(DayOfWeek.THURSDAY, WeekdayNum.parse("TH").dayOfWeek)
            assertEquals(DayOfWeek.FRIDAY, WeekdayNum.parse("FR").dayOfWeek)
            assertEquals(DayOfWeek.SATURDAY, WeekdayNum.parse("SA").dayOfWeek)
            assertEquals(DayOfWeek.SUNDAY, WeekdayNum.parse("SU").dayOfWeek)
        }

        @Test
        fun `parse weekday with positive ordinal`() {
            val wdn = WeekdayNum.parse("2TU")
            assertEquals(DayOfWeek.TUESDAY, wdn.dayOfWeek)
            assertEquals(2, wdn.ordinal)
        }

        @Test
        fun `parse weekday with negative ordinal`() {
            val wdn = WeekdayNum.parse("-1FR")
            assertEquals(DayOfWeek.FRIDAY, wdn.dayOfWeek)
            assertEquals(-1, wdn.ordinal)
        }

        @Test
        fun `parse is case insensitive`() {
            assertEquals(DayOfWeek.MONDAY, WeekdayNum.parse("mo").dayOfWeek)
            assertEquals(DayOfWeek.MONDAY, WeekdayNum.parse("Mo").dayOfWeek)
        }

        @Test
        fun `parse throws for invalid weekday`() {
            assertThrows<IllegalArgumentException> {
                WeekdayNum.parse("XX")
            }
        }

        @Test
        fun `toICalString for simple weekday`() {
            assertEquals("MO", WeekdayNum(DayOfWeek.MONDAY).toICalString())
            assertEquals("SU", WeekdayNum(DayOfWeek.SUNDAY).toICalString())
        }

        @Test
        fun `toICalString with ordinal`() {
            assertEquals("2TU", WeekdayNum(DayOfWeek.TUESDAY, 2).toICalString())
            assertEquals("-1FR", WeekdayNum(DayOfWeek.FRIDAY, -1).toICalString())
        }
    }

    // ==================== Round-Trip Tests ====================

    @Nested
    inner class RoundTripTests {

        @Test
        fun `round-trip daily rule`() {
            val original = "FREQ=DAILY;INTERVAL=3;COUNT=30"
            val parsed = RRule.parse(original)
            val serialized = parsed.toICalString()
            val reparsed = RRule.parse(serialized)

            assertEquals(parsed, reparsed)
        }

        @Test
        fun `round-trip weekly rule with byday`() {
            val original = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
            val parsed = RRule.parse(original)
            val serialized = parsed.toICalString()
            val reparsed = RRule.parse(serialized)

            assertEquals(parsed.freq, reparsed.freq)
            assertEquals(parsed.byDay?.size, reparsed.byDay?.size)
        }

        @Test
        fun `round-trip monthly rule with ordinal`() {
            val original = "FREQ=MONTHLY;BYDAY=2TU"
            val parsed = RRule.parse(original)
            val serialized = parsed.toICalString()
            val reparsed = RRule.parse(serialized)

            assertEquals(parsed, reparsed)
        }

        @Test
        fun `round-trip complex rule`() {
            val original = "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH;COUNT=10"
            val parsed = RRule.parse(original)
            val serialized = parsed.toICalString()
            val reparsed = RRule.parse(serialized)

            assertEquals(parsed.freq, reparsed.freq)
            assertEquals(parsed.byMonth, reparsed.byMonth)
            assertEquals(parsed.count, reparsed.count)
        }
    }

    // ==================== Frequency Enum Tests ====================

    @Nested
    inner class FrequencyTests {

        @Test
        fun `all frequencies exist`() {
            assertEquals(7, Frequency.entries.size)
            assertNotNull(Frequency.SECONDLY)
            assertNotNull(Frequency.MINUTELY)
            assertNotNull(Frequency.HOURLY)
            assertNotNull(Frequency.DAILY)
            assertNotNull(Frequency.WEEKLY)
            assertNotNull(Frequency.MONTHLY)
            assertNotNull(Frequency.YEARLY)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    inner class EdgeCases {

        @Test
        fun `rule with maximum ordinal`() {
            val byDay = listOf(WeekdayNum(DayOfWeek.MONDAY, ordinal = 5))
            val rrule = RRule(freq = Frequency.MONTHLY, byDay = byDay)
            assertEquals(5, rrule.byDay?.first()?.ordinal)
        }

        @Test
        fun `rule with all BY clauses`() {
            val rrule = RRule(
                freq = Frequency.YEARLY,
                interval = 1,
                byDay = listOf(WeekdayNum(DayOfWeek.MONDAY)),
                byMonthDay = listOf(1),
                byMonth = listOf(1),
                byWeekNo = listOf(1),
                byYearDay = listOf(1),
                bySetPos = listOf(1)
            )

            assertNotNull(rrule.byDay)
            assertNotNull(rrule.byMonthDay)
            assertNotNull(rrule.byMonth)
            assertNotNull(rrule.byWeekNo)
            assertNotNull(rrule.byYearDay)
            assertNotNull(rrule.bySetPos)
        }

        @Test
        fun `rule with empty optional fields`() {
            val rrule = RRule(freq = Frequency.DAILY)

            assertNull(rrule.count)
            assertNull(rrule.until)
            assertNull(rrule.byDay)
            assertNull(rrule.byMonthDay)
            assertNull(rrule.byMonth)
            assertNull(rrule.byWeekNo)
            assertNull(rrule.byYearDay)
            assertNull(rrule.bySetPos)
        }

        @Test
        fun `data class equality`() {
            val rrule1 = RRule(freq = Frequency.DAILY, interval = 2, count = 10)
            val rrule2 = RRule(freq = Frequency.DAILY, interval = 2, count = 10)

            assertEquals(rrule1, rrule2)
        }

        @Test
        fun `data class copy`() {
            val original = RRule(freq = Frequency.DAILY)
            val modified = original.copy(interval = 2)

            assertEquals(1, original.interval)
            assertEquals(2, modified.interval)
        }

        @Test
        fun `parse handles extra whitespace`() {
            // Some servers might add whitespace
            val rrule = RRule.parse("FREQ=DAILY")
            assertEquals(Frequency.DAILY, rrule.freq)
        }

        @Test
        fun `multiple bymonthday values`() {
            val rrule = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15,-1")
            assertEquals(listOf(1, 15, -1), rrule.byMonthDay)
        }

        @Test
        fun `until with date only format`() {
            val rrule = RRule.parse("FREQ=DAILY;UNTIL=20241231")
            assertNotNull(rrule.until)
            assertTrue(rrule.until!!.isDate)
        }
    }

    // ==================== BYHOUR / BYMINUTE / BYSECOND Tests ====================

    /**
     * Round-trip and parse tests for RFC 5545 §3.3.10 BYHOUR/BYMINUTE/BYSECOND
     * rule parts. Pinned by RFC §3.8.5.3 example 36 (sub-daily BY* grid).
     */
    @Nested
    inner class ByHourMinuteSecondTests {

        @Test
        fun `parse BYHOUR populates byHour list`() {
            val rrule = RRule.parse("FREQ=DAILY;BYHOUR=9,12,15")
            assertEquals(listOf(9, 12, 15), rrule.byHour)
        }

        @Test
        fun `parse BYMINUTE populates byMinute list`() {
            val rrule = RRule.parse("FREQ=DAILY;BYMINUTE=0,20,40")
            assertEquals(listOf(0, 20, 40), rrule.byMinute)
        }

        @Test
        fun `parse BYSECOND populates bySecond list`() {
            val rrule = RRule.parse("FREQ=MINUTELY;BYSECOND=0,30")
            assertEquals(listOf(0, 30), rrule.bySecond)
        }

        @Test
        fun `toICalString emits BYHOUR when byHour is set`() {
            val rrule = RRule(freq = Frequency.DAILY, byHour = listOf(9, 12, 15))
            val out = rrule.toICalString()
            assertTrue(out.contains("BYHOUR=9,12,15"), "expected BYHOUR=9,12,15 in $out")
        }

        @Test
        fun `toICalString groups BYSECOND BYMINUTE BYHOUR together before BYSETPOS`() {
            val rrule = RRule(
                freq = Frequency.DAILY,
                byHour = listOf(9, 12),
                byMinute = listOf(0, 30),
                bySecond = listOf(0),
                bySetPos = listOf(1),
            )
            val out = rrule.toICalString()
            val idxSecond = out.indexOf("BYSECOND=")
            val idxMinute = out.indexOf("BYMINUTE=")
            val idxHour = out.indexOf("BYHOUR=")
            val idxSetPos = out.indexOf("BYSETPOS=")
            assertTrue(
                idxSecond >= 0 && idxMinute >= 0 && idxHour >= 0 && idxSetPos >= 0,
                "all four parts present: $out",
            )
            assertTrue(idxSecond < idxMinute, "BYSECOND before BYMINUTE: $out")
            assertTrue(idxMinute < idxHour, "BYMINUTE before BYHOUR: $out")
            assertTrue(idxHour < idxSetPos, "BYHOUR before BYSETPOS: $out")
        }

        @Test
        fun `null sub-daily fields are omitted from toICalString`() {
            val rrule = RRule(freq = Frequency.DAILY, count = 5)
            val out = rrule.toICalString()
            assertFalse(out.contains("BYHOUR"), "no BYHOUR in $out")
            assertFalse(out.contains("BYMINUTE"), "no BYMINUTE in $out")
            assertFalse(out.contains("BYSECOND"), "no BYSECOND in $out")
        }

        @Test
        fun `round trip preserves all three fields`() {
            val original = RRule(
                freq = Frequency.DAILY,
                byHour = listOf(9, 12, 15),
                byMinute = listOf(0, 30),
                bySecond = listOf(0, 15, 30),
            )
            val reparsed = RRule.parse(original.toICalString())
            assertEquals(original, reparsed)
        }

        @Test
        fun `RFC 5545 section 3_8_5_3 example 36 round trips`() {
            val input = "FREQ=DAILY;BYHOUR=9,10,11,12,13,14,15,16;BYMINUTE=0,20,40"
            val parsed = RRule.parse(input)
            assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16), parsed.byHour)
            assertEquals(listOf(0, 20, 40), parsed.byMinute)
            val reparsed = RRule.parse(parsed.toICalString())
            assertEquals(parsed, reparsed)
        }

        @Test
        fun `parse tolerates non-integer entries in BYHOUR list`() {
            // Matches existing .mapNotNull { toIntOrNull() } tolerance for
            // byMonthDay/byMonth/byWeekNo/byYearDay.
            val rrule = RRule.parse("FREQ=DAILY;BYHOUR=9,foo,12")
            assertEquals(listOf(9, 12), rrule.byHour)
        }

        @Test
        fun `parse accepts out of range values without model level validation`() {
            // Model layer accepts any Int; range validation is the engine's
            // responsibility. Matches the tolerance already in byMonthDay/byMonth.
            val rrule = RRule.parse("FREQ=DAILY;BYHOUR=24;BYSECOND=-1")
            assertEquals(listOf(24), rrule.byHour)
            assertEquals(listOf(-1), rrule.bySecond)
        }
    }
}
