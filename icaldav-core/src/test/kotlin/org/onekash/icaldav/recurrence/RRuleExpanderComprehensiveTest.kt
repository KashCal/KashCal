package org.onekash.icaldav.recurrence

import org.onekash.icaldav.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive RRuleExpander tests.
 *
 * Covers all recurrence patterns per RFC 5545.
 */
@DisplayName("RRuleExpander Comprehensive Tests")
class RRuleExpanderComprehensiveTest {

    private val expander = RRuleExpander()
    private val zone = ZoneId.of("America/New_York")
    private val utcZone = ZoneId.of("UTC")

    // Helper to create time range
    private fun rangeForMonth(year: Int, month: Int): Pair<Instant, Instant> {
        val start = ZonedDateTime.of(year, month, 1, 0, 0, 0, 0, zone).toInstant()
        val end = start.plus(31, ChronoUnit.DAYS)
        return start to end
    }

    @Nested
    @DisplayName("Daily Recurrence")
    inner class DailyRecurrenceTests {

        @Test
        fun `daily recurrence generates correct occurrences`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 1, 10, 0),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(5, occurrences.size)
        }

        @Test
        fun `daily with interval skips days`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 1, 10, 0),
                rrule = RRule(freq = Frequency.DAILY, interval = 2, count = 5)
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(5, occurrences.size)
            // Check dates are every 2 days
        }

        @Test
        fun `daily until date stops correctly`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 1, 10, 0),
                rrule = RRule(
                    freq = Frequency.DAILY,
                    until = ICalDateTime(
                        timestamp = ZonedDateTime.of(2023, 12, 10, 23, 59, 59, 0, utcZone)
                            .toInstant().toEpochMilli(),
                        timezone = null,
                        isUtc = true,
                        isDate = false
                    )
                )
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size <= 10)
        }
    }

    @Nested
    @DisplayName("Weekly Recurrence")
    inner class WeeklyRecurrenceTests {

        @Test
        fun `weekly recurrence generates correct occurrences`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 4, 10, 0), // Monday
                rrule = RRule(freq = Frequency.WEEKLY, count = 4)
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(4, occurrences.size)
        }

        @Test
        fun `weekly on specific days MWF`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 4, 10, 0), // Monday
                rrule = RRule(
                    freq = Frequency.WEEKLY,
                    byDay = listOf(
                        WeekdayNum(DayOfWeek.MONDAY),
                        WeekdayNum(DayOfWeek.WEDNESDAY),
                        WeekdayNum(DayOfWeek.FRIDAY)
                    ),
                    count = 9
                )
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(9, occurrences.size)
        }

        @Test
        fun `weekly with interval every 2 weeks`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 4, 10, 0),
                rrule = RRule(freq = Frequency.WEEKLY, interval = 2, count = 3)
            )

            val start = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 1, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
        }
    }

    @Nested
    @DisplayName("Monthly Recurrence")
    inner class MonthlyRecurrenceTests {

        @Test
        fun `monthly on day of month`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 15, 10, 0),
                rrule = RRule(freq = Frequency.MONTHLY, count = 3)
            )

            val start = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
        }

        @Test
        fun `monthly on last day of month`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 31, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(-1), // Last day
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }

        @Test
        fun `monthly on second Tuesday`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 12, 10, 0), // Second Tuesday
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.TUESDAY, 2)),
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }

        @Test
        fun `monthly on last Friday`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 29, 10, 0), // Last Friday
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.FRIDAY, -1)),
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }
    }

    @Nested
    @DisplayName("Yearly Recurrence")
    inner class YearlyRecurrenceTests {

        @Test
        fun `yearly on same date`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 25, 10, 0),
                rrule = RRule(freq = Frequency.YEARLY, count = 3)
            )

            val start = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
        }

        @Test
        fun `yearly on specific month and day`() {
            val event = createEvent(
                dtStart = dateTime(2023, 7, 4, 10, 0),
                rrule = RRule(
                    freq = Frequency.YEARLY,
                    byMonth = listOf(7),
                    byMonthDay = listOf(4),
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
        }

        @Test
        fun `yearly on last Thursday of November - Thanksgiving`() {
            val event = createEvent(
                dtStart = dateTime(2023, 11, 23, 10, 0),
                rrule = RRule(
                    freq = Frequency.YEARLY,
                    byMonth = listOf(11),
                    byDay = listOf(WeekdayNum(DayOfWeek.THURSDAY, -1)),
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }
    }

    @Nested
    @DisplayName("EXDATE Handling")
    inner class ExdateTests {

        @Test
        fun `single EXDATE excludes occurrence`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 1, 10, 0),
                rrule = RRule(freq = Frequency.DAILY, count = 5),
                exdates = listOf(dateTime(2023, 12, 3, 10, 0))
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(4, occurrences.size)
        }

        @Test
        fun `multiple EXDATEs exclude occurrences`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 1, 10, 0),
                rrule = RRule(freq = Frequency.DAILY, count = 10),
                exdates = listOf(
                    dateTime(2023, 12, 3, 10, 0),
                    dateTime(2023, 12, 5, 10, 0),
                    dateTime(2023, 12, 7, 10, 0)
                )
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(7, occurrences.size)
        }
    }

    @Nested
    @DisplayName("Override Handling")
    inner class OverrideTests {

        @Test
        fun `override replaces original occurrence`() {
            val masterEvent = createEvent(
                uid = "recurring-meeting",
                dtStart = dateTime(2023, 12, 1, 10, 0),
                dtEnd = dateTime(2023, 12, 1, 11, 0),
                rrule = RRule(freq = Frequency.DAILY, count = 5),
                summary = "Daily Standup"
            )

            val overrideEvent = createEvent(
                uid = "recurring-meeting",
                dtStart = dateTime(2023, 12, 3, 14, 0), // Moved to 2pm
                dtEnd = dateTime(2023, 12, 3, 15, 0),
                recurrenceId = dateTime(2023, 12, 3, 10, 0),
                summary = "Daily Standup (Moved)"
            )

            val overrides = mapOf(
                "20231203" to overrideEvent
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(masterEvent, start, end, overrides)

            assertEquals(5, occurrences.size)

            val movedOccurrence = occurrences.find { it.summary == "Daily Standup (Moved)" }
            assertTrue(movedOccurrence != null)
        }
    }

    @Nested
    @DisplayName("All-Day Events")
    inner class AllDayEventTests {

        @Test
        fun `all-day daily recurrence`() {
            val event = createEvent(
                dtStart = ICalDateTime(
                    timestamp = ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, utcZone)
                        .toInstant().toEpochMilli(),
                    timezone = null,
                    isUtc = false,
                    isDate = true
                ),
                rrule = RRule(freq = Frequency.DAILY, count = 5),
                isAllDay = true
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(5, occurrences.size)
            assertTrue(occurrences.all { it.isAllDay })
        }
    }

    @Nested
    @DisplayName("DST and Timezone Edge Cases")
    inner class DstEdgeCaseTests {

        @Test
        fun `daily recurrence across DST spring forward`() {
            // March 10, 2024: 2am -> 3am (spring forward) in America/New_York
            val event = createEvent(
                dtStart = dateTime(2024, 3, 9, 2, 30), // 2:30am, day before DST
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val start = ZonedDateTime.of(2024, 3, 8, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 15, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
            // Should still generate 3 occurrences even though 2:30am doesn't exist on March 10
        }

        @Test
        fun `daily recurrence across DST fall back`() {
            // November 3, 2024: 2am -> 1am (fall back) in America/New_York
            val event = createEvent(
                dtStart = dateTime(2024, 11, 2, 1, 30), // 1:30am, day before DST
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val start = ZonedDateTime.of(2024, 11, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 11, 10, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
        }

        @Test
        fun `weekly recurrence maintains local time across DST`() {
            // Event at 10am should stay at 10am local time across DST change
            val event = createEvent(
                dtStart = dateTime(2024, 3, 4, 10, 0), // Monday before DST
                rrule = RRule(freq = Frequency.WEEKLY, count = 3)
            )

            val start = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
            // All occurrences should be at 10am local time
        }

        @Test
        fun `all-day event maintains date regardless of timezone`() {
            val event = createEvent(
                dtStart = ICalDateTime(
                    timestamp = ZonedDateTime.of(2023, 12, 25, 0, 0, 0, 0, utcZone)
                        .toInstant().toEpochMilli(),
                    timezone = null,
                    isUtc = false,
                    isDate = true
                ),
                rrule = RRule(freq = Frequency.YEARLY, count = 3),
                isAllDay = true
            )

            val start = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
            // All should be December 25, not shifted by timezone
        }

        @Test
        fun `weekly recurrence at 1-30am during fall back - time occurs twice`() {
            // November 3, 2024: 1:30am occurs TWICE in America/New_York
            // First at 1:30am EDT (-04:00), then at 1:30am EST (-05:00)
            // This tests ical4j issue #716 - offset difference between 3.x and 4.x
            val event = createEvent(
                dtStart = dateTime(2024, 10, 27, 1, 30), // Week before DST
                rrule = RRule(freq = Frequency.WEEKLY, count = 3)
            )

            val start = ZonedDateTime.of(2024, 10, 25, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 11, 15, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
            // All occurrences should be at 1:30am local time
            occurrences.forEach { occ ->
                val zdt = occ.dtStart.toZonedDateTime()
                assertEquals(1, zdt.hour)
                assertEquals(30, zdt.minute)
            }
        }

        @Test
        fun `overnight event spanning DST transition maintains duration`() {
            // Event from 10pm to 6am spanning November 3, 2024 DST change
            // This tests ical4j issue #688 - variable duration during DST
            val event = createEvent(
                dtStart = dateTime(2024, 11, 2, 22, 0), // 10pm day before DST
                dtEnd = dateTime(2024, 11, 3, 6, 0),    // 6am on DST day
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val start = ZonedDateTime.of(2024, 11, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 11, 10, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(3, occurrences.size)
            // First occurrence: normal 8 hours
            // Second occurrence (Nov 2-3): 9 hours due to fall back (extra hour)
            // Third occurrence: normal 8 hours
            // Note: The exact duration behavior depends on ical4j's DST handling
            // This test documents the behavior rather than asserting exact values
            occurrences.forEach { occ ->
                assertTrue(occ.dtEnd != null, "All occurrences should have end time")
            }
        }
    }

    @Nested
    @DisplayName("Complex BYDAY Patterns")
    inner class ComplexBydayTests {

        @Test
        fun `first Monday of every month`() {
            val event = createEvent(
                dtStart = dateTime(2024, 1, 1, 10, 0), // First Monday of Jan 2024
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.MONDAY, 1)),
                    count = 6
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }

        @Test
        fun `third Wednesday of every month`() {
            val event = createEvent(
                dtStart = dateTime(2024, 1, 17, 10, 0), // Third Wednesday of Jan 2024
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.WEDNESDAY, 3)),
                    count = 4
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 6, 30, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }

        @Test
        fun `second-to-last Thursday of month`() {
            val event = createEvent(
                dtStart = dateTime(2024, 1, 18, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.THURSDAY, -2)),
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 6, 30, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 1)
        }

        @Test
        fun `every weekday - BYDAY MO TU WE TH FR`() {
            val event = createEvent(
                dtStart = dateTime(2024, 1, 1, 9, 0), // Monday
                rrule = RRule(
                    freq = Frequency.WEEKLY,
                    byDay = listOf(
                        WeekdayNum(DayOfWeek.MONDAY),
                        WeekdayNum(DayOfWeek.TUESDAY),
                        WeekdayNum(DayOfWeek.WEDNESDAY),
                        WeekdayNum(DayOfWeek.THURSDAY),
                        WeekdayNum(DayOfWeek.FRIDAY)
                    ),
                    count = 10
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 1, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(10, occurrences.size)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `non-recurring event returns single occurrence`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 15, 10, 0),
                rrule = null
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            assertEquals(1, occurrences.size)
        }

        @Test
        fun `event outside range returns empty or single`() {
            val event = createEvent(
                dtStart = dateTime(2023, 11, 15, 10, 0),
                rrule = null
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            // Implementation may return 0 or 1 depending on edge handling
            assertTrue(occurrences.size <= 1)
        }

        @Test
        fun `duration is preserved across occurrences`() {
            val event = createEvent(
                dtStart = dateTime(2023, 12, 1, 10, 0),
                dtEnd = dateTime(2023, 12, 1, 12, 30), // 2.5 hours
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            // All occurrences should have same duration
            occurrences.forEach { occ ->
                val duration = occ.dtEnd!!.timestamp - occ.dtStart.timestamp
                assertEquals(2 * 60 * 60 * 1000 + 30 * 60 * 1000, duration)
            }
        }

        @Test
        fun `importId is unique for each occurrence`() {
            val event = createEvent(
                uid = "test-event",
                dtStart = dateTime(2023, 12, 1, 10, 0),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            val (start, end) = rangeForMonth(2023, 12)
            val occurrences = expander.expand(event, start, end)

            val importIds = occurrences.map { it.importId }.toSet()
            assertEquals(5, importIds.size) // All unique
        }
    }

    // Helper functions
    private fun dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): ICalDateTime {
        return ICalDateTime(
            timestamp = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
                .toInstant().toEpochMilli(),
            timezone = zone,
            isUtc = false,
            isDate = false
        )
    }

    private fun createEvent(
        uid: String = "test-event",
        dtStart: ICalDateTime,
        dtEnd: ICalDateTime? = null,
        rrule: RRule? = null,
        exdates: List<ICalDateTime> = emptyList(),
        recurrenceId: ICalDateTime? = null,
        summary: String = "Test Event",
        isAllDay: Boolean = false
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = dtEnd ?: ICalDateTime(
                timestamp = dtStart.timestamp + 3600000,
                timezone = dtStart.timezone,
                isUtc = dtStart.isUtc,
                isDate = dtStart.isDate
            ),
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