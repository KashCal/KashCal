package org.onekash.kashcal.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for formatSearchResultDate function.
 * Tests recurring icon and multi-day date range display logic.
 *
 * IMPORTANT: All-day events use UTC for date calculations to preserve calendar dates.
 * Timed events use local timezone for user's perspective.
 */
class FormatSearchResultDateTest {

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

    // ========== Recurring Icon Tests ==========

    @Test
    fun `master recurring event shows icon`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0),
            rrule = "FREQ=WEEKLY"
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertTrue("Should contain recurring icon", result.contains("\uD83D\uDD01"))
    }

    @Test
    fun `exception event shows icon`() {
        // Exception events have originalEventId but no rrule
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 10, 0),  // Modified time
            endTs = getTimestamp(2023, 12, 24, 18, 0),
            rrule = null,  // Exception has no rrule
            originalEventId = 100L  // Points to master
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertTrue("Exception event should show recurring icon", result.contains("\uD83D\uDD01"))
    }

    @Test
    fun `non-recurring event does not show icon`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0),
            rrule = null,
            originalEventId = null
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertTrue("Non-recurring event should not show icon", !result.contains("\uD83D\uDD01"))
    }

    // ========== Single-Day Events ==========

    @Test
    fun `single-day all-day shows date only`() {
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),
            isAllDay = true
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023", result)
    }

    @Test
    fun `same-day timed event shows time`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 24, 17, 0)
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 · 9:00 AM", result)
    }

    @Test
    fun `same-day PM event shows correct time`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 14, 30),
            endTs = getTimestamp(2023, 12, 24, 16, 45)
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 · 2:30 PM", result)
    }

    // ========== Multi-Day All-Day Events ==========

    @Test
    fun `multi-day all-day shows date range`() {
        // 3-day event: Dec 24-26
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // Inclusive - last second of Dec 26
            isAllDay = true
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 → Dec 26, 2023", result)
    }

    @Test
    fun `5-day all-day vacation shows correct range`() {
        // Dec 20-24 (5 days)
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 20, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),  // Inclusive - last second of Dec 24
            isAllDay = true
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 20, 2023 → Dec 24, 2023", result)
    }

    @Test
    fun `multi-day all-day with recurring shows range and icon`() {
        // Parsers store endTs as INCLUSIVE
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // Inclusive
            isAllDay = true,
            rrule = "FREQ=YEARLY"
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 → Dec 26, 2023 \uD83D\uDD01", result)
    }

    // ========== Multi-Day Timed Events ==========

    @Test
    fun `multi-day timed event shows date range`() {
        // Conference Dec 24 9am - Dec 25 5pm
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 25, 17, 0)
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 → Dec 25, 2023", result)
    }

    @Test
    fun `multi-day timed with recurring shows range and icon`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 24, 9, 0),
            endTs = getTimestamp(2023, 12, 25, 17, 0),
            rrule = "FREQ=MONTHLY"
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 → Dec 25, 2023 \uD83D\uDD01", result)
    }

    // ========== Cross-Year Events ==========

    @Test
    fun `cross-year all-day event shows correct range`() {
        // New Year trip Dec 31 - Jan 2 (3 days)
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 31, 0, 0),
            endTs = getUtcTimestamp(2024, 1, 2, 23, 59),  // Inclusive - last second of Jan 2
            isAllDay = true
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 31, 2023 → Jan 2, 2024", result)
    }

    @Test
    fun `cross-year timed event shows correct range`() {
        val event = createEvent(
            startTs = getTimestamp(2023, 12, 31, 20, 0),
            endTs = getTimestamp(2024, 1, 1, 2, 0)
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 31, 2023 → Jan 1, 2024", result)
    }

    // ========== Edge Cases ==========

    @Test
    fun `leap year Feb 28 to Mar 1 all-day`() {
        // 2024 is a leap year - Feb 28, 29, Mar 1 = 3 days
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2024, 2, 28, 0, 0),
            endTs = getUtcTimestamp(2024, 3, 1, 23, 59),  // Inclusive - last second of Mar 1
            isAllDay = true
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Feb 28, 2024 → Mar 1, 2024", result)
    }

    @Test
    fun `non-leap year Feb 28 to Mar 1 all-day`() {
        // 2023 is not a leap year - Feb 28, Mar 1 = 2 days
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 2, 28, 0, 0),
            endTs = getUtcTimestamp(2023, 3, 1, 23, 59),  // Inclusive - last second of Mar 1
            isAllDay = true
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Feb 28, 2023 → Mar 1, 2023", result)
    }

    @Test
    fun `exception event multi-day shows range and icon`() {
        // Exception to a recurring multi-day event (Dec 24-26 = 3 days)
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // Inclusive - last second of Dec 26
            isAllDay = true,
            rrule = null,
            originalEventId = 100L
        )
        val result = formatSearchResultDate(event, fixedZone)
        assertEquals("Dec 24, 2023 → Dec 26, 2023 \uD83D\uDD01", result)
    }

    // ========== Timezone Edge Cases ==========

    @Test
    fun `all-day event in positive offset timezone shows correct date`() {
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        // Single day all-day event: Dec 24
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 24, 23, 59),  // Inclusive - last second of Dec 24
            isAllDay = true
        )
        val result = formatSearchResultDate(event, tokyoZone)
        assertEquals("Dec 24, 2023", result)
    }

    @Test
    fun `all-day multi-day event in negative offset timezone shows correct range`() {
        // Even in LA (UTC-8), all-day dates use UTC, so Dec 24-26 stays Dec 24-26
        // Parsers store endTs as INCLUSIVE (last second of last day)
        val laZone = ZoneId.of("America/Los_Angeles")
        val event = createEvent(
            startTs = getUtcTimestamp(2023, 12, 24, 0, 0),
            endTs = getUtcTimestamp(2023, 12, 26, 23, 59),  // Inclusive - last second of Dec 26
            isAllDay = true
        )
        val result = formatSearchResultDate(event, laZone)
        assertEquals("Dec 24, 2023 → Dec 26, 2023", result)
    }
}
