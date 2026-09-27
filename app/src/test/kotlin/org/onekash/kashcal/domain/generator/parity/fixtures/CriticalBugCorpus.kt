package org.onekash.kashcal.domain.generator.parity.fixtures

import org.onekash.kashcal.domain.generator.parity.RRuleCase
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pool B: one minimal reproducer per expansion-related quirk in
 * [org.onekash.kashcal.domain.generator.LibRecurEngine].
 *
 * LibRecurEngine letters eight quirks, (a) to (i) without an (f). Each case below is the
 * smallest input that hits a real bug the quirk guards against. `ParityCorpusValidationTest`
 * requires exactly 6 cases.
 *
 * Quirks referenced:
 *   (a) all-day events force UTC regardless of TZID
 *   (b) COUNT and UNTIL both present: strip UNTIL (lib-recur rejects a rule with both)
 *   (c) DATE-format UNTIL requires date-only DTSTART (matched by `isAllDay`)
 *   (d) FastForwarded optimization applies only when rangeStart > DTSTART + 30d
 *   (e) MAX_ITERATIONS safety for unbounded SECONDLY/MINUTELY
 *   (g) RDATE/EXDATE inherit DTSTART hour/minute/second for matching
 *   (h) sub-second truncation via seconds-math (second-boundary alignment)
 *   (i) FastForwarded DateTime type must match DTSTART type (all-day vs timed)
 *
 * Quirks (c) and (i) share the DATE-format UNTIL on an all-day event scenario, so one case (#3)
 * covers both. Quirks (e) and (h) share a MINUTELY rule over a bounded range (#5). That leaves
 * 6 cases.
 */
object CriticalBugCorpus {

    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    private fun et(y: Int, m: Int, d: Int, hour: Int = 9, minute: Int = 0, second: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, second, 0, ETZ).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    val cases: List<RRuleCase> = listOf(

        // CRITICAL (a): all-day events must expand in UTC. Expanded in UTC-6, an all-day event
        // stored as Jan 6 00:00 UTC would land on Jan 5. Test: an all-day WEEKLY BYDAY=MO rule
        // with a non-UTC TZID attached must still land on Mondays, not Sundays. The engine
        // forces UTC regardless of case.timezone.
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

        // CRITICAL (b): COUNT and UNTIL must not both appear. lib-recur rejects a rule with both,
        // which expands to 0 occurrences; the engine strips UNTIL so COUNT wins. Test: COUNT=3 with
        // an UNTIL before DTSTART. Without the quirk this yields 0 occurrences; with it, 3 starting
        // from DTSTART.
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

        // CRITICAL (c) + (i): DATE-format UNTIL requires an all-day (date-only) DTSTART to satisfy
        // lib-recur's isAllDay() assertion. A timed DTSTART with a DATE-format UNTIL triggers:
        //   "floating start times with absolute until values not allowed"
        // The engine builds a date-only DateTime when UNTIL is date-only and the event is all-day,
        // and FastForwarded (quirk i) uses the same all-day type to avoid a mismatch. Test: all-day
        // YEARLY with a DATE-format UNTIL and a range far enough after DTSTART that FastForwarded
        // applies; the rule is the one from issue #62 (`OccurrenceGeneratorTest`).
        RRuleCase(
            name = "CRITICAL (c+i): all-day YEARLY with DATE-format UNTIL across FastForwarded window",
            category = "critical",
            rrule = "FREQ=YEARLY;UNTIL=20350927",
            dtstartMs = utcMidnight(2020, 9, 27),
            timezone = null,
            isAllDay = true,
            rdateStrings = null,
            exdateStrings = null,
            // rangeStart far after DTSTART to force the FastForwarded code path (quirk i).
            rangeStartMs = utcMidnight(2030, 1, 1),
            rangeEndMs = utcMidnight(2036, 1, 1),
        ),

        // CRITICAL (d): FastForwarded only when rangeStart is more than 30 days after DTSTART;
        // otherwise DTSTART itself could be lost from the output. Test: rangeStart at midnight
        // the same day, before DTSTART (below the 30-day threshold); DTSTART must still appear
        // in the output. A naive FastForward would skip it.
        RRuleCase(
            name = "CRITICAL (d): FastForwarded NOT applied when rangeStart <30d after DTSTART",
            category = "critical",
            rrule = "FREQ=DAILY;COUNT=10",
            dtstartMs = et(2025, 6, 1, 9, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = et(2025, 6, 1, 0, 0), // same day, below the threshold
            rangeEndMs = et(2025, 6, 30, 0, 0),
        ),

        // CRITICAL (e) + (h): MAX_ITERATIONS safety against unbounded expansion, plus sub-second
        // truncation via seconds-math. A FREQ=MINUTELY rule with no COUNT or UNTIL would expand
        // forever; MAX_ITERATIONS=10000 caps it. The tight range checks normal operation. The
        // DTSTART has no sub-second part, so for (h) this checks only that every returned
        // timestamp is second-aligned (milliseconds = 0).
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

        // CRITICAL (g): RDATE/EXDATE inherit DTSTART's hour, minute and second for matching.
        // Without that, a DATE-format EXDATE ("20250703") against a timed DTSTART (10:00 AM)
        // silently fails to match: the engine looks for an occurrence at 00:00, but occurrences
        // are at 10:00. Test: daily at 10:00 with one EXDATE in the middle; the excluded day must
        // be absent from output. With quirk (g) the EXDATE is matched at 10:00 on that date.
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
