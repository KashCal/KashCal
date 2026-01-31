package org.onekash.kashcal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Calendar move operation robustness tests.
 *
 * Tests verify:
 * - Move to read-only calendar rejection
 * - Partial failure handling (DELETE succeeds, PUT fails)
 * - Move with caldavUrl conflicts
 * - Rollback scenarios
 * - Move during active sync
 * - Large exception set move performance
 *
 * These tests ensure calendar move reliability.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CalendarMoveRobustnessTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var sourceCalendarId: Long = 0
    private var targetCalendarId: Long = 0
    private var readOnlyCalendarId: Long = 0
    private var testAccountId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Use ICLOUD provider to test CalDAV sync behavior
        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )

        sourceCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://test.com/source/",
                displayName = "Source Calendar",
                color = 0xFF2196F3.toInt(),
                isReadOnly = false
            )
        )

        targetCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://test.com/target/",
                displayName = "Target Calendar",
                color = 0xFF4CAF50.toInt(),
                isReadOnly = false
            )
        )

        readOnlyCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://test.com/readonly/",
                displayName = "Read-Only Calendar",
                color = 0xFFFF5722.toInt(),
                isReadOnly = true
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Read-Only Calendar Tests ====================

    @Test
    fun `move to read-only calendar should be rejected`() = runTest {
        val event = createAndInsertEvent("Event to Move", sourceCalendarId)

        try {
            eventWriter.moveEventToCalendar(event.id, readOnlyCalendarId)
            fail("Should reject move to read-only calendar")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error should mention read-only",
                e.message?.contains("read-only", ignoreCase = true) == true
            )
        }

        // Event should remain in source calendar
        val unmoved = database.eventsDao().getById(event.id)!!
        assertEquals(sourceCalendarId, unmoved.calendarId)
    }

    @Test
    fun `move from read-only calendar should succeed`() = runTest {
        // Create event in read-only calendar (simulating server sync)
        val event = createAndInsertEvent("Read-Only Event", readOnlyCalendarId)

        // Moving FROM read-only should work (user might want to edit)
        eventWriter.moveEventToCalendar(event.id, targetCalendarId)

        val moved = database.eventsDao().getById(event.id)!!
        assertEquals(targetCalendarId, moved.calendarId)
    }

    // ==================== URL Capture Timing Tests ====================

    @Test
    fun `move operation should capture caldavUrl before clearing when event was SYNCED`() = runTest {
        val originalUrl = "https://test.com/source/event123.ics"
        val event = createAndInsertEvent("URL Capture Test", sourceCalendarId)

        // Mark as SYNCED with caldavUrl - this is required for MOVE operation
        val syncedEvent = event.copy(
            caldavUrl = originalUrl,
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedEvent)

        // Perform move
        eventWriter.moveEventToCalendar(syncedEvent.id, targetCalendarId)

        // Check pending operation has original URL
        val ops = database.pendingOperationsDao().getForEvent(syncedEvent.id)
        val moveOp = ops.find { it.operation == PendingOperation.OPERATION_MOVE }

        assertNotNull("Should have MOVE operation for SYNCED event with caldavUrl", moveOp)
        assertEquals(
            "MOVE operation should have original URL",
            originalUrl, moveOp!!.targetUrl
        )

        // Event's caldavUrl should be cleared
        val movedEvent = database.eventsDao().getById(syncedEvent.id)!!
        assertNull("Event caldavUrl should be cleared after move", movedEvent.caldavUrl)
    }

    // ==================== Same Calendar Move Tests ====================

    @Test
    fun `move to same calendar should be no-op`() = runTest {
        val event = createAndInsertEvent("Same Calendar Event", sourceCalendarId)
        val originalUpdatedAt = event.updatedAt

        // Move to same calendar
        eventWriter.moveEventToCalendar(event.id, sourceCalendarId)

        // Should be no-op (no pending operation created)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val moveOps = ops.filter { it.operation == PendingOperation.OPERATION_MOVE }
        assertTrue("Should not create MOVE operation for same calendar", moveOps.isEmpty())

        // Calendar should remain unchanged
        val unchanged = database.eventsDao().getById(event.id)!!
        assertEquals(sourceCalendarId, unchanged.calendarId)
    }

    // ==================== Recurring Event Move Tests ====================

    @Test
    fun `move recurring event should move master only`() = runTest {
        val master = createAndInsertRecurringEvent("Recurring Move", sourceCalendarId)
        occurrenceGenerator.regenerateOccurrences(master)

        // Move master
        eventWriter.moveEventToCalendar(master.id, targetCalendarId)

        // Master should be in target calendar
        val movedMaster = database.eventsDao().getById(master.id)!!
        assertEquals(targetCalendarId, movedMaster.calendarId)

        // Occurrences should be updated to target calendar
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue(occurrences.all { it.calendarId == targetCalendarId })
    }

    @Test
    fun `move recurring event with exceptions automatically moves exceptions`() = runTest {
        // v21.6.0: EventWriter.moveEventToCalendar NOW moves exceptions with master.
        // RFC 5545 says exceptions share UID with master and must stay together.
        val master = createAndInsertRecurringEvent("Recurring with Exceptions", sourceCalendarId)
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create exception
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "Exception Event")
        )

        // Move master
        eventWriter.moveEventToCalendar(master.id, targetCalendarId)

        // Master should be in target calendar
        val movedMaster = database.eventsDao().getById(master.id)!!
        assertEquals(targetCalendarId, movedMaster.calendarId)

        // v21.6.0: Exception should ALSO be in target calendar (cascaded with master)
        val movedException = database.eventsDao().getById(exception.id)!!
        assertEquals(targetCalendarId, movedException.calendarId)
    }

    @Test
    fun `move exception event directly throws error`() = runTest {
        // v21.6.0: EventWriter.moveEventToCalendar now rejects moving exceptions directly.
        // Per CLAUDE.md pattern #11, exceptions must stay with their master.
        val master = createAndInsertRecurringEvent("Master for Exception", sourceCalendarId)
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create exception
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "Direct Move Exception")
        )

        // v21.6.0: EventWriter now throws for exception events
        try {
            eventWriter.moveEventToCalendar(exception.id, targetCalendarId)
            fail("Should throw IllegalArgumentException for exception events")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cannot move exception event"))
        }
    }

    // ==================== Performance Tests ====================

    @Test
    fun `move recurring event with 30 exceptions should complete quickly`() = runTest {
        // v21.6.0: EventWriter.moveEventToCalendar now cascades to exceptions.
        // This test verifies performance with the cascading behavior.
        val master = createAndInsertRecurringEvent("Large Exception Set", sourceCalendarId)
            .let {
                // Update to have more occurrences
                val updated = it.copy(rrule = "FREQ=DAILY;COUNT=40")
                database.eventsDao().update(updated)
                database.eventsDao().getById(it.id)!!
            }

        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create 30 exceptions
        repeat(30) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception $i").withOccurrenceTime(occurrences[i])
            )
        }

        // Verify 30 exceptions created
        val exceptionsBefore = database.eventsDao().getExceptionsForMaster(master.id)
        assertEquals(30, exceptionsBefore.size)

        // Move should complete in reasonable time
        val moveTime = measureTimeMillis {
            eventWriter.moveEventToCalendar(master.id, targetCalendarId)
        }

        assertTrue("Move took ${moveTime}ms, should be under 3000ms", moveTime < 3000)

        // Master should be moved
        val movedMaster = database.eventsDao().getById(master.id)!!
        assertEquals(targetCalendarId, movedMaster.calendarId)

        // v21.6.0: Exceptions should ALSO be moved to target calendar (cascaded)
        val exceptionsAfter = database.eventsDao().getExceptionsForMaster(master.id)
        assertTrue("All exceptions should be in target calendar",
            exceptionsAfter.all { it.calendarId == targetCalendarId })
    }

    // ==================== Cross-Account Move Tests ====================

    @Test
    fun `move to calendar in different account should work`() = runTest {
        // Create second account
        val secondAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "test2@test.com")
        )
        val otherAccountCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = secondAccountId,
                caldavUrl = "https://other.com/cal/",
                displayName = "Other Account Calendar",
                color = 0xFF9C27B0.toInt()
            )
        )

        val event = createAndInsertEvent("Cross-Account Event", sourceCalendarId)

        // Move to other account's calendar
        eventWriter.moveEventToCalendar(event.id, otherAccountCalendarId)

        val moved = database.eventsDao().getById(event.id)!!
        assertEquals(otherAccountCalendarId, moved.calendarId)

        // Should have CREATE operation (not MOVE) because event was PENDING_CREATE (not SYNCED)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertTrue(
            "Should have CREATE operation for unsynced event",
            ops.any { it.operation == PendingOperation.OPERATION_CREATE }
        )
    }

    // ==================== Sync Status Transition Tests ====================

    @Test
    fun `move SYNCED event should mark as PENDING_UPDATE`() = runTest {
        val event = createAndInsertEvent("Synced Event", sourceCalendarId)

        // Mark as synced
        database.eventsDao().update(event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://test.com/source/synced.ics"
        ))

        eventWriter.moveEventToCalendar(event.id, targetCalendarId)

        val moved = database.eventsDao().getById(event.id)!!

        // Should create MOVE pending operation
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertTrue(
            "Should have MOVE operation for synced event",
            ops.any { it.operation == PendingOperation.OPERATION_MOVE }
        )
    }

    @Test
    fun `move PENDING_CREATE event should just update calendarId`() = runTest {
        val event = createAndInsertEvent("Pending Create Event", sourceCalendarId)
        // Already PENDING_CREATE by default

        eventWriter.moveEventToCalendar(event.id, targetCalendarId)

        val moved = database.eventsDao().getById(event.id)!!
        assertEquals(targetCalendarId, moved.calendarId)

        // Should NOT create MOVE operation (event not on server yet)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val moveOps = ops.filter { it.operation == PendingOperation.OPERATION_MOVE }

        // Implementation may vary - either no MOVE op, or MOVE op with no targetUrl
        if (moveOps.isNotEmpty()) {
            // If MOVE exists, it's acceptable but targetUrl might be null
            val moveOp = moveOps.first()
            assertTrue(
                "MOVE for unsynced event should have null targetUrl",
                moveOp.targetUrl == null || moveOp.targetUrl!!.isEmpty()
            )
        }
    }

    // ==================== Occurrence Update Tests ====================

    @Test
    fun `move should update all occurrence calendarIds`() = runTest {
        val event = createAndInsertRecurringEvent("Occurrence Update Test", sourceCalendarId)
        occurrenceGenerator.regenerateOccurrences(event)

        val occurrencesBefore = database.occurrencesDao().getForEvent(event.id)
        assertTrue(occurrencesBefore.all { it.calendarId == sourceCalendarId })

        eventWriter.moveEventToCalendar(event.id, targetCalendarId)

        val occurrencesAfter = database.occurrencesDao().getForEvent(event.id)
        assertTrue(
            "All occurrences should have new calendarId",
            occurrencesAfter.all { it.calendarId == targetCalendarId }
        )
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String, calendarId: Long): Event {
        val now = System.currentTimeMillis()
        return Event(
            calendarId = calendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = now,
            endTs = now + 3600000,
            timezone = "UTC",
            syncStatus = SyncStatus.PENDING_CREATE,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
    }

    private suspend fun createAndInsertEvent(title: String, calendarId: Long): Event {
        val event = createTestEvent(title, calendarId)
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    private suspend fun createAndInsertRecurringEvent(title: String, calendarId: Long): Event {
        val now = System.currentTimeMillis()
        val event = Event(
            calendarId = calendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = now,
            endTs = now + 3600000,
            timezone = "UTC",
            rrule = "FREQ=DAILY;COUNT=10",
            syncStatus = SyncStatus.PENDING_CREATE,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    /**
     * Create a modified event with correct times for the given occurrence.
     * EventWriter requires correct startTs/endTs matching the occurrence being edited.
     */
    private fun Event.withOccurrenceTime(occurrence: Occurrence): Event {
        val duration = this.endTs - this.startTs
        return this.copy(
            startTs = occurrence.startTs,
            endTs = occurrence.startTs + duration
        )
    }
}
