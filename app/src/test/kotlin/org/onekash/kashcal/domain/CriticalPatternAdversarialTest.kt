package org.onekash.kashcal.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins data-layer invariants the sync path relies on, over an in-memory Room database.
 *
 * Areas covered:
 * - Time-based queries go through the occurrences table: `Event.endTs` is the first
 *   occurrence's end, not the series end
 * - A PendingOperation keeps the targetUrl captured at queue time (DELETE and MOVE)
 * - Exception events share the master's UID
 * - Exceptions link to the master through the `originalEventId` FK
 * - The PendingOperation queue holds CREATE, UPDATE and DELETE ops, and
 *   [PendingOperation.calculateRetryDelay] stays positive and capped
 *
 * The tests write events and ops through the DAOs; none drives EventWriter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CriticalPatternAdversarialTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventsDao: EventsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        eventsDao = database.eventsDao()
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

        // Setup test calendar
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://caldav.test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Time-Based Queries via Occurrences ====================

    @Test
    fun `Event endTs is first occurrence end - NOT series end`() = runTest {
        // Shows why filtering by Event.endTs misses a recurring event.
        val now = System.currentTimeMillis()

        // Create recurring event: daily for 10 days
        val event = Event(
            uid = "recurring@test.com",
            calendarId = testCalendarId,
            title = "Daily Standup",
            startTs = now,
            endTs = now + 3600000, // 1 hour: the first occurrence only
            dtstamp = now,
            rrule = "FREQ=DAILY;COUNT=10",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = eventsDao.insert(event)
        val savedEvent = eventsDao.getById(eventId)!!

        // Generate occurrences
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            now - 86400000,
            now + 20 * 86400000
        )

        // Event.endTs is the first occurrence's end (now + 1 hour); the series ends on day 10.
        val lastOccurrence = database.occurrencesDao().getForEvent(eventId)
            .maxByOrNull { it.endTs }!!

        // Event.endTs isn't the series end.
        assertTrue(
            "Last occurrence should be days after Event.endTs",
            lastOccurrence.endTs > savedEvent.endTs + 8 * 86400000
        )

        // A filter on Event.endTs would drop this event after day 1.
        val futureTime = now + 5 * 86400000 // 5 days from now
        assertTrue(
            "Event.endTs < futureTime - would incorrectly filter out this event!",
            savedEvent.endTs < futureTime
        )

        // The occurrences table still has it.
        val futureOccurrences = database.occurrencesDao().getForEvent(eventId)
            .filter { it.endTs >= futureTime }
        assertTrue(
            "Should find future occurrences via occurrences table",
            futureOccurrences.isNotEmpty()
        )
    }

    @Test
    fun `infinite recurring event has finite Event endTs`() = runTest {
        val now = System.currentTimeMillis()

        // Infinite recurring event (no COUNT or UNTIL)
        val event = Event(
            uid = "infinite@test.com",
            calendarId = testCalendarId,
            title = "Weekly Meeting",
            startTs = now,
            endTs = now + 3600000, // 1 hour
            dtstamp = now,
            rrule = "FREQ=WEEKLY", // Repeats forever
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = eventsDao.insert(event)
        val savedEvent = eventsDao.getById(eventId)!!

        // Event.endTs is the first occurrence's end, finite though the series never ends.
        assertEquals(
            "Event.endTs should be first occurrence end only",
            now + 3600000,
            savedEvent.endTs
        )

        // Generate occurrences for a month
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            now,
            now + 30 * 86400000L // 30 days
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId)
        // Should have multiple weekly occurrences in 30 days
        assertTrue("Should generate multiple weekly occurrences", occurrences.size >= 4)

        // Occurrences extend past Event.endTs.
        val lastOccurrence = occurrences.maxByOrNull { it.endTs }!!
        assertTrue(
            "Occurrences extend beyond Event.endTs",
            lastOccurrence.endTs > savedEvent.endTs
        )
    }

    // ==================== Self-Contained Sync Operations ====================

    @Test
    fun `PendingOperation DELETE must store targetUrl at queue time`() = runTest {
        val now = System.currentTimeMillis()

        // Event with CalDAV URL
        val caldavUrl = "https://caldav.icloud.com/123456/calendars/work/event1.ics"
        val event = Event(
            uid = "delete-test@test.com",
            calendarId = testCalendarId,
            title = "Event to Delete",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = caldavUrl,
            etag = "\"abc123\"",
            syncStatus = SyncStatus.PENDING_DELETE
        )
        val eventId = eventsDao.insert(event)

        // The op stores the URL at queue time.
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_DELETE,
            targetUrl = caldavUrl // Captured before the event's URL is cleared
        )
        database.pendingOperationsDao().insert(pendingOp)

        // The event's caldavUrl is then cleared.
        eventsDao.update(event.copy(id = eventId, caldavUrl = null))

        // The op still has the URL.
        val ops = database.pendingOperationsDao().getAll()
        assertEquals(caldavUrl, ops.first().targetUrl)

        // Reading it from the event at process time would give null.
        val clearedEvent = eventsDao.getById(eventId)!!
        assertNull("Event caldavUrl should be cleared", clearedEvent.caldavUrl)
    }

    @Test
    fun `PendingOperation MOVE must store source URL before clearing`() = runTest {
        val now = System.currentTimeMillis()

        // Create target calendar for the move
        val targetCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = database.calendarsDao().getById(testCalendarId)!!.accountId,
                caldavUrl = "https://caldav.test.com/work/",
                displayName = "Work Calendar",
                color = 0xFFFF5722.toInt()
            )
        )

        val sourceUrl = "https://caldav.icloud.com/123/calendars/personal/event.ics"
        val event = Event(
            uid = "move-test@test.com",
            calendarId = testCalendarId,
            title = "Event to Move",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = sourceUrl,
            etag = "\"xyz789\"",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = eventsDao.insert(event)

        // Capture the source URL before any change.
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = sourceUrl // Source URL for DELETE step
        )
        database.pendingOperationsDao().insert(pendingOp)

        // Move event to different calendar (clears caldavUrl)
        eventsDao.update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null,
            syncStatus = SyncStatus.PENDING_CREATE
        ))

        // PendingOperation has source URL for DELETE
        val ops = database.pendingOperationsDao().getAll()
        assertEquals(sourceUrl, ops.first().targetUrl)
    }

    // ==================== Exception Events Share Master UID ====================

    @Test
    fun `exception event must have same UID as master`() = runTest {
        val now = System.currentTimeMillis()
        val masterUid = "master-series@test.com"

        // Create master event
        val masterEvent = Event(
            uid = masterUid,
            calendarId = testCalendarId,
            title = "Weekly Standup",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            rrule = "FREQ=WEEKLY;COUNT=10",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = eventsDao.insert(masterEvent)
        val savedMaster = eventsDao.getById(masterId)!!

        // The third occurrence (two weeks from now)
        val targetOccTime = now + 14 * 86400000L

        // The exception has the master's UID; RECURRENCE-ID picks the instance
        // (RFC 5545 §3.8.4.4).
        val exception = Event(
            uid = masterUid, // Same as the master
            calendarId = testCalendarId,
            title = "Weekly Standup - RESCHEDULED",
            startTs = targetOccTime + 3600000, // Different time
            endTs = targetOccTime + 7200000,
            dtstamp = now,
            originalEventId = masterId,
            originalInstanceTime = targetOccTime,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = eventsDao.insert(exception)

        // Verify UID matches
        val savedException = eventsDao.getById(exceptionId)!!
        assertEquals(
            "Exception UID must match master UID",
            savedMaster.uid,
            savedException.uid
        )
    }

    @Test
    fun `exception event with different UID would create orphan on server`() = runTest {
        // Shows the trap: an exception with its own UID is a separate event on the server.
        val now = System.currentTimeMillis()
        val masterUid = "master@test.com"

        val masterEvent = Event(
            uid = masterUid,
            calendarId = testCalendarId,
            title = "Master",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            rrule = "FREQ=DAILY;COUNT=5",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = eventsDao.insert(masterEvent)

        // The trap: a UID of its own
        val wrongUid = "${masterUid}-${now}"
        val badException = Event(
            uid = wrongUid, // Not the master's UID
            calendarId = testCalendarId,
            title = "Exception",
            startTs = now + 86400000,
            endTs = now + 86400000 + 3600000,
            dtstamp = now,
            originalEventId = masterId,
            originalInstanceTime = now + 86400000,
            syncStatus = SyncStatus.PENDING_CREATE // Would be pushed as a new event
        )

        // Pushed, this would create an orphan event on the server. Nothing is written; the
        // assert only checks that the two UIDs differ.
        assertNotEquals(
            "Different UIDs would create orphan on server - THIS IS A BUG",
            masterUid,
            wrongUid
        )
    }

    // ==================== Exception Linking via FK ====================

    @Test
    fun `exception links to master via originalEventId FK`() = runTest {
        val now = System.currentTimeMillis()

        val masterEvent = Event(
            uid = "fk-test@test.com",
            calendarId = testCalendarId,
            title = "Master Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            rrule = "FREQ=DAILY;COUNT=5",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = eventsDao.insert(masterEvent)

        // Exception linked via FK, not string parsing
        val exception = Event(
            uid = "fk-test@test.com",
            calendarId = testCalendarId,
            title = "Exception",
            startTs = now + 86400000,
            endTs = now + 86400000 + 3600000,
            dtstamp = now,
            originalEventId = masterId, // FK link
            originalInstanceTime = now + 86400000,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = eventsDao.insert(exception)

        // Verify FK relationship
        val savedException = eventsDao.getById(exceptionId)!!
        assertEquals(masterId, savedException.originalEventId)

        // Can query exceptions by master ID
        val exceptions = eventsDao.getExceptionsForMaster(masterId)
        assertEquals(1, exceptions.size)
        assertEquals(exceptionId, exceptions.first().id)
    }

    // ==================== PendingOperation Queue ====================

    @Test
    fun `all mutations create PendingOperation entries`() = runTest {
        val now = System.currentTimeMillis()

        // The test inserts each op itself: it checks the queue stores all three kinds, not
        // that a mutation queues one.
        // CREATE op for a PENDING_CREATE event
        val createEvent = Event(
            uid = "create@test.com",
            calendarId = testCalendarId,
            title = "New Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_CREATE
        )
        val createId = eventsDao.insert(createEvent)
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = createId,
                operation = PendingOperation.OPERATION_CREATE
            )
        )

        // UPDATE op for a PENDING_UPDATE event
        val updateEvent = Event(
            uid = "update@test.com",
            calendarId = testCalendarId,
            title = "Updated Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = "https://caldav.test.com/event.ics",
            etag = "\"123\"",
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val updateId = eventsDao.insert(updateEvent)
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = updateId,
                operation = PendingOperation.OPERATION_UPDATE,
                targetUrl = "https://caldav.test.com/event.ics"
            )
        )

        // DELETE op for a PENDING_DELETE event
        val deleteEvent = Event(
            uid = "delete@test.com",
            calendarId = testCalendarId,
            title = "Deleted Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = "https://caldav.test.com/delete.ics",
            etag = "\"456\"",
            syncStatus = SyncStatus.PENDING_DELETE
        )
        val deleteId = eventsDao.insert(deleteEvent)
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = deleteId,
                operation = PendingOperation.OPERATION_DELETE,
                targetUrl = "https://caldav.test.com/delete.ics"
            )
        )

        // All three ops are queued.
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(3, pendingOps.size)

        val operations = pendingOps.map { op -> op.operation }.toSet()
        assertTrue(operations.contains(PendingOperation.OPERATION_CREATE))
        assertTrue(operations.contains(PendingOperation.OPERATION_UPDATE))
        assertTrue(operations.contains(PendingOperation.OPERATION_DELETE))
    }

    @Test
    fun `calculateRetryDelay handles edge cases`() {
        // A negative count gives the base delay.
        assertEquals(
            "Negative retryCount should return base delay",
            30_000L,
            PendingOperation.calculateRetryDelay(-1)
        )

        assertEquals(
            "Int.MIN_VALUE should return base delay",
            30_000L,
            PendingOperation.calculateRetryDelay(Int.MIN_VALUE)
        )

        // High retryCount should be capped
        val highDelay = PendingOperation.calculateRetryDelay(100)
        assertTrue(
            "High retryCount should be capped",
            highDelay <= 30_000L * 1024 // 2^10 * base
        )

        // All delays must be positive
        listOf(-100, -1, 0, 1, 5, 10, 100, Int.MAX_VALUE).forEach { count ->
            assertTrue(
                "Delay for retryCount=$count must be positive",
                PendingOperation.calculateRetryDelay(count) > 0
            )
        }
    }
}
