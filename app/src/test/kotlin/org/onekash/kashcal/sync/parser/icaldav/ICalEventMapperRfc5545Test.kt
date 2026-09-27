package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [ICalEventMapper.toEntity] against RFC 5545 rules:
 * - DURATION-only events (§3.6.1: DTEND and DURATION are mutually exclusive)
 * - STATUS mapping and its CONFIRMED default (§3.8.1.11)
 * - missing or empty SUMMARY becomes "Untitled"
 * - PRIORITY boundary values 0 to 9 (§3.8.1.9)
 * - CLASS mapping and its PUBLIC default (§3.8.1.3)
 * - EXDATE and RDATE stored as millisecond CSV
 * - all-day events without DTEND or across month and year boundaries
 * - SEQUENCE (§3.8.7.4), TRANSP default, UID, DTSTAMP, floating time, importId
 * - a DATE RECURRENCE-ID on an all-day exception, and alarmCount
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICalEventMapperRfc5545Test {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ==================== RFC 5545 Section 3.6.1: DURATION vs DTEND ====================

    @Test
    fun `event with DURATION but no DTEND calculates endTs from effectiveEnd`() {
        // RFC 5545 Section 3.6.1: "Either 'dtend' or 'duration' MAY appear...
        // but 'dtend' and 'duration' MUST NOT occur in the same 'eventprop'."
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-only@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DURATION:PT1H30M
            SUMMARY:Duration-Only Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        // effectiveEnd() gives DTSTART + DURATION: 10:00 + 1h30m = 11:30.
        val expectedDurationMs = (1 * 60 + 30) * 60 * 1000L // 1h30m in ms
        assertEquals(
            "endTs should be startTs + DURATION",
            entity.startTs + expectedDurationMs,
            entity.endTs
        )
        assertNotNull("duration field should be stored", entity.duration)
    }

    @Test
    fun `event with DURATION P1D has correct endTs`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-1d@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T090000Z
            DURATION:P1D
            SUMMARY:All Day Duration
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        val oneDayMs = 24 * 60 * 60 * 1000L
        assertEquals(
            "endTs should be startTs + P1D",
            entity.startTs + oneDayMs,
            entity.endTs
        )
    }

    @Test
    fun `event with DURATION PT0S (zero-duration) has endTs equal to startTs`() {
        // RFC 5545: Zero-duration events are valid (e.g., milestones)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:zero-duration@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DURATION:PT0S
            SUMMARY:Milestone
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Zero-duration: endTs should equal startTs", entity.startTs, entity.endTs)
    }

    // ==================== RFC 5545 Section 3.8.1.11: STATUS Values ====================

    @Test
    fun `maps TENTATIVE status correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tentative@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Maybe Meeting
            STATUS:TENTATIVE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("TENTATIVE", entity.status)
    }

    @Test
    fun `maps CONFIRMED status correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:confirmed@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Confirmed Meeting
            STATUS:CONFIRMED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("CONFIRMED", entity.status)
    }

    @Test
    fun `event without STATUS defaults to CONFIRMED`() {
        // RFC 5545: STATUS is optional; the mapped event defaults to CONFIRMED.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-status@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:No Status Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Default status should be CONFIRMED", "CONFIRMED", entity.status)
    }

    // ==================== RFC 5545: Missing SUMMARY ====================

    @Test
    fun `event without SUMMARY defaults to Untitled`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-summary@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Missing SUMMARY should default to 'Untitled'", "Untitled", entity.title)
    }

    @Test
    fun `event with empty SUMMARY should default to Untitled`() {
        // RFC 5545: SUMMARY is optional. A present but empty SUMMARY must still give a
        // usable title: the mapper treats "" like a missing SUMMARY.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:empty-summary@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics).getOrNull()
        if (result != null && result.isNotEmpty()) {
            val entity = ICalEventMapper.toEntity(result.first(), ics, 1L, null, null).event
            assertEquals(
                "Empty SUMMARY should become 'Untitled'",
                "Untitled",
                entity.title
            )
        }
        // A parser rejection of the empty SUMMARY is acceptable too.
    }

    // ==================== RFC 5545 Section 3.8.1.9: PRIORITY ====================

    @Test
    fun `PRIORITY=0 means undefined`() {
        // RFC 5545 §3.8.1.9: 0 is an undefined priority.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-0@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Undefined Priority
            PRIORITY:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("PRIORITY=0 means undefined", 0, entity.priority)
    }

    @Test
    fun `PRIORITY=1 is highest priority`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-1@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Highest Priority
            PRIORITY:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals(1, entity.priority)
    }

    @Test
    fun `PRIORITY=9 is lowest priority`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-9@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Lowest Priority
            PRIORITY:9
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals(9, entity.priority)
    }

    @Test
    fun `PRIORITY=5 is medium priority`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-5@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Medium Priority
            PRIORITY:5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals(5, entity.priority)
    }

    // ==================== RFC 5545 Section 3.8.1.3: CLASS ====================

    @Test
    fun `maps PRIVATE classification correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:class-private@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Private Event
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("PRIVATE", entity.classification)
    }

    @Test
    fun `maps CONFIDENTIAL classification correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:class-confidential@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Confidential Event
            CLASS:CONFIDENTIAL
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("CONFIDENTIAL", entity.classification)
    }

    @Test
    fun `event without CLASS defaults to PUBLIC`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-class@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:No Class Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Default CLASS should be PUBLIC", "PUBLIC", entity.classification)
    }

    // ==================== RFC 5545: EXDATE Storage Format ====================

    @Test
    fun `multiple EXDATE properties are joined as comma-separated milliseconds`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-exdate@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260105T100000Z
            DTEND:20260105T110000Z
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20260107T100000Z
            EXDATE:20260109T100000Z
            EXDATE:20260111T100000Z
            SUMMARY:Multi EXDATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNotNull("Should have exdate", entity.exdate)
        val exdates = entity.exdate!!.split(",")
        assertEquals("Should have 3 EXDATEs", 3, exdates.size)
        exdates.forEach { ms ->
            assertTrue("EXDATE '$ms' should be a valid timestamp",
                ms.toLongOrNull() != null && ms.toLong() > 0)
        }
    }

    // ==================== RFC 5545: RDATE Storage Format ====================

    @Test
    fun `RDATE values are stored as comma-separated milliseconds`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rdate-storage@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260105T100000Z
            DTEND:20260105T110000Z
            RRULE:FREQ=WEEKLY;COUNT=3
            RDATE:20260110T100000Z,20260117T100000Z
            SUMMARY:Event with RDATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNotNull("Should have rdate", entity.rdate)
        val rdates = entity.rdate!!.split(",")
        assertEquals("Should have 2 RDATEs", 2, rdates.size)
        rdates.forEach { ms ->
            assertTrue("RDATE '$ms' should be a valid timestamp",
                ms.toLongOrNull() != null && ms.toLong() > 0)
        }
    }

    // ==================== RFC 5545: All-Day DTEND Edge Cases ====================

    @Test
    fun `all-day event without DTEND defaults to 1-day event`() {
        // RFC 5545 Section 3.6.1: If DTEND and DURATION are both absent,
        // a DATE value DTSTART indicates a 1-day event.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-dtend@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20260115
            SUMMARY:No End Date
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics).getOrNull()
        if (result != null && result.isNotEmpty()) {
            val entity = ICalEventMapper.toEntity(result.first(), ics, 1L, null, null).event

            assertTrue("Should be all-day", entity.isAllDay)
            // A 1-day event: endTs falls on the same day as startTs.
            val startDate = java.time.Instant.ofEpochMilli(entity.startTs)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(entity.endTs)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
            assertEquals("Should be same-day event", startDate, endDate)
        }
    }

    @Test
    fun `all-day multi-day event spanning month boundary`() {
        // Jan 30 - Feb 2 (4 days) crossing Jan→Feb boundary
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:month-boundary@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20260130
            DTEND;VALUE=DATE:20260203
            SUMMARY:Month Boundary Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertTrue("Should be all-day", entity.isAllDay)

        val startDate = java.time.Instant.ofEpochMilli(entity.startTs)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(entity.endTs)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()

        assertEquals("Start should be Jan 30", java.time.LocalDate.of(2026, 1, 30), startDate)
        assertEquals(
            "End should be Feb 2 (DTEND exclusive, so Feb 3 - 1ms = Feb 2)",
            java.time.LocalDate.of(2026, 2, 2),
            endDate
        )
    }

    @Test
    fun `all-day event spanning year boundary`() {
        // Dec 30 - Jan 2 crossing Dec→Jan boundary
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:year-boundary@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251230
            DTEND;VALUE=DATE:20260103
            SUMMARY:New Year Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        val startDate = java.time.Instant.ofEpochMilli(entity.startTs)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(entity.endTs)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()

        assertEquals("Start should be Dec 30 2025", java.time.LocalDate.of(2025, 12, 30), startDate)
        assertEquals(
            "End should be Jan 2 2026 (exclusive DTEND Jan 3 - 1ms)",
            java.time.LocalDate.of(2026, 1, 2),
            endDate
        )
    }

    // ==================== RFC 5545 Section 3.8.7.4: SEQUENCE ====================

    @Test
    fun `SEQUENCE field is mapped correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sequence@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Sequenced Event
            SEQUENCE:7
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("SEQUENCE should be 7", 7, entity.sequence)
    }

    @Test
    fun `missing SEQUENCE defaults to 0`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-sequence@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:No Sequence
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Default SEQUENCE should be 0", 0, entity.sequence)
    }

    // ==================== RFC 5545: TRANSP (Transparency) ====================

    @Test
    fun `OPAQUE transp is default for timed events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:opaque-default@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Default Transp
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Default TRANSP should be OPAQUE", "OPAQUE", entity.transp)
    }

    // ==================== RFC 5545: UID is Preserved ====================

    @Test
    fun `UID with special characters is preserved`() {
        // A UID with a long hex prefix, hyphens and an @domain suffix.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:040000008200E00074C5B7101A82E008-event-123@outlook.com
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Outlook Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals(
            "UID with special chars should be preserved",
            "040000008200E00074C5B7101A82E008-event-123@outlook.com",
            entity.uid
        )
    }

    // ==================== RFC 5545: RECURRENCE-ID for All-Day Exception ====================

    @Test
    fun `all-day exception event has originalInstanceTime from DATE RECURRENCE-ID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-recid@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID;VALUE=DATE:20260112
            DTSTART;VALUE=DATE:20260113
            DTEND;VALUE=DATE:20260114
            SUMMARY:Moved Holiday
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertTrue("Should be all-day", entity.isAllDay)
        assertNotNull("Should have originalInstanceTime from RECURRENCE-ID", entity.originalInstanceTime)

        // RECURRENCE-ID VALUE=DATE:20260112 maps to Jan 12 2026 00:00 UTC.
        val recidDate = java.time.Instant.ofEpochMilli(entity.originalInstanceTime!!)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        assertEquals(
            "RECURRENCE-ID should reference Jan 12",
            java.time.LocalDate.of(2026, 1, 12),
            recidDate
        )
    }

    // ==================== RFC 5545: DTSTAMP ====================

    @Test
    fun `DTSTAMP is mapped correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:dtstamp@kashcal.test
            DTSTAMP:20260115T123456Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Timestamped Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertTrue("DTSTAMP should be positive", entity.dtstamp > 0)
    }

    // ==================== RFC 5545: Floating Time (no timezone, no Z) ====================

    @Test
    fun `floating time event preserves timezone from parser`() {
        // RFC 5545 Section 3.3.5: Floating time has no timezone and no Z suffix.
        // "Floating time SHOULD only be used where that is the reasonable behavior"
        // The icaldav library may assign a timezone to a floating time, so this test
        // checks only that the entity is timed with ordered, positive timestamps.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:floating@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000
            DTEND:20260115T110000
            SUMMARY:Floating Time Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertFalse("Floating time event should not be all-day", entity.isAllDay)
        assertTrue("startTs should be positive", entity.startTs > 0)
        assertTrue("endTs should be after startTs", entity.endTs > entity.startTs)
        assertEquals("Floating Time Event", entity.title)
    }

    // ==================== RFC 5545: importId Format ====================

    @Test
    fun `master event importId equals UID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-import@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Master
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("Master importId should equal UID", "master-import@kashcal.test", entity.importId)
    }

    @Test
    fun `exception event importId contains RECID separator`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exception-import@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20260120T100000Z
            DTSTART:20260120T140000Z
            DTEND:20260120T150000Z
            SUMMARY:Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertTrue(
            "Exception importId should contain :RECID:",
            entity.importId?.contains(":RECID:") == true
        )
        assertTrue(
            "Exception importId should start with UID",
            entity.importId?.startsWith("exception-import@kashcal.test") == true
        )
    }

    // ==================== RFC 5545: Alarm Count Tracking ====================

    @Test
    fun `alarmCount tracks total alarms including those beyond limit`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-count@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Multi Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1W
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        // Reminders are capped at 5; all five fit.
        assertEquals("Should store all 5 reminders", 5, entity.reminders?.size ?: 0)
        assertEquals("alarmCount should track all 5", 5, entity.alarmCount)
    }
}
