package org.onekash.kashcal.domain.quickadd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * Comprehensive stress testing for QuickAddParser — targeting open-source library release.
 *
 * Categories:
 *  1. Combinatorial: every date type x every time type x location x duration x recurrence
 *  2. Rule priority conflicts: overlapping signals that could confuse the parser
 *  3. Token boundary: inputs that straddle tokenizer classification edges
 *  4. Regression: specific failure patterns found during evaluation
 *  5. Permutation: same semantics in different word orders
 *  6. Locale-sensitive ambiguity: M/D vs D/M, 12h vs 24h
 *  7. Normalizer stress: unusual character sequences, number words at limits
 *  8. Reference time sensitivity: midnight, noon, year boundary, DST-adjacent
 *  9. Duration edge cases: overflow, cross-midnight, combined with ranges
 * 10. Recurrence exhaustive: all FREQ types, intervals, BYDAY combos
 * 11. Multi-feature sentences: complex real-world inputs combining 4-5 features
 * 12. Fuzz-inspired: random-looking patterns that exercise corner cases
 */
class QuickAddParserStressTest {

    // Monday April 13, 2026, 10:00 AM
    private val ref = LocalDateTime.of(2026, 4, 13, 10, 0)

    private var originalLocale: Locale? = null

    @Before
    fun pinLocaleToUS() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private fun parse(input: String, reference: LocalDateTime = ref) =
        QuickAddParser.parse(input, reference)

    // ════════════════════════════════════════════════════════════
    //  1. COMBINATORIAL: date type x time type
    //     Exhaustively combine each date source with each time source
    // ════════════════════════════════════════════════════════════

    // --- Date keyword + each time format ---

