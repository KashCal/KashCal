package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.parser.ICalParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.assertEquals

/**
 * UNTIL bounds a recurrence inclusively (RFC 5545 section 3.3.10). When
 * DTSTART has a TZID, UNTIL is a UTC instant, so the set of occurrences must
 * be the same whatever timezone the device runs in. A floating DTSTART has a
 * floating UNTIL, read on the same wall clock as the occurrences.
 */
@DisplayName("RRULE UNTIL across device timezones")
class RRuleExpanderUtcUntilTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val ny = ZoneId.of("America/New_York")

    /** Parse and expand with the JVM default timezone forced to [deviceZone], then restore. */
    private fun expandOnDevice(deviceZone: String, vevent: String): List<Long> {
        val saved = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
            val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\n" +
                "BEGIN:VEVENT\nUID:until-test\nSUMMARY:Until\n$vevent\nEND:VEVENT\nEND:VCALENDAR\n"
            val master = parser.parseAllEvents(ics).getOrThrow().single()
            val from = master.dtStart.toInstant().minusSeconds(DAY_SECONDS)
            val to = from.plusSeconds(1200L * DAY_SECONDS)
            expander.expand(master, TimeRange(from, to)).map { it.dtStart.timestamp }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    private fun nyMs(y: Int, mo: Int, d: Int, h: Int) =
        ZonedDateTime.of(y, mo, d, h, 0, 0, 0, ny).toInstant().toEpochMilli()

    @Nested
    @DisplayName("timed series with a UTC UNTIL")
    inner class TimedUtcUntil {

        // RFC 5545 section 3.8.5.3, "Every day in January, for 3 years".
        // UNTIL=20000131T140000Z is 09:00 EST, the last occurrence itself.
        private val everyDayInJanuary = """
            DTSTART;TZID=America/New_York:19980101T090000
            DTEND;TZID=America/New_York:19980101T100000
            RRULE:FREQ=DAILY;UNTIL=20000131T140000Z;BYMONTH=1
        """.trimIndent()

        private fun assertEveryDayInJanuary(deviceZone: String) {
            val result = expandOnDevice(deviceZone, everyDayInJanuary)
            assertEquals(93, result.size, "3 Januaries of 31 days on a $deviceZone device")
            assertEquals(nyMs(1998, 1, 1, 9), result.first())
            assertEquals(nyMs(2000, 1, 31, 9), result.last(), "Jan 31 2000 is the inclusive UNTIL")
        }

        @Test
        fun `RFC every day in January keeps Jan 31 2000 on a Los Angeles device`() =
            assertEveryDayInJanuary("America/Los_Angeles")

        @Test
        fun `RFC every day in January keeps Jan 31 2000 on a UTC device`() =
            assertEveryDayInJanuary("UTC")

        @Test
        fun `RFC every day in January keeps Jan 31 2000 on a Tokyo device`() =
            assertEveryDayInJanuary("Asia/Tokyo")

        // Tuesdays 10:00 New York from 2024-03-05; the US clock change on
        // 2024-03-10 moves the later occurrences from 15:00Z to 14:00Z.
        private fun tuesdays(until: String) = """
            DTSTART;TZID=America/New_York:20240305T100000
            DTEND;TZID=America/New_York:20240305T110000
            RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=$until
        """.trimIndent()

        private val throughMar12 = listOf(nyMs(2024, 3, 5, 10), nyMs(2024, 3, 12, 10))
        private val throughMar19 = throughMar12 + nyMs(2024, 3, 19, 10)

        @Test
        fun `UNTIL one second before an occurrence ends the series before it on every device`() {
            for (zone in listOf("America/Los_Angeles", "UTC", "Europe/Berlin")) {
                assertEquals(throughMar12, expandOnDevice(zone, tuesdays("20240319T135959Z")), zone)
            }
        }

        @Test
        fun `UNTIL equal to an occurrence keeps it on every device`() {
            for (zone in listOf("America/Los_Angeles", "UTC", "Europe/Berlin")) {
                assertEquals(throughMar19, expandOnDevice(zone, tuesdays("20240319T140000Z")), zone)
            }
        }
    }

    @Nested
    @DisplayName("floating series with a floating UNTIL")
    inner class FloatingUntil {

        private val floatingTuesdays = """
            DTSTART:20240305T100000
            DTEND:20240305T110000
            RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319T100000
        """.trimIndent()

        @Test
        fun `a floating UNTIL is read on the device wall clock in any zone`() {
            for (zone in listOf("America/Los_Angeles", "Asia/Tokyo")) {
                val wallClocks = expandOnDevice(zone, floatingTuesdays).map {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.of(zone))
                }
                assertEquals(
                    listOf(5, 12, 19).map { LocalDateTime.of(2024, 3, it, 10, 0) },
                    wallClocks,
                    zone,
                )
            }
        }

        @Test
        fun `a floating UNTIL on a TZID series is read on the event wall clock`() {
            // Parsed on a Tokyo device, 10:00 is still 10:00 New York, so the 19th is kept.
            val result = expandOnDevice(
                "Asia/Tokyo",
                """
                    DTSTART;TZID=America/New_York:20240305T100000
                    DTEND;TZID=America/New_York:20240305T110000
                    RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319T100000
                """.trimIndent(),
            )
            assertEquals(listOf(5, 12, 19).map { nyMs(2024, 3, it, 10) }, result)
        }
    }

    /**
     * A DATE UNTIL on a timed series reads as midnight at the start of that
     * date, which ends the series before that day's occurrence. Pinned so a
     * change to it is deliberate.
     */
    @Nested
    @DisplayName("timed series with a DATE UNTIL")
    inner class TimedDateUntil {

        @Test
        fun `a DATE UNTIL on a Tokyo series ends before that day on any device`() {
            val tokyo = ZoneId.of("Asia/Tokyo")
            for (zone in listOf("UTC", "America/Los_Angeles")) {
                val result = expandOnDevice(
                    zone,
                    """
                        DTSTART;TZID=Asia/Tokyo:20240305T080000
                        DTEND;TZID=Asia/Tokyo:20240305T090000
                        RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319
                    """.trimIndent(),
                )
                assertEquals(
                    listOf(5, 12).map { ZonedDateTime.of(2024, 3, it, 8, 0, 0, 0, tokyo).toInstant().toEpochMilli() },
                    result,
                    zone,
                )
            }
        }
    }

    /**
     * Pins the current UNTIL handling on all-day series so a change to it is
     * deliberate. These tests don't claim a DATE-TIME UNTIL on an all-day
     * series ends on the right day.
     */
    @Nested
    @DisplayName("all-day series")
    inner class AllDay {

        private fun allDayTuesdays(until: String) = """
            DTSTART;VALUE=DATE:20240305
            DTEND;VALUE=DATE:20240306
            RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=$until
        """.trimIndent()

        private fun utcMidnight(d: Int) =
            LocalDateTime.of(2024, 3, d, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        @Test
        fun `a DATE UNTIL keeps its last day on every device`() {
            for (zone in listOf("UTC", "Europe/Berlin", "America/Los_Angeles")) {
                assertEquals(
                    listOf(5, 12, 19).map(::utcMidnight),
                    expandOnDevice(zone, allDayTuesdays("20240319")),
                    zone,
                )
            }
        }

        @Test
        fun `a UTC DATE-TIME UNTIL keeps reading on the device clock`() {
            // 2024-03-18T23:00Z is midnight on the 19th in Berlin, 23:00 on the 18th in UTC.
            assertEquals(
                listOf(5, 12).map(::utcMidnight),
                expandOnDevice("UTC", allDayTuesdays("20240318T230000Z")),
            )
            assertEquals(
                listOf(5, 12, 19).map(::utcMidnight),
                expandOnDevice("Europe/Berlin", allDayTuesdays("20240318T230000Z")),
            )
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}
