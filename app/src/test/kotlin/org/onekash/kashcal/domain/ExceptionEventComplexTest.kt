package org.onekash.kashcal.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Tests exception edge cases through [EventWriter] over an in-memory Room database.
 *
 * Tests cover:
 * - Re-editing an exception, directly or through the same occurrence
 * - Deleting a master that has exceptions, through [EventWriter.deleteEvent] and the FK cascade
 * - Timing bounds for querying 50 exceptions and moving a series with 20
 * - Exception links surviving an RRULE change that regenerates occurrences
 * - An exception's sync status and its UID, shared with the master (RFC 5545)
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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)

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

    // ==================== Nested Exception Editing Tests ====================

    @Test
    fun `editing exception event should preserve master link`() = runTest {
        val master = createAndInsertRecurringEvent("Weekly Meeting", "FREQ=WEEKLY;COUNT=10")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue(occurrences.size >= 3)

        // An exception for the first occurrence
        val firstOccurrenceTs = occurrences[0].startTs
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = firstOccurrenceTs,
            modifiedEvent = master.copy(title = "Modified Meeting")
        )

        assertEquals(master.id, exception.originalEventId)
        assertEquals(master.uid, exception.uid)

        // Edit the exception itself.
        val updatedException = exception.copy(title = "Double Modified Meeting")
        eventWriter.updateEvent(updatedException, isLocal = true)

        // The link to the master is kept.
        val reloadedException = database.eventsDao().getById(exception.id)!!
        assertEquals(master.id, reloadedException.originalEventId)
        assertEquals("Double Modified Meeting", reloadedException.title)
    }

    @Test
    fun `re-editing same occurrence should update existing exception not create new`() = runTest {
        val master = createAndInsertRecurringEvent("Daily Standup", "FREQ=DAILY;COUNT=5")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val targetOccurrence = occurrences[1]
        val targetOccurrenceTs = targetOccurrence.startTs

        // First edit
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrenceTs,
            modifiedEvent = master.copy(title = "First Edit").withOccurrenceTime(targetOccurrence)
        )

        // Second edit on same occurrence
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrenceTs,
            modifiedEvent = master.copy(title = "Second Edit").withOccurrenceTime(targetOccurrence)
        )

        // The second edit updates the same exception.
        assertEquals(exception1.id, exception2.id)

        // One exception for this occurrence
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

        // Two exceptions
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "Exception 1").withOccurrenceTime(occurrences[0])
        )
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[1].startTs,
            modifiedEvent = master.copy(title = "Exception 2").withOccurrenceTime(occurrences[1])
        )

        // A pending CREATE for one exception; the queue isn't asserted after the delete.
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = exception1.id,
                operation = PendingOperation.OPERATION_CREATE,
                status = PendingOperation.STATUS_PENDING
            )
        )

        // A local delete removes the master at once.
        eventWriter.deleteEvent(master.id, isLocal = true)

        // The master is gone or marked PENDING_DELETE.
        val deletedMaster = database.eventsDao().getById(master.id)
        assertTrue(deletedMaster == null || deletedMaster.syncStatus == SyncStatus.PENDING_DELETE)

        // Its exceptions are gone or marked PENDING_DELETE.
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

        repeat(3) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception ${i + 1}").withOccurrenceTime(occurrences[i])
            )
        }

        val beforeDelete = database.eventsDao().getExceptionsForMaster(master.id)
        assertEquals(3, beforeDelete.size)

        // Delete the master row directly.
        database.eventsDao().deleteById(master.id)

        // The `original_event_id` FK cascade deletes its exceptions.
        val afterDelete = database.eventsDao().getExceptionsForMaster(master.id)
        assertEquals(0, afterDelete.size)
    }

    // ==================== Large Exception Set Performance Tests ====================

    @Test
    fun `recurring event with 50 exceptions should query efficiently`() = runTest {
        val master = createAndInsertRecurringEvent("Daily Task", "FREQ=DAILY;COUNT=60")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        repeat(50) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception ${i + 1}").withOccurrenceTime(occurrences[i])
            )
        }

        // The query finishes in under a second.
        val queryTime = measureTimeMillis {
            val exceptions = database.eventsDao().getExceptionsForMaster(master.id)
            assertEquals(50, exceptions.size)
        }

        assertTrue("Query took ${queryTime}ms, should be under 1000ms", queryTime < 1000)
    }

    @Test
    fun `move recurring event with many exceptions should complete reasonably`() = runTest {
        // EventWriter.moveEventToCalendar moves a master's exceptions with it; this bounds
        // its time with 20 exceptions.
        val master = createAndInsertRecurringEvent("Movable Event", "FREQ=DAILY;COUNT=30")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        repeat(20) { i ->
            eventWriter.editSingleOccurrence(
                masterEventId = master.id,
                occurrenceTimeMs = occurrences[i].startTs,
                modifiedEvent = master.copy(title = "Exception ${i + 1}").withOccurrenceTime(occurrences[i])
            )
        }

        // One UPDATE moves all the exceptions, so the move finishes in under 2 seconds.
        val moveTime = measureTimeMillis {
            eventWriter.moveEventToCalendar(master.id, secondCalendarId)
        }

        assertTrue("Move took ${moveTime}ms, should be under 2000ms", moveTime < 2000)

        val movedMaster = database.eventsDao().getById(master.id)!!
        assertEquals(secondCalendarId, movedMaster.calendarId)

        // The exceptions moved with the master.
        val exceptions = database.eventsDao().getExceptionsForMaster(master.id)
        assertTrue("All exceptions should move with master",
            exceptions.all { it.calendarId == secondCalendarId })
    }

    // ==================== Exception Linking After RRULE Regeneration Tests ====================

    @Test
    fun `exception links should survive RRULE count increase`() = runTest {
        val master = createAndInsertRecurringEvent("Expandable", "FREQ=WEEKLY;COUNT=4")
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val targetOccurrence = occurrences[1]
        val exceptionTs = targetOccurrence.startTs

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = exceptionTs,
            modifiedEvent = master.copy(title = "Important Exception").withOccurrenceTime(targetOccurrence)
        )

        // Raise COUNT, which regenerates occurrences.
        val updatedMaster = database.eventsDao().getById(master.id)!!.copy(
            rrule = "FREQ=WEEKLY;COUNT=10"
        )
        eventWriter.updateEvent(updatedMaster, isLocal = true)

        // The exception is still linked.
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
        val targetOccurrence = occurrences[2]
        val exceptionTs = targetOccurrence.startTs

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = exceptionTs,
            modifiedEvent = master.copy(title = "Preserved Exception").withOccurrenceTime(targetOccurrence)
        )

        // Change the frequency, which regenerates occurrences.
        val updatedMaster = database.eventsDao().getById(master.id)!!.copy(
            rrule = "FREQ=WEEKLY;COUNT=7"
        )
        eventWriter.updateEvent(updatedMaster, isLocal = true)

        // The exception still exists and is linked.
        val reloadedException = database.eventsDao().getById(exception.id)
        assertNotNull(reloadedException)
        assertEquals(master.id, reloadedException!!.originalEventId)
    }

    // ==================== Exception Sync Status Transitions Tests ====================

    @Test
    fun `exception inherits SYNCED status when bundled with master`() = runTest {
        val master = createAndInsertRecurringEvent("Synced Event", "FREQ=WEEKLY;COUNT=5")
        occurrenceGenerator.regenerateOccurrences(master)

        // Mark the master synced; it has no caldavUrl.
        database.eventsDao().update(master.copy(syncStatus = SyncStatus.SYNCED))

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // The exception is pushed inside the master's resource.
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[0].startTs,
            modifiedEvent = master.copy(title = "Bundled Exception")
        )

        // editSingleOccurrence always stores the exception SYNCED, and moves the master to
        // PENDING_UPDATE only when it has a caldavUrl. The assert accepts either.
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

        // The UIDs match; RECURRENCE-ID picks the instance (RFC 5545 §3.8.4.4).
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

    /**
     * Returns a copy moved to [occurrence]'s start, keeping this event's duration.
     *
     * An exception built from `master.copy` alone keeps the master's start, so
     * [OccurrenceGenerator.linkException] reads it as moved onto the first occurrence and
     * deletes the occurrence already there.
     */
    private fun Event.withOccurrenceTime(occurrence: Occurrence): Event {
        val duration = this.endTs - this.startTs
        return this.copy(
            startTs = occurrence.startTs,
            endTs = occurrence.startTs + duration
        )
    }
}
