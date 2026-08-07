package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Third-party count oracle for the production RRULE engine.
 *
 * Every case here carries an independently-authored expected occurrence count
 * transcribed from a widely-used C iCalendar library's recurrence conformance
 * corpus (each fixture there records an `X-EXPECT-NUMEVENTS` line). That count
 * is a *third* opinion — independent of both ical4j and the retired reference
 * engine. The existing differential harness only proves the two JVM engines
 * *agree*; it cannot catch the case where both are wrong the same way. An
 * absolute count from an unrelated implementation can.
 *
 * These four combinations were absent from the parity corpus:
 *   - FREQ=DAILY;BYDAY=<weekdays>  (the "every weekday" pattern many desktop
 *     clients emit; the corpus had zero DAILY+BYDAY cases)
 *   - FREQ=DAILY;BYDAY;WKST        (WKST must not change a DAILY+BYDAY count)
 *   - FREQ=DAILY;BYMONTH          (day-level filtering by month, leap-year span)
 *   - FREQ=MINUTELY;BYHOUR        (sub-daily expansion constrained by hour)
 *
 * The assertion is on COUNT only (matching the corpus oracle), not on exact
 * timestamps — DST/zone offsets are deliberately out of scope here; the RFC
 * example suite in [org.onekash.kashcal.domain.generator.parity.fixtures.RfcExamplesCorpus]
 * covers exact-timestamp ground truth.
 */
class RRuleCountOracleTest {

    private fun countOf(
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        timezone: String,
    ): Int = IcalDavRRuleEngine.expandToTimestamps(
        rrule = rrule,
        dtstartMs = dtstartMs,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        timezone = timezone,
        isAllDay = false,
        rdateStrings = null,
        exdateStrings = null,
    ).size

    private fun at(y: Int, m: Int, d: Int, hour: Int = 0, minute: Int = 0, zone: String): Long =
        ZonedDateTime.of(y, m, d, hour, minute, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    // "Every weekday, 50 occurrences." DTSTART Tue 2002-01-01 09:00 UTC.
    // COUNT-bounded — 50 weekdays land by mid-March 2002; range is generous.
    @Test
    fun `daily on weekdays COUNT=50 yields 50 occurrences`() {
        val n = countOf(
            rrule = "FREQ=DAILY;COUNT=50;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR",
            dtstartMs = at(2002, 1, 1, 9, 0, zone = "UTC"),
            rangeStartMs = at(2002, 1, 1, zone = "UTC"),
            rangeEndMs = at(2002, 6, 1, zone = "UTC"),
            timezone = "UTC",
        )
        assertEquals(50, n)
    }

    // "Every weekday until 2002-01-20, WKST=SU." DTSTART Tue 2002-01-01 09:00 UTC.
    // Weekdays Jan 1-4, 7-11, 14-18 = 14 (Jan 19/20 are Sat/Sun). WKST must not
    // change a DAILY expansion — the count is 14 regardless of week-start.
    @Test
    fun `daily on weekdays until date with WKST=SU yields 14 occurrences`() {
        val n = countOf(
            rrule = "FREQ=DAILY;UNTIL=20020120T090000Z;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR;WKST=SU",
            dtstartMs = at(2002, 1, 1, 9, 0, zone = "UTC"),
            rangeStartMs = at(2002, 1, 1, zone = "UTC"),
            rangeEndMs = at(2002, 2, 1, zone = "UTC"),
            timezone = "UTC",
        )
        assertEquals(14, n)
    }

    // "Every day in January, for 3 years." DTSTART 1998-01-01 09:00 America/Los_Angeles,
    // UNTIL 2000-01-31 09:00 (floating, matches DTSTART wall time). Jan of 1998+1999+2000
    // = 31 × 3 = 93. Spans a leap year (2000) but January is unaffected.
    @Test
    fun `daily filtered by BYMONTH January across 3 years yields 93 occurrences`() {
        val zone = "America/Los_Angeles"
        val n = countOf(
            rrule = "FREQ=DAILY;UNTIL=20000131T090000;INTERVAL=1;BYMONTH=1",
            dtstartMs = at(1998, 1, 1, 9, 0, zone = zone),
            rangeStartMs = at(1997, 12, 1, zone = zone),
            rangeEndMs = at(2000, 2, 1, zone = zone),
            timezone = zone,
        )
        assertEquals(93, n)
    }

    // "Every 20 minutes from 9:00 AM to 4:40 PM, 20 occurrences."
    // DTSTART 1997-09-02 09:00 America/Los_Angeles. COUNT-bounded to 20; hours 9-16
    // give 24 slots/day, so all 20 fall on the first day.
    @Test
    fun `minutely every 20 min constrained by BYHOUR COUNT=20 yields 20 occurrences`() {
        val zone = "America/Los_Angeles"
        val n = countOf(
            rrule = "FREQ=MINUTELY;COUNT=20;INTERVAL=20;BYHOUR=9,10,11,12,13,14,15,16",
            dtstartMs = at(1997, 9, 2, 9, 0, zone = zone),
            rangeStartMs = at(1997, 9, 2, zone = zone),
            rangeEndMs = at(1997, 9, 4, zone = zone),
            timezone = zone,
        )
        assertEquals(20, n)
    }
}
