package org.onekash.kashcal.domain.generator.parity.fixtures

import org.onekash.kashcal.domain.generator.parity.RRuleCase
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pool A — RFC 5545 §3.8.5.3 worked examples.
 *
 * Every case here carries `rfcExpected` — the spec-documented list of occurrences
 * transcribed from the RFC text. These cases are ground truth: if either engine
 * disagrees with `rfcExpected`, the engine is wrong, not the fixture.
 *
 * Case naming convention (enforced by `ParityCorpusValidationTest`):
 *   "RFC 5545 §3.8.5.3 example N: <short description>"
 *
 * Timezone note: the RFC states "All examples assume the Eastern United States time zone."
 * Epoch ms are computed via `ZonedDateTime` over `America/New_York`, which uses the
 * IANA tz-data historical DST rules — EST/EDT transitions in 1997/1998 follow the
 * pre-2007 "first Sunday of April / last Sunday of October" rule, not the post-2007
 * "second Sunday of March / first Sunday of November" rule. Example 38 (year 2007)
 * uses January dates and is therefore unaffected by the 2007 DST rule change.
 */
object RfcExamplesCorpus {

    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    /** 9:00 AM Eastern on the given date, as epoch ms. tzdata resolves EDT/EST. */
    private fun et9(y: Int, m: Int, d: Int, hour: Int = 9, minute: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, 0, 0, ETZ).toInstant().toEpochMilli()

    /** UTC midnight for a given date (used for all-day cases — none in Pool A). */
    @Suppress("unused")
    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun rfcCase(
        number: String,
        description: String,
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        rfcExpected: List<Long>,
        rdateStrings: String? = null,
        exdateStrings: String? = null,
        timezone: String? = "America/New_York",
        isAllDay: Boolean = false,
    ): RRuleCase = RRuleCase(
        name = "RFC 5545 §3.8.5.3 example $number: $description",
        category = "rfc",
        rrule = rrule,
        dtstartMs = dtstartMs,
        timezone = timezone,
        isAllDay = isAllDay,
        rdateStrings = rdateStrings,
        exdateStrings = exdateStrings,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        rfcExpected = rfcExpected,
    )

    val cases: List<RRuleCase> = listOf(
        // Example 1 — Daily for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970902T090000
        // RRULE:FREQ=DAILY;COUNT=10
        // ==> (1997 9:00 AM EDT) September 2-11
        rfcCase(
            number = "1",
            description = "daily for 10 occurrences",
            rrule = "FREQ=DAILY;COUNT=10",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 9, 30),
            rfcExpected = (2..11).map { et9(1997, 9, it) },
        ),

