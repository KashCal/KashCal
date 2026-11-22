package org.onekash.kashcal.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what happens to the stored ICS ([EventIcsStore]) when [EventWriter.moveEventToCalendar]
 * moves an event.
 *
 * The move keeps the row id, so its stored ICS, and its extraProperties: the body pushed to the
 * new calendar still carries the source's attendees and X-properties, the old calendar's
 * included. Also covers serialization with the stored ICS kept versus cleared, the ops queued, a
 * corrupted or missing stored ICS, a move to a local calendar, and a large stored ICS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MoveOperationRawIcalTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var parser: ICalParser
    private lateinit var icsStore: EventIcsStore

    private var calendarAId: Long = 0
    private var calendarBId: Long = 0
    private var localCalendarId: Long = 0
    private var testAccountId: Long = 0

    // Sample server ICS with rich properties
    private val richServerIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//iCloud//Calendar Server//EN
        BEGIN:VEVENT
        UID:move-rich@icloud.com
        DTSTAMP:20251220T100000Z
        DTSTART:20251225T100000Z
        DTEND:20251225T120000Z
        SUMMARY:Rich Event
        LOCATION:Room A
        DESCRIPTION:Important meeting
        ORGANIZER;CN=Boss:mailto:boss@company.com
        ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@company.com
        ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE:mailto:bob@company.com
        BEGIN:VALARM
        UID:alarm-1
        ACTION:DISPLAY
        TRIGGER:-PT15M
        DESCRIPTION:15 min
        END:VALARM
        BEGIN:VALARM
        UID:alarm-2
        ACTION:DISPLAY
        TRIGGER:-PT1H
        DESCRIPTION:1 hour
        END:VALARM
        BEGIN:VALARM
        UID:alarm-3
        ACTION:DISPLAY
        TRIGGER:-P1D
        DESCRIPTION:1 day
        END:VALARM
        BEGIN:VALARM
        UID:alarm-4
        ACTION:DISPLAY
        TRIGGER:-P1W
        DESCRIPTION:1 week
        END:VALARM
        X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
        X-CALENDAR-A-SYNC-ID:cal-a-12345
        X-CUSTOM-CATEGORY:work
        SEQUENCE:5
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)
        parser = ICalParser()
        icsStore = EventIcsStore(database)

        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )

        calendarAId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://caldav.icloud.com/calendar-a/",
                displayName = "Calendar A",
                color = 0xFF2196F3.toInt(),
                isReadOnly = false
            )
        )

        calendarBId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://caldav.icloud.com/calendar-b/",
                displayName = "Calendar B",
                color = 0xFF4CAF50.toInt(),
                isReadOnly = false
            )
        )

        // Create a true local account for testing synced→local moves
        val localAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "local")
        )
        localCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = localAccountId,  // True LOCAL account
                caldavUrl = "local://default",
                displayName = "Local Calendar",
                color = 0xFFFF5722.toInt(),
                isReadOnly = false
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Stored ICS Preservation Tests ====================

    @Test
    fun `MOVE preserves the stored ICS`() = runTest {
        // An event with a stored ICS, as a server sync leaves it
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        // Verify the stored ICS is set before move
        assertNotNull("stored ICS should be set before move", icsStore.read(event))

        // Perform move
        eventWriter.moveEventToCalendar(event.id, calendarBId)

        // Get moved event
        val movedEvent = database.eventsDao().getById(event.id)!!

        // The stored ICS is kept with the row id
        assertNotNull(
            "stored ICS should be preserved after MOVE",
            icsStore.read(movedEvent)
        )
        assertEquals(richServerIcs, icsStore.read(movedEvent))
    }

    @Test
    fun `MOVE clears caldavUrl and etag but preserves the stored ICS`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId).let {
            // Simulate synced state
            val synced = it.copy(
                caldavUrl = "https://caldav.icloud.com/calendar-a/event.ics",
                etag = "\"v5\"",
                syncStatus = SyncStatus.SYNCED
            )
            database.eventsDao().update(synced)
            synced
        }

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        // caldavUrl and etag cleared
        assertNull("caldavUrl should be cleared", movedEvent.caldavUrl)
        assertNull("etag should be cleared", movedEvent.etag)

        // stored ICS preserved
        assertNotNull("stored ICS should be preserved", icsStore.read(movedEvent))
    }

    @Test
    fun `MOVE preserves extraProperties`() = runTest {
        val extraProps = mapOf(
            "X-CUSTOM-PROP" to "value1",
            "X-APPLE-TRAVEL-DURATION" to "PT30M"
        )

        val event = createEventWithExtras(extraProps, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        assertEquals(
            "extraProperties should be preserved after MOVE",
            extraProps,
            movedEvent.extraProperties
        )
    }

    // ==================== Serialization After MOVE Tests ====================

    @Test
    fun `serialization after MOVE honors stored reminder set for displayed alarms`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        // Serialize for push to new calendar
        val pushIcs = IcsPatcher.serialize(movedEvent, icsStore.read(movedEvent))
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // richServerIcs has 4 alarms, all in the displayed window (the first 5). The entity
        // stores 2 reminders, so the other 2 were deleted and the move must not bring them
        // back. The stored ICS still supplies the attendees and X-properties.
        assertEquals(
            "Displayed alarms beyond the stored 2 are dropped after MOVE",
            2,
            parsed.alarms.size
        )
    }

    @Test
    fun `serialization after MOVE preserves attendees from the stored ICS`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!
        val pushIcs = IcsPatcher.serialize(movedEvent, icsStore.read(movedEvent))
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        assertEquals("Should preserve 2 attendees", 2, parsed.attendees.size)
        assertNotNull("Should preserve organizer", parsed.organizer)
    }

    @Test
    fun `serialization after MOVE preserves X-properties from the stored ICS`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!
        val pushIcs = IcsPatcher.serialize(movedEvent, icsStore.read(movedEvent))
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // X-properties from Calendar A's stored ICS still present
        assertTrue(
            "X-APPLE property should be preserved",
            parsed.rawProperties.keys.any { it.contains("X-APPLE") }
        )
        assertTrue(
            "X-CALENDAR-A-SYNC-ID should be preserved (may be unwanted)",
            parsed.rawProperties.keys.any { it.contains("X-CALENDAR-A") }
        )
    }

    @Test
    fun `serialization after MOVE without a stored ICS uses generateFresh`() = runTest {
        // Create local event (no stored ICS)
        val event = createLocalEvent(calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        assertNull("Local event should have no stored ICS", icsStore.read(movedEvent))

        // Serialize - should use generateFresh
        val pushIcs = IcsPatcher.serialize(movedEvent, icsStore.read(movedEvent))
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Only entity's reminders (2) should be present
        assertEquals(
            "Should have entity's reminders only",
            2,
            parsed.alarms.size
        )
    }

    // ==================== MOVE Operation Queue Tests ====================

    @Test
    fun `MOVE from SYNCED event creates OPERATION_MOVE with targetUrl`() = runTest {
        val originalUrl = "https://caldav.icloud.com/calendar-a/event.ics"

        val event = createEventWithRawIcal(richServerIcs, calendarAId).let {
            val synced = it.copy(
                caldavUrl = originalUrl,
                etag = "\"v5\"",
                syncStatus = SyncStatus.SYNCED
            )
            database.eventsDao().update(synced)
            synced
        }

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val moveOp = ops.find { it.operation == PendingOperation.OPERATION_MOVE }

        assertNotNull("Should create OPERATION_MOVE", moveOp)
        assertEquals("Should store original URL as targetUrl", originalUrl, moveOp!!.targetUrl)
        assertEquals("Should store target calendar ID", calendarBId, moveOp.targetCalendarId)
    }

    @Test
    fun `MOVE from PENDING_CREATE event creates OPERATION_CREATE`() = runTest {
        // Event created locally, not yet synced
        val event = createLocalEvent(calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val ops = database.pendingOperationsDao().getForEvent(event.id)

        // Should NOT create MOVE (nothing to delete on server)
        val moveOps = ops.filter { it.operation == PendingOperation.OPERATION_MOVE }
        assertTrue("Should NOT create MOVE for unsync'd event", moveOps.isEmpty())

        // Should create CREATE for new calendar
        val createOps = ops.filter { it.operation == PendingOperation.OPERATION_CREATE }
        assertEquals("Should have CREATE operation", 1, createOps.size)
    }

    // ==================== Comparison: Stored ICS Preserved vs Cleared ====================

    @Test
    fun `comparison - preserved vs cleared stored ICS on MOVE`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        // Scenario A: stored ICS kept, as the move does
        val pushIcsPreserved = IcsPatcher.serialize(movedEvent, icsStore.read(movedEvent))
        val parsedPreserved = parser.parseAllEvents(pushIcsPreserved).getOrNull()!!.first()

        // Scenario B: stored ICS cleared
        val pushIcsCleared = IcsPatcher.serialize(movedEvent, null)
        val parsedCleared = parser.parseAllEvents(pushIcsCleared).getOrNull()!!.first()

        // Compare
        println("=== COMPARISON: stored ICS Preserved vs Cleared ===")
        println("Preserved: ${parsedPreserved.alarms.size} alarms, ${parsedPreserved.attendees.size} attendees")
        println("Cleared:   ${parsedCleared.alarms.size} alarms, ${parsedCleared.attendees.size} attendees")
        println("Preserved X-props: ${parsedPreserved.rawProperties.keys.filter { it.startsWith("X-") }}")
        println("Cleared X-props:   ${parsedCleared.rawProperties.keys.filter { it.startsWith("X-") }}")

        // The kept version has at least as many alarms
        assertTrue(
            "Preserved stored ICS should have more alarms",
            parsedPreserved.alarms.size >= parsedCleared.alarms.size
        )

        // Cleared version only has what entity stores
        assertEquals(
            "Cleared should have only entity reminders",
            movedEvent.reminders?.size ?: 0,
            parsedCleared.alarms.size
        )

        // No attendees when cleared (entity doesn't store them)
        assertEquals(
            "Cleared should have no attendees",
            0,
            parsedCleared.attendees.size
        )
    }

    // ==================== Edge Cases ====================

    @Test
    fun `MOVE with a corrupted stored ICS still succeeds`() = runTest {
        val corruptedIcs = "NOT VALID ICS DATA"

        val event = createEventWithRawIcal(corruptedIcs, calendarAId)

        // Move should succeed
        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!
        assertEquals("Should be in Calendar B", calendarBId, movedEvent.calendarId)

        // Serialization should fall back to generateFresh
        val pushIcs = IcsPatcher.serialize(movedEvent, icsStore.read(movedEvent))
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()

        assertNotNull("Should produce valid ICS via fallback", parsed)
    }

    @Test
    fun `MOVE to local calendar auto-detects local target`() = runTest {
        // SYNCED with a caldavUrl, so the move queues a DELETE for the server copy
        val event = createEventWithRawIcal(richServerIcs, calendarAId).let {
            val synced = it.copy(
                caldavUrl = "https://caldav.icloud.com/calendar-a/event.ics",
                etag = "\"v1\"",
                syncStatus = SyncStatus.SYNCED
            )
            database.eventsDao().update(synced)
            database.eventsDao().getById(it.id)!!
        }

        // The writer treats the target as local from its account's provider
        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        assertEquals("Should be in local calendar", localCalendarId, movedEvent.calendarId)
        assertEquals("Should be SYNCED (local)", SyncStatus.SYNCED, movedEvent.syncStatus)

        // Should queue DELETE for the synced source
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have DELETE op for source", 1, ops.size)
        assertEquals(PendingOperation.OPERATION_DELETE, ops[0].operation)
        assertEquals("DELETE should have sourceCalendarId", calendarAId, ops[0].sourceCalendarId)
    }

    @Test
    fun `multiple MOVE operations - stored ICS accumulates old calendar X-props`() = runTest {
        // Documents a side effect of keeping the stored ICS: the old calendar's X-properties follow

        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        // Move to Calendar B
        eventWriter.moveEventToCalendar(event.id, calendarBId)

        // Until a pull from Calendar B replaces it, the stored ICS holds Calendar A's data
        var currentEvent = database.eventsDao().getById(event.id)!!

        // The stored ICS still has X-CALENDAR-A-SYNC-ID
        val pushIcs = IcsPatcher.serialize(currentEvent, icsStore.read(currentEvent))
        assertTrue(
            "After move to B, still has Calendar A X-prop",
            pushIcs.contains("X-CALENDAR-A")
        )
    }

    // ==================== Performance Test ====================

    @Test
    fun `MOVE event with a large stored ICS performs within limits`() = runTest {
        // Create event with a large stored ICS (many attendees, alarms)
        val largeIcs = buildLargeIcs(
            attendeeCount = 50,
            alarmCount = 10,
            xPropertyCount = 20
        )

        val event = createEventWithRawIcal(largeIcs, calendarAId)

        val startTime = System.currentTimeMillis()
        eventWriter.moveEventToCalendar(event.id, calendarBId)
        val moveTime = System.currentTimeMillis() - startTime

        assertTrue(
            "MOVE with a large stored ICS should complete within 100ms, took ${moveTime}ms",
            moveTime < 100
        )

        // Serialization time
        val movedEvent = database.eventsDao().getById(event.id)!!
        val stored = icsStore.read(movedEvent)
        val serializeStart = System.currentTimeMillis()
        val pushIcs = IcsPatcher.serialize(movedEvent, stored)
        val serializeTime = System.currentTimeMillis() - serializeStart

        assertTrue(
            "Serialization with a large stored ICS should complete within 500ms, took ${serializeTime}ms",
            serializeTime < 500
        )

        // Verify all data preserved
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()
        assertEquals("Should preserve all 50 attendees", 50, parsed.attendees.size)
        // 10 alarms, at -5M through -50M. The first 5 are the displayed window: -PT15M
        // matches one and the other 4 are dropped. The 5 hidden ones (index 5 and up) were
        // never shown and are kept verbatim. -PT1H matches none, so it is added as a new
        // alarm: 1 kept + 1 new + 5 hidden = 7.
        assertEquals("Displayed deletions honored, hidden alarms preserved", 7, parsed.alarms.size)
    }

    // ==================== Helper Methods ====================

    private suspend fun createEventWithRawIcal(rawIcal: String, calendarId: Long): Event {
        val now = System.currentTimeMillis()
        val event = Event(
            uid = "move-test-${System.nanoTime()}@test.com",
            calendarId = calendarId,
            title = "Move Test Event",
            location = "Room A",
            description = "Test event for move",
            startTs = 1735120800000L,
            endTs = 1735128000000L,
            reminders = listOf("-PT15M", "-PT1H"),  // Only 2 stored
            syncStatus = SyncStatus.SYNCED,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
        val id = database.eventsDao().insert(event)
        icsStore.write(id, rawIcal)
        return event.copy(id = id)
    }

    private suspend fun createEventWithExtras(extras: Map<String, String>, calendarId: Long): Event {
        val now = System.currentTimeMillis()
        val event = Event(
            uid = "extras-test-${System.nanoTime()}@test.com",
            calendarId = calendarId,
            title = "Extras Test Event",
            startTs = 1735120800000L,
            endTs = 1735128000000L,
            extraProperties = extras,
            syncStatus = SyncStatus.SYNCED,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    private suspend fun createLocalEvent(calendarId: Long): Event {
        val now = System.currentTimeMillis()
        val event = Event(
            uid = "local-test-${System.nanoTime()}@test.com",
            calendarId = calendarId,
            title = "Local Test Event",
            startTs = 1735120800000L,
            endTs = 1735128000000L,
            reminders = listOf("-PT15M", "-PT30M"),
            syncStatus = SyncStatus.PENDING_CREATE,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    private fun buildLargeIcs(attendeeCount: Int, alarmCount: Int, xPropertyCount: Int): String {
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//Test//Large//EN")
        sb.appendLine("BEGIN:VEVENT")
        sb.appendLine("UID:large-event@test.com")
        sb.appendLine("DTSTAMP:20251220T100000Z")
        sb.appendLine("DTSTART:20251225T100000Z")
        sb.appendLine("DTEND:20251225T120000Z")
        sb.appendLine("SUMMARY:Large Event")

        // Attendees
        repeat(attendeeCount) { i ->
            sb.appendLine("ATTENDEE;CN=Person$i;PARTSTAT=NEEDS-ACTION:mailto:person$i@company.com")
        }

        // Alarms
        repeat(alarmCount) { i ->
            sb.appendLine("BEGIN:VALARM")
            sb.appendLine("ACTION:DISPLAY")
            sb.appendLine("TRIGGER:-PT${(i + 1) * 5}M")
            sb.appendLine("DESCRIPTION:Alarm $i")
            sb.appendLine("END:VALARM")
        }

        // X-properties
        repeat(xPropertyCount) { i ->
            sb.appendLine("X-CUSTOM-PROP-$i:value-$i-${"a".repeat(50)}")
        }

        sb.appendLine("END:VEVENT")
        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }
}
