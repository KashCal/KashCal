package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
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

    // ==================== Timezone recognition ====================

    @Test
    fun `3pm EST sets timezone to America New York`() {
        val ctx = parse("meeting at 3pm est")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
        assertEquals("America/New_York", ctx.timezone)
    }

    @Test
    fun `10am PST sets timezone to America Los Angeles`() {
        val ctx = parse("call 10am pst")
        assertEquals(LocalTime.of(10, 0), ctx.resolveTime())
        assertEquals("America/Los_Angeles", ctx.timezone)
    }

    @Test
    fun `9am UTC sets timezone to UTC`() {
        val ctx = parse("standup 9am utc")
        assertEquals(LocalTime.of(9, 0), ctx.resolveTime())
        assertEquals("UTC", ctx.timezone)
    }

    @Test
    fun `3pm without timezone has null timezone`() {
        val ctx = parse("meeting at 3pm")
        assertEquals(LocalTime.of(15, 0), ctx.resolveTime())
        assertNull(ctx.timezone)
    }

    @Test
    fun `timezone consumed from tokens`() {
        val normalized = NormalizerChain().normalize("meeting 3pm est")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        TimeRule.apply(tokens, context)
        // "est" should be consumed (index 2)
        assert(context.isConsumed(2))
    }
}
