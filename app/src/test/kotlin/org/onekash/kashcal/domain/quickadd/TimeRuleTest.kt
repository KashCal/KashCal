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
}
