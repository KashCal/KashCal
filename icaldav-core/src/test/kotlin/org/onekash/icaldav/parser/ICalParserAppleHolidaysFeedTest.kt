package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult

/**
 * Checks that the iCloud holiday feed parses every VEVENT (KashCal/KashCal#219).
 *
 * iCloud's holiday feeds, like other feeds from the icalendar-ruby gem, emit DTSTAMP as
 * `;VALUE=DATE:YYYYMMDD`, though RFC 5545 §3.8.7.2 requires DATE-TIME. ical4j's DateProperty
 * serializer throws UnsupportedTemporalTypeException (HourOfDay) on the resulting LocalDate,
 * so without the DATE-TIME rewrite in preprocessICalData every VEVENT fails to parse and is
 * silently dropped.
 *
 * The fixture is a snapshot of `https://calendars.icloud.com/holidays/us_en.ics` captured
 * 2026-05-11, under src/test/resources/fixtures/. Re-snapshot it if the publisher changes the
 * format; the expected count is read from the file.
 */
class ICalParserAppleHolidaysFeedTest {

    @Test
    fun apple_holidays_feed_parses_all_events() {
        val content = readFixture()
        val expected = Regex("BEGIN:VEVENT").findAll(content).count()
        assertTrue(expected > 0, "fixture has no VEVENTs")

        val result = ICalParser().parseAllEvents(content)
        require(result is ParseResult.Success) { "Parse failed: $result" }

        assertEquals(expected, result.value.size,
            "every VEVENT in the Apple holiday feed must parse")
    }

    @Test
    fun apple_holidays_feed_preserves_publisher_dtstamp() {
        val content = readFixture()
        val result = ICalParser().parseAllEvents(content)
        require(result is ParseResult.Success) { "Parse failed: $result" }

        // The fixture stamps every VEVENT with DTSTAMP;VALUE=DATE:19760401. preprocessICalData
        // rewrites it to midnight UTC, so the parsed dtstamp keeps the publisher's date.
        val sample = result.value.firstOrNull { it.dtstamp != null }
        assertNotNull(sample, "expected parseable DTSTAMP after preprocessing")
        val expectedMs = 197164800000L // 1976-04-01T00:00:00Z
        assertEquals(expectedMs, sample!!.dtstamp!!.timestamp,
            "publisher DTSTAMP date must be preserved across preprocessing")
    }

    private fun readFixture(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/apple_us_holidays.ics")
            ?: error("missing fixture: fixtures/apple_us_holidays.ics")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
