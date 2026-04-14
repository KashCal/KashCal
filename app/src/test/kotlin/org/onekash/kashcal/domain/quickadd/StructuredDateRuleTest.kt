package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.StructuredDateRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime

class StructuredDateRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        StructuredDateRule.apply(tokens, context)
        return context
    }

    // ==================== M/D (US format) ====================

    @Test
    fun `1 slash 15 resolves to January 15`() {
        val ctx = parse("1/15")
        // Jan 15 is past from April 13, 2026 → Jan 15, 2027
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `5 slash 1 resolves to May 1 current year`() {
        val ctx = parse("5/1")
        // May 1 is future from April 13 → May 1, 2026
        assertEquals(LocalDate.of(2026, 5, 1), ctx.resolveDate())
    }

    // ==================== M/D/Y (US format with year) ====================

    @Test
    fun `01 slash 15 slash 2027 resolves to January 15 2027`() {
        val ctx = parse("01/15/2027")
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `12 slash 25 slash 2026 resolves to December 25 2026`() {
        val ctx = parse("12/25/2026")
        assertEquals(LocalDate.of(2026, 12, 25), ctx.resolveDate())
    }

    // ==================== ISO Y-M-D ====================

    @Test
    fun `2027-01-15 resolves to January 15 2027`() {
        val ctx = parse("2027-01-15")
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `2026-12-25 resolves to December 25 2026`() {
        val ctx = parse("2026-12-25")
        assertEquals(LocalDate.of(2026, 12, 25), ctx.resolveDate())
    }

    // ==================== European D.M.Y ====================

    @Test
    fun `15 dot 01 dot 2027 resolves to January 15 2027`() {
        val ctx = parse("15.01.2027")
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `25 dot 12 dot 2026 resolves to December 25 2026`() {
        val ctx = parse("25.12.2026")
        assertEquals(LocalDate.of(2026, 12, 25), ctx.resolveDate())
    }
}
