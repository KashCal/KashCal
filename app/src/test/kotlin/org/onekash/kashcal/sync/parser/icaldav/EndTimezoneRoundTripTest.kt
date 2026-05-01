package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A0.2 — Event.endTimezone mapper wire-up.
 *
 * Verifies that events with distinct DTSTART and DTEND TZIDs (e.g., flights
 * SFO→JFK) round-trip through KashCal's CalDAV mapper layer correctly:
 * - Inbound: server's distinct DTEND TZID is stored in Event.endTimezone
 * - Inbound: matching TZIDs collapse to endTimezone=null (per doc invariant)
 * - Outbound fresh + patch: Event.endTimezone reflected as distinct DTEND TZID
 *
 * CalendarProvider side is out of scope (B5.5). RFC 5545 §3.8.2.2 permits
 * distinct TZIDs on DTSTART vs DTEND.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EndTimezoneRoundTripTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    private fun createEvent(
        uid: String = "a02-test@kashcal.test",
        title: String = "A0.2 Test",
        startTs: Long = 1_767_088_800_000L,   // 2025-12-30T14:00:00Z
        endTs: Long = 1_767_106_800_000L,     // 2025-12-30T19:00:00Z
        isAllDay: Boolean = false,
        timezone: String? = "America/Los_Angeles",
        endTimezone: String? = null,
        rawIcal: String? = null,
        sequence: Int = 0
    ): Event = Event(
        uid = uid,
        calendarId = 1L,
        title = title,
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        timezone = timezone,
        endTimezone = endTimezone,
        status = "CONFIRMED",
        transp = "OPAQUE",
        classification = "PUBLIC",
        sequence = sequence,
        rawIcal = rawIcal,
        dtstamp = 0L,
        createdAt = 1_579_089_600_000L,
        updatedAt = 1_718_440_200_000L,
        syncStatus = SyncStatus.SYNCED
    )

    /**
     * Find lines starting with [prefix] that appear inside the first VEVENT block.
     * VTIMEZONE sub-components also emit DTSTART/DTEND for DST transitions, so a
     * plain top-level filter would match those. Scoping to VEVENT isolates the
     * event-level properties under test.
     */
    private fun findLines(ics: String, prefix: String): List<String> {
        val lines = ics.lines()
        val start = lines.indexOfFirst { it.trim() == "BEGIN:VEVENT" }
        val end = lines.indexOfFirst { it.trim() == "END:VEVENT" }
        if (start < 0 || end < 0 || end <= start) return emptyList()
        return lines.subList(start + 1, end).filter { it.startsWith(prefix) }
    }

    // ========== Inbound parse (ICalEventMapper.toEntity) ==========

    @Test
    fun `inbound stores distinct endTimezone when DTEND TZID differs from DTSTART TZID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sfo-jfk@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/Los_Angeles:20251225T080000
            DTEND;TZID=America/New_York:20251225T163000
            SUMMARY:SFO to JFK
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        assertEquals("America/Los_Angeles", entity.timezone)
        assertEquals("America/New_York", entity.endTimezone)
    }

    @Test
    fun `inbound stores null endTimezone when DTEND TZID matches DTSTART TZID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:same-zone@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251225T100000
            DTEND;TZID=America/New_York:20251225T113000
            SUMMARY:Same Zone
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        assertEquals("America/New_York", entity.timezone)
        assertNull(
            "Matching DTEND TZID must normalize to null per Event.kt:127 invariant",
            entity.endTimezone
        )
    }

    @Test
    fun `inbound stores null endTimezone when DTEND absent (DURATION-only)`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-only@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251225T100000
            DURATION:PT5H30M
            SUMMARY:Duration Only
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        assertNull(entity.endTimezone)
    }

    @Test
    fun `inbound stores null endTimezone for all-day events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:all-day@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:Christmas
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        assertTrue(entity.isAllDay)
        assertNull(entity.endTimezone)
    }

    // ========== Outbound fresh (EventToICalEventMapper via IcsPatcher.generateFresh) ==========

    @Test
    fun `fresh path emits distinct DTSTART and DTEND TZIDs when endTimezone set`() {
        val event = createEvent(
            timezone = "America/Los_Angeles",
            endTimezone = "America/New_York"
        )

        val ics = IcsPatcher.generateFresh(event)

        val dtStartLine = findLines(ics, "DTSTART").single()
        val dtEndLine = findLines(ics, "DTEND").single()
        assertTrue(
            "DTSTART must carry TZID=America/Los_Angeles; got: $dtStartLine",
            dtStartLine.contains("TZID=America/Los_Angeles")
        )
        assertTrue(
            "DTEND must carry TZID=America/New_York; got: $dtEndLine",
            dtEndLine.contains("TZID=America/New_York")
        )
    }

    @Test
    fun `fresh path emits single TZID on both DTSTART and DTEND when endTimezone null`() {
        val event = createEvent(
            timezone = "America/New_York",
            endTimezone = null
        )

        val ics = IcsPatcher.generateFresh(event)

        val dtStartLine = findLines(ics, "DTSTART").single()
        val dtEndLine = findLines(ics, "DTEND").single()
        assertTrue(
            "DTSTART must carry TZID=America/New_York; got: $dtStartLine",
            dtStartLine.contains("TZID=America/New_York")
        )
        assertTrue(
            "DTEND must carry TZID=America/New_York (same as start); got: $dtEndLine",
            dtEndLine.contains("TZID=America/New_York")
        )
    }

    @Test
    fun `fresh path falls back to start zone when endTimezone is invalid IANA`() {
        // Windows TZID "Pacific Standard Time" is not a valid IANA ID.
        // resolveZone() returns null for it; mapper falls back to start zone.
        val event = createEvent(
            timezone = "America/Los_Angeles",
            endTimezone = "Pacific Standard Time"
        )

        val ics = IcsPatcher.generateFresh(event)

        val dtEndLine = findLines(ics, "DTEND").single()
        assertTrue(
            "DTEND must fall back to start zone (TZID=America/Los_Angeles); got: $dtEndLine",
            dtEndLine.contains("TZID=America/Los_Angeles")
        )
        assertFalse(
            "DTEND must not emit malformed TZID=Pacific Standard Time; got: $dtEndLine",
            dtEndLine.contains("Pacific Standard Time")
        )
    }

    @Test
    fun `fresh path exception overload respects exception endTimezone`() {
        val masterUid = "recurring-master@kashcal.test"
        val master = createEvent(
            uid = masterUid,
            timezone = "America/Los_Angeles",
            endTimezone = null
        )
        val exception = createEvent(
            uid = masterUid,
            timezone = "America/Los_Angeles",
            endTimezone = "America/New_York"
        ).copy(
            originalEventId = 1L,
            originalInstanceTime = master.startTs
        )

        val icalEvent = EventToICalEventMapper.toICalEvent(master, exception)

        assertNotNull("Exception DTEND must carry its own timezone", icalEvent.dtEnd?.timezone)
        assertEquals(
            "Exception DTEND TZID must come from exception.endTimezone",
            "America/New_York",
            icalEvent.dtEnd?.timezone?.id
        )
        assertEquals(
            "Exception DTSTART TZID must come from exception.timezone",
            "America/Los_Angeles",
            icalEvent.dtStart.timezone?.id
        )
    }

    // ========== Outbound patch (IcsPatcher.patch) ==========

    @Test
    fun `patch path overrides DTEND TZID when Event endTimezone differs from rawIcal`() {
        val raw = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:patch-override@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251225T080000
            DTEND;TZID=America/New_York:20251225T163000
            SUMMARY:Originally Same Zone
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "patch-override@kashcal.test",
            timezone = "America/Los_Angeles",
            endTimezone = "America/New_York",
            rawIcal = raw
        )

        val ics = IcsPatcher.patch(raw, event)

        val dtStartLine = findLines(ics, "DTSTART").single()
        val dtEndLine = findLines(ics, "DTEND").single()
        // Positive: reflects Event columns
        assertTrue(
            "Patched DTSTART must carry Event.timezone (America/Los_Angeles); got: $dtStartLine",
            dtStartLine.contains("TZID=America/Los_Angeles")
        )
        assertTrue(
            "Patched DTEND must carry Event.endTimezone (America/New_York); got: $dtEndLine",
            dtEndLine.contains("TZID=America/New_York")
        )
        // Negative: rawIcal's original DTSTART TZID must NOT survive into output
        // (catches hypothetical .copy() preservation bug where original.dtStart.zone leaked).
        assertFalse(
            "Patched DTSTART must not preserve rawIcal's original TZID=America/New_York; got: $dtStartLine",
            dtStartLine.contains("TZID=America/New_York")
        )
    }

    // ========== Round-trip ==========

    @Test
    fun `round-trip through fresh+parse preserves distinct endTimezone`() {
        val original = createEvent(
            timezone = "America/Los_Angeles",
            endTimezone = "America/New_York"
        )

        val ics = IcsPatcher.generateFresh(original)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val roundTripped = ICalEventMapper.toEntity(parsed, ics, 1L, null, null)

        assertEquals("America/Los_Angeles", roundTripped.timezone)
        assertEquals("America/New_York", roundTripped.endTimezone)
    }

    @Test
    fun `round-trip through patch+parse preserves distinct endTimezone override`() {
        // Server originally same-zone; user edits to distinct zones; push; pull back.
        val raw = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-patch@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251225T080000
            DTEND;TZID=America/New_York:20251225T163000
            SUMMARY:Originally Same Zone
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val edited = createEvent(
            uid = "roundtrip-patch@kashcal.test",
            timezone = "America/Los_Angeles",
            endTimezone = "America/New_York",
            rawIcal = raw
        )

        val patchedIcs = IcsPatcher.patch(raw, edited)
        val parsed = parser.parseAllEvents(patchedIcs).getOrNull()!!.first()
        val roundTripped = ICalEventMapper.toEntity(parsed, patchedIcs, 1L, null, null)

        assertEquals("America/Los_Angeles", roundTripped.timezone)
        assertEquals("America/New_York", roundTripped.endTimezone)
    }

    // ========== Asymmetric edge case ==========

    @Test
    fun `inbound accepts floating start paired with distinct DTEND zone`() {
        // RFC 5545-valid but unusual. DTSTART has no TZID (floating time);
        // DTEND has an explicit TZID. Normalization logic must treat
        // (timezone=null) != ("America/New_York") and keep endTimezone set.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:floating-start@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T080000
            DTEND;TZID=America/New_York:20251225T163000
            SUMMARY:Floating Start Only
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        // Parser may normalize floating DTSTART to UTC; don't assert on entity.timezone.
        // What A0.2 guarantees is that a distinct DTEND zone survives regardless.
        assertEquals(
            "Distinct DTEND zone must be preserved when start is floating",
            "America/New_York",
            entity.endTimezone
        )
    }
}
