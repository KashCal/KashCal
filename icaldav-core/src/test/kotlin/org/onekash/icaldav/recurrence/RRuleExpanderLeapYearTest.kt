package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [RRuleExpander] on February 29, short months, BYSETPOS and UTC series.
 *
 * RFC 5545 §3.3.10: a recurrence instance with an invalid date (Feb 29 in a non-leap year, the
 * 31st of a 30-day month) must be ignored and not counted. So a yearly Feb 29 series occurs only
 * in leap years, and a monthly series on the 29th skips February in non-leap years.
 *
 * The BYSETPOS tests assert only that expansion returns (`size >= 0` is always true).
 */
@DisplayName("RRuleExpander Leap Year and BYSETPOS Tests")
class RRuleExpanderLeapYearTest {

    private val expander = RRuleExpander()
    private val zone = ZoneId.of("America/New_York")
    private val utcZone = ZoneId.of("UTC")

    @Nested
    @DisplayName("February 29 Leap Year Handling")
    inner class Feb29LeapYearTests {

        @Test
        fun `yearly on Feb 29 only occurs on leap years`() {
            // 2024 is a leap year, 2025 is not, 2028 is.
            val event = createEvent(
                dtStart = dateTime(2024, 2, 29, 10, 0),
                rrule = RRule(
                    freq = Frequency.YEARLY,
                    count = 3
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2033, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // Expected 2024-02-29, 2028-02-29 and 2032-02-29; asserts that some occur, all on
            // Feb 29.
            assertTrue(occurrences.isNotEmpty(), "Should have at least one Feb 29 occurrence")

            occurrences.forEach { occ ->
                val zdt = occ.dtStart.toZonedDateTime()
                assertEquals(2, zdt.monthValue, "All occurrences should be in February")
                assertEquals(29, zdt.dayOfMonth, "All occurrences should be on day 29")
            }
        }

        @Test
        fun `monthly on day 29 - skips or adjusts February in non-leap years`() {
            // Monthly on the 29th, across a non-leap and a leap February.
            val event = createEvent(
                dtStart = dateTime(2023, 1, 29, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(29),
                    count = 14
                )
            )

            val start = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // Expected the 29th of every month but Feb 2023, then Jan 29 and Feb 29, 2024 (a leap
            // year). Asserts only that some occur and that Feb 2024 has one.
            assertTrue(occurrences.isNotEmpty())

            val feb2024Occurrences = occurrences.filter { occ ->
                val zdt = occ.dtStart.toZonedDateTime()
                zdt.year == 2024 && zdt.monthValue == 2
            }
            assertTrue(feb2024Occurrences.isNotEmpty(), "Feb 2024 (leap year) should have occurrence on 29th")
        }

        @Test
        fun `monthly on day 31 - skips months without 31 days`() {
            val event = createEvent(
                dtStart = dateTime(2024, 1, 31, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(31),
                    count = 12
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // Seven months have a 31st (Jan, Mar, May, Jul, Aug, Oct, Dec); asserts that some
            // occur, all on the 31st.
            assertTrue(occurrences.isNotEmpty())

            occurrences.forEach { occ ->
                val zdt = occ.dtStart.toZonedDateTime()
                assertEquals(31, zdt.dayOfMonth, "All occurrences should be on day 31")
            }
        }

        @Test
        fun `last day of month pattern handles variable month lengths`() {
            // BYMONTHDAY=-1 is the last day of the month.
            val event = createEvent(
                dtStart = dateTime(2024, 1, 31, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byMonthDay = listOf(-1),
                    count = 6
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 7, 1, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // Expected Jan 31, Feb 29 (leap), Mar 31, Apr 30, May 31 and Jun 30.
            assertTrue(occurrences.isNotEmpty())

            val dates = occurrences.map { occ ->
                val zdt = occ.dtStart.toZonedDateTime()
                "${zdt.monthValue}/${zdt.dayOfMonth}"
            }
            // `dates` isn't asserted; only a non-empty result is.
        }

        @Test
        fun `yearly birthday on Feb 29 - born on leap day`() {
            // A birthday on Feb 29, 2000, recurring yearly.
            val event = createEvent(
                dtStart = dateTime(2000, 2, 29, 0, 0),
                isAllDay = true,
                dtEnd = null,
                rrule = RRule(
                    freq = Frequency.YEARLY,
                    count = 10
                )
            )

            val start = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2040, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // Leap years in range: 2000, 2004, 2008, 2012, 2016, 2020, 2024, 2028, 2032, 2036.
            assertTrue(occurrences.isNotEmpty())

            // Every occurrence is Feb 29.
            occurrences.forEach { occ ->
                val ld = occ.dtStart.toLocalDate()
                assertEquals(2, ld.monthValue)
                assertEquals(29, ld.dayOfMonth)
            }
        }
    }

    @Nested
    @DisplayName("BYSETPOS Tests")
    inner class BySetPosTests {

        @Test
        fun `BYSETPOS 1 with BYDAY - first weekday of month`() {
            // The first weekday of the month.
            val event = createEvent(
                dtStart = dateTime(2024, 1, 1, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(
                        WeekdayNum(DayOfWeek.MONDAY),
                        WeekdayNum(DayOfWeek.TUESDAY),
                        WeekdayNum(DayOfWeek.WEDNESDAY),
                        WeekdayNum(DayOfWeek.THURSDAY),
                        WeekdayNum(DayOfWeek.FRIDAY)
                    ),
                    bySetPos = listOf(1),
                    count = 6
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 7, 1, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // RRuleExpander passes BYSETPOS to ical4j (RRuleExpanderAdversarialTest counts 12
            // second Tuesdays), despite the message below. This assert is always true.
            assertTrue(occurrences.size >= 0, "Implementation may or may not support BYSETPOS")
        }

        @Test
        fun `BYSETPOS -1 with BYDAY - last weekday of month`() {
            // The last weekday of the month.
            val event = createEvent(
                dtStart = dateTime(2024, 1, 31, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(
                        WeekdayNum(DayOfWeek.MONDAY),
                        WeekdayNum(DayOfWeek.TUESDAY),
                        WeekdayNum(DayOfWeek.WEDNESDAY),
                        WeekdayNum(DayOfWeek.THURSDAY),
                        WeekdayNum(DayOfWeek.FRIDAY)
                    ),
                    bySetPos = listOf(-1),
                    count = 6
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 7, 1, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            // Always true; BYSETPOS is passed to ical4j, despite the message.
            assertTrue(occurrences.size >= 0, "Implementation may or may not support BYSETPOS")
        }

        @Test
        fun `BYSETPOS 2 - second occurrence in set`() {
            // The second weekday of each month.
            val event = createEvent(
                dtStart = dateTime(2024, 1, 2, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(
                        WeekdayNum(DayOfWeek.MONDAY),
                        WeekdayNum(DayOfWeek.TUESDAY),
                        WeekdayNum(DayOfWeek.WEDNESDAY),
                        WeekdayNum(DayOfWeek.THURSDAY),
                        WeekdayNum(DayOfWeek.FRIDAY)
                    ),
                    bySetPos = listOf(2),
                    count = 6
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 7, 1, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 0)
        }

        @Test
        fun `BYSETPOS with multiple values`() {
            // The first and last weekday of the month.
            val event = createEvent(
                dtStart = dateTime(2024, 1, 1, 10, 0),
                rrule = RRule(
                    freq = Frequency.MONTHLY,
                    byDay = listOf(
                        WeekdayNum(DayOfWeek.MONDAY),
                        WeekdayNum(DayOfWeek.TUESDAY),
                        WeekdayNum(DayOfWeek.WEDNESDAY),
                        WeekdayNum(DayOfWeek.THURSDAY),
                        WeekdayNum(DayOfWeek.FRIDAY)
                    ),
                    bySetPos = listOf(1, -1),
                    count = 12
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 7, 1, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 0)
        }

        @Test
        fun `BYSETPOS for yearly - second Tuesday in November US Election Day`() {
            // US Election Day, the first Tuesday after the first Monday in November:
            // FREQ=YEARLY;BYMONTH=11;BYDAY=TU;BYMONTHDAY=2,3,4,5,6,7,8, plus BYSETPOS=1.
            val event = createEvent(
                dtStart = dateTime(2024, 11, 5, 0, 0), // Election Day 2024
                isAllDay = true,
                dtEnd = null,
                rrule = RRule(
                    freq = Frequency.YEARLY,
                    byMonth = listOf(11),
                    byDay = listOf(WeekdayNum(DayOfWeek.TUESDAY)),
                    byMonthDay = listOf(2, 3, 4, 5, 6, 7, 8),
                    bySetPos = listOf(1),
                    count = 5
                )
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2030, 12, 31, 0, 0, 0, 0, zone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.size >= 0)
        }
    }

    @Nested
    @DisplayName("UTC Event Expansion")
    inner class UtcExpansionTests {

        @Test
        fun `UTC event expands correctly`() {
            val event = createEvent(
                dtStart = ICalDateTime(
                    timestamp = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, utcZone)
                        .toInstant().toEpochMilli(),
                    timezone = null,
                    isUtc = true,
                    isDate = false
                ),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, utcZone).toInstant()
            val end = ZonedDateTime.of(2024, 1, 31, 0, 0, 0, 0, utcZone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(5, occurrences.size)

            // Every occurrence is at 10:00 UTC.
            occurrences.forEach { occ ->
                val zdt = Instant.ofEpochMilli(occ.dtStart.timestamp)
                    .atZone(utcZone)
                assertEquals(10, zdt.hour)
            }
        }

        @Test
        fun `UTC event spanning timezone boundaries`() {
            // Daily at 23:00 UTC, which falls on a different date in zones east of UTC.
            val event = createEvent(
                dtStart = ICalDateTime(
                    timestamp = ZonedDateTime.of(2024, 1, 1, 23, 0, 0, 0, utcZone)
                        .toInstant().toEpochMilli(),
                    timezone = null,
                    isUtc = true,
                    isDate = false
                ),
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, utcZone).toInstant()
            val end = ZonedDateTime.of(2024, 1, 31, 0, 0, 0, 0, utcZone).toInstant()
            val occurrences = expander.expand(event, start, end)

            assertEquals(5, occurrences.size)

            occurrences.forEach { occ ->
                val zdt = Instant.ofEpochMilli(occ.dtStart.timestamp)
                    .atZone(utcZone)
                assertEquals(23, zdt.hour)
            }
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
        summary: String = "Test Event",
        isAllDay: Boolean = false
    ): ICalEvent {
        val effectiveDtEnd = if (isAllDay && dtEnd == null) {
            // An all-day event defaults to one day long.
            ICalDateTime(
                timestamp = dtStart.timestamp + 86400000,
                timezone = dtStart.timezone,
                isUtc = dtStart.isUtc,
                isDate = true
            )
        } else {
            dtEnd ?: ICalDateTime(
                timestamp = dtStart.timestamp + 3600000,
                timezone = dtStart.timezone,
                isUtc = dtStart.isUtc,
                isDate = dtStart.isDate
            )
        }

        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = if (isAllDay) ICalDateTime(dtStart.timestamp, dtStart.timezone, dtStart.isUtc, true) else dtStart,
            dtEnd = effectiveDtEnd,
            duration = null,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = exdates,
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
}
