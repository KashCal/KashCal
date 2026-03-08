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
}
