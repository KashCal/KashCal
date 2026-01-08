package org.onekash.kashcal.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.writer.EventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for critical patterns documented in CLAUDE.md.
 *
 * These tests prevent regression of bugs that have caused real issues.
 * Each test maps to a specific critical pattern.
 *
 * Patterns tested:
 * 1. @Transaction for multi-step operations (Pattern #1)
 * 2. PendingOperation Queue for Sync (Pattern #2)
 * 3. Exception Linking via FK (Pattern #3)
 * 4. SyncStatus Enum transitions (Pattern #4)
 * 5. Time-Based Queries: Use Occurrences Table (Pattern #5)
 * 6. Self-Contained Sync Operations (Pattern #6)
 * 7. Exception Events Share Master UID (Pattern #8)
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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        runTest {
            val iCloudAccountId = database.accountsDao().insert(
                Account(provider = "icloud", email = "test@icloud.com")
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
                Account(provider = "local", email = "local")
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

    // ==================== Pattern #8: Exception Events Share Master UID ====================

    @Test
    fun `exception event has same UID as master event`() = runTest {
        // Create recurring event
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)
        val masterUid = master.uid

        // Get first occurrence to edit
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue("Should have occurrences", occurrences.isNotEmpty())
        val firstOccurrenceTs = occurrences.first().startTs

        // Edit single occurrence - creates exception
        val modifiedEvent = master.copy(title = "Modified Occurrence")
        val exception = eventWriter.editSingleOccurrence(master.id, firstOccurrenceTs, modifiedEvent)

        // CRITICAL: Exception MUST have same UID as master (RFC 5545)
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

        // Create three exceptions
        val exception1 = eventWriter.editSingleOccurrence(
            master.id,
            occurrences[0].startTs,
            master.copy(title = "Exception 1")
        )
        val exception2 = eventWriter.editSingleOccurrence(
            master.id,
            occurrences[1].startTs,
            master.copy(title = "Exception 2")
        )
        val exception3 = eventWriter.editSingleOccurrence(
            master.id,
            occurrences[2].startTs,
            master.copy(title = "Exception 3")
        )

        // All exceptions MUST have same UID
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

        // Simulate sync completed - event needs caldavUrl and SYNCED status for UPDATE queue
        val syncedMaster = master.copy(
            caldavUrl = "https://caldav.icloud.com/personal/test.ics",
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedMaster)

        // Clear any pending operations from create
        database.pendingOperationsDao().deleteAll()

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val occurrenceTs = occurrences.first().startTs

        eventWriter.editSingleOccurrence(
            master.id,
            occurrenceTs,
            syncedMaster.copy(title = "Exception")
        )

        // Should queue UPDATE on MASTER (not CREATE on exception)
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

        // Exception never gets its own caldavUrl - bundled with master
        assertNull(exception.caldavUrl)
    }

    // ==================== Pattern #6: Self-Contained Sync Operations ====================

    @Test
    fun `MOVE operation stores targetUrl at queue time before clearing caldavUrl`() = runTest {
        // Create event with caldavUrl
        val master = eventWriter.createEvent(createSingleEvent(), isLocal = false)
        val originalCaldavUrl = "https://caldav.icloud.com/personal/event123.ics"

        // Simulate sync completed - set caldavUrl and etag
        val syncedEvent = master.copy(
            caldavUrl = originalCaldavUrl,
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedEvent)

        // Clear pending ops from create
        database.pendingOperationsDao().deleteAll()

        // Move to different calendar
        eventWriter.moveEventToCalendar(syncedEvent.id, iCloudCalendar2Id, isLocal = false)

        // Get the pending operation
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)

        val moveOp = pendingOps[0]
        assertEquals(PendingOperation.OPERATION_MOVE, moveOp.operation)

        // CRITICAL: targetUrl must be stored from BEFORE the move
        // This is Pattern #6 - context captured at queue time
        assertEquals(originalCaldavUrl, moveOp.targetUrl)

        // Verify the event's caldavUrl is now null (reset for new calendar)
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

        eventWriter.moveEventToCalendar(syncedEvent.id, iCloudCalendar2Id, isLocal = false)

        val moveOp = database.pendingOperationsDao().getAll().first()

        // All necessary context in operation:
        assertNotNull(moveOp.eventId)
        assertNotNull(moveOp.targetUrl) // Old URL for DELETE
        assertNotNull(moveOp.targetCalendarId) // New calendar ID
    }

    // ==================== Pattern #5: Time-Based Queries Use Occurrences Table ====================

    @Test
    fun `recurring event with future occurrences found despite Event endTs in past`() = runTest {
        // Create a recurring event that "started" in the past
        val pastTime = System.currentTimeMillis() - 30L * 24 * 3600 * 1000 // 30 days ago
        val event = Event(
            id = 0,
            uid = "",
            calendarId = iCloudCalendarId,
            title = "Weekly Past Event",
            startTs = pastTime,
            endTs = pastTime + 3600000, // Event.endTs is the FIRST occurrence's end time
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
        // Create recurring event starting in past
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

        // Query for occurrences TODAY using occurrences table (not Event.endTs)
        val now = System.currentTimeMillis()
        val endOfToday = now + 24 * 3600 * 1000

        val todayOccurrences = database.occurrencesDao().getInRangeOnce(now - 3600000, endOfToday)
            .filter { it.eventId == created.id }

        // Should find today's occurrence via occurrences table
        assertTrue(
            "Should find occurrence for today via occurrences table",
            todayOccurrences.isNotEmpty()
        )
    }

    // ==================== Pattern #4: SyncStatus Transitions ====================

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on update`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        // Update before sync completes
        val updated = eventWriter.updateEvent(event.copy(title = "Updated"), isLocal = false)

        // Should stay PENDING_CREATE (not become PENDING_UPDATE)
        assertEquals(SyncStatus.PENDING_CREATE, updated.syncStatus)
    }

    @Test
    fun `SYNCED becomes PENDING_UPDATE on update`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        // Simulate sync completed
        val synced = event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.icloud.com/test.ics",
            etag = "\"abc\""
        )
        database.eventsDao().update(synced)

        // Update after sync
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

    // ==================== Pattern #2: PendingOperation Queue ====================

    @Test
    fun `operations queued in FIFO order`() = runTest {
        database.pendingOperationsDao().deleteAll()

        // Create multiple events
        val event1 = eventWriter.createEvent(createSingleEvent().copy(title = "Event 1"), isLocal = false)
        val event2 = eventWriter.createEvent(createSingleEvent().copy(title = "Event 2"), isLocal = false)
        val event3 = eventWriter.createEvent(createSingleEvent().copy(title = "Event 3"), isLocal = false)

        val pendingOps = database.pendingOperationsDao().getAll()

        // Should be in order by createdAt
        assertEquals(3, pendingOps.size)
        assertTrue("Operations should be ordered by creation time",
            pendingOps[0].createdAt <= pendingOps[1].createdAt &&
            pendingOps[1].createdAt <= pendingOps[2].createdAt
        )
    }

    @Test
    fun `no duplicate operations for same event and operation type`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        // Simulate sync completed - operations only queued for non-PENDING_CREATE events
        val syncedEvent = event.copy(
            caldavUrl = "https://caldav.icloud.com/personal/test.ics",
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(syncedEvent)
        database.pendingOperationsDao().deleteAll()

        // Multiple updates - should not create duplicate pending ops
        eventWriter.updateEvent(syncedEvent.copy(title = "Update 1"), isLocal = false)
        eventWriter.updateEvent(syncedEvent.copy(title = "Update 2"), isLocal = false)
        eventWriter.updateEvent(syncedEvent.copy(title = "Update 3"), isLocal = false)

        val pendingOps = database.pendingOperationsDao().getAll()
            .filter { it.eventId == event.id && it.operation == PendingOperation.OPERATION_UPDATE }

        // Should only have one UPDATE operation (not three)
        assertEquals(1, pendingOps.size)
    }

    // ==================== Pattern #1: Transaction Atomicity ====================

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

        // Both should exist (atomic operation)
        assertNotNull("Exception event should exist", database.eventsDao().getById(exception.id))

        // v15.0.6: Find by exceptionEventId since occurrence times are updated to exception's times
        val linkedOccurrence = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull("Occurrence should still exist and be linked", linkedOccurrence)
        assertEquals("Exception should be linked", exception.id, linkedOccurrence?.exceptionEventId)
    }

    // Note: editThisAndFuture is on EventCoordinator, not EventWriter
    // This test is disabled until EventCoordinator test suite is created
    // @Test
    // fun `splitSeries truncates master and creates new event atomically`() = runTest { ... }

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

        // Exception should NOT have RRULE (it's a single instance)
        assertNull("Exception should not have RRULE", exception.rrule)
        assertFalse("Exception should not be recurring", exception.isRecurring)
        assertTrue("Exception should be marked as exception", exception.isException)
    }

    @Test
    fun `deleting occurrence adds EXDATE not separate operation`() = runTest {
        val master = eventWriter.createEvent(createRecurringEvent(), isLocal = false)

        // Simulate sync completed - event needs caldavUrl and SYNCED status for UPDATE queue
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

        // Should have UPDATE on master (to add EXDATE), not DELETE
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)
        assertEquals(PendingOperation.OPERATION_UPDATE, pendingOps[0].operation)
        assertEquals(master.id, pendingOps[0].eventId)

        // Master should have EXDATE
        val updatedMaster = database.eventsDao().getById(master.id)!!
        assertTrue("Master should have EXDATE", updatedMaster.exdate?.isNotEmpty() == true)
    }

    @Test
    fun `move from iCloud to local does not queue sync operation`() = runTest {
        val event = eventWriter.createEvent(createSingleEvent(), isLocal = false)

        val synced = event.copy(
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.icloud.com/test.ics"
        )
        database.eventsDao().update(synced)
        database.pendingOperationsDao().deleteAll()

        // Move to local calendar
        eventWriter.moveEventToCalendar(synced.id, localCalendarId, isLocal = true)

        // Should not queue any sync operation (local calendars don't sync)
        val pendingOps = database.pendingOperationsDao().getAll()
        assertTrue("No sync operation for move to local", pendingOps.isEmpty())
    }

    @Test
    fun `move from local to iCloud queues CREATE not MOVE`() = runTest {
        val event = createSingleEvent().copy(calendarId = localCalendarId)
        val created = eventWriter.createEvent(event, isLocal = true)
        database.pendingOperationsDao().deleteAll()

        // Move from local to iCloud
        eventWriter.moveEventToCalendar(created.id, iCloudCalendarId, isLocal = false)

        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)
        // Should be CREATE (new to server), not MOVE
        assertEquals(PendingOperation.OPERATION_CREATE, pendingOps[0].operation)
    }
}
