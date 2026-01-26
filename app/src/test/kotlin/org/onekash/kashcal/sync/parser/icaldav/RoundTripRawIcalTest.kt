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
 * Comprehensive round-trip tests for rawIcal preservation.
 *
 * These tests verify that server-originated ICS data is correctly:
 * 1. Stored in rawIcal during PULL
 * 2. Preserved during local edits (UI operations)
 * 3. Used by IcsPatcher to preserve alarms, attendees, X-properties during PUSH
 * 4. Handled correctly in MOVE operations
 *
 * Test categories:
 * - PULL: Server → rawIcal storage
 * - UPDATE: rawIcal preservation during edits
 * - MOVE: rawIcal behavior when changing calendars
 * - EXCEPTION: rawIcal handling for recurring event exceptions
 * - EDGE CASES: Invalid rawIcal, null rawIcal, corrupted data
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoundTripRawIcalTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ==================== PULL: Server → rawIcal Storage ====================

    @Test
    fun `ICalEventMapper stores rawIcal from server response`() {
        val serverIcs = createRichServerIcs()

        // Parse server response
        val parsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()

        // Map to entity (simulating PullStrategy)
        val entity = ICalEventMapper.toEntity(
            icalEvent = parsed,
            rawIcal = serverIcs,  // This is what PullStrategy passes
            calendarId = 1L,
            caldavUrl = "https://caldav.example.com/event.ics",
            etag = "\"abc123\""
        )

        // Verify rawIcal is stored
        assertNotNull("rawIcal should be stored", entity.rawIcal)
        assertEquals("rawIcal should match server response", serverIcs, entity.rawIcal)
    }

    @Test
    fun `ICalEventMapper extracts extraProperties for X-properties`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCloud//Test//EN
            BEGIN:VEVENT
            UID:xprop-test@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:X-Property Test
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
            X-CUSTOM-PROP:custom-value
            X-KASHCAL-EMOJI:🎄
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()

        val entity = ICalEventMapper.toEntity(
            icalEvent = parsed,
            rawIcal = serverIcs,
            calendarId = 1L,
            caldavUrl = null,
            etag = null
        )

        // Verify extraProperties captured X-* properties
        assertNotNull("extraProperties should be present", entity.extraProperties)
        assertTrue(
            "Should have X-APPLE-STRUCTURED-LOCATION",
            entity.extraProperties!!.keys.any { it.contains("X-APPLE-STRUCTURED-LOCATION") }
        )
    }

    // ==================== UPDATE: rawIcal Preservation During Edits ====================

    @Test
    fun `Event copy preserves rawIcal when not explicitly set`() {
        val originalRawIcal = createRichServerIcs()
        val entity = createEventWithRawIcal(originalRawIcal)

        // Simulate UI edit - copy with only changed fields
        val edited = entity.copy(
            title = "Updated Title",
            location = "New Location",
            description = "New Description",
            updatedAt = System.currentTimeMillis()
        )

        // rawIcal should be preserved
        assertEquals(
            "rawIcal should be preserved after copy",
            originalRawIcal,
            edited.rawIcal
        )
    }

    @Test
    fun `Event copy preserves extraProperties when not explicitly set`() {
        val extraProps = mapOf(
            "X-CUSTOM-PROP" to "value1",
            "X-APPLE-TRAVEL-DURATION" to "PT30M"
        )
        val entity = createEventWithExtras(extraProps)

        // Simulate UI edit
        val edited = entity.copy(
            title = "Updated Title",
            startTs = entity.startTs + 3600000
        )

        // extraProperties should be preserved
        assertEquals(
            "extraProperties should be preserved after copy",
            extraProps,
            edited.extraProperties
        )
    }

    @Test
    fun `IcsPatcher patch uses rawIcal to preserve 5 alarms when entity has only 3`() {
        // Server ICS with 5 alarms
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:multi-alarm@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT5M
            DESCRIPTION:5 minutes
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 minutes
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 minutes
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Entity only stores first 3 reminders (per KashCal design)
        val entity = createEventWithRawIcal(serverIcs).copy(
            uid = "multi-alarm@test.com",
            title = "Updated Title",  // User changed title
            reminders = listOf("-PT5M", "-PT15M", "-PT30M")  // Only first 3
        )

        // Patch should use rawIcal and preserve ALL 5 alarms
        val patched = IcsPatcher.patch(serverIcs, entity)

        // Parse result
        val result = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Title should be updated", "Updated Title", result.summary)
        assertEquals(
            "All 5 alarms should be preserved from rawIcal",
            5,
            result.alarms.size
        )
    }

    @Test
    fun `IcsPatcher patch preserves attendees from rawIcal`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:attendee-test@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Team Meeting
            ORGANIZER;CN=Boss:mailto:boss@company.com
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@company.com
            ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE:mailto:bob@company.com
            ATTENDEE;CN=Carol;PARTSTAT=NEEDS-ACTION:mailto:carol@company.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createEventWithRawIcal(serverIcs).copy(
            uid = "attendee-test@test.com",
            title = "Team Meeting - UPDATED",  // User changed title
            location = "Room 101"  // User added location
        )

        val patched = IcsPatcher.patch(serverIcs, entity)
        val result = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Team Meeting - UPDATED", result.summary)
        assertEquals("Room 101", result.location)
        assertEquals("Should preserve 3 attendees", 3, result.attendees.size)
        assertNotNull("Should preserve organizer", result.organizer)
        assertEquals("boss@company.com", result.organizer?.email)
    }

    @Test
    fun `IcsPatcher patch preserves X-APPLE properties from rawIcal`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            UID:apple-props@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Apple Calendar Event
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS="1 Infinite Loop":geo:37.33,-122.03
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            X-APPLE-TRAVEL-DURATION:PT30M
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createEventWithRawIcal(serverIcs).copy(
            uid = "apple-props@test.com",
            title = "Apple Calendar Event - Modified"
        )

        val patched = IcsPatcher.patch(serverIcs, entity)
        val result = parser.parseAllEvents(patched).getOrNull()!!.first()

        // Verify X-APPLE properties preserved
        assertTrue(
            "Should preserve X-APPLE-STRUCTURED-LOCATION",
            result.rawProperties.keys.any { it.contains("X-APPLE-STRUCTURED-LOCATION") }
        )
        assertTrue(
            "Should preserve X-APPLE-TRAVEL properties",
            result.rawProperties.keys.any { it.contains("X-APPLE-TRAVEL") }
        )
    }

    // ==================== MOVE: rawIcal Behavior When Changing Calendars ====================

    @Test
    fun `MOVE scenario - rawIcal preserved after calendar change allows round-trip`() {
        // Simulate: Event from Server (Calendar A) → Moved to Calendar B → Pushed to Server
        val originalServerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//CalendarA//Server//EN
            BEGIN:VEVENT
            UID:move-test@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event to Move
            LOCATION:Original Location
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 Hour Reminder
            END:VALARM
            X-CALENDAR-A-PROP:calendar-a-value
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Event pulled from Calendar A with rawIcal
        val eventInCalendarA = createEventWithRawIcal(originalServerIcs).copy(
            uid = "move-test@test.com",
            calendarId = 1L,  // Calendar A
            caldavUrl = "https://server.com/calendar-a/move-test.ics",
            etag = "\"v1\"",
            reminders = listOf("-PT15M", "-PT1H")
        )

        // MOVE operation clears caldavUrl but preserves rawIcal (current behavior)
        val eventAfterMove = eventInCalendarA.copy(
            calendarId = 2L,  // Calendar B
            caldavUrl = null,  // Cleared - will get new URL
            etag = null,  // Cleared
            syncStatus = SyncStatus.PENDING_CREATE
            // rawIcal is NOT cleared in current implementation
        )

        // Verify rawIcal is preserved after move
        assertEquals(
            "rawIcal should be preserved after MOVE",
            originalServerIcs,
            eventAfterMove.rawIcal
        )

        // When pushed to Calendar B, IcsPatcher should use preserved rawIcal
        val pushIcs = IcsPatcher.serialize(eventAfterMove)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Both alarms should be preserved
        assertEquals(
            "Alarms should be preserved via rawIcal after MOVE",
            2,
            pushParsed.alarms.size
        )
    }

    @Test
    fun `MOVE scenario - old calendar X-properties preserved in rawIcal affect new calendar`() {
        // This documents potential issue: Calendar A-specific properties go to Calendar B
        val calendarASpecificIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//CalendarA//Server//EN
            BEGIN:VEVENT
            UID:cal-specific@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Calendar Specific Event
            X-CALENDAR-A-UNIQUE-ID:cal-a-12345
            X-CALENDAR-A-SYNC-TAG:sync-tag-value
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val eventInCalendarA = createEventWithRawIcal(calendarASpecificIcs).copy(
            uid = "cal-specific@test.com",
            calendarId = 1L
        )

        // After MOVE (rawIcal preserved)
        val eventAfterMove = eventInCalendarA.copy(
            calendarId = 2L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // Serialize for Calendar B
        val pushIcs = IcsPatcher.serialize(eventAfterMove)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Calendar A-specific properties are still there!
        // This documents current behavior - may or may not be desired
        assertTrue(
            "Calendar A properties preserved after MOVE (current behavior)",
            pushParsed.rawProperties.keys.any { it.contains("X-CALENDAR-A") }
        )
    }

    @Test
    fun `MOVE scenario with null rawIcal uses generateFresh`() {
        // Event created locally (no rawIcal) then moved
        val localEvent = Event(
            uid = "local-move@test.com",
            calendarId = 1L,
            title = "Local Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-PT30M"),
            rawIcal = null,  // Locally created - no server ICS
            syncStatus = SyncStatus.PENDING_CREATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // After MOVE
        val eventAfterMove = localEvent.copy(
            calendarId = 2L,
            caldavUrl = null,
            etag = null
        )

        // Serialize - should use generateFresh() since rawIcal is null
        val pushIcs = IcsPatcher.serialize(eventAfterMove)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        assertEquals("Local Event", pushParsed.summary)
        assertEquals(2, pushParsed.alarms.size)  // Both reminders preserved via generateFresh
    }

    @Test
    fun `MOVE with cleared rawIcal forces generateFresh`() {
        // Test what happens if we explicitly clear rawIcal during MOVE
        val originalServerIcs = createRichServerIcs()

        val eventInCalendarA = createEventWithRawIcal(originalServerIcs).copy(
            uid = "clear-test@test.com",
            calendarId = 1L,
            reminders = listOf("-PT15M")  // Only 1 reminder stored in entity
        )

        // MOVE with explicit rawIcal clear
        val eventAfterMove = eventInCalendarA.copy(
            calendarId = 2L,
            caldavUrl = null,
            etag = null,
            rawIcal = null,  // EXPLICITLY CLEARED
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // Serialize - should use generateFresh()
        val pushIcs = IcsPatcher.serialize(eventAfterMove)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Only entity's reminders used (not 5 from original server ICS)
        assertEquals(
            "Only entity reminders when rawIcal cleared",
            1,
            pushParsed.alarms.size
        )
    }

    // ==================== EXCEPTION: rawIcal Handling for Recurring Events ====================

    @Test
    fun `Exception event has no rawIcal - uses generateException`() {
        // Master from server
        val masterServerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:recurring-master@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly Meeting
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val master = createEventWithRawIcal(masterServerIcs).copy(
            uid = "recurring-master@test.com",
            rrule = "FREQ=WEEKLY",
            reminders = listOf("-PT15M")
        )

        // Exception created locally - NO rawIcal
        val exception = Event(
            uid = "recurring-master@test.com",  // Same UID as master
            importId = "recurring-master@test.com:RECID:1735725600000",
            calendarId = 1L,
            title = "Weekly Meeting - MODIFIED",
            startTs = 1735725600000L + 3600000,  // Moved 1 hour
            endTs = 1735725600000L + 7200000,
            originalEventId = 1L,
            originalInstanceTime = 1735725600000L,
            reminders = listOf("-PT30M"),  // Different reminder
            rawIcal = null,  // Exception has no rawIcal
            rrule = null,  // Exceptions don't have RRULE
            syncStatus = SyncStatus.SYNCED,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Serialize master with exception
        val combined = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val parsed = parser.parseAllEvents(combined).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedException = parsed.find { it.recurrenceId != null }!!

        // Master preserves its 1 alarm from rawIcal
        assertEquals(1, parsedMaster.alarms.size)

        // Exception uses its own reminders (via generateException)
        assertEquals(1, parsedException.alarms.size)
        assertEquals("Weekly Meeting - MODIFIED", parsedException.summary)
    }

    @Test
    fun `serializeWithExceptions preserves master rawIcal alarms`() {
        // Server ICS with 4 alarms
        val masterServerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:multi-alarm-master@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Standup
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT5M
            END:VALARM
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
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val master = createEventWithRawIcal(masterServerIcs).copy(
            uid = "multi-alarm-master@test.com",
            rrule = "FREQ=DAILY",
            reminders = listOf("-PT5M", "-PT15M", "-PT30M")  // Only 3 stored
        )

        val exception = createException(
            masterUid = "multi-alarm-master@test.com",
            masterId = 1L,
            originalInstanceTime = 1735207200000L,
            title = "Daily Standup - Extended",
            reminders = listOf("-PT10M")
        )

        val combined = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val parsed = parser.parseAllEvents(combined).getOrNull()!!

        val parsedMaster = parsed.find { it.recurrenceId == null }!!

        // Master should have ALL 4 alarms from rawIcal
        assertEquals(
            "Master should preserve all 4 alarms from rawIcal",
            4,
            parsedMaster.alarms.size
        )
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `patch with corrupted rawIcal falls back to generateFresh`() {
        val corruptedIcs = """
            BEGIN:VCALENDAR
            THIS IS NOT VALID ICS DATA
            MISSING:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = Event(
            uid = "fallback-test@test.com",
            calendarId = 1L,
            title = "Fallback Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M"),
            rawIcal = corruptedIcs,
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Should fall back to generateFresh
        val result = IcsPatcher.serialize(entity)
        val parsed = parser.parseAllEvents(result).getOrNull()

        assertNotNull("Should produce valid ICS via fallback", parsed)
        assertEquals("Fallback Event", parsed!!.first().summary)
    }

    @Test
    fun `patch with empty rawIcal falls back to generateFresh`() {
        val entity = Event(
            uid = "empty-raw@test.com",
            calendarId = 1L,
            title = "Empty Raw Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M"),
            rawIcal = "",  // Empty string
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val result = IcsPatcher.serialize(entity)
        val parsed = parser.parseAllEvents(result).getOrNull()

        assertNotNull("Should produce valid ICS", parsed)
        assertEquals("Empty Raw Event", parsed!!.first().summary)
    }

    @Test
    fun `patch with rawIcal containing different UID still works`() {
        // Edge case: rawIcal has different UID than entity
        // (shouldn't happen in practice, but test robustness)
        val rawIcsWithDifferentUid = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:original-uid@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = Event(
            uid = "different-uid@test.com",  // Different from rawIcal!
            calendarId = 1L,
            title = "Updated Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            rawIcal = rawIcsWithDifferentUid,
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Patch uses entity's values for everything IcsPatcher manages
        val result = IcsPatcher.patch(rawIcsWithDifferentUid, entity)
        val parsed = parser.parseAllEvents(result).getOrNull()!!.first()

        // Note: Current implementation patches onto original, so UID from original is preserved
        // This is actually correct - the parsed event in IcsPatcher.patch() has the original UID
        assertEquals("Updated Event", parsed.summary)
    }

    @Test
    fun `serialize preserves timezone in round-trip`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            DTSTART:20070311T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            TZNAME:EDT
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            DTSTART:20071104T020000
            RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
            TZNAME:EST
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:tz-test@test.com
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251225T100000
            DTEND;TZID=America/New_York:20251225T110000
            SUMMARY:Timezone Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createEventWithRawIcal(serverIcs).copy(
            uid = "tz-test@test.com",
            timezone = "America/New_York"
        )

        val result = IcsPatcher.serialize(entity)

        // Should contain VTIMEZONE
        assertTrue(
            "Should include VTIMEZONE",
            result.contains("BEGIN:VTIMEZONE") || result.contains("TZID=America/New_York")
        )
    }

    @Test
    fun `all-day event round-trip preserves DATE format`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:allday@test.com
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()

        val entity = createEventWithRawIcal(serverIcs).copy(
            uid = "allday@test.com",
            isAllDay = true,
            title = "All Day Event - Modified"
        )

        val result = IcsPatcher.serialize(entity)
        val resultParsed = parser.parseAllEvents(result).getOrNull()!!.first()

        assertTrue("Should remain all-day event", resultParsed.isAllDay)
    }

    // ==================== Full Round-Trip Simulation ====================

    @Test
    fun `full round-trip - server to local to server preserves all properties`() {
        // Step 1: Receive from server
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCloud//Calendar//EN
            BEGIN:VEVENT
            UID:full-roundtrip@icloud.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T120000Z
            SUMMARY:Important Meeting
            LOCATION:Conference Room A
            DESCRIPTION:Quarterly review meeting
            STATUS:CONFIRMED
            TRANSP:OPAQUE
            CLASS:PRIVATE
            SEQUENCE:3
            ORGANIZER;CN=Boss:mailto:boss@company.com
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@company.com
            ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE:mailto:bob@company.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week before
            END:VALARM
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
            X-CUSTOM-CATEGORY:work
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Parse as if received from server
        val serverParsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()

        // Map to entity (simulating PullStrategy)
        val entityFromServer = ICalEventMapper.toEntity(
            icalEvent = serverParsed,
            rawIcal = serverIcs,
            calendarId = 1L,
            caldavUrl = "https://caldav.icloud.com/event.ics",
            etag = "\"v3\""
        )

        // Step 2: Local edit (change title and add location detail)
        val editedEntity = entityFromServer.copy(
            title = "Important Meeting - RESCHEDULED",
            location = "Conference Room B",  // Changed
            description = "Quarterly review meeting - moved to new room"
        )

        // Step 3: Push back to server
        val pushIcs = IcsPatcher.serialize(editedEntity)

        // Step 4: Verify all properties preserved
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Updated properties
        assertEquals("Important Meeting - RESCHEDULED", pushParsed.summary)
        assertEquals("Conference Room B", pushParsed.location)
        assertTrue(pushParsed.description!!.contains("moved to new room"))

        // Preserved properties
        assertEquals("Should preserve 4 alarms", 4, pushParsed.alarms.size)
        assertEquals("Should preserve 2 attendees", 2, pushParsed.attendees.size)
        assertNotNull("Should preserve organizer", pushParsed.organizer)
        assertEquals("boss@company.com", pushParsed.organizer?.email)
        assertEquals(4, pushParsed.sequence)  // Incremented from 3

        // Preserved X-properties
        assertTrue(
            "Should preserve X-APPLE property",
            pushParsed.rawProperties.keys.any { it.contains("X-APPLE") }
        )
        assertTrue(
            "Should preserve X-CUSTOM property",
            pushParsed.rawProperties.keys.any { it.contains("X-CUSTOM") }
        )
    }

    // ==================== Helper Methods ====================

    private fun createRichServerIcs(): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Server//Test//EN
        BEGIN:VEVENT
        UID:rich-event@test.com
        DTSTAMP:20251220T100000Z
        DTSTART:20251225T100000Z
        DTEND:20251225T110000Z
        SUMMARY:Rich Event
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT5M
        END:VALARM
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
        X-CUSTOM-PROP:custom-value
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun createEventWithRawIcal(rawIcal: String): Event = Event(
        uid = "test-event@test.com",
        calendarId = 1L,
        title = "Test Event",
        startTs = 1735120800000L,
        endTs = 1735124400000L,
        rawIcal = rawIcal,
        syncStatus = SyncStatus.SYNCED,
        dtstamp = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createEventWithExtras(extras: Map<String, String>): Event = Event(
        uid = "extras-event@test.com",
        calendarId = 1L,
        title = "Event with Extras",
        startTs = 1735120800000L,
        endTs = 1735124400000L,
        extraProperties = extras,
        syncStatus = SyncStatus.SYNCED,
        dtstamp = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createException(
        masterUid: String,
        masterId: Long,
        originalInstanceTime: Long,
        title: String,
        reminders: List<String>? = null
    ): Event = Event(
        uid = masterUid,
        importId = "$masterUid:RECID:$originalInstanceTime",
        calendarId = 1L,
        title = title,
        startTs = originalInstanceTime + 3600000,
        endTs = originalInstanceTime + 7200000,
        originalEventId = masterId,
        originalInstanceTime = originalInstanceTime,
        reminders = reminders,
        rawIcal = null,
        rrule = null,
        syncStatus = SyncStatus.SYNCED,
        dtstamp = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
}
