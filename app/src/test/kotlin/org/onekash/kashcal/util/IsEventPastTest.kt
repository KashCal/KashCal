package org.onekash.kashcal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Tests [DateTimeUtils.isEventPast]: an all-day occurrence compares day codes, a timed one
 * compares instants (rule on the function).
 *
 * The all-day trap: at 6 PM Central (UTC-6) on Jan 15 the clock reads Jan 16 00:00 UTC, past a
 * Jan 15 all-day endTs of 23:59:59.999 UTC, so an instant compare would gray out today's event.
 * No zone is set; each test passes `nowMs` and the local `todayDayCode` that zone would give.
 */
class IsEventPastTest {

    // ========== All-Day Event Tests ==========

    @Test
    fun `all-day event today at 6 PM UTC-6 is NOT past`() {
        // Jan 15, 2025, 6 PM Central (UTC-6) is Jan 16, 00:00 UTC.
        val jan15_6pm_central_as_utc = createUtcMillis(2025, 1, 16, 0, 0)

        // An all-day event ending Jan 15 stores the end of its UTC day, 23:59:59.999.
        val endTs = createUtcMillis(2025, 1, 15, 23, 59, 59, 999)
        val endDay = 20250115  // Jan 15, 2025

        // Today in Central timezone is still Jan 15
        val todayDayCode = 20250115

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = true,
            nowMs = jan15_6pm_central_as_utc,
            todayDayCode = todayDayCode
        )

        assertFalse("All-day event for today should NOT be past at 6 PM local time", isPast)
    }

    @Test
    fun `all-day event yesterday is past`() {
        val nowMs = createUtcMillis(2025, 1, 16, 12, 0)
        val endTs = createUtcMillis(2025, 1, 15, 23, 59, 59, 999)
        val endDay = 20250115  // Jan 15
        val todayDayCode = 20250116  // Jan 16

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = true,
            nowMs = nowMs,
            todayDayCode = todayDayCode
        )

        assertTrue("All-day event from yesterday should be past", isPast)
    }

    @Test
    fun `all-day event tomorrow is NOT past`() {
        val nowMs = createUtcMillis(2025, 1, 15, 12, 0)
        val endTs = createUtcMillis(2025, 1, 16, 23, 59, 59, 999)
        val endDay = 20250116  // Jan 16
        val todayDayCode = 20250115  // Jan 15

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = true,
            nowMs = nowMs,
            todayDayCode = todayDayCode
        )

        assertFalse("All-day event for tomorrow should NOT be past", isPast)
    }

    @Test
    fun `multi-day all-day event on last day at 6 PM is NOT past`() {
        // 3-day event, Jan 14-16; now is Jan 16 at 6 PM UTC-6, midnight UTC the next day.
        val jan16_6pm_as_utc = createUtcMillis(2025, 1, 17, 0, 0)

        val endTs = createUtcMillis(2025, 1, 16, 23, 59, 59, 999)
        val endDay = 20250116  // Jan 16
        val todayDayCode = 20250116  // Still Jan 16 locally

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = true,
            nowMs = jan16_6pm_as_utc,
            todayDayCode = todayDayCode
        )

        assertFalse("Multi-day event on its last day should NOT be past", isPast)
    }

    @Test
    fun `multi-day all-day event spanning today (middle day) is NOT past`() {
        // 3-day event: Jan 14-16, currently Jan 15 at 6 PM local
        val jan15_6pm_as_utc = createUtcMillis(2025, 1, 16, 0, 0)

        val endTs = createUtcMillis(2025, 1, 16, 23, 59, 59, 999)
        val endDay = 20250116  // Jan 16
        val todayDayCode = 20250115  // Jan 15

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = true,
            nowMs = jan15_6pm_as_utc,
            todayDayCode = todayDayCode
        )

        assertFalse("Multi-day event spanning today should NOT be past", isPast)
    }

    @Test
    fun `all-day event at 11_59 PM local time is NOT past`() {
        // Jan 15 at 11:59 PM Central = Jan 16 05:59 UTC
        val jan15_1159pm_as_utc = createUtcMillis(2025, 1, 16, 5, 59)

        val endTs = createUtcMillis(2025, 1, 15, 23, 59, 59, 999)
        val endDay = 20250115
        val todayDayCode = 20250115  // Still Jan 15 locally

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = true,
            nowMs = jan15_1159pm_as_utc,
            todayDayCode = todayDayCode
        )

        assertFalse("All-day event should NOT be past at 11:59 PM on same day", isPast)
    }

    // ========== Timed Event Tests ==========

    @Test
    fun `timed event that has ended is past`() {
        val nowMs = createUtcMillis(2025, 1, 15, 15, 0)  // 3 PM
        val endTs = createUtcMillis(2025, 1, 15, 14, 0)  // Ended at 2 PM
        val endDay = 20250115

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = false,
            nowMs = nowMs,
            todayDayCode = 20250115
        )

        assertTrue("Timed event that ended should be past", isPast)
    }

    @Test
    fun `timed event that has NOT ended is NOT past`() {
        val nowMs = createUtcMillis(2025, 1, 15, 13, 0)  // 1 PM
        val endTs = createUtcMillis(2025, 1, 15, 14, 0)  // Ends at 2 PM
        val endDay = 20250115

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = false,
            nowMs = nowMs,
            todayDayCode = 20250115
        )

        assertFalse("Timed event that hasn't ended should NOT be past", isPast)
    }

    @Test
    fun `timed event ending exactly now is NOT past`() {
        val nowMs = createUtcMillis(2025, 1, 15, 14, 0)
        val endTs = createUtcMillis(2025, 1, 15, 14, 0)  // Same as now
        val endDay = 20250115

        val isPast = DateTimeUtils.isEventPast(
            endTs = endTs,
            endDay = endDay,
            isAllDay = false,
            nowMs = nowMs,
            todayDayCode = 20250115
        )

        assertFalse("Timed event ending exactly now should NOT be past (endTs < nowMs is false)", isPast)
    }

    // ========== Helper Functions ==========

    private fun createUtcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
        millis: Int = 0
    ): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis
    }
}
