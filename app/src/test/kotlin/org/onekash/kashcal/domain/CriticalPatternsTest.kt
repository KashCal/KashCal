package org.onekash.kashcal.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins data-layer invariants through [EventWriter] and [OccurrenceGenerator] over an in-memory
 * Room database.
 *
 * Areas covered:
 * - Exceptions share the master's UID, link through `originalEventId`, have no caldavUrl, and
 *   an edit queues an UPDATE on the master
 * - A MOVE op carries the old URL and target calendar captured at queue time
 * - Time-based queries go through the occurrences table, not `Event.endTs`
 * - SyncStatus transitions on update, local calendars included
 * - The PendingOperation queue: oldest first, one UPDATE for repeated edits
 * - [EventWriter.editSingleOccurrence] writes the exception and links its occurrence
 * - An exception has no RRULE; deleting an occurrence adds an EXDATE; moves between local and
 *   iCloud calendars queue a DELETE or a CREATE
 * - A moved exception doesn't duplicate after occurrence regeneration
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CriticalPatternsTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var iCloudCalendarId: Long = 0
    private var iCloudCalendar2Id: Long = 0
    private var localCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)

        runTest {
            val iCloudAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            iCloudCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = iCloudAccountId,
                    caldavUrl = "https://caldav.icloud.com/personal/",
                    displayName = "Personal",
                    color = 0xFF0000FF.toInt()
                )
            )
            iCloudCalendar2Id = database.calendarsDao().insert(
                Calendar(
                    accountId = iCloudAccountId,
                    caldavUrl = "https://caldav.icloud.com/work/",
                    displayName = "Work",
                    color = 0xFFFF5722.toInt()
                )
            )

            val localAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
            )
            localCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = localAccountId,
                    caldavUrl = "local://default",
                    displayName = "Local",
                    color = 0xFF4CAF50.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createRecurringEvent(
        calendarId: Long = iCloudCalendarId,
        rrule: String = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
    ): Event {
        val now = System.currentTimeMillis()
        return Event(
            id = 0,
            uid = "",
            calendarId = calendarId,
            title = "Recurring Meeting",
            startTs = now,
            endTs = now + 3600000,
            rrule = rrule,
            dtstamp = now
        )
    }

    private fun createSingleEvent(calendarId: Long = iCloudCalendarId): Event {
        val now = System.currentTimeMillis()
        return Event(
            id = 0,
            uid = "",
            calendarId = calendarId,
            title = "Single Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now
        )
    }

    // ==================== Exception Events Share Master UID ====================

    @Test
    fun `exception event has same UID as master event`() = runTest {
        // Create recurring event
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)
        val masterUid = master.uid

        // Get first occurrence to edit
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue("Should have occurrences", occurrences.isNotEmpty())
        val firstOccurrenceTs = occurrences.first().startTs

        // Editing one occurrence creates an exception.
        val modifiedEvent = master.copy(title = "Modified Occurrence")
        val exception = eventWriter.editSingleOccurrence(master.id, firstOccurrenceTs, modifiedEvent)

        // The exception has the master's UID; RECURRENCE-ID picks the instance
        // (RFC 5545 §3.8.4.4).
        assertEquals("Exception must have same UID as master", masterUid, exception.uid)
    }

    @Test
    fun `multiple exceptions all have same UID as master`() = runTest {
        // Create recurring event
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)
        val masterUid = master.uid

        // Get multiple occurrences
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue("Should have multiple occurrences", occurrences.size >= 3)

        // Three exceptions, each given its own occurrence's times. `master.copy` alone keeps the
        // first occurrence's start, which linkException reads as a move onto that occurrence.
        val duration = master.endTs - master.startTs
        val exception1 = eventWriter.editSingleOccurrence(
            master.id,
            occurrences[0].startTs,
            master.copy(title = "Exception 1", startTs = occurrences[0].startTs, endTs = occurrences[0].startTs + duration)
        )
        val exception2 = eventWriter.editSingleOccurrence(
            master.id,
            occurrences[1].startTs,
            master.copy(title = "Exception 2", startTs = occurrences[1].startTs, endTs = occurrences[1].startTs + duration)
        )
        val exception3 = eventWriter.editSingleOccurrence(
            master.id,
            occurrences[2].startTs,
            master.copy(title = "Exception 3", startTs = occurrences[2].startTs, endTs = occurrences[2].startTs + duration)
        )

        // Every exception has the master's UID.
        assertEquals(masterUid, exception1.uid)
        assertEquals(masterUid, exception2.uid)
        assertEquals(masterUid, exception3.uid)
    }

    @Test
    fun `re-editing exception preserves UID`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)
        val masterUid = master.uid

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        // First edit
        val exception = eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            master.copy(title = "First Edit")
        )
        assertEquals(masterUid, exception.uid)

        // Second edit of same exception
        val reEditedException = eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            exception.copy(title = "Second Edit")
        )

        // UID must still match master
        assertEquals(masterUid, reEditedException.uid)
    }

    @Test
    fun `exception event links to master via originalEventId FK`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        val exception = eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            master.copy(title = "Exception")
        )

        // Exception linked via FK, not string parsing
        assertEquals(master.id, exception.originalEventId)
        assertNotNull(exception.originalInstanceTime)
    }

    @Test
    fun `editing exception queues UPDATE on master not CREATE on exception`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        // Give it a caldavUrl: editSingleOccurrence queues the master's UPDATE only then.
        val syncedMaster = master.copy(
            caldavUrl = "https://caldav.icloud.com/personal/test.ics",
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedMaster)

        // Clear the CREATE queued by createEvent.
        database.pendingOperationsDao().deleteAll()

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            syncedMaster.copy(title = "Exception")
        )

        // One UPDATE on the master, no CREATE for the exception.
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)
        assertEquals(master.id, pendingOps[0].eventId)
        assertEquals(PendingOperation.OPERATION_UPDATE, pendingOps[0].operation)
    }

    @Test
    fun `exception caldavUrl remains null - not synced separately`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        val exception = eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            master.copy(title = "Exception")
        )

        // An exception never gets its own caldavUrl: it is pushed inside the master's resource.
        assertNull(exception.caldavUrl)
    }

    // ==================== Self-Contained Sync Operations ====================

    @Test
    fun `MOVE operation stores targetUrl at queue time before clearing caldavUrl`() = runTest {
        // Create an event, then give it a caldavUrl.
        val master = eventWriter.createEvent(createSingleEvent(), isLocal = false)
        val originalCaldavUrl = "https://caldav.icloud.com/personal/event123.ics"

        // As if a sync had stored its URL and etag.
        val syncedEvent = master.copy(
            caldavUrl = originalCaldavUrl,
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedEvent)

        // Clear the CREATE queued by createEvent.
        database.pendingOperationsDao().deleteAll()

        // Move to another calendar in the same account.
        eventWriter.moveEventToCalendar(syncedEvent.id, iCloudCalendar2Id)

        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)

        val moveOp = pendingOps[0]
        assertEquals(PendingOperation.OPERATION_MOVE, moveOp.operation)

        // targetUrl is the URL from before the move, captured at queue time.
        assertEquals(originalCaldavUrl, moveOp.targetUrl)

        // The event's caldavUrl is cleared for the new calendar.
        val updatedEvent = database.eventsDao().getById(syncedEvent.id)!!
        assertNull(updatedEvent.caldavUrl)
    }

    @Test
    fun `MOVE operation has all context needed without reading event`() = runTest {
        val master = eventWriter.createEvent(createSingleEvent(), isLocal = false)
        val originalCaldavUrl = "https://caldav.icloud.com/personal/event456.ics"

        val syncedEvent = master.copy(
            caldavUrl = originalCaldavUrl,
            etag = "\"def456\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedEvent)
        database.pendingOperationsDao().deleteAll()

        eventWriter.moveEventToCalendar(syncedEvent.id, iCloudCalendar2Id)

        val moveOp = database.pendingOperationsDao().getAll().first()

        // The op carries all the push needs.
        assertNotNull(moveOp.eventId)
        assertNotNull(moveOp.targetUrl) // Old URL for DELETE
        assertNotNull(moveOp.targetCalendarId) // New calendar ID
    }

    // ==================== Time-Based Queries Use Occurrences Table ====================

    @Test
    fun `recurring event with future occurrences found despite Event endTs in past`() = runTest {
        // A recurring event that started 30 days ago.
        val pastTime = System.currentTimeMillis() - 30L * 24 * 3600 * 1000 // 30 days ago
        val event = Event(
            id = 0,
            uid = "",
            calendarId = iCloudCalendarId,
            title = "Weekly Past Event",
            startTs = pastTime,
            endTs = pastTime + 3600000, // Event.endTs is the first occurrence's end
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR", // Generates future occurrences
            dtstamp = System.currentTimeMillis()
        )

        val created = eventWriter.createEvent(event, isLocal = false)

        // Event.endTs is in the past
        assertTrue("Event.endTs should be in past", created.endTs < System.currentTimeMillis())

        // But occurrences table has future entries
        val allOccurrences = database.occurrencesDao().getForEvent(created.id)
        val futureOccurrences = allOccurrences.filter { it.endTs >= System.currentTimeMillis() }

        assertTrue(
            "Should have future occurrences even though Event.endTs is past",
            futureOccurrences.isNotEmpty()
        )
    }

    @Test
    fun `time-range query uses occurrences table not Event endTs`() = runTest {
        // A daily event that started 7 days ago.
        val pastTime = System.currentTimeMillis() - 7L * 24 * 3600 * 1000 // 7 days ago
        val event = Event(
            id = 0,
            uid = "",
            calendarId = iCloudCalendarId,
            title = "Daily Meeting",
            startTs = pastTime,
            endTs = pastTime + 3600000,
            rrule = "FREQ=DAILY",
            dtstamp = System.currentTimeMillis()
        )

        val created = eventWriter.createEvent(event, isLocal = false)

        // Query the next 24 hours through the occurrences table.
        val now = System.currentTimeMillis()
        val endOfToday = now + 24 * 3600 * 1000

        val todayOccurrences = database.occurrencesDao().getInRangeOnce(now - 3600000, endOfToday)
            .filter { it.eventId == created.id }

        assertTrue(
            "Should find occurrence for today via occurrences table",
            todayOccurrences.isNotEmpty()
        )
    }

    // ==================== SyncStatus Transitions ====================

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on update`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        // Update before sync completes
        val updated = eventWriter.updateEvent(event.copy(title = "Updated"), isLocal = false)

        // Stays PENDING_CREATE: the queued CREATE carries the edit.
        assertEquals(SyncStatus.PENDING_CREATE, updated.syncStatus)
    }

    @Test
    fun `SYNCED becomes PENDING_UPDATE on update`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        // As if a sync had completed.
        val synced = event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.icloud.com/test.ics",
            etag = "\"abc\""
        )
        database.eventsDao().update(synced)

        val updated = eventWriter.updateEvent(synced.copy(title = "Updated"), isLocal = false)

        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }

    @Test
    fun `PENDING_UPDATE stays PENDING_UPDATE on another update`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        val synced = event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.icloud.com/test.ics"
        )
        database.eventsDao().update(synced)

        val updated1 = eventWriter.updateEvent(synced.copy(title = "Update 1"), isLocal = false)
        assertEquals(SyncStatus.PENDING_UPDATE, updated1.syncStatus)

        val updated2 = eventWriter.updateEvent(updated1.copy(title = "Update 2"), isLocal = false)
        assertEquals(SyncStatus.PENDING_UPDATE, updated2.syncStatus)
    }

    @Test
    fun `local calendar events always have SYNCED status`() = runTest {
        val event = createSingleEvent().copy(calendarId = localCalendarId)

        val created = eventWriter.createEvent(event, isLocal = true)
        assertEquals(SyncStatus.SYNCED, created.syncStatus)

        val updated = eventWriter.updateEvent(created.copy(title = "Updated"), isLocal = true)
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
    }

    // ==================== PendingOperation Queue ====================

    @Test
    fun `operations queued in FIFO order`() = runTest {
        database.pendingOperationsDao().deleteAll()

        // Three creates, each queuing a CREATE.
        val event1 = eventWriter.createEvent(createSingleEvent().copy(title = "Event 1"), isLocal = false)
        val event2 = eventWriter.createEvent(createSingleEvent().copy(title = "Event 2"), isLocal = false)
        val event3 = eventWriter.createEvent(createSingleEvent().copy(title = "Event 3"), isLocal = false)

        val pendingOps = database.pendingOperationsDao().getAll()

        // getAll returns them oldest first by createdAt.
        assertEquals(3, pendingOps.size)
        assertTrue("Operations should be ordered by creation time",
            pendingOps[0].createdAt <= pendingOps[1].createdAt &&
            pendingOps[1].createdAt <= pendingOps[2].createdAt
        )
    }

    @Test
    fun `no duplicate operations for same event and operation type`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        // Mark it synced: updateEvent queues no UPDATE for a PENDING_CREATE event.
        val syncedEvent = event.copy(
            caldavUrl = "https://caldav.icloud.com/personal/test.ics",
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedEvent)
        database.pendingOperationsDao().deleteAll()

        // Each update folds into the one pending UPDATE.
        eventWriter.updateEvent(syncedEvent.copy(title = "Update 1"), isLocal = false)
        eventWriter.updateEvent(syncedEvent.copy(title = "Update 2"), isLocal = false)
        eventWriter.updateEvent(syncedEvent.copy(title = "Update 3"), isLocal = false)

        val pendingOps = database.pendingOperationsDao().getAll()
            .filter { it.eventId == event.id && it.operation == PendingOperation.OPERATION_UPDATE }

        assertEquals(1, pendingOps.size)
    }

    // ==================== Transaction Atomicity ====================

    @Test
    fun `editSingleOccurrence creates exception and links in single transaction`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        val exception = eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            master.copy(title = "Exception")
        )

        // Both the exception and its linked occurrence exist.
        assertNotNull("Exception event should exist", database.eventsDao().getById(exception.id))

        // Look it up by exceptionEventId: linking moves the occurrence to the exception's times.
        val linkedOccurrence = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull("Occurrence should still exist and be linked", linkedOccurrence)
        assertEquals("Exception should be linked", exception.id, linkedOccurrence?.exceptionEventId)
    }

    // The this-and-future split (`EventWriter.splitSeries`) is tested in `EventWriterTest`.

    // ==================== Additional Edge Cases ====================

    @Test
    fun `exception event does not have its own RRULE`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        val exception = eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            master.copy(title = "Exception", rrule = null)
        )

        // An exception is one instance, so it has no RRULE.
        assertNull("Exception should not have RRULE", exception.rrule)
        assertFalse("Exception should not be recurring", exception.isRecurring)
        assertTrue("Exception should be marked as exception", exception.isException)
    }

    @Test
    fun `deleting occurrence adds EXDATE not separate operation`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        // Mark it synced: deleteSingleOccurrence queues no UPDATE for a PENDING_CREATE master.
        val syncedMaster = master.copy(
            caldavUrl = "https://caldav.icloud.com/personal/test.ics",
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedMaster)
        database.pendingOperationsDao().deleteAll()

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        eventWriter.deleteSingleOccurrence(master.id, occurrenceTs)

        // One UPDATE on the master to push the EXDATE, no DELETE.
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)
        assertEquals(PendingOperation.OPERATION_UPDATE, pendingOps[0].operation)
        assertEquals(master.id, pendingOps[0].eventId)

        val updatedMaster = database.eventsDao().getById(master.id)!!
        assertTrue("Master should have EXDATE", updatedMaster.exdate?.isNotEmpty() == true)
    }

    @Test
    fun `move from iCloud to local queues DELETE operation`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        val synced = event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.icloud.com/test.ics"
        )
        database.eventsDao().update(synced)
        database.pendingOperationsDao().deleteAll()

        // moveEventToCalendar reads synced-to-local from the two accounts.
        eventWriter.moveEventToCalendar(synced.id, localCalendarId)

        // One DELETE (it also carries sourceCalendarId, not asserted here).
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)
        assertEquals(PendingOperation.OPERATION_DELETE, pendingOps[0].operation)
    }

    @Test
    fun `move from local to iCloud queues CREATE not MOVE`() = runTest {
        val event = createSingleEvent().copy(calendarId = localCalendarId)
        val created = eventWriter.createEvent(event, isLocal = true)
        database.pendingOperationsDao().deleteAll()

        // moveEventToCalendar reads local-to-synced from the two accounts.
        eventWriter.moveEventToCalendar(created.id, iCloudCalendarId)

        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)
        // A CREATE, since the event is new to the server, not a MOVE.
        assertEquals(PendingOperation.OPERATION_CREATE, pendingOps[0].operation)
    }

    // ==================== Exception Linking After Regeneration ====================

    @Test
    fun `moved exception should not duplicate after RRULE regeneration`() = runTest {
        // Regenerating occurrences after an exception moved to another time must not show
        // both the original and the moved occurrence.

        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = true)
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrencesBefore = database.occurrencesDao().getForEvent(master.id)
        assertTrue("Should have multiple occurrences", occurrencesBefore.size >= 3)

        // The second occurrence
        val originalOccurrence = occurrencesBefore[1]
        val originalTime = originalOccurrence.startTs

        // Move it 2 hours later.
        val newStartTime = originalTime + 2 * 60 * 60 * 1000  // +2 hours
        val newEndTime = newStartTime + 60 * 60 * 1000  // 1 hour duration

        val modifiedEvent = master.copy(
            title = "Moved Meeting",
            startTs = newStartTime,
            endTs = newEndTime,
            rrule = null
        )

        val exception = eventWriter.editSingleOccurrence(
            master.id,
            originalTime,
            modifiedEvent
        )

        assertNotNull("Exception should be created", exception)
        assertEquals("Exception should link to master", master.id, exception.originalEventId)
        assertEquals("Exception should have originalInstanceTime", originalTime, exception.originalInstanceTime)

        // Regenerate, as when the server updates the master.
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrencesAfter = database.occurrencesDao().getForEvent(master.id)

        // Occurrences within a minute of the original time: none, the exception replaced it.
        val atOriginalTime = occurrencesAfter.filter {
            kotlin.math.abs(it.startTs - originalTime) < 60000
        }

        // Occurrences within a minute of the new time: one, the exception's.
        val atNewTime = occurrencesAfter.filter {
            kotlin.math.abs(it.startTs - newStartTime) < 60000
        }

        assertEquals(
            "Should have NO occurrence at original time (replaced by exception)",
            0, atOriginalTime.size
        )
        assertEquals(
            "Should have exactly ONE occurrence at new time (the exception)",
            1, atNewTime.size
        )
        assertEquals(
            "Occurrence at new time should link to exception event",
            exception.id, atNewTime[0].exceptionEventId
        )

        // No two occurrences share a start time.
        val uniqueStartTimes = occurrencesAfter.map { it.startTs }.toSet()
        assertEquals(
            "All occurrences should have unique start times",
            occurrencesAfter.size, uniqueStartTimes.size
        )
    }
}
