package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.RecurrenceRule
import org.onekash.kashcal.domain.quickadd.rule.TimeRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RecurrenceRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private val normalizer = NormalizerChain()

    /**
     * Helper: normalizes input, tokenizes, applies RecurrenceRule.
     * Optionally applies TimeRule first (for tests that combine recurrence + time).
     */
    private fun parse(input: String, applyTime: Boolean = false): ParseContext {
        val normalized = normalizer.normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        if (applyTime) {
            TimeRule.apply(tokens, context)
        }
        RecurrenceRule.apply(tokens, context)
        return context
    }

    // ==================== Standalone RECURRENCE_KEYWORD ====================

    @Test
    fun `daily sets FREQ=DAILY`() {
        val ctx = parse("daily")
        assertEquals("FREQ=DAILY", ctx.rrule)
    }

    @Test
    fun `weekly sets FREQ=WEEKLY`() {
        val ctx = parse("weekly")
        assertEquals("FREQ=WEEKLY", ctx.rrule)
    }

    @Test
    fun `biweekly sets FREQ=WEEKLY INTERVAL=2`() {
        val ctx = parse("biweekly")
        assertEquals("FREQ=WEEKLY;INTERVAL=2", ctx.rrule)
    }

    @Test
    fun `monthly sets FREQ=MONTHLY`() {
        val ctx = parse("monthly")
        assertEquals("FREQ=MONTHLY", ctx.rrule)
    }

    @Test
    fun `yearly sets FREQ=YEARLY`() {
        val ctx = parse("yearly")
        assertEquals("FREQ=YEARLY", ctx.rrule)
    }

    @Test
    fun `annually sets FREQ=YEARLY`() {
        val ctx = parse("annually")
        assertEquals("FREQ=YEARLY", ctx.rrule)
    }

    // ==================== EVERY + WEEKDAY ====================

    @Test
    fun `every Monday sets weekly with BYDAY=MO`() {
        val ctx = parse("every Monday")
        assertEquals("FREQ=WEEKLY;BYDAY=MO", ctx.rrule)
    }

    @Test
    fun `every Friday sets weekly with BYDAY=FR`() {
        val ctx = parse("every Friday")
        assertEquals("FREQ=WEEKLY;BYDAY=FR", ctx.rrule)
    }

    @Test
    fun `every Monday sets weekdayDate to next Monday`() {
        val ctx = parse("every Monday")
        // Reference is Monday April 13, 2026. Bare weekday for same day advances 7 days.
        assertEquals(LocalDate.of(2026, 4, 20), ctx.weekdayDate)
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `every Wednesday sets weekdayDate to next Wednesday`() {
        val ctx = parse("every Wednesday")
        // Reference is Monday April 13. Wednesday is 2 days ahead → April 15.
        assertEquals(LocalDate.of(2026, 4, 15), ctx.weekdayDate)
    }

    @Test
    fun `every Monday consumes both tokens`() {
        val normalized = normalizer.normalize("every Monday")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // Both "every" (index 0) and "monday" (index 1) should be consumed
        assertTrue(context.isConsumed(0))
        assertTrue(context.isConsumed(1))
    }

    // ==================== EVERY + UNIT ====================

    @Test
    fun `every day sets FREQ=DAILY`() {
        val ctx = parse("every day")
        assertEquals("FREQ=DAILY", ctx.rrule)
    }

    @Test
    fun `every week sets FREQ=WEEKLY`() {
        val ctx = parse("every week")
        assertEquals("FREQ=WEEKLY", ctx.rrule)
    }

    @Test
    fun `every month sets FREQ=MONTHLY`() {
        val ctx = parse("every month")
        assertEquals("FREQ=MONTHLY", ctx.rrule)
    }

    @Test
    fun `every year sets FREQ=YEARLY`() {
        val ctx = parse("every year")
        assertEquals("FREQ=YEARLY", ctx.rrule)
    }

    // ==================== EVERY + NUMBER + UNIT ====================

    @Test
    fun `every 2 weeks sets FREQ=WEEKLY INTERVAL=2`() {
        val ctx = parse("every 2 weeks")
        assertEquals("FREQ=WEEKLY;INTERVAL=2", ctx.rrule)
    }

    @Test
    fun `every 3 days sets FREQ=DAILY INTERVAL=3`() {
        val ctx = parse("every 3 days")
        assertEquals("FREQ=DAILY;INTERVAL=3", ctx.rrule)
    }

    @Test
    fun `every 6 months sets FREQ=MONTHLY INTERVAL=6`() {
        val ctx = parse("every 6 months")
        assertEquals("FREQ=MONTHLY;INTERVAL=6", ctx.rrule)
    }

    // ==================== EVERY + NUMBER + UNIT + ON + WEEKDAY ====================

    @Test
    fun `every 2 weeks on Friday sets interval and BYDAY`() {
        val ctx = parse("every 2 weeks on Friday")
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR", ctx.rrule)
    }

    @Test
    fun `every 2 weeks on Friday sets weekdayDate`() {
        val ctx = parse("every 2 weeks on Friday")
        // Reference is Monday April 13. Friday is 4 days ahead → April 17.
        assertEquals(LocalDate.of(2026, 4, 17), ctx.weekdayDate)
        assertTrue(ctx.dateSet)
    }

    // ==================== Combined with time (full pipeline partial) ====================

    @Test
    fun `every Monday at 10am - recurrence with time`() {
        val ctx = parse("every Monday at 10am", applyTime = true)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", ctx.rrule)
        assertEquals(LocalTime.of(10, 0), ctx.resolveTime())
    }

    @Test
    fun `daily standup at 9am - recurrence keyword with time`() {
        val ctx = parse("daily standup at 9am", applyTime = true)
        assertEquals("FREQ=DAILY", ctx.rrule)
        assertEquals(LocalTime.of(9, 0), ctx.resolveTime())
    }

    // ==================== Title extraction (RECURRENCE_KEYWORD consumed) ====================

    @Test
    fun `weekly standup - keyword consumed, standup remains for title`() {
        val normalized = normalizer.normalize("weekly standup")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // "weekly" should be consumed
        assertTrue(context.isConsumed(0))
        // "standup" should NOT be consumed
        assertTrue(!context.isConsumed(1))
    }

    @Test
    fun `daily meeting - keyword consumed, meeting remains for title`() {
        val normalized = normalizer.normalize("daily meeting")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        assertTrue(context.isConsumed(0))
        assertTrue(!context.isConsumed(1))
    }

    // ==================== Edge cases ====================

    @Test
    fun `no recurrence keywords gives null rrule`() {
        val ctx = parse("meeting tomorrow at 3pm")
        assertNull(ctx.rrule)
    }

    @Test
    fun `every alone without following pattern gives null rrule`() {
        val ctx = parse("every")
        assertNull(ctx.rrule)
    }

    @Test
    fun `every followed by unknown word gives null rrule`() {
        val ctx = parse("every thing")
        assertNull(ctx.rrule)
    }

    @Test
    fun `every 2 weeks without on - no BYDAY`() {
        val ctx = parse("every 2 weeks")
        assertEquals("FREQ=WEEKLY;INTERVAL=2", ctx.rrule)
        // No BYDAY component
        assertTrue(!ctx.rrule!!.contains("BYDAY"))
    }
}