        // Example 2 — Daily until December 24, 1997.
        // RRULE:FREQ=DAILY;UNTIL=19971224T000000Z
        // ==> September 2-30 (EDT); October 1-25 (EDT); October 26-31, November 1-30, December 1-23 (EST)
        // UNTIL=19971224T000000Z = 19971223T190000 EST = 19971223T190000-0500.
        // Every 09:00 ET occurrence between DTSTART and UNTIL, inclusive. DST ends 1997-10-26.
        rfcCase(
            number = "2",
            description = "daily until December 24 1997",
            rrule = "FREQ=DAILY;UNTIL=19971224T000000Z",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 12, 25),
            rfcExpected = buildList {
                // Sep 2-30 (EDT), 29 occurrences
                for (d in 2..30) add(et9(1997, 9, d))
                // Oct 1-25 (EDT), 25 occurrences
                for (d in 1..25) add(et9(1997, 10, d))
                // Oct 26-31 (EST), 6 occurrences
                for (d in 26..31) add(et9(1997, 10, d))
                // Nov 1-30 (EST), 30 occurrences
                for (d in 1..30) add(et9(1997, 11, d))
                // Dec 1-23 (EST), 23 occurrences
                for (d in 1..23) add(et9(1997, 12, d))
            },
        ),

        // Example 3 — Every other day forever (RFC text truncated; bounded here).
        // DTSTART;TZID=America/New_York:19970902T090000
        // RRULE:FREQ=DAILY;INTERVAL=2
        // ==> (EDT) Sep 2,4,6,8,10,12,14,16,18,20,22,24,26,28,30;
        //           Oct 2,4,6,8,10,12,14,16,18,20,22,24
        //     (EST) Oct 26,28,30; Nov 1,3,5,...,29; Dec 1,3,...
        // Bounded to <Dec 2 1997> so the explicit RFC-listed dates through Dec 1 are covered.
        // DST end: Oct 26 1997 (pre-2007 rule).
        rfcCase(
            number = "3",
            description = "every other day forever (bounded to Dec 1 1997)",
            rrule = "FREQ=DAILY;INTERVAL=2",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 12, 2),
            rfcExpected = buildList {
                // Sep 2,4,..,30 (EDT)
                for (d in 2..30 step 2) add(et9(1997, 9, d))
                // Oct 2,4,..,24 (EDT)
                for (d in 2..24 step 2) add(et9(1997, 10, d))
                // Oct 26,28,30 (EST — DST ended Oct 26)
                for (d in 26..30 step 2) add(et9(1997, 10, d))
                // Nov 1,3,..,29 (EST)
                for (d in 1..29 step 2) add(et9(1997, 11, d))
                // Dec 1 (EST)
                add(et9(1997, 12, 1))
            },
        ),

        // Example 4 — Every 10 days, 5 occurrences.
        // RRULE:FREQ=DAILY;INTERVAL=10;COUNT=5
        // ==> September 2,12,22; October 2,12
        rfcCase(
            number = "4",
            description = "every 10 days 5 occurrences",
            rrule = "FREQ=DAILY;INTERVAL=10;COUNT=5",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 10, 31),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 12), et9(1997, 9, 22),
                et9(1997, 10, 2), et9(1997, 10, 12),
            ),
        ),

        // Example 5 — Every day in January for 3 years.
        // DTSTART;TZID=America/New_York:19980101T090000
        // RRULE:FREQ=YEARLY;UNTIL=20000131T140000Z;BYMONTH=1;BYDAY=SU,MO,TU,WE,TH,FR,SA
        // UNTIL=20000131T140000Z = 20000131T090000 EST. Includes Jan 31 9:00 of each year.
        // ==> Jan 1-31 in 1998, 1999, 2000 (all EST).
        rfcCase(
            number = "5",
            description = "every day in January for 3 years",
            rrule = "FREQ=YEARLY;UNTIL=20000131T140000Z;BYMONTH=1;BYDAY=SU,MO,TU,WE,TH,FR,SA",
            dtstartMs = et9(1998, 1, 1),
            rangeStartMs = et9(1997, 12, 1),
            rangeEndMs = et9(2000, 2, 1),
            rfcExpected = buildList {
                for (year in listOf(1998, 1999, 2000)) {
                    for (day in 1..31) add(et9(year, 1, day))
                }
            },
        ),

        // Example 6 — Weekly for 10 occurrences.
        // RRULE:FREQ=WEEKLY;COUNT=10
        // ==> Sep 2,9,16,23,30; Oct 7,14,21 (EDT); Oct 28; Nov 4 (EST)
        rfcCase(
            number = "6",
            description = "weekly for 10 occurrences",
            rrule = "FREQ=WEEKLY;COUNT=10",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 11, 30),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 9), et9(1997, 9, 16), et9(1997, 9, 23), et9(1997, 9, 30),
                et9(1997, 10, 7), et9(1997, 10, 14), et9(1997, 10, 21),
                et9(1997, 10, 28), et9(1997, 11, 4),
            ),
        ),

        // Example 7 — Weekly until December 24, 1997.
        // RRULE:FREQ=WEEKLY;UNTIL=19971224T000000Z
        // ==> Sep 2,9,16,23,30; Oct 7,14,21 (EDT); Oct 28; Nov 4,11,18,25; Dec 2,9,16,23 (EST)
        rfcCase(
            number = "7",
            description = "weekly until December 24 1997",
            rrule = "FREQ=WEEKLY;UNTIL=19971224T000000Z",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 12, 25),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 9), et9(1997, 9, 16), et9(1997, 9, 23), et9(1997, 9, 30),
                et9(1997, 10, 7), et9(1997, 10, 14), et9(1997, 10, 21), et9(1997, 10, 28),
                et9(1997, 11, 4), et9(1997, 11, 11), et9(1997, 11, 18), et9(1997, 11, 25),
                et9(1997, 12, 2), et9(1997, 12, 9), et9(1997, 12, 16), et9(1997, 12, 23),
            ),
        ),

        // Example 8 — Every other week forever (truncated in RFC; bounded to listed occurrences here).
        // RRULE:FREQ=WEEKLY;INTERVAL=2;WKST=SU
        // ==> Sep 2,16,30; Oct 14 (EDT); Oct 28; Nov 11,25; Dec 9,23 (1997 EST); Jan 6,20; Feb 3,17 (1998 EST)
        rfcCase(
            number = "8",
            description = "every other week forever (bounded to listed occurrences)",
            rrule = "FREQ=WEEKLY;INTERVAL=2;WKST=SU",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 2, 18),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 16), et9(1997, 9, 30),
                et9(1997, 10, 14), et9(1997, 10, 28),
                et9(1997, 11, 11), et9(1997, 11, 25),
                et9(1997, 12, 9), et9(1997, 12, 23),
                et9(1998, 1, 6), et9(1998, 1, 20),
                et9(1998, 2, 3), et9(1998, 2, 17),
            ),
        ),

        // Example 9 — Weekly on Tuesday and Thursday for five weeks.
        // RRULE:FREQ=WEEKLY;COUNT=10;WKST=SU;BYDAY=TU,TH
        // ==> Sep 2,4,9,11,16,18,23,25,30; Oct 2 (EDT)
        rfcCase(
            number = "9",
            description = "weekly on Tuesday and Thursday for five weeks (COUNT=10)",
            rrule = "FREQ=WEEKLY;COUNT=10;WKST=SU;BYDAY=TU,TH",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 10, 10),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 4),
                et9(1997, 9, 9), et9(1997, 9, 11),
                et9(1997, 9, 16), et9(1997, 9, 18),
                et9(1997, 9, 23), et9(1997, 9, 25),
                et9(1997, 9, 30), et9(1997, 10, 2),
            ),
        ),

        // Example 10 — Every other week Mo,We,Fr until Dec 24 1997, starting Mon Sep 1 1997.
        // DTSTART;TZID=America/New_York:19970901T090000
        // RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=19971224T000000Z;WKST=SU;BYDAY=MO,WE,FR
        // ==> Sep 1,3,5,15,17,19,29; Oct 1,3,13,15,17 (EDT);
        //     Oct 27,29,31; Nov 10,12,14,24,26,28; Dec 8,10,12,22 (EST)
        rfcCase(
            number = "10",
            description = "every other week MoWeFr until Dec 24 1997",
            rrule = "FREQ=WEEKLY;INTERVAL=2;UNTIL=19971224T000000Z;WKST=SU;BYDAY=MO,WE,FR",
            dtstartMs = et9(1997, 9, 1),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 12, 25),
            rfcExpected = listOf(
                et9(1997, 9, 1), et9(1997, 9, 3), et9(1997, 9, 5),
                et9(1997, 9, 15), et9(1997, 9, 17), et9(1997, 9, 19),
                et9(1997, 9, 29), et9(1997, 10, 1), et9(1997, 10, 3),
                et9(1997, 10, 13), et9(1997, 10, 15), et9(1997, 10, 17),
                et9(1997, 10, 27), et9(1997, 10, 29), et9(1997, 10, 31),
                et9(1997, 11, 10), et9(1997, 11, 12), et9(1997, 11, 14),
                et9(1997, 11, 24), et9(1997, 11, 26), et9(1997, 11, 28),
                et9(1997, 12, 8), et9(1997, 12, 10), et9(1997, 12, 12), et9(1997, 12, 22),
            ),
        ),

        // Example 11 — Every other week on Tuesday and Thursday for 8 occurrences.
        // RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=8;WKST=SU;BYDAY=TU,TH
        // ==> Sep 2,4,16,18,30; Oct 2,14,16 (EDT)
        rfcCase(
            number = "11",
            description = "every other week TuTh for 8 occurrences",
            rrule = "FREQ=WEEKLY;INTERVAL=2;COUNT=8;WKST=SU;BYDAY=TU,TH",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 10, 20),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 4),
                et9(1997, 9, 16), et9(1997, 9, 18),
                et9(1997, 9, 30), et9(1997, 10, 2),
                et9(1997, 10, 14), et9(1997, 10, 16),
            ),
        ),

        // Example 12 — Monthly on the first Friday for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970905T090000
        // RRULE:FREQ=MONTHLY;COUNT=10;BYDAY=1FR
        // ==> Sep 5, Oct 3 (EDT); Nov 7, Dec 5 (EST);
        //     Jan 2, Feb 6, Mar 6, Apr 3 (1998 EST); May 1, Jun 5 (1998 EDT)
        rfcCase(
            number = "12",
            description = "monthly on the first Friday for 10 occurrences",
            rrule = "FREQ=MONTHLY;COUNT=10;BYDAY=1FR",
            dtstartMs = et9(1997, 9, 5),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 7, 1),
            rfcExpected = listOf(
                et9(1997, 9, 5), et9(1997, 10, 3),
                et9(1997, 11, 7), et9(1997, 12, 5),
                et9(1998, 1, 2), et9(1998, 2, 6),
                et9(1998, 3, 6), et9(1998, 4, 3),
                et9(1998, 5, 1), et9(1998, 6, 5),
            ),
        ),

        // Example 13 — Monthly on the first Friday until December 24, 1997.
        // RRULE:FREQ=MONTHLY;UNTIL=19971224T000000Z;BYDAY=1FR
        // ==> Sep 5, Oct 3 (EDT); Nov 7, Dec 5 (EST)
        rfcCase(
            number = "13",
            description = "monthly on the first Friday until Dec 24 1997",
            rrule = "FREQ=MONTHLY;UNTIL=19971224T000000Z;BYDAY=1FR",
            dtstartMs = et9(1997, 9, 5),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 12, 25),
            rfcExpected = listOf(
                et9(1997, 9, 5), et9(1997, 10, 3),
                et9(1997, 11, 7), et9(1997, 12, 5),
            ),
        ),

        // Example 14 — Every other month on the first and last Sunday for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970907T090000
        // RRULE:FREQ=MONTHLY;INTERVAL=2;COUNT=10;BYDAY=1SU,-1SU
        // ==> Sep 7,28 (EDT); Nov 2,30 (EST);
        //     Jan 4,25; Mar 1,29 (1998 EST); May 3,31 (1998 EDT)
        rfcCase(
            number = "14",
            description = "every other month 1st and last Sunday for 10 occurrences",
            rrule = "FREQ=MONTHLY;INTERVAL=2;COUNT=10;BYDAY=1SU,-1SU",
            dtstartMs = et9(1997, 9, 7),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 6, 1),
            rfcExpected = listOf(
                et9(1997, 9, 7), et9(1997, 9, 28),
                et9(1997, 11, 2), et9(1997, 11, 30),
                et9(1998, 1, 4), et9(1998, 1, 25),
                et9(1998, 3, 1), et9(1998, 3, 29),
                et9(1998, 5, 3), et9(1998, 5, 31),
            ),
        ),

        // Example 15 — Monthly on the second-to-last Monday for 6 months.
        // DTSTART;TZID=America/New_York:19970922T090000
        // RRULE:FREQ=MONTHLY;COUNT=6;BYDAY=-2MO
        // ==> Sep 22, Oct 20 (EDT); Nov 17, Dec 22 (EST);
        //     Jan 19, Feb 16 (1998 EST)
        rfcCase(
            number = "15",
            description = "monthly second-to-last Monday for 6 months",
            rrule = "FREQ=MONTHLY;COUNT=6;BYDAY=-2MO",
            dtstartMs = et9(1997, 9, 22),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 3, 1),
            rfcExpected = listOf(
                et9(1997, 9, 22), et9(1997, 10, 20),
                et9(1997, 11, 17), et9(1997, 12, 22),
                et9(1998, 1, 19), et9(1998, 2, 16),
            ),
        ),

        // Example 16 — Monthly on the third-to-the-last day of the month, forever (bounded).
        // DTSTART;TZID=America/New_York:19970928T090000
        // RRULE:FREQ=MONTHLY;BYMONTHDAY=-3
        // ==> Sep 28 (EDT); Oct 29, Nov 28, Dec 29 (EST); Jan 29, Feb 26 (1998 EST)
        rfcCase(
            number = "16",
            description = "monthly third-to-last day forever (bounded to Feb 1998)",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=-3",
            dtstartMs = et9(1997, 9, 28),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 3, 1),
            rfcExpected = listOf(
                et9(1997, 9, 28),
                et9(1997, 10, 29), et9(1997, 11, 28), et9(1997, 12, 29),
                et9(1998, 1, 29), et9(1998, 2, 26),
            ),
        ),

        // Example 17 — Monthly on the 2nd and 15th for 10 occurrences.
        // RRULE:FREQ=MONTHLY;COUNT=10;BYMONTHDAY=2,15
        // ==> Sep 2,15; Oct 2,15 (EDT); Nov 2,15; Dec 2,15 (EST); Jan 2,15 (1998 EST)
        rfcCase(
            number = "17",
            description = "monthly on 2nd and 15th for 10 occurrences",
            rrule = "FREQ=MONTHLY;COUNT=10;BYMONTHDAY=2,15",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 2, 1),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 15),
                et9(1997, 10, 2), et9(1997, 10, 15),
                et9(1997, 11, 2), et9(1997, 11, 15),
                et9(1997, 12, 2), et9(1997, 12, 15),
                et9(1998, 1, 2), et9(1998, 1, 15),
            ),
        ),

        // Example 18 — Monthly on the first and last day for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970930T090000
        // RRULE:FREQ=MONTHLY;COUNT=10;BYMONTHDAY=1,-1
        // ==> Sep 30, Oct 1 (EDT); Oct 31, Nov 1, Nov 30, Dec 1, Dec 31 (EST);
        //     Jan 1, Jan 31, Feb 1 (1998 EST)
        rfcCase(
            number = "18",
            description = "monthly on 1st and last day for 10 occurrences",
            rrule = "FREQ=MONTHLY;COUNT=10;BYMONTHDAY=1,-1",
            dtstartMs = et9(1997, 9, 30),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 2, 15),
            rfcExpected = listOf(
                et9(1997, 9, 30), et9(1997, 10, 1),
                et9(1997, 10, 31), et9(1997, 11, 1),
                et9(1997, 11, 30), et9(1997, 12, 1),
                et9(1997, 12, 31),
                et9(1998, 1, 1), et9(1998, 1, 31),
                et9(1998, 2, 1),
            ),
        ),

        // Example 19 — Every 18 months on the 10th thru 15th for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970910T090000
        // RRULE:FREQ=MONTHLY;INTERVAL=18;COUNT=10;BYMONTHDAY=10,11,12,13,14,15
        // ==> Sep 10,11,12,13,14,15 (1997 EDT); Mar 10,11,12,13 (1999 EST)
        rfcCase(
            number = "19",
            description = "every 18 months on 10th thru 15th for 10 occurrences",
            rrule = "FREQ=MONTHLY;INTERVAL=18;COUNT=10;BYMONTHDAY=10,11,12,13,14,15",
            dtstartMs = et9(1997, 9, 10),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1999, 4, 1),
            rfcExpected = listOf(
                et9(1997, 9, 10), et9(1997, 9, 11), et9(1997, 9, 12),
                et9(1997, 9, 13), et9(1997, 9, 14), et9(1997, 9, 15),
                et9(1999, 3, 10), et9(1999, 3, 11),
                et9(1999, 3, 12), et9(1999, 3, 13),
            ),
        ),

        // Example 20 — Every Tuesday, every other month, forever (bounded).
        // DTSTART;TZID=America/New_York:19970902T090000  (Tuesday)
        // RRULE:FREQ=MONTHLY;INTERVAL=2;BYDAY=TU
        // ==> Sep 2,9,16,23,30 (1997 EDT);
        //     Nov 4,11,18,25 (1997 EST);
        //     Jan 6,13,20,27 (1998 EST);
        //     Mar 3,10,17,24,31 (1998 EST)
        rfcCase(
            number = "20",
            description = "every Tuesday every other month forever (bounded to Mar 1998)",
            rrule = "FREQ=MONTHLY;INTERVAL=2;BYDAY=TU",
            dtstartMs = et9(1997, 9, 2),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 4, 1),
            rfcExpected = listOf(
                et9(1997, 9, 2), et9(1997, 9, 9), et9(1997, 9, 16), et9(1997, 9, 23), et9(1997, 9, 30),
                et9(1997, 11, 4), et9(1997, 11, 11), et9(1997, 11, 18), et9(1997, 11, 25),
                et9(1998, 1, 6), et9(1998, 1, 13), et9(1998, 1, 20), et9(1998, 1, 27),
                et9(1998, 3, 3), et9(1998, 3, 10), et9(1998, 3, 17), et9(1998, 3, 24), et9(1998, 3, 31),
            ),
        ),

        // Example 21 — Yearly in June and July for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970610T090000
        // RRULE:FREQ=YEARLY;COUNT=10;BYMONTH=6,7
        // ==> Jun 10, Jul 10 each year 1997-2001 (all EDT)
        rfcCase(
            number = "21",
            description = "yearly in June and July for 10 occurrences",
            rrule = "FREQ=YEARLY;COUNT=10;BYMONTH=6,7",
            dtstartMs = et9(1997, 6, 10),
            rangeStartMs = et9(1997, 6, 1),
            rangeEndMs = et9(2001, 8, 1),
            rfcExpected = buildList {
                for (y in 1997..2001) {
                    add(et9(y, 6, 10))
                    add(et9(y, 7, 10))
                }
            },
        ),

        // Example 22 — Every other year on Jan/Feb/Mar for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970310T090000
        // RRULE:FREQ=YEARLY;INTERVAL=2;COUNT=10;BYMONTH=1,2,3
        // ==> Mar 10 (1997 EST);
        //     Jan 10, Feb 10, Mar 10 (1999 EST; 2001 EST; 2003 EST)
        // DST begins first Sunday of April in 1997-2006; Mar 10 is always EST.
        rfcCase(
            number = "22",
            description = "every other year Jan/Feb/Mar for 10 occurrences",
            rrule = "FREQ=YEARLY;INTERVAL=2;COUNT=10;BYMONTH=1,2,3",
            dtstartMs = et9(1997, 3, 10),
            rangeStartMs = et9(1997, 3, 1),
            rangeEndMs = et9(2003, 4, 1),
            rfcExpected = listOf(
                et9(1997, 3, 10),
                et9(1999, 1, 10), et9(1999, 2, 10), et9(1999, 3, 10),
                et9(2001, 1, 10), et9(2001, 2, 10), et9(2001, 3, 10),
                et9(2003, 1, 10), et9(2003, 2, 10), et9(2003, 3, 10),
            ),
        ),

        // Example 23 — Every third year on 1st, 100th, 200th day for 10 occurrences.
        // DTSTART;TZID=America/New_York:19970101T090000
        // RRULE:FREQ=YEARLY;INTERVAL=3;COUNT=10;BYYEARDAY=1,100,200
        // ==> Jan 1 (1997 EST); Apr 10, Jul 19 (1997 EDT);
        //     Jan 1 (2000 EST); Apr 9, Jul 18 (2000 EDT);
        //     Jan 1 (2003 EST); Apr 10, Jul 19 (2003 EDT);
        //     Jan 1 (2006 EST)
        // Leap-year note: 2000 is a leap year (div by 400), so day-100 = Apr 9 and day-200 = Jul 18.
        rfcCase(
            number = "23",
            description = "every 3 years on 1st 100th 200th day for 10 occurrences",
            rrule = "FREQ=YEARLY;INTERVAL=3;COUNT=10;BYYEARDAY=1,100,200",
            dtstartMs = et9(1997, 1, 1),
            rangeStartMs = et9(1996, 12, 1),
            rangeEndMs = et9(2006, 2, 1),
            rfcExpected = listOf(
                et9(1997, 1, 1), et9(1997, 4, 10), et9(1997, 7, 19),
                et9(2000, 1, 1), et9(2000, 4, 9), et9(2000, 7, 18),
                et9(2003, 1, 1), et9(2003, 4, 10), et9(2003, 7, 19),
                et9(2006, 1, 1),
            ),
        ),

        // Example 24 — Every 20th Monday of the year, forever (bounded).
        // DTSTART;TZID=America/New_York:19970519T090000  (Monday)
        // RRULE:FREQ=YEARLY;BYDAY=20MO
        // ==> May 19 (1997 EDT); May 18 (1998 EDT); May 17 (1999 EDT)
        rfcCase(
            number = "24",
            description = "every 20th Monday of the year forever (bounded to 1999)",
            rrule = "FREQ=YEARLY;BYDAY=20MO",
            dtstartMs = et9(1997, 5, 19),
            rangeStartMs = et9(1997, 5, 1),
            rangeEndMs = et9(1999, 6, 1),
            rfcExpected = listOf(
                et9(1997, 5, 19),
                et9(1998, 5, 18),
                et9(1999, 5, 17),
            ),
        ),

        // Example 25 — Monday of week number 20 (default WKST=MO), forever (bounded).
        // DTSTART;TZID=America/New_York:19970512T090000  (ISO week-20 Monday for 1997)
        // RRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO
        // ==> May 12 (1997 EDT); May 11 (1998 EDT); May 17 (1999 EDT)
        // Note: 1999 has Jan 1 on a Friday → ISO week 1 starts Jan 4, so week 20 Monday is May 17.
        rfcCase(
            number = "25",
            description = "Monday of ISO week 20 forever (bounded to 1999)",
            rrule = "FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO",
            dtstartMs = et9(1997, 5, 12),
            rangeStartMs = et9(1997, 5, 1),
            rangeEndMs = et9(1999, 6, 1),
            rfcExpected = listOf(
                et9(1997, 5, 12),
                et9(1998, 5, 11),
                et9(1999, 5, 17),
            ),
        ),

        // Example 26 — Every Thursday in March, forever (bounded).
        // DTSTART;TZID=America/New_York:19970313T090000  (Thursday)
        // RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=TH
        // ==> Mar 13,20,27 (1997 EST); Mar 5,12,19,26 (1998 EST); Mar 4,11,18,25 (1999 EST)
        // DST begins first Sunday of April (pre-2007 rule) — all March dates are EST.
        rfcCase(
            number = "26",
            description = "every Thursday in March forever (bounded to 1999)",
            rrule = "FREQ=YEARLY;BYMONTH=3;BYDAY=TH",
            dtstartMs = et9(1997, 3, 13),
            rangeStartMs = et9(1997, 3, 1),
            rangeEndMs = et9(1999, 4, 1),
            rfcExpected = listOf(
                et9(1997, 3, 13), et9(1997, 3, 20), et9(1997, 3, 27),
                et9(1998, 3, 5), et9(1998, 3, 12), et9(1998, 3, 19), et9(1998, 3, 26),
                et9(1999, 3, 4), et9(1999, 3, 11), et9(1999, 3, 18), et9(1999, 3, 25),
            ),
        ),

        // Example 27 — Every Thursday in June/July/August, forever (bounded).
        // DTSTART;TZID=America/New_York:19970605T090000  (Thursday)
        // RRULE:FREQ=YEARLY;BYDAY=TH;BYMONTH=6,7,8
        // ==> Jun 5,12,19,26; Jul 3,10,17,24,31; Aug 7,14,21,28 (1997 EDT)
        //     Jun 4,11,18,25; Jul 2,9,16,23,30; Aug 6,13,20,27 (1998 EDT)
        //     Jun 3,10,17,24; Jul 1,8,15,22,29; Aug 5,12,19,26 (1999 EDT)
        rfcCase(
            number = "27",
            description = "every Thursday in Jun_Jul_Aug forever (bounded to 1999)",
            rrule = "FREQ=YEARLY;BYDAY=TH;BYMONTH=6,7,8",
            dtstartMs = et9(1997, 6, 5),
            rangeStartMs = et9(1997, 6, 1),
            rangeEndMs = et9(1999, 9, 1),
            rfcExpected = listOf(
                // 1997
                et9(1997, 6, 5), et9(1997, 6, 12), et9(1997, 6, 19), et9(1997, 6, 26),
                et9(1997, 7, 3), et9(1997, 7, 10), et9(1997, 7, 17), et9(1997, 7, 24), et9(1997, 7, 31),
                et9(1997, 8, 7), et9(1997, 8, 14), et9(1997, 8, 21), et9(1997, 8, 28),
                // 1998
                et9(1998, 6, 4), et9(1998, 6, 11), et9(1998, 6, 18), et9(1998, 6, 25),
                et9(1998, 7, 2), et9(1998, 7, 9), et9(1998, 7, 16), et9(1998, 7, 23), et9(1998, 7, 30),
                et9(1998, 8, 6), et9(1998, 8, 13), et9(1998, 8, 20), et9(1998, 8, 27),
                // 1999
                et9(1999, 6, 3), et9(1999, 6, 10), et9(1999, 6, 17), et9(1999, 6, 24),
                et9(1999, 7, 1), et9(1999, 7, 8), et9(1999, 7, 15), et9(1999, 7, 22), et9(1999, 7, 29),
                et9(1999, 8, 5), et9(1999, 8, 12), et9(1999, 8, 19), et9(1999, 8, 26),
            ),
        ),

        // Example 28 — Every Friday the 13th forever.
        // DTSTART;TZID=America/New_York:19970902T090000
        // EXDATE;TZID=America/New_York:19970902T090000  (excludes DTSTART itself)
        // RRULE:FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13
        // ==> Feb 13, Mar 13, Nov 13 (1998 EST); Aug 13 (1999 EDT); Oct 13 (2000 EDT); ...
        // Bound range to include the 5 explicitly listed occurrences.
        rfcCase(
            number = "28",
            description = "every Friday 13th forever with EXDATE on DTSTART",
            rrule = "FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13",
            dtstartMs = et9(1997, 9, 2),
            // EXDATE format: pass DTSTART's local date/time as YYYYMMDDTHHMMSS — both engines
            // treat this as a TZID-attached local time equal to DTSTART.
            exdateStrings = "19970902T090000",
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(2000, 10, 14),
            rfcExpected = listOf(
                et9(1998, 2, 13), et9(1998, 3, 13), et9(1998, 11, 13),
                et9(1999, 8, 13), et9(2000, 10, 13),
            ),
        ),

        // Example 29 — First Saturday that follows the first Sunday of the month, forever (bounded).
        // DTSTART;TZID=America/New_York:19970913T090000  (Saturday)
        // RRULE:FREQ=MONTHLY;BYDAY=SA;BYMONTHDAY=7,8,9,10,11,12,13
        // ==> Sep 13, Oct 11 (1997 EDT); Nov 8, Dec 13 (1997 EST);
        //     Jan 10, Feb 7, Mar 7 (1998 EST); Apr 11, May 9, Jun 13 (1998 EDT)
        // DST: 1997 EDT ends Oct 26; 1998 EDT begins Apr 5 (pre-2007 rule).
        rfcCase(
            number = "29",
            description = "first Saturday after first Sunday forever (bounded to Jun 1998)",
            rrule = "FREQ=MONTHLY;BYDAY=SA;BYMONTHDAY=7,8,9,10,11,12,13",
            dtstartMs = et9(1997, 9, 13),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 7, 1),
            rfcExpected = listOf(
                et9(1997, 9, 13), et9(1997, 10, 11),
                et9(1997, 11, 8), et9(1997, 12, 13),
                et9(1998, 1, 10), et9(1998, 2, 7), et9(1998, 3, 7),
                et9(1998, 4, 11), et9(1998, 5, 9), et9(1998, 6, 13),
            ),
        ),

        // Example 30 — Every 4 years, the first Tuesday after a Monday in November
        //              (U.S. Presidential Election day), forever (bounded).
        // DTSTART;TZID=America/New_York:19961105T090000  (Tuesday)
        // RRULE:FREQ=YEARLY;INTERVAL=4;BYMONTH=11;BYDAY=TU;BYMONTHDAY=2,3,4,5,6,7,8
        // ==> Nov 5 (1996 EST); Nov 7 (2000 EST); Nov 2 (2004 EST)
        // DST end: pre-2007 rule = last Sunday of October — all listed dates are EST.
        rfcCase(
            number = "30",
            description = "every 4 years US Election Day forever (bounded to 2004)",
            rrule = "FREQ=YEARLY;INTERVAL=4;BYMONTH=11;BYDAY=TU;BYMONTHDAY=2,3,4,5,6,7,8",
            dtstartMs = et9(1996, 11, 5),
            rangeStartMs = et9(1996, 11, 1),
            rangeEndMs = et9(2004, 12, 1),
            rfcExpected = listOf(
                et9(1996, 11, 5),
                et9(2000, 11, 7),
                et9(2004, 11, 2),
            ),
        ),

        // Example 31 — Third instance of Tu/We/Th for next 3 months.
        // DTSTART;TZID=America/New_York:19970904T090000
        // RRULE:FREQ=MONTHLY;COUNT=3;BYDAY=TU,WE,TH;BYSETPOS=3
        // ==> Sep 4, Oct 7 (EDT); Nov 6 (EST)
        rfcCase(
            number = "31",
            description = "third instance of TuWeTh for next 3 months (BYSETPOS=3)",
            rrule = "FREQ=MONTHLY;COUNT=3;BYDAY=TU,WE,TH;BYSETPOS=3",
            dtstartMs = et9(1997, 9, 4),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1997, 12, 1),
            rfcExpected = listOf(
                et9(1997, 9, 4), et9(1997, 10, 7), et9(1997, 11, 6),
            ),
        ),

        // Example 32 — Second-to-last weekday of the month, forever (bounded).
        // DTSTART;TZID=America/New_York:19970929T090000  (Monday)
        // RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-2
        // ==> Sep 29 (1997 EDT); Oct 30, Nov 27, Dec 30 (1997 EST);
        //     Jan 29, Feb 26, Mar 30 (1998 EST)
        // Sep 1997: weekdays end Mon Sep 29, Tue Sep 30 → -2 = Sep 29.
        // Oct 1997: ends Thu Oct 30, Fri Oct 31 → -2 = Oct 30 (DST ended Oct 26).
        // Nov 1997: ends Thu Nov 27, Fri Nov 28 → -2 = Nov 27.
        // Dec 1997: ends Tue Dec 30, Wed Dec 31 → -2 = Dec 30.
        // Jan 1998: ends Thu Jan 29, Fri Jan 30 → -2 = Jan 29.
        // Feb 1998: ends Thu Feb 26, Fri Feb 27 → -2 = Feb 26.
        // Mar 1998: ends Mon Mar 30, Tue Mar 31 → -2 = Mar 30.
        rfcCase(
            number = "32",
            description = "second-to-last weekday of month forever (bounded to Mar 1998)",
            rrule = "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-2",
            dtstartMs = et9(1997, 9, 29),
            rangeStartMs = et9(1997, 9, 1),
            rangeEndMs = et9(1998, 4, 1),
            rfcExpected = listOf(
                et9(1997, 9, 29),
                et9(1997, 10, 30), et9(1997, 11, 27), et9(1997, 12, 30),
                et9(1998, 1, 29), et9(1998, 2, 26), et9(1998, 3, 30),
            ),
        ),

        // Example 33 — Every 3 hours 9am to 5pm on Sep 2 1997.
        // DTSTART;TZID=America/New_York:19970902T090000
        // RRULE:FREQ=HOURLY;INTERVAL=3;UNTIL=19970902T170000Z
        // UNTIL=19970902T170000Z = 13:00 EDT (Sep 2). So only 09:00 and 12:00 qualify.
        // The RFC prints "09:00,12:00,15:00" but under strict RFC semantics (UNTIL in UTC),
        // 15:00 EDT = 19:00 UTC > UNTIL, so it's outside. The ical4j/lib-recur engines
        // differ on this; this case exists specifically to surface that divergence.
        // Use the RFC's literal text (3 occurrences) as ground truth; flag as
        // knownDivergenceReason if engines produce fewer.
        rfcCase(
            number = "33",
            description = "every 3 hours on Sep 2 1997 until 17Z",
            rrule = "FREQ=HOURLY;INTERVAL=3;UNTIL=19970902T170000Z",
            dtstartMs = et9(1997, 9, 2, 9, 0),
            rangeStartMs = et9(1997, 9, 2, 0, 0),
            rangeEndMs = et9(1997, 9, 3, 0, 0),
            // RFC prints 09:00, 12:00, 15:00 — but UNTIL=17:00Z cuts before 15:00 EDT (=19:00Z).
            // Transcribe the RFC literal text; the case's role is to expose the ambiguity.
            rfcExpected = listOf(
                et9(1997, 9, 2, 9, 0),
                et9(1997, 9, 2, 12, 0),
                et9(1997, 9, 2, 15, 0),
            ),
        ).copy(
            knownDivergenceReason = "RFC prints 09:00,12:00,15:00 but UNTIL=19970902T170000Z " +
                "(13:00 EDT) excludes 15:00 EDT. This is a known RFC ambiguity/error — a literal " +
                "UTC UNTIL comparison yields 2 occurrences. Engines differ on whether to honor the " +
                "printed text or the literal UNTIL.",
        ),

        // Example 34 — Every 15 minutes for 6 occurrences.
        // DTSTART;TZID=America/New_York:19970902T090000
        // RRULE:FREQ=MINUTELY;INTERVAL=15;COUNT=6
        // ==> 09:00,09:15,09:30,09:45,10:00,10:15 (EDT)
        rfcCase(
            number = "34",
            description = "every 15 minutes for 6 occurrences",
            rrule = "FREQ=MINUTELY;INTERVAL=15;COUNT=6",
            dtstartMs = et9(1997, 9, 2, 9, 0),
            rangeStartMs = et9(1997, 9, 2, 0, 0),
            rangeEndMs = et9(1997, 9, 3, 0, 0),
            rfcExpected = listOf(
                et9(1997, 9, 2, 9, 0),
                et9(1997, 9, 2, 9, 15),
                et9(1997, 9, 2, 9, 30),
                et9(1997, 9, 2, 9, 45),
                et9(1997, 9, 2, 10, 0),
                et9(1997, 9, 2, 10, 15),
            ),
        ),

        // Example 35 — Every 90 minutes for 4 occurrences.
        // RRULE:FREQ=MINUTELY;INTERVAL=90;COUNT=4
        // ==> 09:00, 10:30, 12:00, 13:30 (EDT)
        rfcCase(
            number = "35",
            description = "every 90 minutes for 4 occurrences",
            rrule = "FREQ=MINUTELY;INTERVAL=90;COUNT=4",
            dtstartMs = et9(1997, 9, 2, 9, 0),
            rangeStartMs = et9(1997, 9, 2, 0, 0),
            rangeEndMs = et9(1997, 9, 3, 0, 0),
            rfcExpected = listOf(
                et9(1997, 9, 2, 9, 0),
                et9(1997, 9, 2, 10, 30),
                et9(1997, 9, 2, 12, 0),
                et9(1997, 9, 2, 13, 30),
            ),
        ),

        // Example 36 — Every 20 minutes from 9am to 4:40pm every day.
        // DTSTART;TZID=America/New_York:19970902T090000
        // RRULE:FREQ=DAILY;BYHOUR=9,10,11,12,13,14,15,16;BYMINUTE=0,20,40
        // ==> 9:00,9:20,9:40,10:00,...,16:00,16:20,16:40 each day
        // Bounded to Sep 2-4 1997 (all EDT; DST ends Oct 26 1997 in pre-2007 rules).
        // 3 days × 8 hours (9..16) × 3 minutes (0, 20, 40) = 72 occurrences.
        rfcCase(
            number = "36",
            description = "every 20 minutes from 9am to 4_40pm for 3 days",
            rrule = "FREQ=DAILY;BYHOUR=9,10,11,12,13,14,15,16;BYMINUTE=0,20,40",
            dtstartMs = et9(1997, 9, 2, 9, 0),
            rangeStartMs = et9(1997, 9, 2, 0, 0),
            rangeEndMs = et9(1997, 9, 5, 0, 0),
            rfcExpected = buildList {
                for (day in 2..4) {
                    for (hour in 9..16) {
                        for (minute in listOf(0, 20, 40)) {
                            add(et9(1997, 9, day, hour, minute))
                        }
                    }
                }
            },
        ),

        // Example 37a — WKST=MO variant. DTSTART Tue Aug 5 1997.
        // RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO
        // ==> Aug 5, 10, 19, 24 (1997 EDT)
        rfcCase(
            number = "37a",
            description = "WKST=MO changes bi-weekly TuSu expansion",
            rrule = "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO",
            dtstartMs = et9(1997, 8, 5),
            rangeStartMs = et9(1997, 8, 1),
            rangeEndMs = et9(1997, 9, 1),
            rfcExpected = listOf(
                et9(1997, 8, 5), et9(1997, 8, 10),
                et9(1997, 8, 19), et9(1997, 8, 24),
            ),
        ),

        // Example 37b — WKST=SU variant. DTSTART Tue Aug 5 1997.
        // RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU
        // ==> Aug 5, 17, 19, 31 (1997 EDT)
        rfcCase(
            number = "37b",
            description = "WKST=SU changes bi-weekly TuSu expansion",
            rrule = "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU",
            dtstartMs = et9(1997, 8, 5),
            rangeStartMs = et9(1997, 8, 1),
            rangeEndMs = et9(1997, 9, 1),
            rfcExpected = listOf(
                et9(1997, 8, 5), et9(1997, 8, 17),
                et9(1997, 8, 19), et9(1997, 8, 31),
            ),
        ),

        // Example 38 — Invalid date (Feb 30) ignored.
        // DTSTART;TZID=America/New_York:20070115T090000
        // RRULE:FREQ=MONTHLY;BYMONTHDAY=15,30;COUNT=5
        // ==> Jan 15, Jan 30 (2007 EST); Feb 15 (2007 EST); Mar 15, Mar 30 (2007 EDT)
        // DST begins Mar 11, 2007 (post-2006 rule), so Mar 15 is EDT.
        rfcCase(
            number = "38",
            description = "invalid date Feb 30 ignored (BYMONTHDAY=15,30)",
            rrule = "FREQ=MONTHLY;BYMONTHDAY=15,30;COUNT=5",
            dtstartMs = et9(2007, 1, 15),
            rangeStartMs = et9(2007, 1, 1),
            rangeEndMs = et9(2007, 5, 1),
            rfcExpected = listOf(
                et9(2007, 1, 15), et9(2007, 1, 30),
                et9(2007, 2, 15),
                et9(2007, 3, 15), et9(2007, 3, 30),
            ),
        ),
    )
}
