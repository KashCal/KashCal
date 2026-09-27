package org.onekash.kashcal.rfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests RFC 5545 conformance of the event serialize and parse paths ([IcsPatcher],
 * [ICalGenerator], [ICalParser], [EventToICalEventMapper], [ICalEventMapper]). Each test names
 * the RFC section it exercises.
 *
 * Covered: line folding and CRLF (§3.1), UTF-8 (§3.1.4), DATE-TIME forms (§3.3.5), RRULE
 * (§3.3.10), TEXT escaping (§3.3.11), all-day and recurring DTEND (§3.6.1), PRODID and VERSION
 * (§3.7.3, §3.7.4), CLASS, GEO and PRIORITY (§3.8.1), TRANSP (§3.8.2.7), UID and RECURRENCE-ID
 * (§3.8.4), EXDATE and RDATE (§3.8.5), change management (§3.8.7), one VCALENDAR for a master
 * and its exceptions, and one VTIMEZONE per shared TZID (§3.6.5). The TRANSP test names cite
 * §3.8.1.7, which is LOCATION.
 *
 * Out of scope: VTODO, VJOURNAL and VFREEBUSY (PullStrategy skips a resource holding one of
 * them and no VEVENT), and iTIP scheduling.
 */
class Rfc5545ComplianceAuditTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator(prodId = "-//KashCal//Audit//EN")

    // ----------------------------------------------------------------------
    // §3.1  Content lines (line folding)
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-1 - long SUMMARY is folded so no line exceeds 75 octets`() {
        val long = "x".repeat(300)
        val event = baseEvent(title = long)
        val ics = IcsPatcher.serialize(event)

        ics.split("\r\n", "\n").forEach { line ->
            assertTrue(
                "Line exceeded 75 octets (${line.toByteArray(Charsets.UTF_8).size}): $line",
                line.toByteArray(Charsets.UTF_8).size <= 75
            )
        }
    }

    @Test
    fun `RFC 5545 §3-1 - generator output uses CRLF as required line break`() {
        val ics = IcsPatcher.serialize(baseEvent(title = "CRLF check"))
        // RFC 5545 §3.1: "Content lines are delimited by a line break, which is a CRLF
        // sequence (CR character followed by LF character)."
        assertTrue(
            "Output must contain CRLF line breaks",
            ics.contains("\r\n")
        )
    }

    @Test
    fun `RFC 5545 §3-1 - parser unfolds CRLF+SPACE in round trip of long DESCRIPTION`() {
        val long = "y".repeat(400)
        val event = baseEvent(description = long)
        val ics = IcsPatcher.serialize(event)
        val reparsed = parseFirstEvent(ics)
        assertEquals(long, reparsed.description)
    }

    // ----------------------------------------------------------------------
    // §3.1.4  Character set
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-1-4 - UTF-8 emoji and CJK survive round trip without splitting code points`() {
        val text = "Lunch 🍕 with 田中 — fast and tasty!"
        val event = baseEvent(title = text)
        val ics = IcsPatcher.serialize(event)
        val reparsed = parseFirstEvent(ics)
        assertEquals(text, reparsed.summary)
    }

    // ----------------------------------------------------------------------
    // §3.3.5  DATE-TIME (Form 2 = UTC, Form 3 = TZID)
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-3-5 - timed event with TZID emits Form 3 DATE-TIME and TZID parameter`() {
        val zone = ZoneId.of("America/New_York")
        val start = ZonedDateTime.of(2026, 6, 15, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        val event = baseEvent(timezone = "America/New_York", startTs = start, endTs = start + 3_600_000)
        val ics = IcsPatcher.serialize(event)
        assertTrue(
            "DTSTART must include TZID parameter:\n$ics",
            Regex("DTSTART;TZID=America/New_York:20260615T093000").containsMatchIn(ics)
        )
        assertFalse(
            "Form 3 DATE-TIME must not have Z suffix",
            Regex("DTSTART;TZID=[^:]+:[0-9]{8}T[0-9]{6}Z").containsMatchIn(ics)
        )
    }

    @Test
    fun `RFC 5545 §3-3-5 - DTSTAMP is always emitted in UTC Form 2`() {
        val ics = IcsPatcher.serialize(baseEvent())
        // RFC 5545 §3.8.7.2: DTSTAMP is a DATE-TIME, and "The value MUST be specified in the
        // UTC time format." Looks for DTSTAMP:YYYYMMDDTHHMMSSZ.
        assertTrue(
            "DTSTAMP must be UTC (Form 2):\n$ics",
            Regex("DTSTAMP:[0-9]{8}T[0-9]{6}Z").containsMatchIn(ics)
        )
    }

    // ----------------------------------------------------------------------
    // §3.3.10  RRULE
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-3-10 - RRULE round trip preserves FREQ=WEEKLY BYDAY=MO,WE,FR`() {
        val rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        val event = baseEvent(rrule = rrule)
        val ics = IcsPatcher.serialize(event)
        val reparsed = parseFirstEvent(ics)
        val emitted = reparsed.rrule?.toICalString() ?: ""
        assertTrue(
            "FREQ must round-trip: emitted='$emitted'",
            emitted.contains("FREQ=WEEKLY")
        )
        assertTrue("BYDAY must round-trip: emitted='$emitted'", emitted.contains("BYDAY="))
        listOf("MO", "WE", "FR").forEach {
            assertTrue("$it must remain in BYDAY: emitted='$emitted'", emitted.contains(it))
        }
    }

    @Test
    fun `RFC 5545 §3-3-10 - non-recurring exception VEVENT must not emit RRULE`() {
        val master = baseEvent(rrule = "FREQ=DAILY;COUNT=5")
        val exception = baseEvent(
            uid = master.uid,
            title = "Override",
            originalEventId = 42L,
            originalInstanceTime = master.startTs + 86_400_000L
        )
        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val veventBlocks = Regex("BEGIN:VEVENT.*?END:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(ics).toList()
        assertEquals("Master + 1 exception = 2 VEVENTs", 2, veventBlocks.size)
        val exceptionBlock = veventBlocks.first { it.value.contains("RECURRENCE-ID") }.value
        assertFalse(
            "Exception VEVENT must not contain RRULE (RFC 5545 §3.8.5 forbids RRULE on a single instance)",
            exceptionBlock.contains("\nRRULE:") || exceptionBlock.contains("\rRRULE:")
        )
    }

    // ----------------------------------------------------------------------
    // §3.3.11  TEXT escaping
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-3-11 - DESCRIPTION with comma and semicolon and backslash and newline survives round trip`() {
        val description = "Line 1, with comma; and semicolon\nand backslash \\\\ end"
        val event = baseEvent(description = description)
        val ics = IcsPatcher.serialize(event)
        val reparsed = parseFirstEvent(ics)
        assertEquals(description, reparsed.description)
    }

    @Test
    fun `RFC 5545 §3-3-11 - escaped newline lowercase n is unescaped on parse`() {
        // RFC 5545 §3.3.11: a line break in a TEXT value is written as BACKSLASH followed by
        // "n" or "N".
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:audit-escape@kashcal
DTSTAMP:20260101T000000Z
DTSTART:20260101T100000Z
DTEND:20260101T110000Z
SUMMARY:Line1\nLine2
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")
        val ev = parseFirstEvent(ics)
        assertEquals("Line1\nLine2", ev.summary)
    }

    @Test
    fun `RFC 5545 §3-3-11 - escaped newline uppercase N is unescaped on parse`() {
        // RFC 5545 §3.3.11 allows '\N' (uppercase) as a newline escape.
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:audit-escape-N@kashcal
DTSTAMP:20260101T000000Z
DTSTART:20260101T100000Z
DTEND:20260101T110000Z
SUMMARY:Line1\NLine2
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")
        val ev = parseFirstEvent(ics)
        assertEquals(
            "RFC 5545 §3.3.11: \\N MUST decode to a newline (case-insensitive).",
            "Line1\nLine2",
            ev.summary
        )
    }

    // ----------------------------------------------------------------------
    // §3.6.1  VEVENT semantics
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-6-1 - all-day VEVENT emits exclusive DTEND on next day`() {
        val zone = ZoneId.of("UTC")
        val day = ZonedDateTime.of(2026, 3, 5, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        // An all-day [Event.endTs] is inclusive (the last ms of the last day); the serializer
        // must emit the exclusive DTEND (next day 00:00).
        val event = baseEvent(
            isAllDay = true,
            startTs = day,
            endTs = day + 86_400_000L - 1L
        )
        val ics = IcsPatcher.serialize(event)
        assertTrue(
            "All-day DTSTART must be VALUE=DATE form:\n$ics",
            ics.contains("DTSTART;VALUE=DATE:20260305")
        )
        assertTrue(
            "All-day DTEND must be exclusive next day (20260306) per RFC 5545 §3.6.1:\n$ics",
            ics.contains("DTEND;VALUE=DATE:20260306")
        )
    }

    @Test
    fun `RFC 5545 §3-6-1 - recurring VEVENT emits DTEND not DURATION`() {
        // RFC 5545 §3.6.1 allows DTEND or DURATION, never both. Every serialize path emits
        // DTEND (rule on [EventToICalEventMapper.toICalEvent]), which also serves interop: iCloud
        // rejects an EXDATE update on a bounded recurring scheduling object expressed with
        // DURATION, while DTEND is accepted across servers.
        val zone = ZoneId.of("America/New_York")
        val start = ZonedDateTime.of(2026, 3, 1, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val event = baseEvent(
            timezone = "America/New_York",
            startTs = start,
            endTs = start + 3_600_000L,
            rrule = "FREQ=WEEKLY;COUNT=10"
        )
        val ics = IcsPatcher.serialize(event)
        assertTrue(
            "Recurring event should emit DTEND:\n$ics",
            Regex("(?m)^DTEND[:;]").containsMatchIn(ics)
        )
        assertFalse(
            "Recurring event should not emit DURATION (DTEND form for path convergence + interop):\n$ics",
            ics.contains("DURATION:")
        )
    }

    // ----------------------------------------------------------------------
    // §3.7  Calendar properties (PRODID, VERSION)
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-7-3 and §3-7-4 - VCALENDAR carries PRODID and VERSION 2-0`() {
        val ics = IcsPatcher.serialize(baseEvent())
        assertTrue("PRODID required:\n$ics", Regex("(?m)^PRODID:").containsMatchIn(ics))
        assertTrue("VERSION:2.0 required:\n$ics", ics.contains("VERSION:2.0"))
    }

    // ----------------------------------------------------------------------
    // §3.8.2.7  TRANSP: free/busy semantics
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-8-1-7 - default TRANSP is OPAQUE and is omitted on the wire to match default`() {
        val ics = IcsPatcher.serialize(baseEvent(transp = "OPAQUE"))
        // The generator omits TRANSP when it is OPAQUE, the RFC default, so the parser must
        // read the absence as OPAQUE. Both halves are asserted.
        assertFalse("Default OPAQUE should not be emitted:\n$ics", ics.contains("TRANSP:OPAQUE"))
        val reparsed = parseFirstEvent(ics)
        assertEquals(Transparency.OPAQUE, reparsed.transparency)
    }

    @Test
    fun `RFC 5545 §3-8-1-7 - TRANSPARENT is emitted explicitly and round trips into Event entity`() {
        val ev = baseEvent(transp = "TRANSPARENT")
        val ics = IcsPatcher.serialize(ev)
        assertTrue("TRANSPARENT must be emitted:\n$ics", ics.contains("TRANSP:TRANSPARENT"))
        val parsed = parseFirstEvent(ics)
        assertEquals(Transparency.TRANSPARENT, parsed.transparency)
        // The mapper must keep TRANSP on the Event row (free/busy depends on it).
        val entity = ICalEventMapper.toEntity(parsed, ics, calendarId = 1L, caldavUrl = null, etag = null).event
        assertEquals("TRANSPARENT", entity.transp)
    }

    @Test
    fun `RFC 5545 §3-8-1-7 - unknown TRANSP value falls back to OPAQUE per Transparency-fromString`() {
        // RFC 5545 §3.8.2.7 defines two values, OPAQUE and TRANSPARENT; an unknown value
        // should not crash.
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:audit-transp@kashcal
DTSTAMP:20260101T000000Z
DTSTART:20260101T100000Z
DTEND:20260101T110000Z
SUMMARY:Bogus TRANSP value
TRANSP:BUSY
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")
        val parsed = parseFirstEvent(ics)
        assertEquals(
            "Unknown TRANSP token must be treated as OPAQUE (the safer default for free/busy)",
            Transparency.OPAQUE,
            parsed.transparency
        )
    }

    // ----------------------------------------------------------------------
    // §3.8.1.3 / .6 / .9  Descriptive component properties (CLASS, GEO, PRIORITY)
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-8-1-3 - CLASS round-trips PUBLIC PRIVATE and CONFIDENTIAL`() {
        listOf("PUBLIC", "PRIVATE", "CONFIDENTIAL").forEach { cls ->
            val ev = baseEvent(classification = cls)
            val ics = IcsPatcher.serialize(ev)
            val parsed = parseFirstEvent(ics)
            val entity = ICalEventMapper.toEntity(parsed, ics, 1L, null, null).event
            assertEquals("CLASS $cls must round-trip", cls, entity.classification)
        }
    }

    @Test
    fun `RFC 5545 §3-8-1-9 - PRIORITY 1 through 9 round trips and 0 means undefined`() {
        for (p in 1..9) {
            val ev = baseEvent(priority = p)
            val ics = IcsPatcher.serialize(ev)
            assertTrue("PRIORITY:$p missing for $p:\n$ics", ics.contains("PRIORITY:$p"))
            val parsed = parseFirstEvent(ics)
            assertEquals(p, parsed.priority)
        }
        val zero = baseEvent(priority = 0)
        val icsZero = IcsPatcher.serialize(zero)
        assertFalse(
            "PRIORITY:0 (undefined) should not be emitted as a wire property:\n$icsZero",
            Regex("(?m)^PRIORITY:0\\b").containsMatchIn(icsZero)
        )
    }

    @Test
    fun `RFC 5545 §3-8-1-6 - GEO round-trips lat-semi-lon to entity`() {
        val ev = baseEvent(geoLat = 37.386013, geoLon = -122.082932)
        val ics = IcsPatcher.serialize(ev)
        assertTrue("GEO must use semicolon separator:\n$ics", ics.contains("GEO:37.386013;-122.082932"))
        val parsed = parseFirstEvent(ics)
        val entity = ICalEventMapper.toEntity(parsed, ics, 1L, null, null).event
        assertEquals(37.386013, entity.geoLat ?: 0.0, 1e-9)
        assertEquals(-122.082932, entity.geoLon ?: 0.0, 1e-9)
    }

    // ----------------------------------------------------------------------
    // §3.8.4.7  UID
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-8-4-7 - UID is preserved exactly through round trip`() {
        val tricky = "audit-UID-with.dots+plus_under-and-dash@example.com"
        val ev = baseEvent(uid = tricky)
        val ics = IcsPatcher.serialize(ev)
        val parsed = parseFirstEvent(ics)
        assertEquals(tricky, parsed.uid)
    }

    @Test
    fun `RFC 5545 §3-8-4-7 - master and exception share the same UID`() {
        val master = baseEvent(uid = "shared-uid@kashcal", rrule = "FREQ=DAILY;COUNT=3")
        val exception = baseEvent(
            uid = master.uid,
            originalEventId = 1L,
            originalInstanceTime = master.startTs + 86_400_000L,
            title = "Different title"
        )
        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val uidMatches = Regex("(?m)^UID:(.+)$").findAll(ics).map { it.groupValues[1].trim() }.toList()
        assertEquals("Both VEVENTs must carry a UID", 2, uidMatches.size)
        assertEquals(
            "Master and exception must share UID (RFC 5545 §3.8.4.7); exceptions are distinguished by RECURRENCE-ID, not UID",
            uidMatches.toSet().size,
            1
        )
    }

    // ----------------------------------------------------------------------
    // §3.8.4.4  RECURRENCE-ID
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-8-4-4 - exception emits RECURRENCE-ID and master does not`() {
        val master = baseEvent(rrule = "FREQ=WEEKLY;COUNT=5")
        val exception = baseEvent(
            uid = master.uid,
            originalEventId = 1L,
            originalInstanceTime = master.startTs + 7L * 86_400_000L
        )
        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val veventBlocks = Regex("BEGIN:VEVENT.*?END:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(ics).toList()
        val masterBlock = veventBlocks.first { !it.value.contains("RECURRENCE-ID") }.value
        val exceptionBlock = veventBlocks.first { it.value.contains("RECURRENCE-ID") }.value
        assertFalse("Master must not contain RECURRENCE-ID", masterBlock.contains("RECURRENCE-ID"))
        assertTrue("Exception must contain RECURRENCE-ID", exceptionBlock.contains("RECURRENCE-ID"))
    }

    // ----------------------------------------------------------------------
    // §3.8.5.1 / 3.8.5.2  EXDATE / RDATE
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-8-5-1 - EXDATE round trips through entity to ICS`() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val excluded = start + 7L * 86_400_000L
        val ev = baseEvent(
            startTs = start,
            endTs = start + 3_600_000L,
            rrule = "FREQ=WEEKLY;COUNT=3",
            exdate = excluded.toString()
        )
        val ics = IcsPatcher.serialize(ev)
        assertTrue(
            "EXDATE must be present on the wire when the entity has one:\n$ics",
            Regex("(?m)^EXDATE[:;]").containsMatchIn(ics)
        )
    }

    @Test
    fun `RFC 5545 §3-8-5-2 - RDATE round trips through entity to ICS`() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val extra = start + 14L * 86_400_000L
        val ev = baseEvent(
            startTs = start,
            endTs = start + 3_600_000L,
            rrule = "FREQ=WEEKLY;COUNT=3",
            rdate = extra.toString()
        )
        val ics = IcsPatcher.serialize(ev)
        assertTrue(
            "RDATE must be present on the wire when the entity has one:\n$ics",
            Regex("(?m)^RDATE[:;]").containsMatchIn(ics)
        )
    }

    // ----------------------------------------------------------------------
    // §3.8.7  Change management: DTSTAMP, SEQUENCE, LAST-MODIFIED, CREATED
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-8-7-2 - every VEVENT carries DTSTAMP`() {
        val ics = IcsPatcher.serialize(baseEvent())
        assertTrue(
            "DTSTAMP is REQUIRED by RFC 5545 §3.6.1 / §3.8.7.2:\n$ics",
            Regex("(?m)^DTSTAMP:").containsMatchIn(ics)
        )
    }

    @Test
    fun `RFC 5545 §3-8-7-4 - SEQUENCE round-trips and defaults to 0 when absent`() {
        val zero = baseEvent(sequence = 0)
        val seven = baseEvent(sequence = 7)
        assertEquals(0, parseFirstEvent(IcsPatcher.serialize(zero)).sequence)
        assertEquals(7, parseFirstEvent(IcsPatcher.serialize(seven)).sequence)
    }

    @Test
    fun `RFC 5545 §3-8-7 - DTSTAMP CREATED and LAST-MODIFIED are all UTC when emitted`() {
        val ics = IcsPatcher.serialize(baseEvent())
        listOf("DTSTAMP", "CREATED", "LAST-MODIFIED").forEach { prop ->
            val match = Regex("(?m)^$prop:([^\r\n]+)").find(ics)
            if (match != null) {
                val v = match.groupValues[1]
                assertTrue(
                    "$prop must be UTC (Form 2, ends with Z): '$v'",
                    v.endsWith("Z")
                )
            }
        }
    }

    // ----------------------------------------------------------------------
    // §3.4 + §3.6  Calendar bundling (master and exceptions in one VCALENDAR)
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-6 - exporting master plus exceptions yields a single VCALENDAR with shared envelope`() {
        val master = baseEvent(rrule = "FREQ=DAILY;COUNT=10")
        val exceptions = listOf(
            baseEvent(
                uid = master.uid,
                originalEventId = 1L,
                originalInstanceTime = master.startTs + 1L * 86_400_000L,
                title = "Day 2 override"
            ),
            baseEvent(
                uid = master.uid,
                originalEventId = 1L,
                originalInstanceTime = master.startTs + 3L * 86_400_000L,
                title = "Day 4 override"
            )
        )
        val ics = IcsPatcher.serializeWithExceptions(master, exceptions)
        val vcalCount = Regex("BEGIN:VCALENDAR").findAll(ics).count()
        val veventCount = Regex("BEGIN:VEVENT").findAll(ics).count()
        assertEquals("Exactly one VCALENDAR envelope (RFC 5545 §3.4)", 1, vcalCount)
        assertEquals("Master + 2 exceptions = 3 VEVENTs", 3, veventCount)
    }

    // ----------------------------------------------------------------------
    // App-side calendar bundle generation (used by IcsExporter)
    // ----------------------------------------------------------------------

    @Test
    fun `RFC 5545 §3-6-5 - calendar bundle dedupes VTIMEZONE for events that share a TZID`() {
        val zone = ZoneId.of("Europe/Berlin")
        val start1 = ZonedDateTime.of(2026, 5, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start2 = ZonedDateTime.of(2026, 5, 2, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val e1 = baseEvent(uid = "berlin-1@k", timezone = "Europe/Berlin", startTs = start1, endTs = start1 + 3_600_000L)
        val e2 = baseEvent(uid = "berlin-2@k", timezone = "Europe/Berlin", startTs = start2, endTs = start2 + 3_600_000L)
        val ics = generator.generate(
            ICalCalendar(
                prodId = null,
                xWrCalname = "AuditBundle",
                events = listOf(EventToICalEventMapper.toICalEvent(e1), EventToICalEventMapper.toICalEvent(e2))
            ),
            includeVTimezone = true
        )
        val vtzCount = Regex("BEGIN:VTIMEZONE").findAll(ics).count()
        assertEquals(
            "Two events sharing TZID Europe/Berlin should produce exactly one VTIMEZONE block",
            1,
            vtzCount
        )
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private fun baseEvent(
        uid: String = "audit-${(0..1_000_000).random()}@kashcal",
        title: String = "Audit event",
        description: String? = null,
        startTs: Long = Instant.parse("2026-01-15T10:00:00Z").toEpochMilli(),
        endTs: Long = Instant.parse("2026-01-15T11:00:00Z").toEpochMilli(),
        timezone: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        rdate: String? = null,
        exdate: String? = null,
        transp: String = "OPAQUE",
        classification: String = "PUBLIC",
        priority: Int = 0,
        sequence: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null
    ): Event = Event(
        id = 0L,
        uid = uid,
        importId = uid,
        calendarId = 1L,
        title = title,
        description = description,
        location = null,
        startTs = startTs,
        endTs = endTs,
        timezone = timezone,
        isAllDay = isAllDay,
        rrule = rrule,
        rdate = rdate,
        exdate = exdate,
        transp = transp,
        classification = classification,
        priority = priority,
        sequence = sequence,
        geoLat = geoLat,
        geoLon = geoLon,
        dtstamp = startTs,
        createdAt = startTs,
        updatedAt = startTs,
        originalEventId = originalEventId,
        originalInstanceTime = originalInstanceTime
    )

    private fun parseFirstEvent(ics: String): ICalEvent {
        val result = parser.parseAllEvents(ics)
        assertTrue("parseAllEvents must succeed: $result", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertTrue("at least one VEVENT", events.isNotEmpty())
        return events.first()
    }
}
