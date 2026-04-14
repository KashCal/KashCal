package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.rule.DurationRule
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDateTime
import java.time.LocalTime

class DurationRuleTest {

    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0) // Monday April 13, 2026, 10:00 AM

    private fun parseWithDuration(input: String, startTime: LocalTime? = null): ParseContext {
        val tokens = WordTokenizer.tokenize(input.lowercase())
        val context = ParseContext(reference)
        if (startTime != null) {
            context.time = startTime
            context.timeSet = true
        }
        DurationRule.apply(tokens, context)
        return context
    }

    // === Basic duration patterns ===

    @Test
    fun `for 90 minutes sets endTime 90 minutes after start`() {
        val context = parseWithDuration("for 90 minutes", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(15, 30), context.endTime)
    }

    @Test
    fun `for 1 hour sets endTime 1 hour after start`() {
        val context = parseWithDuration("for 1 hour", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(15, 0), context.endTime)
    }

    @Test
    fun `for 2 hours sets endTime 2 hours after start`() {
        val context = parseWithDuration("for 2 hours", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(16, 0), context.endTime)
    }

    @Test
    fun `for 30 minutes with no prior time uses reference time`() {
        val context = parseWithDuration("for 30 minutes")
        // reference is 10:00
        assertEquals(LocalTime.of(10, 30), context.endTime)
    }

    // === Decimal duration ===

    @Test
    fun `for 2_5 hours sets endTime 2h30m after start`() {
        val context = parseWithDuration("for 2.5 hours", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(16, 30), context.endTime)
    }

    @Test
    fun `for 1_5 hours sets endTime 1h30m after start`() {
        val context = parseWithDuration("for 1.5 hours", startTime = LocalTime.of(10, 0))
        assertEquals(LocalTime.of(11, 30), context.endTime)
    }

    // === Edge cases ===

    @Test
    fun `for 0 minutes sets endTime equal to start`() {
        val context = parseWithDuration("for 0 minutes", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(14, 0), context.endTime)
    }

    @Test
    fun `no for clause leaves endTime null`() {
        val context = parseWithDuration("meeting tomorrow", startTime = LocalTime.of(14, 0))
        assertNull(context.endTime)
    }

    @Test
    fun `for with day unit does not set endTime`() {
        val context = parseWithDuration("for 1 day", startTime = LocalTime.of(14, 0))
        assertNull(context.endTime)
    }

    @Test
    fun `for with week unit does not set endTime`() {
        val context = parseWithDuration("for 1 week", startTime = LocalTime.of(14, 0))
        assertNull(context.endTime)
    }

    // === Consumed indices ===

    @Test
    fun `for duration tokens are consumed`() {
        val tokens = WordTokenizer.tokenize("meeting for 90 minutes")
        val context = ParseContext(reference)
        context.time = LocalTime.of(14, 0)
        context.timeSet = true
        DurationRule.apply(tokens, context)
        // "for" at index 1, "90" at index 2, "minutes" at index 3 should be consumed
        assertTrue(context.isConsumed(1))
        assertTrue(context.isConsumed(2))
        assertTrue(context.isConsumed(3))
    }

    // === "for" disambiguation ===

    @Test
    fun `for before non-number is not consumed as duration`() {
        val tokens = WordTokenizer.tokenize("party for sarah for 2 hours")
        val context = ParseContext(reference)
        context.time = LocalTime.of(19, 0)
        context.timeSet = true
        DurationRule.apply(tokens, context)
        assertEquals(LocalTime.of(21, 0), context.endTime)
        // First "for" (index 1) NOT consumed, second "for" (index 3) IS consumed
        assertTrue(!context.isConsumed(1))
        assertTrue(context.isConsumed(3))
    }

    // === Abbreviations ===

    @Test
    fun `for 30 mins works with abbreviated unit`() {
        val context = parseWithDuration("for 30 mins", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(14, 30), context.endTime)
    }

    @Test
    fun `for 2 hrs works with abbreviated unit`() {
        val context = parseWithDuration("for 2 hrs", startTime = LocalTime.of(14, 0))
        assertEquals(LocalTime.of(16, 0), context.endTime)
    }
}
