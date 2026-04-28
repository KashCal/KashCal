package org.onekash.kashcal.domain.quickadd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.StructuredDateRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class StructuredDateRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

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

    private fun parse(input: String, locale: Locale = Locale.US): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized, locale = locale)
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

    // ==================== Locale-aware ambiguous slash dates (issue #194) ====================

    @Test
    fun `5 slash 10 under en_US locale resolves to May 10 MDY`() {
        val ctx = parse("5/10", locale = Locale.US)
        assertEquals(LocalDate.of(2026, 5, 10), ctx.resolveDate())
    }

    @Test
    fun `5 slash 10 under en_GB locale resolves to October 5 DMY`() {
        val ctx = parse("5/10", locale = Locale.UK)
        assertEquals(LocalDate.of(2026, 10, 5), ctx.resolveDate())
    }

    @Test
    fun `5 slash 10 slash 2026 under en_GB resolves to October 5 2026 DMY`() {
        val ctx = parse("5/10/2026", locale = Locale.UK)
        assertEquals(LocalDate.of(2026, 10, 5), ctx.resolveDate())
    }

    @Test
    fun `5 slash 10 slash 2026 under en_US resolves to May 10 2026 MDY`() {
        val ctx = parse("5/10/2026", locale = Locale.US)
        assertEquals(LocalDate.of(2026, 5, 10), ctx.resolveDate())
    }

    @Test
    fun `13 slash 5 slash 2026 under en_US stays day-first because month cannot be 13`() {
        val ctx = parse("13/5/2026", locale = Locale.US)
        assertEquals(LocalDate.of(2026, 5, 13), ctx.resolveDate())
    }

    @Test
    fun `5 slash 13 slash 2026 under en_GB stays month-first because day cannot be 13 when month is 5`() {
        // part2=13 > 12, so the first position must be month → MDY regardless of locale
        val ctx = parse("5/13/2026", locale = Locale.UK)
        assertEquals(LocalDate.of(2026, 5, 13), ctx.resolveDate())
    }

    @Test
    fun `ISO date resolves same under en_GB locale`() {
        val ctx = parse("2026-10-05", locale = Locale.UK)
        assertEquals(LocalDate.of(2026, 10, 5), ctx.resolveDate())
    }

    @Test
    fun `dot-separated date stays DMY under en_US locale`() {
        val ctx = parse("5.10.2026", locale = Locale.US)
        assertEquals(LocalDate.of(2026, 10, 5), ctx.resolveDate())
    }

    @Test
    fun `5 slash 10 under Locale Germany resolves DMY to October 5`() {
        val ctx = parse("5/10", locale = Locale.GERMANY)
        assertEquals(LocalDate.of(2026, 10, 5), ctx.resolveDate())
    }

    @Test
    fun `5 slash 10 under Locale Japan resolves MDY-style year-month-day ordering to month-first`() {
        // Japan's short pattern is "y/MM/dd" — M appears before d → our rule picks MDY
        val ctx = parse("5/10", locale = Locale.JAPAN)
        assertEquals(LocalDate.of(2026, 5, 10), ctx.resolveDate())
    }
}
