package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Tests that a DATE-form EXDATE (YYYYMMDD) on a timed recurring master excludes the intended
 * day. The mapper stores EXDATE as bare epoch milliseconds, so the value type is gone by
 * expansion time; [ICalEventMapper.normalizeToMasterValueType] first promotes a DATE value to
 * the master's time of day in the master's zone. RFC 5545 §3.8.5.1 allows EXDATE as DATE or
 * DATE-TIME, peer clients emit a DATE form against a timed master, and most CalDAV servers
 * preserve it verbatim.
 *
 * A DATE form parses to UTC midnight. Read in a negative-offset (Americas) master zone, that
 * instant falls on the previous day, so without the promotion the wrong day is excluded and the
 * intended occurrence survives. Positive-offset zones keep the same calendar day, so those cases
 * guard against an over-correction.
 *
 * Each test asserts that the intended day is suppressed and the 5-occurrence series yields 4;
 * the zoned cases also assert that a neighbor day survives. The path is the real pull pipeline:
 * parse server ICS -> [ICalEventMapper.toEntity] -> [IcalDavRRuleEngine.expandToTimestamps]
 * over the stored `event.exdate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExdateValueTypeProbeTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun zoned(zoneId: String, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of(zoneId)).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `DATE-form EXDATE on a UTC timed master suppresses the intended occurrence`() {
        // Timed daily master at 10:00 UTC, 5 occurrences (Dec 25-29).
        // EXDATE is DATE-form (20251227), a real-world value-type mismatch.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-vt@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE;VALUE=DATE:20251227
            SUMMARY:Daily standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(parsed, ics, calendarId = 1L, caldavUrl = null, etag = null).event

        val occurrences = IcalDavRRuleEngine.expandToTimestamps(
            rrule = entity.rrule,
            dtstartMs = entity.startTs,
            rangeStartMs = utc(2025, 12, 25, 0, 0),
            rangeEndMs = utc(2025, 12, 30, 0, 0),
            timezone = entity.timezone,
            isAllDay = entity.isAllDay,
            rdateStrings = entity.rdate,
            exdateStrings = entity.exdate,
        )

        val excludedDayOccurrence = utc(2025, 12, 27, 10, 0)
        assertFalse(
            "Dec 27 10:00 occurrence must be suppressed by the DATE-form EXDATE; " +
                "stored exdate=${entity.exdate}, occurrences=$occurrences",
            occurrences.contains(excludedDayOccurrence)
        )
        assertEquals("Series of 5 minus 1 excepted day = 4 occurrences", 4, occurrences.size)
    }

    @Test
    fun `DATE-TIME EXDATE matching the timed master suppresses the intended occurrence`() {
        // EXDATE carries the matching DATE-TIME value type: the baseline the normalization
        // must not break.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-vt-dt@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE:20251227T100000Z
            SUMMARY:Daily standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(parsed, ics, calendarId = 1L, caldavUrl = null, etag = null).event

        val occurrences = IcalDavRRuleEngine.expandToTimestamps(
            rrule = entity.rrule,
            dtstartMs = entity.startTs,
            rangeStartMs = utc(2025, 12, 25, 0, 0),
            rangeEndMs = utc(2025, 12, 30, 0, 0),
            timezone = entity.timezone,
            isAllDay = entity.isAllDay,
            rdateStrings = entity.rdate,
            exdateStrings = entity.exdate,
        )

        assertFalse(
            "Dec 27 10:00 must be suppressed by the matching DATE-TIME EXDATE; occurrences=$occurrences",
            occurrences.contains(utc(2025, 12, 27, 10, 0))
        )
        assertEquals("5 minus 1 excepted = 4", 4, occurrences.size)
    }

    @Test
    fun `DATE-form EXDATE on an all-day master suppresses the intended day`() {
        // All-day master with a DATE-form EXDATE: the value types already match, and the
        // normalization must leave this case alone.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-vt-allday@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE;VALUE=DATE:20251227
            SUMMARY:All day thing
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(parsed, ics, calendarId = 1L, caldavUrl = null, etag = null).event

        val occurrences = IcalDavRRuleEngine.expandToTimestamps(
            rrule = entity.rrule,
            dtstartMs = entity.startTs,
            rangeStartMs = utcMidnight(2025, 12, 25),
            rangeEndMs = utcMidnight(2025, 12, 30),
            timezone = entity.timezone,
            isAllDay = entity.isAllDay,
            rdateStrings = entity.rdate,
            exdateStrings = entity.exdate,
        )

        assertFalse(
            "Dec 27 all-day must be suppressed; occurrences=$occurrences",
            occurrences.contains(utcMidnight(2025, 12, 27))
        )
        assertEquals("5 minus 1 excepted = 4", 4, occurrences.size)
    }

    @Test
    fun `DATE-form EXDATE on a negative-offset timed master suppresses the correct local day`() {
        // Master in America/Los_Angeles (UTC-8). A DATE-form EXDATE of 20251227 means
        // "the Dec 27 occurrence". Left at UTC midnight it reads in LA as Dec 26 16:00
        // local, day code 20251226, which would suppress the wrong day.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-vt-la@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/Los_Angeles:20251225T090000
            DTEND;TZID=America/Los_Angeles:20251225T100000
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE;VALUE=DATE:20251227
            SUMMARY:Daily standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(parsed, ics, calendarId = 1L, caldavUrl = null, etag = null).event

        val occurrences = IcalDavRRuleEngine.expandToTimestamps(
            rrule = entity.rrule,
            dtstartMs = entity.startTs,
            rangeStartMs = zoned("America/Los_Angeles", 2025, 12, 25, 0, 0),
            rangeEndMs = zoned("America/Los_Angeles", 2025, 12, 30, 0, 0),
            timezone = entity.timezone,
            isAllDay = entity.isAllDay,
            rdateStrings = entity.rdate,
            exdateStrings = entity.exdate,
        )

        val dec27 = zoned("America/Los_Angeles", 2025, 12, 27, 9, 0)
        val dec26 = zoned("America/Los_Angeles", 2025, 12, 26, 9, 0)
        assertFalse(
            "Dec 27 09:00 LA must be suppressed; stored exdate=${entity.exdate}, occurrences=$occurrences",
            occurrences.contains(dec27)
        )
        assertTrue(
            "Dec 26 09:00 LA must SURVIVE (it was not excepted); occurrences=$occurrences",
            occurrences.contains(dec26)
        )
        assertEquals("5 minus 1 excepted = 4", 4, occurrences.size)
    }

    @Test
    fun `DATE-form EXDATE on a positive-offset timed master suppresses the correct local day`() {
        // Master in Asia/Tokyo (UTC+9). UTC midnight plus a positive offset stays on the
        // same calendar day, so this guards against an over-correction shifting the
        // exclusion to the wrong day.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-vt-tokyo@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=Asia/Tokyo:20251225T090000
            DTEND;TZID=Asia/Tokyo:20251225T100000
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE;VALUE=DATE:20251227
            SUMMARY:Daily standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(parsed, ics, calendarId = 1L, caldavUrl = null, etag = null).event

        val occurrences = IcalDavRRuleEngine.expandToTimestamps(
            rrule = entity.rrule,
            dtstartMs = entity.startTs,
            rangeStartMs = zoned("Asia/Tokyo", 2025, 12, 25, 0, 0),
            rangeEndMs = zoned("Asia/Tokyo", 2025, 12, 30, 0, 0),
            timezone = entity.timezone,
            isAllDay = entity.isAllDay,
            rdateStrings = entity.rdate,
            exdateStrings = entity.exdate,
        )

        val dec27 = zoned("Asia/Tokyo", 2025, 12, 27, 9, 0)
        val dec28 = zoned("Asia/Tokyo", 2025, 12, 28, 9, 0)
        assertFalse(
            "Dec 27 09:00 Tokyo must be suppressed; stored exdate=${entity.exdate}, occurrences=$occurrences",
            occurrences.contains(dec27)
        )
        assertTrue(
            "Dec 28 09:00 Tokyo must SURVIVE (it was not excepted); occurrences=$occurrences",
            occurrences.contains(dec28)
        )
        assertEquals("5 minus 1 excepted = 4", 4, occurrences.size)
    }
}
