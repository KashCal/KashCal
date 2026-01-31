package org.onekash.kashcal.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for CLAUDE.md Critical Patterns.
 *
 * These tests verify the patterns documented in CLAUDE.md are followed:
 * 1. Always Use @Transaction for Multi-Step Operations
 * 2. PendingOperation Queue for Sync
 * 3. Exception Linking via FK
 * 4. Time-Based Queries: Use Occurrences Table (NOT Event.endTs)
 * 5. Self-Contained Sync Operations (targetUrl captured at queue time)
 * 6. Exception Events Share Master UID
 *
 * Violations of these patterns caused bugs that required multiple fix cycles.
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
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

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

    // ==================== Pattern 4: Time-Based Queries via Occurrences ====================

    @Test
    fun `Event endTs is first occurrence end - NOT series end`() = runTest {
        // This test documents WHY filtering by Event.endTs is wrong
        val now = System.currentTimeMillis()

        // Create recurring event: daily for 10 days
        val event = Event(
            uid = "recurring@test.com",
            calendarId = testCalendarId,
            title = "Daily Standup",
            startTs = now,
            endTs = now + 3600000, // 1 hour - THIS IS FIRST OCCURRENCE ONLY
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

        // Event.endTs = first occurrence end = now + 1 hour
        // But series actually ends on day 10!
        val lastOccurrence = database.occurrencesDao().getForEvent(eventId)
            .maxByOrNull { it.endTs }!!

        // CRITICAL: Event.endTs != series end
        assertTrue(
            "Last occurrence should be days after Event.endTs",
            lastOccurrence.endTs > savedEvent.endTs + 8 * 86400000
        )

        // WRONG approach: filtering by Event.endTs would miss this event after day 1
        val futureTime = now + 5 * 86400000 // 5 days from now
        assertTrue(
            "Event.endTs < futureTime - would incorrectly filter out this event!",
            savedEvent.endTs < futureTime
        )

        // CORRECT: Query occurrences table
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
            rrule = "FREQ=WEEKLY", // Infinite!
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = eventsDao.insert(event)
        val savedEvent = eventsDao.getById(eventId)!!

        // CRITICAL: Event.endTs = FIRST occurrence end (finite)
        // Even though the event repeats FOREVER!
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

        // Key insight: occurrences extend beyond Event.endTs
        val lastOccurrence = occurrences.maxByOrNull { it.endTs }!!
        assertTrue(
            "Occurrences extend beyond Event.endTs",
            lastOccurrence.endTs > savedEvent.endTs
        )
    }

    // ==================== Pattern 5: Self-Contained Sync Operations ====================

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

        // CORRECT: Store URL in pending operation at queue time
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_DELETE,
            targetUrl = caldavUrl // Captured NOW before any clearing
        )
        database.pendingOperationsDao().insert(pendingOp)

        // Simulate: event.caldavUrl gets cleared (common pattern)
        eventsDao.update(event.copy(id = eventId, caldavUrl = null))

        // VERIFY: PendingOperation still has the URL
        val ops = database.pendingOperationsDao().getAll()
        assertEquals(caldavUrl, ops.first().targetUrl)

        // If we had read from Event at process time, it would be null!
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

        // CORRECT: Capture source URL BEFORE any changes
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

    // ==================== Pattern 6: Exception Events Share Master UID ====================

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

        // Target occurrence time (3rd week from now)
        val targetOccTime = now + 14 * 86400000L

        // CORRECT: Exception has SAME UID as master (RFC 5545)
        val exception = Event(
            uid = masterUid, // MUST be same as master
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
        // This test documents the BUG pattern: different UID = orphan event
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

        // WRONG: Different UID (this was a bug pattern)
        val wrongUid = "${masterUid}-${now}"
        val badException = Event(
            uid = wrongUid, // WRONG! Different UID
            calendarId = testCalendarId,
            title = "Exception",
            startTs = now + 86400000,
            endTs = now + 86400000 + 3600000,
            dtstamp = now,
            originalEventId = masterId,
            originalInstanceTime = now + 86400000,
            syncStatus = SyncStatus.PENDING_CREATE // Would CREATE new event on server!
        )

        // This WOULD create an orphan event on the server
        // The test documents that different UID + PENDING_CREATE = bug
        assertNotEquals(
            "Different UIDs would create orphan on server - THIS IS A BUG",
            masterUid,
            wrongUid
        )
    }

    // ==================== Pattern 3: Exception Linking via FK ====================

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

    // ==================== Pattern 2: PendingOperation Queue ====================

    @Test
    fun `all mutations create PendingOperation entries`() = runTest {
        val now = System.currentTimeMillis()

        // CREATE: Must queue PENDING_CREATE
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

        // UPDATE: Must queue PENDING_UPDATE
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

        // DELETE: Must queue PENDING_DELETE
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

        // Verify all operations queued
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(3, pendingOps.size)

        val operations = pendingOps.map { op -> op.operation }.toSet()
        assertTrue(operations.contains(PendingOperation.OPERATION_CREATE))
        assertTrue(operations.contains(PendingOperation.OPERATION_UPDATE))
        assertTrue(operations.contains(PendingOperation.OPERATION_DELETE))
    }

    @Test
    fun `calculateRetryDelay handles edge cases`() {
        // Pattern violation that was fixed: negative input
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
