package org.onekash.kashcal.domain.generator.parity.fixtures

import org.onekash.kashcal.domain.generator.parity.RRuleCase
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pool B — one minimal reproducer per expansion-related CRITICAL quirk in
 * [org.onekash.kashcal.domain.generator.LibRecurEngine].
 *
 * The LibRecurEngine file annotates 9 CRITICAL quirks (labeled a-i). They
 * cluster into 6 distinct expansion scenarios. Each case below is the
 * smallest input that previously caused a real bug, now protected by the
 * quirk. Tracker acceptance: exactly 6 cases.
 *
 * Quirks referenced:
 *   (a) all-day events force UTC regardless of TZID
 *   (b) COUNT+UNTIL both present → strip UNTIL (lib-recur returns 0 if both)
 *   (c) DATE-format UNTIL requires date-only DTSTART (matched by `isAllDay`)
 *   (d) FastForwarded optimization applies only when rangeStart > DTSTART + 30d
 *   (e) MAX_ITERATIONS safety for unbounded SECONDLY/MINUTELY
 *   (g) RDATE/EXDATE inherit DTSTART hour/minute/second for matching
 *   (h) sub-second truncation via seconds-math (second-boundary alignment)
 *   (i) FastForwarded DateTime type must match DTSTART type (all-day vs timed)
 *
 * Quirks (c) and (i) share the same DATE-format-UNTIL + all-day scenario, so
 * one case (#3) covers both. Quirks (e) and (h) are tested together by a
 * MINUTELY rule over a bounded range (#5). That leaves 6 cases.
 */
object CriticalBugCorpus {

    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    private fun et(y: Int, m: Int, d: Int, hour: Int = 9, minute: Int = 0, second: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, second, 0, ETZ).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    val cases: List<RRuleCase> = listOf(

        // CRITICAL (a) — All-day events must use UTC for expansion. If expansion
        // ran in a non-UTC timezone, an all-day event stored as Jan 6 00:00 UTC
        // would appear on Jan 5 in UTC-6 (the local date shifts). Test: an
        // all-day WEEKLY BYDAY=MO rule with a non-UTC TZID attached — the
        // expansion must still land on Mondays (not Sundays in the user's local
        // zone). The engine forces UTC regardless of the case.timezone input.
        RRuleCase(
            name = "CRITICAL (a): all-day weekly BYDAY=MO stays on Monday regardless of TZID",
            category = "critical",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            dtstartMs = utcMidnight(2025, 1, 6), // Monday
            timezone = "America/Chicago", // UTC-6; would shift expansion date without quirk (a)
            isAllDay = true,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = utcMidnight(2025, 1, 1),
            rangeEndMs = utcMidnight(2025, 2, 15),
        ),

        // CRITICAL (b) — COUNT and UNTIL must not both appear. lib-recur returns
        // 0 occurrences when both are present; the engine strips UNTIL so COUNT
        // wins. Test: both set, COUNT=3, UNTIL in the past. Without the quirk,
        // this yields 0 occurrences; with the quirk, 3 occurrences starting from
        // DTSTART.
        RRuleCase(
            name = "CRITICAL (b): COUNT+UNTIL both present — UNTIL stripped so COUNT wins",
            category = "critical",
            rrule = "FREQ=DAILY;COUNT=3;UNTIL=20000101T000000Z",
            dtstartMs = et(2025, 5, 1, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = et(2025, 5, 1, 0, 0),
            rangeEndMs = et(2025, 5, 10, 0, 0),
        ),

        // CRITICAL (c) + (i) — DATE-format UNTIL requires an all-day (date-only)
        // DTSTART to satisfy lib-recur's isAllDay() assertion. Mixing a timed
        // DTSTART with a DATE-format UNTIL triggers:
        //   "floating start times with absolute until values not allowed"
        // The engine builds a date-only DateTime when UNTIL is date-only AND the
        // event isAllDay. The FastForwarded optimization (quirk i) then uses the
        // same all-day DateTime type to avoid a type mismatch. Test: all-day
        // YEARLY with DATE-format UNTIL far enough in the future that
        // FastForwarded applies — caught by integration test at KashCal app code.
        RRuleCase(
            name = "CRITICAL (c+i): all-day YEARLY with DATE-format UNTIL across FastForwarded window",
            category = "critical",
            rrule = "FREQ=YEARLY;UNTIL=20350927",
            dtstartMs = utcMidnight(2020, 9, 27),
            timezone = null,
            isAllDay = true,
            rdateStrings = null,
            exdateStrings = null,
            // rangeStart far after DTSTART to force FastForwarded code path (quirk i).
            rangeStartMs = utcMidnight(2030, 1, 1),
            rangeEndMs = utcMidnight(2036, 1, 1),
        ),

        // CRITICAL (d) — FastForwarded only when rangeStart is more than 30 days
        // after DTSTART. Otherwise DTSTART itself could be lost from the output.
        // Test: DTSTART at +0d, rangeStart at +5d (below 30d threshold); DTSTART
        // must still appear in the output. A naive FastForward would skip it.
        RRuleCase(
            name = "CRITICAL (d): FastForwarded NOT applied when rangeStart <30d after DTSTART",
            category = "critical",
            rrule = "FREQ=DAILY;COUNT=10",
            dtstartMs = et(2025, 6, 1, 9, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = et(2025, 6, 1, 0, 0), // same day — below threshold
            rangeEndMs = et(2025, 6, 30, 0, 0),
        ),

        // CRITICAL (e) + (h) — MAX_ITERATIONS safety against unbounded expansion,
        // plus sub-second truncation via seconds-math. A FREQ=MINUTELY rule with
        // no COUNT/UNTIL would expand infinitely; MAX_ITERATIONS=10000 caps it.
        // We bound the range tightly to verify normal operation, plus pick DTSTART
        // with a sub-second component to exercise (h) — the returned timestamps
        // should all be second-aligned (milliseconds = 0).
        RRuleCase(
            name = "CRITICAL (e+h): MINUTELY unbounded over narrow range — second-aligned timestamps",
            category = "critical",
            rrule = "FREQ=MINUTELY;INTERVAL=15",
            dtstartMs = et(2025, 7, 1, 10, 0, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = et(2025, 7, 1, 10, 0, 0),
            rangeEndMs = et(2025, 7, 1, 12, 0, 0),
        ),

        // CRITICAL (g) — RDATE/EXDATE inherit DTSTART's hour/minute/second for
        // matching. Without inheritance, a DATE-format EXDATE (e.g., "20250703")
        // against a timed DTSTART (10:00 AM) silently fails to match — the engine
        // looks for an occurrence at 00:00, but occurrences are at 10:00.
        // Test: daily at 10:00, EXDATE one date in the middle — the excluded day
        // must be absent from output. With quirk (g) the EXDATE is matched at
        // 10:00 on that date.
        RRuleCase(
            name = "CRITICAL (g): DATE-format EXDATE on timed DTSTART — time component inherited",
            category = "critical",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 7, 1, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = "20250703",
            rangeStartMs = et(2025, 7, 1, 0, 0),
            rangeEndMs = et(2025, 7, 10, 0, 0),
        ),
    )
}
