package org.onekash.icaldav.parser

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Zone forms Microsoft 365 and Exchange feeds use are read at the instant their VTIMEZONE gives,
 * not in the device zone (#441): `TZID=UTC`, which ical4j turns into floating time, and Windows
 * display names such as `(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi`, with the feed's
 * VTIMEZONE, without it (iCloud drops it), or with a VTIMEZONE whose TZID escapes the commas.
 *
 * The device zone is pinned to Asia/Tokyo, which none of the inputs use, so a fallback to it
 * shows as a wrong instant.
 */
class ICalParserTzidRewriteTest {

    private val parser = ICalParser()
    private lateinit var savedZone: TimeZone

    @BeforeEach
    fun pinDeviceZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @AfterEach
    fun restore() {
        TimeZone.setDefault(savedZone)
        ICalDateTime.customTimezoneResolver = null
    }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun parse(vararg lines: String): List<ICalEvent> {
        val body = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:Microsoft Exchange Server 2010") +
            lines.toList() + "END:VCALENDAR").joinToString("\r\n") + "\r\n"
        val result = parser.parseAllEvents(body)
        return result.getOrNull() ?: error("parse failed: $result")
    }

    private fun vevent(uid: String, vararg props: String) =
        arrayOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261118T120000Z", *props, "SUMMARY:$uid", "END:VEVENT")

    private fun fixedVtimezone(tzid: String, offset: String) = arrayOf(
        "BEGIN:VTIMEZONE", "TZID:$tzid",
        "BEGIN:STANDARD", "DTSTART:16010101T000000", "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:16010101T000000", "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:DAYLIGHT",
        "END:VTIMEZONE",
    )

    /** Exchange's VTIMEZONE for US Central time. */
    private fun centralVtimezone(tzid: String) = arrayOf(
        "BEGIN:VTIMEZONE", "TZID:$tzid",
        "BEGIN:STANDARD", "DTSTART:16010101T020000", "TZOFFSETFROM:-0500", "TZOFFSETTO:-0600",
        "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:16010101T020000", "TZOFFSETFROM:-0600", "TZOFFSETTO:-0500",
        "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3", "END:DAYLIGHT",
        "END:VTIMEZONE",
    )

    private val vtimezoneUtc get() = fixedVtimezone("UTC", "+0000")

    private val beijing = "(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi"
    private val beijingEscaped = "(UTC+08:00) Beijing\\, Chongqing\\, Hong Kong\\, Urumqi"

    // ---- TZID=UTC ----

    @Test
    fun `TZID=UTC with Exchange's VTIMEZONE is read as UTC`() {
        val event = parse(*vtimezoneUtc, *vevent("a", "DTSTART;TZID=UTC:20261210T100000", "DTEND;TZID=UTC:20261210T110000")).single()
        assertEquals(at("2026-12-10T10:00:00Z"), event.dtStart.timestamp)
        assertTrue(event.dtStart.isUtc)
        assertEquals(at("2026-12-10T11:00:00Z"), event.dtEnd!!.timestamp)
    }

    @Test
    fun `TZID=UTC without a VTIMEZONE, quoted or in another case, is read as UTC`() {
        val events = parse(
            *vevent("plain", "DTSTART;TZID=UTC:20261210T100000"),
            *vevent("quoted", "DTSTART;TZID=\"UTC\":20261210T100000"),
            *vevent("lower", "DTSTART;TZID=utc:20261210T100000"),
            *vevent("already-z", "DTSTART;TZID=UTC:20261210T100000Z"),
        )
        for (event in events) {
            assertEquals(at("2026-12-10T10:00:00Z"), event.dtStart.timestamp, event.uid)
        }
    }

    @Test
    fun `TZID=UTC on RECURRENCE-ID, EXDATE and RDATE lists is read as UTC`() {
        val events = parse(
            *vtimezoneUtc,
            *vevent(
                "series",
                "DTSTART;TZID=UTC:20261124T180000",
                "RRULE:FREQ=WEEKLY;COUNT=6",
                "EXDATE;TZID=UTC:20261201T180000,20261208T180000",
                "RDATE;TZID=UTC:20261226T180000",
            ),
            "BEGIN:VEVENT", "UID:series", "DTSTAMP:20261118T120000Z", "RECURRENCE-ID;TZID=UTC:20261124T180000",
            "DTSTART;TZID=UTC:20261124T193000", "SUMMARY:moved", "END:VEVENT",
        )
        val series = events.single { it.recurrenceId == null }
        assertEquals(listOf(at("2026-12-01T18:00:00Z"), at("2026-12-08T18:00:00Z")), series.exdates.map { it.timestamp })
        assertEquals(listOf(at("2026-12-26T18:00:00Z")), series.rdates.map { it.timestamp })
        val moved = events.single { it.recurrenceId != null }
        // The changed occurrence names the series' first instance, so it links to it.
        assertEquals(series.dtStart.timestamp, moved.recurrenceId!!.timestamp)
        assertEquals(at("2026-11-24T19:30:00Z"), moved.dtStart.timestamp)
    }

    @Test
    fun `TZID=UTC on a DATE value or an unknown property is left as sent`() {
        val event = parse(
            *vevent(
                "a",
                "DTSTART;VALUE=DATE:20261210",
                "X-KASHCAL-PROBE;TZID=UTC:20261210T100000",
            ),
        ).single()
        assertTrue(event.dtStart.isDate)
        assertTrue(
            "X-KASHCAL-PROBE;TZID=UTC:20261210T100000" in event.unknownPropertyLines,
            event.unknownPropertyLines.toString()
        )
    }

    // ---- Windows display names ----

    @Test
    fun `a display name with the feed's escaped VTIMEZONE is read at its offset`() {
        val event = parse(
            *fixedVtimezone(beijingEscaped, "+0800"),
            *vevent("bj", "DTSTART;TZID=\"$beijing\":20261210T100000", "DTEND;TZID=\"$beijing\":20261210T113000"),
        ).single()
        assertEquals(at("2026-12-10T02:00:00Z"), event.dtStart.timestamp)
        assertEquals(at("2026-12-10T03:30:00Z"), event.dtEnd!!.timestamp)
        assertEquals(8 * 3600, event.dtStart.timezone!!.rules.getOffset(Instant.now()).totalSeconds)
    }

    @Test
    fun `a display name with no VTIMEZONE is read at its offset`() {
        val event = parse(*vevent("bj", "DTSTART;TZID=\"$beijing\":20261210T100000")).single()
        assertEquals(at("2026-12-10T02:00:00Z"), event.dtStart.timestamp)
    }

    @Test
    fun `a display name resolves through its Windows ID with the app's resolver`() {
        ICalDateTime.customTimezoneResolver = { tzid ->
            mapOf("China Standard Time" to "Asia/Shanghai", "Central Standard Time" to "America/Chicago")[tzid]
                ?.let { ZoneId.of(it) }
        }
        val events = parse(
            *vevent("bj", "DTSTART;TZID=\"$beijing\":20261210T100000"),
            *vevent("us", "DTSTART;TZID=\"(UTC-06:00) Central Time (US & Canada)\":20260715T100000"),
        )
        assertEquals("Asia/Shanghai", events.single { it.uid == "bj" }.dtStart.timezone!!.id)
        val us = events.single { it.uid == "us" }.dtStart
        assertEquals("America/Chicago", us.timezone!!.id)
        assertEquals(at("2026-07-15T15:00:00Z"), us.timestamp)
    }

    @Test
    fun `a display name the table lacks is read from the feed's yearly VTIMEZONE in winter and summer`() {
        val name = "(UTC-06:00) Zentralzeit (USA und Kanada)"
        val events = parse(
            *centralVtimezone(name),
            *vevent("winter", "DTSTART;TZID=\"$name\":20260115T100000"),
            *vevent("summer", "DTSTART;TZID=\"$name\":20260715T100000"),
        )
        assertEquals(at("2026-01-15T16:00:00Z"), events.single { it.uid == "winter" }.dtStart.timestamp)
        assertEquals(at("2026-07-15T15:00:00Z"), events.single { it.uid == "summer" }.dtStart.timestamp)
    }

    @Test
    fun `a city whose zone has another offset is skipped`() {
        // Urumqi is UTC+06:00, so the next city that matches the +08:00 prefix is used.
        val event = parse(*vevent("x", "DTSTART;TZID=\"(UTC+08:00) Urumqi, Hong Kong\":20261210T100000")).single()
        assertEquals(at("2026-12-10T02:00:00Z"), event.dtStart.timestamp)
        assertEquals("Asia/Hong_Kong", event.dtStart.timezone!!.id)
    }

    @Test
    fun `a VTIMEZONE whose offsets disagree with the name wins over the name`() {
        val event = parse(
            *fixedVtimezone(beijingEscaped, "+0500"),
            *vevent("bj", "DTSTART;TZID=\"$beijing\":20261210T100000"),
        ).single()
        // RFC 5545 makes the VTIMEZONE the zone's definition: +05:00, whatever the name says.
        assertEquals(at("2026-12-10T05:00:00Z"), event.dtStart.timestamp)
    }

    @Test
    fun `a name with no prefix, no city and no VTIMEZONE keeps the device zone`() {
        val event = parse(*vevent("x", "DTSTART;TZID=\"Somewhere Standard Time\":20261210T100000")).single()
        assertEquals(at("2026-12-10T01:00:00Z"), event.dtStart.timestamp)
    }

    @Test
    fun `a zone that already resolves is never rewritten`() {
        ICalDateTime.customTimezoneResolver = { if (it == "Central Standard Time") ZoneId.of("America/Chicago") else null }
        val events = parse(
            *centralVtimezone("Central Standard Time"),
            *vevent("win", "DTSTART;TZID=Central Standard Time:20260715T100000"),
            *vevent("iana", "DTSTART;TZID=Europe/Berlin:20260715T100000"),
        )
        assertEquals("America/Chicago", events.single { it.uid == "win" }.dtStart.timezone!!.id)
        assertEquals("Europe/Berlin", events.single { it.uid == "iana" }.dtStart.timezone!!.id)
    }

    @Test
    fun `a malformed VTIMEZONE leaves the body as sent and never throws`() {
        val broken = listOf(
            arrayOf("TZOFFSETFROM:abc", "TZOFFSETTO:+0800"),
            arrayOf("TZOFFSETFROM:+0900", "TZOFFSETTO:+0800", "RRULE:FREQ=YEARLY;BYDAY=5SU;BYMONTH=13"),
            arrayOf("TZOFFSETTO:+0800"),
        )
        for (observance in broken) {
            val events = parse(
                "BEGIN:VTIMEZONE", "TZID:$beijingEscaped",
                "BEGIN:STANDARD", *observance, "END:STANDARD",
                "END:VTIMEZONE",
                *vevent("bj", "DTSTART;TZID=\"$beijing\":20261210T100000"),
            )
            // The VTIMEZONE can't be read, so the name keeps its zone: the device zone.
            assertEquals(at("2026-12-10T01:00:00Z"), events.single().dtStart.timestamp, observance.toList().toString())
        }
    }

    @Test
    fun `every parse entry point gets the rewrite`() {
        val body = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:x",
            *vevent("a", "DTSTART;TZID=UTC:20261210T100000"),
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val expected = at("2026-12-10T10:00:00Z")
        assertEquals(expected, parser.parseAllEvents(body).getOrNull()!!.single().dtStart.timestamp)
        assertEquals(expected, parser.parse(body).getOrNull()!!.events.single().dtStart.timestamp)
        assertEquals(expected, parser.parseWithMethod(body).getOrNull()!!.events.single().dtStart.timestamp)
    }

    @Test
    fun `TZID=UTC on a to-do's DUE is read as UTC`() {
        val body = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:x",
            "BEGIN:VTODO", "UID:t", "DTSTAMP:20261118T120000Z", "DUE;TZID=UTC:20261210T100000", "SUMMARY:t", "END:VTODO",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        assertEquals(at("2026-12-10T10:00:00Z"), parser.parseAllTodos(body).getOrNull()!!.single().due!!.timestamp)
    }

    @Test
    fun `an old series in a display-name zone does not keep the zone from matching`() {
        // Exchange writes today's rule from 1601 on; a series from before the 2007 US change
        // must not stop the match for the zone's other events.
        val name = "(UTC-06:00) Zentralzeit (USA und Kanada)"
        val events = parse(
            *centralVtimezone(name),
            *vevent("old", "DTSTART;TZID=\"$name\":20050113T100000", "RRULE:FREQ=WEEKLY"),
            *vevent("summer", "DTSTART;TZID=\"$name\":20270715T100000"),
        )
        assertEquals(at("2027-07-15T15:00:00Z"), events.single { it.uid == "summer" }.dtStart.timestamp)
    }

    @Test
    fun `a VTIMEZONE the rewrite can't read keeps a known display name in the device zone`() {
        val events = parse(
            "BEGIN:VTIMEZONE", "TZID:$beijingEscaped",
            "BEGIN:STANDARD", "DTSTART:16010101T000000", "RDATE:20270101T000000",
            "TZOFFSETFROM:+0800", "TZOFFSETTO:+0800", "END:STANDARD",
            "END:VTIMEZONE",
            *vevent("bj", "DTSTART;TZID=\"$beijing\":20261210T100000"),
        )
        assertEquals(at("2026-12-10T01:00:00Z"), events.single().dtStart.timestamp)
    }

    @Test
    fun `a rule match prefers a zone the Windows table names`() {
        // Sydney's rules; without the preference the first match in sorted order is
        // Antarctica/Macquarie.
        val name = "(UTC+10:00) Ostküste"
        val event = parse(
            "BEGIN:VTIMEZONE", "TZID:$name",
            "BEGIN:STANDARD", "DTSTART:16010101T030000", "TZOFFSETFROM:+1100", "TZOFFSETTO:+1000",
            "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=4", "END:STANDARD",
            "BEGIN:DAYLIGHT", "DTSTART:16010101T020000", "TZOFFSETFROM:+1000", "TZOFFSETTO:+1100",
            "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=10", "END:DAYLIGHT",
            "END:VTIMEZONE",
            *vevent("syd", "DTSTART;TZID=\"$name\":20270115T100000"),
        ).single()
        assertEquals(at("2027-01-14T23:00:00Z"), event.dtStart.timestamp)
        assertTrue(event.dtStart.timezone!!.id.startsWith("Australia/"), event.dtStart.timezone!!.id)
    }
}
