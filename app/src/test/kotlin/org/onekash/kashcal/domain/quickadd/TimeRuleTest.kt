package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.TimeRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDateTime
import java.time.LocalTime

class TimeRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        TimeRule.apply(tokens, context)
        return context
    }

    // ==================== AM/PM formats ====================

    @Test
    fun `3pm resolves to 15 colon 00`() {
        val ctx = parse("3pm")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
    }

    @Test
    fun `3 pm with space resolves to 15 colon 00`() {
        // After normalization "3 pm" → two tokens: "3" (NUMBER) and "pm" (MERIDIEM)
        val ctx = parse("3 pm")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
    }

    @Test
    fun `3PM uppercase resolves to 15 colon 00`() {
        val ctx = parse("3PM")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
    }

    @Test
    fun `9am resolves to 09 colon 00`() {
        val ctx = parse("9am")
        assertEquals(LocalTime.of(9, 0), ctx.resolveTime())
    }

    // ==================== Colon format with AM/PM ====================

    @Test
    fun `at 3 colon 30 PM resolves to 15 colon 30`() {
        val ctx = parse("at 3:30 PM")
        assertEquals(LocalTime.of(15, 30), ctx.resolveTime())
    }

    @Test
    fun `10 colon 30am resolves to 10 colon 30`() {
        val ctx = parse("10:30am")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
    }

    // ==================== 24-hour format ====================

    @Test
    fun `15 colon 00 resolves to 15 colon 00`() {
        val ctx = parse("15:00")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
    }

    @Test
    fun `at 10 colon 30 without meridiem resolves to 10 colon 30`() {
        val ctx = parse("at 10:30")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
    }

    @Test
    fun `0 colon 00 resolves to midnight`() {
        val ctx = parse("0:00")
        assertEquals(LocalTime.of(0, 0), ctx.resolveTime())
    }

    // ==================== Time keywords ====================

    @Test
    fun `noon resolves to 12 colon 00`() {
        val ctx = parse("noon")
        assertEquals(LocalTime.of(12, 0), ctx.resolveTime())
    }

    @Test
    fun `midnight resolves to 00 colon 00`() {
        val ctx = parse("midnight")
        assertEquals(LocalTime.of(0, 0), ctx.resolveTime())
    }

    // ==================== 12-hour edge cases ====================

    @Test
    fun `12pm resolves to noon (12 colon 00)`() {
        val ctx = parse("12pm")
        assertEquals(LocalTime.of(12, 0), ctx.resolveTime())
    }

    @Test
    fun `12am resolves to midnight (00 colon 00)`() {
        val ctx = parse("12am")
        assertEquals(LocalTime.of(0, 0), ctx.resolveTime())
    }

    // ==================== Time ranges (hyphenated) ====================

    @Test
    fun `2-3pm resolves to startTime 14 colon 00 endTime 15 colon 00`() {
        val ctx = parse("2-3pm")
        assertEquals(LocalTime.of(14, 0), ctx.resolveTime())
        assertEquals(LocalTime.of(15, 0), ctx.endTime)
    }

    @Test
    fun `10 colon 30-11 colon 30am resolves correctly`() {
        val ctx = parse("10:30-11:30am")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
        assertEquals(LocalTime.of(11, 30), ctx.endTime)
    }

    @Test
    fun `11pm-1am cross-midnight resolves correctly`() {
        val ctx = parse("11pm-1am")
        assertEquals(LocalTime.of(23, 0), ctx.resolveTime())
        assertEquals(LocalTime.of(1, 0), ctx.endTime)
    }

    // ==================== Time ranges (TIME to TIME) ====================

    @Test
    fun `2pm to 4pm resolves both start and end time`() {
        val ctx = parse("2pm to 4pm")
        assertEquals(LocalTime.of(14, 0), ctx.resolveTime())
        assertEquals(LocalTime.of(16, 0), ctx.endTime)
    }

    @Test
    fun `10 colon 30am to 11 colon 30am resolves correctly`() {
        val ctx = parse("10:30am to 11:30am")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
        assertEquals(LocalTime.of(11, 30), ctx.endTime)
    }

    // ==================== Dot-separated time with space ====================

    @Test
    fun `3 dot 15 pm resolves to 15 colon 15`() {
        val ctx = parse("3.15 pm")
        assertEquals(LocalTime.of(15, 15), ctx.resolveTime())
    }

    @Test
    fun `10 dot 30 am resolves to 10 colon 30`() {
        val ctx = parse("10.30 am")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
    }

    @Test
    fun `3 dot 12 pm resolves to 15 colon 12 not Dec 3 date`() {
        val ctx = parse("3.12 pm")
        assertEquals(LocalTime.of(15, 12), ctx.resolveTime())
    }

    @Test
    fun `12 dot 30 pm resolves to 12 colon 30`() {
        val ctx = parse("12.30 pm")
        assertEquals(LocalTime.of(12, 30), ctx.resolveTime())
    }

    // ==================== Quarter/half time expressions ====================

    @Test
    fun `quarter past 3 resolves to 03 colon 15`() {
        val ctx = parse("quarter past 3")
        assertEquals(LocalTime.of(3, 15), ctx.resolveTime())
    }

    @Test
    fun `half past 10 resolves to 10 colon 30`() {
        val ctx = parse("half past 10")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
    }

    @Test
    fun `quarter to 4 resolves to 03 colon 45`() {
        val ctx = parse("quarter to 4")
        assertEquals(LocalTime.of(3, 45), ctx.resolveTime())
    }

    @Test
    fun `quarter past 3 pm resolves to 15 colon 15`() {
        val ctx = parse("quarter past 3 pm")
        assertEquals(LocalTime.of(15, 15), ctx.resolveTime())
    }

    @Test
    fun `half past 10 am resolves to 10 colon 30`() {
        val ctx = parse("half past 10 am")
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
    }

    @Test
    fun `quarter to 1 pm resolves to 12 colon 45`() {
        val ctx = parse("quarter to 1 pm")
        assertEquals(LocalTime.of(12, 45), ctx.resolveTime())
    }

    @Test
    fun `quarter to 12 resolves to 11 colon 45`() {
        val ctx = parse("quarter to 12")
        assertEquals(LocalTime.of(11, 45), ctx.resolveTime())
    }

    @Test
    fun `a quarter past 3 resolves to 03 colon 15`() {
        val ctx = parse("a quarter past 3")
        assertEquals(LocalTime.of(3, 15), ctx.resolveTime())
    }

    // ==================== Fuzzy time keywords ====================

    @Test
    fun `morning resolves to 08 colon 00`() {
        val ctx = parse("morning")
        assertEquals(LocalTime.of(8, 0), ctx.resolveTime())
    }

    @Test
    fun `afternoon resolves to 14 colon 00`() {
        val ctx = parse("afternoon")
        assertEquals(LocalTime.of(14, 0), ctx.resolveTime())
    }

    @Test
    fun `evening resolves to 18 colon 00`() {
        val ctx = parse("evening")
        assertEquals(LocalTime.of(18, 0), ctx.resolveTime())
    }

    @Test
    fun `night resolves to 20 colon 00`() {
        val ctx = parse("night")
        assertEquals(LocalTime.of(20, 0), ctx.resolveTime())
    }

    @Test
    fun `tonight resolves to 20 colon 00`() {
        val ctx = parse("tonight")
        assertEquals(LocalTime.of(20, 0), ctx.resolveTime())
    }

    @Test
    fun `at night resolves to 20 colon 00 with at consumed`() {
        val ctx = parse("at night")
        assertEquals(LocalTime.of(20, 0), ctx.resolveTime())
    }

    @Test
    fun `noon still works after fuzzy additions`() {
        val ctx = parse("noon")
        assertEquals(LocalTime.of(12, 0), ctx.resolveTime())
    }

    @Test
    fun `midnight still works after fuzzy additions`() {
        val ctx = parse("midnight")
        assertEquals(LocalTime.of(0, 0), ctx.resolveTime())
    }

    // ==================== No time ====================

    @Test
    fun `no time component returns null`() {
        val ctx = parse("tomorrow")
        assertNull(ctx.resolveTime())
    }

    @Test
    fun `plain text returns null time`() {
        val ctx = parse("coffee with sarah")
        assertNull(ctx.resolveTime())
    }

    // ==================== Zone abbreviations ====================

    @Test
    fun `a zone abbreviation after a time is not read`() {
        for (input in listOf("meeting at 3pm est", "call 10am pst", "standup 9am utc")) {
            val tokens = WordTokenizer.tokenize(NormalizerChain().normalize(input))
            val context = ParseContext(reference)
            TimeRule.apply(tokens, context)
            // The zone word is the last token and stays unconsumed.
            assertFalse(input, context.isConsumed(tokens.lastIndex))
        }
        assertEquals(LocalTime.of(10, 0), parse("call 10am pst").resolveTime())
    }

    // ==================== Compact 24-hour "at HHMM" ====================

    @Test
    fun `at 1500 resolves to 15 colon 00`() {
        // "1500" tokenizes as YEAR; the "at" lead-in reinterprets it as a clock time.
        val ctx = parse("at 1500")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
    }

    @Test
    fun `at 0930 resolves to 09 colon 30`() {
        // "0930" tokenizes as NUMBER(930); the 4-char text must be read for HHMM.
        val ctx = parse("at 0930")
        assertEquals(LocalTime.of(9, 30), ctx.resolveTime())
    }

    @Test
    fun `at 2130 resolves to 21 colon 30`() {
        val ctx = parse("at 2130")
        assertEquals(LocalTime.of(21, 30), ctx.resolveTime())
    }

    @Test
    fun `bare 1500 without at is not a time`() {
        // Without the "at" lead-in, a 4-digit year must not be read as a clock time.
        val ctx = parse("1500")
        assertNull(ctx.resolveTime())
    }

    @Test
    fun `at 2500 invalid clock is not a time`() {
        val ctx = parse("at 2500")
        assertNull(ctx.resolveTime())
    }

    @Test
    fun `this 1500 is not a compact time - only at triggers it`() {
        // A 4-digit run is far more often a year; only an explicit "at" lead-in
        // reinterprets it as a clock time, so "this 1500" must not become 15:00.
        val ctx = parse("this 1500")
        assertNull(ctx.resolveTime())
    }

    // ==================== Part-of-day extras ====================

    @Test
    fun `after work resolves to 18 colon 00`() {
        val ctx = parse("gym after work")
        assertEquals(LocalTime.of(18, 0), ctx.resolveTime())
    }

    @Test
    fun `lunchtime resolves to noon`() {
        val ctx = parse("meet at lunchtime")
        assertEquals(LocalTime.of(12, 0), ctx.resolveTime())
    }

    @Test
    fun `a bare at-hour resolves as typed`() {
        assertEquals(LocalTime.of(8, 0), parse("dinner at 8").resolveTime())
        assertEquals(LocalTime.of(15, 0), parse("call at 15").resolveTime())
    }

    @Test
    fun `an anchored day-half word adds 12 hours to a bare hour`() {
        assertEquals(LocalTime.of(21, 0), parse("this evening at 9").resolveTime())
        assertEquals(LocalTime.of(20, 0), parse("at 8 tonight").resolveTime())
        assertEquals(LocalTime.of(22, 0), parse("at 10 at night").resolveTime())
    }

    @Test
    fun `an h-notation token after for is not a time of day`() {
        assertEquals(null, parse("gym for 2h").resolveTime())
        assertEquals(LocalTime.of(11, 0), parse("at 11 for 2h").resolveTime())
        assertEquals(LocalTime.of(9, 0), parse("at 9 2h").resolveTime())
    }
}
