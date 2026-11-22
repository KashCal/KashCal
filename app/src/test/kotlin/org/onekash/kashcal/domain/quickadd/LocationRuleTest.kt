package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.normalizer.SpannedText
import org.onekash.kashcal.domain.quickadd.rule.AbsoluteDateRule
import org.onekash.kashcal.domain.quickadd.rule.LocationRule
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.RelativeDateRule
import org.onekash.kashcal.domain.quickadd.rule.TimeRule
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDateTime
import java.util.Locale

class LocationRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private val normalizer = NormalizerChain()

    /**
     * Normalizes and tokenizes [input] over the span path the parser uses (tokens cut from the
     * typed text), then applies the relative-date, absolute-date and time rules before
     * [LocationRule], in [QuickAddParser]'s order. The parser also runs other rules before it.
     */
    private fun parse(input: String): ParseContext {
        val tokens = WordTokenizer.tokenize(normalizer.normalize(SpannedText.of(input)), input, Locale.US)
        val context = ParseContext(reference, source = input)
        RelativeDateRule.apply(tokens, context)
        AbsoluteDateRule.apply(tokens, context)
        TimeRule.apply(tokens, context)
        LocationRule.apply(tokens, context)
        return context
    }

    // ==================== Typed location text ====================

    @Test
    fun `a unit word inside a location does not end it`() {
        assertEquals("Hall Day Spa", parse("Lunch at Hall Day Spa").location)
        assertEquals("Week Street Cafe", parse("Coffee at Week Street Cafe").location)
    }

    @Test
    fun `a trailing unit word still ends the location`() {
        assertEquals("Bob's next", parse("Party at Bob's next week").location)
    }

    @Test
    fun `the location keeps typed punctuation and cuts consumed words`() {
        assertEquals("Bob & Sue's", parse("Dinner at Bob & Sue's tomorrow").location)
        assertEquals("Blue Bottle downtown", parse("Coffee at Blue Bottle tomorrow downtown").location)
    }

    // ==================== Basic location extraction ====================

    @Test
    fun `trailing at with location words extracts location`() {
        val ctx = parse("coffee at Blue Bottle")
        assertEquals("Blue Bottle", ctx.location)
    }

    @Test
    fun `location after time at`() {
        val ctx = parse("coffee tomorrow at 3pm at Blue Bottle")
        assertEquals("Blue Bottle", ctx.location)
    }

    @Test
    fun `at before time only - no location`() {
        val ctx = parse("meeting at 3pm")
        assertNull(ctx.location)
    }

    @Test
    fun `at noon only - no location`() {
        val ctx = parse("lunch at noon")
        assertNull(ctx.location)
    }

    // ==================== Multiple "at" tokens ====================

    @Test
    fun `two at tokens - time at consumed, location at available`() {
        val ctx = parse("at noon at Blue Bottle")
        assertEquals("Blue Bottle", ctx.location)
    }

    @Test
    fun `dentist at location at time`() {
        val ctx = parse("Dentist at Dr Smiths Office at 2pm")
        assertEquals("Dr Smiths Office", ctx.location)
    }

    // ==================== Location with numbers ====================

    @Test
    fun `location with numbers preserved`() {
        val ctx = parse("Meeting at Room 101 at 3pm")
        assertEquals("Room 101", ctx.location)
    }

    // ==================== Edge cases ====================

    @Test
    fun `input ending with at - no location`() {
        val ctx = parse("meeting at")
        assertNull(ctx.location)
    }

    @Test
    fun `no at in input - no location`() {
        val ctx = parse("coffee tomorrow 3pm")
        assertNull(ctx.location)
    }

    @Test
    fun `location is multi-word`() {
        val ctx = parse("Party at The Grand Ballroom")
        assertEquals("The Grand Ballroom", ctx.location)
    }

    @Test
    fun `all tokens after at are consumed - no location`() {
        val ctx = parse("meeting at 3pm")
        assertNull(ctx.location)
    }

    @Test
    fun `location extraction does not interfere with time`() {
        val ctx = parse("coffee at 3pm at Blue Bottle")
        assertEquals("Blue Bottle", ctx.location)
        assertEquals(java.time.LocalTime.of(15, 0), ctx.resolveTime())
    }

    @Test
    fun `special characters preserved in location`() {
        val ctx = parse("Coffee at O'Brien's Pub tomorrow at 3pm")
        assertEquals("O'Brien's Pub", ctx.location)
    }

    @Test
    fun `a dash between kept location words stays`() {
        assertEquals("Joe\u2019s \u2014 downtown", parse("Dinner at Joe\u2019s \u2014 downtown tomorrow").location)
    }
}
