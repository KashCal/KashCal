package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Tests for RruleUtils RRULE UNTIL manipulation.
 *
 * Covers:
 * - Adding UNTIL to simple RRULE
 * - Replacing existing UNTIL
 * - Replacing COUNT with UNTIL
 * - DateTime format for timed events
 * - Date-only format for all-day events (RFC 5545 §3.3.10)
 * - COUNT + all-day combinatorial edge case
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
    // Splits a recurring series's RRULE at a chosen instance so the
    // total occurrence count is preserved across the split:
    //   - COUNT branch: master keeps COUNT=pastCount, new series gets
    //     COUNT=(N - pastCount). No UNTIL on either side.
    //   - UNTIL/unbounded branch: master gets UNTIL=splitTime-1; new
    //     series carries the original UNTIL forward (or stays
    //     unbounded by returning null).
    //
    // The helper takes a separate userRrule argument so the new
    // series carries the user's edited recurrence pattern when they
    // changed it as part of "this and future." When userRrule and
    // masterRrule match (no edit), the helper preserves the master's
    // structure verbatim across the split.
    //
    // Returns null on the new series for two cases: (a) unbounded
    // master with no user edit (caller leaves new row unbounded);
    // (b) degenerate COUNT split where pastCount==0 or pastCount>=total
    // (caller should fall back to in-place ALL_EVENTS update on master).

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
        // null new-series is reserved for "user dropped recurrence."
        // No-edit unbounded: new row carries master's rrule verbatim.
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
        // Master is WEEKLY;COUNT=10. User opens an occurrence, changes
        // FREQ to DAILY *and* explicitly sets COUNT=5 (a number that
        // can't be a no-op of the master's 10). That's a deliberate
        // "5 daily occurrences from here" — honor it; do not recompute
        // to preserve master's total.
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
        // Master is WEEKLY;COUNT=10. User opens an occurrence and changes
        // recurrence to DAILY before picking THIS_AND_FUTURE. The new
        // series row should be DAILY (user's edit), not WEEKLY (master's
        // pattern). COUNT splits as remaining: 10 - 4 = 6.
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
        // Master is unbounded WEEKLY. User changes to DAILY and picks
        // THIS_AND_FUTURE. New row should be unbounded DAILY.
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

    // ====== bounds-shape changes (#1, #3, #5, #8) ====================
    //
    // The bounds shape on the new series follows the user's edited
    // rrule, not the master's. If the user dropped COUNT or UNTIL,
    // the new series stays unbounded (or carries only the user's
    // bounds). If the user added UNTIL where master had COUNT, the
    // new series carries UNTIL only — never both.

    @Test
    fun `splitRruleAtTime user replaces master COUNT with UNTIL — new series carries only user UNTIL, no COUNT`() {
        // RFC 5545 §3.3.10 forbids COUNT and UNTIL in the same recur.
        // ical4j's Recur enforces this on parse, so emitting both
        // crashes downstream. The fix: when user supplied UNTIL,
        // drop the COUNT-append branch.
        val (master, newSeries) = RruleUtils.splitRruleAtTime(
            masterRrule = "FREQ=WEEKLY;COUNT=10",
            userRrule = "FREQ=DAILY;UNTIL=20270101T000000Z",
            untilMs = jan15_10am_utc,
            pastCount = 4,
            isAllDay = false,
        )
        assertEquals("FREQ=WEEKLY;COUNT=4", master)
        assertEquals("FREQ=DAILY;UNTIL=20270101T000000Z", newSeries)
        // Defensive — never both.
        assertFalse("new series must not contain COUNT", newSeries!!.contains("COUNT="))
    }

    @Test
    fun `splitRruleAtTime user removes COUNT — new series stays unbounded`() {
        // User opens a COUNT-bounded master and deliberately drops
        // COUNT to make the future tail unbounded. The total-
        // preservation rule must yield to the user's deliberate
        // bounds removal.
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
        // Symmetric: user opens an UNTIL-bounded master and drops
        // the UNTIL to make the future tail unbounded.
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
        // User picked "Does not repeat" on the form's recurrence
        // picker (formState.rrule=null). Caller passes userRrule=null.
        // The new series row should be non-recurring.
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
        // The COUNT branch already honors a deliberate user-set COUNT
        // (test above). UNTIL must be symmetric: when user explicitly
        // picks a different end date, honor it; don't silently
        // override with master's UNTIL.
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
    // splitRruleAtTime's null new-series now means "non-recurring new
    // row" (user dropped recurrence). Degenerate-split detection
    // (where producing master COUNT=0 / new COUNT=0 would be invalid
    // per RFC 5545) is the caller's responsibility via
    // `isDegenerateCountSplit`. Callers check this BEFORE invoking
    // splitRruleAtTime; on true, fall back to in-place ALL_EVENTS
    // update on the master.

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
        // UNTIL-bounded splits never produce COUNT=0; the degenerate
        // case is COUNT-specific.
        assertFalse(RruleUtils.isDegenerateCountSplit("FREQ=WEEKLY;UNTIL=20270101T000000Z", pastCount = 0))
    }

    // ===== rrulesEquivalent =====
    // The scope sheet decides whether the user changed the recurrence
    // rule by comparing the loaded RRULE against the form's. A raw
    // string compare misfires when the picker re-emits a cosmetically
    // different but semantically identical rule (reordered parts, case,
    // whitespace, trailing separators), spuriously disabling save
    // options. rrulesEquivalent compares by meaning instead.

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
        // Different bounds shape is a real semantic difference; we only
        // normalize cosmetics, not COUNT-to-UNTIL conversion.
        assertFalse(RruleUtils.rrulesEquivalent("FREQ=DAILY;COUNT=10", "FREQ=DAILY;UNTIL=20260115T000000Z"))
    }
}
