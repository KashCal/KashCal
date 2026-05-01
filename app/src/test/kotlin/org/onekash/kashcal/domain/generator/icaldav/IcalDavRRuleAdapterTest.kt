package org.onekash.kashcal.domain.generator.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.icaldav.model.ICalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Adapter contract for the production RRULE → ical4j path.
 *
 * Bridges the OccurrenceGenerator's primitive-argument signature
 * (rrule string, epoch ms, timezone string, isAllDay, CSV RDATE/EXDATE)
 * into an [org.onekash.icaldav.model.ICalEvent] consumed by
 * [org.onekash.icaldav.recurrence.RRuleExpander].
 *
 * The adapter encapsulates two behavior-preserving quirks ported from
 * `LibRecurEngine` so migration doesn't regress real-world-malformed inputs:
 *
 *   (b) COUNT+UNTIL both present → strip UNTIL (lib-recur's sanitizer).
 *   (g) DATE-format RDATE/EXDATE against a timed DTSTART → inherit DTSTART's
 *       hour/minute/second so `toDayCode()` returns the correct local day.
 */
class IcalDavRRuleAdapterTest {

    private val baseStartUtc = 1704067200000L // 2024-01-01 00:00:00 UTC

    // ========== DTSTART round-trip ==========

    @Test
    fun `DTSTART epoch ms survives adapter conversion`() {
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = baseStartUtc,
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(baseStartUtc, event.dtStart.timestamp)
    }

    @Test
    fun `isAllDay flag survives conversion`() {
        val timed = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = baseStartUtc,
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        val allDay = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = baseStartUtc,
            timezone = null,
            isAllDay = true,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(false, timed.isAllDay)
        assertEquals(true, allDay.isAllDay)
    }

    // ========== Timezone resolution ==========

    @Test
    fun `non-null IANA TZID resolves to matching ZoneId`() {
        val event = buildDefault(timezone = "America/New_York")
        assertEquals(ZoneId.of("America/New_York"), event.dtStart.timezone)
    }

    @Test
    fun `null TZID yields floating (null zone)`() {
        val event = buildDefault(timezone = null, isAllDay = false)
        assertNull(event.dtStart.timezone)
    }

    @Test
    fun `invalid TZID falls through to null (floating), not exception`() {
        val event = buildDefault(timezone = "NotAZone/XYZ")
        assertNull(event.dtStart.timezone)
    }

    @Test
    fun `isAllDay=true forces UTC-equivalent regardless of TZID input`() {
        val event = buildDefault(timezone = "America/New_York", isAllDay = true)
        assertTrue("all-day should have isDate=true", event.dtStart.isDate)
    }

    // ========== RDATE CSV parsing ==========

    @Test
    fun `RDATE in milliseconds format parses to matching ICalDateTime`() {
        val extra = baseStartUtc + 10L * 86400 * 1000
        val event = buildDefault(rdate = extra.toString())
        assertEquals(1, event.rdates.size)
        assertEquals(extra, event.rdates[0].timestamp)
    }

    @Test
    fun `RDATE in YYYYMMDDTHHMMSSZ format parses`() {
        val event = buildDefault(rdate = "20240115T120000Z")
        assertEquals(1, event.rdates.size)
        assertEquals(1705320000000L, event.rdates[0].timestamp)
    }

    @Test
    fun `RDATE CSV with unparseable entries silently skips them`() {
        val event = buildDefault(rdate = "garbage,20240115T100000Z")
        assertEquals(1, event.rdates.size)
    }

    @Test
    fun `null RDATE yields empty list`() {
        val event = buildDefault(rdate = null)
        assertTrue(event.rdates.isEmpty())
    }

    // ========== QUIRK (g) — DATE-format RDATE/EXDATE inherit DTSTART time ==========

    @Test
    fun `QUIRK g — DATE-format EXDATE against TIMED event with TZID inherits DTSTART hour`() {
        // DTSTART: 2025-07-01 10:30 America/New_York (local); EXDATE "20250703".
        // Expected: EXDATE's toDayCode is "20250703", matching lib-recur's
        // inheritance semantics. Pre-fix behavior would have produced "20250702"
        // because UTC-midnight of 2025-07-03 is 20:00 EDT on 2025-07-02.
        val dtstart = ZonedDateTime.of(2025, 7, 1, 10, 30, 0, 0, ZoneId.of("America/New_York"))
            .toInstant().toEpochMilli()
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = dtstart,
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = "20250703",
        )
        assertEquals(1, event.exdates.size)
        assertEquals("EXDATE day code should be the local date from DTSTART's perspective",
            "20250703", event.exdates[0].toDayCode())
    }

    @Test
    fun `QUIRK g — DATE-format EXDATE against TIMED event with floating timezone inherits DTSTART hour`() {
        // The dominant shape in existing OccurrenceGenerator tests: timezone=null,
        // timed event constructed via parseDate() at a specific hour. The quirk-g
        // inheritance must work in this shape too.
        val dtstart = ZonedDateTime.of(2025, 7, 1, 14, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = dtstart,
            timezone = null,
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = "20250703",
        )
        assertEquals(1, event.exdates.size)
        // With floating zone and DTSTART hour=14, inheriting hour 14 gives
        // exdate at 2025-07-03 14:00 local time, whose day code in the system
        // default zone is 20250703.
        assertEquals("20250703", event.exdates[0].toDayCode())
    }

    @Test
    fun `QUIRK g — DATE-format RDATE against ALL-DAY event still converts to UTC midnight`() {
        // Quirk g applies only to timed events. For all-day, DATE-format RDATE
        // remains UTC-midnight (matching the existing all-day semantics).
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=WEEKLY;COUNT=3",
            dtstartMs = utcMidnight(2024, 1, 1),
            timezone = null,
            isAllDay = true,
            rdateStrings = "20240115",
            exdateStrings = null,
        )
        assertEquals(1, event.rdates.size)
        assertEquals(1705276800000L, event.rdates[0].timestamp)
    }

    // ========== QUIRK (b) — COUNT+UNTIL sanitization ==========

    @Test
    fun `QUIRK b — RRULE with both COUNT and UNTIL has UNTIL stripped`() {
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=3;UNTIL=20000101T000000Z",
            dtstartMs = baseStartUtc,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertNotNull("rrule should parse after sanitization", event.rrule)
        assertEquals(3, event.rrule!!.count)
        assertNull("UNTIL should be stripped when COUNT is present", event.rrule!!.until)
    }

    @Test
    fun `QUIRK b — RRULE with only COUNT passes through unchanged`() {
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = baseStartUtc,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(3, event.rrule!!.count)
        assertNull(event.rrule!!.until)
    }

    @Test
    fun `QUIRK b — RRULE with only UNTIL passes through unchanged`() {
        val event = IcalDavRRuleAdapter.buildICalEvent(
            rrule = "FREQ=DAILY;UNTIL=20250101T000000Z",
            dtstartMs = baseStartUtc,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertNull(event.rrule!!.count)
        assertNotNull(event.rrule!!.until)
    }

    // ========== dtEnd + RRULE pass-through + null/empty edges ==========

    @Test
    fun `dtEnd equals dtStart — RRuleExpander ignores duration for expansion`() {
        // IcalDavRRuleEngine throws away each occurrence's dtEnd (only reads
        // dtStart.timestamp); reusing dtStart here saves an allocation per call.
        // If this assertion changes, re-verify that RRuleExpander still
        // selects occurrences independent of duration.
        val event = buildDefault()
        assertNotNull(event.dtEnd)
        assertEquals(baseStartUtc, event.dtEnd!!.timestamp)
    }

    @Test
    fun `RRULE survives parse into RRule model`() {
        val event = buildDefault(rrule = "FREQ=WEEKLY;BYDAY=MO,WE")
        assertNotNull(event.rrule)
    }

    @Test
    fun `malformed RRULE results in null rrule, not exception`() {
        val event = buildDefault(rrule = "GARBAGE")
        assertNull(event.rrule)
    }

    @Test
    fun `null rrule yields null event rrule`() {
        val event = buildDefault(rrule = null)
        assertNull(event.rrule)
    }

    @Test
    fun `blank rrule yields null event rrule`() {
        val event = buildDefault(rrule = "")
        assertNull(event.rrule)
    }

    // ========== extractTimestamps ==========

    @Test
    fun `extractTimestamps returns sorted ascending`() {
        val base = buildDefault()
        val events = listOf(
            base.copy(dtStart = ICalDateTime.fromTimestamp(300L, null, false)),
            base.copy(dtStart = ICalDateTime.fromTimestamp(100L, null, false)),
            base.copy(dtStart = ICalDateTime.fromTimestamp(200L, null, false)),
        )
        assertEquals(listOf(100L, 200L, 300L), IcalDavRRuleAdapter.extractTimestamps(events))
    }

    @Test
    fun `extractTimestamps on empty list returns empty`() {
        assertTrue(IcalDavRRuleAdapter.extractTimestamps(emptyList()).isEmpty())
    }

    // ========== Helpers ==========

    private fun buildDefault(
        rrule: String? = "FREQ=DAILY;COUNT=3",
        timezone: String? = "America/New_York",
        isAllDay: Boolean = false,
        rdate: String? = null,
        exdate: String? = null,
    ) = IcalDavRRuleAdapter.buildICalEvent(
        rrule = rrule,
        dtstartMs = baseStartUtc,
        timezone = timezone,
        isAllDay = isAllDay,
        rdateStrings = rdate,
        exdateStrings = exdate,
    )

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
