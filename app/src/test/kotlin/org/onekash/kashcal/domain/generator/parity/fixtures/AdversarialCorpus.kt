package org.onekash.kashcal.domain.generator.parity.fixtures

import org.onekash.kashcal.domain.generator.parity.RRuleCase
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pool D — adversarial inputs.
 *
 * Intentional edge cases and malformed inputs that stress-test engine
 * robustness. The goal isn't correctness per the RFC (that's Pool A) — it's
 * to surface crashes, hangs, or silent wrong-answers under pathological
 * inputs. Both engines should survive; divergences here become important
 * classification material for migration risk.
 *
 * Categories covered:
 *   - COUNT+UNTIL both present (redundant with Pool B but differently shaped)
 *   - extreme INTERVAL (0, negative, Int.MAX_VALUE)
 *   - malformed RRULE (empty, garbage, missing FREQ, invalid FREQ, injection pattern)
 *   - unbounded recurrence against a bounded range
 *   - SECONDLY / high-frequency MINUTELY (MAX_ITERATIONS territory)
 *   - UNTIL in the past
 *   - UNTIL before DTSTART
 *   - BYDAY invalid ordinal (6th Monday doesn't exist)
 *   - Feb 30 / Feb 29 leap vs non-leap
 *   - DST spring-forward / fall-back landing ON the transition hour
 *   - cross-year spans, very-large COUNT
 */
object AdversarialCorpus {

    private val UTC: ZoneId = ZoneId.of("UTC")
    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    private fun utc(y: Int, m: Int, d: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

    private fun et(y: Int, m: Int, d: Int, hour: Int = 9, minute: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, 0, 0, ETZ).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun adv(
        name: String,
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        timezone: String? = "UTC",
        isAllDay: Boolean = false,
        rdateStrings: String? = null,
        exdateStrings: String? = null,
        knownDivergenceReason: String? = null,
    ): RRuleCase = RRuleCase(
        name = name,
        category = "adversarial",
        rrule = rrule,
        dtstartMs = dtstartMs,
        timezone = timezone,
        isAllDay = isAllDay,
        rdateStrings = rdateStrings,
        exdateStrings = exdateStrings,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        knownDivergenceReason = knownDivergenceReason,
    )

    val cases: List<RRuleCase> = listOf(

        // COUNT+UNTIL both present — lib-recur's CRITICAL (b) strips UNTIL; ical4j may honor both.
        adv(
            name = "adversarial: COUNT=5 and UNTIL=20250110 both present (ambiguity)",
            rrule = "FREQ=DAILY;COUNT=5;UNTIL=20250110T000000Z",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "RFC 5545 §3.3.10 forbids both — behavior undefined; engines may differ",
        ),

        // INTERVAL=0 — RFC says INTERVAL defaults to 1, zero is invalid.
        adv(
            name = "adversarial: INTERVAL=0 (invalid per RFC)",
            rrule = "FREQ=DAILY;INTERVAL=0;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "INTERVAL=0 undefined per RFC; engines may silently use 1 or reject",
        ),

        // INTERVAL=-1 — negative, invalid.
        adv(
            name = "adversarial: INTERVAL=-1 (invalid negative)",
            rrule = "FREQ=DAILY;INTERVAL=-1;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "INTERVAL=-1 is invalid; engines may reject or crash",
        ),

        // INTERVAL=Int.MAX_VALUE — legal per grammar but nonsensical; engine must not overflow.
        adv(
            name = "adversarial: INTERVAL=2147483647 (Int.MAX_VALUE)",
            rrule = "FREQ=YEARLY;INTERVAL=2147483647;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 12, 31),
            knownDivergenceReason = "huge interval may only yield DTSTART within range; overflow risk",
        ),

        // Empty RRULE — LibRecurEngine returns emptyList() on null/blank; ical4j's parse may throw.
        adv(
            name = "adversarial: empty RRULE",
            rrule = "",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "empty RRULE: LibRecurEngine returns [] early; ical4j may expand to just DTSTART",
        ),

        // Garbage RRULE.
        adv(
            name = "adversarial: garbage RRULE text",
            rrule = "THIS IS NOT AN RRULE",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "unparseable RRULE — both engines should gracefully yield empty or DTSTART-only",
        ),

        // Missing FREQ — grammatically invalid.
        adv(
            name = "adversarial: RRULE missing FREQ",
            rrule = "COUNT=5;BYDAY=MO",
            dtstartMs = utc(2025, 1, 6, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "FREQ is REQUIRED; engines must reject or no-op",
        ),

        // Invalid FREQ value.
        adv(
            name = "adversarial: FREQ=FORTNIGHTLY (not in enum)",
            rrule = "FREQ=FORTNIGHTLY;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "FORTNIGHTLY not in RFC enum; both engines should reject",
        ),

        // SQL-injection-shaped value to verify engines don't interpret specials.
        adv(
            name = "adversarial: RRULE with SQL-injection-shaped payload",
            rrule = "FREQ=DAILY;COUNT=3;UNTIL=' OR 1=1--",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "parser must treat UNTIL value as opaque string, not evaluate it",
        ),

        // Unbounded recurrence against bounded range — relies on range cut, not COUNT/UNTIL.
        adv(
            name = "adversarial: FREQ=DAILY forever over 1-month range",
            rrule = "FREQ=DAILY",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        // FREQ=SECONDLY against a narrow range — MAX_ITERATIONS territory.
        adv(
            name = "adversarial: FREQ=SECONDLY over 1-hour range (MAX_ITERATIONS territory)",
            rrule = "FREQ=SECONDLY;INTERVAL=60",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1, 10, 0),
            rangeEndMs = utc(2025, 1, 1, 11, 0),
            knownDivergenceReason = "unbounded SECONDLY — lib-recur's MAX_ITERATIONS caps output",
        ),

        // FREQ=MINUTELY unbounded across 1 day — 1440 iterations, under MAX_ITERATIONS.
        adv(
            name = "adversarial: FREQ=MINUTELY unbounded over 1 day",
            rrule = "FREQ=MINUTELY",
            dtstartMs = utc(2025, 1, 1, 0, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 1, 2),
        ),

        // UNTIL in the past.
        adv(
            name = "adversarial: UNTIL before DTSTART yields empty expansion",
            rrule = "FREQ=DAILY;UNTIL=20240101T000000Z",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        // UNTIL way in the past relative to range.
        adv(
            name = "adversarial: UNTIL=19700101 older than Unix epoch boundary",
            rrule = "FREQ=DAILY;UNTIL=19700101T000000Z",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        // BYDAY invalid ordinal — 6th Monday doesn't exist in any month.
        adv(
            name = "adversarial: FREQ=MONTHLY BYDAY=6MO (6th Monday never exists)",
            rrule = "FREQ=MONTHLY;BYDAY=6MO;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
            knownDivergenceReason = "no 6th Monday — engines may yield empty, skip months, or error",
        ),

        // BYMONTHDAY=30 with DTSTART Feb 28 on non-leap year — "Feb 30" would skip Feb.
        adv(
            name = "adversarial: BYMONTHDAY=30 across Feb (non-leap)",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=30;COUNT=6",
            dtstartMs = utc(2025, 1, 30, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        // Feb 29 anniversary in non-leap year range.
        adv(
            name = "adversarial: FREQ=YEARLY BYMONTH=2 BYMONTHDAY=29 across 3 non-leap years",
            rrule = "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29",
            dtstartMs = utc(2020, 2, 29, 10, 0), // last leap before range
            rangeStartMs = utc(2021, 1, 1),
            rangeEndMs = utc(2024, 1, 1), // three non-leap years: 2021, 2022, 2023
            knownDivergenceReason = "Feb 29 in non-leap years — both engines should skip",
        ),

        // DST spring-forward landing on the transition hour. In America/New_York on
        // 2025-03-09 the clock jumps from 02:00 EST to 03:00 EDT. A daily rule at
        // 02:30 would normally hit a non-existent local time on 3/9. Engines vary
        // on how they handle this (shift forward, skip, error).
        adv(
            name = "adversarial: DAILY at 02:30 landing on DST spring-forward (America/New_York)",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 3, 7, 2, 30), // Fri Mar 7 02:30 EST
            timezone = "America/New_York",
            rangeStartMs = et(2025, 3, 1),
            rangeEndMs = et(2025, 3, 15),
            knownDivergenceReason = "02:30 on 3/9 doesn't exist in local time — engines may shift to 03:30 EDT or skip",
        ),

        // DST fall-back — 01:30 happens twice on 11/2/2025 in America/New_York.
        adv(
            name = "adversarial: DAILY at 01:30 landing on DST fall-back (America/New_York)",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 11, 1, 1, 30),
            timezone = "America/New_York",
            rangeStartMs = et(2025, 11, 1),
            rangeEndMs = et(2025, 11, 10),
            knownDivergenceReason = "01:30 on 11/2 is ambiguous — engines may choose EDT or EST",
        ),

        // All-day across DST — the date shouldn't shift.
        adv(
            name = "adversarial: all-day DAILY across DST (should not shift)",
            rrule = "FREQ=DAILY;COUNT=30",
            dtstartMs = utcMidnight(2025, 3, 5),
            timezone = null,
            isAllDay = true,
            rangeStartMs = utcMidnight(2025, 3, 1),
            rangeEndMs = utcMidnight(2025, 4, 5),
        ),

        // COUNT=10000 — large but within MAX_ITERATIONS, to check performance.
        adv(
            name = "adversarial: FREQ=DAILY COUNT=10000 (near MAX_ITERATIONS ceiling)",
            rrule = "FREQ=DAILY;COUNT=10000",
            dtstartMs = utc(2020, 1, 1, 10, 0),
            rangeStartMs = utc(2020, 1, 1),
            rangeEndMs = utc(2048, 1, 1),
            knownDivergenceReason = "at/near lib-recur's MAX_ITERATIONS=10000 safety cap",
        ),

        // Negative BYSETPOS beyond range.
        adv(
            name = "adversarial: BYSETPOS=-100 exceeds candidates (should yield empty per month)",
            rrule = "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-100;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
            knownDivergenceReason = "BYSETPOS=-100 has no valid candidate in any month; engines may loop",
        ),

        // BYMONTH=13 (out of range).
        adv(
            name = "adversarial: BYMONTH=13 (invalid month)",
            rrule = "FREQ=YEARLY;BYMONTH=13;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2028, 1, 1),
            knownDivergenceReason = "BYMONTH=13 invalid; engines may reject or silently drop",
        ),
    )
}
