package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.model.WeekdayNum
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Tests that [RRuleExpander] stays within time and memory bounds on adversarial rules
 * (CWE-400, uncontrolled resource consumption). Each test has a JUnit timeout, most assert an
 * elapsed-time bound, and some bound the count; printed counts aren't asserted.
 *
 * Scenarios:
 * - rules without COUNT or UNTIL: SECONDLY over an hour, MINUTELY over a day, HOURLY over 100
 *   years (about 876,000 occurrences), DAILY over 1000 years
 * - COUNT=50000 under a 200 MB memory delta, and 5000 EXDATEs or 5000 overrides
 * - BYSETPOS, BYWEEKNO and BYYEARDAY combinations, and a 5000-count SECONDLY rule
 * - zero-width, past, after-UNTIL and year-9999 ranges
 */
@DisplayName("Recurrence Resource Exhaustion Tests")
class RecurrenceResourceExhaustionTest {

    private val expander = RRuleExpander()
    private val zone = ZoneId.of("UTC")
    private val defaultStart = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, zone)

    @Nested
    @DisplayName("CPU Exhaustion Prevention")
    inner class CpuExhaustionTests {

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `SECONDLY without limit completes in reasonable time over 1 hour`() {
            // FREQ=SECONDLY over 1 hour: 3601 occurrences, since both range ends are kept.
            val rrule = RRule(
                freq = Frequency.SECONDLY,
                interval = 1
                // No COUNT or UNTIL.
            )
            val event = createTestEvent(rrule = rrule)

            val oneHourRange = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusHours(1).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, oneHourRange)
            val duration = System.currentTimeMillis() - startTime

            // ical4j generates every one; the bound is on time and count.
            assertTrue(duration < 10000,
                "Should complete in under 10 seconds, took ${duration}ms")
            assertTrue(occurrences.size <= 3601,
                "Should have at most 3601 occurrences (1 hour of seconds)")

            // Printed for information, not asserted.
            println("SECONDLY over 1 hour generated ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `MINUTELY without limit completes over 1 day`() {
            // FREQ=MINUTELY over 1 day: 1441 occurrences with both range ends kept.
            val rrule = RRule(
                freq = Frequency.MINUTELY,
                interval = 1
            )
            val event = createTestEvent(rrule = rrule)

            val oneDayRange = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusDays(1).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, oneDayRange)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "Should complete in under 10 seconds, took ${duration}ms")
            assertTrue(occurrences.size <= 1441,
                "Should have at most 1441 occurrences (1 day of minutes)")

            println("MINUTELY over 1 day generated ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `HOURLY without limit completes over 100 years`() {
            // FREQ=HOURLY over 100 years: about 876,000 occurrences.
            val rrule = RRule(
                freq = Frequency.HOURLY,
                interval = 1
            )
            val event = createTestEvent(rrule = rrule)

            val hundredYearRange = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(100).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, hundredYearRange)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "Should complete in under 10 seconds, took ${duration}ms")

            println("HOURLY over 100 years generated ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `DAILY over 1000 years completes`() {
            // FREQ=DAILY over 1000 years: about 365,000 occurrences.
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1
            )
            val event = createTestEvent(rrule = rrule)

            val thousandYearRange = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(1000).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, thousandYearRange)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "Should complete in under 10 seconds, took ${duration}ms")

            println("DAILY over 1000 years generated ${occurrences.size} occurrences in ${duration}ms")
        }
    }

    @Nested
    @DisplayName("Memory Exhaustion Prevention")
    inner class MemoryExhaustionTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        fun `high count limit does not cause OOM`() {
            // COUNT=50000 measures memory without taking long.
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1,
                count = 50000
            )
            val event = createTestEvent(rrule = rrule)

            // A range long enough for all 50000 occurrences.
            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(150).toInstant()  // about 54,800 days
            )

            // Heap in use before expanding.
            val runtime = Runtime.getRuntime()
            runtime.gc()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val occurrences = expander.expand(event, range)

            runtime.gc()
            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = memAfter - memBefore

            // Under 200 MB for 50000 occurrences.
            assertTrue(memUsed < 200_000_000L,
                "Memory usage should be reasonable: ${memUsed / 1_000_000}MB used")

            println("COUNT=50K generated ${occurrences.size} occurrences, memory delta: ${memUsed / 1_000_000}MB")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `expansion with many EXDATE entries completes`() {
            // Many EXDATEs could slow exclusion checking.
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1,
                count = 10000
            )

            // 5000 EXDATEs, every other day.
            val exdates = (1..5000).map { day ->
                ICalDateTime.fromZonedDateTime(defaultStart.plusDays(day.toLong() * 2))
            }

            val event = createTestEvent(
                rrule = rrule,
                exdates = exdates
            )

            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(30).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, range)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "Should complete with many EXDATEs in reasonable time")

            println("Expansion with 5000 EXDATEs: ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `expansion with many overrides completes`() {
            // Many overrides could slow override matching.
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1,
                count = 10000
            )
            val masterEvent = createTestEvent(rrule = rrule)

            // 5000 overrides, each moved 2 hours later.
            val overrides = (1..5000).map { day ->
                createTestEvent(
                    uid = masterEvent.uid,
                    summary = "Override $day",
                    startDate = defaultStart.plusDays(day.toLong()).plusHours(2),
                    recurrenceId = ICalDateTime.fromZonedDateTime(defaultStart.plusDays(day.toLong()))
                )
            }

            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(30).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(masterEvent, range, overrides)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "Should complete with many overrides in reasonable time")

            println("Expansion with 5000 overrides: ${occurrences.size} occurrences in ${duration}ms")
        }
    }

    @Nested
    @DisplayName("Pathological RRULE Combinations")
    inner class PathologicalRRuleCombinations {

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `YEARLY with BYMONTH BYMONTHDAY BYDAY completes`() {
            // MONTHLY on weekdays, taking the first five and the last two of each month.
            val rrule = RRule(
                freq = Frequency.MONTHLY,
                interval = 1,
                byDay = listOf(
                    WeekdayNum(DayOfWeek.MONDAY),
                    WeekdayNum(DayOfWeek.TUESDAY),
                    WeekdayNum(DayOfWeek.WEDNESDAY),
                    WeekdayNum(DayOfWeek.THURSDAY),
                    WeekdayNum(DayOfWeek.FRIDAY)
                ),
                bySetPos = listOf(1, 2, 3, 4, 5, -1, -2)
            )
            val event = createTestEvent(rrule = rrule)

            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(100).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, range)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "Complex RRULE should complete in reasonable time")

            println("Complex MONTHLY rule over 100 years: ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `YEARLY BYWEEKNO BYDAY combination completes`() {
            // Week number plus day.
            val rrule = RRule(
                freq = Frequency.YEARLY,
                interval = 1,
                byWeekNo = (1..53).toList(),
                byDay = listOf(
                    WeekdayNum(DayOfWeek.MONDAY),
                    WeekdayNum(DayOfWeek.FRIDAY)
                )
            )
            val event = createTestEvent(rrule = rrule)

            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(50).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, range)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "BYWEEKNO+BYDAY should complete in reasonable time")

            println("YEARLY BYWEEKNO BYDAY over 50 years: ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `YEARLY BYYEARDAY with all days completes`() {
            // Every day of the year.
            val rrule = RRule(
                freq = Frequency.YEARLY,
                interval = 1,
                byYearDay = (1..366).toList()
            )
            val event = createTestEvent(rrule = rrule)

            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusYears(10).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, range)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(duration < 10000,
                "All BYYEARDAY should complete in reasonable time")

            println("YEARLY with all 366 BYYEARDAY over 10 years: ${occurrences.size} occurrences in ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `interval of 1 second with moderate range completes`() {
            // SECONDLY with INTERVAL=1 over 2 hours, capped by COUNT.
            val rrule = RRule(
                freq = Frequency.SECONDLY,
                interval = 1,
                count = 5000
            )
            val event = createTestEvent(rrule = rrule)

            val range = TimeRange(
                defaultStart.toInstant(),
                defaultStart.plusHours(2).toInstant()
            )

            val startTime = System.currentTimeMillis()
            val occurrences = expander.expand(event, range)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(occurrences.size <= 5000,
                "Should respect COUNT limit")
            assertTrue(duration < 10000,
                "Should complete in reasonable time")

            println("SECONDLY interval=1 with COUNT=5000: ${occurrences.size} occurrences in ${duration}ms")
        }
    }

    @Nested
    @DisplayName("Edge Case Time Ranges")
    inner class EdgeCaseTimeRanges {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `range start equals range end returns empty or single`() {
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1,
                count = 100
            )
            val event = createTestEvent(rrule = rrule)

            val zeroRange = TimeRange(
                defaultStart.toInstant(),
                defaultStart.toInstant()
            )

            val occurrences = expander.expand(event, zeroRange)

            // A zero-width range has at most the occurrence at that instant.
            assertTrue(occurrences.size <= 1,
                "Zero-width range should have at most 1 occurrence")
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `range entirely before event start returns empty`() {
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1,
                count = 100
            )
            val event = createTestEvent(rrule = rrule)  // Starts 2024-01-01

            val pastRange = TimeRange(
                defaultStart.minusYears(10).toInstant(),
                defaultStart.minusYears(5).toInstant()
            )

            val occurrences = expander.expand(event, pastRange)

            assertTrue(occurrences.isEmpty(),
                "Range before event should return empty")
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `range entirely after UNTIL returns empty`() {
            val rrule = RRule(
                freq = Frequency.DAILY,
                interval = 1,
                until = ICalDateTime.fromZonedDateTime(defaultStart.plusDays(30))
            )
            val event = createTestEvent(rrule = rrule)

            val futureRange = TimeRange(
                defaultStart.plusYears(1).toInstant(),
                defaultStart.plusYears(2).toInstant()
            )

            val occurrences = expander.expand(event, futureRange)

            assertTrue(occurrences.isEmpty(),
                "Range after UNTIL should return empty")
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `very far future range completes`() {
            val rrule = RRule(
                freq = Frequency.YEARLY,
                interval = 1
            )
            val event = createTestEvent(rrule = rrule)

            // Years 9990 to 9999.
            val farFutureRange = TimeRange(
                ZonedDateTime.of(9990, 1, 1, 0, 0, 0, 0, zone).toInstant(),
                ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, zone).toInstant()
            )

            val occurrences = expander.expand(event, farFutureRange)

            // May hold 10 occurrences or none; asserts at most 10.
            assertTrue(occurrences.size <= 10,
                "Far future range should work")
        }
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
        isAllDay: Boolean = false
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
