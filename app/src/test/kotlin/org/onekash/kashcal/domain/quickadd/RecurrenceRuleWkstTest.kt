package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.RecurrenceRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDateTime
import java.util.Calendar

/**
 * Tests that the firstDayOfWeek setting passes through [ParseContext] to [RecurrenceRule] without
 * changing its output. The grammar gives a WKST only to "every N weeks on <weekday>", a single-day
 * BYDAY, and `RruleBuilder.weekly` emits WKST only for an INTERVAL above 1 with two or more days.
 */
class RecurrenceRuleWkstTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private val normalizer = NormalizerChain()

    private fun parse(input: String, firstDayOfWeek: Int): ParseContext {
        val normalized = normalizer.normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference, firstDayOfWeek = firstDayOfWeek)
        RecurrenceRule.apply(tokens, context)
        return context
    }

    @Test
    fun `every 2 weeks on Sunday with firstDayOfWeek=SUNDAY emits no WKST (single-day gate)`() {
        val ctx = parse("every 2 weeks on Sunday", firstDayOfWeek = Calendar.SUNDAY)
        // WKST changes nothing for a single-day BYDAY, so the builder leaves it out.
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=SU", ctx.rrule)
    }

    @Test
    fun `every 2 weeks on Sunday with firstDayOfWeek=MONDAY emits no WKST (single-day gate)`() {
        val ctx = parse("every 2 weeks on Sunday", firstDayOfWeek = Calendar.MONDAY)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=SU", ctx.rrule)
    }

    @Test
    fun `every Monday with firstDayOfWeek=SUNDAY emits no INTERVAL no WKST (interval=1 gate)`() {
        val ctx = parse("every Monday", firstDayOfWeek = Calendar.SUNDAY)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", ctx.rrule)
    }
}
