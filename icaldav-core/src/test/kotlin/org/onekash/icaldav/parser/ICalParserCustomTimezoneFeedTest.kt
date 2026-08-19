package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Regression test for KashCal/KashCal#346.
 *
 * Some publishers define their own VTIMEZONE with a non-IANA TZID (here
 * `TZsfv`) and record the intended real zone only in an X-LIC-LOCATION hint.
 * This is permitted by RFC 5545 §3.2.19 (a TZID with no leading solidus names
 * a timezone defined by an embedded VTIMEZONE). ical4j 4.x resolves TZID via
 * java.time.ZoneId.of at date-access time, so the unknown name throws and the
 * per-VEVENT parse dropped every event — the feed showed a non-zero event
 * count in preview but zero events after refresh.
 *
 * Fixture is a real published sports-schedule feed captured 2026-08-19, with
 * its publisher identifiers replaced by generic placeholders; the count
 * assertion is self-describing so a re-snapshot stays valid.
 */
class ICalParserCustomTimezoneFeedTest {

    @Test
    fun custom_tzid_feed_parses_all_events() {
        val content = readFixture()
        val expected = Regex("BEGIN:VEVENT").findAll(content).count()
        assertTrue(expected > 0, "fixture has no VEVENTs")

        val result = ICalParser().parseAllEvents(content)
        require(result is ParseResult.Success) { "Parse failed: $result" }

        assertEquals(expected, result.value.size,
            "every VEVENT must survive an unresolvable custom TZID")
    }

    @Test
    fun custom_tzid_resolves_via_x_lic_location_to_correct_offset() {
        val content = readFixture()
        val result = ICalParser().parseAllEvents(content)
        require(result is ParseResult.Success) { "Parse failed: $result" }

        // First event: DTSTART;TZID=TZsfv:20260823T133000. TZsfv carries
        // X-LIC-LOCATION:Europe/Amsterdam, which is CEST (UTC+2) on that date,
        // so 13:30 local == 11:30 UTC. The rewrite must produce that instant,
        // not the device-local (floating) interpretation.
        val expectedMs = ZonedDateTime
            .of(2026, 8, 23, 13, 30, 0, 0, ZoneId.of("Europe/Amsterdam"))
            .toInstant().toEpochMilli()

        val first = result.value.minByOrNull { it.dtStart.timestamp }
        assertNotNull(first, "expected at least one parsed event")
        assertEquals(expectedMs, first!!.dtStart.timestamp,
            "X-LIC-LOCATION zone must drive the offset, not the device zone")
    }

    @Test
    fun unresolvable_tzid_without_hint_is_kept_not_dropped() {
        // No X-LIC-LOCATION, so the custom zone cannot be rewritten. Relaxed
        // validation must still keep the event (falling back to floating time)
        // rather than dropping it.
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:test\r\n")
            append("BEGIN:VTIMEZONE\r\nTZID:MadeUpZone\r\n")
            append("BEGIN:STANDARD\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0100\r\n")
            append("TZNAME:XX\r\nDTSTART:19700101T000000\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n")
            append("BEGIN:VEVENT\r\nUID:x@test\r\nDTSTAMP:20260819T000000Z\r\n")
            append("DTSTART;TZID=MadeUpZone:20260823T133000\r\n")
            append("DTEND;TZID=MadeUpZone:20260823T153000\r\nSUMMARY:kept\r\nEND:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }

        val result = ICalParser().parseAllEvents(ics)
        require(result is ParseResult.Success) { "Parse failed: $result" }
        assertEquals(1, result.value.size,
            "an unresolvable TZID with no X-LIC-LOCATION must not drop the event")
    }

    private fun readFixture(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/custom_tzid_xlic_location.ics")
            ?: error("missing fixture: fixtures/custom_tzid_xlic_location.ics")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
