package org.onekash.kashcal.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for formatEventTimeDisplay function.
 * Tests multi-day event "Day X of Y" display logic.
 *
 * IMPORTANT: All-day events use UTC for date calculations to preserve calendar dates.
 * Timed events use local timezone for user's perspective.
 */
class FormatEventTimeDisplayTest {

    // Fixed timezone for deterministic tests (used for timed events)
    private val fixedZone = ZoneId.of("America/New_York")

    // Helper to create test events
    private fun createEvent(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean = false,
        rrule: String? = null,
        originalEventId: Long? = null
    ) = Event(
        id = 1L,
        uid = "test-uid",
        calendarId = 1L,
        title = "Test Event",
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        rrule = rrule,
        originalEventId = originalEventId,
        dtstamp = System.currentTimeMillis()
    )

    // Helper to get timestamp for a specific date/time in the test timezone (for timed events)
    private fun getTimestamp(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, fixedZone)
            .toInstant()
            .toEpochMilli()
    }

    // Helper to get UTC timestamp for all-day events
    // All-day events are stored as UTC midnight per RFC 5545
    private fun getUtcTimestamp(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
    }

    // ========== Single-Day Events ==========

    @Test
    fun `single day all-day event returns All day`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),
            isAllDay = true
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertEquals("All day", result)
    }

    @Test
    fun `single day timed event returns time range`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0)
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertEquals("9:00 AM - 5:00 PM", result)
    }

    @Test
    fun `single day PM to PM event returns correct format`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 14, 30),
            endTs = getTimestamp(2023, 12, 24, 16, 45)
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertEquals("2:30 PM - 4:45 PM", result)
    }

    // ========== Multi-Day All-Day Events ==========

    @Test
    fun `multi-day all-day day 1 returns Day 1 of N`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // 3 days
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 24, 12, 0)  // Day 1 (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 1 of 3", result)
    }

    @Test
    fun `multi-day all-day day 2 returns Day 2 of N`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // 3 days
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 25, 12, 0)  // Day 2 (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 2 of 3", result)
    }

    @Test
    fun `multi-day all-day last day returns Day N of N`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // 3 days
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 26, 12, 0)  // Day 3 (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 3 of 3", result)
    }

    // ========== Multi-Day Timed Events ==========

    @Test
    fun `multi-day timed day 1 shows start time`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 25, 17, 0)  // 2 days
        )
        val selectedDate = getTimestamp(2023, 12, 24)  // Day 1
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 1 of 2 · starts 9:00 AM", result)
    }

    @Test
    fun `multi-day timed last day shows end time`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 25, 17, 0)  // 2 days
        )
        val selectedDate = getTimestamp(2023, 12, 25)  // Day 2
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 2 of 2 · ends 5:00 PM", result)
    }

    @Test
    fun `multi-day timed middle day returns Day X of N without time`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 27, 17, 0)  // 4 days
        )
        val selectedDate = getTimestamp(2023, 12, 25)  // Day 2 (middle)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 2 of 4", result)
    }

    @Test
    fun `multi-day timed day 3 of 4 returns correct day`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 27, 17, 0)  // 4 days
        )
        val selectedDate = getTimestamp(2023, 12, 26)  // Day 3
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 3 of 4", result)
    }

    // ========== Recurring Indicator ==========

    @Test
    fun `recurring multi-day event shows indicator on day 1`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),
            isAllDay = true,
            rrule = "FREQ=WEEKLY"
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 24, 12, 0)  // Day 1 (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertTrue("Should contain recurring indicator", result.contains("\uD83D\uDD01"))
        assertEquals("Day 1 of 3 \uD83D\uDD01", result)
    }

    @Test
    fun `recurring multi-day timed event day 1 shows start time and indicator`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 25, 17, 0),
            rrule = "FREQ=MONTHLY"
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertEquals("Day 1 of 2 · starts 9:00 AM \uD83D\uDD01", result)
    }

    @Test
    fun `non-recurring event does not show indicator`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),
            isAllDay = true,
            rrule = null
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 24, 12, 0)  // Day 1 (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertTrue("Should not contain recurring indicator", !result.contains("\uD83D\uDD01"))
    }

    // ========== Single-Day Recurring Events (BUG FIX) ==========

    @Test
    fun `single day recurring timed event shows icon`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0),
            rrule = "FREQ=DAILY"
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertTrue("Single-day recurring should show icon", result.contains("\uD83D\uDD01"))
        assertEquals("9:00 AM - 5:00 PM \uD83D\uDD01", result)
    }

    @Test
    fun `single day recurring all-day event shows icon`() {
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),
            isAllDay = true,
            rrule = "FREQ=WEEKLY"
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertTrue("Single-day all-day recurring should show icon", result.contains("\uD83D\uDD01"))
        assertEquals("All day \uD83D\uDD01", result)
    }

    @Test
    fun `single day non-recurring timed event does not show icon`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0),
            rrule = null
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertTrue("Single-day non-recurring should not show icon", !result.contains("\uD83D\uDD01"))
        assertEquals("9:00 AM - 5:00 PM", result)
    }

    // ========== Exception Events (BUG FIX) ==========

    @Test
    fun `exception event single-day shows icon`() {
        // Exception events have originalEventId but no rrule
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 10, 0),  // Modified time
            endTs = getTimestamp(2023, 12, 24, 18, 0),
            rrule = null,
            originalEventId = 100L
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertTrue("Exception event should show recurring icon", result.contains("\uD83D\uDD01"))
        assertEquals("10:00 AM - 6:00 PM \uD83D\uDD01", result)
    }

    @Test
    fun `exception event all-day single-day shows icon`() {
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),
            isAllDay = true,
            rrule = null,
            originalEventId = 100L
        )
        val result = formatEventTimeDisplay(event, event.startTs, fixedZone)
        assertTrue("Exception all-day event should show icon", result.contains("\uD83D\uDD01"))
        assertEquals("All day \uD83D\uDD01", result)
    }

    @Test
    fun `exception event multi-day shows icon`() {
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),
            isAllDay = true,
            rrule = null,
            originalEventId = 100L
        )
        val selectedDate = getTimestamp(2023, 12, 24, 12, 0)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertTrue("Multi-day exception should show icon", result.contains("\uD83D\uDD01"))
        assertEquals("Day 1 of 3 \uD83D\uDD01", result)
    }

    @Test
    fun `BUG FIX - exception event without rrule shows recurring icon`() {
        // This was the original bug: exception events showed no icon
        // because the check was only: event.rrule != null
        val exception = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0),
            rrule = null,  // No rrule!
            originalEventId = 1L  // But has originalEventId
        )

        val result = formatEventTimeDisplay(exception, exception.startTs, fixedZone)
        assertTrue("Exception event MUST show recurring icon (BUG FIX)", result.contains("\uD83D\uDD01"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `event spanning year boundary calculates correctly`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 31, 0, 0),
            endTs = getUtcTimestamp(2024, 1, 1, 23, 59),  // 2 days spanning year
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2024, 1, 1, 12, 0)  // Day 2 (Jan 1, local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 2 of 2", result)
    }

    @Test
    fun `leap year Feb 28 to Mar 1 calculates correctly`() {
        // 2024 is a leap year (Feb has 29 days)
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2024, 2, 28, 0, 0),
            endTs = getUtcTimestamp(2024, 3, 1, 23, 59),  // 3 days with leap day
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2024, 2, 29, 12, 0)  // Feb 29 (day 2, local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 2 of 3", result)
    }

    @Test
    fun `non-leap year Feb 28 to Mar 1 is 2 days`() {
        // 2023 is not a leap year
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 2, 28, 0, 0),
            endTs = getUtcTimestamp(2023, 3, 1, 23, 59),  // 2 days (no Feb 29)
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 3, 1, 12, 0)  // Mar 1 (day 2, local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 2 of 2", result)
    }

    @Test
    fun `5-day event shows correct day count`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 20, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),  // 5 days
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 22, 12, 0)  // Day 3 (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 3 of 5", result)
    }

    @Test
    fun `selected date before event start coerces to day 1`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 20, 12, 0)  // Before event (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 1 of 3", result)
    }

    @Test
    fun `selected date after event end coerces to last day`() {
        // All-day events use UTC timestamps
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),
            isAllDay = true
        )
        // Selected date from calendar picker is ALWAYS local time
        val selectedDate = getTimestamp(2023, 12, 30, 12, 0)  // After event (local time)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("Day 3 of 3", result)
    }

    // ========== BUG FIX VERIFICATION TESTS ==========

    @Test
    fun `all-day event selected with LOCAL date shows correct day in negative offset TZ`() {
        // THE BUG: When user selects Dec 25 in EST, the local timestamp is different from UTC
        // but the code was incorrectly interpreting selectedDateMillis as UTC for all-day events.
        //
        // Event: Dec 24-26 all-day (stored as UTC midnight)
        // User in EST (UTC-5) selects Dec 25 from calendar picker
        // Calendar picker returns local time Dec 25 12:00 EST = Dec 25 17:00 UTC
        // BUT the buggy code would interpret this as "UTC Dec 25" and compare to "UTC Dec 24 start"
        // which could result in wrong day number due to timezone math.
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),  // Dec 24 00:00 UTC
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // Dec 26 23:59 UTC
            isAllDay = true
        )

        // User selects Dec 25 in LOCAL time (this is what the calendar picker provides)
        val selectedDateLocal = getTimestamp(2023, 12, 25, 12, 0)  // Dec 25 noon EST

        val result = formatEventTimeDisplay(event, selectedDateLocal, fixedZone)
        assertEquals("Day 2 of 3", result)
    }

    @Test
    fun `all-day event shows correct day when local time is late night crossing UTC boundary`() {
        // Edge case: User at 11 PM EST on Dec 24 (which is Dec 25 04:00 UTC)
        // The calendar picker date is "Dec 24" (user's local date), not Dec 25
        // Event: Dec 24-26 all-day (UTC)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),
            isAllDay = true
        )

        // Dec 24 11 PM EST = Dec 25 04:00 UTC
        // But the calendar picker says "Dec 24" from the user's perspective
        val selectedDateLocal = getTimestamp(2023, 12, 24, 23, 0)

        val result = formatEventTimeDisplay(event, selectedDateLocal, fixedZone)
        assertEquals("Day 1 of 3", result)  // Should be Day 1 because user selected Dec 24 (local)
    }

    @Test
    fun `all-day event in positive offset timezone shows correct day`() {
        // Test with Tokyo (UTC+9) to ensure fix works for positive offsets too
        val tokyoZone = ZoneId.of("Asia/Tokyo")

        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),  // Dec 24 00:00 UTC
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // Dec 26 23:59 UTC
            isAllDay = true
        )

        // User in Tokyo selects Dec 25 from calendar picker (local time)
        val selectedDateTokyo = ZonedDateTime.of(2023, 12, 25, 12, 0, 0, 0, tokyoZone)
            .toInstant()
            .toEpochMilli()

        val result = formatEventTimeDisplay(event, selectedDateTokyo, tokyoZone)
        assertEquals("Day 2 of 3", result)
    }

    @Test
    fun `single-day all-day event returns All day not Day 1 of 1`() {
        // Regression test: single-day all-day events should show "All day", not "Day 1 of 1"
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),  // Same day
            isAllDay = true
        )
        val selectedDate = getTimestamp(2023, 12, 24, 12, 0)
        val result = formatEventTimeDisplay(event, selectedDate, fixedZone)
        assertEquals("All day", result)
    }
}
