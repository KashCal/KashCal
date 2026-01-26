package org.onekash.kashcal.sync.strategy

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for PushStrategy's serializeEventWithExceptions() behavior.
 *
 * These tests verify:
 * - Master event serialization uses IcsPatcher.serialize() (preserves rawIcal)
 * - Exception event serialization uses IcsPatcher.serializeWithExceptions()
 * - Combined master+exception ICS is valid
 * - Round-trip fidelity for various scenarios
 *
 * Note: These tests don't require a database - they test the serialization
 * logic directly using IcsPatcher, which is what PushStrategy uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyRoundTripTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ==================== CREATE: New Event Serialization ====================

    @Test
    fun `CREATE local event uses generateFresh - produces valid ICS`() {
        // Event created locally (no rawIcal)
        val localEvent = createLocalEvent(
            title = "New Local Event",
            reminders = listOf("-PT15M", "-PT30M", "-PT1H")
        )

        // Simulate what PushStrategy.processCreate does
        val (icalData, _) = simulateSerializeEventWithExceptions(localEvent, emptyList())

        // Should be valid ICS
        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals("New Local Event", parsed.summary)
        assertEquals(3, parsed.alarms.size)
        assertNull("No RECURRENCE-ID for non-exception", parsed.recurrenceId)
    }

    @Test
    fun `CREATE recurring event with no exceptions`() {
        val recurringEvent = createLocalEvent(
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        )

        val (icalData, exceptions) = simulateSerializeEventWithExceptions(recurringEvent, emptyList())

        assertTrue("No exceptions returned", exceptions.isEmpty())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals("Weekly Meeting", parsed.summary)
        assertNotNull("Should have RRULE", parsed.rrule)
        assertTrue(parsed.rrule!!.toICalString().contains("FREQ=WEEKLY"))
    }

    @Test
    fun `CREATE recurring event with 3 exceptions`() {
        val masterStartTs = 1735099200000L // Dec 25, 2024

        val master = createLocalEvent(
            title = "Daily Standup",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=DAILY",
            reminders = listOf("-PT15M")
        )

        val exceptions = listOf(
            createException(
                masterUid = master.uid,
                masterId = 1L,
                originalInstanceTime = masterStartTs + (2 * 24 * 3600000L), // Day 3
                title = "Daily Standup - Extended",
                startTs = masterStartTs + (2 * 24 * 3600000L) + 3600000,
                endTs = masterStartTs + (2 * 24 * 3600000L) + 7200000
            ),
            createException(
                masterUid = master.uid,
                masterId = 1L,
                originalInstanceTime = masterStartTs + (5 * 24 * 3600000L), // Day 6
                title = "Daily Standup - Cancelled",
                startTs = masterStartTs + (5 * 24 * 3600000L),
                endTs = masterStartTs + (5 * 24 * 3600000L) + 3600000,
                status = "CANCELLED"
            ),
            createException(
                masterUid = master.uid,
                masterId = 1L,
                originalInstanceTime = masterStartTs + (10 * 24 * 3600000L), // Day 11
                title = "Daily Standup - Location Change",
                startTs = masterStartTs + (10 * 24 * 3600000L),
                endTs = masterStartTs + (10 * 24 * 3600000L) + 3600000,
                location = "Room B"
            )
        )

        val (icalData, serializedExceptions) = simulateSerializeEventWithExceptions(master, exceptions)

        assertEquals("Should serialize 3 exceptions", 3, serializedExceptions.size)

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!

        assertEquals("Should have 4 VEVENTs", 4, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        assertEquals("Daily Standup", parsedMaster.summary)
        assertEquals(3, parsedExceptions.size)

        // All exceptions share master's UID
        parsedExceptions.forEach { exc ->
            assertEquals(master.uid, exc.uid)
            assertNull("Exceptions have no RRULE", exc.rrule)
        }
    }

    // ==================== UPDATE: Server Event Re-Serialization ====================

    @Test
    fun `UPDATE server event preserves 5 alarms from rawIcal`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:update-test@server.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Server Event
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
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createServerEvent(serverIcs).copy(
            title = "Server Event - UPDATED",
            reminders = listOf("-PT5M", "-PT15M", "-PT30M")  // Only 3 stored
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(event, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals("Server Event - UPDATED", parsed.summary)
        assertEquals("All 5 alarms preserved from rawIcal", 5, parsed.alarms.size)
    }

    @Test
    fun `UPDATE server event preserves attendees from rawIcal`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:attendee-update@server.com
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

        val event = createServerEvent(serverIcs).copy(
            title = "Team Meeting - Time Changed",
            startTs = 1735128000000L  // Different time
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(event, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals("Team Meeting - Time Changed", parsed.summary)
        assertEquals("3 attendees preserved", 3, parsed.attendees.size)
        assertNotNull("Organizer preserved", parsed.organizer)
    }

    @Test
    fun `UPDATE recurring master with new exception bundles correctly`() {
        val masterServerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:recurring-update@server.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly Sync
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val masterStartTs = 1735120800000L

        val master = createServerEvent(masterServerIcs).copy(
            uid = "recurring-update@server.com",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY",
            reminders = listOf("-PT15M", "-PT1H")
        )

        // New exception created locally
        val exception = createException(
            masterUid = master.uid,
            masterId = 1L,
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Weekly Sync - Rescheduled",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (2 * 3600000L),
            endTs = masterStartTs + (7 * 24 * 3600000L) + (3 * 3600000L),
            reminders = listOf("-PT30M")
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(master, listOf(exception))

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!

        assertEquals("Should have master + 1 exception", 2, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedException = parsed.find { it.recurrenceId != null }!!

        // Master preserves its 2 alarms from rawIcal
        assertEquals("Master keeps 2 alarms from rawIcal", 2, parsedMaster.alarms.size)

        // Exception has its own alarm
        assertEquals("Exception has its own alarm", 1, parsedException.alarms.size)
    }

    // ==================== DELETE: Single Occurrence (EXDATE) ====================

    @Test
    fun `UPDATE master with new EXDATE serializes correctly`() {
        val masterStartTs = 1735099200000L

        val master = createLocalEvent(
            title = "Daily Task",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=10"
        ).copy(
            // After deleteSingleOccurrence - EXDATE added
            exdate = "1735185600000,1735272000000"  // Days 2 and 3 excluded
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(master, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals(2, parsed.exdates.size)
    }

    // ==================== MOVE: Calendar Change ====================

    @Test
    fun `MOVE event serialization preserves all from rawIcal`() {
        val originalServerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//CalendarA//Server//EN
            BEGIN:VEVENT
            UID:move-event@server.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event to Move
            ORGANIZER;CN=Organizer:mailto:org@company.com
            ATTENDEE;CN=Att1:mailto:att1@company.com
            ATTENDEE;CN=Att2:mailto:att2@company.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            X-CALENDAR-A-ID:12345
            X-APPLE-STRUCTURED-LOCATION:geo:37.33,-122.03
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Event after MOVE (rawIcal preserved, caldavUrl/etag cleared)
        val movedEvent = createServerEvent(originalServerIcs).copy(
            calendarId = 2L,  // New calendar
            caldavUrl = null,  // Cleared
            etag = null,  // Cleared
            syncStatus = SyncStatus.PENDING_CREATE
            // rawIcal preserved (current behavior)
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(movedEvent, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        // All preserved from rawIcal
        assertEquals(3, parsed.alarms.size)
        assertEquals(2, parsed.attendees.size)
        assertNotNull(parsed.organizer)
        assertTrue(parsed.rawProperties.keys.any { it.contains("X-CALENDAR-A") })
        assertTrue(parsed.rawProperties.keys.any { it.contains("X-APPLE") })
    }

    @Test
    fun `MOVE recurring event with exceptions bundles all`() {
        val masterStartTs = 1735099200000L

        val masterServerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:recurring-move@server.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly to Move
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val master = createServerEvent(masterServerIcs).copy(
            uid = "recurring-move@server.com",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY",
            calendarId = 2L,  // Moved to new calendar
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // Exception created before the move
        val exception = createException(
            masterUid = master.uid,
            masterId = 1L,
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Weekly to Move - Modified",
            startTs = masterStartTs + (7 * 24 * 3600000L) + 3600000,
            endTs = masterStartTs + (7 * 24 * 3600000L) + 7200000
        ).copy(calendarId = 2L)  // Exception should also be moved

        val (icalData, _) = simulateSerializeEventWithExceptions(master, listOf(exception))

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!

        assertEquals("Master + exception bundled", 2, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        assertEquals("Master preserves 2 alarms from rawIcal", 2, parsedMaster.alarms.size)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `single event (not recurring) ignores exceptions list`() {
        val singleEvent = createLocalEvent(
            title = "Single Event",
            rrule = null
        )

        // Pass exceptions (shouldn't be used for non-recurring)
        val fakeException = createException(
            masterUid = singleEvent.uid,
            masterId = 1L,
            originalInstanceTime = System.currentTimeMillis(),
            title = "Fake Exception"
        )

        val (icalData, exceptions) = simulateSerializeEventWithExceptions(singleEvent, listOf(fakeException))

        // Exception not serialized because master isn't recurring
        assertTrue("No exceptions for single event", exceptions.isEmpty())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!
        assertEquals("Only one event", 1, parsed.size)
    }

    @Test
    fun `exception event alone returns no-op success`() {
        // Exception events are skipped in PushStrategy.processCreate/processUpdate
        // They're bundled with master via serializeWithExceptions

        val exception = createException(
            masterUid = "master@test.com",
            masterId = 1L,
            originalInstanceTime = 1735099200000L,
            title = "Exception Only"
        )

        // If we tried to serialize exception alone, it should work
        // (IcsPatcher.serialize uses generateFresh for events without rawIcal)
        val icalData = IcsPatcher.serialize(exception)

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        // Has RECURRENCE-ID since originalInstanceTime is set
        // But PushStrategy skips these entirely
        assertEquals("Exception Only", parsed.summary)
    }

    @Test
    fun `corrupted rawIcal falls back to generateFresh`() {
        val event = Event(
            uid = "corrupted@test.com",
            calendarId = 1L,
            title = "Corrupted Raw",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M"),
            rawIcal = "THIS IS NOT VALID ICS",
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(event, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()

        assertNotNull("Should produce valid ICS via fallback", parsed)
        assertEquals("Corrupted Raw", parsed!!.first().summary)
        assertEquals("Entity reminder used", 1, parsed.first().alarms.size)
    }

    @Test
    fun `empty rawIcal uses generateFresh`() {
        val event = Event(
            uid = "empty-raw@test.com",
            calendarId = 1L,
            title = "Empty Raw Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-PT30M"),
            rawIcal = "",
            syncStatus = SyncStatus.PENDING_UPDATE,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(event, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals("Empty Raw Event", parsed.summary)
        assertEquals(2, parsed.alarms.size)
    }

    // ==================== Helper: Simulates PushStrategy.serializeEventWithExceptions ====================

    /**
     * Simulates PushStrategy.serializeEventWithExceptions() behavior.
     *
     * From PushStrategy.kt lines 541-551:
     * ```
     * if (event.rrule != null && event.originalEventId == null) {
     *     // Master recurring event - include exceptions
     *     val icalData = IcsPatcher.serializeWithExceptions(event, exceptions)
     *     icalData to exceptions
     * } else {
     *     // Single event or exception event
     *     IcsPatcher.serialize(event) to emptyList()
     * }
     * ```
     */
    private fun simulateSerializeEventWithExceptions(
        event: Event,
        exceptions: List<Event>
    ): Pair<String, List<Event>> {
        return if (event.rrule != null && event.originalEventId == null) {
            // Master recurring event - include exceptions
            val icalData = IcsPatcher.serializeWithExceptions(event, exceptions)
            icalData to exceptions
        } else {
            // Single event or exception event
            IcsPatcher.serialize(event) to emptyList()
        }
    }

    // ==================== Test Data Helpers ====================

    private fun createLocalEvent(
        title: String,
        startTs: Long = 1735120800000L,
        endTs: Long = 1735124400000L,
        rrule: String? = null,
        reminders: List<String>? = listOf("-PT15M")
    ): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "local-${System.nanoTime()}@kashcal.test",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            rrule = rrule,
            reminders = reminders,
            rawIcal = null,  // Local - no rawIcal
            syncStatus = SyncStatus.PENDING_CREATE,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun createServerEvent(rawIcal: String): Event {
        val now = System.currentTimeMillis()
        // Parse rawIcal to extract reminders (mimics ICalEventMapper.toEntity behavior)
        val parsed = parser.parseAllEvents(rawIcal).getOrNull()?.firstOrNull()
        val reminders = parsed?.alarms
            ?.filter { it.trigger != null && !it.triggerRelatedToEnd }
            ?.mapNotNull { alarm ->
                alarm.trigger?.let { org.onekash.icaldav.util.DurationUtils.format(it) }
            }
            ?.takeIf { it.isNotEmpty() }
        return Event(
            uid = "server-${System.nanoTime()}@server.com",
            calendarId = 1L,
            title = "Server Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            rawIcal = rawIcal,
            reminders = reminders,  // Extract from rawIcal like ICalEventMapper does
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://server.com/event.ics",
            etag = "\"v1\"",
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun createException(
        masterUid: String,
        masterId: Long,
        originalInstanceTime: Long,
        title: String,
        startTs: Long = originalInstanceTime + 3600000,
        endTs: Long = originalInstanceTime + 7200000,
        location: String? = null,
        status: String = "CONFIRMED",
        reminders: List<String>? = null
    ): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = masterUid,  // Same as master
            importId = "$masterUid:RECID:$originalInstanceTime",
            calendarId = 1L,
            title = title,
            location = location,
            startTs = startTs,
            endTs = endTs,
            status = status,
            originalEventId = masterId,
            originalInstanceTime = originalInstanceTime,
            reminders = reminders,
            rawIcal = null,  // Exceptions have no rawIcal
            rrule = null,  // Exceptions don't have RRULE
            syncStatus = SyncStatus.SYNCED,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
    }
}
