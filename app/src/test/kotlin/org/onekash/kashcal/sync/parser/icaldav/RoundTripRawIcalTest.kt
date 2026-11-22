package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that the pull hands the server's ICS on for storage ([MappedEntity.ics]) and that
 * IcsPatcher, given that stored ICS on push, keeps alarms, attendees and X-properties. The stored
 * ICS lives beside the row ([org.onekash.kashcal.data.db.EventIcsSource], keyed by row id), so an
 * edit or a move keeps it by keeping the row id.
 *
 * Sections:
 * - PULL: server ICS returned for storage
 * - UPDATE: stored ICS kept through edits
 * - MOVE: stored ICS when changing calendars
 * - EXCEPTION: stored ICS for recurring event exceptions
 * - EDGE CASES: corrupted, empty and mismatched stored ICS, timezone, all-day
 * - Full round-trip: pull, edit, push
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoundTripRawIcalTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ==================== PULL: Server ICS returned for storage ====================

    @Test
    fun `ICalEventMapper returns the server response as the ICS to store`() {
        val serverIcs = createRichServerIcs()

        // Parse server response
        val parsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()

        // Map to entity, as PullStrategy does
        val mapped = ICalEventMapper.toEntity(
            icalEvent = parsed,
            rawIcal = serverIcs,  // what PullStrategy passes
            calendarId = 1L,
            caldavUrl = "https://caldav.example.com/event.ics",
            etag = "\"abc123\""
        )

        // The ICS to store is returned beside the row
        assertNotNull("the stored ICS should be returned", mapped.ics)
        assertEquals("the stored ICS should match server response", serverIcs, mapped.ics)
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
        ).event

        // extraProperties captured the X-* properties
        assertNotNull("extraProperties should be present", entity.extraProperties)
        assertTrue(
            "Should have X-APPLE-STRUCTURED-LOCATION",
            entity.extraProperties!!.keys.any { it.contains("X-APPLE-STRUCTURED-LOCATION") }
        )
    }

    // ==================== UPDATE: Stored ICS Preservation During Edits ====================

    @Test
    fun `Event copy keeps the row id its stored ICS is keyed by`() {
        val originalRawIcal = createRichServerIcs()
        val entity = syncedEvent().copy(id = 7L)

        // A UI edit: copy with only the changed fields
        val edited = entity.copy(
            title = "Updated Title",
            location = "New Location",
            description = "New Description",
            updatedAt = System.currentTimeMillis()
        )

        // The row id is kept, so the stored ICS stays the edited row's
        assertEquals("the row id should be kept after copy", 7L, edited.id)
        val pushed = parser.parseAllEvents(IcsPatcher.serialize(edited, originalRawIcal)).getOrNull()!!.first()
        assertTrue(
            "the stored ICS's X-CUSTOM-PROP should survive the edit",
            pushed.rawProperties.keys.any { it.contains("X-CUSTOM-PROP") }
        )
    }

    @Test
    fun `Event copy preserves extraProperties when not explicitly set`() {
        val extraProps = mapOf(
            "X-CUSTOM-PROP" to "value1",
            "X-APPLE-TRAVEL-DURATION" to "PT30M"
        )
        val entity = createEventWithExtras(extraProps)

        // A UI edit
        val edited = entity.copy(
            title = "Updated Title",
            startTs = entity.startTs + 3600000
        )

        // extraProperties are kept
        assertEquals(
            "extraProperties should be preserved after copy",
            extraProps,
            edited.extraProperties
        )
    }

    @Test
    fun `IcsPatcher patch honors deletion when entity keeps only 3 of 5 displayed alarms`() {
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

        // The user kept 3 of the 5 reminders
        val entity = syncedEvent().copy(
            uid = "multi-alarm@test.com",
            title = "Updated Title",  // User changed title
            reminders = listOf("-PT5M", "-PT15M", "-PT30M")  // Only first 3
        )

        // The patch keeps the stored ICS's other properties, but the reminder list is the source of
        // truth for the displayed window: all 5 originals were displayed (index < 5) and the
        // user kept 3, so the other 2 are deleted and not re-added.
        val patched = IcsPatcher.patch(serverIcs, entity)

        // Parse result
        val result = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Title should be updated", "Updated Title", result.summary)
        assertEquals(
            "Deleted displayed alarms dropped; only the kept 3 remain",
            3,
            result.alarms.size
        )
        val triggers = result.alarms.mapNotNull { it.trigger?.toMinutes() }
        assertFalse("deleted -PT1H dropped", triggers.contains(-60L))
        assertFalse("deleted -P1D dropped", triggers.contains(-1440L))
    }

    @Test
    fun `IcsPatcher patch preserves attendees from the stored ICS`() {
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

        val entity = syncedEvent().copy(
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
    fun `IcsPatcher patch preserves X-APPLE properties from the stored ICS`() {
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

        val entity = syncedEvent().copy(
            uid = "apple-props@test.com",
            title = "Apple Calendar Event - Modified"
        )

        val patched = IcsPatcher.patch(serverIcs, entity)
        val result = parser.parseAllEvents(patched).getOrNull()!!.first()

        // X-APPLE properties are kept
        assertTrue(
            "Should preserve X-APPLE-STRUCTURED-LOCATION",
            result.rawProperties.keys.any { it.contains("X-APPLE-STRUCTURED-LOCATION") }
        )
        assertTrue(
            "Should preserve X-APPLE-TRAVEL properties",
            result.rawProperties.keys.any { it.contains("X-APPLE-TRAVEL") }
        )
    }

    // ==================== MOVE: Stored ICS When Changing Calendars ====================

    @Test
    fun `MOVE scenario - stored ICS preserved after calendar change allows round-trip`() {
        // An event from the server in calendar A, moved to calendar B, pushed to the server
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

        // Event pulled from Calendar A, its server ICS stored for row 7
        val eventInCalendarA = syncedEvent().copy(
            id = 7L,
            uid = "move-test@test.com",
            calendarId = 1L,  // Calendar A
            caldavUrl = "https://server.com/calendar-a/move-test.ics",
            etag = "\"v1\"",
            reminders = listOf("-PT15M", "-PT1H")
        )

        // A move clears caldavUrl and etag but keeps the row id, as EventWriter's move does, so
        // the stored ICS stays with the row
        val eventAfterMove = eventInCalendarA.copy(
            calendarId = 2L,  // Calendar B
            caldavUrl = null,  // cleared; the push sets the new URL
            etag = null,  // cleared
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // The row id, and with it the stored ICS, is kept after the move
        assertEquals(
            "the row id should be preserved after MOVE",
            7L,
            eventAfterMove.id
        )

        // Pushed to calendar B, IcsPatcher patches the kept stored ICS
        val pushIcs = IcsPatcher.serialize(eventAfterMove, originalServerIcs)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Both alarms are kept
        assertEquals(
            "Alarms should be preserved via the stored ICS after MOVE",
            2,
            pushParsed.alarms.size
        )
    }

    @Test
    fun `MOVE scenario - old calendar X-properties preserved in the stored ICS affect new calendar`() {
        // Documents that calendar A's own properties go to calendar B
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

        val eventInCalendarA = syncedEvent().copy(
            uid = "cal-specific@test.com",
            calendarId = 1L
        )

        // After the move, the stored ICS kept
        val eventAfterMove = eventInCalendarA.copy(
            calendarId = 2L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // Serialize for calendar B
        val pushIcs = IcsPatcher.serialize(eventAfterMove, calendarASpecificIcs)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Calendar A's properties are still there. This documents current behavior, which may
        // or may not be desired.
        assertTrue(
            "Calendar A properties preserved after MOVE (current behavior)",
            pushParsed.rawProperties.keys.any { it.contains("X-CALENDAR-A") }
        )
    }

    @Test
    fun `MOVE scenario without a stored ICS uses generateFresh`() {
        // Event created locally (no stored ICS), then moved
        val localEvent = Event(
            uid = "local-move@test.com",
            calendarId = 1L,
            title = "Local Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-PT30M"),
            syncStatus = SyncStatus.PENDING_CREATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // After the move
        val eventAfterMove = localEvent.copy(
            calendarId = 2L,
            caldavUrl = null,
            etag = null
        )

        // Without a stored ICS, serialize uses generateFresh()
        val pushIcs = IcsPatcher.serialize(eventAfterMove, null)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        assertEquals("Local Event", pushParsed.summary)
        assertEquals(2, pushParsed.alarms.size)  // both reminders, via generateFresh
    }

    @Test
    fun `MOVE with the stored ICS cleared forces generateFresh`() {
        // A move that clears the stored ICS (createRichServerIcs' five alarms)
        val eventInCalendarA = syncedEvent().copy(
            uid = "clear-test@test.com",
            calendarId = 1L,
            reminders = listOf("-PT15M")  // only 1 reminder on the entity
        )

        // Move with the stored ICS cleared
        val eventAfterMove = eventInCalendarA.copy(
            calendarId = 2L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // Serialize falls back to generateFresh()
        val pushIcs = IcsPatcher.serialize(eventAfterMove, null)
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Only the entity's reminder, not the 5 alarms of the server ICS
        assertEquals(
            "Only entity reminders when the stored ICS is cleared",
            1,
            pushParsed.alarms.size
        )
    }

    // ==================== EXCEPTION: Stored ICS Handling for Recurring Events ====================

    @Test
    fun `Exception event has no stored ICS - uses generateException`() {
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

        val master = syncedEvent().copy(
            uid = "recurring-master@test.com",
            rrule = "FREQ=WEEKLY",
            reminders = listOf("-PT15M")
        )

        // Exception created locally, with no stored ICS of its own
        val exception = Event(
            uid = "recurring-master@test.com",  // same UID as master
            importId = "recurring-master@test.com:RECID:1735725600000",
            calendarId = 1L,
            title = "Weekly Meeting - MODIFIED",
            startTs = 1735725600000L + 3600000,  // moved 1 hour
            endTs = 1735725600000L + 7200000,
            originalEventId = 1L,
            originalInstanceTime = 1735725600000L,
            reminders = listOf("-PT30M"),  // different reminder
            rrule = null,  // exceptions have no RRULE
            syncStatus = SyncStatus.SYNCED,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Serialize master with exception
        val combined = IcsPatcher.serializeWithExceptions(master, masterServerIcs, listOf(exception))
        val parsed = parser.parseAllEvents(combined).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedException = parsed.find { it.recurrenceId != null }!!

        // Master keeps its 1 alarm from the stored ICS
        assertEquals(1, parsedMaster.alarms.size)

        // Exception uses its own reminders, rebuilt by EventToICalEventMapper.toICalEvent
        assertEquals(1, parsedException.alarms.size)
        assertEquals("Weekly Meeting - MODIFIED", parsedException.summary)
    }

    @Test
    fun `serializeWithExceptions preserves the master's stored alarms`() {
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

        val master = syncedEvent().copy(
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

        val combined = IcsPatcher.serializeWithExceptions(master, masterServerIcs, listOf(exception))
        val parsed = parser.parseAllEvents(combined).getOrNull()!!

        val parsedMaster = parsed.find { it.recurrenceId == null }!!

        // All 4 master alarms were displayed (index < 5); the user kept 3, so the 4th (-PT1H)
        // was deleted and must not be re-added.
        assertEquals(
            "Deleted displayed alarm dropped; 3 kept",
            3,
            parsedMaster.alarms.size
        )
        val triggers = parsedMaster.alarms.mapNotNull { it.trigger?.toMinutes() }
        assertFalse("deleted -PT1H dropped", triggers.contains(-60L))
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `patch with a corrupted stored ICS falls back to generateFresh`() {
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
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Falls back to generateFresh
        val result = IcsPatcher.serialize(entity, corruptedIcs)
        val parsed = parser.parseAllEvents(result).getOrNull()

        assertNotNull("Should produce valid ICS via fallback", parsed)
        assertEquals("Fallback Event", parsed!!.first().summary)
    }

    @Test
    fun `patch with an empty stored ICS falls back to generateFresh`() {
        val entity = Event(
            uid = "empty-raw@test.com",
            calendarId = 1L,
            title = "Empty Raw Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M"),
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val result = IcsPatcher.serialize(entity, "")  // Empty string
        val parsed = parser.parseAllEvents(result).getOrNull()

        assertNotNull("Should produce valid ICS", parsed)
        assertEquals("Empty Raw Event", parsed!!.first().summary)
    }

    @Test
    fun `patch with a stored ICS containing a different UID still works`() {
        // The stored ICS has a different UID from the entity; not expected in practice
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
            uid = "different-uid@test.com",  // differs from the stored ICS
            calendarId = 1L,
            title = "Updated Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // The patch writes the entity's values, UID included, for every field IcsPatcher manages
        val result = IcsPatcher.patch(rawIcsWithDifferentUid, entity)
        val parsed = parser.parseAllEvents(result).getOrNull()!!.first()

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

        val entity = syncedEvent().copy(
            uid = "tz-test@test.com",
            timezone = "America/New_York"
        )

        val result = IcsPatcher.serialize(entity, serverIcs)

        // Includes a VTIMEZONE or a TZID reference
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

        val entity = syncedEvent().copy(
            uid = "allday@test.com",
            isAllDay = true,
            title = "All Day Event - Modified"
        )

        val result = IcsPatcher.serialize(entity, serverIcs)
        val resultParsed = parser.parseAllEvents(result).getOrNull()!!.first()

        assertTrue("Should remain all-day event", resultParsed.isAllDay)
    }

    // ==================== Full Round-Trip Simulation ====================

    @Test
    fun `full round-trip - server to local to server preserves all properties`() {
        // Step 1: receive from the server
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

        // Parse as if received from the server
        val serverParsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()

        // Map to entity, as PullStrategy does
        val mappedFromServer = ICalEventMapper.toEntity(
            icalEvent = serverParsed,
            rawIcal = serverIcs,
            calendarId = 1L,
            caldavUrl = "https://caldav.icloud.com/event.ics",
            etag = "\"v3\""
        )
        val entityFromServer = mappedFromServer.event

        // Step 2: local edit of title, location and description
        val editedEntity = entityFromServer.copy(
            title = "Important Meeting - RESCHEDULED",
            location = "Conference Room B",  // changed
            description = "Quarterly review meeting - moved to new room"
        )

        // Step 3: push back to the server
        val pushIcs = IcsPatcher.serialize(editedEntity, mappedFromServer.ics)

        // Step 4: every property survives
        val pushParsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Updated properties
        assertEquals("Important Meeting - RESCHEDULED", pushParsed.summary)
        assertEquals("Conference Room B", pushParsed.location)
        assertTrue(pushParsed.description!!.contains("moved to new room"))

        // Kept properties
        assertEquals("Should preserve 4 alarms", 4, pushParsed.alarms.size)
        assertEquals("Should preserve 2 attendees", 2, pushParsed.attendees.size)
        assertNotNull("Should preserve organizer", pushParsed.organizer)
        assertEquals("boss@company.com", pushParsed.organizer?.email)
        // The patcher writes SEQUENCE verbatim. EventWriter decides the bump (SequenceBumper,
        // which bumps for this title and location change); this test calls IcsPatcher directly.
        assertEquals(3, pushParsed.sequence)  // kept; the patcher doesn't bump

        // Kept X-properties
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

    /** A synced row; a test passes its stored ICS to IcsPatcher beside it. */
    private fun syncedEvent(): Event = Event(
        uid = "test-event@test.com",
        calendarId = 1L,
        title = "Test Event",
        startTs = 1735120800000L,
        endTs = 1735124400000L,
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
        rrule = null,
        syncStatus = SyncStatus.SYNCED,
        dtstamp = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
}
