package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.WeekdayRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime

class WeekdayRuleTest {

    // April 12, 2026 = Sunday
    private val sunday = LocalDateTime.of(2026, 4, 12, 10, 0)

    // April 13, 2026 = Monday
    private val monday = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String, ref: LocalDateTime = sunday): ParseContext {
        val normalized = NormalizerChain().normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(ref)
        WeekdayRule.apply(tokens, context)
        return context
    }

    // ==================== Bare weekday from Sunday ====================

    @Test
    fun `bare Monday from Sunday resolves to next day`() {
        val ctx = parse("monday", sunday)
        // Sunday Apr 12 → Monday Apr 13 (1 day away)
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `bare Friday from Sunday resolves to this Friday`() {
        val ctx = parse("friday", sunday)
        // Sunday Apr 12 → Friday Apr 17
        assertEquals(LocalDate.of(2026, 4, 17), ctx.resolveDate())
    }

    @Test
    fun `bare Saturday from Sunday resolves to this Saturday`() {
        val ctx = parse("saturday", sunday)
        // Sunday Apr 12 → Saturday Apr 18 (not yesterday)
        assertEquals(LocalDate.of(2026, 4, 18), ctx.resolveDate())
    }

    // ==================== Same day of week ====================

    @Test
    fun `bare Sunday from Sunday resolves to next Sunday (7 days)`() {
        val ctx = parse("sunday", sunday)
        // Sunday Apr 12 → Sunday Apr 19 (not today)
        assertEquals(LocalDate.of(2026, 4, 19), ctx.resolveDate())
    }

    @Test
    fun `bare Monday from Monday resolves to next Monday (7 days)`() {
        val ctx = parse("monday", monday)
        // Monday Apr 13 → Monday Apr 20 (not today)
        assertEquals(LocalDate.of(2026, 4, 20), ctx.resolveDate())
    }

    // ==================== "next" modifier ====================

    @Test
    fun `next Monday from Sunday resolves to tomorrow`() {
        val ctx = parse("next monday", sunday)
        // "next Monday" = coming Monday = Apr 13
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `next Friday from Sunday resolves to this Friday`() {
        val ctx = parse("next friday", sunday)
        assertEquals(LocalDate.of(2026, 4, 17), ctx.resolveDate())
    }

    @Test
    fun `next Sunday from Sunday resolves to next week`() {
        val ctx = parse("next sunday", sunday)
        // "next Sunday" from Sunday = next occurrence = Apr 19
        assertEquals(LocalDate.of(2026, 4, 19), ctx.resolveDate())
    }

    // ==================== "last" modifier ====================

    @Test
    fun `last Friday from Sunday resolves to most recent Friday`() {
        val ctx = parse("last friday", sunday)
        // Sunday Apr 12 → Friday Apr 10
        assertEquals(LocalDate.of(2026, 4, 10), ctx.resolveDate())
    }

    @Test
    fun `last Monday from Sunday resolves to most recent Monday`() {
        val ctx = parse("last monday", sunday)
        // Sunday Apr 12 → Monday Apr 6
        assertEquals(LocalDate.of(2026, 4, 6), ctx.resolveDate())
    }

    @Test
    fun `last Sunday from Sunday resolves to last week`() {
        val ctx = parse("last sunday", sunday)
        // Sunday Apr 12 → Sunday Apr 5
        assertEquals(LocalDate.of(2026, 4, 5), ctx.resolveDate())
    }

    // ==================== Abbreviations ====================

    @Test
    fun `Mon abbreviation resolves correctly`() {
        val ctx = parse("mon", sunday)
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `Tue abbreviation resolves correctly`() {
        val ctx = parse("tue", sunday)
        assertEquals(LocalDate.of(2026, 4, 14), ctx.resolveDate())
    }

    @Test
    fun `Wed abbreviation resolves correctly`() {
        val ctx = parse("wed", sunday)
        assertEquals(LocalDate.of(2026, 4, 15), ctx.resolveDate())
    }

    @Test
    fun `Thur abbreviation resolves correctly`() {
        val ctx = parse("thur", sunday)
        assertEquals(LocalDate.of(2026, 4, 16), ctx.resolveDate())
    }

    @Test
    fun `Fri abbreviation resolves correctly`() {
        val ctx = parse("fri", sunday)
        assertEquals(LocalDate.of(2026, 4, 17), ctx.resolveDate())
    }

    @Test
    fun `Sat abbreviation resolves correctly`() {
        val ctx = parse("sat", sunday)
        assertEquals(LocalDate.of(2026, 4, 18), ctx.resolveDate())
    }

    @Test
    fun `Sun abbreviation resolves correctly`() {
        val ctx = parse("sun", sunday)
        // Sunday from Sunday → next Sunday
        assertEquals(LocalDate.of(2026, 4, 19), ctx.resolveDate())
    }
}
