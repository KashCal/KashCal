package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `next Monday from Sunday resolves to following week`() {
        val ctx = parse("next monday", sunday)
        // "next Monday" from Sunday Apr 12: next occurrence is Apr 13 (1 day), but <7 days → skip to Apr 20
        assertEquals(LocalDate.of(2026, 4, 20), ctx.resolveDate())
    }

    @Test
    fun `next Friday from Sunday resolves to following week`() {
        val ctx = parse("next friday", sunday)
        // "next Friday" from Sunday Apr 12: next occurrence is Apr 17 (5 days), but <7 days → skip to Apr 24
        assertEquals(LocalDate.of(2026, 4, 24), ctx.resolveDate())
    }

    @Test
    fun `next Sunday from Sunday resolves to following week`() {
        val ctx = parse("next sunday", sunday)
        // "next Sunday" from Sunday Apr 12: bare = Apr 19 (7 days), >=7 → Apr 19
        assertEquals(LocalDate.of(2026, 4, 19), ctx.resolveDate())
    }

    // ==================== "this" modifier ====================

    @Test
    fun `this Monday from Monday resolves to today`() {
        val ctx = parse("this monday", monday)
        // "this Monday" from Monday Apr 13: bare weekday for same day = +7 (Apr 20)
        // BUT "this" should mean today if it IS the day → Apr 13
        assertEquals(LocalDate.of(2026, 4, 13), ctx.resolveDate())
    }

    @Test
    fun `this Wednesday from Monday resolves to same week`() {
        val ctx = parse("this wednesday", monday)
        // "this Wednesday" from Monday Apr 13 = next occurrence = Apr 15
        assertEquals(LocalDate.of(2026, 4, 15), ctx.resolveDate())
    }

    @Test
    fun `this Friday from Sunday resolves to next occurrence`() {
        val ctx = parse("this friday", sunday)
        // "this Friday" from Sunday Apr 12 = next occurrence = Apr 17
        assertEquals(LocalDate.of(2026, 4, 17), ctx.resolveDate())
    }

    @Test
    fun `next Monday from Monday resolves to following week`() {
        val ctx = parse("next monday", monday)
        // "next Monday" from Monday Apr 13: bare = Apr 20 (7 days), >=7 → Apr 20
        assertEquals(LocalDate.of(2026, 4, 20), ctx.resolveDate())
    }

    @Test
    fun `next Wednesday from Monday resolves to following week`() {
        val ctx = parse("next wednesday", monday)
        // "next Wednesday" from Monday Apr 13: bare = Apr 15 (2 days), <7 → Apr 22
        assertEquals(LocalDate.of(2026, 4, 22), ctx.resolveDate())
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

    // ==================== Multi-day range (TO + WEEKDAY) ====================

    @Test
    fun `Friday to Sunday sets endDate`() {
        val ctx = parse("friday to sunday", sunday)
        // Sunday Apr 12 → Friday Apr 17, endDate Sunday Apr 19
        assertEquals(LocalDate.of(2026, 4, 17), ctx.weekdayDate)
        assertEquals(LocalDate.of(2026, 4, 19), ctx.endDate)
    }

    @Test
    fun `Monday to Wednesday sets endDate`() {
        val ctx = parse("monday to wednesday", sunday)
        // Sunday Apr 12 → Monday Apr 13, endDate Wednesday Apr 15
        assertEquals(LocalDate.of(2026, 4, 13), ctx.weekdayDate)
        assertEquals(LocalDate.of(2026, 4, 15), ctx.endDate)
    }

    @Test
    fun `Saturday to Sunday sets endDate`() {
        val ctx = parse("saturday to sunday", sunday)
        // Sunday Apr 12 → Saturday Apr 18, endDate Sunday Apr 19
        assertEquals(LocalDate.of(2026, 4, 18), ctx.weekdayDate)
        assertEquals(LocalDate.of(2026, 4, 19), ctx.endDate)
    }

    @Test
    fun `bare weekday without to has null endDate`() {
        val ctx = parse("friday", sunday)
        assertNull(ctx.endDate)
    }

    @Test
    fun `Friday to Sunday consumes all three tokens`() {
        val normalized = NormalizerChain().normalize("conference friday to sunday")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(sunday)
        WeekdayRule.apply(tokens, context)
        // "conference" (0) should NOT be consumed
        assertTrue(!context.isConsumed(0))
        // "friday" (1), "to" (2), "sunday" (3) should be consumed
        assertTrue(context.isConsumed(1))
        assertTrue(context.isConsumed(2))
        assertTrue(context.isConsumed(3))
    }

    @Test
    fun `next Friday to Sunday sets both dates with modifier`() {
        val ctx = parse("next friday to sunday", sunday)
        // "next friday" from Sunday Apr 12: bare = Apr 17, <7 → Apr 24
        // endDate: Sunday after Apr 24 = Apr 26
        assertEquals(LocalDate.of(2026, 4, 24), ctx.weekdayDate)
        assertEquals(LocalDate.of(2026, 4, 26), ctx.endDate)
    }
}
