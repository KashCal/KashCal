package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Adversarial and exhaustive testing for QuickAddParser.
 *
 * Categories:
 *  1. Crash resistance (fuzz-like inputs)
 *  2. Integer overflow and boundary values
 *  3. Ambiguity resolution (conflicting signals)
 *  4. Title extraction integrity
 *  5. False positive resistance (numbers/words that shouldn't parse)
 *  6. Unicode and special characters
 *  7. Rule interaction and ordering
 *  8. Structured date edge cases
 *  9. Time parsing edge cases
 * 10. Determinism and idempotency
 * 11. Real-world corpus
 * 12. Performance under adversarial inputs
 */
class QuickAddParserAdversarialTest {

    // Monday April 13, 2026 10:00 AM
    private val ref = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String, reference: LocalDateTime = ref) =
        QuickAddParser.parse(input, reference)

    // ════════════════════════════════════════════════════════════
    //  1. CRASH RESISTANCE — must never throw regardless of input
    // ════════════════════════════════════════════════════════════

    @Test
    fun `null-like inputs do not crash`() {
        // Empty, whitespace variants, control characters
        listOf(
            "", " ", "  ", "\t", "\n", "\r\n", "\t\n\r",
            "\u0000", "\u0001", "\u007F",          // NUL, SOH, DEL
            "\uFEFF",                               // BOM
            "\u200B", "\u200C", "\u200D",           // Zero-width space/joiner
            "\uFFFD",                               // Replacement character
        ).forEach { input ->
            assertNotNull("Crashed on: ${input.map { it.code }}", parse(input))
        }
    }

    @Test
    fun `extremely long input does not crash or hang`() {
        val longInput = "a".repeat(10_000) + " tomorrow at 3pm"
        val start = System.nanoTime()
        val result = parse(longInput)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertNotNull(result)
        assertTrue("Took ${elapsed}ms, expected < 500ms", elapsed < 500)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `input of only special characters does not crash`() {
        listOf(
            "!@#\$%^&*()", "<<<>>>", "{}[]|\\",
            "~`+=", ";;;", "...", "///", "---",
            "\"\"\"", "'''", "```", "***"
        ).forEach { input ->
            assertNotNull("Crashed on: $input", parse(input))
        }
    }

    @Test
    fun `deeply nested parentheses and brackets do not crash`() {
        val nested = "(" .repeat(100) + "meeting" + ")".repeat(100)
        assertNotNull(parse(nested))
    }

    @Test
    fun `repeated date keywords do not crash`() {
        listOf(
            "tomorrow tomorrow tomorrow",
            "today yesterday tomorrow",
            "next next next friday",
            "last last last monday",
            "tomorrow yesterday today",
        ).forEach { input ->
            assertNotNull("Crashed on: $input", parse(input))
        }
    }

    @Test
    fun `contradictory modifiers do not crash`() {
        listOf(
            "last next friday",
            "next last monday",
            "this last next tuesday",
        ).forEach { input ->
            assertNotNull("Crashed on: $input", parse(input))
        }
    }

    @Test
    fun `all keyword combinations do not crash`() {
        // Every keyword followed by every other keyword
        val keywords = listOf("at", "in", "on", "the", "next", "last", "this", "for", "to", "of", "from", "ago")
        for (a in keywords) {
            for (b in keywords) {
                assertNotNull("Crashed on: '$a $b'", parse("$a $b"))
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  2. INTEGER OVERFLOW AND BOUNDARY VALUES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `huge number does not crash (integer overflow guard)`() {
        // Numbers beyond Int.MAX_VALUE (2147483647)
        listOf(
            "99999999999 things to do",
            "9999999999th birthday",
            "99999999999",
            "meeting at 99999999999pm",
            "in 99999999999 minutes",
        ).forEach { input ->
            assertNotNull("Crashed on: $input", parse(input))
        }
    }

    @Test
    fun `huge number becomes part of title`() {
        val result = parse("99999999999 things to do tomorrow")
        assertTrue(result.title.contains("99999999999"))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `Int MAX_VALUE number does not crash`() {
        val result = parse("${Int.MAX_VALUE} tomorrow")
        assertNotNull(result)
    }

    @Test
    fun `invalid date boundaries handled gracefully`() {
        listOf(
            "February 30",      // Invalid day
            "February 31",      // Invalid day
            "April 31",         // Invalid day (April has 30)
            "January 0",        // Day 0
            "January 32",       // Day 32
            "February 29 2027", // Not a leap year
        ).forEach { input ->
            val result = parse(input)
            assertNotNull("Crashed on: $input", result)
            // Invalid dates should fall back to reference
            assertEquals("$input: expected reference date", ref.toLocalDate(), result.startDate)
        }
    }

    @Test
    fun `February 29 on leap year resolves correctly`() {
        val result = parse("February 29 2028")
        assertEquals(LocalDate.of(2028, 2, 29), result.startDate)
    }

    @Test
    fun `structured date with all zeros does not crash`() {
        val result = parse("0/0/0")
        assertNotNull(result)
    }

    @Test
    fun `structured date with large invalid parts does not crash`() {
        listOf("99/99/9999", "0/0", "32/13/2026", "13/32").forEach { input ->
            assertNotNull("Crashed on: $input", parse(input))
        }
    }

    @Test
    fun `time boundary values`() {
        // Valid boundaries
        assertEquals(LocalTime.of(0, 0), parse("at midnight").startTime)
        assertEquals(LocalTime.of(12, 0), parse("at noon").startTime)
        assertEquals(LocalTime.of(23, 59), parse("at 23:59").startTime)
        assertEquals(LocalTime.of(0, 0), parse("at 0:00").startTime)

        // Invalid times — should not parse as time
        assertNull("25:00 should not parse", parse("at 25:00").startTime)
        assertNull("13pm should not parse", parse("at 13pm").startTime)
        assertNull("0pm should not parse", parse("at 0pm").startTime)
        assertNull("0am should not parse", parse("at 0am").startTime)
    }

    @Test
    fun `minute value 60 does not parse as time`() {
        // 3:60 — minute >= 60 should not be valid
        assertNull(parse("at 3:60pm").startTime)
    }

    // ════════════════════════════════════════════════════════════
    //  3. AMBIGUITY RESOLUTION
    // ════════════════════════════════════════════════════════════

    @Test
    fun `two times in input — first wins`() {
        val result = parse("2pm meeting at 3pm")
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    @Test
    fun `two dates in input — weekday beats date keyword in resolve priority`() {
        // weekdayDate has higher resolve priority than dateKeywordDate
        // Priority: absoluteDate > relativeDateTime > weekdayDate > dateKeywordDate
        val result = parse("tomorrow friday")
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate) // friday wins
    }

    @Test
    fun `absolute date takes priority over weekday`() {
        // AbsoluteDate > Weekday in resolve order
        val result = parse("January 15 friday")
        assertEquals(LocalDate.of(2027, 1, 15), result.startDate)
    }

    @Test
    fun `relative offset and explicit time — explicit time wins`() {
        // "in 3 hours" sets relativeDateTime, "at 5pm" sets context.time
        // resolveTime() returns context.time if set
        val result = parse("in 3 hours at 5pm")
        assertEquals(LocalTime.of(17, 0), result.startTime)
    }

    @Test
    fun `date keyword and absolute date — absolute wins via priority`() {
        val result = parse("tomorrow January 15")
        // absoluteDate (Jan 15) has higher priority than dateKeywordDate (tomorrow)
        assertEquals(LocalDate.of(2027, 1, 15), result.startDate)
    }

    @Test
    fun `repeated same weekday uses first occurrence`() {
        val result = parse("friday friday friday")
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate)
    }

    // ════════════════════════════════════════════════════════════
    //  4. TITLE EXTRACTION INTEGRITY
    // ════════════════════════════════════════════════════════════

    @Test
    fun `title preserves words between consumed tokens`() {
        val result = parse("Coffee with Sarah tomorrow at 3pm")
        assertEquals("Coffee with Sarah", result.title)
    }

    @Test
    fun `title preserves leading words before date`() {
        val result = parse("Dentist appointment jan 15 at 2pm")
        assertEquals("Dentist appointment", result.title)
    }

    @Test
    fun `title preserves trailing words after date-time`() {
        val result = parse("tomorrow at 3pm Team standup")
        assertEquals("Team standup", result.title)
    }

    @Test
    fun `date-time only input produces empty title`() {
        assertEquals("", parse("tomorrow at 3pm").title)
        assertEquals("", parse("friday at noon").title)
        assertEquals("", parse("in 30 minutes").title)
    }

    @Test
    fun `possessives preserved in title`() {
        val result = parse("Doctor's appointment tomorrow")
        assertTrue(result.title.contains("Doctor's"))
    }

    @Test
    fun `hyphens preserved in title`() {
        val result = parse("re-schedule meeting tomorrow")
        assertTrue(result.title.contains("re-schedule"))
    }

    @Test
    fun `numbers in title preserved when not consumed by rules`() {
        val result = parse("15 things to do tomorrow")
        assertTrue(result.title.contains("15"))
    }

    @Test
    fun `title with only unconsumed stop words is empty`() {
        // "at the in on" — all are keywords, filtered from title
        val result = parse("at the in on")
        assertEquals("", result.title)
    }

    @Test
    fun `title preserves mixed case from original input`() {
        val result = parse("IMPORTANT Meeting tomorrow at 3pm")
        assertTrue(result.title.contains("IMPORTANT"))
        assertTrue(result.title.contains("Meeting"))
    }

    @Test
    fun `multiple spaces in title are normalized`() {
        val result = parse("Coffee   with    Sarah   tomorrow")
        assertEquals("Coffee with Sarah", result.title)
    }

    // ════════════════════════════════════════════════════════════
    //  5. FALSE POSITIVE RESISTANCE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `bare small number is not parsed as date`() {
        // "5" alone should not become a date
        val result = parse("5 things to do")
        assertTrue(result.title.contains("5"))
        assertEquals(ref.toLocalDate(), result.startDate) // Falls back to reference
    }

    @Test
    fun `bare 12 is not parsed as time or date`() {
        val result = parse("12 angry men")
        assertTrue(result.title.contains("12"))
        assertNull(result.startTime)
    }

    @Test
    fun `bare year is not parsed as date`() {
        val result = parse("meeting about 2026 goals")
        // "2026" is YEAR token, not consumed by any rule alone
        assertNull(result.startTime)
    }

    @Test
    fun `room number not parsed as time`() {
        val result = parse("Room 101 tomorrow")
        assertTrue(result.title.contains("Room"))
        assertTrue(result.title.contains("101"))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `building number not parsed as date`() {
        val result = parse("Building 5 meeting tomorrow")
        assertTrue(result.title.contains("5"))
    }

    @Test
    fun `chapter number not parsed as time`() {
        val result = parse("Chapter 12 review tomorrow")
        assertTrue(result.title.contains("12"))
    }

    @Test
    fun `large number not parsed as structured date`() {
        val result = parse("Highway 101 road trip friday")
        assertTrue(result.title.contains("101"))
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate)
    }

    @Test
    fun `month name as verb is consumed (known limitation)`() {
        // "may" the verb is indistinguishable from "May" the month
        val result = parse("I may go tomorrow")
        // "may" is tokenized as MONTH — this is a known ambiguity
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `ordinal in title context preserved when no month follows`() {
        val result = parse("1st place finish tomorrow")
        assertTrue(result.title.contains("1"))
    }

    // ════════════════════════════════════════════════════════════
    //  6. UNICODE AND SPECIAL CHARACTERS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `emoji in title preserved through pipeline`() {
        val result = parse("☕ Coffee ☕ tomorrow at 3pm")
        assertTrue(result.title.contains("☕"))
        assertTrue(result.title.contains("Coffee"))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `multiple different emoji preserved`() {
        val result = parse("🎂 Birthday party 🎉 friday at 7pm")
        assertTrue(result.title.contains("🎂"))
        assertTrue(result.title.contains("🎉"))
    }

    @Test
    fun `accented characters preserved`() {
        val result = parse("Café meeting tomorrow")
        assertTrue(result.title.contains("Caf"))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `CJK characters in title preserved`() {
        val result = parse("会議 tomorrow at 3pm")
        assertTrue(result.title.contains("会議"))
    }

    @Test
    fun `Cyrillic characters in title preserved`() {
        val result = parse("Встреча tomorrow at noon")
        assertTrue(result.title.contains("Встреча"))
    }

    @Test
    fun `Arabic characters do not crash`() {
        val result = parse("اجتماع tomorrow")
        assertNotNull(result)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `mixed script input parses correctly`() {
        val result = parse("Tokyo 東京 trip friday at 9am")
        assertTrue(result.title.contains("Tokyo"))
        assertTrue(result.title.contains("東京"))
        assertEquals(LocalTime.of(9, 0), result.startTime)
    }

    @Test
    fun `zero-width characters handled gracefully`() {
        // Zero-width space between "to" and "morrow"
        val result = parse("to\u200Bmorrow at 3pm")
        assertNotNull(result)
        // May or may not parse as "tomorrow" depending on normalization
    }

    @Test
    fun `emoji-only input does not crash`() {
        val result = parse("🎵🎶🎤")
        assertNotNull(result)
        assertTrue(result.title.contains("🎵"))
    }

    // ════════════════════════════════════════════════════════════
    //  7. RULE INTERACTION AND ORDERING
    // ════════════════════════════════════════════════════════════

    @Test
    fun `date keyword consumed before weekday rule sees it`() {
        // "today" is DATE_KEYWORD, should be consumed by RelativeDateRule
        // not confused with any weekday logic
        val result = parse("today at 3pm")
        assertEquals(ref.toLocalDate(), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `weekday not confused with month name`() {
        // No weekday name overlaps with month name
        val result = parse("friday january 15 at 3pm")
        // AbsoluteDateRule (Jan 15) has higher priority than weekday
        assertEquals(LocalDate.of(2027, 1, 15), result.startDate)
    }

    @Test
    fun `relative offset does not consume time keyword tokens`() {
        // "noon" is TIME_KEYWORD, not UNIT — RelativeOffsetRule should skip it
        val result = parse("noon tomorrow")
        assertEquals(LocalTime.of(12, 0), result.startTime)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `at keyword consumed only when preceding a time`() {
        // "at" before non-time word should not be consumed
        val result = parse("look at this tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        // "at" and "this" are keywords (filtered), "look" is in title
        assertTrue(result.title.contains("look"))
    }

    @Test
    fun `in keyword not consumed without number + unit`() {
        // "in" alone without "NUMBER UNIT" should not be consumed by RelativeOffsetRule
        val result = parse("meeting in the office tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `of keyword consumed only between number and month`() {
        val result = parse("15th of March at 3pm")
        assertEquals(LocalDate.of(2027, 3, 15), result.startDate)
    }

    @Test
    fun `this modifier treated as next for weekdays`() {
        // "this friday" = "next friday"
        val resultThis = parse("this friday")
        val resultNext = parse("next friday")
        assertEquals(resultThis.startDate, resultNext.startDate)
    }

    @Test
    fun `multiple consumed at keywords do not break title`() {
        // Two "at" keywords — one consumed by time, one filtered
        val result = parse("meet at the café at 3pm tomorrow")
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    // ════════════════════════════════════════════════════════════
    //  8. STRUCTURED DATE EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `ISO date format YYYY-MM-DD`() {
        assertEquals(LocalDate.of(2027, 1, 15), parse("2027-01-15").startDate)
        assertEquals(LocalDate.of(2026, 12, 25), parse("2026-12-25").startDate)
    }

    @Test
    fun `European dot format D_M_Y`() {
        assertEquals(LocalDate.of(2027, 1, 15), parse("15.01.2027").startDate)
        assertEquals(LocalDate.of(2026, 12, 25), parse("25.12.2026").startDate)
    }

    @Test
    fun `D_M_Y detected when first number greater than 12`() {
        assertEquals(LocalDate.of(2027, 1, 15), parse("15/01/2027").startDate)
        assertEquals(LocalDate.of(2026, 12, 25), parse("25/12/2026").startDate)
        assertEquals(LocalDate.of(2027, 1, 15), parse("15-01-2027").startDate)
    }

    @Test
    fun `D_M without year when first number greater than 12`() {
        assertEquals(LocalDate.of(2027, 1, 15), parse("15/01").startDate)
    }

    @Test
    fun `ambiguous 12_11 treated as M_D (US default)`() {
        // 12 <= 12, so M/D applies: month=12, day=11
        assertEquals(LocalDate.of(2026, 12, 11), parse("12/11").startDate)
    }

    @Test
    fun `two digit year resolved correctly`() {
        assertEquals(LocalDate.of(2027, 1, 15), parse("1/15/27").startDate)
        assertEquals(LocalDate.of(1999, 12, 25), parse("12/25/99").startDate)
    }

    @Test
    fun `structured date with invalid month does not set date`() {
        // month > 12 — should fail gracefully
        val result = parse("0/15/2027")
        // month=0 is invalid, resolveFutureDate returns null
        assertEquals(ref.toLocalDate(), result.startDate)
    }

    @Test
    fun `structured date combined with time`() {
        val result = parse("2027-01-15 at 3pm")
        assertEquals(LocalDate.of(2027, 1, 15), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `phone-number-like pattern does not crash`() {
        // "555-12-34" would match structuredDateRegex but produce invalid date
        val result = parse("Call 555-12-34 tomorrow")
        assertNotNull(result)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    // ════════════════════════════════════════════════════════════
    //  9. TIME PARSING EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `12pm is noon not midnight`() {
        assertEquals(LocalTime.of(12, 0), parse("at 12pm").startTime)
    }

    @Test
    fun `12am is midnight not noon`() {
        assertEquals(LocalTime.of(0, 0), parse("at 12am").startTime)
    }

    @Test
    fun `1pm through 11pm resolve correctly`() {
        for (h in 1..11) {
            val result = parse("at ${h}pm")
            assertEquals("${h}pm", LocalTime.of(h + 12, 0), result.startTime)
        }
    }

    @Test
    fun `1am through 11am resolve correctly`() {
        for (h in 1..11) {
            val result = parse("at ${h}am")
            assertEquals("${h}am", LocalTime.of(h, 0), result.startTime)
        }
    }

    @Test
    fun `24-hour times 0 through 23`() {
        for (h in 0..23) {
            val timeStr = "%d:00".format(h)
            val result = parse("at $timeStr")
            assertEquals(timeStr, LocalTime.of(h, 0), result.startTime)
        }
    }

    @Test
    fun `space-separated time with meridiem`() {
        assertEquals(LocalTime.of(14, 30), parse("at 2 30 pm").startTime)
        assertEquals(LocalTime.of(9, 45), parse("at 9 45 am").startTime)
    }

    @Test
    fun `space-separated 24h time with at prefix`() {
        assertEquals(LocalTime.of(10, 15), parse("at 10 15").startTime)
        assertEquals(LocalTime.of(14, 30), parse("at 14 30").startTime)
    }

    @Test
    fun `space-separated time without at does not false-positive`() {
        // "2 30" without "at" prefix and without meridiem should NOT parse as time
        // (would be too aggressive — "Room 2 30 people" shouldn't become 2:30)
        val result = parse("2 30 things")
        assertNull(result.startTime)
    }

    @Test
    fun `minutes with colon preserve full precision`() {
        assertEquals(LocalTime.of(15, 45), parse("at 3:45pm").startTime)
        assertEquals(LocalTime.of(15, 1), parse("at 3:01pm").startTime)
        assertEquals(LocalTime.of(15, 59), parse("at 3:59pm").startTime)
    }

    @Test
    fun `meridiem with dots (a_m_ and p_m_)`() {
        assertEquals(LocalTime.of(15, 0), parse("at 3 p.m.").startTime)
        assertEquals(LocalTime.of(9, 0), parse("at 9 a.m.").startTime)
    }

    // ════════════════════════════════════════════════════════════
    // 10. DETERMINISM AND IDEMPOTENCY
    // ════════════════════════════════════════════════════════════

    @Test
    fun `same input always produces same output`() {
        val inputs = listOf(
            "Coffee tomorrow at 3pm",
            "meeting in 2 hours",
            "friday at noon",
            "15/01/2027",
            "25 december 2026",
        )
        for (input in inputs) {
            val r1 = parse(input)
            val r2 = parse(input)
            assertEquals("$input: non-deterministic date", r1.startDate, r2.startDate)
            assertEquals("$input: non-deterministic time", r1.startTime, r2.startTime)
            assertEquals("$input: non-deterministic title", r1.title, r2.title)
            assertEquals("$input: non-deterministic confidence", r1.confidence, r2.confidence)
        }
    }

    @Test
    fun `different reference times produce different dates for relative inputs`() {
        val morningRef = LocalDateTime.of(2026, 4, 13, 8, 0)
        val eveningRef = LocalDateTime.of(2026, 4, 13, 20, 0)

        val r1 = parse("in 2 hours", morningRef)
        val r2 = parse("in 2 hours", eveningRef)

        assertEquals(LocalTime.of(10, 0), r1.startTime)
        assertEquals(LocalTime.of(22, 0), r2.startTime)
    }

    @Test
    fun `absolute dates are unaffected by reference time`() {
        val r1 = parse("January 15 2027", LocalDateTime.of(2026, 1, 1, 0, 0))
        val r2 = parse("January 15 2027", LocalDateTime.of(2026, 12, 31, 23, 59))
        assertEquals(r1.startDate, r2.startDate)
    }

    // ════════════════════════════════════════════════════════════
    // 11. REAL-WORLD CORPUS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `real-world - gym`() {
        val result = parse("Gym")
        assertEquals("Gym", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `real-world - call mom`() {
        val result = parse("Call mom")
        assertEquals("Call mom", result.title)
    }

    @Test
    fun `real-world - pick up kids at 3 30`() {
        val result = parse("Pick up kids at 3:30")
        assertEquals("Pick up kids", result.title)
        assertEquals(LocalTime.of(3, 30), result.startTime)
    }

    @Test
    fun `real-world - 1 on 1 with manager wednesday`() {
        val result = parse("1:1 with manager wednesday")
        // "1:1" is a time token (01:01). First time wins.
        assertEquals(LocalDate.of(2026, 4, 15), result.startDate)
    }

    @Test
    fun `real-world - haircut 2pm`() {
        val result = parse("Haircut 2pm")
        assertEquals("Haircut", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    @Test
    fun `real-world - PTO next week`() {
        // "next" is KEYWORD(NEXT), "week" is UNIT — no weekday follows
        // RelativeOffsetRule sees "in NUMBER UNIT" — no "in" here
        val result = parse("PTO next week")
        assertEquals("PTO", result.title)
    }

    @Test
    fun `real-world - dr appointment jan 15 2pm`() {
        val result = parse("Dr. appointment Jan 15 at 2pm")
        assertTrue(result.title.contains("Dr"))
        assertTrue(result.title.contains("appointment"))
        assertEquals(LocalDate.of(2027, 1, 15), result.startDate)
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    @Test
    fun `real-world - flight to NYC friday 6am`() {
        val result = parse("Flight to NYC friday at 6am")
        assertTrue(result.title.contains("Flight"))
        assertTrue(result.title.contains("NYC"))
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate)
        assertEquals(LocalTime.of(6, 0), result.startTime)
    }

    @Test
    fun `real-world - birthday party saturday 7pm`() {
        val result = parse("Birthday party saturday 7pm")
        assertTrue(result.title.contains("Birthday"))
        assertTrue(result.title.contains("party"))
        assertEquals(LocalDate.of(2026, 4, 18), result.startDate)
        assertEquals(LocalTime.of(19, 0), result.startTime)
    }

    @Test
    fun `real-world - board meeting Q1 review tomorrow`() {
        val result = parse("Board meeting Q1 review tomorrow")
        assertTrue(result.title.contains("Board"))
        assertTrue(result.title.contains("Q1"))
        assertTrue(result.title.contains("review"))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `real-world - standup in 30 min`() {
        val result = parse("standup in 30 min")
        assertEquals("standup", result.title)
        assertEquals(LocalTime.of(10, 30), result.startTime)
    }

    @Test
    fun `real-world - lunch with team today at noon`() {
        val result = parse("Lunch with team today at noon")
        assertTrue(result.title.contains("Lunch"))
        assertTrue(result.title.contains("team"))
        assertEquals(ref.toLocalDate(), result.startDate)
        assertEquals(LocalTime.of(12, 0), result.startTime)
    }

    @Test
    fun `real-world - date night friday at 7pm`() {
        val result = parse("Date night friday at 7pm")
        assertTrue(result.title.contains("Date"))
        assertTrue(result.title.contains("night"))
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate)
        assertEquals(LocalTime.of(19, 0), result.startTime)
    }

    @Test
    fun `real-world - yoga class at 6am`() {
        val result = parse("Yoga class at 6am")
        assertEquals("Yoga class", result.title)
        assertEquals(LocalTime.of(6, 0), result.startTime)
    }

    @Test
    fun `real-world - dentist dec 25`() {
        val result = parse("Dentist dec 25")
        assertEquals("Dentist", result.title)
        assertEquals(LocalDate.of(2026, 12, 25), result.startDate)
    }

    @Test
    fun `real-world - taxes april 15`() {
        val result = parse("Taxes april 15")
        assertEquals("Taxes", result.title)
        assertEquals(LocalDate.of(2026, 4, 15), result.startDate)
    }

    // ════════════════════════════════════════════════════════════
    // 12. PERFORMANCE UNDER ADVERSARIAL INPUTS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `many tokens do not cause quadratic blowup`() {
        // 200 words — each rule iterates tokens, but should be O(n)
        val input = (1..200).joinToString(" ") { "word$it" } + " tomorrow at 3pm"
        val start = System.nanoTime()
        val result = parse(input)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("Took ${elapsed}ms for 200+ tokens, expected < 100ms", elapsed < 100)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `many structured date candidates do not hang`() {
        // Multiple items that look like structured dates
        val input = (1..50).joinToString(" ") { "1/$it" } + " at 3pm"
        val start = System.nanoTime()
        val result = parse(input)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("Took ${elapsed}ms, expected < 100ms", elapsed < 100)
        assertNotNull(result)
    }

    @Test
    fun `repeated keywords do not cause exponential matching`() {
        val input = "at ".repeat(100) + "3pm tomorrow"
        val start = System.nanoTime()
        val result = parse(input)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("Took ${elapsed}ms, expected < 100ms", elapsed < 100)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    // ════════════════════════════════════════════════════════════
    // 13. CONFIDENCE SCORING
    // ════════════════════════════════════════════════════════════

    @Test
    fun `date and time gives HIGH confidence`() {
        assertEquals(ParseConfidence.HIGH, parse("tomorrow at 3pm").confidence)
        assertEquals(ParseConfidence.HIGH, parse("in 30 minutes").confidence) // sets both date + time
        assertEquals(ParseConfidence.HIGH, parse("friday at noon").confidence)
    }

    @Test
    fun `date only gives MEDIUM confidence`() {
        assertEquals(ParseConfidence.MEDIUM, parse("tomorrow").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("friday").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("january 15").confidence)
    }

    @Test
    fun `time only gives MEDIUM confidence`() {
        assertEquals(ParseConfidence.MEDIUM, parse("meeting at 3pm").confidence)
        assertEquals(ParseConfidence.MEDIUM, parse("at noon").confidence)
    }

    @Test
    fun `no date or time gives LOW confidence`() {
        assertEquals(ParseConfidence.LOW, parse("meeting").confidence)
        assertEquals(ParseConfidence.LOW, parse("hello world").confidence)
        assertEquals(ParseConfidence.LOW, parse("").confidence)
    }

    // ════════════════════════════════════════════════════════════
    // 14. ISALLDAY CORRECTNESS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `date without time is all-day`() {
        assertTrue(parse("tomorrow").isAllDay)
        assertTrue(parse("friday").isAllDay)
        assertTrue(parse("january 15").isAllDay)
    }

    @Test
    fun `date with time is not all-day`() {
        assertEquals(false, parse("tomorrow at 3pm").isAllDay)
        assertEquals(false, parse("in 30 minutes").isAllDay)
    }

    @Test
    fun `no date and no time is all-day`() {
        assertTrue(parse("meeting with Bob").isAllDay)
    }

    // ════════════════════════════════════════════════════════════
    // 15. WEEKDAY EXHAUSTIVE TESTS (from Monday reference)
    // ════════════════════════════════════════════════════════════

    @Test
    fun `bare weekday from Monday reference — all 7 days`() {
        // Reference: Monday April 13, 2026
        assertEquals(LocalDate.of(2026, 4, 20), parse("monday").startDate)   // same day → +7
        assertEquals(LocalDate.of(2026, 4, 14), parse("tuesday").startDate)  // +1
        assertEquals(LocalDate.of(2026, 4, 15), parse("wednesday").startDate) // +2
        assertEquals(LocalDate.of(2026, 4, 16), parse("thursday").startDate) // +3
        assertEquals(LocalDate.of(2026, 4, 17), parse("friday").startDate)   // +4
        assertEquals(LocalDate.of(2026, 4, 18), parse("saturday").startDate) // +5
        assertEquals(LocalDate.of(2026, 4, 19), parse("sunday").startDate)   // +6
    }

    @Test
    fun `last weekday from Monday reference — all 7 days`() {
        // Reference: Monday April 13, 2026
        assertEquals(LocalDate.of(2026, 4, 6), parse("last monday").startDate)    // same day → -7
        assertEquals(LocalDate.of(2026, 4, 7), parse("last tuesday").startDate)   // -6
        assertEquals(LocalDate.of(2026, 4, 8), parse("last wednesday").startDate) // -5
        assertEquals(LocalDate.of(2026, 4, 9), parse("last thursday").startDate)  // -4
        assertEquals(LocalDate.of(2026, 4, 10), parse("last friday").startDate)   // -3
        assertEquals(LocalDate.of(2026, 4, 11), parse("last saturday").startDate) // -2
        assertEquals(LocalDate.of(2026, 4, 12), parse("last sunday").startDate)   // -1
    }

    @Test
    fun `bare weekday from different reference days`() {
        // From Wednesday April 15
        val wedRef = LocalDateTime.of(2026, 4, 15, 10, 0)
        assertEquals(LocalDate.of(2026, 4, 20), parse("monday", wedRef).startDate)   // +5
        assertEquals(LocalDate.of(2026, 4, 22), parse("wednesday", wedRef).startDate) // same → +7
        assertEquals(LocalDate.of(2026, 4, 17), parse("friday", wedRef).startDate)    // +2
        assertEquals(LocalDate.of(2026, 4, 19), parse("sunday", wedRef).startDate)    // +4

        // From Sunday April 19
        val sunRef = LocalDateTime.of(2026, 4, 19, 10, 0)
        assertEquals(LocalDate.of(2026, 4, 20), parse("monday", sunRef).startDate)    // +1
        assertEquals(LocalDate.of(2026, 4, 24), parse("friday", sunRef).startDate)    // +5
        assertEquals(LocalDate.of(2026, 4, 26), parse("sunday", sunRef).startDate)    // same → +7
    }

    // ════════════════════════════════════════════════════════════
    // 16. YEAR BOUNDARY AND CALENDAR EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `tomorrow at year boundary (Dec 31)`() {
        val decRef = LocalDateTime.of(2026, 12, 31, 10, 0)
        assertEquals(LocalDate.of(2027, 1, 1), parse("tomorrow", decRef).startDate)
    }

    @Test
    fun `future-biased month resolution wraps year`() {
        // From December, "January 5" should resolve to next year
        val decRef = LocalDateTime.of(2026, 12, 30, 10, 0)
        assertEquals(LocalDate.of(2027, 1, 5), parse("January 5", decRef).startDate)
    }

    @Test
    fun `future-biased month resolution stays in current year when date is ahead`() {
        // From April, "December 25" should resolve to current year
        assertEquals(LocalDate.of(2026, 12, 25), parse("December 25").startDate)
    }

    @Test
    fun `future-biased month resolution for same month later day`() {
        // From April 13, "April 20" should be April 20 current year
        assertEquals(LocalDate.of(2026, 4, 20), parse("April 20").startDate)
    }

    @Test
    fun `future-biased month resolution for same month earlier day wraps`() {
        // From April 13, "April 5" is past → wraps to April 5 next year
        assertEquals(LocalDate.of(2027, 4, 5), parse("April 5").startDate)
    }

    @Test
    fun `leap year Feb 29 in various years`() {
        // 2028 is a leap year
        assertEquals(LocalDate.of(2028, 2, 29), parse("February 29 2028").startDate)
        // 2027 is not — should fail gracefully
        assertEquals(ref.toLocalDate(), parse("February 29 2027").startDate)
        // 2100 is not a leap year (divisible by 100 but not 400)
        assertEquals(ref.toLocalDate(), parse("February 29 2100").startDate)
        // 2000 was a leap year (divisible by 400)
        assertEquals(LocalDate.of(2000, 2, 29), parse("February 29 2000").startDate)
    }

    // ════════════════════════════════════════════════════════════
    // 17. NORMALIZER EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `number words in offsets`() {
        assertEquals(LocalTime.of(10, 15), parse("in fifteen minutes").startTime)
        assertEquals(LocalTime.of(10, 45), parse("in forty-five minutes").startTime)
        assertEquals(LocalTime.of(12, 0), parse("in two hours").startTime)
    }

    @Test
    fun `a and an normalize to 1`() {
        assertEquals(LocalTime.of(11, 0), parse("in an hour").startTime)
        assertEquals(LocalDate.of(2026, 4, 20), parse("in a week").startDate)
    }

    @Test
    fun `multi-word expressions normalized`() {
        assertEquals(LocalDate.of(2026, 4, 15), parse("day after tomorrow").startDate)
        assertEquals(LocalDate.of(2026, 4, 11), parse("day before yesterday").startDate)
    }

    @Test
    fun `number word case insensitive`() {
        assertEquals(LocalTime.of(10, 15), parse("in FIFTEEN minutes").startTime)
        assertEquals(LocalTime.of(12, 0), parse("in TWO hours").startTime)
    }

    // ════════════════════════════════════════════════════════════
    // 18. ABBREVIATION EXHAUSTIVE COVERAGE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `all weekday abbreviations resolve`() {
        assertNotNull(parse("mon").startDate)
        assertNotNull(parse("tue").startDate)
        assertNotNull(parse("tues").startDate)
        assertNotNull(parse("wed").startDate)
        assertNotNull(parse("thu").startDate)
        assertNotNull(parse("thur").startDate)
        assertNotNull(parse("thurs").startDate)
        assertNotNull(parse("fri").startDate)
        assertNotNull(parse("sat").startDate)
        assertNotNull(parse("sun").startDate)
    }

    @Test
    fun `all month abbreviations resolve`() {
        val monthAbbrevs = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec")
        for (abbrev in monthAbbrevs) {
            val result = parse("$abbrev 15")
            assertTrue("$abbrev 15 should set date", result.startDate != ref.toLocalDate() || abbrev == "apr")
        }
    }

    @Test
    fun `all tomorrow abbreviations resolve`() {
        val tmrAbbrevs = listOf("tomorrow", "tmr", "tmrw", "2morrow", "tomorow", "tommorow")
        for (abbrev in tmrAbbrevs) {
            assertEquals(
                "$abbrev should resolve to tomorrow",
                LocalDate.of(2026, 4, 14),
                parse(abbrev).startDate
            )
        }
    }

    @Test
    fun `all yesterday abbreviations resolve`() {
        listOf("yesterday", "yday").forEach { abbrev ->
            assertEquals(
                "$abbrev should resolve to yesterday",
                LocalDate.of(2026, 4, 12),
                parse(abbrev).startDate
            )
        }
    }

    @Test
    fun `all today abbreviations resolve`() {
        listOf("today", "tdy", "2day").forEach { abbrev ->
            assertEquals(
                "$abbrev should resolve to today",
                ref.toLocalDate(),
                parse(abbrev).startDate
            )
        }
    }
}
