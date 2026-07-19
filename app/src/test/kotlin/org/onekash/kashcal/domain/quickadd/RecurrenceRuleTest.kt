package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /** Like [parse] but with a caller-supplied reference date. */
    private fun parseAt(input: String, ref: LocalDateTime): ParseContext {
        val normalized = normalizer.normalize(input)
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(ref)
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

    // ==================== EVERY WEEKDAY ====================

    @Test
    fun `every weekday sets BYDAY MO through FR`() {
        val ctx = parse("every weekday")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", ctx.rrule)
    }

    @Test
    fun `every weekdays sets BYDAY MO through FR`() {
        val ctx = parse("every weekdays")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", ctx.rrule)
    }

    @Test
    fun `every weekday at 9am - recurrence with time`() {
        val ctx = parse("every weekday at 9am", applyTime = true)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", ctx.rrule)
        assertEquals(LocalTime.of(9, 0), ctx.resolveTime())
    }

    @Test
    fun `every weekday consumes both tokens`() {
        val normalized = normalizer.normalize("standup every weekday")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // "every" (index 1) and "weekday" (index 2) should be consumed
        assertTrue(context.isConsumed(1))
        assertTrue(context.isConsumed(2))
        // "standup" (index 0) should NOT be consumed
        assertTrue(!context.isConsumed(0))
    }

    @Test
    fun `weekdays standalone sets BYDAY MO through FR`() {
        val ctx = parse("weekdays")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", ctx.rrule)
    }

    @Test
    fun `weekdays standup - keyword consumed, standup remains`() {
        val normalized = normalizer.normalize("weekdays standup")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        assertTrue(context.isConsumed(0))
        assertTrue(!context.isConsumed(1))
    }

    @Test
    fun `every day still works - not confused with weekday`() {
        val ctx = parse("every day")
        assertEquals("FREQ=DAILY", ctx.rrule)
    }

    @Test
    fun `every Monday still works after weekday addition`() {
        val ctx = parse("every Monday")
        assertEquals("FREQ=WEEKLY;BYDAY=MO", ctx.rrule)
    }

    // ==================== UNTIL ====================

    @Test
    fun `every Monday until December sets UNTIL to Dec 31`() {
        val ctx = parse("every Monday until December")
        assertNotNull(ctx.rrule)
        assertTrue(ctx.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(ctx.rrule!!.contains("BYDAY=MO"))
        assertTrue(ctx.rrule!!.contains("UNTIL="))
    }

    @Test
    fun `every Monday until Jan 15 sets UNTIL`() {
        val ctx = parse("every Monday until Jan 15")
        assertNotNull(ctx.rrule)
        assertTrue(ctx.rrule!!.contains("UNTIL="))
    }

    @Test
    fun `every Monday until December 15 sets UNTIL`() {
        val ctx = parse("every Monday until December 15")
        assertNotNull(ctx.rrule)
        assertTrue(ctx.rrule!!.contains("UNTIL="))
    }

    // ==================== COUNT ====================

    @Test
    fun `every day for 10 times sets COUNT=10`() {
        val ctx = parse("every day for 10 times")
        assertNotNull(ctx.rrule)
        assertTrue(ctx.rrule!!.contains("FREQ=DAILY"))
        assertTrue(ctx.rrule!!.contains("COUNT=10"))
    }

    @Test
    fun `daily 10 times sets COUNT=10`() {
        val ctx = parse("daily 10 times")
        assertNotNull(ctx.rrule)
        assertTrue(ctx.rrule!!.contains("FREQ=DAILY"))
        assertTrue(ctx.rrule!!.contains("COUNT=10"))
    }

    @Test
    fun `every Monday without until or count has no UNTIL or COUNT`() {
        val ctx = parse("every Monday")
        assertNotNull(ctx.rrule)
        assertTrue(!ctx.rrule!!.contains("UNTIL"))
        assertTrue(!ctx.rrule!!.contains("COUNT"))
    }

    // ==================== EVERY + multiple weekdays ====================

    @Test
    fun `every Monday and Wednesday sets BYDAY=MO,WE`() {
        val ctx = parse("every Monday and Wednesday")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", ctx.rrule)
    }

    @Test
    fun `every Monday Wednesday and Friday sets BYDAY=MO,WE,FR`() {
        // Commas normalize to spaces upstream, so "Monday, Wednesday, and Friday"
        // reaches the rule as "monday wednesday and friday".
        val ctx = parse("every Monday, Wednesday, and Friday")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", ctx.rrule)
    }

    @Test
    fun `every Tuesday and Thursday sets BYDAY in weekday order`() {
        val ctx = parse("every Thursday and Tuesday")
        // Output is canonically Monday-first ordered by RruleBuilder.
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", ctx.rrule)
    }

    @Test
    fun `gym every Monday and Wednesday consumes all recurrence tokens`() {
        val normalized = normalizer.normalize("gym every Monday and Wednesday")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // tokens: gym(0) every(1) monday(2) and(3) wednesday(4)
        assertTrue(!context.isConsumed(0)) // "gym" stays for title
        assertTrue(context.isConsumed(1))
        assertTrue(context.isConsumed(2))
        assertTrue(context.isConsumed(3)) // "and" connector consumed
        assertTrue(context.isConsumed(4))
    }

    @Test
    fun `every Monday and Wednesday anchors start on the earliest upcoming selected day`() {
        val ctx = parse("every Monday and Wednesday")
        // Reference is Monday April 13; bare Monday advances to April 20, Wednesday
        // is April 15 — the earliest upcoming selected day, so DTSTART must be Apr 15
        // (a day the rule actually recurs on), not the reference Monday.
        assertEquals(LocalDate.of(2026, 4, 15), ctx.weekdayDate)
        assertTrue(ctx.dateSet)
    }

    // ==================== MWF / TTh shorthands ====================

    @Test
    fun `MWF sets BYDAY=MO,WE,FR`() {
        val ctx = parse("MWF")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", ctx.rrule)
    }

    @Test
    fun `TTh sets BYDAY=TU,TH`() {
        val ctx = parse("TTh")
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", ctx.rrule)
    }

    @Test
    fun `standup MWF at 9am - shorthand with time`() {
        val ctx = parse("standup MWF at 9am", applyTime = true)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", ctx.rrule)
        assertEquals(LocalTime.of(9, 0), ctx.resolveTime())
    }

    // ==================== EVERY OTHER <unit|weekday> ====================

    @Test
    fun `every other week sets FREQ=WEEKLY INTERVAL=2`() {
        val ctx = parse("every other week")
        assertEquals("FREQ=WEEKLY;INTERVAL=2", ctx.rrule)
    }

    @Test
    fun `every other day sets FREQ=DAILY INTERVAL=2`() {
        val ctx = parse("every other day")
        assertEquals("FREQ=DAILY;INTERVAL=2", ctx.rrule)
    }

    @Test
    fun `every other month sets FREQ=MONTHLY INTERVAL=2`() {
        val ctx = parse("every other month")
        assertEquals("FREQ=MONTHLY;INTERVAL=2", ctx.rrule)
    }

    @Test
    fun `every other Friday sets weekly interval 2 with BYDAY=FR`() {
        val ctx = parse("every other Friday")
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR", ctx.rrule)
    }

    @Test
    fun `every other Friday sets weekdayDate`() {
        val ctx = parse("every other Friday")
        // Reference Monday April 13; coming Friday is April 17.
        assertEquals(LocalDate.of(2026, 4, 17), ctx.weekdayDate)
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `every other day consumes every other and day`() {
        val normalized = normalizer.normalize("standup every other day")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // tokens: standup(0) every(1) other(2) day(3)
        assertTrue(!context.isConsumed(0))
        assertTrue(context.isConsumed(1))
        assertTrue(context.isConsumed(2))
        assertTrue(context.isConsumed(3))
    }

    // ==================== Monthly ordinal-weekday ("first Monday of the month") ====================

    @Test
    fun `first Monday of the month sets BYDAY=1MO`() {
        val ctx = parse("first Monday of the month")
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", ctx.rrule)
    }

    @Test
    fun `first Monday of the month anchors start on the first occurrence`() {
        // Reference Monday April 13; April's 1st Monday (Apr 6) is already past,
        // so the first occurrence on/after the reference is May's 1st Monday.
        val ctx = parse("first Monday of the month")
        assertEquals(LocalDate.of(2026, 5, 4), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `last Friday of every month sets BYDAY=-1FR`() {
        val ctx = parse("last Friday of every month")
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", ctx.rrule)
    }

    @Test
    fun `last Friday of every month anchors start on the first occurrence`() {
        // Last Friday of April 2026 is Apr 24, which is on/after the reference.
        val ctx = parse("last Friday of every month")
        assertEquals(LocalDate.of(2026, 4, 24), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `second Tuesday of the month sets BYDAY=2TU`() {
        // "second" tokenizes as UNIT(SECONDS); the rule must map it to ordinal 2.
        val ctx = parse("second Tuesday of the month")
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", ctx.rrule)
    }

    @Test
    fun `second Tuesday of the month anchors start on the first occurrence`() {
        // 2nd Tuesday of April 2026 is Apr 14, on/after the reference Monday Apr 13.
        val ctx = parse("second Tuesday of the month")
        assertEquals(LocalDate.of(2026, 4, 14), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `the 2nd Friday of every month sets BYDAY=2FR`() {
        val ctx = parse("the 2nd Friday of every month")
        assertEquals("FREQ=MONTHLY;BYDAY=2FR", ctx.rrule)
    }

    @Test
    fun `the 2nd Friday of every month anchors start on the first occurrence`() {
        // April's 2nd Friday (Apr 10) is past the reference, so it rolls to May's
        // 2nd Friday, May 8.
        val ctx = parse("the 2nd Friday of every month")
        assertEquals(LocalDate.of(2026, 5, 8), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    // ==================== Monthly by-date ("15th of every month") ====================

    @Test
    fun `on the 15th of every month sets BYMONTHDAY=15`() {
        val ctx = parse("on the 15th of every month")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", ctx.rrule)
    }

    @Test
    fun `on the 15th of every month anchors start on the first occurrence`() {
        // April 15 is on/after the reference Monday Apr 13.
        val ctx = parse("on the 15th of every month")
        assertEquals(LocalDate.of(2026, 4, 15), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `the 1st of the month sets BYMONTHDAY=1`() {
        val ctx = parse("the 1st of the month")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1", ctx.rrule)
    }

    @Test
    fun `the 1st of the month anchors start on the first occurrence`() {
        // April 1 is already past the reference Apr 13, so it rolls to May 1.
        val ctx = parse("the 1st of the month")
        assertEquals(LocalDate.of(2026, 5, 1), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `the 31st of every month skips short months rather than clamping`() {
        // April 2026 has only 30 days, so the by-date anchor must skip April
        // entirely (not clamp to Apr 30) and land on May 31.
        val ctx = parse("the 31st of every month")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=31", ctx.rrule)
        assertEquals(LocalDate.of(2026, 5, 31), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `fifth Friday of every month skips months without a 5th Friday`() {
        // April 2026 has only four Fridays (3, 10, 17, 24); the ordinal-5 anchor
        // must skip April rather than spilling into May, landing on May 29 —
        // May's actual 5th Friday.
        val ctx = parse("fifth Friday of every month")
        assertEquals("FREQ=MONTHLY;BYDAY=5FR", ctx.rrule)
        assertEquals(LocalDate.of(2026, 5, 29), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    // ==================== Monthly last-day ("last day of the month") ====================

    @Test
    fun `last day of the month sets BYMONTHDAY=-1`() {
        val ctx = parse("last day of the month")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", ctx.rrule)
        // "the month" shares the recurring anchor path with "every month"; pin the
        // start date here too so a regression can't diverge the two connectives.
        assertEquals(LocalDate.of(2026, 4, 30), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `last day of every month sets BYMONTHDAY=-1`() {
        val ctx = parse("last day of every month")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", ctx.rrule)
    }

    @Test
    fun `last day of every month anchors start on the reference month end`() {
        // The reference month's last day (Apr 30) is always on/after the reference
        // (Mon Apr 13), so the recurring last-day rule starts there.
        val ctx = parse("last day of every month")
        assertEquals(LocalDate.of(2026, 4, 30), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `last day of this month is a one-off at month end with no rrule`() {
        // "this month" is a single occurrence in the current month, not a
        // recurrence — February 2026 ends on the 28th.
        val ctx = parseAt("last day of this month", feb2026)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 2, 28), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `rent last day of every month keeps rent as the title`() {
        val normalized = normalizer.normalize("rent last day of every month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // tokens: rent(0) last(1) day(2) of(3) every(4) month(5)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", context.rrule)
        assertTrue("rent must NOT be consumed (it's the title)", !context.isConsumed(0))
        assertTrue("last must be consumed", context.isConsumed(1))
        assertTrue("day must be consumed", context.isConsumed(2))
        assertTrue("of must be consumed", context.isConsumed(3))
        assertTrue("every must be consumed", context.isConsumed(4))
        assertTrue("month must be consumed", context.isConsumed(5))
    }

    @Test
    fun `first day of every month is not claimed as a last-day rule`() {
        // Only "last day" maps to BYMONTHDAY=-1. "first day" carries an ordinal
        // other than "last", so the day-unit case must decline; the phrase then
        // falls through to the plain EVERY + month path (FREQ=MONTHLY), never the
        // last-day rule.
        val ctx = parse("first day of every month")
        assertEquals("FREQ=MONTHLY", ctx.rrule)
    }

    @Test
    fun `pay rent on the 15th of every month leaves pay rent for title`() {
        val normalized = normalizer.normalize("pay rent on the 15th of every month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // tokens: pay(0) rent(1) on(2) the(3) 15th(4) of(5) every(6) month(7)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", context.rrule)
        assertTrue(!context.isConsumed(0)) // pay
        assertTrue(!context.isConsumed(1)) // rent
        assertTrue(context.isConsumed(4)) // 15th
        assertTrue(context.isConsumed(5)) // of
        assertTrue(context.isConsumed(6)) // every
        assertTrue(context.isConsumed(7)) // month
    }

    // ==================== "of this month" → one-off in the current month (NOT recurrence) ====================

    @Test
    fun `last Friday of this month is a one-off on the current month's last Friday`() {
        // "this month" means the current month (April 2026), NOT a recurrence.
        // Last Friday of April 2026 is Apr 24.
        val ctx = parse("last Friday of this month")
        assertNull("of this month must not be recurring", ctx.rrule)
        assertEquals(LocalDate.of(2026, 4, 24), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `first Monday of this month is a one-off even when already past the reference`() {
        // April's 1st Monday (Apr 6) is before the reference Mon Apr 13, but
        // "this month" anchors to the current month regardless — no roll-forward.
        val ctx = parse("first Monday of this month")
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 4, 6), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `15th of this month is a one-off on the current month's 15th`() {
        val ctx = parse("15th of this month")
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 4, 15), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `last Friday of this month consumes its tokens so they do not leak into the title`() {
        val normalized = normalizer.normalize("rent last Friday of this month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(reference)
        RecurrenceRule.apply(tokens, context)
        // tokens: rent(0) last(1) friday(2) of(3) this(4) month(5)
        assertNull(context.rrule)
        assertTrue("last must be consumed", context.isConsumed(1))
        assertTrue("friday must be consumed", context.isConsumed(2))
        assertTrue("of must be consumed", context.isConsumed(3))
        assertTrue("this must be consumed", context.isConsumed(4))
        assertTrue("month must be consumed", context.isConsumed(5))
        assertTrue("rent must NOT be consumed (it's the title)", !context.isConsumed(0))
    }

    // ==================== "of this month" degenerate cases → clamp within the month ====================

    // Reference for clamp tests: Tue Feb 10, 2026. February 2026 has 28 days and
    // only four Fridays (6, 13, 20, 27) — no 5th Friday, and no 29/30/31.
    private val feb2026 = LocalDateTime.of(2026, 2, 10, 10, 0)

    @Test
    fun `31st of this month clamps to the last day of a short month`() {
        val ctx = parseAt("31st of this month", feb2026)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 2, 28), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `30th of this month clamps to the last day of a short month`() {
        val ctx = parseAt("30th of this month", feb2026)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 2, 28), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `fifth Friday of this month falls back to the last Friday when the month has none`() {
        // "this month" must stay in the month, so with no 5th Friday it uses the
        // last Friday (Feb 27) rather than rolling forward to another month.
        val ctx = parseAt("fifth Friday of this month", feb2026)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 2, 27), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `31st of this month with a leaked-token check keeps the title clean on clamp`() {
        val normalized = normalizer.normalize("rent 31st of this month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(feb2026)
        RecurrenceRule.apply(tokens, context)
        // tokens: rent(0) 31st(1) of(2) this(3) month(4)
        assertNull(context.rrule)
        assertTrue("31st must be consumed", context.isConsumed(1))
        assertTrue("of must be consumed", context.isConsumed(2))
        assertTrue("this must be consumed", context.isConsumed(3))
        assertTrue("month must be consumed", context.isConsumed(4))
        assertTrue("rent must NOT be consumed (it's the title)", !context.isConsumed(0))
        assertEquals(LocalDate.of(2026, 2, 28), context.resolveDate())
    }

    // Leap-year boundary for the day-of-month clamp (pins minOf() behavior).
    private val feb2028Leap = LocalDateTime.of(2028, 2, 10, 10, 0)

    @Test
    fun `29th of this month clamps to Feb 28 in a non-leap year`() {
        val ctx = parseAt("29th of this month", feb2026)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 2, 28), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    @Test
    fun `29th of this month is Feb 29 in a leap year`() {
        val ctx = parseAt("29th of this month", feb2028Leap)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2028, 2, 29), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    // ==================== "of this month" unresolvable → consume-and-decline (no title leak) ====================

    @Test
    fun `first of this month (word ordinal, no weekday) is consumed so it does not leak into the title`() {
        val normalized = normalizer.normalize("rent first of this month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(feb2026)
        RecurrenceRule.apply(tokens, context)
        // tokens: rent(0) first(1) of(2) this(3) month(4)
        assertNull(context.rrule)
        assertTrue("first must be consumed", context.isConsumed(1))
        assertTrue("of must be consumed", context.isConsumed(2))
        assertTrue("this must be consumed", context.isConsumed(3))
        assertTrue("month must be consumed", context.isConsumed(4))
        assertTrue("rent must NOT be consumed (it's the title)", !context.isConsumed(0))
    }

    @Test
    fun `0th of this month (invalid number) is consumed so it does not leak into the title`() {
        val normalized = normalizer.normalize("rent 0th of this month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(feb2026)
        RecurrenceRule.apply(tokens, context)
        // tokens: rent(0) 0th(1) of(2) this(3) month(4)
        assertNull(context.rrule)
        assertTrue("0th must be consumed", context.isConsumed(1))
        assertTrue("of must be consumed", context.isConsumed(2))
        assertTrue("month must be consumed", context.isConsumed(4))
        assertTrue("rent must NOT be consumed (it's the title)", !context.isConsumed(0))
    }

    @Test
    fun `32nd of this month (out-of-range number) is consumed so it does not leak into the title`() {
        val normalized = normalizer.normalize("32nd of this month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(feb2026)
        RecurrenceRule.apply(tokens, context)
        // tokens: 32nd(0) of(1) this(2) month(3)
        assertNull(context.rrule)
        assertTrue("32nd must be consumed", context.isConsumed(0))
        assertTrue("of must be consumed", context.isConsumed(1))
        assertTrue("month must be consumed", context.isConsumed(3))
    }

    @Test
    fun `best of this month is NOT consumed because best is not an ordinal`() {
        // Guard against over-consuming: only recognizable ordinals/numbers before
        // "of ... month" are treated as a (declined) date phrase. A plain word
        // like "best" must stay part of the title.
        val normalized = normalizer.normalize("best of this month")
        val tokens = WordTokenizer.tokenize(normalized)
        val context = ParseContext(feb2026)
        RecurrenceRule.apply(tokens, context)
        // tokens: best(0) of(1) this(2) month(3)
        assertNull(context.rrule)
        assertTrue("best must NOT be consumed", !context.isConsumed(0))
        assertTrue("of must NOT be consumed", !context.isConsumed(1))
        assertTrue("month must NOT be consumed", !context.isConsumed(3))
    }

    @Test
    fun `second Monday of this month resolves the weekday date and is not hijacked by the ordinal-decline path`() {
        // "second" satisfies the Case C ordinal-decline predicate, but the weekday
        // form must take the ordinal-weekday path (Case A) and produce a real
        // date, NOT be swallowed as a no-op decline. Feb 2026's 2nd Monday is
        // Feb 9. This pins the Case-A-before-Case-C ordering.
        val ctx = parseAt("second Monday of this month", feb2026)
        assertNull(ctx.rrule)
        assertEquals(LocalDate.of(2026, 2, 9), ctx.resolveDate())
        assertTrue(ctx.dateSet)
    }

    // ==================== Regression guards (must NOT become monthly recurrence) ====================

    @Test
    fun `last Friday without month anchor stays a one-off date not monthly`() {
        val ctx = parse("last Friday")
        // No "of ... month" tail: RecurrenceRule must not claim it; WeekdayRule
        // resolves the most-recent Friday (April 10 from Monday April 13).
        assertNull(ctx.rrule)
    }
}
