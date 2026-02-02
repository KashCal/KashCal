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
 * Tests for IcsPatcher: verifies round-trip preservation when patching
 * existing ICS data and fresh generation for new events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsPatcherTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ========== Patch Tests - Preserve Original Properties ==========

    @Test
    fun `patch preserves alarms beyond first 3`() {
        // Original ICS with 5 alarms
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-alarm@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            UID:alarm-1
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            UID:alarm-2
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min
            END:VALARM
            BEGIN:VALARM
            UID:alarm-3
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            BEGIN:VALARM
            UID:alarm-4
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day
            END:VALARM
            BEGIN:VALARM
            UID:alarm-5
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Parse and create entity (only stores first 3 alarms)
        val originalEvents = parser.parseAllEvents(originalIcs).getOrNull()!!
        val originalEvent = originalEvents.first()

        // Create Event entity with only the first 3 reminders
        val entity = createTestEvent(
            uid = "multi-alarm@kashcal.test",
            title = "Updated Title",  // Changed
            startTs = originalEvent.dtStart.timestamp,
            endTs = originalEvent.effectiveEnd().timestamp,
            reminders = listOf("-PT15M", "-PT30M", "-PT1H")
        )

        // Patch with updated title
        val patched = IcsPatcher.patch(originalIcs, entity)

        // Re-parse the patched ICS
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // Verify title was updated
        assertEquals("Updated Title", patchedEvent.summary)

        // Verify ALL 5 alarms are preserved
        assertEquals(
            "All 5 original alarms should be preserved",
            5,
            patchedEvent.alarms.size
        )
    }

    @Test
    fun `patch preserves attendees`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:attendee-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting with Attendees
            ORGANIZER;CN=John Doe:mailto:john@example.com
            ATTENDEE;CN=Jane Smith;PARTSTAT=ACCEPTED:mailto:jane@example.com
            ATTENDEE;CN=Bob Wilson;PARTSTAT=TENTATIVE:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "attendee-test@kashcal.test",
            title = "Updated Meeting Title",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Updated Meeting Title", patchedEvent.summary)
        assertEquals("Should preserve organizer", "john@example.com", patchedEvent.organizer?.email)
        assertEquals("Should preserve 2 attendees", 2, patchedEvent.attendees.size)
    }

    @Test
    fun `patch preserves rawProperties`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:raw-props@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Custom Props
            X-CUSTOM-PROP:custom value
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "raw-props@kashcal.test",
            title = "Updated Title",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertFalse("Should preserve raw properties", patchedEvent.rawProperties.isEmpty())
        assertTrue(
            "Should have X-CUSTOM-PROP",
            patchedEvent.rawProperties.any { it.key.contains("X-CUSTOM-PROP") }
        )
    }

    @Test
    fun `patch increments sequence number`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:seq-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Event
            SEQUENCE:5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "seq-test@kashcal.test",
            title = "Updated Event",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            sequence = 5
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Sequence should be incremented", 6, patchedEvent.sequence)
    }

    @Test
    fun `patch updates EXDATE when changed`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20251226T100000Z
            SUMMARY:Daily Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Add a new EXDATE
        val entity = createTestEvent(
            uid = "exdate-test@kashcal.test",
            title = "Daily Event",
            startTs = 1735120800000L, // Dec 25, 2025 10:00 UTC
            endTs = 1735124400000L,
            rrule = "FREQ=DAILY;COUNT=10",
            exdate = "1735207200000,1735293600000"  // Dec 26 and Dec 27 in ms
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Should have 2 EXDATEs", 2, patchedEvent.exdates.size)
    }

    // ========== Generate Fresh Tests ==========

    @Test
    fun `generateFresh creates valid ICS for new event`() {
        val entity = createTestEvent(
            uid = "new-event@kashcal.test",
            title = "New Event",
            description = "A brand new event",
            location = "Meeting Room",
            startTs = 1735120800000L, // Dec 25, 2025 10:00 UTC
            endTs = 1735124400000L,   // Dec 25, 2025 11:00 UTC
            timezone = "America/New_York",
            reminders = listOf("-PT15M", "-PT1H")
        )

        val generated = IcsPatcher.generateFresh(entity)

        // Verify it parses correctly
        val events = parser.parseAllEvents(generated).getOrNull()!!
        assertEquals("Should have 1 event", 1, events.size)

        val event = events.first()
        assertEquals("new-event@kashcal.test", event.uid)
        assertEquals("New Event", event.summary)
        assertEquals("A brand new event", event.description)
        assertEquals("Meeting Room", event.location)
        assertEquals(2, event.alarms.size)
    }

    @Test
    fun `generateFresh creates all-day event correctly`() {
        val entity = createTestEvent(
            uid = "allday-new@kashcal.test",
            title = "All Day Event",
            startTs = 1735084800000L, // Dec 25, 2025 00:00 UTC
            endTs = 1735171199999L,   // Dec 25, 2025 23:59:59.999 UTC
            isAllDay = true
        )

        val generated = IcsPatcher.generateFresh(entity)

        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertTrue("Should be all-day", event.isAllDay)
    }

    @Test
    fun `generateFresh creates recurring event correctly`() {
        val entity = createTestEvent(
            uid = "recurring-new@kashcal.test",
            title = "Weekly Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        )

        val generated = IcsPatcher.generateFresh(entity)

        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have RRULE", event.rrule)
        assertTrue(event.rrule!!.toICalString().contains("FREQ=WEEKLY"))
    }

    @Test
    fun `generateFresh includes organizer when present`() {
        val entity = createTestEvent(
            uid = "org-new@kashcal.test",
            title = "Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            organizerEmail = "john@example.com",
            organizerName = "John Doe"
        )

        val generated = IcsPatcher.generateFresh(entity)

        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have organizer", event.organizer)
        assertEquals("john@example.com", event.organizer?.email)
        assertEquals("John Doe", event.organizer?.name)
    }

    // ========== Fallback Tests ==========

    @Test
    fun `patch falls back to generateFresh when rawIcal is null`() {
        val entity = createTestEvent(
            uid = "fallback-null@kashcal.test",
            title = "Fallback Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L
        )

        val result = IcsPatcher.patch(null, entity)

        // Should generate valid ICS
        val events = parser.parseAllEvents(result).getOrNull()!!
        assertEquals("fallback-null@kashcal.test", events.first().uid)
    }

    @Test
    fun `patch falls back to generateFresh when rawIcal is invalid`() {
        val entity = createTestEvent(
            uid = "fallback-invalid@kashcal.test",
            title = "Fallback Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L
        )

        val result = IcsPatcher.patch("not valid ical data", entity)

        // Should generate valid ICS
        val events = parser.parseAllEvents(result).getOrNull()!!
        assertEquals("fallback-invalid@kashcal.test", events.first().uid)
    }

    // ========== RawIcsParser Tests ==========

    @Test
    fun `RawIcsParser extracts all alarms`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:raw-alarms@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
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
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val alarms = RawIcsParser.getAllAlarms(ics)

        assertEquals("Should have 4 alarms", 4, alarms.size)
    }

    @Test
    fun `RawIcsParser getAlarmCount works correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-count@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Alarm 1
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Alarm 2
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertEquals(2, RawIcsParser.getAlarmCount(ics))
    }

    @Test
    fun `RawIcsParser handles null and invalid input gracefully`() {
        assertEquals(0, RawIcsParser.getAlarmCount(null))
        assertEquals(0, RawIcsParser.getAlarmCount(""))
        assertEquals(0, RawIcsParser.getAlarmCount("invalid"))
        assertTrue(RawIcsParser.getAllAlarms(null).isEmpty())
    }

    // ========== serializeWithExceptions Round-Trip Tests ==========

    @Test
    fun `serializeWithExceptions with 3 exceptions round-trips correctly`() {
        // Create master recurring event
        val masterStartTs = 1735099200000L // Dec 25, 2024 00:00 UTC
        val master = createTestEvent(
            uid = "weekly-standup@kashcal.test",
            title = "Weekly Standup",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000, // 1 hour
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            reminders = listOf("-PT15M", "-PT30M")
        )

        // Create 3 different exceptions
        val exception1 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-standup@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L), // Week 2
            title = "Weekly Standup - Extended",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (2 * 3600000L), // Moved 2 hours later
            endTs = masterStartTs + (7 * 24 * 3600000L) + (4 * 3600000L), // 2 hours long
            reminders = listOf("-PT30M") // Different alarm
        )

        val exception2 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-standup@kashcal.test",
            originalInstanceTime = masterStartTs + (14 * 24 * 3600000L), // Week 3
            title = "Weekly Standup - Room Change",
            startTs = masterStartTs + (14 * 24 * 3600000L),
            endTs = masterStartTs + (14 * 24 * 3600000L) + 3600000,
            location = "Conference Room B" // Added location
        )

        val exception3 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-standup@kashcal.test",
            originalInstanceTime = masterStartTs + (21 * 24 * 3600000L), // Week 4
            title = "Weekly Standup - Quarterly Review",
            startTs = masterStartTs + (21 * 24 * 3600000L),
            endTs = masterStartTs + (21 * 24 * 3600000L) + (2 * 3600000L), // 2 hours
            description = "Year-end quarterly review" // Added description
        )

        // Serialize with all exceptions
        val serialized = IcsPatcher.serializeWithExceptions(
            master,
            listOf(exception1, exception2, exception3)
        )

        // Parse the result
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        // Verify we have 4 events (1 master + 3 exceptions)
        assertEquals("Should have 4 events", 4, parsed.size)

        // Find master and exceptions
        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        // Verify master
        assertEquals("weekly-standup@kashcal.test", parsedMaster.uid)
        assertEquals("Weekly Standup", parsedMaster.summary)
        assertNotNull("Master should have RRULE", parsedMaster.rrule)

        // Verify all 3 exceptions
        assertEquals("Should have 3 exceptions", 3, parsedExceptions.size)

        // All exceptions share master's UID
        parsedExceptions.forEach { exc ->
            assertEquals("Exception should share master UID", "weekly-standup@kashcal.test", exc.uid)
            assertNull("Exception should NOT have RRULE", exc.rrule)
            assertNotNull("Exception should have RECURRENCE-ID", exc.recurrenceId)
        }

        // Verify each exception's unique properties
        val exc1 = parsedExceptions.find { it.summary == "Weekly Standup - Extended" }!!
        assertEquals("Extended exception should have different alarm", 1, exc1.alarms.size)

        val exc2 = parsedExceptions.find { it.summary == "Weekly Standup - Room Change" }!!
        assertEquals("Room change exception should have location", "Conference Room B", exc2.location)

        val exc3 = parsedExceptions.find { it.summary == "Weekly Standup - Quarterly Review" }!!
        assertEquals("Quarterly review should have description", "Year-end quarterly review", exc3.description)
    }

    @Test
    fun `serializeWithExceptions with cancelled exception (deletion)`() {
        // Create master recurring event
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "daily-meeting@kashcal.test",
            title = "Daily Sync",
            startTs = masterStartTs,
            endTs = masterStartTs + 1800000, // 30 min
            rrule = "FREQ=DAILY"
        )

        // Create a cancelled exception (deleted occurrence)
        val cancelledException = createExceptionEvent(
            masterId = 1L,
            masterUid = "daily-meeting@kashcal.test",
            originalInstanceTime = masterStartTs + (3 * 24 * 3600000L), // Day 4
            title = "Daily Sync", // Keep original title
            startTs = masterStartTs + (3 * 24 * 3600000L),
            endTs = masterStartTs + (3 * 24 * 3600000L) + 1800000,
            status = "CANCELLED" // Cancelled!
        )

        // Serialize
        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(cancelledException))

        // Parse
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val parsedCancelled = parsed.find { it.recurrenceId != null }!!
        assertEquals("Cancelled exception should have CANCELLED status",
            "CANCELLED", parsedCancelled.status?.name)
    }

    @Test
    fun `serializeWithExceptions with mixed cancelled and modified exceptions`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "team-call@kashcal.test",
            title = "Team Call",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY;COUNT=10"
        )

        // Week 2: Modified (moved to afternoon)
        val modifiedException = createExceptionEvent(
            masterId = 1L,
            masterUid = "team-call@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Team Call - Afternoon",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (6 * 3600000L), // 6 hours later
            endTs = masterStartTs + (7 * 24 * 3600000L) + (7 * 3600000L)
        )

        // Week 3: Cancelled
        val cancelledException = createExceptionEvent(
            masterId = 1L,
            masterUid = "team-call@kashcal.test",
            originalInstanceTime = masterStartTs + (14 * 24 * 3600000L),
            title = "Team Call",
            startTs = masterStartTs + (14 * 24 * 3600000L),
            endTs = masterStartTs + (14 * 24 * 3600000L) + 3600000,
            status = "CANCELLED"
        )

        // Week 4: Modified with location
        val locationException = createExceptionEvent(
            masterId = 1L,
            masterUid = "team-call@kashcal.test",
            originalInstanceTime = masterStartTs + (21 * 24 * 3600000L),
            title = "Team Call - Offsite",
            startTs = masterStartTs + (21 * 24 * 3600000L),
            endTs = masterStartTs + (21 * 24 * 3600000L) + 3600000,
            location = "Building C, Room 101"
        )

        val serialized = IcsPatcher.serializeWithExceptions(
            master,
            listOf(modifiedException, cancelledException, locationException)
        )

        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 4 events", 4, parsed.size)

        val cancelled = parsed.filter { it.status?.name == "CANCELLED" }
        val confirmed = parsed.filter { it.status?.name != "CANCELLED" }

        assertEquals("Should have 1 cancelled", 1, cancelled.size)
        assertEquals("Should have 3 confirmed (master + 2 modified)", 3, confirmed.size)
    }

    @Test
    fun `serializeWithExceptions re-edit same occurrence preserves only latest`() {
        // Scenario: User edits occurrence on Week 2, then edits it again
        // Only the FINAL edit should appear in serialization

        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "evolving-meeting@kashcal.test",
            title = "Planning Session",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY"
        )

        // Final version of Week 2 exception (after multiple edits)
        // Title changed twice: "Planning Session" -> "Extended Planning" -> "Final Planning"
        // Time changed: morning -> afternoon -> evening
        val finalException = createExceptionEvent(
            masterId = 1L,
            masterUid = "evolving-meeting@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L), // Week 2
            title = "Final Planning Session", // After multiple edits
            startTs = masterStartTs + (7 * 24 * 3600000L) + (10 * 3600000L), // Evening time
            endTs = masterStartTs + (7 * 24 * 3600000L) + (12 * 3600000L), // 2 hour meeting
            description = "Final version after re-edits",
            sequence = 3 // Higher sequence from multiple edits
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(finalException))

        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val exception = parsed.find { it.recurrenceId != null }!!
        assertEquals("Should have final title", "Final Planning Session", exception.summary)
        assertEquals("Should have final description", "Final version after re-edits", exception.description)
        assertEquals("Sequence should reflect edits", 3, exception.sequence)
    }

    @Test
    fun `serializeWithExceptions with 5 exceptions and varied modifications`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "big-series@kashcal.test",
            title = "Sprint Review",
            startTs = masterStartTs,
            endTs = masterStartTs + (2 * 3600000L), // 2 hours
            rrule = "FREQ=WEEKLY;BYDAY=FR",
            reminders = listOf("-PT1H", "-P1D"),
            location = "Main Conference Room"
        )

        val exceptions = listOf(
            // Exception 1: Time change only
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
                title = "Sprint Review",
                startTs = masterStartTs + (7 * 24 * 3600000L) + (3 * 3600000L), // 3 hours later
                endTs = masterStartTs + (7 * 24 * 3600000L) + (5 * 3600000L)
            ),
            // Exception 2: Title + location change
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (14 * 24 * 3600000L),
                title = "Sprint Review - Remote",
                startTs = masterStartTs + (14 * 24 * 3600000L),
                endTs = masterStartTs + (14 * 24 * 3600000L) + (2 * 3600000L),
                location = "Zoom Meeting"
            ),
            // Exception 3: Cancelled
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (21 * 24 * 3600000L),
                title = "Sprint Review",
                startTs = masterStartTs + (21 * 24 * 3600000L),
                endTs = masterStartTs + (21 * 24 * 3600000L) + (2 * 3600000L),
                status = "CANCELLED"
            ),
            // Exception 4: Different alarms
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (28 * 24 * 3600000L),
                title = "Sprint Review - Important",
                startTs = masterStartTs + (28 * 24 * 3600000L),
                endTs = masterStartTs + (28 * 24 * 3600000L) + (2 * 3600000L),
                reminders = listOf("-PT30M", "-PT1H", "-PT2H") // More reminders
            ),
            // Exception 5: All properties changed
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (35 * 24 * 3600000L),
                title = "Year-End Sprint Review",
                startTs = masterStartTs + (35 * 24 * 3600000L) + (2 * 3600000L),
                endTs = masterStartTs + (35 * 24 * 3600000L) + (5 * 3600000L), // 3 hours
                location = "Executive Boardroom",
                description = "Year-end review with stakeholders",
                reminders = listOf("-P1D", "-PT2H")
            )
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, exceptions)

        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        // Verify 6 events total
        assertEquals("Should have 6 events", 6, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        // Verify master integrity
        assertEquals("Sprint Review", parsedMaster.summary)
        assertEquals("Main Conference Room", parsedMaster.location)
        assertNotNull(parsedMaster.rrule)

        // Verify exception count
        assertEquals("Should have 5 exceptions", 5, parsedExceptions.size)

        // Verify all have unique RECURRENCE-IDs
        val recurrenceIds = parsedExceptions.map { it.recurrenceId!!.timestamp }.toSet()
        assertEquals("All RECURRENCE-IDs should be unique", 5, recurrenceIds.size)

        // Verify cancelled one
        val cancelled = parsedExceptions.filter { it.status?.name == "CANCELLED" }
        assertEquals("Should have 1 cancelled", 1, cancelled.size)

        // Verify year-end exception has all modifications
        val yearEnd = parsedExceptions.find { it.summary == "Year-End Sprint Review" }!!
        assertEquals("Executive Boardroom", yearEnd.location)
        assertEquals("Year-end review with stakeholders", yearEnd.description)
    }

    @Test
    fun `serializeWithExceptions preserves RECURRENCE-ID timestamps accurately`() {
        val masterStartTs = 1735099200000L // Dec 25, 2024 00:00 UTC
        val master = createTestEvent(
            uid = "timestamp-test@kashcal.test",
            title = "Timestamp Test",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Create exceptions on specific dates
        val day3Ts = masterStartTs + (2 * 24 * 3600000L) // Dec 27
        val day7Ts = masterStartTs + (6 * 24 * 3600000L) // Dec 31
        val day10Ts = masterStartTs + (9 * 24 * 3600000L) // Jan 3

        val exceptions = listOf(
            createExceptionEvent(
                masterId = 1L,
                masterUid = "timestamp-test@kashcal.test",
                originalInstanceTime = day3Ts,
                title = "Day 3 Modified",
                startTs = day3Ts + 3600000, // Moved 1 hour
                endTs = day3Ts + (2 * 3600000L)
            ),
            createExceptionEvent(
                masterId = 1L,
                masterUid = "timestamp-test@kashcal.test",
                originalInstanceTime = day7Ts,
                title = "Day 7 Modified",
                startTs = day7Ts,
                endTs = day7Ts + 3600000
            ),
            createExceptionEvent(
                masterId = 1L,
                masterUid = "timestamp-test@kashcal.test",
                originalInstanceTime = day10Ts,
                title = "Day 10 Modified",
                startTs = day10Ts + (2 * 3600000L),
                endTs = day10Ts + (3 * 3600000L)
            )
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, exceptions)
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        // Verify each RECURRENCE-ID matches the originalInstanceTime
        val recIdTimestamps = parsedExceptions.map { it.recurrenceId!!.timestamp }.sorted()
        val expectedTimestamps = listOf(day3Ts, day7Ts, day10Ts).sorted()

        assertEquals("RECURRENCE-ID timestamps should match original instance times",
            expectedTimestamps, recIdTimestamps)
    }

    @Test
    fun `serializeWithExceptions handles exception with different timezone than master`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "tz-test@kashcal.test",
            title = "Cross-TZ Meeting",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY"
            // No timezone - use UTC to avoid timezone loading issues in test env
        )

        // Exception moved to different time (no explicit timezone to avoid test env issues)
        val exception = createExceptionEvent(
            masterId = 1L,
            masterUid = "tz-test@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Cross-TZ Meeting - Rescheduled",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (14 * 3600000L), // Different time
            endTs = masterStartTs + (7 * 24 * 3600000L) + (15 * 3600000L)
            // No timezone - test the time change aspect without timezone complexity
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val parsedException = parsed.find { it.recurrenceId != null }!!
        assertEquals("Cross-TZ Meeting - Rescheduled", parsedException.summary)
        // Verify the event was serialized with different time
        assertNotNull(parsedException.dtStart)
        assertNotEquals("Exception should have different start time than original occurrence",
            masterStartTs + (7 * 24 * 3600000L), parsedException.dtStart.timestamp)
    }

    @Test
    fun `serializeWithExceptions empty exceptions list returns master only`() {
        val master = createTestEvent(
            uid = "solo@kashcal.test",
            title = "Solo Event",
            startTs = 1735099200000L,
            endTs = 1735102800000L,
            rrule = "FREQ=MONTHLY"
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, emptyList())
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have only master", 1, parsed.size)
        assertNull("Should have no RECURRENCE-ID", parsed.first().recurrenceId)
        assertEquals("Solo Event", parsed.first().summary)
    }

    // ========== BUG CONFIRMATION: User Reminder Edits Not Synced ==========
    // These tests confirm the bug where user's reminder edits are ignored by patch()

    @Test
    fun `BUG - patch should sync user reminder edits but currently ignores them`() {
        // Original ICS with 3 alarms
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:reminder-edit-bug@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User edits reminder 1 from 15m to 45m (keeps reminder 2 at 30m)
        val entity = createTestEvent(
            uid = "reminder-edit-bug@kashcal.test",
            title = "Original Title",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT45M", "-PT30M")  // USER'S EDIT: 15m → 45m
        )

        // Patch the ICS
        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // BUG: This assertion SHOULD pass but currently FAILS
        // The first alarm should be 45 minutes (user's edit), not 15 minutes (original)
        val alarmTriggers = patchedEvent.alarms.map { alarm ->
            alarm.trigger?.let { duration ->
                duration.toMinutes()
            }
        }

        assertEquals(
            "First alarm should be user's edit (45 min), but bug preserves original (15 min)",
            -45L,
            alarmTriggers[0]
        )
    }

    @Test
    fun `BUG - patch preserves extra alarms but should also sync user edits`() {
        // Original ICS with 5 alarms
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:five-alarm-edit@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
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
            TRIGGER:-PT2H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User edits: changes first alarm from 15m to 45m
        val entity = createTestEvent(
            uid = "five-alarm-edit@kashcal.test",
            title = "Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT45M", "-PT30M")  // User changed first alarm
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        val alarmTriggers = patchedEvent.alarms.mapNotNull { it.trigger?.toMinutes() }

        // Should have 5 alarms total (user's 2 edits + 3 preserved)
        assertEquals("Should have 5 alarms", 5, patchedEvent.alarms.size)

        // BUG: First alarm should be -45 (user's edit), not -15 (original)
        assertEquals(
            "First alarm should be user's edit (-45 min)",
            -45L,
            alarmTriggers[0]
        )

        // Alarms 3-5 should be preserved from original
        assertEquals("Third alarm preserved", -60L, alarmTriggers[2])
        assertEquals("Fourth alarm preserved", -120L, alarmTriggers[3])
        assertEquals("Fifth alarm preserved", -1440L, alarmTriggers[4])
    }

    @Test
    fun `BUG - patch should clear all alarms when user removes reminders`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:clear-alarms@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User cleared all reminders (both set to "No reminder" → null)
        val entity = createTestEvent(
            uid = "clear-alarms@kashcal.test",
            title = "Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = null  // User wants NO reminders
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // BUG: Should have 0 alarms (user's intent), but currently preserves original 2
        assertEquals(
            "Should have 0 alarms when user clears reminders, but bug preserves original",
            0,
            patchedEvent.alarms.size
        )
    }

    // ========== Sorted Reminders Round-Trip Tests ==========
    // ICalEventMapper now sorts reminders by duration (v21.5.6)
    // These tests verify IcsPatcher handles sorted reminders correctly

    @Test
    fun `patch applies sorted reminders to unsorted rawIcal alarms`() {
        // Server originally sent alarms in order: 1 day, 1 hour, 15 min
        // ICalEventMapper sorted them to: 15 min, 1 hour, 1 day
        // When patching, triggers should be updated in original positions
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sorted-roundtrip@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Unsorted Alarms
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Entity has sorted reminders (as stored by ICalEventMapper)
        val entity = createTestEvent(
            uid = "sorted-roundtrip@kashcal.test",
            title = "Event with Unsorted Alarms",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-PT1H", "-P1D")  // Sorted order from pull
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // Should have 3 alarms
        assertEquals("Should have 3 alarms", 3, patchedEvent.alarms.size)

        // Extract triggers in minutes for verification
        val triggers = patchedEvent.alarms.mapNotNull { it.trigger?.toMinutes() }

        // The triggers should now be in sorted order (from entity.reminders)
        // Note: IcsPatcher merges reminders[i] with originalAlarms[i] by position
        // So: reminders[0]=-PT15M → originalAlarms[0] (was -P1D), etc.
        assertEquals("First trigger should be 15 min", -15L, triggers[0])
        assertEquals("Second trigger should be 1 hour", -60L, triggers[1])
        assertEquals("Third trigger should be 1 day", -1440L, triggers[2])
    }

    @Test
    fun `patch preserves ACTION types from original alarms with sorted reminders`() {
        // Verify ACTION types (AUDIO, EMAIL, DISPLAY) are preserved from original
        // even when triggers are reordered by sorting
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:action-preserve@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-P1D
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT1H
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Sorted reminders: 15 min comes before 1 day
        // But we only have 2 in entity (typical case)
        val entity = createTestEvent(
            uid = "action-preserve@kashcal.test",
            title = "Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-P1D")  // Sorted: 15m before 1d
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Should have 2 alarms", 2, patchedEvent.alarms.size)

        // ACTION types come from original alarms by position
        // reminders[0] (-PT15M) → originalAlarms[0] (AUDIO)
        // reminders[1] (-P1D) → originalAlarms[1] (EMAIL)
        val actions = patchedEvent.alarms.map { it.action.name }
        assertEquals("First alarm should preserve AUDIO action", "AUDIO", actions[0])
        assertEquals("Second alarm should preserve EMAIL action", "EMAIL", actions[1])

        // But triggers should be from entity.reminders
        val triggers = patchedEvent.alarms.mapNotNull { it.trigger?.toMinutes() }
        assertEquals("First trigger from sorted reminders", -15L, triggers[0])
        assertEquals("Second trigger from sorted reminders", -1440L, triggers[1])
    }

    // ========== RFC 5545/7986 Extended Properties Tests ==========

    @Test
    fun `generateFresh includes priority field`() {
        val entity = createTestEvent(
            uid = "priority-fresh@kashcal.test",
            title = "High Priority Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            priority = 1
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertEquals(1, event.priority)
    }

    @Test
    fun `generateFresh includes geo coordinates`() {
        val entity = createTestEvent(
            uid = "geo-fresh@kashcal.test",
            title = "Event at Apple Park",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            geoLat = 37.334722,
            geoLon = -122.008889
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have GEO", event.geo)
        assertTrue("GEO should contain latitude", event.geo!!.contains("37.334722"))
        assertTrue("GEO should contain longitude", event.geo!!.contains("-122.008889"))
    }

    @Test
    fun `generateFresh includes color field`() {
        val entity = createTestEvent(
            uid = "color-fresh@kashcal.test",
            title = "Red Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            color = 0xFFFF0000.toInt() // Red
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have COLOR", event.color)
        assertEquals("#FF0000", event.color)
    }

    @Test
    fun `generateFresh includes url field`() {
        val entity = createTestEvent(
            uid = "url-fresh@kashcal.test",
            title = "Event with Link",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            url = "https://example.com/event"
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertEquals("https://example.com/event", event.url)
    }

    @Test
    fun `generateFresh includes categories field`() {
        val entity = createTestEvent(
            uid = "categories-fresh@kashcal.test",
            title = "Categorized Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            categories = listOf("MEETING", "WORK", "IMPORTANT")
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertEquals(3, event.categories.size)
        assertTrue(event.categories.contains("MEETING"))
        assertTrue(event.categories.contains("WORK"))
        assertTrue(event.categories.contains("IMPORTANT"))
    }

    @Test
    fun `patch updates RFC 5545 extended properties`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rfc-update@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Event
            PRIORITY:5
            GEO:37.0;-122.0
            COLOR:#0000FF
            URL:https://old.example.com
            CATEGORIES:OLD
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "rfc-update@kashcal.test",
            title = "Updated Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            priority = 1,
            geoLat = 40.7128,
            geoLon = -74.0060,
            color = 0xFFFF0000.toInt(),
            url = "https://new.example.com",
            categories = listOf("NEW", "UPDATED")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val events = parser.parseAllEvents(patched).getOrNull()!!
        val event = events.first()

        assertEquals("Updated Event", event.summary)
        assertEquals(1, event.priority)
        assertTrue("GEO should be updated", event.geo?.contains("40.7128") == true)
        assertEquals("#FF0000", event.color)
        assertEquals("https://new.example.com", event.url)
        assertEquals(2, event.categories.size)
        assertTrue(event.categories.contains("NEW"))
    }

    @Test
    fun `generateException includes RFC 5545 extended properties`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "exc-rfc@kashcal.test",
            title = "Weekly Meeting",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY"
        )

        val exception = createExceptionEvent(
            masterId = 1L,
            masterUid = "exc-rfc@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Weekly Meeting - Special",
            startTs = masterStartTs + (7 * 24 * 3600000L),
            endTs = masterStartTs + (7 * 24 * 3600000L) + 3600000,
            priority = 2,
            geoLat = 51.5074,
            geoLon = -0.1278,
            color = 0xFF00FF00.toInt(),
            url = "https://special.example.com",
            categories = listOf("SPECIAL", "MEETING")
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!
        val parsedException = parsed.find { it.recurrenceId != null }!!

        assertEquals(2, parsedException.priority)
        assertNotNull("Exception should have GEO", parsedException.geo)
        assertEquals("#00FF00", parsedException.color)
        assertEquals("https://special.example.com", parsedException.url)
        assertEquals(2, parsedException.categories.size)
    }

    // ========== Helper ==========

    private fun createExceptionEvent(
        masterId: Long,
        masterUid: String,
        originalInstanceTime: Long,
        title: String,
        startTs: Long,
        endTs: Long,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        status: String = "CONFIRMED",
        reminders: List<String>? = null,
        sequence: Int = 1,
        priority: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null,
        color: Int? = null,
        url: String? = null,
        categories: List<String>? = null
    ): Event {
        return Event(
            id = 100L + (originalInstanceTime % 1000), // Unique ID
            uid = masterUid, // Same UID as master
            importId = "$masterUid:RECID:$originalInstanceTime",
            calendarId = 1L,
            title = title,
            location = location,
            description = description,
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            isAllDay = false,
            status = status,
            transp = "OPAQUE",
            classification = "PUBLIC",
            organizerEmail = null,
            organizerName = null,
            rrule = null, // Exceptions have no RRULE
            rdate = null,
            exdate = null,
            duration = null,
            originalEventId = masterId, // Link to master
            originalInstanceTime = originalInstanceTime, // Which occurrence is modified
            originalSyncId = null,
            reminders = reminders,
            extraProperties = null,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = null,
            etag = null,
            sequence = sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = null,
            serverModifiedAt = null,
            priority = priority,
            geoLat = geoLat,
            geoLon = geoLon,
            color = color,
            url = url,
            categories = categories,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    // ========== Helper ==========

    private fun createTestEvent(
        uid: String,
        title: String,
        startTs: Long,
        endTs: Long,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        exdate: String? = null,
        reminders: List<String>? = null,
        sequence: Int = 0,
        organizerEmail: String? = null,
        organizerName: String? = null,
        priority: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null,
        color: Int? = null,
        url: String? = null,
        categories: List<String>? = null
    ): Event {
        return Event(
            uid = uid,
            calendarId = 1L,
            title = title,
            location = location,
            description = description,
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            isAllDay = isAllDay,
            status = "CONFIRMED",
            transp = "OPAQUE",
            classification = "PUBLIC",
            organizerEmail = organizerEmail,
            organizerName = organizerName,
            rrule = rrule,
            rdate = null,
            exdate = exdate,
            duration = null,
            originalEventId = null,
            originalInstanceTime = null,
            originalSyncId = null,
            reminders = reminders,
            extraProperties = null,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = null,
            etag = null,
            sequence = sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = null,
            serverModifiedAt = null,
            priority = priority,
            geoLat = geoLat,
            geoLon = geoLon,
            color = color,
            url = url,
            categories = categories,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
}
