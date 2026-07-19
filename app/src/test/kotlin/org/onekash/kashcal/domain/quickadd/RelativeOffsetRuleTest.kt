package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.RelativeOffsetRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RelativeOffsetRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RelativeOffsetRule.apply(tokens, context)
        return context
    }

    // ==================== "in X minutes/hours" ====================

    @Test
    fun `in 30 minutes resolves to 10 colon 30`() {
        val ctx = parse("in 30 minutes")
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
        assertEquals(LocalTime.of(10, 30), ctx.resolveTime())
    }

    @Test
    fun `in 2 hours resolves to 12 colon 00`() {
        val ctx = parse("in 2 hours")
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
        assertEquals(LocalTime.of(12, 0), ctx.resolveTime())
    }

    @Test
    fun `in fifteen minutes resolves to 10 colon 15`() {
        val ctx = parse("in fifteen minutes")
        assertEquals(LocalTime.of(10, 15), ctx.resolveTime())
    }

    // ==================== "in X days/weeks/months/years" ====================

    @Test
    fun `in 3 days resolves to April 16`() {
        val ctx = parse("in 3 days")
        assertEquals(LocalDate.of(2026, 4, 16), ctx.resolveDate())
    }

    @Test
    fun `in 2 weeks resolves to April 27`() {
        val ctx = parse("in 2 weeks")
        assertEquals(LocalDate.of(2026, 4, 27), ctx.resolveDate())
    }

    @Test
    fun `in 1 month resolves to May 13`() {
        val ctx = parse("in 1 month")
        assertEquals(LocalDate.of(2026, 5, 13), ctx.resolveDate())
    }

    @Test
    fun `in 1 year resolves to April 13 2027`() {
        val ctx = parse("in 1 year")
        assertEquals(LocalDate.of(2027, 4, 13), ctx.resolveDate())
    }

    // ==================== "X ago" ====================

    @Test
    fun `3 days ago resolves to April 10`() {
        val ctx = parse("3 days ago")
        assertEquals(LocalDate.of(2026, 4, 10), ctx.resolveDate())
    }

    @Test
    fun `2 hours ago resolves to 08 colon 00`() {
        val ctx = parse("2 hours ago")
        assertEquals(LocalTime.of(8, 0), ctx.resolveTime())
    }

    @Test
    fun `1 week ago resolves to April 6`() {
        val ctx = parse("1 week ago")
        assertEquals(LocalDate.of(2026, 4, 6), ctx.resolveDate())
    }

    // ==================== Number words ====================

    @Test
    fun `in forty-five minutes resolves correctly`() {
        val ctx = parse("in forty-five minutes")
        assertEquals(LocalTime.of(10, 45), ctx.resolveTime())
    }

    // ==================== "from now" / "later" (forward offset) ====================

    @Test
    fun `3 days from now resolves like in 3 days`() {
        val ctx = parse("3 days from now")
        assertEquals(LocalDate.of(2026, 4, 16), ctx.resolveDate())
    }

    @Test
    fun `5 minutes later resolves forward`() {
        val ctx = parse("5 minutes later")
        assertEquals(LocalTime.of(10, 5), ctx.resolveTime())
    }

    @Test
    fun `2 hours from now resolves forward`() {
        val ctx = parse("2 hours from now")
        assertEquals(LocalTime.of(12, 0), ctx.resolveTime())
    }
}
