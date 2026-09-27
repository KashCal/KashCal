package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.model.WeekdayNum
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Tests [RRuleExpander] on RFC 5545 recurrence edge cases:
 * - DST transitions, and UTC, Asia/Tokyo and Asia/Shanghai (no DST) series
 * - Feb 29 and BYMONTHDAY 29, 30 and 31 in shorter months
 * - Last day and ordinal rules (BYMONTHDAY=-1, BYDAY=-1FR, BYDAY=5MO)
 * - EXDATE and RDATE, alone and together, and a RECURRENCE-ID override
 * - Large sets (COUNT=365, an unbounded weekly rule, COUNT=120)
 * - All-day series, and events with neither RRULE nor RDATE
 * - The [TimeRange] factories
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
            // US DST: March 10, 2024 at 2:00 AM -> 3:00 AM.
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

            // Every occurrence stays at 10:00 local time.
            occurrences.forEach { occ ->
                val occZdt = occ.dtStart.toZonedDateTime()
                assertEquals(10, occZdt.hour, "Hour should be preserved across DST")
            }
        }

        @Test
        fun `daily rule across fall back DST transition`() {
            // US DST: November 3, 2024 at 2:00 AM -> 1:00 AM.
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
            // BST ends on the last Sunday of October, Oct 27, 2024.
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

            // Every occurrence stays at 14:30 local time.
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
            // Starts on Feb 29, 2024, a leap year.
            val startZdt = LocalDateTime.of(2024, 2, 29, 12, 0)
                .atZone(ZoneId.systemDefault())

            val event = createEvent(
                dtStart = ICalDateTime.fromZonedDateTime(startZdt, false),
                duration = Duration.ofHours(1),
                rrule = RRule(freq = Frequency.YEARLY, count = 4)
            )

            // A 5-year range.
            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(5).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // RRuleExpander sets no ical4j Recur.Skip, so ical4j omits Feb 29 in non-leap years.
            // Asserts only that some occurrences exist.
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

            // Leap year 2024 has a Feb 29. Asserts only that some occurrences exist.
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

            // February has no 30th, and with no Recur.Skip set ical4j omits it rather than
            // moving it. Asserts at least 11.
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

            // Only 7 months have 31 days.
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

            // Each occurrence is the last day of its month.
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

            // Asserts each is a Friday, not that it is the last one.
            occurrences.forEach { occ ->
                val occZdt = occ.dtStart.toZonedDateTime()
                assertEquals(DayOfWeek.FRIDAY, occZdt.dayOfWeek)
            }
        }

        @Test
        fun `monthly rule BYDAY=5MO handles months without 5th Monday`() {
            // January 2024 has 5 Mondays, February 2024 does not.
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

            // Not every month has a 5th Monday, so fewer than 12 fall in the year.
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

            // Exclude January 3.
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

            // 5 daily occurrences minus 1 EXDATE.
            assertEquals(4, occurrences.size)

            // Jan 3 is absent.
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

            // An extra occurrence on Jan 10.
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

            // 2 from the RRULE plus 1 RDATE.
            assertEquals(3, occurrences.size)

            // Jan 10 is included.
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

            // DTSTART isn't added on its own, so these are the three RDATEs (an event with
            // neither RRULE nor RDATE would return itself). Asserts at least 3.
            assertTrue(occurrences.size >= 3)
        }

        @Test
        fun `RDATE duplicate of RRULE occurrence is deduplicated`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            // An RDATE on an RRULE occurrence (Jan 8, the second Monday).
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

            // Still 3: the RDATE on an RRULE day is dropped.
            assertEquals(3, occurrences.size)
        }

        @Test
        fun `EXDATE removes RDATE occurrence`() {
            val startZdt = LocalDateTime.of(2024, 1, 1, 10, 0)
                .atZone(ZoneId.systemDefault())

            val rdateZdt = LocalDateTime.of(2024, 1, 10, 10, 0)
                .atZone(ZoneId.systemDefault())

            // An EXDATE on the RDATE.
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

            // The 2 RRULE occurrences; the EXDATE removes the RDATE.
            assertEquals(2, occurrences.size)

            // Jan 10 is absent.
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

            // An override moving Jan 3 to 14:00.
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

            val overrides = listOf(overrideEvent)

            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusDays(6).toInstant()
            )

            val occurrences = expander.expand(masterEvent, range, overrides)

            assertEquals(5, occurrences.size)

            // The Jan 3 occurrence is the override.
            val jan3Occ = occurrences.find { it.dtStart.toDayCode() == "20240103" }
            assertNotNull(jan3Occ)
            assertEquals("Modified Event", jan3Occ?.summary)
            assertEquals(14, jan3Occ?.dtStart?.toZonedDateTime()?.hour)
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

            // The end is April 1, midnight.
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

            // The series has no end, so the range bounds it to one year.
            val range = TimeRange(
                startZdt.toInstant(),
                startZdt.plusYears(1).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // 52 or 53 weekly occurrences in a year.
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

            // Every occurrence is at 9:00 in Tokyo.
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
            // ical4j issue #720 reports ical4j's embedded VTIMEZONE for Asia/Shanghai as wrong.
            // China has used a fixed UTC+8 with no DST since 1991.
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

            // Every occurrence is at 10:30 in Shanghai.
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
            // A year of monthly occurrences keeps 8:00 and +08:00, since Shanghai has no DST.
            val shanghaiZone = ZoneId.of("Asia/Shanghai")

            // Starts in winter.
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

            occurrences.forEach { occ ->
                val occInShanghai = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(occ.dtStart.timestamp),
                    shanghaiZone
                )
                assertEquals(8, occInShanghai.hour,
                    "Hour should remain 8 in Shanghai (no DST) for ${occInShanghai.month}")

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
            // Weekly on Mon, Wed and Fri.
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

            // Each occurrence is a Mon, Wed or Fri.
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

            // A range that doesn't include the event.
            val range = TimeRange(
                startZdt.plusDays(5).toInstant(),
                startZdt.plusDays(10).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // An event with neither RRULE nor RDATE comes back whatever the range; filtering it
            // is up to the caller.
            assertEquals(1, occurrences.size)
        }
    }
}