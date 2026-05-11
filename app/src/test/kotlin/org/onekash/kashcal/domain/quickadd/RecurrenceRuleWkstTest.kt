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
 * WKST plumbing tests for QuickAdd's RecurrenceRule. Today's grammar only emits
 * single-day BYDAY for biweekly, which the gate suppresses — so these tests pin
 * that the firstDayOfWeek setting threads through ParseContext without corrupting
 * output. See RruleBuilder.weekly for the gate semantics.
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
        // Single-day BYDAY: WKST has no behavioral effect, gate suppresses emission.
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
