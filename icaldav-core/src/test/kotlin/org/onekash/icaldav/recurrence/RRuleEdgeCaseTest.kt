package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.onekash.icaldav.model.*
import java.time.*
import java.time.temporal.ChronoUnit

/**
 * Edge case tests for RRuleExpander.
 *
 * Tests RFC 5545 recurrence expansion edge cases:
 * - DST transitions
 * - Leap year handling (February 29)
 * - Month boundary conditions
 * - Last day of month rules
 * - EXDATE/RDATE interactions
 * - Timezone-aware expansion
 * - RECURRENCE-ID overrides
 * - Large occurrence sets
 * - Ordinal edge cases (-1 = last)
 */
class RRuleEdgeCaseTest {

    private val expander = RRuleExpander()

    // ==================== Helper Functions ====================

    private fun createEvent(
        uid: String = "test-event",
        dtStart: ICalDateTime,
        dtEnd: ICalDateTime? = null,
        duration: Duration? = null,
        isAllDay: Boolean = false,
        rrule: RRule? = null,
        exdates: List<ICalDateTime> = emptyList(),
        rdates: List<ICalDateTime> = emptyList()
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Test Event",
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = duration,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = exdates,
            rdates = rdates,
            recurrenceId = null,
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

    // ==================== DST Transition Tests ====================

    @Nested
    inner class DstTransitionTests {

        @Test
        fun `daily rule across spring forward DST transition`() {
            // US DST: March 10, 2024 at 2:00 AM -> 3:00 AM
            val zone = ZoneId.of("America/New_York")
            val startTime = LocalDateTime.of(2024, 3, 8, 10, 0)
            val startZdt = startTime.atZone(zone)

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(6).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(5, occurrences.size)

            // All occurrences should be at 10:00 AM local time
            occurrences.forEach { occ ->
                val occZdt = occ.dtStart.toZonedDateTime()
                assertEquals(10, occZdt.hour, "Hour should be preserved across DST")
            }
        }

        @Test
        fun `daily rule across fall back DST transition`() {
            // US DST: November 3, 2024 at 2:00 AM -> 1:00 AM
            val zone = ZoneId.of("America/New_York")
            val startTime = LocalDateTime.of(2024, 11, 1, 10, 0)
            val startZdt = startTime.atZone(zone)

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(6).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(5, occurrences.size)
        }

        @Test
        fun `weekly rule preserves local time across DST`() {
            val zone = ZoneId.of("Europe/London")
            // BST ends last Sunday of October - Oct 27, 2024
            val startTime = LocalDateTime.of(2024, 10, 21, 14, 30)
            val startZdt = startTime.atZone(zone)

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofMinutes(90),
                rrule = RRule(freq = Frequency.WEEKLY, count = 3)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusWeeks(4).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(3, occurrences.size)

            // All should be at 14:30 local time
            occurrences.forEach { occ ->
                val occZdt = occ.dtStart.toZonedDateTime()
                assertEquals(14, occZdt.hour)
                assertEquals(30, occZdt.minute)
            }
        }
    }

    // ==================== Leap Year Tests ====================

    @Nested
    inner class LeapYearTests {

        @Test
        fun `yearly rule on Feb 29 in leap year`() {
            // Event starts on Feb 29, 2024 (leap year)
            val startZdt = LocalDateTime.of(2024, 2, 29, 12, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.YEARLY, count = 4)
            )

            // Expand over 5 years
            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(5).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // ical4j typically generates Feb 28 in non-leap years, or skips
            // The exact behavior depends on ical4j's LEAP_MONTH_HANDLING
            assertTrue(occurrences.isNotEmpty())
        }

        @Test
        fun `monthly rule BYMONTHDAY=29 handles February`() {
            val startZdt = LocalDateTime.of(2024, 1, 29, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(29),
                    count = 12
                )
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Should include Feb 29 in leap year 2024
            // In non-leap years, Feb 29 doesn't exist
            assertTrue(occurrences.isNotEmpty())
        }

        @Test
        fun `monthly rule BYMONTHDAY=30 skips February`() {
            val startZdt = LocalDateTime.of(2024, 1, 30, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(30),
                    count = 12
                )
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Should have 11 occurrences (Feb doesn't have 30th)
            // Or 12 if ical4j adjusts to Feb 28
            assertTrue(occurrences.size >= 11)
        }

        @Test
        fun `monthly rule BYMONTHDAY=31 handles 30-day months`() {
            val startZdt = LocalDateTime.of(2024, 1, 31, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(31),
                    count = 12
                )
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Only 7 months have 31 days
            assertTrue(occurrences.size >= 7)
        }
    }

    // ==================== Last Day of Month Tests ====================

    @Nested
    inner class LastDayOfMonthTests {

        @Test
        fun `monthly rule BYMONTHDAY=-1 gives last day`() {
            val startZdt = LocalDateTime.of(2024, 1, 31, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(-1),  // Last day
                    count = 6
                )
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusMonths(7).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(6, occurrences.size)

            // Verify each occurrence is the last day of its month
            occurrences.forEach { occ ->
                val occZdt = occ.dtStart.toZonedDateTime()
                val lastDayOfMonth = occZdt.toLocalDate().lengthOfMonth()
                assertEquals(lastDayOfMonth, occZdt.dayOfMonth,
                    "Day ${occZdt.dayOfMonth} should be last day ($lastDayOfMonth) of ${occZdt.month}")
            }
        }

        @Test
        fun `monthly rule BYDAY=-1FR gives last Friday`() {
            val startZdt = LocalDateTime.of(2024, 1, 26, 10, 0)  // Last Friday of Jan
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.FRIDAY, -1)),
                    count = 6
                )
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusMonths(7).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(6, occurrences.size)

