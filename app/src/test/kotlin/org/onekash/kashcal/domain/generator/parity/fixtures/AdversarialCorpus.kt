package org.onekash.kashcal.domain.generator.parity.fixtures

import org.onekash.kashcal.domain.generator.parity.RRuleCase
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pool D: adversarial inputs.
 *
 * Edge cases and malformed inputs meant to surface crashes, hangs or silent wrong answers, not
 * to check RFC correctness (that's Pool A). Both engines should survive them; a divergence here
 * is classified in the parity report.
 *
 * Categories:
 *   - COUNT and UNTIL both present (overlaps Pool B, shaped differently)
 *   - extreme INTERVAL (0, negative, Int.MAX_VALUE)
 *   - malformed RRULE (empty, garbage, missing FREQ, invalid FREQ, injection-shaped value)
 *   - unbounded recurrence against a bounded range
 *   - unbounded SECONDLY and high-frequency MINUTELY (subject to the MAX_ITERATIONS cap)
 *   - UNTIL before DTSTART, including UNTIL at the Unix epoch
 *   - BYDAY with an ordinal no month has (6th Monday)
 *   - BYMONTHDAY=30 across February, Feb 29 in non-leap years
 *   - DST spring-forward and fall-back landing on the transition hour, all-day across DST
 *   - very large COUNT, out-of-range BYSETPOS and BYMONTH
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

        // COUNT and UNTIL both present: both engines strip UNTIL (quirk b in each).
        adv(
            name = "adversarial: COUNT=5 and UNTIL=20250110 both present (ambiguity)",
            rrule = "FREQ=DAILY;COUNT=5;UNTIL=20250110T000000Z",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "RFC 5545 §3.3.10 forbids both — behavior undefined; engines may differ",
        ),

        // INTERVAL=0: the RFC defaults INTERVAL to 1; zero is invalid.
        adv(
            name = "adversarial: INTERVAL=0 (invalid per RFC)",
            rrule = "FREQ=DAILY;INTERVAL=0;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "INTERVAL=0 undefined per RFC; engines may silently use 1 or reject",
        ),

        // INTERVAL=-1: negative, invalid.
        adv(
            name = "adversarial: INTERVAL=-1 (invalid negative)",
            rrule = "FREQ=DAILY;INTERVAL=-1;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
            knownDivergenceReason = "INTERVAL=-1 is invalid; engines may reject or crash",
        ),

        // INTERVAL=Int.MAX_VALUE: legal per grammar but nonsensical; the engine must not overflow.
        adv(
            name = "adversarial: INTERVAL=2147483647 (Int.MAX_VALUE)",
            rrule = "FREQ=YEARLY;INTERVAL=2147483647;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 12, 31),
            knownDivergenceReason = "huge interval may only yield DTSTART within range; overflow risk",
        ),

        // Empty RRULE: both engines return an empty list for a null or blank RRULE before
        // parsing, so no parser runs or can throw.
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

        // Missing FREQ: grammatically invalid.
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

        // Unbounded recurrence against a bounded range: only the range cut bounds it.
        adv(
            name = "adversarial: FREQ=DAILY forever over 1-month range",
            rrule = "FREQ=DAILY",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        // Unbounded FREQ=SECONDLY against a narrow range, subject to the MAX_ITERATIONS cap.
        adv(
            name = "adversarial: FREQ=SECONDLY over 1-hour range (MAX_ITERATIONS territory)",
            rrule = "FREQ=SECONDLY;INTERVAL=60",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1, 10, 0),
            rangeEndMs = utc(2025, 1, 1, 11, 0),
            knownDivergenceReason = "unbounded SECONDLY — lib-recur's MAX_ITERATIONS caps output",
        ),

        // FREQ=MINUTELY unbounded across 1 day: 1440 occurrences, under MAX_ITERATIONS.
        adv(
            name = "adversarial: FREQ=MINUTELY unbounded over 1 day",
            rrule = "FREQ=MINUTELY",
            dtstartMs = utc(2025, 1, 1, 0, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 1, 2),
        ),

        // UNTIL before DTSTART.
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

        // BYDAY ordinal no month has: there is never a 6th Monday.
        adv(
            name = "adversarial: FREQ=MONTHLY BYDAY=6MO (6th Monday never exists)",
            rrule = "FREQ=MONTHLY;BYDAY=6MO;COUNT=3",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
            knownDivergenceReason = "no 6th Monday — engines may yield empty, skip months, or error",
        ),

        // BYMONTHDAY=30 from DTSTART Jan 30, non-leap year: February has no 30th and is skipped.
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

        // DST spring-forward landing on the transition hour. In America/New_York on 2025-03-09
        // the clock jumps from 02:00 EST to 03:00 EDT, so a daily rule at 02:30 hits a local time
        // that doesn't exist on 3/9. Engines may shift forward, skip or error.
        adv(
            name = "adversarial: DAILY at 02:30 landing on DST spring-forward (America/New_York)",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 3, 7, 2, 30), // Fri Mar 7 02:30 EST
            timezone = "America/New_York",
            rangeStartMs = et(2025, 3, 1),
            rangeEndMs = et(2025, 3, 15),
            knownDivergenceReason = "02:30 on 3/9 doesn't exist in local time — engines may shift to 03:30 EDT or skip",
        ),

        // DST fall-back: 01:30 happens twice on 11/2/2025 in America/New_York.
        adv(
            name = "adversarial: DAILY at 01:30 landing on DST fall-back (America/New_York)",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 11, 1, 1, 30),
            timezone = "America/New_York",
            rangeStartMs = et(2025, 11, 1),
            rangeEndMs = et(2025, 11, 10),
            knownDivergenceReason = "01:30 on 11/2 is ambiguous — engines may choose EDT or EST",
        ),

        // All-day across DST: the date shouldn't shift.
        adv(
            name = "adversarial: all-day DAILY across DST (should not shift)",
            rrule = "FREQ=DAILY;COUNT=30",
            dtstartMs = utcMidnight(2025, 3, 5),
            timezone = null,
            isAllDay = true,
            rangeStartMs = utcMidnight(2025, 3, 1),
            rangeEndMs = utcMidnight(2025, 4, 5),
        ),

        // COUNT=10000: large but within MAX_ITERATIONS, to check performance.
        adv(
            name = "adversarial: FREQ=DAILY COUNT=10000 (near MAX_ITERATIONS ceiling)",
            rrule = "FREQ=DAILY;COUNT=10000",
            dtstartMs = utc(2020, 1, 1, 10, 0),
            rangeStartMs = utc(2020, 1, 1),
            rangeEndMs = utc(2048, 1, 1),
            knownDivergenceReason = "at/near lib-recur's MAX_ITERATIONS=10000 safety cap",
        ),

        // Negative BYSETPOS beyond the candidate count.
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
