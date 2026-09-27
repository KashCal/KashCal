package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Tests [RruleUtils].
 *
 * - [RruleUtils.addUntilToRrule]: adds UNTIL, replaces an UNTIL or a COUNT, in the date-time
 *   form for a timed series and the DATE form for an all-day one (RFC 5545 §3.3.10).
 * - [RruleUtils.formatUntilDate]: both forms.
 * - [RruleUtils.splitRruleAtTime], [RruleUtils.isDegenerateCountSplit] and
 *   [RruleUtils.rrulesEquivalent], each in its section below.
 */
class RruleUtilsTest {

    // Fixed timestamp: 2026-01-15 10:00:00 UTC
    private val jan15_10am_utc: Long = run {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    @Test
    fun `addUntilToRrule adds UNTIL to simple RRULE`() {
        val result = RruleUtils.addUntilToRrule("FREQ=WEEKLY", jan15_10am_utc)
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `addUntilToRrule replaces existing UNTIL`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;UNTIL=20250101T000000Z", jan15_10am_utc
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `addUntilToRrule replaces COUNT with UNTIL`() {
        val result = RruleUtils.addUntilToRrule("FREQ=WEEKLY;COUNT=10", jan15_10am_utc)
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `formatUntilDate formats datetime for timed events`() {
        val result = RruleUtils.formatUntilDate(jan15_10am_utc, isAllDay = false)
        assertEquals("20260115T100000Z", result)
    }

    @Test
    fun `formatUntilDate formats date-only for all-day events`() {
        val result = RruleUtils.formatUntilDate(jan15_10am_utc, isAllDay = true)
        assertEquals("20260115", result)
    }

    @Test
    fun `addUntilToRrule uses date-only format for all-day`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;BYDAY=MO", jan15_10am_utc, isAllDay = true
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260115", result)
    }

    @Test
    fun `addUntilToRrule replaces COUNT with date-only UNTIL for all-day`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;COUNT=10", jan15_10am_utc, isAllDay = true
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115", result)
    }

    @Test
    fun `addUntilToRrule handles UNTIL with BYDAY`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20250601T000000Z", jan15_10am_utc
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `addUntilToRrule handles COUNT in middle of RRULE`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=DAILY;COUNT=5;INTERVAL=2", jan15_10am_utc
        )
        // COUNT removed, UNTIL appended
        assertTrue(result.contains("UNTIL=20260115T100000Z"))
        assertFalse(result.contains("COUNT"))
    }

    // ====== splitRruleAtTime ======================================
    //
    // With no edit (userRrule == masterRrule):
    //   - COUNT master: master gets COUNT=pastCount, new series COUNT=(N - pastCount), no
    //     UNTIL on either side.
    //   - UNTIL or unbounded master: master gets UNTIL=untilMs; the new series keeps the
    //     master's rule, its UNTIL or its lack of one.
    // The new series is null only when userRrule is null; the rules for an edited rule are
    // in the sections below and on the function.

    @Test
    fun `splitRruleAtTime COUNT branch keeps total count split between halves`() {
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY;COUNT=10",
            userRrule = "FREQ=DAILY;COUNT=10",
            untilMs = jan15_10am_utc,
            pastCount = 3,
            isAllDay = false,
        )
        assertEquals("FREQ=DAILY;COUNT=3", master)
        assertEquals("FREQ=DAILY;COUNT=7", newSeries)
    }

    @Test
    fun `splitRruleAtTime COUNT branch preserves BYDAY and INTERVAL`() {
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10",
            userRrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10",
            untilMs = jan15_10am_utc,
            pastCount = 4,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=4", master)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=6", newSeries)
    }

    @Test
    fun `splitRruleAtTime UNTIL branch truncates master and preserves original UNTIL on new series`() {
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;UNTIL=20270101T000000Z",
            userRrule = "FREQ=WEEKLY;UNTIL=20270101T000000Z",
            untilMs = jan15_10am_utc,
            pastCount = 0, // ignored on UNTIL branch
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", master)
        assertEquals("FREQ=WEEKLY;UNTIL=20270101T000000Z", newSeries)
    }

    @Test
    fun `splitRruleAtTime unbounded RRULE without user edit truncates master and carries rrule on new series`() {
        // A null new series is reserved for dropped recurrence; an unbounded rule with no
        // edit carries over verbatim.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY",
            userRrule = "FREQ=DAILY",
            untilMs = jan15_10am_utc,
            pastCount = 0,
            isAllDay = false,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260115T100000Z", master)
        assertEquals("FREQ=DAILY", newSeries)
    }

    @Test
    fun `splitRruleAtTime all-day COUNT branch ignores all-day flag for new series`() {
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY;COUNT=10",
            userRrule = "FREQ=DAILY;COUNT=10",
            untilMs = jan15_10am_utc,
            pastCount = 5,
            isAllDay = true,
        )
        assertEquals("FREQ=DAILY;COUNT=5", master)
        assertEquals("FREQ=DAILY;COUNT=5", newSeries)
        assertFalse("master should not include UNTIL", master.contains("UNTIL"))
    }

    @Test
    fun `splitRruleAtTime all-day unbounded uses date-only UNTIL on master`() {
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY",
            userRrule = "FREQ=DAILY",
            untilMs = jan15_10am_utc,
            pastCount = 0,
            isAllDay = true,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260115", master)
        assertEquals("FREQ=DAILY", newSeries)
    }

    @Test
    fun `splitRruleAtTime never emits both COUNT and UNTIL`() {
        val rules = listOf(
            "FREQ=DAILY;COUNT=10",
            "FREQ=WEEKLY;UNTIL=20270101T000000Z",
            "FREQ=DAILY",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=8",
        )
        for (input in rules) {
            val (master, newSeries) = RruleUtils.splitRruleAtTime(
                masterRrule = input,
                userRrule = input,
                untilMs = jan15_10am_utc,
                pastCount = 2,
                isAllDay = false,
            )
            val masterHasBoth = master.contains("COUNT=") && master.contains("UNTIL=")
            assertFalse("master '$master' has both COUNT and UNTIL (input=$input)", masterHasBoth)
            if (newSeries != null) {
                val newHasBoth = newSeries.contains("COUNT=") && newSeries.contains("UNTIL=")
                assertFalse("new '$newSeries' has both COUNT and UNTIL (input=$input)", newHasBoth)
            }
        }
    }

    // ====== user-rrule preservation across the split ===============

    @Test
    fun `splitRruleAtTime COUNT user explicitly sets a different COUNT — new row carries user's COUNT verbatim`() {
        // The user changes WEEKLY;COUNT=10 to DAILY and sets COUNT=5, which differs from the
        // master's 10, so it means "5 daily occurrences from here": kept as is, not
        // recomputed to preserve the master's total.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            userRrule = "FREQ=DAILY;COUNT=5",
            untilMs = jan15_10am_utc,
            pastCount = 2,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=2", master)
        assertEquals("FREQ=DAILY;COUNT=5", newSeries)
    }

    @Test
    fun `splitRruleAtTime COUNT user changes FREQ — new row carries user's FREQ with remaining COUNT`() {
        // The user changes WEEKLY;COUNT=10 to DAILY, keeping COUNT=10, and picks this and
        // future. The new series is DAILY with the remaining count, 10 - 4 = 6.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;COUNT=10",
            userRrule = "FREQ=DAILY;COUNT=10",
            untilMs = jan15_10am_utc,
            pastCount = 4,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;COUNT=4", master)
        assertEquals("FREQ=DAILY;COUNT=6", newSeries)
    }

    @Test
    fun `splitRruleAtTime unbounded user edit — new row carries user's rrule verbatim`() {
        // Unbounded WEEKLY edited to DAILY: the new series is unbounded DAILY.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY",
            userRrule = "FREQ=DAILY",
            untilMs = jan15_10am_utc,
            pastCount = 0,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", master)
        assertEquals("FREQ=DAILY", newSeries)
    }

    // ====== bounds-shape changes ====================================
    //
    // The new series' bounds follow the user's edited rule, not the master's: a dropped
    // COUNT or UNTIL leaves it unbounded, a user UNTIL is kept (on a COUNT master, UNTIL
    // only, never both), and dropped recurrence gives a null new series.

    @Test
    fun `splitRruleAtTime user replaces master COUNT with UNTIL — new series carries only user UNTIL, no COUNT`() {
        // RFC 5545 §3.3.10: UNTIL and COUNT MUST NOT occur in the same recur. A user rule
        // without COUNT is taken verbatim, so no COUNT is added to the user's UNTIL.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;COUNT=10",
            userRrule = "FREQ=DAILY;UNTIL=20270101T000000Z",
            untilMs = jan15_10am_utc,
            pastCount = 4,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;COUNT=4", master)
        assertEquals("FREQ=DAILY;UNTIL=20270101T000000Z", newSeries)
        assertFalse("new series must not contain COUNT", newSeries!!.contains("COUNT="))
    }

    @Test
    fun `splitRruleAtTime user removes COUNT — new series stays unbounded`() {
        // The user drops COUNT to make the future unbounded; keeping the series total
        // yields to that.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY;COUNT=10",
            userRrule = "FREQ=DAILY",
            untilMs = jan15_10am_utc,
            pastCount = 3,
            isAllDay = false,
        )
        assertEquals("FREQ=DAILY;COUNT=3", master)
        assertEquals("FREQ=DAILY", newSeries)
    }

    @Test
    fun `splitRruleAtTime user removes UNTIL — new series stays unbounded`() {
        // The same for an UNTIL master: the user drops UNTIL to make the future unbounded.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;UNTIL=20270101T000000Z",
            userRrule = "FREQ=DAILY",
            untilMs = jan15_10am_utc,
            pastCount = 0,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", master)
        assertEquals("FREQ=DAILY", newSeries)
    }

    @Test
    fun `splitRruleAtTime user dropped recurrence COUNT master — new series is non-recurring`() {
        // The user picked "Does not repeat" (formState.rrule = null), so the caller passes
        // userRrule = null and the new series doesn't repeat.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=DAILY;COUNT=10",
            userRrule = null,
            untilMs = jan15_10am_utc,
            pastCount = 3,
            isAllDay = false,
        )
        assertEquals("FREQ=DAILY;COUNT=3", master)
        assertEquals(null, newSeries)
    }

    @Test
    fun `splitRruleAtTime user dropped recurrence UNTIL master — new series is non-recurring`() {
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;UNTIL=20270101T000000Z",
            userRrule = null,
            untilMs = jan15_10am_utc,
            pastCount = 0,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", master)
        assertEquals(null, newSeries)
    }

    @Test
    fun `splitRruleAtTime user picks earlier UNTIL — new series honors user value`() {
        // Like a user-set COUNT, a user-set UNTIL is kept and never silently replaced with
        // the master's UNTIL.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;UNTIL=20270101T000000Z",
            userRrule = "FREQ=DAILY;UNTIL=20260601T000000Z",
            untilMs = jan15_10am_utc,
            pastCount = 0,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", master)
        assertEquals("FREQ=DAILY;UNTIL=20260601T000000Z", newSeries)
    }

    // ====== degenerate-split detection (separate predicate) ========
    //
    // A split that would give the master or the new series COUNT=0 is caught by
    // isDegenerateCountSplit, which callers check before splitRruleAtTime; on true they
    // update the master in place as an "all events" edit.

    @Test
    fun `isDegenerateCountSplit COUNT pastCount=0 is degenerate`() {
        assertTrue(RruleUtils.isDegenerateCountSplit("FREQ=WEEKLY;COUNT=3", pastCount = 0))
    }

    @Test
    fun `isDegenerateCountSplit COUNT pastCount equals total is degenerate`() {
        assertTrue(RruleUtils.isDegenerateCountSplit("FREQ=WEEKLY;COUNT=3", pastCount = 3))
    }

    @Test
    fun `isDegenerateCountSplit COUNT pastCount greater than total is degenerate`() {
        assertTrue(RruleUtils.isDegenerateCountSplit("FREQ=WEEKLY;COUNT=3", pastCount = 5))
    }

    @Test
    fun `isDegenerateCountSplit non-degenerate COUNT split returns false`() {
        assertFalse(RruleUtils.isDegenerateCountSplit("FREQ=WEEKLY;COUNT=10", pastCount = 4))
    }

    @Test
    fun `isDegenerateCountSplit unbounded rrule is never degenerate`() {
        assertFalse(RruleUtils.isDegenerateCountSplit("FREQ=DAILY", pastCount = 0))
    }

    @Test
    fun `isDegenerateCountSplit UNTIL-bounded rrule is never degenerate`() {
        // An UNTIL split never produces COUNT=0; the degenerate case is COUNT-only.
        assertFalse(RruleUtils.isDegenerateCountSplit("FREQ=WEEKLY;UNTIL=20270101T000000Z", pastCount = 0))
    }

    // ===== rrulesEquivalent =====
    // The picker can re-emit an unchanged rule in another form: reordered parts or list
    // values, other case, whitespace, a trailing separator, an RRULE: prefix. A string
    // compare would read that as a user change (why it matters is on the function).

    @Test
    fun `rrulesEquivalent treats identical strings as equal`() {
        assertTrue(RruleUtils.rrulesEquivalent("FREQ=WEEKLY;COUNT=10", "FREQ=WEEKLY;COUNT=10"))
    }

    @Test
    fun `rrulesEquivalent treats both-null as equal`() {
        assertTrue(RruleUtils.rrulesEquivalent(null, null))
    }

    @Test
    fun `rrulesEquivalent treats null vs non-null as different`() {
        assertFalse(RruleUtils.rrulesEquivalent(null, "FREQ=WEEKLY"))
        assertFalse(RruleUtils.rrulesEquivalent("FREQ=WEEKLY", null))
    }

    @Test
    fun `rrulesEquivalent ignores part ordering`() {
        assertTrue(RruleUtils.rrulesEquivalent("FREQ=WEEKLY;BYDAY=MO,TU", "BYDAY=MO,TU;FREQ=WEEKLY"))
    }

    @Test
    fun `rrulesEquivalent ignores key case and RRULE prefix`() {
        assertTrue(RruleUtils.rrulesEquivalent("FREQ=WEEKLY;COUNT=10", "RRULE:freq=WEEKLY;count=10"))
    }

    @Test
    fun `rrulesEquivalent ignores surrounding whitespace and trailing separator`() {
        assertTrue(RruleUtils.rrulesEquivalent("FREQ=WEEKLY;COUNT=10", " FREQ=WEEKLY; COUNT=10; "))
    }

    @Test
    fun `rrulesEquivalent ignores BYDAY value ordering`() {
        assertTrue(RruleUtils.rrulesEquivalent("FREQ=WEEKLY;BYDAY=MO,WE,FR", "FREQ=WEEKLY;BYDAY=FR,MO,WE"))
    }

    @Test
    fun `rrulesEquivalent reports a real frequency change as different`() {
        assertFalse(RruleUtils.rrulesEquivalent("FREQ=WEEKLY;COUNT=10", "FREQ=DAILY;COUNT=10"))
    }

    @Test
    fun `rrulesEquivalent reports a real UNTIL change as different`() {
        assertFalse(
            RruleUtils.rrulesEquivalent(
                "FREQ=WEEKLY;UNTIL=20271231T000000Z",
                "FREQ=WEEKLY;UNTIL=20261231T000000Z",
            )
        )
    }

    @Test
    fun `rrulesEquivalent does not equate COUNT with UNTIL`() {
        // Different bounds are a real change; only cosmetics are normalized, and COUNT is
        // never converted to UNTIL.
        assertFalse(RruleUtils.rrulesEquivalent("FREQ=DAILY;COUNT=10", "FREQ=DAILY;UNTIL=20260115T000000Z"))
    }
}
