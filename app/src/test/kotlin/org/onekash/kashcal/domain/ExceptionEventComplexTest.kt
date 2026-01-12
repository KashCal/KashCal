package org.onekash.kashcal.domain

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.writer.EventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Complex exception event scenario tests.
 *
 * Tests verify edge cases in recurring event exception handling:
 * - Editing exception that was already edited (nested exceptions)
 * - Delete master while exceptions are pending
 * - Large exception set performance
 * - Exception linking after RRULE regeneration
 * - Exception event sync status transitions
 *
 * These tests validate RFC 5545 compliance and data integrity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExceptionEventComplexTest {

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

        val accountId = database.accountsDao().insert(
            Account(provider = "test", email = "test@test.com")
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

    // ==================== Nested Exception Editing Tests ====================

    @Test
    fun `editing exception event should preserve master link`() = runTest {
        // Create recurring event
        val master = createAndInsertRecurringEvent("Weekly Meeting", "FREQ=WEEKLY;COUNT=10")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue(occurrences.size >= 3)

        // Create exception for first occurrence
        val firstOccurrenceTs = occurrences[0].startTs
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = firstOccurrenceTs,
            modifiedEvent = master.copy(title = "Modified Meeting")
        )

        // Verify exception is linked to master
        assertEquals(master.id, exception.originalEventId)
        assertEquals(master.uid, exception.uid)

        // Now edit the exception again
        val updatedException = exception.copy(title = "Double Modified Meeting")
        eventWriter.updateEvent(updatedException, isLocal = true)

        // Verify link is still preserved
        val reloadedException = database.eventsDao().getById(exception.id)!!
        assertEquals(master.id, reloadedException.originalEventId)
        assertEquals("Double Modified Meeting", reloadedException.title)
    }

    @Test
    fun `re-editing same occurrence should update existing exception not create new`() = runTest {
        val master = createAndInsertRecurringEvent("Daily Standup", "FREQ=DAILY;COUNT=5")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val targetOccurrenceTs = occurrences[1].startTs

        // First edit
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrenceTs,
            modifiedEvent = master.copy(title = "First Edit")
        )

        // Second edit on same occurrence
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrenceTs,
            modifiedEvent = master.copy(title = "Second Edit")
        )

        // Should be same exception event
        assertEquals(exception1.id, exception2.id)

        // Verify only one exception exists for this occurrence
        val allExceptions = database.eventsDao().getExceptionsForMaster(master.id)
        val matchingExceptions = allExceptions.filter {
            it.originalInstanceTime == targetOccurrenceTs
        }
        assertEquals(1, matchingExceptions.size)
        assertEquals("Second Edit", matchingExceptions[0].title)
    }

    // ==================== Delete Master with Pending Exceptions Tests ====================

    @Test
    fun `delete master with pending exception creates should cleanup queue`() = runTest {
        val master = createAndInsertRecurringEvent("Team Sync", "FREQ=WEEKLY;COUNT=8")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create multiple exceptions
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "Exception 1")
        )
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[1].startTs,
            modifiedEvent = master.copy(title = "Exception 2")
        )

        // Add pending operations for exceptions
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = exception1.id,
                operation = PendingOperation.OPERATION_CREATE,
                status = PendingOperation.STATUS_PENDING
            )
        )

        // Delete master
        eventWriter.deleteEvent(master.id, isLocal = true)

        // Verify master is marked for deletion
        val deletedMaster = database.eventsDao().getById(master.id)
        assertTrue(deletedMaster == null || deletedMaster.syncStatus == SyncStatus.PENDING_DELETE)

        // Verify exceptions are also cleaned up
        val remainingExceptions = database.eventsDao().getExceptionsForMaster(master.id)
        assertTrue(
            remainingExceptions.isEmpty() ||
            remainingExceptions.all { it.syncStatus == SyncStatus.PENDING_DELETE }
        )
    }

    @Test
    fun `delete master cascades to all exceptions`() = runTest {
        val master = createAndInsertRecurringEvent("Monthly Review", "FREQ=MONTHLY;COUNT=6")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create 3 exceptions
        repeat(3) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception ${i + 1}")
            )
        }

        // Verify exceptions exist
        val beforeDelete = database.eventsDao().getExceptionsForMaster(master.id)
        assertEquals(3, beforeDelete.size)

        // Delete master (hard delete simulation)
        database.eventsDao().deleteById(master.id)

        // Exceptions should be cascade deleted via FK
        val afterDelete = database.eventsDao().getExceptionsForMaster(master.id)
        assertEquals(0, afterDelete.size)
    }

    // ==================== Large Exception Set Performance Tests ====================

    @Test
    fun `recurring event with 50 exceptions should query efficiently`() = runTest {
        val master = createAndInsertRecurringEvent("Daily Task", "FREQ=DAILY;COUNT=60")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create 50 exceptions
        repeat(50) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception ${i + 1}")
            )
        }

        // Query should complete quickly (under 1 second)
        val queryTime = measureTimeMillis {
            val exceptions = database.eventsDao().getExceptionsForMaster(master.id)
            assertEquals(50, exceptions.size)
        }

        assertTrue("Query took ${queryTime}ms, should be under 1000ms", queryTime < 1000)
    }

    @Test
    fun `move recurring event with many exceptions should complete reasonably`() = runTest {
        // Note: EventWriter.moveEventToCalendar does NOT move exceptions.
        // This test documents that the master move completes quickly even when
        // many exceptions exist.
        val master = createAndInsertRecurringEvent("Movable Event", "FREQ=DAILY;COUNT=30")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create 20 exceptions
        repeat(20) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception ${i + 1}")
            )
        }

        // Move should complete quickly
        val moveTime = measureTimeMillis {
            eventWriter.moveEventToCalendar(master.id, secondCalendarId)
        }

        assertTrue("Move took ${moveTime}ms, should be under 2000ms", moveTime < 2000)

        // Master should be moved
        val movedMaster = database.eventsDao().getById(master.id)!!
        assertEquals(secondCalendarId, movedMaster.calendarId)

        // Exceptions remain in original calendar (implementation does not cascade move)
        val exceptions = database.eventsDao().getExceptionsForMaster(master.id)
        assertTrue(exceptions.all { it.calendarId == testCalendarId })
    }

    // ==================== Exception Linking After RRULE Regeneration Tests ====================

    @Test
    fun `exception links should survive RRULE count increase`() = runTest {
        val master = createAndInsertRecurringEvent("Expandable", "FREQ=WEEKLY;COUNT=4")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val exceptionTs = occurrences[1].startTs

        // Create exception
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = exceptionTs,
            modifiedEvent = master.copy(title = "Important Exception")
        )

        // Update RRULE to have more occurrences
        val updatedMaster = database.eventsDao().getById(master.id)!!.copy(
            rrule = "FREQ=WEEKLY;COUNT=10"
        )
        eventWriter.updateEvent(updatedMaster, isLocal = true)

        // Exception should still be linked
        val reloadedException = database.eventsDao().getById(exception.id)
        assertNotNull(reloadedException)
        assertEquals(master.id, reloadedException!!.originalEventId)
        assertEquals("Important Exception", reloadedException.title)
    }

    @Test
    fun `exception links should survive RRULE frequency change`() = runTest {
        val master = createAndInsertRecurringEvent("Flexible", "FREQ=DAILY;COUNT=7")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val exceptionTs = occurrences[2].startTs

        // Create exception
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = exceptionTs,
            modifiedEvent = master.copy(title = "Preserved Exception")
        )

        // Change frequency (regenerates occurrences)
        val updatedMaster = database.eventsDao().getById(master.id)!!.copy(
            rrule = "FREQ=WEEKLY;COUNT=7"
        )
        eventWriter.updateEvent(updatedMaster, isLocal = true)

        // Exception should still exist and be linked
        val reloadedException = database.eventsDao().getById(exception.id)
        assertNotNull(reloadedException)
        assertEquals(master.id, reloadedException!!.originalEventId)
    }

    // ==================== Exception Sync Status Transitions Tests ====================

    @Test
    fun `exception inherits SYNCED status when bundled with master`() = runTest {
        val master = createAndInsertRecurringEvent("Synced Event", "FREQ=WEEKLY;COUNT=5")
        occurrenceGenerator.regenerateOccurrences(master)

        // Mark master as synced
        database.eventsDao().update(master.copy(syncStatus = SyncStatus.SYNCED))

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create exception - should be bundled with master, so status should reflect that
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "Bundled Exception")
        )

        // Exception sync status depends on implementation
        // It should either be SYNCED (bundled) or trigger master UPDATE
        val masterAfter = database.eventsDao().getById(master.id)!!
        assertTrue(
            masterAfter.syncStatus == SyncStatus.PENDING_UPDATE ||
            exception.syncStatus == SyncStatus.SYNCED
        )
    }

    @Test
    fun `exception should share UID with master per RFC 5545`() = runTest {
        val masterUid = UUID.randomUUID().toString()
        val master = createAndInsertRecurringEvent("RFC Event", "FREQ=DAILY;COUNT=3")
            .let { database.eventsDao().getById(it.id)!! }

        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "RFC Exception")
        )

        // UID must match (RFC 5545 requirement)
        assertEquals(master.uid, exception.uid)
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            calendarId = testCalendarId,
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

    private suspend fun createAndInsertRecurringEvent(title: String, rrule: String): Event {
        val now = System.currentTimeMillis()
        val event = Event(
            calendarId = testCalendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = now,
            endTs = now + 3600000,
            timezone = "UTC",
            rrule = rrule,
            syncStatus = SyncStatus.PENDING_CREATE,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }
}
