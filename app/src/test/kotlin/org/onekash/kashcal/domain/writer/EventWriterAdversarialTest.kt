package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for EventWriter.
 *
 * Tests probe edge cases that could corrupt data:
 * - Non-existent event operations
 * - Concurrent modifications
 * - Exception event edge cases
 * - Split series edge cases
 * - Move operations with invalid calendars
 * - State machine violations
 *
 * These tests verify defensive coding and data integrity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterAdversarialTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var secondCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Setup test calendars with ICLOUD provider for CalDAV sync behavior tests
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal1/",
                displayName = "Calendar 1",
                color = 0xFF2196F3.toInt()
            )
        )
        secondCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Calendar 2",
                color = 0xFFFF5722.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Non-Existent Event Tests ====================

    @Test
    fun `updateEvent throws for non-existent event`() = runTest {
        val fakeEvent = createTestEvent("Fake").copy(id = 999999L)

        try {
            eventWriter.updateEvent(fakeEvent, isLocal = false)
            fail("Should throw for non-existent event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Event not found") == true)
        }
    }

    @Test
    fun `deleteEvent throws for non-existent event`() = runTest {
        try {
            eventWriter.deleteEvent(999999L, isLocal = false)
            fail("Should throw for non-existent event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Event not found") == true)
        }
    }

    @Test
    fun `editSingleOccurrence throws for non-existent master`() = runTest {
        val modifiedEvent = createTestEvent("Modified")

        try {
            eventWriter.editSingleOccurrence(
                masterEventId = 999999L,
                occurrenceTimeMs = System.currentTimeMillis(),
                modifiedEvent = modifiedEvent,
                isLocal = false
            )
            fail("Should throw for non-existent master")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Master event not found") == true)
        }
    }

    @Test
    fun `deleteSingleOccurrence throws for non-existent master`() = runTest {
        try {
            eventWriter.deleteSingleOccurrence(
                masterEventId = 999999L,
                occurrenceTimeMs = System.currentTimeMillis(),
                isLocal = false
            )
            fail("Should throw for non-existent master")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Master event not found") == true)
        }
    }

    @Test
    fun `splitSeries throws for non-existent master`() = runTest {
        val modifiedEvent = createTestEvent("Modified")

        try {
            eventWriter.splitSeries(
                masterEventId = 999999L,
                splitTimeMs = System.currentTimeMillis(),
                modifiedEvent = modifiedEvent,
                isLocal = false
            )
            fail("Should throw for non-existent master")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Master event not found") == true)
        }
    }

    @Test
    fun `deleteThisAndFuture throws for non-existent master`() = runTest {
        try {
            eventWriter.deleteThisAndFuture(
                masterEventId = 999999L,
                fromTimeMs = System.currentTimeMillis(),
                isLocal = false
            )
            fail("Should throw for non-existent master")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Master event not found") == true)
        }
    }

    @Test
    fun `moveEventToCalendar throws for non-existent event`() = runTest {
        try {
            eventWriter.moveEventToCalendar(
                eventId = 999999L,
                newCalendarId = secondCalendarId
            )
            fail("Should throw for non-existent event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Event not found") == true)
        }
    }

    // ==================== Non-Recurring Event Edge Cases ====================

    @Test
    fun `editSingleOccurrence throws for non-recurring event`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Non-Recurring"), isLocal = false)
        val modifiedEvent = event.copy(title = "Modified")

        try {
            eventWriter.editSingleOccurrence(
                masterEventId = event.id,
                occurrenceTimeMs = event.startTs,
                modifiedEvent = modifiedEvent,
                isLocal = false
            )
            fail("Should throw for non-recurring event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not recurring") == true)
        }
    }

    @Test
    fun `deleteSingleOccurrence throws for non-recurring event`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Non-Recurring"), isLocal = false)

        try {
            eventWriter.deleteSingleOccurrence(
                masterEventId = event.id,
                occurrenceTimeMs = event.startTs,
                isLocal = false
            )
            fail("Should throw for non-recurring event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not recurring") == true)
        }
    }

    @Test
    fun `splitSeries throws for non-recurring event`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Non-Recurring"), isLocal = false)
        val modifiedEvent = event.copy(title = "Modified")

        try {
            eventWriter.splitSeries(
                masterEventId = event.id,
                splitTimeMs = event.startTs,
                modifiedEvent = modifiedEvent,
                isLocal = false
            )
            fail("Should throw for non-recurring event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not recurring") == true)
        }
    }

    @Test
    fun `deleteThisAndFuture throws for non-recurring event`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Non-Recurring"), isLocal = false)

        try {
            eventWriter.deleteThisAndFuture(
                masterEventId = event.id,
                fromTimeMs = event.startTs,
                isLocal = false
            )
            fail("Should throw for non-recurring event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not recurring") == true)
        }
    }

    // ==================== Same Calendar Move (No-Op) ====================

    @Test
    fun `moveEventToCalendar to same calendar is no-op`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Same Cal"), isLocal = false)
        val originalCalendarId = event.calendarId

        eventWriter.moveEventToCalendar(event.id, originalCalendarId)

        val afterMove = database.eventsDao().getById(event.id)!!
        assertEquals(originalCalendarId, afterMove.calendarId)
    }

    // ==================== Delete From First Occurrence ====================

    @Test
    fun `deleteThisAndFuture from first occurrence deletes entire event`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=5")
        val event = eventWriter.createEvent(recurringEvent, isLocal = true)

        // Delete from first occurrence (startTs)
        eventWriter.deleteThisAndFuture(event.id, event.startTs, isLocal = true)

        // Event should be completely deleted
        val deleted = database.eventsDao().getById(event.id)
        assertNull("Event should be deleted when deleting from first occurrence", deleted)
    }

    // ==================== Split Series Edge Cases ====================

    @Test
    fun `splitSeries creates new event with different UID`() = runTest {
        val recurringEvent = createTestEvent("Daily Meeting").copy(rrule = "FREQ=DAILY;COUNT=10")
        val master = eventWriter.createEvent(recurringEvent, isLocal = false)

        // Get second occurrence time
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitTime = occurrences[2].startTs // 3rd occurrence

        val modifiedEvent = master.copy(title = "Modified Meeting")
        val newEvent = eventWriter.splitSeries(master.id, splitTime, modifiedEvent, isLocal = false)

        // New event should have different UID
        assertNotEquals(master.uid, newEvent.uid)
    }

    @Test
    fun `splitSeries truncates master RRULE with UNTIL`() = runTest {
        val recurringEvent = createTestEvent("Daily Meeting").copy(rrule = "FREQ=DAILY;COUNT=10")
        val master = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitTime = occurrences[5].startTs

        val modifiedEvent = master.copy(title = "Modified")
        eventWriter.splitSeries(master.id, splitTime, modifiedEvent, isLocal = false)

        // Master RRULE should now have UNTIL
        val updatedMaster = database.eventsDao().getById(master.id)!!
        assertTrue(
            "Master RRULE should have UNTIL",
            updatedMaster.rrule?.contains("UNTIL=") == true
        )
    }

    @Test
    fun `splitSeries replaces existing COUNT with UNTIL`() = runTest {
        val recurringEvent = createTestEvent("Counted").copy(rrule = "FREQ=DAILY;COUNT=20")
        val master = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitTime = occurrences[5].startTs

        val modifiedEvent = master.copy(title = "Modified")
        eventWriter.splitSeries(master.id, splitTime, modifiedEvent, isLocal = false)

        val updatedMaster = database.eventsDao().getById(master.id)!!
        assertTrue(
            "COUNT should be removed",
            !updatedMaster.rrule!!.contains("COUNT=")
        )
        assertTrue(
            "UNTIL should be added",
            updatedMaster.rrule!!.contains("UNTIL=")
        )
    }

    // ==================== Exception Event Edge Cases ====================

    @Test
    fun `exception event has same UID as master`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=5")
        val master = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val occTime = occurrences[1].startTs

        val modifiedEvent = master.copy(title = "Modified Occurrence")
        val exception = eventWriter.editSingleOccurrence(master.id, occTime, modifiedEvent, isLocal = false)

        // RFC 5545: Exception MUST have same UID as master
        assertEquals("Exception UID should match master", master.uid, exception.uid)
    }

    @Test
    fun `exception event links to master via originalEventId`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=5")
        val master = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val occTime = occurrences[1].startTs

        val modifiedEvent = master.copy(title = "Modified")
        val exception = eventWriter.editSingleOccurrence(master.id, occTime, modifiedEvent, isLocal = false)

        assertEquals(master.id, exception.originalEventId)
        assertEquals(occTime, exception.originalInstanceTime)
    }

    @Test
    fun `re-editing exception updates existing instead of creating new`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=5")
        val master = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val occTime = occurrences[1].startTs

        // First edit
        val firstEdit = master.copy(title = "First Edit")
        val exception1 = eventWriter.editSingleOccurrence(master.id, occTime, firstEdit, isLocal = false)

        // Second edit of same occurrence
        val secondEdit = master.copy(title = "Second Edit")
        val exception2 = eventWriter.editSingleOccurrence(master.id, occTime, secondEdit, isLocal = false)

        // Should be same exception event ID (updated, not new)
        assertEquals(exception1.id, exception2.id)
        assertEquals("Second Edit", exception2.title)

        // Should only have 1 exception
        val exceptions = database.eventsDao().getExceptionsForMaster(master.id)
        assertEquals(1, exceptions.size)
    }

    // ==================== Pending Operation Queue Tests ====================

    @Test
    fun `duplicate pending operations are not created`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        val initialPendingCount = database.pendingOperationsDao().getAll().size

        // Update the same event multiple times
        eventWriter.updateEvent(event.copy(title = "Update 1"), isLocal = false)
        eventWriter.updateEvent(event.copy(title = "Update 2"), isLocal = false)
        eventWriter.updateEvent(event.copy(title = "Update 3"), isLocal = false)

        val finalPending = database.pendingOperationsDao().getForEvent(event.id)
        // Should not have multiple pending UPDATE operations
        val pendingUpdates = finalPending.filter { it.operation == PendingOperation.OPERATION_UPDATE }
        assertTrue("Should not duplicate pending operations", pendingUpdates.size <= 1)
    }

    @Test
    fun `delete upgrades existing pending operation`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        // Simulate sync completed
        database.eventsDao().update(event.copy(syncStatus = SyncStatus.SYNCED))
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Create update operation
        eventWriter.updateEvent(event.copy(title = "Updated"), isLocal = false)

        // Now delete - should upgrade UPDATE to DELETE
        eventWriter.deleteEvent(event.id, isLocal = false)

        val pending = database.pendingOperationsDao().getForEvent(event.id)
        val hasDelete = pending.any { it.operation == PendingOperation.OPERATION_DELETE }
        assertTrue("Delete should exist in pending", hasDelete)
    }

    // ==================== UID Generation Tests ====================

    @Test
    fun `UID is generated if empty`() = runTest {
        val eventWithoutUid = createTestEvent("No UID").copy(uid = "")
        val created = eventWriter.createEvent(eventWithoutUid, isLocal = false)

        assertTrue("UID should be generated", created.uid.isNotBlank())
        assertTrue("UID should be properly formatted", created.uid.contains("@"))
    }

    @Test
    fun `UID is preserved if provided`() = runTest {
        val customUid = "custom-uid-${System.nanoTime()}@example.com"
        val eventWithUid = createTestEvent("Has UID").copy(uid = customUid)
        val created = eventWriter.createEvent(eventWithUid, isLocal = false)

        assertEquals(customUid, created.uid)
    }

    @Test
    fun `generated UIDs are unique across many events`() = runTest {
        val events = (1..50).map { i ->
            eventWriter.createEvent(createTestEvent("Event $i").copy(uid = ""), isLocal = false)
        }

        val uids = events.map { it.uid }
        assertEquals("All UIDs should be unique", 50, uids.distinct().size)
    }

    // ==================== Timestamp Tests ====================

    @Test
    fun `createEvent sets all timestamps`() = runTest {
        val beforeCreate = System.currentTimeMillis()
        val event = eventWriter.createEvent(createTestEvent("Timestamped"), isLocal = false)
        val afterCreate = System.currentTimeMillis()

        assertNotNull(event.createdAt)
        assertNotNull(event.updatedAt)
        assertNotNull(event.localModifiedAt)

        assertTrue(event.dtstamp >= beforeCreate)
        assertTrue(event.dtstamp <= afterCreate)
    }

    @Test
    fun `updateEvent updates localModifiedAt`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        val originalModified = event.localModifiedAt!!

        delay(10) // Ensure time passes

        val updated = eventWriter.updateEvent(event.copy(title = "Updated"), isLocal = false)

        assertTrue(
            "localModifiedAt should increase",
            updated.localModifiedAt!! > originalModified
        )
    }

    // ==================== Sync Status State Machine ====================

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE after update`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        val updated = eventWriter.updateEvent(event.copy(title = "Updated"), isLocal = false)
        assertEquals(
            "Should stay PENDING_CREATE until synced",
            SyncStatus.PENDING_CREATE,
            updated.syncStatus
        )
    }

    @Test
    fun `SYNCED becomes PENDING_UPDATE after update`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        // Simulate sync completed
        val synced = event.copy(syncStatus = SyncStatus.SYNCED)
        database.eventsDao().update(synced)

        val updated = eventWriter.updateEvent(synced.copy(title = "Updated"), isLocal = false)
        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }

    @Test
    fun `PENDING_CREATE event is hard deleted`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Never Synced"), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        eventWriter.deleteEvent(event.id, isLocal = false)

        val deleted = database.eventsDao().getById(event.id)
        assertNull("PENDING_CREATE should be hard deleted", deleted)
    }

    @Test
    fun `SYNCED event is soft deleted`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Synced"), isLocal = false)
        val synced = event.copy(syncStatus = SyncStatus.SYNCED)
        database.eventsDao().update(synced)

        eventWriter.deleteEvent(event.id, isLocal = false)

        val deleted = database.eventsDao().getById(event.id)
        assertNotNull("SYNCED should be soft deleted", deleted)
        assertEquals(SyncStatus.PENDING_DELETE, deleted!!.syncStatus)
    }

    // ==================== Sequence Number Tests ====================

    @Test
    fun `sequence increments on timing change`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        val originalSeq = event.sequence

        val updated = eventWriter.updateEvent(
            event.copy(startTs = event.startTs + 3600000),
            isLocal = false
        )

        assertEquals(originalSeq + 1, updated.sequence)
    }

    @Test
    fun `sequence increments on RRULE change`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=5")
        val event = eventWriter.createEvent(recurringEvent, isLocal = false)
        val originalSeq = event.sequence

        val updated = eventWriter.updateEvent(
            event.copy(rrule = "FREQ=WEEKLY;COUNT=5"),
            isLocal = false
        )

        assertEquals(originalSeq + 1, updated.sequence)
    }

    @Test
    fun `sequence stays same on title-only change`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)
        val originalSeq = event.sequence

        val updated = eventWriter.updateEvent(
            event.copy(title = "New Title"),
            isLocal = false
        )

        assertEquals(originalSeq, updated.sequence)
    }

    // ==================== Occurrence Cascade Tests ====================

    @Test
    fun `deleting event removes occurrences`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=10")
        val event = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occsBefore = database.occurrencesDao().getForEvent(event.id)
        assertEquals(10, occsBefore.size)

        eventWriter.deleteEvent(event.id, isLocal = true)

        val occsAfter = database.occurrencesDao().getForEvent(event.id)
        assertTrue("Occurrences should be deleted", occsAfter.isEmpty())
    }

    @Test
    fun `updating RRULE regenerates occurrences`() = runTest {
        val recurringEvent = createTestEvent("Recurring").copy(rrule = "FREQ=DAILY;COUNT=5")
        val event = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occsBefore = database.occurrencesDao().getForEvent(event.id)
        assertEquals(5, occsBefore.size)

        eventWriter.updateEvent(event.copy(rrule = "FREQ=WEEKLY;COUNT=3"), isLocal = false)

        val occsAfter = database.occurrencesDao().getForEvent(event.id)
        assertEquals(3, occsAfter.size)
    }

    // ==================== Move Event Tests ====================

    @Test
    fun `moveEvent updates calendarId`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("To Move"), isLocal = false)
        assertEquals(testCalendarId, event.calendarId)

        eventWriter.moveEventToCalendar(event.id, secondCalendarId)

        val moved = database.eventsDao().getById(event.id)!!
        assertEquals(secondCalendarId, moved.calendarId)
    }

    @Test
    fun `moveEvent updates occurrence calendarIds`() = runTest {
        val recurringEvent = createTestEvent("Recurring Move").copy(rrule = "FREQ=DAILY;COUNT=5")
        val event = eventWriter.createEvent(recurringEvent, isLocal = false)

        eventWriter.moveEventToCalendar(event.id, secondCalendarId)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            assertEquals("Occurrence calendarId should be updated", secondCalendarId, occ.calendarId)
        }
    }

    @Test
    fun `moveEvent clears caldavUrl and etag`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Synced").copy(
            caldavUrl = "https://server.com/event.ics",
            etag = "\"abc123\""
        ), isLocal = false)

        eventWriter.moveEventToCalendar(event.id, secondCalendarId)

        val moved = database.eventsDao().getById(event.id)!!
        assertNull("caldavUrl should be cleared", moved.caldavUrl)
        assertNull("etag should be cleared", moved.etag)
    }

    @Test
    fun `moveEvent creates MOVE operation for synced event`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Synced"), isLocal = false)
        // Simulate sync completed with URL
        val synced = event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://server.com/event.ics",
            etag = "\"abc123\""
        )
        database.eventsDao().update(synced)
        database.pendingOperationsDao().deleteForEvent(event.id)

        eventWriter.moveEventToCalendar(event.id, secondCalendarId)

        val pending = database.pendingOperationsDao().getForEvent(event.id)
        val moveOp = pending.find { it.operation == PendingOperation.OPERATION_MOVE }
        assertNotNull("MOVE operation should be created", moveOp)
        assertEquals("https://server.com/event.ics", moveOp!!.targetUrl)
        assertEquals(secondCalendarId, moveOp.targetCalendarId)
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now + 3600000, // 1 hour from now
            endTs = now + 7200000,   // 2 hours from now
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_CREATE
        )
    }
}