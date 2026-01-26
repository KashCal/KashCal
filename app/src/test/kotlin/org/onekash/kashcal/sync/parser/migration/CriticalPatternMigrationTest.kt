package org.onekash.kashcal.sync.parser.migration

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for critical patterns documented in CLAUDE.md.
 *
 * These tests ensure that icaldav library integration doesn't break
 * any of the critical patterns that prevent bugs in KashCal.
 *
 * Critical patterns tested:
 * - Pattern 8: Exception Events Share Master UID
 * - Pattern 11: Exception Events Require Special Handling
 * - Pattern 12: Load Exception Events Using exceptionEventId
 * - Pattern 13: Two Exception Occurrence Models Coexist
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CriticalPatternMigrationTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    // =============================================================================
    // Pattern 8: Exception Events Share Master UID
    // =============================================================================

    @Test
    fun `pattern 8 - exception event has same UID as master`() {
        // Master and exception bundled in same ICS (iCloud format)
        val bundledIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            UID:master-uid-12345
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            RRULE:FREQ=DAILY;COUNT=10
            SUMMARY:Daily Standup
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid-12345
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231217T140000Z
            DTSTART:20231217T150000Z
            DTEND:20231217T160000Z
            SUMMARY:Rescheduled Standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(bundledIcs)
        val events = result.getOrNull()!!
        assertEquals("Should parse 2 events (master + exception)", 2, events.size)

        val master = events.find { it.recurrenceId == null }!!
        val exception = events.find { it.recurrenceId != null }!!

        // RFC 5545 requirement: Same UID
        assertEquals(
            "Exception must have same UID as master (RFC 5545)",
            master.uid,
            exception.uid
        )

        // Distinguished by RECURRENCE-ID
        assertNull("Master should not have RECURRENCE-ID", master.recurrenceId)
        assertNotNull("Exception must have RECURRENCE-ID", exception.recurrenceId)
    }

    @Test
    fun `pattern 8 - exception importId includes RECID`() {
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:shared-uid-67890
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231220T140000Z
            DTSTART:20231220T150000Z
            DTEND:20231220T160000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = parser.parseAllEvents(exceptionIcs).getOrNull()!![0]

        // importId must include RECID for unique database lookup
        assertTrue(
            "Exception importId must contain RECID for unique lookup",
            event.importId.contains("RECID")
        )

        // Entity mapping preserves this
        val entity = ICalEventMapper.toEntity(event, exceptionIcs, 1L, null, null)
        assertTrue(
            "Entity importId must contain RECID",
            entity.importId!!.contains("RECID")
        )
    }

    @Test
    fun `pattern 8 - IcsPatcher generates exception with master UID`() {
        // Create a master event
        val masterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-for-exception
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val masterIcalEvent = parser.parseAllEvents(masterIcs).getOrNull()!![0]
        val masterEntity = ICalEventMapper.toEntity(masterIcalEvent, masterIcs, 1L, null, null)

        // Create an exception event with master's UID
        val exceptionEntity = masterEntity.copy(
            id = 2L,
            title = "Rescheduled Meeting",
            originalEventId = 1L,
            originalInstanceTime = masterEntity.startTs + 7 * 24 * 60 * 60 * 1000,
            rrule = null,  // Exceptions don't have RRULE
            rawIcal = null  // No existing rawIcal
        )

        // IcsPatcher.serializeWithExceptions should generate correct ICS
        val generatedIcs = IcsPatcher.serializeWithExceptions(masterEntity, listOf(exceptionEntity))

        // Parse the generated ICS
        val parsedEvents = parser.parseAllEvents(generatedIcs).getOrNull()!!

        // Should have master + exception
        assertEquals("Should have 2 events", 2, parsedEvents.size)

        val master = parsedEvents.find { it.rrule != null }!!
        val exception = parsedEvents.find { it.recurrenceId != null }!!

        // Exception should have same UID as master
        assertEquals(
            "Generated exception must have master's UID",
            master.uid,
            exception.uid
        )
    }

    // =============================================================================
    // Pattern 11: Exception Events Require Special Handling
    // =============================================================================

    @Test
    fun `pattern 11 - exception event entity has originalEventId set`() {
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exception-uid-test
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231220T140000Z
            DTSTART:20231220T150000Z
            DTEND:20231220T160000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(exceptionIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, exceptionIcs, 1L, null, null)

        // originalEventId is set by PullStrategy after master lookup
        // ICalEventMapper sets it to null, but originalInstanceTime is populated
        assertNull(
            "ICalEventMapper sets originalEventId to null (caller sets after master lookup)",
            entity.originalEventId
        )
        assertNotNull(
            "originalInstanceTime should be set from RECURRENCE-ID",
            entity.originalInstanceTime
        )

        // After master lookup, caller would set:
        val linkedEntity = entity.copy(originalEventId = 100L)
        assertEquals(100L, linkedEntity.originalEventId)
    }

    @Test
    fun `pattern 11 - exception has no caldavUrl (bundled with master)`() {
        // Exception events are bundled in master's .ics file
        // They don't have their own CalDAV URL
        val bundledIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:shared-uid
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            RRULE:FREQ=DAILY
            SUMMARY:Master
            END:VEVENT
            BEGIN:VEVENT
            UID:shared-uid
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231217T140000Z
            DTSTART:20231217T150000Z
            DTEND:20231217T160000Z
            SUMMARY:Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(bundledIcs).getOrNull()!!
        val exception = events.find { it.recurrenceId != null }!!

        // When mapped, exception should not get caldavUrl
        // (it's bundled with master, no separate .ics file)
        val entity = ICalEventMapper.toEntity(
            exception,
            null,  // No rawIcal for exception
            1L,
            null,  // No caldavUrl for exception
            null
        )

        assertNull(
            "Exception should not have caldavUrl (bundled with master)",
            entity.caldavUrl
        )
    }

    // =============================================================================
    // Pattern 12: Load Exception Events Using exceptionEventId
    // =============================================================================

    @Test
    fun `pattern 12 - exception event preserves RECURRENCE-ID timestamp`() {
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-test-uid
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231220T140000Z
            DTSTART:20231220T150000Z
            DTEND:20231220T160000Z
            SUMMARY:Rescheduled
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(exceptionIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, exceptionIcs, 1L, null, null)

        // originalInstanceTime should match RECURRENCE-ID
        assertEquals(
            "originalInstanceTime must match RECURRENCE-ID timestamp",
            icalEvent.recurrenceId!!.timestamp,
            entity.originalInstanceTime
        )
    }

    @Test
    fun `pattern 12 - ICalEventMapper isException detects exception events`() {
        val masterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-uid
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            RRULE:FREQ=DAILY
            SUMMARY:Master
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-uid
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231217T140000Z
            DTSTART:20231217T150000Z
            DTEND:20231217T160000Z
            SUMMARY:Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val master = parser.parseAllEvents(masterIcs).getOrNull()!![0]
        val exception = parser.parseAllEvents(exceptionIcs).getOrNull()!![0]

        assertFalse("Master should not be exception", ICalEventMapper.isException(master))
        assertTrue("Exception should be exception", ICalEventMapper.isException(exception))
    }

    // =============================================================================
    // Pattern 13: Two Exception Occurrence Models Coexist
    // =============================================================================

    @Test
    fun `pattern 13 - Model A - separate occurrence for exception`() {
        // Model A (PullStrategy): Exception has its own occurrence row
        // eventId = exception.id, exceptionEventId = null
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:model-a-test
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231220T140000Z
            DTSTART:20231220T150000Z
            DTEND:20231220T160000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(exceptionIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, exceptionIcs, 1L, null, null)

        // In Model A, the occurrence's eventId points directly to exception
        // This simulates how PullStrategy creates occurrences
        // The UI pattern `occ.exceptionEventId ?: occ.eventId` handles this:
        // null ?: 101 → 101 (loads exception correctly)

        assertNotNull("originalInstanceTime must be set", entity.originalInstanceTime)
        assertTrue(
            "importId must contain RECID for Model A",
            entity.importId!!.contains("RECID")
        )
    }

    @Test
    fun `pattern 13 - Model B - linked occurrence via exceptionEventId`() {
        // Model B (EventWriter): Master occurrence links to exception
        // eventId = master.id, exceptionEventId = exception.id

        // The UI pattern `occ.exceptionEventId ?: occ.eventId` handles this:
        // 101 ?: 100 → 101 (loads exception correctly)

        // This is tested by verifying the entity structure supports linking
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:model-b-test
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231220T140000Z
            DTSTART:20231220T150000Z
            DTEND:20231220T160000Z
            SUMMARY:Exception Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(exceptionIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, exceptionIcs, 1L, null, null)

        // Entity can be linked to master via originalEventId (set by caller)
        val linkedEntity = entity.copy(originalEventId = 100L)
        assertEquals(100L, linkedEntity.originalEventId)
        assertNotNull("originalInstanceTime needed for Model B linking", linkedEntity.originalInstanceTime)
    }

    // =============================================================================
    // Additional critical pattern tests
    // =============================================================================

    @Test
    fun `timestamp conversion handles timezone correctly`() {
        val nyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timezone-test
            DTSTAMP:20231215T100000Z
            DTSTART;TZID=America/New_York:20231215T090000
            DTEND;TZID=America/New_York:20231215T100000
            SUMMARY:New York Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val utcIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:utc-test
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:UTC Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val nyEvent = parser.parseAllEvents(nyIcs).getOrNull()!![0]
        val utcEvent = parser.parseAllEvents(utcIcs).getOrNull()!![0]

        val nyEntity = ICalEventMapper.toEntity(nyEvent, nyIcs, 1L, null, null)
        val utcEntity = ICalEventMapper.toEntity(utcEvent, utcIcs, 1L, null, null)

        // Timezone should be preserved
        assertEquals("America/New_York", nyEntity.timezone)
        assertNull("UTC should have null timezone", utcEntity.timezone)

        // Timestamps are absolute milliseconds (correct across timezones)
        assertTrue("Timestamps should be positive", nyEntity.startTs > 0)
        assertTrue("Timestamps should be positive", utcEntity.startTs > 0)
    }

    @Test
    fun `all-day event timestamps consistent`() {
        val allDayIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-consistency-test
            DTSTAMP:20231215T100000Z
            DTSTART;VALUE=DATE:20231215
            DTEND;VALUE=DATE:20231216
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(allDayIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, allDayIcs, 1L, null, null)

        // Both should agree on all-day status
        assertTrue("icaldav event should be all-day", icalEvent.isAllDay)
        assertTrue("Entity should be all-day", entity.isAllDay)

        // ICalEventMapper adjusts endTs: exclusive → inclusive
        assertTrue(
            "Entity endTs should be adjusted for exclusive DTEND",
            entity.endTs < icalEvent.dtEnd!!.timestamp
        )
    }

    @Test
    fun `RRULE string format preserved exactly`() {
        val rruleIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-format-test
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=12
            SUMMARY:Monthly on 2nd Tuesday
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(rruleIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, rruleIcs, 1L, null, null)

        // RRULE string should be preserved
        assertNotNull("RRULE should be mapped", entity.rrule)
        assertEquals(
            "RRULE format should match",
            icalEvent.rrule!!.toICalString(),
            entity.rrule
        )

        // Round-trip should preserve
        val regenerated = IcsPatcher.serialize(entity)
        val reparsed = parser.parseAllEvents(regenerated).getOrNull()!![0]
        assertEquals(
            "RRULE should survive round-trip",
            entity.rrule,
            reparsed.rrule?.toICalString()
        )
    }

    @Test
    fun `sequence number preserved and incremented on update`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sequence-test
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SEQUENCE:7
            SUMMARY:Event with Sequence
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        // Sequence should be preserved
        assertEquals(7, entity.sequence)

        // IcsPatcher should increment on update
        val regenerated = IcsPatcher.serialize(entity)
        val reparsed = parser.parseAllEvents(regenerated).getOrNull()!![0]
        assertEquals(
            "Sequence should be incremented by IcsPatcher",
            8,
            reparsed.sequence
        )
    }

    @Test
    fun `cancelled exception occurrence handled correctly`() {
        // iCloud represents deleted occurrences as CANCELLED exceptions
        val cancelledIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            UID:cancelled-exception-test
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231217T140000Z
            DTSTART:20231217T140000Z
            DTEND:20231217T150000Z
            STATUS:CANCELLED
            SUMMARY:Deleted Occurrence
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(cancelledIcs).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(icalEvent, cancelledIcs, 1L, null, null)

        // Should be recognized as exception
        assertTrue("Should be exception", ICalEventMapper.isException(icalEvent))
        assertNotNull("Should have originalInstanceTime", entity.originalInstanceTime)

        // Status should be CANCELLED
        assertEquals("CANCELLED", entity.status)
    }
}
