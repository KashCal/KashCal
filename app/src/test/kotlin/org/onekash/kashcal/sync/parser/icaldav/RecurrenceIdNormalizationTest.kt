package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ICalDateTime
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * RFC 5545 §3.8.4.4 says RECURRENCE-ID's value type MUST match the master's
 * DTSTART value type. Some clients in the wild get this wrong — emitting
 * `RECURRENCE-ID;VALUE=DATE` against a timed master, or DATE-TIME against an
 * all-day master. Live multi-server tests show 7 of 10 CalDAV servers preserve
 * the mismatched form.
 *
 * Without normalization, the timestamp KashCal stores for the exception lands
 * at midnight UTC (DATE form parsed) while the master's RRULE expansion at
 * the same calendar day puts the instance at the master's local time-of-day.
 * The 60-second linkException tolerance can't bridge the gap, leaving two
 * occurrence rows for that day.
 *
 * This file covers the pure normalization helper. The wire-level capture
 * across servers lives in MultiServerScopeSheetWireTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurrenceIdNormalizationTest {

    @Test
    fun `DATE-form RECURRENCE-ID against timed master promotes to master's time-of-day`() {
        // Master: every day at 10:00 America/Chicago, June 1 2026 onwards.
        val chicago = ZoneId.of("America/Chicago")
        val masterStart = ICalDateTime.parse("20260601T100000", "America/Chicago")
        // Exception arrives with RECURRENCE-ID;VALUE=DATE:20260603 (RFC violation).
        // Parser produces a DATE-form ICalDateTime at midnight UTC of Jun 3.
        val exceptionRecurrenceIdRaw = ICalDateTime.parse("20260603")

        val normalized = ICalEventMapper.normalizeRecurrenceId(
            recurrenceId = exceptionRecurrenceIdRaw,
            masterDtStart = masterStart,
        )

        assertNotNull("normalization must produce a value", normalized)
        assertFalse(
            "normalized RECURRENCE-ID must be DATE-TIME (matches master)",
            normalized!!.isDate,
        )
        // Expected: Jun 3 10:00 America/Chicago in epoch ms.
        val expectedTs = java.time.ZonedDateTime
            .of(2026, 6, 3, 10, 0, 0, 0, chicago)
            .toInstant().toEpochMilli()
        assertEquals(
            "normalized timestamp must equal master time-of-day in master's TZ on RECURRENCE-ID's date",
            expectedTs,
            normalized.timestamp,
        )
    }

    @Test
    fun `DATE-TIME-form RECURRENCE-ID against all-day master demotes to DATE`() {
        // Master: all-day, June 1 2026 (UTC midnight per ICalDateTime convention).
        val masterStart = ICalDateTime.parse("20260601")
        assertTrue("sanity: master is DATE", masterStart.isDate)
        // Exception arrives with RECURRENCE-ID;TZID=America/Chicago:20260603T000000
        // — DATE-TIME form against an all-day master.
        val exceptionRecurrenceIdRaw = ICalDateTime.parse("20260603T000000", "America/Chicago")

        val normalized = ICalEventMapper.normalizeRecurrenceId(
            recurrenceId = exceptionRecurrenceIdRaw,
            masterDtStart = masterStart,
        )

        assertNotNull(normalized)
        assertTrue(
            "normalized RECURRENCE-ID must be DATE (matches all-day master)",
            normalized!!.isDate,
        )
        // Expected: Jun 3 UTC midnight.
        val expectedTs = java.time.LocalDate
            .of(2026, 6, 3)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals(
            "normalized timestamp must be Jun 3 UTC midnight",
            expectedTs,
            normalized.timestamp,
        )
    }

    @Test
    fun `matched value-type passes through unchanged`() {
        // Master timed, exception RECURRENCE-ID also timed — no change.
        val masterStart = ICalDateTime.parse("20260601T100000", "America/Chicago")
        val recurrenceId = ICalDateTime.parse("20260603T100000", "America/Chicago")

        val normalized = ICalEventMapper.normalizeRecurrenceId(
            recurrenceId = recurrenceId,
            masterDtStart = masterStart,
        )

        assertEquals(
            "matched value-type must pass through (same timestamp)",
            recurrenceId.timestamp,
            normalized!!.timestamp,
        )
        assertEquals(
            "matched value-type must pass through (same isDate)",
            recurrenceId.isDate,
            normalized.isDate,
        )
    }

    @Test
    fun `null recurrence id passes through as null`() {
        val masterStart = ICalDateTime.parse("20260601T100000", "America/Chicago")
        val normalized = ICalEventMapper.normalizeRecurrenceId(
            recurrenceId = null,
            masterDtStart = masterStart,
        )
        assertEquals(null, normalized)
    }

    @Test
    fun `null master pass-through preserves recurrence id verbatim`() {
        // No master available — we can't normalize. Pass through unchanged
        // rather than guess.
        val recurrenceId = ICalDateTime.parse("20260603")
        val normalized = ICalEventMapper.normalizeRecurrenceId(
            recurrenceId = recurrenceId,
            masterDtStart = null,
        )
        assertEquals(recurrenceId.timestamp, normalized!!.timestamp)
        assertEquals(recurrenceId.isDate, normalized.isDate)
    }
}
