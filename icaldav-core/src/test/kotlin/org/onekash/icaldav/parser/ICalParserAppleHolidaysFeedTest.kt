package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult

/**
 * Regression test for KashCal/KashCal#219.
 *
 * Apple's iCloud holiday feeds (and other feeds produced by the icalendar-ruby
 * gem) emit DTSTAMP as `;VALUE=DATE:YYYYMMDD` despite RFC 5545 §3.8.7.2
 * requiring DATE-TIME. ical4j's DateProperty serializer throws
 * UnsupportedTemporalTypeException (HourOfDay) when this lands on a LocalDate,
 * causing parseVEvent to silently drop every VEVENT.
 *
 * Fixture is a snapshot of `https://calendars.icloud.com/holidays/us_en.ics`
 * captured 2026-05-11 and stored under src/test/resources/fixtures/. Re-snapshot
 * if Apple alters the publisher format; the test asserts on whatever VEVENT
 * count is in the file, so the count is self-describing.
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

        // The fixture stamps every VEVENT with DTSTAMP;VALUE=DATE:19760401.
        // After preprocessICalData rewrites it to a DATE-TIME, parsed dtstamp
        // should reflect the publisher's date — not "now".
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