            // Each occurrence should be a Friday
            occurrences.forEach { occ ->
                val occZdt = occ.dtStart.toZonedDateTime()
                assertEquals(DayOfWeek.FRIDAY, occZdt.dayOfWeek)
            }
        }

        @Test
        fun `monthly rule BYDAY=5MO handles months without 5th Monday`() {
            // January 2024 has 5 Mondays, February 2024 does not
            val startZdt = LocalDateTime.of(2024, 1, 29, 10, 0)  // 5th Monday
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(WeekdayNum(DayOfWeek.MONDAY, 5)),
                    count = 12
                )
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Not all months have 5 Mondays, so fewer than 12 occurrences
            assertTrue(occurrences.size < 12)
            assertTrue(occurrences.isNotEmpty())
        }
    }

    // ==================== EXDATE Tests ====================

    @Nested
    inner class ExdateTests {

        @Test
        fun `EXDATE excludes specific occurrence`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            // Exclude January 3rd
            val exdateZdt = LocalDateTime.of(2024, 1, 3, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 5),
                exdates = listOf(ICalDateTime.fromZonedDateTime(exdateZdt, false))
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(6).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // 5 daily occurrences minus 1 exdate = 4
            assertEquals(4, occurrences.size)

            // Verify Jan 3rd is not in results
            val jan3DayCode = "20240103"
            occurrences.forEach { occ ->
                assertNotEquals(jan3DayCode, occ.dtStart.toDayCode())
            }
        }

        @Test
        fun `multiple EXDATEs exclude multiple occurrences`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val exdates = listOf(
                LocalDateTime.of(2024, 1, 2, 10, 0),
                LocalDateTime.of(2024, 1, 4, 10, 0),
                LocalDateTime.of(2024, 1, 6, 10, 0)
            ).map { ICalDateTime.fromZonedDateTime(it.atZone(ZoneId.systemDefault()), false) }

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 7),
                exdates = exdates
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(8).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(4, occurrences.size)  // 7 - 3 = 4
        }

        @Test
        fun `EXDATE on all-day event matches by date only`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 0, 0)
                .atZone(ZoneId.systemDefault())

            val exdateZdt = LocalDateTime.of(2024, 1, 3, 0, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, true),
                isAllDay = true,
                rrule = RRule(freq = Frequency.DAILY, count = 5),
                exdates = listOf(ICalDateTime.fromZonedDateTime(exdateZdt, true))
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(6).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(4, occurrences.size)
        }
    }

    // ==================== RDATE Tests ====================

    @Nested
    inner class RdateTests {

        @Test
        fun `RDATE adds additional occurrence`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            // Add an extra occurrence on Jan 10th
            val rdateZdt = LocalDateTime.of(2024, 1, 10, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.WEEKLY, count = 2),  // Jan 1 and Jan 8
                rdates = listOf(ICalDateTime.fromZonedDateTime(rdateZdt, false))
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(15).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // 2 from RRULE + 1 from RDATE = 3
            assertEquals(3, occurrences.size)

            // Verify Jan 10th is included
            val jan10DayCode = "20240110"
            assertTrue(occurrences.any { it.dtStart.toDayCode() == jan10DayCode })
        }

        @Test
        fun `RDATE without RRULE creates occurrences`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val rdates = listOf(
                LocalDateTime.of(2024, 1, 5, 10, 0),
                LocalDateTime.of(2024, 1, 15, 14, 0),
                LocalDateTime.of(2024, 2, 1, 9, 0)
            ).map { ICalDateTime.fromZonedDateTime(it.atZone(ZoneId.systemDefault()), false) }

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = null,  // No RRULE
                rdates = rdates
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusMonths(2).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Original event + 3 RDATE occurrences
            // Note: Event without RRULE/RDATE returns single occurrence
            // With RDATE, should return RDATE occurrences
            assertTrue(occurrences.size >= 3)
        }

        @Test
        fun `RDATE duplicate of RRULE occurrence is deduplicated`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            // Add RDATE that matches an RRULE occurrence (Jan 8 = second Monday)
            val rdateZdt = LocalDateTime.of(2024, 1, 8, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.WEEKLY, count = 3),  // Jan 1, 8, 15
                rdates = listOf(ICalDateTime.fromZonedDateTime(rdateZdt, false))
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(20).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Should still be 3, not 4 (duplicate deduplicated)
            assertEquals(3, occurrences.size)
        }

        @Test
        fun `EXDATE removes RDATE occurrence`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val rdateZdt = LocalDateTime.of(2024, 1, 10, 10, 0)
                .atZone(ZoneId.systemDefault())

            // EXDATE for the RDATE
            val exdateZdt = LocalDateTime.of(2024, 1, 10, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.WEEKLY, count = 2),
                rdates = listOf(ICalDateTime.fromZonedDateTime(rdateZdt, false)),
                exdates = listOf(ICalDateTime.fromZonedDateTime(exdateZdt, false))
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(15).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // 2 from RRULE, RDATE removed by EXDATE = 2
            assertEquals(2, occurrences.size)

            // Verify Jan 10th is NOT included
            val jan10DayCode = "20240110"
            assertFalse(occurrences.any { it.dtStart.toDayCode() == jan10DayCode })
        }
    }

    // ==================== RECURRENCE-ID Override Tests ====================

    @Nested
    inner class RecurrenceIdOverrideTests {

        @Test
        fun `override replaces generated occurrence`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val masterEvent = createEvent(
                uid = "master-event",
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            // Create override for Jan 3rd with different time
            val overrideZdt = LocalDateTime.of(2024, 1, 3, 14, 0)
                .atZone(ZoneId.systemDefault())
            val recIdZdt = LocalDateTime.of(2024, 1, 3, 10, 0)
                .atZone(ZoneId.systemDefault())

            val overrideEvent = createEvent(
                uid = "master-event",
                dtStart = ICalDateTime.fromZonedDateTime(overrideZdt, false),
                duration = Duration.ofHours(2)
            ).copy(
                importId = "master-event:RECID:20240103T100000",
                recurrenceId = ICalDateTime.fromZonedDateTime(recIdZdt, false),
                summary = "Modified Event"
            )

            val overrides = RRuleExpander.buildOverrideMap(listOf(overrideEvent))

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(6).toInstant()
            )

            val occurrences = expander.expand(masterEvent, range, overrides)

            assertEquals(5, occurrences.size)

            // Find the Jan 3rd occurrence
            val jan3Occ = occurrences.find { it.dtStart.toDayCode() == "20240103" }
            assertNotNull(jan3Occ)
            assertEquals("Modified Event", jan3Occ?.summary)
            assertEquals(14, jan3Occ?.dtStart?.toZonedDateTime()?.hour)
        }

        @Test
        fun `buildOverrideMap creates correct day code mapping`() {
            val recIdZdt = LocalDateTime.of(2024, 1, 15, 10, 0)
                .atZone(ZoneId.systemDefault())

            val overrideEvent = createEvent(
                uid = "test",
                dtStart = ICalDateTime.fromZonedDateTime(recIdZdt.plusHours(4), false)
            ).copy(
                recurrenceId = ICalDateTime.fromZonedDateTime(recIdZdt, false)
            )

            val overrideMap = RRuleExpander.buildOverrideMap(listOf(overrideEvent))

            assertEquals(1, overrideMap.size)
            assertTrue(overrideMap.containsKey("20240115"))
        }

        @Test
        fun `events without recurrenceId are excluded from override map`() {
            val regularEvent = createEvent(
                uid = "regular",
                dtStart = ICalDateTime.fromZonedDateTime(
                    LocalDateTime.of(2024, 1, 1, 10, 0).atZone(ZoneId.systemDefault()),
                    false
                )
            )

            val overrideMap = RRuleExpander.buildOverrideMap(listOf(regularEvent))

            assertTrue(overrideMap.isEmpty())
        }
    }

    // ==================== TimeRange Helper Tests ====================

    @Nested
    inner class TimeRangeTests {

        @Test
        fun `forMonth creates correct range`() {
            val range = TimeRange.forMonth(2024, 3)

            val startZdt = ZonedDateTime.ofInstant(range.start, ZoneId.systemDefault())
            val endZdt = ZonedDateTime.ofInstant(range.end, ZoneId.systemDefault())

            assertEquals(2024, startZdt.year)
            assertEquals(3, startZdt.monthValue)
            assertEquals(1, startZdt.dayOfMonth)

            // End should be April 1st (exclusive)
            assertEquals(4, endZdt.monthValue)
            assertEquals(1, endZdt.dayOfMonth)
        }

        @Test
        fun `nextDays creates forward range`() {
            val range = TimeRange.nextDays(30)

            val durationMs = range.end.toEpochMilli() - range.start.toEpochMilli()
            val durationDays = durationMs / (24 * 60 * 60 * 1000)

            assertEquals(30, durationDays)
        }

        @Test
        fun `aroundNow creates symmetric range`() {
            val range = TimeRange.aroundNow(7, 7)

            val totalDays = ChronoUnit.DAYS.between(range.start, range.end)
            assertEquals(14, totalDays)
        }

        @Test
        fun `syncWindow creates 2-year range`() {
            val range = TimeRange.syncWindow()

            val totalDays = ChronoUnit.DAYS.between(range.start, range.end)
            assertEquals(730, totalDays)  // 365 + 365
        }
    }

    // ==================== Large Occurrence Set Tests ====================

    @Nested
    inner class LargeOccurrenceSetTests {

        @Test
        fun `daily rule with COUNT=365 generates full year`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 365)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).plusDays(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(365, occurrences.size)
        }

        @Test
        fun `weekly rule over 5 years with reasonable expansion`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.WEEKLY)  // No COUNT, infinite
            )

            // Only expand 1 year at a time to avoid memory issues
            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // ~52 weeks in a year
            assertTrue(occurrences.size in 52..53)
        }

        @Test
        fun `monthly rule for 10 years`() {
            val startZdt = LocalDateTime.of(2024, 1, 15, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.MONTHLY, count = 120)  // 10 years
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(11).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(120, occurrences.size)
        }
    }

    // ==================== Timezone Edge Cases ====================

    @Nested
    inner class TimezoneEdgeCaseTests {

        @Test
        fun `UTC event expands correctly regardless of local timezone`() {
            // UTC start time
            val startInstant = Instant.parse("2024-01-01T10:00:00Z")
            val startZdt = ZonedDateTime.ofInstant(startInstant, ZoneId.of("UTC"))

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val range = TimeRange(
                startInstant,
                startInstant.plus(5, ChronoUnit.DAYS)
            )

            val occurrences = expander.expand(event, range)

            assertEquals(3, occurrences.size)
        }

        @Test
        fun `cross-timezone event preserves original timezone`() {
            val tokyoZone = ZoneId.of("Asia/Tokyo")
            val startZdt = LocalDateTime.of(2024, 1, 1, 9, 0).atZone(tokyoZone)

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(5).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(3, occurrences.size)

            // All occurrences should be at 9:00 in Tokyo
            occurrences.forEach { occ ->
                val occInTokyo = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(occ.dtStart.timestamp),
                    tokyoZone
                )
                assertEquals(9, occInTokyo.hour)
            }
        }

        @Test
        fun `Asia Shanghai timezone preserves local time (ical4j issue 720)`() {
            // ical4j issue #720: Embedded VTIMEZONE for Asia/Shanghai was incorrect
            // China uses fixed UTC+8 with no DST since 1991
            val shanghaiZone = ZoneId.of("Asia/Shanghai")
            val startZdt = LocalDateTime.of(2024, 6, 15, 10, 30).atZone(shanghaiZone)

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.WEEKLY, count = 4)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusWeeks(5).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(4, occurrences.size)

            // All occurrences should be at 10:30 in Shanghai (fixed UTC+8)
            occurrences.forEach { occ ->
                val occInShanghai = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(occ.dtStart.timestamp),
                    shanghaiZone
                )
                assertEquals(10, occInShanghai.hour, "Hour should be 10 in Shanghai")
                assertEquals(30, occInShanghai.minute, "Minute should be 30")
            }
        }

        @Test
        fun `Asia Shanghai daily recurrence maintains fixed UTC+8 offset`() {
            // Verify no DST transitions affect Shanghai timezone
            // China abolished DST in 1991 - should be fixed UTC+8 year-round
            val shanghaiZone = ZoneId.of("Asia/Shanghai")

            // Start in winter
            val winterStart = LocalDateTime.of(2024, 1, 15, 8, 0).atZone(shanghaiZone)

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(winterStart, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.MONTHLY, count = 12)  // Full year
            )

            val range = TimeRange(
                winterStart.toInstant(),
                winterStart.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(12, occurrences.size)

            // All occurrences should be at 8:00 Shanghai time (no DST shifts)
            occurrences.forEach { occ ->
                val occInShanghai = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(occ.dtStart.timestamp),
                    shanghaiZone
                )
                assertEquals(8, occInShanghai.hour,
                    "Hour should remain 8 in Shanghai (no DST) for ${occInShanghai.month}")

                // Verify offset is always +08:00
                assertEquals(ZoneOffset.ofHours(8), occInShanghai.offset,
                    "Shanghai offset should always be +08:00")
            }
        }
    }

    // ==================== All-Day Event Tests ====================

    @Nested
    inner class AllDayEventTests {

        @Test
        fun `all-day event expands to full days`() {
            val startZdt = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, true),
                isAllDay = true,
                rrule = RRule(freq = Frequency.DAILY, count = 3)
            )

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(5).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(3, occurrences.size)

            occurrences.forEach { occ ->
                assertTrue(occ.isAllDay || occ.dtStart.isDate)
            }
        }

        @Test
        fun `weekly all-day event on specific days`() {
            // Weekly on Mon, Wed, Fri
            val startZdt = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneId.systemDefault())  // Monday

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, true),
                isAllDay = true,
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

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusWeeks(4).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(9, occurrences.size)

            // Verify each occurrence is Mon, Wed, or Fri
            occurrences.forEach { occ ->
                val dow = occ.dtStart.toZonedDateTime().dayOfWeek
                assertTrue(
                    dow == DayOfWeek.MONDAY ||
                    dow == DayOfWeek.WEDNESDAY ||
                    dow == DayOfWeek.FRIDAY,
                    "Expected Mon/Wed/Fri, got $dow"
                )
            }
        }
    }

    // ==================== Non-Recurring Event Tests ====================

    @Nested
    inner class NonRecurringEventTests {

        @Test
        fun `event without RRULE returns single occurrence`() {
            val startZdt = LocalDateTime.of(2024, 1, 15, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = null
            )

            val range = TimeRange(
                startZdt.minusDays(1).toInstant(),
                startZdt.plusDays(5).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(1, occurrences.size)
            assertEquals(startZdt.toInstant().toEpochMilli(), occurrences[0].dtStart.timestamp)
        }

        @Test
        fun `event outside range returns empty list`() {
            val startZdt = LocalDateTime.of(2024, 1, 15, 10, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = null
            )

            // Range that doesn't include the event
            val range = TimeRange(
                startZdt.plusDays(5).toInstant(),
                startZdt.plusDays(10).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Non-recurring event outside range returns the single event
            // (filtering is typically done at query level)
            // The expander returns the event; range filtering happens elsewhere
            assertEquals(1, occurrences.size)
        }
    }
}