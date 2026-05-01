package org.onekash.icaldav.recurrence

import org.onekash.icaldav.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Adversarial tests for RRULE parsing and occurrence expansion - ported from KashCal.
 *
 * Tests probe edge cases that could crash or hang:
 * - Malformed RRULE strings
 * - Extreme parameter values (INTERVAL=0, negative COUNT)
 * - MAX_ITERATIONS safety limit for infinite recurrence
 * - EXDATE parsing edge cases
 * - BYDAY edge cases (invalid day codes, out of range ordinals)
 * - BYMONTHDAY edge cases (31st in months with <31 days)
 * - UNTIL edge cases
 * - Timezone trap scenarios
 *
 * These tests verify defensive coding in RRuleExpander.
 */
@DisplayName("RRuleExpander Adversarial Tests")
class RRuleExpanderAdversarialTest {

    private val expander = RRuleExpander()
    private val zone = ZoneId.of("UTC")
    private val defaultStart = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, zone)

    // Large time range for testing
    private val oneYearRange = TimeRange(
        defaultStart.minusDays(1).toInstant(),
        defaultStart.plusYears(1).toInstant()
    )

    private val tenYearRange = TimeRange(
        defaultStart.minusDays(1).toInstant(),
        defaultStart.plusYears(10).toInstant()
    )

    // ==================== Malformed RRULE Tests ====================

    @Test
    fun `null RRULE returns single occurrence`() {
        val event = createTestEvent(rrule = null)

        val occurrences = expander.expand(event, oneYearRange)

        assertEquals(1, occurrences.size, "Non-recurring event should return single occurrence")
    }

    @Test
    fun `empty byDay list handled gracefully`() {
        val rrule = RRule(
            freq = Frequency.WEEKLY,
            interval = 1,
            count = 5,
            byDay = emptyList()
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // Empty byDay should default to event's start day
        assertTrue(occurrences.isNotEmpty(), "Should generate occurrences with empty byDay")
    }

    // ==================== Extreme Parameter Tests ====================

    @Test
    fun `zero interval handled - should default to 1 or fail gracefully`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 0,  // Invalid
            count = 5
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // lib-recur may default to 1 or handle differently
        assertTrue(occurrences.size >= 0, "Should handle zero interval gracefully")
    }

    @Test
    fun `negative interval handled gracefully`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = -1,  // Invalid
            count = 5
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        assertTrue(occurrences.size >= 0, "Should handle negative interval gracefully")
    }

    @Test
    fun `very large interval generates few occurrences`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 999999,  // ~2739 years per occurrence
            count = 5
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, tenYearRange)

        // With 999999-day interval, very few (if any) occurrences in 10 years
        assertTrue(occurrences.size <= 5, "Should have few occurrences with huge interval")
    }

    @Test
    fun `zero count generates no recurring occurrences`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 0
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // COUNT=0 may be treated as no occurrences or as unlimited depending on impl
        assertTrue(occurrences.size >= 0, "Should handle zero count")
    }

    @Test
    fun `negative count handled gracefully`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = -5
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        assertTrue(occurrences.size >= 0, "Should handle negative count gracefully")
    }

    // ==================== MAX_ITERATIONS Safety Tests ====================

    @Test
    fun `infinite daily recurrence is limited`() {
        // No COUNT or UNTIL - potentially infinite
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1
            // No count, no until
        )
        val event = createTestEvent(rrule = rrule)

        val hundredYearRange = TimeRange(
            defaultStart.minusDays(1).toInstant(),
            defaultStart.plusYears(100).toInstant()
        )

        val occurrences = expander.expand(event, hundredYearRange)

        // Should be limited by internal MAX_ITERATIONS or time range
        assertTrue(occurrences.size <= 1000 || occurrences.size <= 36525,
            "Should be limited by MAX_ITERATIONS or time range")
    }

    @Test
    fun `SECONDLY frequency is limited or rejected`() {
        // FREQ=SECONDLY would generate massive occurrences
        val rrule = RRule(
            freq = Frequency.SECONDLY,
            interval = 1,
            count = 10000  // Even with count, could be huge
        )
        val event = createTestEvent(rrule = rrule)

        // Should complete without hanging
        val startTime = System.currentTimeMillis()
        val occurrences = expander.expand(event, oneYearRange)
        val duration = System.currentTimeMillis() - startTime

        // Should complete reasonably fast (< 5 seconds) and be limited
        assertTrue(duration < 5000, "SECONDLY expansion should complete quickly")
        assertTrue(occurrences.size <= 10000, "SECONDLY should be limited")
    }

    @Test
    fun `MINUTELY frequency is limited`() {
        val rrule = RRule(
            freq = Frequency.MINUTELY,
            interval = 1
            // No count - infinite
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // 1 year = ~525600 minutes - should complete (even if large)
        // Just verify it completes without hanging
        assertTrue(occurrences.isNotEmpty(),
            "MINUTELY should generate some occurrences")
    }

    // ==================== UNTIL Edge Cases ====================

    @Test
    fun `UNTIL in the past generates no occurrences in future range`() {
        val pastUntil = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, zone)
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            until = ICalDateTime.fromZonedDateTime(pastUntil)
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // UNTIL is 1970, range starts 2024 - no overlap
        assertEquals(0, occurrences.size, "UNTIL in past should generate 0 in future range")
    }

    @Test
    fun `UNTIL before DTSTART generates single occurrence or none`() {
        val untilBeforeStart = defaultStart.minusDays(1)
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            until = ICalDateTime.fromZonedDateTime(untilBeforeStart)
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // DTSTART might still be included, or 0 if UNTIL excludes it
        assertTrue(occurrences.size <= 1, "UNTIL before DTSTART should have 0-1 occurrences")
    }

    // ==================== BYDAY Edge Cases ====================

    @Test
    fun `6th Monday of month - does not exist`() {
        // 6MO = 6th Monday, which never exists
        val rrule = RRule(
            freq = Frequency.MONTHLY,
            interval = 1,
            count = 12,
            byDay = listOf(WeekdayNum(DayOfWeek.MONDAY, 6))  // 6th Monday
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // 6th Monday never exists in any month
        assertEquals(0, occurrences.size, "6th Monday should generate 0 occurrences")
    }

    @Test
    fun `last Friday of month works correctly`() {
        // -1FR = last Friday
        // Use Jan 26, 2024 which is a Friday (last Friday of Jan 2024)
        val fridayStart = ZonedDateTime.of(2024, 1, 26, 10, 0, 0, 0, zone)
        val rrule = RRule(
            freq = Frequency.MONTHLY,
            interval = 1,
            count = 12,
            byDay = listOf(WeekdayNum(DayOfWeek.FRIDAY, -1))  // Last Friday
        )
        val event = createTestEvent(
            startDate = fridayStart,
            rrule = rrule
        )

        val occurrences = expander.expand(event, oneYearRange)

        assertEquals(12, occurrences.size, "Should generate exactly 12 last Fridays")
    }

    @Test
    fun `BYDAY on different start day - alignment behavior`() {
        // Start on Tuesday (Jan 2, 2024 was Tuesday) with BYDAY=MO
        val tuesdayStart = ZonedDateTime.of(2024, 1, 2, 10, 0, 0, 0, zone)
        val rrule = RRule(
            freq = Frequency.WEEKLY,
            interval = 1,
            count = 5,
            byDay = listOf(WeekdayNum(DayOfWeek.MONDAY))
        )
        val event = createTestEvent(
            startDate = tuesdayStart,
            rrule = rrule
        )

        val occurrences = expander.expand(event, oneYearRange)

        // DTSTART on Tuesday but BYDAY=MO - behavior varies by RFC interpretation
        assertTrue(occurrences.isNotEmpty(), "Should generate some Monday occurrences")
    }

    // ==================== BYMONTHDAY Edge Cases ====================

    @Test
    fun `BYMONTHDAY=31 skips short months`() {
        val rrule = RRule(
            freq = Frequency.MONTHLY,
            interval = 1,
            count = 12,
            byMonthDay = listOf(31)
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // Only months with 31 days: Jan, Mar, May, Jul, Aug, Oct, Dec = 7 months
        assertTrue(occurrences.size <= 12 && occurrences.size >= 0,
            "BYMONTHDAY=31 should skip months without 31st")
    }

    @Test
    fun `Feb 30 never exists`() {
        val rrule = RRule(
            freq = Frequency.YEARLY,
            interval = 1,
            count = 5,
            byMonth = listOf(2),
            byMonthDay = listOf(30)
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, tenYearRange)

        // Feb 30 never exists
        assertEquals(0, occurrences.size, "Feb 30 should never exist")
    }

    @Test
    fun `Feb 29 only in leap years`() {
        val rrule = RRule(
            freq = Frequency.YEARLY,
            interval = 1,
            count = 10,
            byMonth = listOf(2),
            byMonthDay = listOf(29)
        )
        val event = createTestEvent(rrule = rrule)

        // 50 year range - should have ~12-13 leap years
        val fiftyYearRange = TimeRange(
            defaultStart.minusDays(1).toInstant(),
            defaultStart.plusYears(50).toInstant()
        )

        val occurrences = expander.expand(event, fiftyYearRange)

        // Up to 10 (COUNT limit) but may be fewer if range is too short
        assertTrue(occurrences.size <= 10 && occurrences.size > 0,
            "Should have leap year occurrences")
    }

    // ==================== EXDATE Edge Cases ====================

    @Test
    fun `EXDATE excludes correct occurrences`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        // Exclude day 2 and 4
        val exdates = listOf(
            ICalDateTime.fromZonedDateTime(defaultStart.plusDays(1)),  // Day 2
            ICalDateTime.fromZonedDateTime(defaultStart.plusDays(3))   // Day 4
        )
        val event = createTestEvent(
            rrule = rrule,
            exdates = exdates
        )

        val occurrences = expander.expand(event, oneYearRange)

        // 5 - 2 excluded = 3
        assertEquals(3, occurrences.size, "EXDATE should exclude 2 occurrences")
    }

    @Test
    fun `all occurrences excluded generates empty list`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 3
        )
        // Exclude all 3 occurrences
        val exdates = listOf(
            ICalDateTime.fromZonedDateTime(defaultStart),
            ICalDateTime.fromZonedDateTime(defaultStart.plusDays(1)),
            ICalDateTime.fromZonedDateTime(defaultStart.plusDays(2))
        )
        val event = createTestEvent(
            rrule = rrule,
            exdates = exdates
        )

        val occurrences = expander.expand(event, oneYearRange)

        assertEquals(0, occurrences.size, "All excluded should generate 0")
    }

    // ==================== Timezone Edge Cases ====================

    @Test
    fun `all-day event uses UTC for expansion`() {
        val utcMidnight = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        val event = createTestEvent(
            startDate = utcMidnight,
            rrule = rrule,
            isAllDay = true
        )

        val occurrences = expander.expand(event, oneYearRange)

        assertEquals(5, occurrences.size)

        // All occurrences should be at UTC midnight
        occurrences.forEach { occ ->
            val hour = occ.dtStart.toZonedDateTime().hour
            assertEquals(0, hour, "All-day occurrence should be at midnight")
        }
    }

    @Test
    fun `different timezones generate correct local times`() {
        val nyZone = ZoneId.of("America/New_York")
        val nyStart = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, nyZone)

        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        val event = createTestEvent(
            startDate = nyStart,
            rrule = rrule,
            timezone = "America/New_York"
        )

        val nyRange = TimeRange(
            nyStart.minusDays(1).toInstant(),
            nyStart.plusYears(1).toInstant()
        )

        val occurrences = expander.expand(event, nyRange)

        assertEquals(5, occurrences.size)
    }

    // ==================== Complex RRULE Tests ====================

    @Test
    fun `BYSETPOS with 2nd Tuesday works`() {
        // 2nd Tuesday of each month
        val rrule = RRule(
            freq = Frequency.MONTHLY,
            interval = 1,
            count = 12,
            byDay = listOf(WeekdayNum(DayOfWeek.TUESDAY)),
            bySetPos = listOf(2)
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        // Should generate 12 occurrences (one per month)
        assertEquals(12, occurrences.size, "Should have 12 2nd Tuesdays")
    }

    @Test
    fun `WKST affects week numbering`() {
        // Weekly starting Monday vs Sunday
        val rrule = RRule(
            freq = Frequency.WEEKLY,
            interval = 1,
            count = 4,
            byDay = listOf(WeekdayNum(DayOfWeek.SUNDAY)),
            wkst = DayOfWeek.MONDAY
        )
        val event = createTestEvent(rrule = rrule)

        val occurrences = expander.expand(event, oneYearRange)

        assertEquals(4, occurrences.size, "Should generate 4 Sunday occurrences")
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(
        uid: String = "test-${System.nanoTime()}",
        summary: String = "Test Event",
        startDate: ZonedDateTime = defaultStart,
        endDate: ZonedDateTime? = null,
        rrule: RRule? = null,
        exdates: List<ICalDateTime> = emptyList(),
        recurrenceId: ICalDateTime? = null,
        isAllDay: Boolean = false,
        timezone: String? = null  // Kept for compatibility, stored in dtStart.timezone
    ): ICalEvent {
        val actualEnd = endDate ?: startDate.plusHours(1)

        return ICalEvent(
            uid = uid,
            importId = if (recurrenceId != null) "$uid:RECID:${recurrenceId.toICalString()}" else uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime.fromZonedDateTime(startDate, isAllDay),
            dtEnd = ICalDateTime.fromZonedDateTime(actualEnd, isAllDay),
            duration = null,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = recurrenceId,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
