package org.onekash.kashcal.sync.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Tests the push serialization choice through [IcsPatcher], the serializer [PushStrategy] calls.
 *
 * - A one-off event goes through [IcsPatcher.serialize], which patches rawIcal when there is one
 *   and generates fresh ICS when it's missing or unparseable.
 * - A recurring master goes through [IcsPatcher.serializeWithExceptions] with its exceptions.
 * - The result parses back as valid ICS with the expected alarms, attendees and EXDATEs, for
 *   create, update and move.
 *
 * No database and no [PushStrategy]: [simulateSerializeEventWithExceptions] copies the branch
 * `serializeEventWithExceptions` takes, without its attendee lookup.
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
        // Created in the app, so no rawIcal.
        val localEvent = createLocalEvent(
            title = "New Local Event",
            reminders = listOf("-PT15M", "-PT30M", "-PT1H")
        )

        // The serialization PushStrategy.processCreate runs.
        val (icalData, _) = simulateSerializeEventWithExceptions(localEvent, emptyList())

        // Parses as valid ICS.
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

        // Every exception shares the master's UID.
        parsedExceptions.forEach { exc ->
            assertEquals(master.uid, exc.uid)
            assertNull("Exceptions have no RRULE", exc.rrule)
        }

        // Each exception must carry its own RECURRENCE-ID, the occurrence it replaces. The same
        // RECURRENCE-ID on every exception, or a dropped or duplicated one, would pass the UID
        // and RRULE checks above, so assert they're distinct and map back to the three
        // originalInstanceTimes.
        val recurrenceIds = parsedExceptions.map { it.recurrenceId.toString() }
        assertEquals(
            "Each exception must have a distinct RECURRENCE-ID",
            3,
            recurrenceIds.toSet().size
        )
        val expectedInstanceMs = exceptions.map { it.originalInstanceTime!! }.toSet()
        val parsedInstanceMs = parsedExceptions.map { it.recurrenceId!!.timestamp }.toSet()
        assertEquals(
            "Parsed RECURRENCE-IDs must match the three exception instance times",
            expectedInstanceMs,
            parsedInstanceMs
        )
    }

    // ==================== UPDATE: Server Event Re-Serialization ====================

    @Test
    fun `UPDATE server event honors deletion of displayed alarms`() {
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
            reminders = listOf("-PT5M", "-PT15M", "-PT30M")  // the user kept 3
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(event, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        assertEquals("Server Event - UPDATED", parsed.summary)
        // All 5 original alarms are in the displayed window (positions below 5), so the user's
        // 3 reminders win: the 2 alarms the user dropped (-PT1H, -P1D) aren't re-added. Only
        // alarms at positions 5 and beyond are kept as a hidden tail.
        assertEquals("Deleted displayed alarms are dropped", 3, parsed.alarms.size)
        val triggers = parsed.alarms.mapNotNull { it.trigger?.let { d -> org.onekash.icaldav.model.ICalAlarm.formatDuration(d) } }
        assertFalse("dropped -PT1H", triggers.contains("-PT1H"))
        assertFalse("dropped -P1D", triggers.contains("-P1D"))
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
            startTs = 1735128000000L  // a different time
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

        // An exception created in the app.
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

        // The master keeps its 2 alarms from rawIcal.
        assertEquals("Master keeps 2 alarms from rawIcal", 2, parsedMaster.alarms.size)

        // The exception has its own alarm.
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
            // As after deleteSingleOccurrence: EXDATE added.
            exdate = "1735185600000,1735272000000"  // days 2 and 3 excluded
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

        // The row after a move: rawIcal kept, caldavUrl and etag cleared, as
        // EventWriter.moveEventToCalendar leaves it.
        val movedEvent = createServerEvent(originalServerIcs).copy(
            calendarId = 2L,  // new calendar
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        )

        val (icalData, _) = simulateSerializeEventWithExceptions(movedEvent, emptyList())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        // Everything kept from rawIcal.
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
            calendarId = 2L,  // moved to a new calendar
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        )

        // An exception created before the move.
        val exception = createException(
            masterUid = master.uid,
            masterId = 1L,
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Weekly to Move - Modified",
            startTs = masterStartTs + (7 * 24 * 3600000L) + 3600000,
            endTs = masterStartTs + (7 * 24 * 3600000L) + 7200000
        ).copy(calendarId = 2L)  // moved with its master

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

        // An exception passed in, which a one-off event ignores.
        val fakeException = createException(
            masterUid = singleEvent.uid,
            masterId = 1L,
            originalInstanceTime = System.currentTimeMillis(),
            title = "Fake Exception"
        )

        val (icalData, exceptions) = simulateSerializeEventWithExceptions(singleEvent, listOf(fakeException))

        // Not serialized, because the event isn't recurring.
        assertTrue("No exceptions for single event", exceptions.isEmpty())

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!
        assertEquals("Only one event", 1, parsed.size)
    }

    @Test
    fun `exception event alone returns no-op success`() {
        // PushStrategy.processCreate and processUpdate skip an exception's own op (except a
        // partstat-only RSVP): it rides in its master's body through serializeWithExceptions.

        val exception = createException(
            masterUid = "master@test.com",
            masterId = 1L,
            originalInstanceTime = 1735099200000L,
            title = "Exception Only"
        )

        // Serializing an exception alone still works: with no rawIcal, IcsPatcher.serialize
        // generates fresh ICS.
        val icalData = IcsPatcher.serialize(exception)

        val parsed = parser.parseAllEvents(icalData).getOrNull()!!.first()

        // The fresh VEVENT has no RECURRENCE-ID (not asserted here): the standalone mapping
        // drops it, and PushStrategy never sends an exception alone.
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

    // ============ Helper: copy of the serializeEventWithExceptions branch ============

    /**
     * Serializes [event] the way `PushStrategy.serializeEventWithExceptions` branches: a
     * recurring master with [exceptions], anything else alone with no exceptions returned.
     *
     * The production function also passes each row's attendees from the attendees table, or
     * null when there are none. This copy passes null for every row, so the master or one-off
     * event keeps its rawIcal ATTENDEE block and each exception is generated with none.
     */
    private fun simulateSerializeEventWithExceptions(
        event: Event,
        exceptions: List<Event>
    ): Pair<String, List<Event>> {
        return if (event.rrule != null && event.originalEventId == null) {
            // Recurring master: include the exceptions.
            val icalData = IcsPatcher.serializeWithExceptions(event, exceptions)
            icalData to exceptions
        } else {
            // One-off event or exception.
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
            rawIcal = null,  // created in the app, so no rawIcal
            syncStatus = SyncStatus.PENDING_CREATE,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun createServerEvent(rawIcal: String): Event {
        val now = System.currentTimeMillis()
        // Reminders from rawIcal's START-relative alarms, roughly as ICalEventMapper.toEntity
        // derives them (no sort or cap here).
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
            reminders = reminders,  // from rawIcal, as above
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
            uid = masterUid,  // same as the master
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
            rawIcal = null,  // these fixture exceptions have no rawIcal
            rrule = null,  // an exception has no RRULE
            syncStatus = SyncStatus.SYNCED,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
    }
}
