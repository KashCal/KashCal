package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.RelativeDateRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime

class RelativeDateRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RelativeDateRule.apply(tokens, context)
        return context
    }

    @Test
    fun `tomorrow resolves to reference plus 1 day`() {
        val ctx = parse("tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), ctx.resolveDate())
    }

    @Test
    fun `today resolves to reference date`() {
        val ctx = parse("today")
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `yesterday resolves to reference minus 1 day`() {
        val ctx = parse("yesterday")
        assertEquals(LocalDate.of(2026, 4, 12), ctx.resolveDate())
    }

    @Test
    fun `day after tomorrow resolves to reference plus 2 days`() {
        val ctx = parse("day after tomorrow")
        assertEquals(LocalDate.of(2026, 4, 15), ctx.resolveDate())
    }

    @Test
    fun `day before yesterday resolves to reference minus 2 days`() {
        val ctx = parse("day before yesterday")
        assertEquals(LocalDate.of(2026, 4, 11), ctx.resolveDate())
    }

    // ==================== Abbreviations ====================

    @Test
    fun `tmr resolves to tomorrow`() {
        val ctx = parse("tmr")
        assertEquals(LocalDate.of(2026, 4, 14), ctx.resolveDate())
    }

    @Test
    fun `tmrw resolves to tomorrow`() {
        val ctx = parse("tmrw")
        assertEquals(LocalDate.of(2026, 4, 14), ctx.resolveDate())
    }

    @Test
    fun `2day resolves to today`() {
        val ctx = parse("2day")
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `tdy resolves to today`() {
        val ctx = parse("tdy")
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `yday resolves to yesterday`() {
        val ctx = parse("yday")
        assertEquals(LocalDate.of(2026, 4, 12), ctx.resolveDate())
    }

    // ==================== Consumed Indices ====================

    @Test
    fun `consumed indices marks the date keyword token`() {
        val normalized = NormalizerChain().normalize("tomorrow")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RelativeDateRule.apply(tokens, context)
        assertTrue(context.isConsumed(0))
    }

    @Test
    fun `no date component defaults to reference date`() {
        val ctx = parse("coffee with sarah")
        assertEquals(reference.toLocalDate(), ctx.resolveDate())
    }

    // ==================== Weekend ====================

    @Test
    fun `this weekend resolves to coming Saturday`() {
        // Reference Monday April 13, 2026 → coming Saturday is April 18.
        val ctx = parse("this weekend")
        assertEquals(LocalDate.of(2026, 4, 18), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `bare weekend resolves to coming Saturday`() {
        val ctx = parse("weekend")
        assertEquals(LocalDate.of(2026, 4, 18), ctx.resolveDate())
    }

    @Test
    fun `on the weekend resolves to coming Saturday`() {
        val ctx = parse("on the weekend")
        assertEquals(LocalDate.of(2026, 4, 18), ctx.resolveDate())
    }

    @Test
    fun `next weekend resolves to Saturday after this weekend`() {
        val ctx = parse("next weekend")
        assertEquals(LocalDate.of(2026, 4, 25), ctx.resolveDate())
    }

    @Test
    fun `weekend on Saturday reference resolves to same day`() {
        val satRef = LocalDateTime.of(2026, 4, 18, 10, 0) // Saturday
        val normalized = NormalizerChain().normalize("this weekend")
        val tokens = WordTokenizer.tokenize(normalized)
        val ctx = ParseContext(satRef)
        RelativeDateRule.apply(tokens, ctx)
        assertEquals(LocalDate.of(2026, 4, 18), ctx.resolveDate())
    }

    @Test
    fun `weekend on Sunday reference rolls forward to next Saturday`() {
        val sunRef = LocalDateTime.of(2026, 4, 19, 10, 0) // Sunday
        val normalized = NormalizerChain().normalize("this weekend")
        val tokens = WordTokenizer.tokenize(normalized)
        val ctx = ParseContext(sunRef)
        RelativeDateRule.apply(tokens, ctx)
        // Never go backward to yesterday's Saturday; roll forward to April 25.
        assertEquals(LocalDate.of(2026, 4, 25), ctx.resolveDate())
    }
}
