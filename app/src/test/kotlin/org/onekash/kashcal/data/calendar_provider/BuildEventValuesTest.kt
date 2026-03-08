package org.onekash.kashcal.data.calendar_provider

import android.content.ContentValues
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for buildEventValues() helper function.
 *
 * Verifies:
 * 1. All-day UTC midnight shift
 * 2. Inclusive->exclusive +1 day for all-day events
 * 3. DURATION (RFC 5545 format) for recurring vs DTEND for single
 * 4. Timezone preservation
 *
 * TDD pre-tests for C5: buildEventValues helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BuildEventValuesTest {

    // ========== All-Day Event End Time Conversion ==========

    @Test
    fun `all-day single-day event converts inclusive end to exclusive next day`() {
        // KashCal: 1-day all-day event on Jan 15
        // Stored as startTs = Jan 15 00:00 UTC, endTs = Jan 15 23:59:59.999 UTC (inclusive)
        // CalendarProvider expects: DTSTART = Jan 15 00:00 UTC, DTEND = Jan 16 00:00 UTC (exclusive)
        val jan15Start = LocalDate.of(2026, 1, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val jan15EndInclusive = jan15Start + 86_400_000 - 1 // 23:59:59.999

        val values = buildEventValues(
            title = "All-day Event",
            description = null,
            location = null,
            startTs = jan15Start,
            endTs = jan15EndInclusive,
            isAllDay = true,
            rrule = null,
            duration = null,
            timezone = "America/New_York"
        )

        // DTEND should be Jan 16 00:00 UTC (exclusive)
        val expectedDtend = LocalDate.of(2026, 1, 16)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals("DTEND should be next day midnight UTC", expectedDtend, values.getAsLong(Events.DTEND))
    }

    @Test
    fun `all-day 3-day event converts to correct exclusive end`() {
        // KashCal: 3-day all-day event Feb 15-17
        // Stored as startTs = Feb 15 00:00 UTC, endTs = Feb 17 23:59:59.999 UTC (inclusive)
        // CalendarProvider expects: DTSTART = Feb 15 00:00 UTC, DTEND = Feb 18 00:00 UTC (exclusive)
        val feb15Start = LocalDate.of(2026, 2, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val feb17EndInclusive = LocalDate.of(2026, 2, 17)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() + 86_400_000 - 1 // Feb 17 23:59:59.999

        val values = buildEventValues(
            title = "3-Day Event",
            description = null,
            location = null,
            startTs = feb15Start,
            endTs = feb17EndInclusive,
            isAllDay = true,
            rrule = null,
            duration = null,
            timezone = "UTC"
        )

        // DTEND should be Feb 18 00:00 UTC (exclusive)
        val expectedDtend = LocalDate.of(2026, 2, 18)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals("DTEND should be Feb 18 00:00 UTC", expectedDtend, values.getAsLong(Events.DTEND))
    }

    @Test
    fun `all-day event uses UTC timezone`() {
        val jan15Start = LocalDate.of(2026, 1, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val values = buildEventValues(
            title = "All-day Event",
            description = null,
            location = null,
            startTs = jan15Start,
            endTs = jan15Start + 86_400_000 - 1,
            isAllDay = true,
            rrule = null,
            duration = null,
            timezone = "America/New_York" // Should be ignored for all-day
        )

        assertEquals("All-day events should use UTC timezone", "UTC", values.getAsString(Events.EVENT_TIMEZONE))
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))
    }

    // ========== Timed Event Handling ==========

    @Test
    fun `timed single event uses DTEND not DURATION`() {
        val startTs = 1704067200000L // Some timestamp
        val endTs = startTs + 3_600_000 // 1 hour later

        val values = buildEventValues(
            title = "Meeting",
            description = null,
            location = null,
            startTs = startTs,
            endTs = endTs,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "America/Los_Angeles"
        )

        assertEquals("Single event should have DTEND", endTs, values.getAsLong(Events.DTEND))
        assertNull("Single event should not have DURATION", values.getAsString(Events.DURATION))
        assertEquals(0, values.getAsInteger(Events.ALL_DAY))
    }

    @Test
    fun `timed event preserves provided timezone`() {
        val values = buildEventValues(
            title = "Meeting",
            description = null,
            location = null,
            startTs = 1704067200000L,
            endTs = 1704070800000L,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "Europe/London"
        )

        assertEquals("Europe/London", values.getAsString(Events.EVENT_TIMEZONE))
    }

    // ========== Recurring Event Handling ==========

    @Test
    fun `recurring event uses DURATION not DTEND`() {
        val startTs = 1704067200000L
        val endTs = startTs + 3_600_000 // 1 hour

        val values = buildEventValues(
            title = "Daily Standup",
            description = null,
            location = null,
            startTs = startTs,
            endTs = endTs,
            isAllDay = false,
            rrule = "FREQ=DAILY;COUNT=5",
            duration = null,
            timezone = "America/New_York"
        )

        assertEquals("PT1H", values.getAsString(Events.DURATION))
        assertNull("Recurring event should not have DTEND", values.getAsLong(Events.DTEND))
        assertEquals("FREQ=DAILY;COUNT=5", values.getAsString(Events.RRULE))
    }

    @Test
    fun `recurring event calculates DURATION correctly for 90 minutes`() {
        val startTs = 1704067200000L
        val endTs = startTs + 5_400_000 // 90 minutes

        val values = buildEventValues(
            title = "Long Meeting",
            description = null,
            location = null,
            startTs = startTs,
            endTs = endTs,
            isAllDay = false,
            rrule = "FREQ=WEEKLY",
            duration = null,
            timezone = "UTC"
        )

        assertEquals("PT1H30M", values.getAsString(Events.DURATION))
    }

    @Test
    fun `recurring event with provided duration uses provided value`() {
        val values = buildEventValues(
            title = "Custom Duration",
            description = null,
            location = null,
            startTs = 1704067200000L,
            endTs = null, // No endTs when duration provided
            isAllDay = false,
            rrule = "FREQ=DAILY",
            duration = "PT2H",
            timezone = "UTC"
        )

        assertEquals("PT2H", values.getAsString(Events.DURATION))
        assertNull(values.getAsLong(Events.DTEND))
    }

    @Test
    fun `recurring all-day event uses DURATION P1D`() {
        val jan15Start = LocalDate.of(2026, 1, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val jan15EndInclusive = jan15Start + 86_400_000 - 1

        val values = buildEventValues(
            title = "Daily All-Day",
            description = null,
            location = null,
            startTs = jan15Start,
            endTs = jan15EndInclusive,
            isAllDay = true,
            rrule = "FREQ=DAILY",
            duration = null,
            timezone = "UTC"
        )

        assertEquals("P1D", values.getAsString(Events.DURATION))
        assertNull("Recurring all-day should not have DTEND", values.getAsLong(Events.DTEND))
        assertEquals("UTC", values.getAsString(Events.EVENT_TIMEZONE))
    }

    // ========== Field Mapping ==========

    @Test
    fun `all text fields are mapped correctly`() {
        val values = buildEventValues(
            title = "Team Meeting",
            description = "Weekly sync",
            location = "Room 101",
            startTs = 1704067200000L,
            endTs = 1704070800000L,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "America/Chicago"
        )

        assertEquals("Team Meeting", values.getAsString(Events.TITLE))
        assertEquals("Weekly sync", values.getAsString(Events.DESCRIPTION))
        assertEquals("Room 101", values.getAsString(Events.EVENT_LOCATION))
        assertEquals(1704067200000L, values.getAsLong(Events.DTSTART))
    }

    @Test
    fun `null description and location are handled`() {
        val values = buildEventValues(
            title = "Minimal Event",
            description = null,
            location = null,
            startTs = 1704067200000L,
            endTs = 1704070800000L,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "UTC"
        )

        assertNull(values.getAsString(Events.DESCRIPTION))
        assertNull(values.getAsString(Events.EVENT_LOCATION))
    }

    @Test
    fun `empty strings for description and location are preserved`() {
        val values = buildEventValues(
            title = "Event",
            description = "",
            location = "",
            startTs = 1704067200000L,
            endTs = 1704070800000L,
            isAllDay = false,
            rrule = null,
            duration = null,
            timezone = "UTC"
        )

        // Empty strings should be stored (allows clearing via update)
        assertEquals("", values.getAsString(Events.DESCRIPTION))
        assertEquals("", values.getAsString(Events.EVENT_LOCATION))
    }

    // ========== Duration Format ==========

    @Test
    fun `duration format handles hours only`() {
        val values = buildEventValues(
            title = "Event",
            description = null,
            location = null,
            startTs = 1704067200000L,
            endTs = 1704067200000L + 7_200_000, // 2 hours
            isAllDay = false,
            rrule = "FREQ=DAILY",
            duration = null,
            timezone = "UTC"
        )

        assertEquals("PT2H", values.getAsString(Events.DURATION))
    }

    @Test
    fun `duration format handles minutes only`() {
        val values = buildEventValues(
            title = "Event",
            description = null,
            location = null,
            startTs = 1704067200000L,
            endTs = 1704067200000L + 1_800_000, // 30 minutes
            isAllDay = false,
            rrule = "FREQ=DAILY",
            duration = null,
            timezone = "UTC"
        )

        assertEquals("PT30M", values.getAsString(Events.DURATION))
    }

    @Test
    fun `duration format handles days for multi-day all-day recurring`() {
        // 3-day recurring all-day event
        val feb15Start = LocalDate.of(2026, 2, 15)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val feb17EndInclusive = LocalDate.of(2026, 2, 17)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() + 86_400_000 - 1

        val values = buildEventValues(
            title = "3-Day Recurring",
            description = null,
            location = null,
            startTs = feb15Start,
            endTs = feb17EndInclusive,
            isAllDay = true,
            rrule = "FREQ=YEARLY",
            duration = null,
            timezone = "UTC"
        )

        assertEquals("P3D", values.getAsString(Events.DURATION))
    }
}
