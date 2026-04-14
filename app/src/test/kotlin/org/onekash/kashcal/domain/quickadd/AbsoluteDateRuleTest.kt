package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.AbsoluteDateRule
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime

class AbsoluteDateRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    // Reference near year end: December 30, 2026
    private val yearEnd = LocalDateTime.of(2026, 12, 30, 10, 0)

    private fun parse(input: String, ref: LocalDateTime = reference): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(ref)
        AbsoluteDateRule.apply(tokens, context)
        return context
    }

    // ==================== Month + Day ====================

    @Test
    fun `january 15 resolves to future Jan 15`() {
        val ctx = parse("january 15")
        // April 2026 → Jan 15 is past → Jan 15, 2027
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `may 1 resolves to current year when in future`() {
        val ctx = parse("may 1")
        // April 13, 2026 → May 1 is future → May 1, 2026
        assertEquals(LocalDate.of(2026, 5, 1), ctx.resolveDate())
    }

    @Test
    fun `march 10 resolves to next year when past`() {
        val ctx = parse("march 10")
        // April 13, 2026 → March 10 is past → March 10, 2027
        assertEquals(LocalDate.of(2027, 3, 10), ctx.resolveDate())
    }

    // ==================== Month + Day + Year ====================

    @Test
    fun `jan 15 2027 resolves to exact date`() {
        val ctx = parse("jan 15 2027")
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `december 25 2026 resolves to exact date`() {
        val ctx = parse("december 25 2026")
        assertEquals(LocalDate.of(2026, 12, 25), ctx.resolveDate())
    }

    // ==================== Ordinal format ====================

    @Test
    fun `15th of march resolves correctly`() {
        val ctx = parse("15th of march")
        // March 15 is past from April 13 → March 15, 2027
        assertEquals(LocalDate.of(2027, 3, 15), ctx.resolveDate())
    }

    @Test
    fun `21st of march 2027 resolves correctly`() {
        val ctx = parse("21st of march 2027")
        assertEquals(LocalDate.of(2027, 3, 21), ctx.resolveDate())
    }

    @Test
    fun `1st of may resolves correctly`() {
        val ctx = parse("1st of may")
        assertEquals(LocalDate.of(2026, 5, 1), ctx.resolveDate())
    }

    @Test
    fun `2nd of june resolves correctly`() {
        val ctx = parse("2nd of june")
        assertEquals(LocalDate.of(2026, 6, 2), ctx.resolveDate())
    }

    @Test
    fun `3rd of july resolves correctly`() {
        val ctx = parse("3rd of july")
        assertEquals(LocalDate.of(2026, 7, 3), ctx.resolveDate())
    }

    // ==================== Abbreviated months ====================

    @Test
    fun `jan abbreviation resolves correctly`() {
        val ctx = parse("jan 15")
        assertEquals(LocalDate.of(2027, 1, 15), ctx.resolveDate())
    }

    @Test
    fun `feb abbreviation resolves correctly`() {
        val ctx = parse("feb 14")
        assertEquals(LocalDate.of(2027, 2, 14), ctx.resolveDate())
    }

    @Test
    fun `sept abbreviation resolves correctly`() {
        val ctx = parse("sept 1")
        assertEquals(LocalDate.of(2026, 9, 1), ctx.resolveDate())
    }

    @Test
    fun `dec abbreviation resolves correctly`() {
        val ctx = parse("dec 31")
        assertEquals(LocalDate.of(2026, 12, 31), ctx.resolveDate())
    }

    // ==================== Year boundary ====================

    @Test
    fun `january 5 on december 30 resolves to next year`() {
        val ctx = parse("january 5", yearEnd)
        assertEquals(LocalDate.of(2027, 1, 5), ctx.resolveDate())
    }

    // ==================== Edge cases ====================

    @Test
    fun `february 29 2028 (leap year) resolves correctly`() {
        val ctx = parse("february 29 2028")
        assertEquals(LocalDate.of(2028, 2, 29), ctx.resolveDate())
    }

    @Test
    fun `february 29 2027 (not leap year) falls back to reference date`() {
        val ctx = parse("february 29 2027")
        // Invalid date → date not set → falls back to reference
        assertEquals(reference.toLocalDate(), ctx.resolveDate())
    }

    @Test
    fun `february 30 falls back to reference date`() {
        val ctx = parse("february 30")
        assertEquals(reference.toLocalDate(), ctx.resolveDate())
    }
}
