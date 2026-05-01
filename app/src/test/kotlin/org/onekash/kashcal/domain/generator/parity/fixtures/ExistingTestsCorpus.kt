package org.onekash.kashcal.domain.generator.parity.fixtures

import org.onekash.kashcal.domain.generator.parity.RRuleCase
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pool C — inputs adapted from existing test suites.
 *
 * App-side sources:
 *   - OccurrenceGeneratorTest
 *   - OccurrenceGeneratorAdvancedTest
 *   - OccurrenceGeneratorEdgeCaseTest
 *   - OccurrenceGeneratorRfc5545ComplianceTest
 *   - RRuleAdversarialTest
 *   - RRuleDSTInteractionTest
 *   - RruleEdgeCasesTest
 *
 * Library-side sources (icaldav-core):
 *   - RRuleExpanderTest
 *   - RRuleExpanderComprehensiveTest
 *   - RRuleExpanderAdversarialTest
 *   - RRuleExpanderLeapYearTest
 *   - RRuleExpanderRdateTest
 *
 * These cases carry ONLY inputs — no `rfcExpected`. Expected outputs emerge
 * from running both engines in chunk 4; divergences here become classification
 * material, not failures.
 */
object ExistingTestsCorpus {

    private val UTC: ZoneId = ZoneId.of("UTC")
    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    private fun utc(y: Int, m: Int, d: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

    private fun et(y: Int, m: Int, d: Int, hour: Int = 9, minute: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, 0, 0, ETZ).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun existingCase(
        name: String,
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        timezone: String? = "UTC",
        isAllDay: Boolean = false,
        rdateStrings: String? = null,
        exdateStrings: String? = null,
    ): RRuleCase = RRuleCase(
        name = name,
        category = "existing",
        rrule = rrule,
        dtstartMs = dtstartMs,
        timezone = timezone,
        isAllDay = isAllDay,
        rdateStrings = rdateStrings,
        exdateStrings = exdateStrings,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
    )

    val cases: List<RRuleCase> = listOf(

        // ========== App-side: OccurrenceGeneratorTest patterns ==========

        existingCase(
            name = "existing: FREQ=DAILY COUNT=5 UTC",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = utc(2025, 1, 15, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        existingCase(
            name = "existing: FREQ=DAILY INTERVAL=3 over 30 days",
            rrule = "FREQ=DAILY;INTERVAL=3",
            dtstartMs = utc(2025, 1, 15, 10, 0),
            rangeStartMs = utc(2025, 1, 15),
            rangeEndMs = utc(2025, 2, 15),
        ),

        existingCase(
            name = "existing: FREQ=DAILY UNTIL=20250110T235959Z",
            rrule = "FREQ=DAILY;UNTIL=20250110T235959Z",
            dtstartMs = utc(2025, 1, 5, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 1, 15),
        ),

        existingCase(
            name = "existing: FREQ=WEEKLY BYDAY=MO,WE,FR",
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            dtstartMs = utc(2025, 1, 6, 10, 0), // Monday
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        existingCase(
            name = "existing: FREQ=WEEKLY INTERVAL=2 bi-weekly",
            rrule = "FREQ=WEEKLY;INTERVAL=2",
            dtstartMs = utc(2025, 1, 6, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 4, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY BYDAY=TU BYSETPOS=2 second Tuesday",
            rrule = "FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2",
            dtstartMs = utc(2025, 1, 14, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY BYDAY=FR BYSETPOS=-1 last Friday",
            rrule = "FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1",
            dtstartMs = utc(2025, 1, 31, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=YEARLY anniversary",
            rrule = "FREQ=YEARLY",
            dtstartMs = utc(2020, 3, 15, 10, 0),
            rangeStartMs = utc(2020, 1, 1),
            rangeEndMs = utc(2030, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=WEEKLY BYDAY=MO all-day",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            dtstartMs = utcMidnight(2025, 1, 6),
            timezone = null,
            isAllDay = true,
            rangeStartMs = utcMidnight(2025, 1, 1),
            rangeEndMs = utcMidnight(2025, 3, 1),
        ),

        // ========== App-side: OccurrenceGeneratorAdvancedTest patterns ==========

        existingCase(
            name = "existing: FREQ=MONTHLY BYMONTHDAY=15,-1 15th and last day",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=15,-1;COUNT=12",
            dtstartMs = utc(2025, 1, 15, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY BYDAY=1MO first Monday",
            rrule = "FREQ=MONTHLY;BYDAY=1MO;COUNT=6",
            dtstartMs = utc(2025, 1, 6, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=YEARLY BYMONTH=2 BYDAY=-1 last day of Feb across leap years",
            rrule = "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1;COUNT=6",
            dtstartMs = utc(2020, 2, 29, 10, 0), // leap year
            rangeStartMs = utc(2020, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        // ========== App-side: OccurrenceGeneratorRfc5545ComplianceTest patterns ==========

        existingCase(
            name = "existing: FREQ=YEARLY BYMONTH=6,7 COUNT=6",
            rrule = "FREQ=YEARLY;BYMONTH=6,7;COUNT=6",
            dtstartMs = utc(2025, 6, 10, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2030, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY BYDAY=MO,TU,WE,TH,FR BYSETPOS=-1 last weekday",
            rrule = "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=6",
            dtstartMs = utc(2025, 1, 31, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=DAILY BYHOUR=9,12,15 three times per day",
            rrule = "FREQ=DAILY;BYHOUR=9,12,15;COUNT=6",
            dtstartMs = utc(2025, 1, 1, 9, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 1, 10),
        ),

        existingCase(
            name = "existing: FREQ=YEARLY BYWEEKNO=1 BYDAY=MO first ISO week Monday",
            rrule = "FREQ=YEARLY;BYWEEKNO=1;BYDAY=MO;COUNT=5",
            dtstartMs = utc(2024, 1, 1, 10, 0),
            rangeStartMs = utc(2024, 1, 1),
            rangeEndMs = utc(2030, 1, 1),
        ),

        // ========== App-side: RRuleAdversarialTest patterns ==========

        existingCase(
            name = "existing: FREQ=DAILY COUNT=365 full year",
            rrule = "FREQ=DAILY;COUNT=365",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY BYMONTHDAY=31 skips short months",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=7",
            dtstartMs = utc(2025, 1, 31, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        // ========== App-side: RRuleDSTInteractionTest patterns ==========

        existingCase(
            name = "existing: DAILY across DST spring-forward in America/New_York",
            rrule = "FREQ=DAILY;COUNT=7",
            dtstartMs = et(2025, 3, 8, 9, 0), // Saturday before spring-forward (Sun 3/9)
            timezone = "America/New_York",
            rangeStartMs = et(2025, 3, 1),
            rangeEndMs = et(2025, 3, 20),
        ),

        existingCase(
            name = "existing: DAILY across DST fall-back in America/New_York",
            rrule = "FREQ=DAILY;COUNT=7",
            dtstartMs = et(2025, 11, 1, 9, 0), // Saturday before fall-back (Sun 11/2)
            timezone = "America/New_York",
            rangeStartMs = et(2025, 11, 1),
            rangeEndMs = et(2025, 11, 15),
        ),

        existingCase(
            name = "existing: WEEKLY across DST in Europe/London",
            rrule = "FREQ=WEEKLY;COUNT=8",
            dtstartMs = ZonedDateTime.of(2025, 3, 1, 9, 0, 0, 0, ZoneId.of("Europe/London"))
                .toInstant().toEpochMilli(),
            timezone = "Europe/London",
            rangeStartMs = ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, ZoneId.of("Europe/London"))
                .toInstant().toEpochMilli(),
            rangeEndMs = ZonedDateTime.of(2025, 5, 1, 0, 0, 0, 0, ZoneId.of("Europe/London"))
                .toInstant().toEpochMilli(),
        ),

        // ========== App-side: OccurrenceGeneratorEdgeCaseTest patterns ==========

        existingCase(
            name = "existing: FREQ=WEEKLY UNTIL=20260202 all-day",
            rrule = "FREQ=WEEKLY;UNTIL=20260202",
            dtstartMs = utcMidnight(2025, 9, 1),
            timezone = null,
            isAllDay = true,
            rangeStartMs = utcMidnight(2025, 9, 1),
            rangeEndMs = utcMidnight(2026, 3, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY UNTIL=20260615T000000Z timed",
            rrule = "FREQ=MONTHLY;UNTIL=20260615T000000Z",
            dtstartMs = utc(2025, 10, 15, 10, 0),
            rangeStartMs = utc(2025, 10, 1),
            rangeEndMs = utc(2026, 7, 1),
        ),

        // ========== Library-side: RRuleExpanderTest / Comprehensive patterns ==========

        existingCase(
            name = "existing: FREQ=MONTHLY BYDAY=1FR first Friday COUNT=12",
            rrule = "FREQ=MONTHLY;BYDAY=1FR;COUNT=12",
            dtstartMs = utc(2025, 1, 3, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=MONTHLY BYMONTHDAY=1,15 twice monthly",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=8",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2026, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=YEARLY BYMONTH=2 BYMONTHDAY=29 leap day anniversary",
            rrule = "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;COUNT=3",
            dtstartMs = utc(2020, 2, 29, 10, 0),
            rangeStartMs = utc(2020, 1, 1),
            rangeEndMs = utc(2040, 1, 1),
        ),

        // ========== Library-side: RRuleExpanderRdateTest patterns ==========

        existingCase(
            name = "existing: RDATE adds out-of-pattern date",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
            dtstartMs = utc(2025, 1, 6, 10, 0), // Monday
            rdateStrings = "20250108T100000Z", // Wednesday — out of pattern
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        existingCase(
            name = "existing: EXDATE removes occurrence from pattern",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            dtstartMs = utc(2025, 1, 6, 10, 0),
            exdateStrings = "20250113T100000Z", // skip 2nd occurrence
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 15),
        ),

        existingCase(
            name = "existing: RDATE and EXDATE combined",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
            dtstartMs = utc(2025, 1, 6, 10, 0),
            rdateStrings = "20250108T100000Z",
            exdateStrings = "20250113T100000Z",
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),

        // ========== Library-side: RRuleExpanderLeapYearTest patterns ==========

        existingCase(
            name = "existing: FREQ=MONTHLY BYMONTHDAY=31 across 2024 (leap)",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=31",
            dtstartMs = utc(2024, 1, 31, 10, 0),
            rangeStartMs = utc(2024, 1, 1),
            rangeEndMs = utc(2025, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=YEARLY BYYEARDAY=-1 last day of year",
            rrule = "FREQ=YEARLY;BYYEARDAY=-1;COUNT=4",
            dtstartMs = utc(2024, 12, 31, 10, 0),
            rangeStartMs = utc(2024, 1, 1),
            rangeEndMs = utc(2030, 1, 1),
        ),

        existingCase(
            name = "existing: FREQ=HOURLY COUNT=24 over one day",
            rrule = "FREQ=HOURLY;COUNT=24",
            dtstartMs = utc(2025, 1, 1, 0, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 1, 2),
        ),

        existingCase(
            name = "existing: FREQ=WEEKLY BYDAY=SA,SU weekends COUNT=10",
            rrule = "FREQ=WEEKLY;BYDAY=SA,SU;COUNT=10",
            dtstartMs = utc(2025, 1, 4, 10, 0), // Saturday
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 3, 1),
        ),

        existingCase(
            name = "existing: FREQ=DAILY WKST=SU has no effect (daily ignores WKST)",
            rrule = "FREQ=DAILY;COUNT=5;WKST=SU",
            dtstartMs = utc(2025, 1, 1, 10, 0),
            rangeStartMs = utc(2025, 1, 1),
            rangeEndMs = utc(2025, 2, 1),
        ),
    )
}
