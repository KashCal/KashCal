package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.AbsoluteDateRule
import org.onekash.kashcal.domain.quickadd.rule.DateRangeRule
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class DateRangeRuleTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private class Parsed(val tokens: List<Token>, val context: ParseContext) {
        val unconsumed: List<String> get() = tokens.indices.filter { !context.isConsumed(it) }.map { tokens[it].text }
    }

    private fun parse(input: String): Parsed {
        val tokens = WordTokenizer.tokenize(NormalizerChain().normalize(input), locale = Locale.US)
        val context = ParseContext(reference)
        DateRangeRule.apply(tokens, context)
        return Parsed(tokens, context)
    }

    private fun assertRange(input: String, start: LocalDate, end: LocalDate, left: List<String> = emptyList()) {
        val parsed = parse(input)
        assertEquals("$input: start", start, parsed.context.absoluteDate)
        assertEquals("$input: end", end, parsed.context.endDate)
        assertTrue("$input: dateSet", parsed.context.dateSet)
        assertEquals("$input: unconsumed", left, parsed.unconsumed)
    }

    private fun assertNoRange(input: String) {
        val parsed = parse(input)
        assertNull("$input: start", parsed.context.absoluteDate)
        assertNull("$input: end", parsed.context.endDate)
        assertEquals("$input: unconsumed", parsed.tokens.map { it.text }, parsed.unconsumed)
    }

    private val aug23 = LocalDate.of(2026, 8, 23)
    private val aug28 = LocalDate.of(2026, 8, 28)

    @Test
    fun `month first with to`() = assertRange("aug 23 to 28", aug23, aug28)

    @Test
    fun `month first with a glued hyphen`() = assertRange("aug 23-28", aug23, aug28)

    @Test
    fun `day first with to`() = assertRange("23 to 28 august", aug23, aug28)

    @Test
    fun `day first with a glued hyphen`() = assertRange("23-28 august", aug23, aug28)

    @Test
    fun `across months`() =
        assertRange("aug 30 to sep 2", LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2))

    @Test
    fun `across New Year`() =
        assertRange("dec 24 to jan 2", LocalDate.of(2026, 12, 24), LocalDate.of(2027, 1, 2))

    @Test
    fun `a backwards pair in one month is not a range`() {
        assertNoRange("aug 28 to 23")
        val tokens = WordTokenizer.tokenize(NormalizerChain().normalize("aug 28 to 23"), locale = Locale.US)
        val context = ParseContext(reference)
        DateRangeRule.apply(tokens, context)
        AbsoluteDateRule.apply(tokens, context)
        assertEquals(LocalDate.of(2026, 8, 28), context.absoluteDate)
        assertNull(context.endDate)
    }

    @Test
    fun `a long backwards range across months is not a range`() = assertNoRange("sep 2 to aug 30")

    @Test
    fun `a hyphen day pair is read as two days`() =
        assertRange("3-5 august", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5))

    @Test
    fun `a hyphen pair without a month is left alone`() = assertNoRange("meeting 23-28")

    @Test
    fun `an explicit year after a month-first range`() =
        assertRange("aug 23 to 28 2027", LocalDate.of(2027, 8, 23), LocalDate.of(2027, 8, 28))

    @Test
    fun `an explicit year after a day-first range`() =
        assertRange("23 to 28 august 2027", LocalDate.of(2027, 8, 23), LocalDate.of(2027, 8, 28))

    @Test
    fun `single dates, time ranges and full dates are untouched`() {
        assertNoRange("25 december 2026 2-4pm")
        assertNoRange("15-01-2027")
        assertNoRange("2026-10-05")
    }

    @Test
    fun `a spaced dash works like to`() {
        assertRange("aug 23 - 28", aug23, aug28)
        assertRange("23 - 28 august", aug23, aug28)
        assertNoRange("meeting 3pm - 5pm")
    }

    @Test
    fun `a from before the range is consumed`() =
        assertRange("trip from aug 23 to 28 with kids", aug23, aug28, left = listOf("trip", "with", "kids"))

    @Test
    fun `an end that is a time or an amount is not a day`() {
        assertNoRange("meeting aug 3 to 5 pm")
        assertNoRange("meeting aug 3 to 5pm")
        assertNoRange("meeting aug 3 to 5:30")
        assertNoRange("meeting aug 3 to 5 hours")
    }

    @Test
    fun `a spaced em dash works like to`() = assertRange("aug 23 \u2014 28", aug23, aug28)
}