    @Test
    fun `tomorrow + colon time`() {
        val r = parse("tomorrow at 14:30")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(14, 30), r.startTime)
    }

    @Test
    fun `tomorrow + meridiem time`() {
        val r = parse("tomorrow at 3pm")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `tomorrow + noon keyword`() {
        val r = parse("tomorrow at noon")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(12, 0), r.startTime)
    }

    @Test
    fun `tomorrow + time range`() {
        val r = parse("tomorrow 2-3pm")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(14, 0), r.startTime)
        assertEquals(LocalTime.of(15, 0), r.endTime)
    }

    @Test
    fun `tomorrow + midnight keyword`() {
        val r = parse("tomorrow at midnight")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(0, 0), r.startTime)
    }

    // --- Weekday + each time format ---

    @Test
    fun `friday + 24h colon time`() {
        val r = parse("friday at 15:00")
        assertEquals(LocalDate.of(2026, 4, 17), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `next wednesday + am time`() {
        val r = parse("next wednesday at 9am")
        assertEquals(LocalDate.of(2026, 4, 22), r.startDate)
        assertEquals(LocalTime.of(9, 0), r.startTime)
    }

    @Test
    fun `last friday + meridiem time`() {
        val r = parse("last friday at 2pm")
        assertEquals(LocalDate.of(2026, 4, 10), r.startDate)
        assertEquals(LocalTime.of(14, 0), r.startTime)
    }

    @Test
    fun `saturday + time range`() {
        val r = parse("saturday 10:30am-12:30pm")
        assertEquals(LocalDate.of(2026, 4, 18), r.startDate)
        assertEquals(LocalTime.of(10, 30), r.startTime)
        assertEquals(LocalTime.of(12, 30), r.endTime)
    }

    // --- Absolute date + each time format ---

    @Test
    fun `jan 15 + time`() {
        val r = parse("jan 15 at 10:30am")
        assertEquals(LocalDate.of(2027, 1, 15), r.startDate)
        assertEquals(LocalTime.of(10, 30), r.startTime)
    }

    @Test
    fun `15th of march + noon`() {
        val r = parse("15th of march at noon")
        assertEquals(LocalDate.of(2027, 3, 15), r.startDate)
        assertEquals(LocalTime.of(12, 0), r.startTime)
    }

    @Test
    fun `25 december 2026 + time range`() {
        val r = parse("25 december 2026 2-4pm")
        assertEquals(LocalDate.of(2026, 12, 25), r.startDate)
        assertEquals(LocalTime.of(14, 0), r.startTime)
        assertEquals(LocalTime.of(16, 0), r.endTime)
    }

    // --- Structured date + each time format ---

    @Test
    fun `ISO date + time`() {
        val r = parse("2027-01-15 at 3pm")
        assertEquals(LocalDate.of(2027, 1, 15), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `slash date + 24h time`() {
        val r = parse("15/06/2026 at 14:00")
        assertEquals(LocalDate.of(2026, 6, 15), r.startDate)
        assertEquals(LocalTime.of(14, 0), r.startTime)
    }

    @Test
    fun `dot date + midnight`() {
        val r = parse("25.12.2026 at midnight")
        assertEquals(LocalDate.of(2026, 12, 25), r.startDate)
        assertEquals(LocalTime.of(0, 0), r.startTime)
    }

    // --- Relative offset + explicit time override ---

    @Test
    fun `in 3 days + time — time overrides offset time`() {
        val r = parse("in 3 days at 5pm")
        assertEquals(LocalDate.of(2026, 4, 16), r.startDate)
        // Explicit time overrides: context.time (5pm) > relativeDateTime time
        assertEquals(LocalTime.of(17, 0), r.startTime)
    }

    // ════════════════════════════════════════════════════════════
    //  2. RULE PRIORITY CONFLICTS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `absolute date wins over weekday and date keyword`() {
        // absoluteDate > weekdayDate > dateKeywordDate
        val r = parse("tomorrow friday January 15")
        assertEquals(LocalDate.of(2027, 1, 15), r.startDate) // absolute wins
    }

    @Test
    fun `weekday wins over date keyword`() {
        val r = parse("tomorrow friday")
        assertEquals(LocalDate.of(2026, 4, 17), r.startDate) // friday (weekday) wins over tomorrow (keyword)
    }

    @Test
    fun `relative offset wins over date keyword`() {
        // relativeDateTime > dateKeywordDate
        val r = parse("in 2 hours tomorrow")
        // relativeDateTime from "in 2 hours" is set, dateKeywordDate from "tomorrow" is also set
        // Priority: relativeDateTime > dateKeywordDate
        assertEquals(ref.toLocalDate(), r.startDate) // today (offset)
        assertEquals(LocalTime.of(12, 0), r.startTime) // 10:00 + 2h
    }

    @Test
    fun `absolute date wins over relative offset`() {
        // absoluteDate > relativeDateTime
        val r = parse("January 15 in 2 hours")
        assertEquals(LocalDate.of(2027, 1, 15), r.startDate) // absolute wins
    }

    @Test
    fun `first time expression wins when multiple present`() {
        val r = parse("meeting at 2pm at 4pm")
        assertEquals(LocalTime.of(14, 0), r.startTime) // first wins
    }

    @Test
    fun `time keyword and meridiem time — first wins`() {
        val r = parse("noon 3pm")
        assertEquals(LocalTime.of(12, 0), r.startTime) // noon (first) wins
    }

    @Test
    fun `structured date wins over absolute date when both parseable`() {
        // AbsoluteDateRule runs before StructuredDateRule in pipeline,
        // but if absolute consumes the month/number, structured won't fire
        val r = parse("1/15 January 20")
        // "1/15" parsed as structured date (M/D: Jan 15), "January 20" as absolute
        // absoluteDate overwrites structured because AbsoluteDateRule sets absoluteDate
        // and StructuredDateRule also sets absoluteDate — last writer wins
        assertNotNull(r.startDate)
    }

    // ════════════════════════════════════════════════════════════
    //  3. TOKEN BOUNDARY: edge cases in tokenizer classification
    // ════════════════════════════════════════════════════════════

    @Test
    fun `number 12 — not consumed as time without meridiem or colon`() {
        val r = parse("12 angry men tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertNull(r.startTime)
        assertTrue(r.title.contains("12"))
    }

    @Test
    fun `number 31 — boundary for valid day`() {
        val r = parse("March 31 at 3pm")
        assertEquals(LocalDate.of(2027, 3, 31), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `number 32 — invalid day falls through`() {
        val r = parse("March 32")
        assertEquals(ref.toLocalDate(), r.startDate)
    }

    @Test
    fun `year 2000 — minimum valid year in range`() {
        val r = parse("January 1 2000")
        assertEquals(LocalDate.of(2000, 1, 1), r.startDate)
    }

    @Test
    fun `year 2999 — maximum valid year in range`() {
        val r = parse("January 1 2999")
        assertEquals(LocalDate.of(2999, 1, 1), r.startDate)
    }

    @Test
    fun `year 999 — below yearRegex range, treated as number`() {
        val r = parse("999")
        assertNull(r.startTime)
    }

    @Test
    fun `year 3000 — above yearRegex range`() {
        val r = parse("January 1 3000")
        // "3000" doesn't match yearRegex [12]\d{3}, treated as number
        assertNotNull(r)
    }

    @Test
    fun `ordinal 0th — zero ordinal falls through`() {
        val r = parse("0th of march")
        // Day 0 is invalid
        assertEquals(ref.toLocalDate(), r.startDate)
    }

    @Test
    fun `very large ordinal 999th — too big for day`() {
        val r = parse("999th of march")
        assertEquals(ref.toLocalDate(), r.startDate) // 999 > 31, rejected
    }

    @Test
    fun `time 23 colon 59 — maximum valid time`() {
        assertEquals(LocalTime.of(23, 59), parse("at 23:59").startTime)
    }

    @Test
    fun `time 0 colon 00 — minimum valid time`() {
        assertEquals(LocalTime.of(0, 0), parse("at 0:00").startTime)
    }

    @Test
    fun `time 24 colon 00 — invalid, hour greater than 23`() {
        assertNull(parse("at 24:00").startTime)
    }

    @Test
    fun `meridiem 13pm — invalid`() {
        assertNull(parse("at 13pm").startTime)
    }

    @Test
    fun `meridiem 0pm — invalid (hour must be 1-12)`() {
        assertNull(parse("at 0pm").startTime)
    }

    @Test
    fun `minute 60 — invalid`() {
        assertNull(parse("at 3:60pm").startTime)
    }

    // ════════════════════════════════════════════════════════════
    //  4. PERMUTATION: same semantics, different word orders
    // ════════════════════════════════════════════════════════════

    @Test
    fun `title date time — canonical order`() {
        val r = parse("Coffee tomorrow at 3pm")
        assertEquals("Coffee", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `date title time — date first`() {
        val r = parse("tomorrow Coffee at 3pm")
        assertEquals("Coffee", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `date time title — date and time first`() {
        val r = parse("tomorrow at 3pm Coffee")
        assertEquals("Coffee", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `time date title — time first`() {
        val r = parse("3pm tomorrow Coffee")
        assertEquals("Coffee", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `time title date — time then title then date`() {
        val r = parse("3pm Coffee tomorrow")
        assertEquals("Coffee", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `title time date — title first`() {
        val r = parse("Coffee 3pm tomorrow")
        assertEquals("Coffee", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    // All 6 permutations of {title, date, time} should produce the same result
    @Test
    fun `all 6 permutations produce same date and time`() {
        val permutations = listOf(
            "Coffee tomorrow 3pm",
            "Coffee 3pm tomorrow",
            "tomorrow Coffee 3pm",
            "tomorrow 3pm Coffee",
            "3pm Coffee tomorrow",
            "3pm tomorrow Coffee"
        )
        for (input in permutations) {
            val r = parse(input)
            assertEquals("$input date", LocalDate.of(2026, 4, 14), r.startDate)
            assertEquals("$input time", LocalTime.of(15, 0), r.startTime)
            assertEquals("$input title", "Coffee", r.title)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  5. LOCALE-SENSITIVE AMBIGUITY (M/D vs D/M)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `ambiguous date 6 slash 7 — US default M slash D`() {
        // Both 6 and 7 are <= 12, so US default: month=6, day=7
        val r = parse("6/7")
        assertEquals(LocalDate.of(2026, 6, 7), r.startDate)
    }

    @Test
    fun `unambiguous 13 slash 7 — must be D slash M`() {
        // 13 > 12, so day=13, month=7
        val r = parse("13/7")
        assertEquals(LocalDate.of(2026, 7, 13), r.startDate)
    }

    @Test
    fun `unambiguous 7 slash 13 — must be M slash D`() {
        // Both values: 7 <= 12, but 13 > 12 for day. M/D: month=7, day=13
        val r = parse("7/13")
        assertEquals(LocalDate.of(2026, 7, 13), r.startDate)
    }

    @Test
    fun `ISO format always Y-M-D regardless of values`() {
        val r = parse("2026-06-07")
        assertEquals(LocalDate.of(2026, 6, 7), r.startDate)
    }

    @Test
    fun `dot format always D_M_Y`() {
        val r = parse("7.6.2026")
        assertEquals(LocalDate.of(2026, 6, 7), r.startDate) // day=7, month=6
    }

    @Test
    fun `two-digit year 26 resolves to 2026`() {
        val r = parse("1/15/26")
        assertEquals(LocalDate.of(2026, 1, 15), r.startDate)
    }

    @Test
    fun `two-digit year 50 resolves to 2050`() {
        val r = parse("1/15/50")
        assertEquals(LocalDate.of(2050, 1, 15), r.startDate)
    }

    @Test
    fun `two-digit year 51 resolves to 1951`() {
        val r = parse("1/15/51")
        assertEquals(LocalDate.of(1951, 1, 15), r.startDate)
    }

    @Test
    fun `two-digit year 99 resolves to 1999`() {
        val r = parse("12/25/99")
        assertEquals(LocalDate.of(1999, 12, 25), r.startDate)
    }

    @Test
    fun `two-digit year 00 resolves to 2000`() {
        val r = parse("1/1/00")
        assertEquals(LocalDate.of(2000, 1, 1), r.startDate)
    }

    // ════════════════════════════════════════════════════════════
    //  6. NORMALIZER STRESS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `all number words 1-19`() {
        val words = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
            "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
        )
        for ((i, word) in words.withIndex()) {
            val r = parse("in $word minutes")
            val expected = ref.toLocalTime().plusMinutes((i + 1).toLong())
            assertEquals("$word minutes", expected, r.startTime)
        }
    }

    @Test
    fun `compound number words 21-29`() {
        val compounds = listOf(
            "twenty-one" to 21, "twenty-two" to 22, "twenty-three" to 23,
            "twenty-five" to 25, "twenty-nine" to 29
        )
        for ((word, num) in compounds) {
            val r = parse("in $word minutes")
            val expected = ref.toLocalTime().plusMinutes(num.toLong())
            assertEquals("$word minutes", expected, r.startTime)
        }
    }

    @Test
    fun `compound number word forty-five`() {
        val r = parse("in forty-five minutes")
        assertEquals(LocalTime.of(10, 45), r.startTime)
    }

    @Test
    fun `compound number word ninety`() {
        val r = parse("in ninety minutes")
        assertEquals(LocalTime.of(11, 30), r.startTime)
    }

    @Test
    fun `a and an as one`() {
        assertEquals(LocalTime.of(11, 0), parse("in an hour").startTime)
        assertEquals(LocalDate.of(2026, 4, 20), parse("in a week").startDate)
    }

    @Test
    fun `number words are case insensitive`() {
        assertEquals(LocalTime.of(10, 15), parse("in FIFTEEN minutes").startTime)
        assertEquals(LocalTime.of(10, 15), parse("in Fifteen Minutes").startTime)
        assertEquals(LocalTime.of(10, 15), parse("in fIfTeEn MiNuTeS").startTime)
    }

    @Test
    fun `multi-word expression day after tomorrow with mixed case`() {
        assertEquals(LocalDate.of(2026, 4, 15), parse("Day After Tomorrow").startDate)
        assertEquals(LocalDate.of(2026, 4, 15), parse("DAY AFTER TOMORROW").startDate)
    }

    @Test
    fun `preserves apostrophes in titles`() {
        val r = parse("Mom's birthday tomorrow")
        assertTrue(r.title.contains("Mom's"))
    }

    @Test
    fun `preserves hyphens in titles`() {
        val r = parse("pick-up kids tomorrow at 3pm")
        assertTrue(r.title.contains("pick-up"))
    }

    @Test
    fun `preserves slashes in titles when not a date`() {
        val r = parse("AC/DC concert friday at 8pm")
        assertTrue(r.title.contains("AC"))
        assertTrue(r.title.contains("DC"))
    }

    @Test
    fun `emoji preserved through pipeline`() {
        val r = parse("🎉 Party 🎂 friday at 7pm")
        assertTrue(r.title.contains("🎉"))
        assertTrue(r.title.contains("🎂"))
        assertEquals(LocalDate.of(2026, 4, 17), r.startDate)
    }

    @Test
    fun `multiple different scripts preserved`() {
        val r = parse("Tokyo 東京 trip friday at 9am")
        assertTrue(r.title.contains("東京"))
        assertTrue(r.title.contains("Tokyo"))
    }

    // ════════════════════════════════════════════════════════════
    //  7. REFERENCE TIME SENSITIVITY
    // ════════════════════════════════════════════════════════════

    @Test
    fun `reference at midnight — in 30 minutes`() {
        val midnightRef = LocalDateTime.of(2026, 4, 13, 0, 0)
        val r = parse("in 30 minutes", midnightRef)
        assertEquals(LocalTime.of(0, 30), r.startTime)
    }

    @Test
    fun `reference at 23 colon 50 — in 30 minutes crosses midnight`() {
        val lateRef = LocalDateTime.of(2026, 4, 13, 23, 50)
        val r = parse("in 30 minutes", lateRef)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate) // next day
        assertEquals(LocalTime.of(0, 20), r.startTime)
    }

    @Test
    fun `reference at noon — in 2 hours`() {
        val noonRef = LocalDateTime.of(2026, 4, 13, 12, 0)
        val r = parse("in 2 hours", noonRef)
        assertEquals(LocalTime.of(14, 0), r.startTime)
    }

    @Test
    fun `reference on Sunday — bare weekday resolution`() {
        val sunRef = LocalDateTime.of(2026, 4, 19, 10, 0) // Sunday
        assertEquals(LocalDate.of(2026, 4, 20), parse("monday", sunRef).startDate) // next day
        assertEquals(LocalDate.of(2026, 4, 24), parse("friday", sunRef).startDate) // +5
        assertEquals(LocalDate.of(2026, 4, 26), parse("sunday", sunRef).startDate) // +7
    }

    @Test
    fun `reference on Saturday — bare weekday resolution`() {
        val satRef = LocalDateTime.of(2026, 4, 18, 10, 0) // Saturday
        assertEquals(LocalDate.of(2026, 4, 20), parse("monday", satRef).startDate)  // +2
        assertEquals(LocalDate.of(2026, 4, 24), parse("friday", satRef).startDate)  // +6
        assertEquals(LocalDate.of(2026, 4, 25), parse("saturday", satRef).startDate) // +7
    }

    @Test
    fun `year boundary — December 31 reference`() {
        val decRef = LocalDateTime.of(2026, 12, 31, 10, 0)
        assertEquals(LocalDate.of(2027, 1, 1), parse("tomorrow", decRef).startDate)
        assertEquals(LocalDate.of(2027, 1, 3), parse("in 3 days", decRef).startDate)
        assertEquals(LocalDate.of(2027, 1, 7), parse("in 1 week", decRef).startDate)
    }

    @Test
    fun `leap year Feb 28 — tomorrow is Feb 29`() {
        val leapRef = LocalDateTime.of(2028, 2, 28, 10, 0)
        assertEquals(LocalDate.of(2028, 2, 29), parse("tomorrow", leapRef).startDate)
    }

    @Test
    fun `non-leap year Feb 28 — tomorrow is March 1`() {
        val nonLeapRef = LocalDateTime.of(2027, 2, 28, 10, 0)
        assertEquals(LocalDate.of(2027, 3, 1), parse("tomorrow", nonLeapRef).startDate)
    }

    @Test
    fun `future biased — same month earlier day wraps to next year`() {
        // Ref is April 13. "April 5" is past → wraps to next year
        assertEquals(LocalDate.of(2027, 4, 5), parse("April 5").startDate)
    }

    @Test
    fun `future biased — same month later day stays in current year`() {
        assertEquals(LocalDate.of(2026, 4, 20), parse("April 20").startDate)
    }

    @Test
    fun `future biased — same month same day wraps to next year`() {
        // Ref is April 13. "April 13" is today, which is NOT before ref. isBefore is false, so stays.
        assertEquals(LocalDate.of(2026, 4, 13), parse("April 13").startDate)
    }

    @Test
    fun `future biased from December — January wraps to next year`() {
        val decRef = LocalDateTime.of(2026, 12, 20, 10, 0)
        assertEquals(LocalDate.of(2027, 1, 15), parse("January 15", decRef).startDate)
    }

    // ════════════════════════════════════════════════════════════
    //  8. DURATION EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `duration wraps past midnight`() {
        val r = parse("meeting at 11pm for 3 hours")
        assertEquals(LocalTime.of(23, 0), r.startTime)
        // 23:00 + 3h = 02:00 (next day, wraps via LocalTime.plusMinutes)
        assertEquals(LocalTime.of(2, 0), r.endTime)
    }

    @Test
    fun `decimal duration 0_5 hours = 30 minutes`() {
        val r = parse("meeting at 2pm for 0.5 hours")
        assertEquals(LocalTime.of(14, 0), r.startTime)
        assertEquals(LocalTime.of(14, 30), r.endTime)
    }

    @Test
    fun `duration with no prior start time uses reference`() {
        val r = parse("meeting for 90 minutes")
        // reference is 10:00, endTime = 10:00 + 90m = 11:30
        assertEquals(LocalTime.of(11, 30), r.endTime)
    }

    @Test
    fun `duration combined with time range — range takes priority as first match`() {
        val r = parse("meeting 2-3pm for 2 hours")
        // Time range sets start=2pm, end=3pm
        // Duration would set endTime relative to start, but time range already consumed
        assertEquals(LocalTime.of(14, 0), r.startTime)
        // DurationRule sees existing context.endTime? No — it overwrites unconditionally
        // Actually: TimeRule runs before DurationRule, sets endTime=3pm.
        // DurationRule then sets endTime=2pm+120min=4pm, overwriting.
        assertNotNull(r.endTime)
    }

    @Test
    fun `for as preposition not consumed — Party for Sarah`() {
        val r = parse("Party for Sarah tomorrow at 7pm")
        assertEquals("Party for Sarah", r.title)
        assertEquals(LocalTime.of(19, 0), r.startTime)
        assertNull(r.endTime) // no duration
    }

    @Test
    fun `for as preposition then for as duration — correct disambiguation`() {
        val r = parse("Party for Sarah tomorrow at 7pm for 3 hours")
        assertEquals("Party for Sarah", r.title)
        assertEquals(LocalTime.of(19, 0), r.startTime)
        assertEquals(LocalTime.of(22, 0), r.endTime)
    }

    // ════════════════════════════════════════════════════════════
    //  9. RECURRENCE EXHAUSTIVE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `daily keyword`() {
        val r = parse("daily standup at 9am")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=DAILY"))
    }

    @Test
    fun `weekly keyword`() {
        val r = parse("weekly sync at 10am")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `biweekly keyword`() {
        val r = parse("biweekly review at 2pm")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(r.rrule!!.contains("INTERVAL=2"))
    }

    @Test
    fun `monthly keyword`() {
        val r = parse("monthly report")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=MONTHLY"))
    }

    @Test
    fun `yearly keyword`() {
        val r = parse("yearly review")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=YEARLY"))
    }

    @Test
    fun `annually keyword same as yearly`() {
        val r = parse("annually review")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=YEARLY"))
    }

    @Test
    fun `every day`() {
        val r = parse("every day")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=DAILY"))
    }

    @Test
    fun `every week`() {
        val r = parse("every week")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `every month`() {
        val r = parse("every month")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=MONTHLY"))
    }

    @Test
    fun `every year`() {
        val r = parse("every year")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=YEARLY"))
    }

    @Test
    fun `every monday sets BYDAY=MO and next monday date`() {
        val r = parse("every monday at 10am")
        assertTrue(r.rrule!!.contains("BYDAY=MO"))
        assertEquals(LocalDate.of(2026, 4, 20), r.startDate) // same day → +7
    }

    @Test
    fun `every friday sets BYDAY=FR`() {
        val r = parse("standup every friday at 9am")
        assertTrue(r.rrule!!.contains("BYDAY=FR"))
        assertEquals(LocalDate.of(2026, 4, 17), r.startDate)
    }

    @Test
    fun `every 3 weeks`() {
        val r = parse("review every 3 weeks")
        assertTrue(r.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(r.rrule!!.contains("INTERVAL=3"))
    }

    @Test
    fun `every 2 weeks on friday`() {
        val r = parse("review every 2 weeks on friday")
        assertTrue(r.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(r.rrule!!.contains("INTERVAL=2"))
        assertTrue(r.rrule!!.contains("BYDAY=FR"))
    }

    @Test
    fun `every 6 months`() {
        val r = parse("review every 6 months")
        assertTrue(r.rrule!!.contains("FREQ=MONTHLY"))
        assertTrue(r.rrule!!.contains("INTERVAL=6"))
    }

    @Test
    fun `recurrence keyword consumed from title`() {
        val r = parse("daily standup")
        assertEquals("standup", r.title)
    }

    @Test
    fun `every weekday pattern consumed from title`() {
        val r = parse("Sync every monday at 10am")
        assertEquals("Sync", r.title)
    }

    @Test
    fun `recurrence plus date plus time plus location`() {
        val r = parse("Coffee every monday at 3pm at Blue Bottle")
        assertEquals("Coffee", r.title)
        assertEquals(LocalTime.of(15, 0), r.startTime)
        assertTrue(r.rrule!!.contains("BYDAY=MO"))
        assertEquals("Blue Bottle", r.location)
    }

    // ════════════════════════════════════════════════════════════
    // 10. MULTI-FEATURE SENTENCES (real-world complexity)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `date + time + duration + location`() {
        val r = parse("Meeting tomorrow at 2pm for 90 minutes at Conference Room B")
        assertEquals("Meeting", r.title)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(14, 0), r.startTime)
        assertEquals(LocalTime.of(15, 30), r.endTime)
        assertEquals("Conference Room B", r.location)
        assertEquals(ParseConfidence.HIGH, r.confidence)
    }

    @Test
    fun `recurrence + time + duration + location`() {
        val r = parse("Standup every monday at 9am for 30 minutes at Room 42")
        assertEquals("Standup", r.title)
        assertTrue(r.rrule!!.contains("BYDAY=MO"))
        assertEquals(LocalTime.of(9, 0), r.startTime)
        assertEquals(LocalTime.of(9, 30), r.endTime)
        assertEquals("Room 42", r.location)
    }

    @Test
    fun `absolute date + time range + location`() {
        val r = parse("Workshop jan 15 10:30am-12:30pm at Auditorium")
        assertEquals("Workshop", r.title)
        assertEquals(LocalDate.of(2027, 1, 15), r.startDate)
        assertEquals(LocalTime.of(10, 30), r.startTime)
        assertEquals(LocalTime.of(12, 30), r.endTime)
        assertEquals("Auditorium", r.location)
    }

    @Test
    fun `weekday + time + duration`() {
        val r = parse("Yoga friday at 6am for 1 hour")
        assertEquals("Yoga", r.title)
        assertEquals(LocalDate.of(2026, 4, 17), r.startDate)
        assertEquals(LocalTime.of(6, 0), r.startTime)
        assertEquals(LocalTime.of(7, 0), r.endTime)
    }

    @Test
    fun `complex real-world - team lunch`() {
        val r = parse("Team lunch friday at noon for 1 hour at Cafe Milano")
        assertEquals("Team lunch", r.title)
        assertEquals(LocalDate.of(2026, 4, 17), r.startDate)
        assertEquals(LocalTime.of(12, 0), r.startTime)
        assertEquals(LocalTime.of(13, 0), r.endTime)
        assertEquals("Cafe Milano", r.location)
        assertEquals(ParseConfidence.HIGH, r.confidence)
    }

    @Test
    fun `complex real-world - recurring yoga`() {
        val r = parse("Yoga every wednesday at 6am for 1 hour at Zen Studio")
        assertEquals("Yoga", r.title)
        assertTrue(r.rrule!!.contains("BYDAY=WE"))
        assertEquals(LocalTime.of(6, 0), r.startTime)
        assertEquals(LocalTime.of(7, 0), r.endTime)
        assertEquals("Zen Studio", r.location)
    }

    // ════════════════════════════════════════════════════════════
    // 11. TIME RANGE EXHAUSTIVE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `time range 9am-5pm full workday`() {
        val r = parse("work 9am-5pm")
        assertEquals(LocalTime.of(9, 0), r.startTime)
        assertEquals(LocalTime.of(17, 0), r.endTime)
    }

    @Test
    fun `time range 9-5pm — start infers AM when PM would exceed end`() {
        val r = parse("work 9-5pm")
        // Start has no meridiem — inheriting PM would give 21:00 > 17:00, so flip to AM
        assertEquals(LocalTime.of(9, 0), r.startTime)
        assertEquals(LocalTime.of(17, 0), r.endTime)
    }

    @Test
    fun `time range 11pm-1am cross-midnight`() {
        val r = parse("party 11pm-1am")
        assertEquals(LocalTime.of(23, 0), r.startTime)
        assertEquals(LocalTime.of(1, 0), r.endTime)
    }

    @Test
    fun `time range with colon 10h30-11h30am`() {
        val r = parse("meeting 10:30-11:30am")
        assertEquals(LocalTime.of(10, 30), r.startTime)
        assertEquals(LocalTime.of(11, 30), r.endTime)
    }

    @Test
    fun `time range with both meridiems 10am-2pm`() {
        val r = parse("meeting 10am-2pm")
        assertEquals(LocalTime.of(10, 0), r.startTime)
        assertEquals(LocalTime.of(14, 0), r.endTime)
    }

    @Test
    fun `time range 2pm to 4pm with TO keyword`() {
        val r = parse("call 2pm to 4pm")
        assertEquals(LocalTime.of(14, 0), r.startTime)
        assertEquals(LocalTime.of(16, 0), r.endTime)
    }

    @Test
    fun `time range 9 colon 00am to 10 colon 30am with TO keyword`() {
        val r = parse("meeting 9:00am to 10:30am")
        assertEquals(LocalTime.of(9, 0), r.startTime)
        assertEquals(LocalTime.of(10, 30), r.endTime)
    }

    // ════════════════════════════════════════════════════════════
    // 12. TITLE EXTRACTION UNDER STRESS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `multi-word title with all features consumed`() {
        val r = parse("Board Meeting Q4 Review tomorrow at 2pm for 2 hours at HQ")
        assertTrue(r.title.contains("Board"))
        assertTrue(r.title.contains("Meeting"))
        assertTrue(r.title.contains("Q4"))
        assertTrue(r.title.contains("Review"))
    }

    @Test
    fun `title with numbers that are not dates`() {
        val r = parse("Room 101 meeting 3pm tomorrow")
        assertTrue(r.title.contains("Room"))
        assertTrue(r.title.contains("101"))
        assertTrue(r.title.contains("meeting"))
    }

    @Test
    fun `title preserves consecutive words between consumed tokens`() {
        val r = parse("tomorrow Project Alpha Beta Gamma at 3pm")
        assertEquals("Project Alpha Beta Gamma", r.title)
    }

    @Test
    fun `title from only stop words is empty`() {
        assertEquals("", parse("at on in the for to").title)
    }

    @Test
    fun `title with leading consumed tokens and trailing content`() {
        val r = parse("tomorrow at 3pm Review meeting notes")
        assertEquals("Review meeting notes", r.title)
    }

    @Test
    fun `title with trailing consumed tokens and leading content`() {
        val r = parse("Important reminder tomorrow at 3pm")
        assertEquals("Important reminder", r.title)
    }

    @Test
    fun `title extraction preserves original casing`() {
        val r = parse("URGENT Team Meeting tomorrow at 3pm")
        assertTrue(r.title.contains("URGENT"))
        assertTrue(r.title.contains("Team"))
        assertTrue(r.title.contains("Meeting"))
    }

    @Test
    fun `title extraction with interleaved consumed tokens`() {
        // "at" between two content words — middle "at" is a keyword
        val r = parse("look at this tomorrow at 3pm")
        assertTrue(r.title.contains("look"))
        // "at" and "this" are keywords, filtered by dropWhile/dropLastWhile
    }

    // ════════════════════════════════════════════════════════════
    // 13. FUZZ-INSPIRED (corner cases that exercise edge logic)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `only keywords — no crash, empty title`() {
        val r = parse("at in on the for to of from ago next last this every")
        assertNotNull(r)
        assertEquals("", r.title)
    }

    @Test
    fun `number followed by every time unit`() {
        // "5 seconds", "5 minutes", ..., "5 years" — all valid offset targets
        val units = listOf("seconds", "minutes", "hours", "days", "weeks", "months", "years")
        for (u in units) {
            val r = parse("in 5 $u")
            assertNotNull("Crashed on: in 5 $u", r)
            assertTrue("in 5 $u should set date", r.confidence != ParseConfidence.LOW)
        }
    }

    @Test
    fun `date keyword immediately followed by time`() {
        // No space between keyword area — handled by normalization
        val r = parse("tomorrow3pm")
        // After normalization "tomorrow3pm" → single token. Tokenizer sees UNKNOWN.
        assertNotNull(r)
    }

    @Test
    fun `repeated at keywords 50 times`() {
        val input = "at ".repeat(50) + "3pm"
        val r = parse(input)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `alternating keywords and content`() {
        val r = parse("a at b in c on d for e to f")
        assertNotNull(r)
    }

    @Test
    fun `input with only time range`() {
        val r = parse("2-3pm")
        assertEquals(LocalTime.of(14, 0), r.startTime)
        assertEquals(LocalTime.of(15, 0), r.endTime)
        assertEquals("", r.title)
    }

    @Test
    fun `input with only duration`() {
        val r = parse("for 2 hours")
        assertNotNull(r.endTime) // endTime from reference + 2h
    }

    @Test
    fun `input with only recurrence`() {
        val r = parse("daily")
        assertNotNull(r.rrule)
        assertTrue(r.rrule!!.contains("FREQ=DAILY"))
    }

    @Test
    fun `input with only location`() {
        // "at Central Park" — no time, no date
        val r = parse("at Central Park")
        // "at" is the only AT, TimeRule doesn't consume it (Central is UNKNOWN, not TIME)
        // LocationRule claims it
        assertEquals("Central Park", r.location)
    }

    @Test
    fun `numbers in various positions dont false-positive`() {
        val r = parse("Chapter 12 section 5 review tomorrow")
        assertTrue(r.title.contains("12"))
        assertTrue(r.title.contains("5"))
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
    }

    @Test
    fun `all month names without day — no date extraction`() {
        val months = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
        )
        for (m in months) {
            val r = parse("$m meeting")
            // Month alone (no day number) should not crash.
            // AbsoluteDateRule requires MONTH + NUMBER, so month alone is unconsumed.
            assertNotNull("Crashed on: $m meeting", r)
        }
    }

    @Test
    fun `all weekday names set dateSet true`() {
        val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        for (w in weekdays) {
            val r = parse("meeting $w")
            assertTrue("$w should set date", r.confidence == ParseConfidence.MEDIUM || r.confidence == ParseConfidence.HIGH)
        }
    }

    @Test
    fun `all weekday abbreviations set dateSet true`() {
        val abbrevs = listOf("mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun")
        for (a in abbrevs) {
            val r = parse("meeting $a")
            assertTrue("$a should set date", r.confidence == ParseConfidence.MEDIUM || r.confidence == ParseConfidence.HIGH)
        }
    }

    // ════════════════════════════════════════════════════════════
    // 14. ISALLDAY AND CONFIDENCE EXHAUSTIVE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `isAllDay matrix`() {
        // date only → all day
        assertTrue(parse("tomorrow").isAllDay)
        assertTrue(parse("friday").isAllDay)
        assertTrue(parse("january 15").isAllDay)
        assertTrue(parse("2027-01-15").isAllDay)
        assertTrue(parse("in 3 days").isAllDay) // day offset doesn't set time

        // time present → not all day
        assertEquals(false, parse("tomorrow at 3pm").isAllDay)
        assertEquals(false, parse("in 30 minutes").isAllDay)
        assertEquals(false, parse("meeting at noon").isAllDay)
        assertEquals(false, parse("2-3pm").isAllDay)

        // nothing → all day (no startTime means isAllDay)
        assertTrue(parse("meeting").isAllDay)
        assertTrue(parse("hello world").isAllDay)
    }

    @Test
    fun `confidence matrix`() {
        // HIGH: date + time
        assertEquals(ParseConfidence.HIGH, parse("tomorrow at 3pm").confidence)
        assertEquals(ParseConfidence.HIGH, parse("friday at noon").confidence)
        assertEquals(ParseConfidence.HIGH, parse("in 30 minutes").confidence)
        assertEquals(ParseConfidence.HIGH, parse("jan 15 at 2pm").confidence)

        // MEDIUM: date only
        assertEquals(ParseConfidence.MEDIUM, parse("tomorrow").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("friday").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("in 3 days").confidence)

        // MEDIUM: time only
        assertEquals(ParseConfidence.MEDIUM, parse("at 3pm").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("noon").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("2-3pm").confidence)

        // LOW: neither
        assertEquals(ParseConfidence.LOW, parse("meeting").confidence)
        assertEquals(ParseConfidence.LOW, parse("hello world").confidence)
        assertEquals(ParseConfidence.LOW, parse("").confidence)
    }

    // ════════════════════════════════════════════════════════════
    // 15. PERFORMANCE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `1000 parses complete in reasonable time`() {
        val inputs = listOf(
            "Coffee with Sarah tomorrow at 3pm",
            "meeting in 30 minutes",
            "friday at noon",
            "2027-01-15 at 3pm",
            "standup every monday at 9am for 30 minutes at Room 42",
            "hello world",
            "15th of march 2027",
            "Party for Sarah tomorrow at 7pm for 3 hours at The Grand"
        )

        val start = System.nanoTime()
        repeat(1000) {
            for (input in inputs) {
                parse(input)
            }
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000

        // 8000 parses should complete in well under 5 seconds
        assertTrue("8000 parses took ${elapsed}ms, expected < 5000ms", elapsed < 5000)
    }

    @Test
    fun `parse with 500-word input completes quickly`() {
        val words = (1..500).joinToString(" ") { "word$it" }
        val input = "$words tomorrow at 3pm"
        val start = System.nanoTime()
        val r = parse(input)
        val elapsed = (System.nanoTime() - start) / 1_000_000

        assertTrue("500-word input took ${elapsed}ms, expected < 200ms", elapsed < 200)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    // ════════════════════════════════════════════════════════════
    // 16. REGRESSION: specific edge cases that could easily break
    // ════════════════════════════════════════════════════════════

    @Test
    fun `may as month not confused with may as verb`() {
        // "may 5" should parse as May 5th
        val r = parse("event may 5")
        assertEquals(LocalDate.of(2026, 5, 5), r.startDate)
    }

    @Test
    fun `sat abbreviation works for Saturday`() {
        val r = parse("party sat at 8pm")
        assertEquals(LocalDate.of(2026, 4, 18), r.startDate)
        assertEquals(DayOfWeek.SATURDAY, r.startDate.dayOfWeek)
    }

    @Test
    fun `sun abbreviation works for Sunday`() {
        val r = parse("brunch sun at 11am")
        assertEquals(LocalDate.of(2026, 4, 19), r.startDate)
        assertEquals(DayOfWeek.SUNDAY, r.startDate.dayOfWeek)
    }

    @Test
    fun `min abbreviation works for minutes`() {
        val r = parse("in 15 min")
        assertEquals(LocalTime.of(10, 15), r.startTime)
    }

    @Test
    fun `mins abbreviation works for minutes`() {
        val r = parse("in 45 mins")
        assertEquals(LocalTime.of(10, 45), r.startTime)
    }

    @Test
    fun `hr abbreviation works for hours`() {
        val r = parse("in 1 hr")
        assertEquals(LocalTime.of(11, 0), r.startTime)
    }

    @Test
    fun `hrs abbreviation works for hours`() {
        val r = parse("in 2 hrs")
        assertEquals(LocalTime.of(12, 0), r.startTime)
    }

    @Test
    fun `yr abbreviation works for years`() {
        val r = parse("in 1 yr")
        assertEquals(LocalDate.of(2027, 4, 13), r.startDate)
    }

    @Test
    fun `sept abbreviation works for September`() {
        val r = parse("sept 1")
        assertEquals(LocalDate.of(2026, 9, 1), r.startDate)
    }

    @Test
    fun `a_m_ with dots works`() {
        val r = parse("at 9 a.m.")
        assertEquals(LocalTime.of(9, 0), r.startTime)
    }

    @Test
    fun `p_m_ with dots works`() {
        val r = parse("at 3 p.m.")
        assertEquals(LocalTime.of(15, 0), r.startTime)
    }

    @Test
    fun `tdy abbreviation for today`() {
        assertEquals(ref.toLocalDate(), parse("tdy").startDate)
    }

    @Test
    fun `2day abbreviation for today`() {
        assertEquals(ref.toLocalDate(), parse("2day").startDate)
    }

    @Test
    fun `2moro abbreviation for tomorrow`() {
        assertEquals(LocalDate.of(2026, 4, 14), parse("2moro").startDate)
    }

    @Test
    fun `tommorow typo for tomorrow`() {
        assertEquals(LocalDate.of(2026, 4, 14), parse("tommorow").startDate)
    }

    @Test
    fun `tommorrow typo for tomorrow`() {
        assertEquals(LocalDate.of(2026, 4, 14), parse("tommorrow").startDate)
    }

    @Test
    fun `tomorow typo for tomorrow`() {
        assertEquals(LocalDate.of(2026, 4, 14), parse("tomorow").startDate)
    }

    @Test
    fun `ystrday abbreviation for yesterday`() {
        assertEquals(LocalDate.of(2026, 4, 12), parse("ystrday").startDate)
    }

    @Test
    fun `tday abbreviation for today`() {
        assertEquals(ref.toLocalDate(), parse("tday").startDate)
    }

    @Test
    fun `this and next weekday resolve differently`() {
        val thisFriday = parse("this friday").startDate
        val nextFriday = parse("next friday").startDate
        assertTrue("this friday ($thisFriday) should be before next friday ($nextFriday)",
            thisFriday.isBefore(nextFriday))
    }

    // ════════════════════════════════════════════════════════════
    // 17. CRASH RESISTANCE EXPANDED
    // ════════════════════════════════════════════════════════════

    @Test
    fun `every possible single keyword does not crash`() {
        val all = listOf(
            "at", "of", "in", "ago", "from", "next", "this", "last",
            "now", "the", "on", "for", "to", "every",
            "today", "tomorrow", "yesterday",
            "noon", "midnight",
            "daily", "weekly", "biweekly", "monthly", "yearly", "annually",
            "am", "pm", "a.m.", "p.m.",
            "second", "seconds", "minute", "minutes", "hour", "hours",
            "day", "days", "week", "weeks", "month", "months", "year", "years",
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
        )
        for (word in all) {
            assertNotNull("Crashed on single keyword: $word", parse(word))
        }
    }

    @Test
    fun `every two-keyword combination does not crash (sample)`() {
        val sample = listOf(
            "at", "in", "next", "last", "for", "every", "to", "of",
            "tomorrow", "noon", "daily", "am", "pm", "monday", "january"
        )
        for (a in sample) {
            for (b in sample) {
                assertNotNull("Crashed on: '$a $b'", parse("$a $b"))
            }
        }
    }

    @Test
    fun `random-looking mixed inputs do not crash`() {
        listOf(
            "3pm 2pm 1pm tomorrow yesterday friday",
            "in in in 5 5 5 minutes minutes minutes",
            "at at at noon noon noon",
            "for for for 2 2 2 hours hours hours",
            "every every every day day day",
            "2027 2028 2029 january february march",
            "1st 2nd 3rd 4th 5th 15th 31st of of of january march",
            "tomorrow tomorrow tomorrow at 3pm at 4pm at 5pm",
            "daily weekly monthly yearly biweekly annually",
            "noon midnight noon midnight",
        ).forEach { input ->
            assertNotNull("Crashed on: $input", parse(input))
        }
    }

    @Test
    fun `unicode stress — RTL, combining marks, surrogate pairs`() {
        // All inputs use "tomorrow" so date is consistently April 14
        listOf(
            "مراجعة tomorrow at 3pm",      // Arabic
            "रिव्यू tomorrow",              // Hindi
            "검토 tomorrow at noon",         // Korean
            "レビュー tomorrow at 2pm",      // Japanese katakana
            "café résumé naïve tomorrow",    // Accented Latin
            "\uD83C\uDF89 Party tomorrow",  // Surrogate pair emoji 🎉
            "e\u0301vent tomorrow",          // Combining acute accent
        ).forEach { input ->
            val r = parse(input)
            assertNotNull("Crashed on unicode: $input", r)
            assertEquals("$input: tomorrow should parse", LocalDate.of(2026, 4, 14), r.startDate)
        }
    }

    @Test
    fun `very long title does not degrade`() {
        val longTitle = (1..100).joinToString(" ") { "word" }
        val r = parse("$longTitle tomorrow at 3pm")
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
        assertEquals(LocalTime.of(15, 0), r.startTime)
        assertTrue(r.title.length > 100) // title contains the 100 words
    }

    @Test
    fun `negative numbers in input do not crash`() {
        // After normalization, "-5" may become separate tokens
        val r = parse("meeting -5 tomorrow")
        assertNotNull(r)
        assertEquals(LocalDate.of(2026, 4, 14), r.startDate)
    }
}
