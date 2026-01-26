package org.onekash.kashcal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for MOVE operation rawIcal behavior.
 *
 * Verifies how rawIcal is handled when events are moved between calendars:
 * - rawIcal preservation during EventWriter.moveEventToCalendar()
 * - Serialization behavior with preserved vs cleared rawIcal
 * - Impact on round-trip fidelity
 * - Edge cases: corrupted rawIcal, null rawIcal, etc.
 *
 * These tests help determine if rawIcal should be cleared on MOVE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MoveOperationRawIcalTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var parser: ICalParser

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
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)
        parser = ICalParser()

        testAccountId = database.accountsDao().insert(
            Account(provider = "icloud", email = "test@icloud.com")
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

        localCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,  // Same account (simulating local-like calendar)
                caldavUrl = "https://caldav.icloud.com/local-calendar/",
                displayName = "Local-Like Calendar",
                color = 0xFFFF5722.toInt(),
                isReadOnly = false
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== rawIcal Preservation Tests ====================

    @Test
    fun `MOVE preserves rawIcal in current implementation`() = runTest {
        // Create event with rawIcal (simulating server sync)
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        // Verify rawIcal is set before move
        assertNotNull("rawIcal should be set before move", event.rawIcal)

        // Perform move
        eventWriter.moveEventToCalendar(event.id, calendarBId)

        // Get moved event
        val movedEvent = database.eventsDao().getById(event.id)!!

        // Current behavior: rawIcal is preserved
        assertNotNull(
            "rawIcal should be preserved after MOVE (current behavior)",
            movedEvent.rawIcal
        )
        assertEquals(richServerIcs, movedEvent.rawIcal)
    }

    @Test
    fun `MOVE clears caldavUrl and etag but preserves rawIcal`() = runTest {
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

        // rawIcal preserved
        assertNotNull("rawIcal should be preserved", movedEvent.rawIcal)
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
    fun `serialization after MOVE uses preserved rawIcal - preserves 4 alarms`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        // Serialize for push to new calendar
        val pushIcs = IcsPatcher.serialize(movedEvent)
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // Should preserve all 4 alarms from rawIcal
        assertEquals(
            "All 4 alarms should be preserved via rawIcal after MOVE",
            4,
            parsed.alarms.size
        )
    }

    @Test
    fun `serialization after MOVE preserves attendees from rawIcal`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!
        val pushIcs = IcsPatcher.serialize(movedEvent)
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        assertEquals("Should preserve 2 attendees", 2, parsed.attendees.size)
        assertNotNull("Should preserve organizer", parsed.organizer)
    }

    @Test
    fun `serialization after MOVE preserves X-properties from rawIcal`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!
        val pushIcs = IcsPatcher.serialize(movedEvent)
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()

        // X-properties from Calendar A's rawIcal still present
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
    fun `serialization after MOVE with null rawIcal uses generateFresh`() = runTest {
        // Create local event (no rawIcal)
        val event = createLocalEvent(calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        assertNull("Local event should have no rawIcal", movedEvent.rawIcal)

        // Serialize - should use generateFresh
        val pushIcs = IcsPatcher.serialize(movedEvent)
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

    // ==================== Comparison: rawIcal Preserved vs Cleared ====================

    @Test
    fun `comparison - preserved rawIcal vs cleared rawIcal on MOVE`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!

        // Scenario A: Current behavior (rawIcal preserved)
        val withPreservedRawIcal = movedEvent
        val pushIcsPreserved = IcsPatcher.serialize(withPreservedRawIcal)
        val parsedPreserved = parser.parseAllEvents(pushIcsPreserved).getOrNull()!!.first()

        // Scenario B: If we cleared rawIcal
        val withClearedRawIcal = movedEvent.copy(rawIcal = null)
        val pushIcsCleared = IcsPatcher.serialize(withClearedRawIcal)
        val parsedCleared = parser.parseAllEvents(pushIcsCleared).getOrNull()!!.first()

        // Compare
        println("=== COMPARISON: rawIcal Preserved vs Cleared ===")
        println("Preserved: ${parsedPreserved.alarms.size} alarms, ${parsedPreserved.attendees.size} attendees")
        println("Cleared:   ${parsedCleared.alarms.size} alarms, ${parsedCleared.attendees.size} attendees")
        println("Preserved X-props: ${parsedPreserved.rawProperties.keys.filter { it.startsWith("X-") }}")
        println("Cleared X-props:   ${parsedCleared.rawProperties.keys.filter { it.startsWith("X-") }}")

        // Preserved version has MORE data
        assertTrue(
            "Preserved rawIcal should have more alarms",
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
    fun `MOVE with corrupted rawIcal still succeeds`() = runTest {
        val corruptedIcs = "NOT VALID ICS DATA"

        val event = createEventWithRawIcal(corruptedIcs, calendarAId)

        // Move should succeed
        eventWriter.moveEventToCalendar(event.id, calendarBId)

        val movedEvent = database.eventsDao().getById(event.id)!!
        assertEquals("Should be in Calendar B", calendarBId, movedEvent.calendarId)

        // Serialization should fall back to generateFresh
        val pushIcs = IcsPatcher.serialize(movedEvent)
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()

        assertNotNull("Should produce valid ICS via fallback", parsed)
    }

    @Test
    fun `MOVE to local calendar sets isLocal flag`() = runTest {
        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        eventWriter.moveEventToCalendar(event.id, localCalendarId, isLocal = true)

        val movedEvent = database.eventsDao().getById(event.id)!!

        assertEquals("Should be in local calendar", localCalendarId, movedEvent.calendarId)
        assertEquals("Should be SYNCED (local)", SyncStatus.SYNCED, movedEvent.syncStatus)

        // No pending operations for local
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertTrue("Should have no pending ops for local move", ops.isEmpty())
    }

    @Test
    fun `multiple MOVE operations - rawIcal accumulates old calendar X-props`() = runTest {
        // This test documents potential issue with preserving rawIcal across moves

        val event = createEventWithRawIcal(richServerIcs, calendarAId)

        // Move to Calendar B
        eventWriter.moveEventToCalendar(event.id, calendarBId)

        // Simulate sync completion - rawIcal would get updated from Calendar B
        // But for now, rawIcal still has Calendar A data
        var currentEvent = database.eventsDao().getById(event.id)!!

        // The rawIcal still has X-CALENDAR-A-SYNC-ID
        val pushIcs = IcsPatcher.serialize(currentEvent)
        assertTrue(
            "After move to B, still has Calendar A X-prop",
            pushIcs.contains("X-CALENDAR-A")
        )
    }

    // ==================== Performance Test ====================

    @Test
    fun `MOVE event with large rawIcal performs within limits`() = runTest {
        // Create event with large rawIcal (many attendees, alarms)
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
            "MOVE with large rawIcal should complete within 100ms, took ${moveTime}ms",
            moveTime < 100
        )

        // Serialization time
        val movedEvent = database.eventsDao().getById(event.id)!!
        val serializeStart = System.currentTimeMillis()
        val pushIcs = IcsPatcher.serialize(movedEvent)
        val serializeTime = System.currentTimeMillis() - serializeStart

        assertTrue(
            "Serialization with large rawIcal should complete within 500ms, took ${serializeTime}ms",
            serializeTime < 500
        )

        // Verify all data preserved
        val parsed = parser.parseAllEvents(pushIcs).getOrNull()!!.first()
        assertEquals("Should preserve all 50 attendees", 50, parsed.attendees.size)
        assertEquals("Should preserve all 10 alarms", 10, parsed.alarms.size)
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
            rawIcal = rawIcal,
            syncStatus = SyncStatus.SYNCED,
            dtstamp = now,
            createdAt = now,
            updatedAt = now
        )
        val id = database.eventsDao().insert(event)
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
            rawIcal = null,  // Local event - no rawIcal
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
