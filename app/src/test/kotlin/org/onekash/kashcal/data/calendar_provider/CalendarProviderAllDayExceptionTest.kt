package org.onekash.kashcal.data.calendar_provider

import android.content.ContentValues
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for buildCanceledExceptionValues() helper function.
 *
 * Verifies CalendarProvider all-day recurring event exception handling:
 * - ALL_DAY flag, EVENT_TIMEZONE, ORIGINAL_ALL_DAY for all-day events
 * - DTSTART/DTEND: 24h span at UTC midnight for all-day, same ts for timed
 * - ORIGINAL_INSTANCE_TIME normalization to UTC midnight for all-day events
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CalendarProviderAllDayExceptionTest {

    @Test
    fun `buildCanceledExceptionValues sets ALL_DAY and TIMEZONE for all-day event`() {
        val midnight = 1709251200000L // 2024-03-01 00:00:00 UTC
        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = midnight,
            isAllDay = true
        )

        assertEquals(1, values.getAsInteger(Events.ALL_DAY))
        assertEquals("UTC", values.getAsString(Events.EVENT_TIMEZONE))
        assertEquals(1, values.getAsInteger(Events.ORIGINAL_ALL_DAY))
        assertEquals(midnight, values.getAsLong(Events.DTSTART))
        assertEquals(midnight + 86_400_000L, values.getAsLong(Events.DTEND))
        assertEquals(Events.STATUS_CANCELED, values.getAsInteger(Events.STATUS))
        assertEquals(1L, values.getAsLong(Events.CALENDAR_ID))
        assertEquals(100L, values.getAsLong(Events.ORIGINAL_ID))
    }

    @Test
    fun `buildCanceledExceptionValues uses raw timestamps for timed event`() {
        val ts = 1709280000000L // some non-midnight timestamp
        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = ts,
            isAllDay = false
        )

        assertEquals(ts, values.getAsLong(Events.DTSTART))
        assertEquals(ts, values.getAsLong(Events.DTEND))
        assertNull(values.getAsInteger(Events.ALL_DAY))
        assertEquals(Events.STATUS_CANCELED, values.getAsInteger(Events.STATUS))
    }

    @Test
    fun `buildCanceledExceptionValues normalizes ORIGINAL_INSTANCE_TIME for all-day`() {
        // Pass non-midnight timestamp with isAllDay=true
        val nonMidnight = 1709251200000L + (8 * 3600 * 1000) // 08:00 UTC
        val expectedMidnight = 1709251200000L // 00:00 UTC

        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = nonMidnight,
            isAllDay = true
        )

        assertEquals(expectedMidnight, values.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
    }

    @Test
    fun `buildCanceledExceptionValues preserves ORIGINAL_INSTANCE_TIME for timed`() {
        val ts = 1709280000000L
        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = ts,
            isAllDay = false
        )

        assertEquals(ts, values.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
    }

    @Test
    fun `buildCanceledExceptionValues handles multi-day spanning month boundary`() {
        // All-day event Jan 30 - Feb 2, originalInstanceTime = Jan 30 UTC midnight
        val jan30Midnight = 1706572800000L // 2024-01-30 00:00:00 UTC
        val jan31Midnight = 1706659200000L // 2024-01-31 00:00:00 UTC

        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = jan30Midnight,
            isAllDay = true
        )

        assertEquals(jan30Midnight, values.getAsLong(Events.DTSTART))
        assertEquals(jan31Midnight, values.getAsLong(Events.DTEND))
        assertEquals(1, values.getAsInteger(Events.ALL_DAY))
        assertEquals("UTC", values.getAsString(Events.EVENT_TIMEZONE))
    }

    @Test
    fun `buildCanceledExceptionValues handles multi-day spanning year boundary`() {
        // All-day event Dec 30 - Jan 2, originalInstanceTime = Dec 31 UTC midnight
        val dec31Midnight = 1735603200000L // 2024-12-31 00:00:00 UTC
        val jan1Midnight = 1735689600000L  // 2025-01-01 00:00:00 UTC

        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = dec31Midnight,
            isAllDay = true
        )

        assertEquals(dec31Midnight, values.getAsLong(Events.DTSTART))
        assertEquals(jan1Midnight, values.getAsLong(Events.DTEND))
    }

    @Test
    fun `buildCanceledExceptionValues handles leap year boundary`() {
        // All-day event Feb 28 2024 (leap year), originalInstanceTime = Feb 28 UTC midnight
        val feb28Midnight = 1709078400000L // 2024-02-28 00:00:00 UTC
        val feb29Midnight = 1709164800000L // 2024-02-29 00:00:00 UTC (exists in leap year)

        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = feb28Midnight,
            isAllDay = true
        )

        assertEquals(feb28Midnight, values.getAsLong(Events.DTSTART))
        assertEquals(feb29Midnight, values.getAsLong(Events.DTEND))
    }

    @Test
    fun `buildCanceledExceptionValues handles non-leap year boundary`() {
        // All-day event Feb 28 2025 (non-leap), originalInstanceTime = Feb 28 UTC midnight
        val feb28Midnight = 1740700800000L // 2025-02-28 00:00:00 UTC
        val mar1Midnight = 1740787200000L  // 2025-03-01 00:00:00 UTC (skips Feb 29)

        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = feb28Midnight,
            isAllDay = true
        )

        assertEquals(feb28Midnight, values.getAsLong(Events.DTSTART))
        assertEquals(mar1Midnight, values.getAsLong(Events.DTEND))
    }

    @Test
    fun `buildCanceledExceptionValues normalizes DST-offset time for all-day`() {
        // Mar 10 2024 02:00 UTC - DST transition time in America/New_York, not midnight
        val dstTransitionTime = 1710036000000L // 2024-03-10 02:00:00 UTC
        val mar10Midnight = 1710028800000L     // 2024-03-10 00:00:00 UTC

        val values = buildCanceledExceptionValues(
            calendarId = 1L,
            masterEventId = 100L,
            originalInstanceTime = dstTransitionTime,
            isAllDay = true
        )

        // isAllDay=true should normalize ORIGINAL_INSTANCE_TIME to UTC midnight
        assertEquals(mar10Midnight, values.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals(mar10Midnight, values.getAsLong(Events.DTSTART))
        assertEquals(mar10Midnight + 86_400_000L, values.getAsLong(Events.DTEND))
    }
}
