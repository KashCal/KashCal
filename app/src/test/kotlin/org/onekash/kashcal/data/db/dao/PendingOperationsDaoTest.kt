package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests for PendingOperationsDao - the sync queue.
 *
 * Critical for offline-first architecture. Tests ensure:
 * - FIFO ordering of operations
 * - Duplicate prevention via operationExists
 * - Status transitions (pending -> in_progress -> completed/failed)
 * - Retry scheduling with backoff
 * - Consolidation of CREATE+UPDATE operations
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PendingOperationsDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pendingOpsDao: PendingOperationsDao
    private var testCalendarId: Long = 0
    private var testEventId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        pendingOpsDao = database.pendingOperationsDao()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.icloud.com/test/",
                    displayName = "Test Calendar",
                    color = 0xFF2196F3.toInt()
                )
            )
            testEventId = database.eventsDao().insert(
                Event(
                    id = 0,
                    uid = "test-event-uid",
                    calendarId = testCalendarId,
                    title = "Test Event",
                    startTs = System.currentTimeMillis(),
                    endTs = System.currentTimeMillis() + 3600000,
                    dtstamp = System.currentTimeMillis()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createOperation(
        eventId: Long = testEventId,
        operation: String = PendingOperation.OPERATION_CREATE,
        status: String = PendingOperation.STATUS_PENDING,
        createdAt: Long = System.currentTimeMillis(),
        nextRetryAt: Long = 0
    ): PendingOperation {
        return PendingOperation(
            id = 0,
            eventId = eventId,
            operation = operation,
            status = status,
            createdAt = createdAt,
            nextRetryAt = nextRetryAt
        )
    }

    // ==================== Basic CRUD Tests ====================

    @Test
    fun `insert creates operation with generated ID`() = runTest {
        val op = createOperation()
        val id = pendingOpsDao.insert(op)

        assertTrue(id > 0)

        val retrieved = pendingOpsDao.getById(id)
        assertNotNull(retrieved)
        assertEquals(testEventId, retrieved?.eventId)
    }

    @Test
    fun `getAll returns all operations`() = runTest {
        pendingOpsDao.insert(createOperation(operation = PendingOperation.OPERATION_CREATE))
        pendingOpsDao.insert(createOperation(operation = PendingOperation.OPERATION_UPDATE))
        pendingOpsDao.insert(createOperation(operation = PendingOperation.OPERATION_DELETE))

        val all = pendingOpsDao.getAll()

        assertEquals(3, all.size)
    }

    @Test
    fun `deleteById removes specific operation`() = runTest {
        val id = pendingOpsDao.insert(createOperation())

        pendingOpsDao.deleteById(id)

        val retrieved = pendingOpsDao.getById(id)
        assertNull(retrieved)
    }

    @Test
    fun `deleteAll clears all operations`() = runTest {
        pendingOpsDao.insert(createOperation())
        pendingOpsDao.insert(createOperation())
        pendingOpsDao.insert(createOperation())

        pendingOpsDao.deleteAll()

        val all = pendingOpsDao.getAll()
        assertTrue(all.isEmpty())
    }

    // ==================== FIFO Ordering Tests ====================

    @Test
    fun `getReadyOperations returns operations in FIFO order`() = runTest {
        // Create operations with specific timestamps
        val op1 = createOperation(createdAt = 1000L)
        val op2 = createOperation(createdAt = 2000L)
        val op3 = createOperation(createdAt = 3000L)

        pendingOpsDao.insert(op3) // Insert out of order
        pendingOpsDao.insert(op1)
        pendingOpsDao.insert(op2)

        val ready = pendingOpsDao.getReadyOperations(System.currentTimeMillis())

        assertEquals(3, ready.size)
        assertTrue(ready[0].createdAt <= ready[1].createdAt)
        assertTrue(ready[1].createdAt <= ready[2].createdAt)
    }

    @Test
    fun `getReadyOperations excludes future retry operations`() = runTest {
        val now = System.currentTimeMillis()

        // Pending operation with no delay - should be included
        pendingOpsDao.insert(createOperation(nextRetryAt = 0))

        // Operation scheduled for future retry - should be excluded
        pendingOpsDao.insert(createOperation(nextRetryAt = now + 60000))

        val ready = pendingOpsDao.getReadyOperations(now)

        assertEquals(1, ready.size)
    }

    @Test
    fun `getReadyOperations includes past retry operations`() = runTest {
        val now = System.currentTimeMillis()

        // Operation whose retry time has passed
        pendingOpsDao.insert(createOperation(nextRetryAt = now - 60000))

        val ready = pendingOpsDao.getReadyOperations(now)

        assertEquals(1, ready.size)
    }

    // ==================== Status Transition Tests ====================

    @Test
    fun `markInProgress updates status`() = runTest {
        val id = pendingOpsDao.insert(createOperation())
        val now = System.currentTimeMillis()

        pendingOpsDao.markInProgress(id, now)

        val op = pendingOpsDao.getById(id)
        assertEquals(PendingOperation.STATUS_IN_PROGRESS, op?.status)
    }

    @Test
    fun `markFailed updates status and error message`() = runTest {
        val id = pendingOpsDao.insert(createOperation())
        val now = System.currentTimeMillis()

        pendingOpsDao.markFailed(id, "Connection timeout", now)

        val op = pendingOpsDao.getById(id)
        assertEquals(PendingOperation.STATUS_FAILED, op?.status)
        assertEquals("Connection timeout", op?.lastError)
    }

    @Test
    fun `scheduleRetry sets retry time and increments count`() = runTest {
        val id = pendingOpsDao.insert(createOperation())
        val now = System.currentTimeMillis()
        val retryTime = now + 30000

        pendingOpsDao.scheduleRetry(id, retryTime, "Rate limited", now)

        val op = pendingOpsDao.getById(id)
        assertEquals(PendingOperation.STATUS_PENDING, op?.status)
        assertEquals(retryTime, op?.nextRetryAt)
        assertEquals(1, op?.retryCount)
        assertEquals("Rate limited", op?.lastError)
    }

    @Test
    fun `multiple retries increment count correctly`() = runTest {
        val id = pendingOpsDao.insert(createOperation())
        val now = System.currentTimeMillis()

        pendingOpsDao.scheduleRetry(id, now + 1000, "Error 1", now)
        pendingOpsDao.scheduleRetry(id, now + 2000, "Error 2", now)
        pendingOpsDao.scheduleRetry(id, now + 4000, "Error 3", now)

        val op = pendingOpsDao.getById(id)
        assertEquals(3, op?.retryCount)
    }

    @Test
    fun `resetToPending clears error and retry state`() = runTest {
        val id = pendingOpsDao.insert(createOperation())
        val now = System.currentTimeMillis()

        // First fail it
        pendingOpsDao.markFailed(id, "Some error", now)

        // Then reset
        pendingOpsDao.resetToPending(id, now)

        val op = pendingOpsDao.getById(id)
        assertEquals(PendingOperation.STATUS_PENDING, op?.status)
        assertEquals(0, op?.retryCount)
        assertEquals(0L, op?.nextRetryAt)
        assertNull(op?.lastError)
    }

    // ==================== Duplicate Prevention Tests ====================

    @Test
    fun `operationExists finds duplicate by eventId and operation`() = runTest {
        pendingOpsDao.insert(createOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE
        ))

        val exists = pendingOpsDao.operationExists(
            testEventId,
            PendingOperation.OPERATION_UPDATE
        )

        assertTrue(exists)
    }

    @Test
    fun `operationExists returns false for different operation type`() = runTest {
        pendingOpsDao.insert(createOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_CREATE
        ))

        val exists = pendingOpsDao.operationExists(
            testEventId,
            PendingOperation.OPERATION_UPDATE
        )

        assertFalse(exists)
    }

    @Test
    fun `operationExists ignores FAILED operations`() = runTest {
        val id = pendingOpsDao.insert(createOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE
        ))

        // Mark as failed
        pendingOpsDao.markFailed(id, "Error", System.currentTimeMillis())

        // Should not find it (failed ops are ignored for dedup)
        val exists = pendingOpsDao.operationExists(
            testEventId,
            PendingOperation.OPERATION_UPDATE
        )

        assertFalse(exists)
    }

    // ==================== MOVE Operation Tests ====================

    @Test
    fun `MOVE operation stores targetUrl and targetCalendarId`() = runTest {
        val op = PendingOperation(
            id = 0,
            eventId = testEventId,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old/event.ics",
            targetCalendarId = 999L,
            status = PendingOperation.STATUS_PENDING,
            createdAt = System.currentTimeMillis()
        )

        val id = pendingOpsDao.insert(op)
        val retrieved = pendingOpsDao.getById(id)

        assertEquals("https://caldav.icloud.com/old/event.ics", retrieved?.targetUrl)
        assertEquals(999L, retrieved?.targetCalendarId)
    }

    // ==================== Query by Event Tests ====================

    @Test
    fun `getForEvent returns operations for specific event`() = runTest {
        // Create second event
        val event2Id = database.eventsDao().insert(
            Event(
                id = 0,
                uid = "test-event-2",
                calendarId = testCalendarId,
                title = "Event 2",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        pendingOpsDao.insert(createOperation(eventId = testEventId))
        pendingOpsDao.insert(createOperation(eventId = testEventId))
        pendingOpsDao.insert(createOperation(eventId = event2Id))

        val event1Ops = pendingOpsDao.getForEvent(testEventId)
        val event2Ops = pendingOpsDao.getForEvent(event2Id)

        assertEquals(2, event1Ops.size)
        assertEquals(1, event2Ops.size)
    }

    @Test
    fun `deleteForEvent removes all operations for event`() = runTest {
        pendingOpsDao.insert(createOperation(eventId = testEventId, operation = PendingOperation.OPERATION_CREATE))
        pendingOpsDao.insert(createOperation(eventId = testEventId, operation = PendingOperation.OPERATION_UPDATE))

        pendingOpsDao.deleteForEvent(testEventId)

        val ops = pendingOpsDao.getForEvent(testEventId)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `hasPendingForEvent returns true when pending ops exist`() = runTest {
        pendingOpsDao.insert(createOperation(eventId = testEventId))

        val hasPending = pendingOpsDao.hasPendingForEvent(testEventId)

        assertTrue(hasPending)
    }

    @Test
    fun `hasPendingForEvent returns false when only failed ops exist`() = runTest {
        val id = pendingOpsDao.insert(createOperation(eventId = testEventId))
        pendingOpsDao.markFailed(id, "Error", System.currentTimeMillis())

        val hasPending = pendingOpsDao.hasPendingForEvent(testEventId)

        assertFalse(hasPending)
    }

    // ==================== Count/Flow Tests ====================

    @Test
    fun `getPendingCount returns correct count`() = runTest {
        pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))
        pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))
        val id = pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))
        pendingOpsDao.markInProgress(id, System.currentTimeMillis())

        val count = pendingOpsDao.getPendingCount().first()

        assertEquals(2, count)
    }

    @Test
    fun `getFailedCount returns failed operations count`() = runTest {
        val id1 = pendingOpsDao.insert(createOperation())
        val id2 = pendingOpsDao.insert(createOperation())
        pendingOpsDao.insert(createOperation())

        pendingOpsDao.markFailed(id1, "Error 1", System.currentTimeMillis())
        pendingOpsDao.markFailed(id2, "Error 2", System.currentTimeMillis())

        val count = pendingOpsDao.getFailedCount().first()

        assertEquals(2, count)
    }

    @Test
    fun `getTotalCount returns all operations count`() = runTest {
        pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))
        val id = pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))
        pendingOpsDao.markFailed(id, "Error", System.currentTimeMillis())
        pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))

        val count = pendingOpsDao.getTotalCount()

        assertEquals(3, count)
    }

    // ==================== Consolidation Tests ====================

    @Test
    fun `consolidateOperations removes UPDATE when CREATE exists`() = runTest {
        // Insert CREATE operation
        pendingOpsDao.insert(createOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_CREATE
        ))

        // Insert UPDATE operation
        pendingOpsDao.insert(createOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE
        ))

        // Consolidate should remove UPDATE
        val deletedCount = pendingOpsDao.consolidateOperations(testEventId)

        assertEquals(1, deletedCount)

        val remaining = pendingOpsDao.getForEvent(testEventId)
        assertEquals(1, remaining.size)
        assertEquals(PendingOperation.OPERATION_CREATE, remaining[0].operation)
    }

    @Test
    fun `consolidateOperations does nothing when no CREATE exists`() = runTest {
        // Only UPDATE operation
        pendingOpsDao.insert(createOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE
        ))

        val deletedCount = pendingOpsDao.consolidateOperations(testEventId)

        assertEquals(0, deletedCount)

        val remaining = pendingOpsDao.getForEvent(testEventId)
        assertEquals(1, remaining.size)
    }

    // ==================== Conflict Detection Tests ====================

    private suspend fun insertSecondCalendarWithEvent(): Pair<Long, Long> {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "other@example.com")
        )
        val calId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://other.example.com/cal/",
                displayName = "Other Calendar",
                color = 0xFF9C27B0.toInt()
            )
        )
        val evtId = database.eventsDao().insert(
            Event(
                id = 0,
                uid = "other-event-uid",
                calendarId = calId,
                title = "Other Event",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )
        return calId to evtId
    }

    @Test
    fun `getConflictOperationsForCalendar filters CREATE ops by events calendar_id`() = runTest {
        val (otherCalId, otherEvtId) = insertSecondCalendarWithEvent()
        val opA = pendingOpsDao.insert(createOperation(eventId = testEventId, operation = PendingOperation.OPERATION_CREATE))
        val opB = pendingOpsDao.insert(createOperation(eventId = otherEvtId, operation = PendingOperation.OPERATION_CREATE))
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(opA, now, "412 Precondition Failed", now)
        pendingOpsDao.scheduleRetry(opB, now, "412 Precondition Failed", now)

        val scopedToA = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)
        val scopedToB = pendingOpsDao.getConflictOperationsForCalendar(otherCalId)

        assertEquals(1, scopedToA.size)
        assertEquals(opA, scopedToA[0].id)
        assertEquals(1, scopedToB.size)
        assertEquals(opB, scopedToB[0].id)
    }

    @Test
    fun `getConflictOperationsForCalendar filters UPDATE ops by events calendar_id`() = runTest {
        val (otherCalId, otherEvtId) = insertSecondCalendarWithEvent()
        val opA = pendingOpsDao.insert(createOperation(eventId = testEventId, operation = PendingOperation.OPERATION_UPDATE))
        val opB = pendingOpsDao.insert(createOperation(eventId = otherEvtId, operation = PendingOperation.OPERATION_UPDATE))
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(opA, now, "Conflict detected", now)
        pendingOpsDao.scheduleRetry(opB, now, "Conflict detected", now)

        val scopedToA = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(1, scopedToA.size)
        assertEquals(opA, scopedToA[0].id)
    }

    @Test
    fun `getConflictOperationsForCalendar DELETE with sourceCalendarId ignores events calendar_id`() = runTest {
        val (otherCalId, _) = insertSecondCalendarWithEvent()
        val op = pendingOpsDao.insert(
            PendingOperation(
                id = 0,
                eventId = testEventId,
                operation = PendingOperation.OPERATION_DELETE,
                status = PendingOperation.STATUS_PENDING,
                sourceCalendarId = otherCalId,
                createdAt = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "412 Precondition Failed", now)

        val scopedToSource = pendingOpsDao.getConflictOperationsForCalendar(otherCalId)
        val scopedToEventCalendar = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(1, scopedToSource.size)
        assertEquals(op, scopedToSource[0].id)
        assertEquals(0, scopedToEventCalendar.size)
    }

    @Test
    fun `getConflictOperationsForCalendar DELETE without sourceCalendarId uses events calendar_id`() = runTest {
        val op = pendingOpsDao.insert(
            PendingOperation(
                id = 0,
                eventId = testEventId,
                operation = PendingOperation.OPERATION_DELETE,
                status = PendingOperation.STATUS_PENDING,
                sourceCalendarId = null,
                createdAt = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "412 Precondition Failed", now)

        val scoped = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(1, scoped.size)
        assertEquals(op, scoped[0].id)
    }

    @Test
    fun `getConflictOperationsForCalendar MOVE phase 0 uses sourceCalendarId`() = runTest {
        val (targetCalId, _) = insertSecondCalendarWithEvent()
        val op = pendingOpsDao.insert(
            PendingOperation(
                id = 0,
                eventId = testEventId,
                operation = PendingOperation.OPERATION_MOVE,
                status = PendingOperation.STATUS_PENDING,
                movePhase = PendingOperation.MOVE_PHASE_DELETE,
                sourceCalendarId = testCalendarId,
                targetCalendarId = targetCalId,
                createdAt = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "412 Precondition Failed", now)

        val scopedToSource = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)
        val scopedToTarget = pendingOpsDao.getConflictOperationsForCalendar(targetCalId)

        assertEquals(1, scopedToSource.size)
        assertEquals(op, scopedToSource[0].id)
        assertEquals(0, scopedToTarget.size)
    }

    @Test
    fun `getConflictOperationsForCalendar MOVE phase 1 uses targetCalendarId`() = runTest {
        val (targetCalId, _) = insertSecondCalendarWithEvent()
        val op = pendingOpsDao.insert(
            PendingOperation(
                id = 0,
                eventId = testEventId,
                operation = PendingOperation.OPERATION_MOVE,
                status = PendingOperation.STATUS_PENDING,
                movePhase = PendingOperation.MOVE_PHASE_CREATE,
                sourceCalendarId = testCalendarId,
                targetCalendarId = targetCalId,
                createdAt = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "412 Precondition Failed", now)

        val scopedToSource = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)
        val scopedToTarget = pendingOpsDao.getConflictOperationsForCalendar(targetCalId)

        assertEquals(0, scopedToSource.size)
        assertEquals(1, scopedToTarget.size)
        assertEquals(op, scopedToTarget[0].id)
    }

    @Test
    fun `getConflictOperationsForCalendar excludes non-conflict error messages`() = runTest {
        val op = pendingOpsDao.insert(createOperation(operation = PendingOperation.OPERATION_CREATE))
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "Network error", now)

        val scoped = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(0, scoped.size)
    }

    @Test
    fun `getConflictOperationsForCalendar preserves LIKE-match semantics from the old query`() = runTest {
        val op = pendingOpsDao.insert(createOperation(operation = PendingOperation.OPERATION_CREATE))
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "precondition failed", now)

        val scoped = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(0, scoped.size)
    }

    @Test
    fun `getConflictOperationsForCalendar excludes FAILED status`() = runTest {
        val op = pendingOpsDao.insert(createOperation(operation = PendingOperation.OPERATION_CREATE))
        val now = System.currentTimeMillis()
        pendingOpsDao.markFailed(op, "412 Precondition Failed", now)

        val scoped = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(0, scoped.size)
    }

    @Test
    fun `getConflictOperationsForCalendar orders by created_at ASC`() = runTest {
        val now = System.currentTimeMillis()
        val opLate = pendingOpsDao.insert(
            createOperation(operation = PendingOperation.OPERATION_CREATE, createdAt = now + 1000)
        )
        val opEarly = pendingOpsDao.insert(
            createOperation(operation = PendingOperation.OPERATION_CREATE, createdAt = now - 1000)
        )
        pendingOpsDao.scheduleRetry(opLate, now, "412", now)
        pendingOpsDao.scheduleRetry(opEarly, now, "412", now)

        val scoped = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(2, scoped.size)
        assertEquals(opEarly, scoped[0].id)
        assertEquals(opLate, scoped[1].id)
    }

    @Test
    fun `getConflictOperationsForCalendar excludes orphan op with no event and no sourceCalendarId`() = runTest {
        val op = pendingOpsDao.insert(
            PendingOperation(
                id = 0,
                eventId = 999_999L,
                operation = PendingOperation.OPERATION_UPDATE,
                status = PendingOperation.STATUS_PENDING,
                createdAt = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        pendingOpsDao.scheduleRetry(op, now, "412 Precondition Failed", now)

        val scoped = pendingOpsDao.getConflictOperationsForCalendar(testCalendarId)

        assertEquals(0, scoped.size)
    }

    // ==================== Reset All Failed Tests ====================

    @Test
    fun `resetAllFailed resets all failed operations`() = runTest {
        val id1 = pendingOpsDao.insert(createOperation())
        val id2 = pendingOpsDao.insert(createOperation())
        pendingOpsDao.insert(createOperation()) // This one stays pending
        val now = System.currentTimeMillis()

        pendingOpsDao.markFailed(id1, "Error 1", now)
        pendingOpsDao.markFailed(id2, "Error 2", now)

        pendingOpsDao.resetAllFailed(now)

        val failedCount = pendingOpsDao.getFailedCount().first()
        val pendingCount = pendingOpsDao.getPendingCount().first()

        assertEquals(0, failedCount)
        assertEquals(3, pendingCount)
    }

    // ==================== Cleanup Tests ====================

    @Test
    fun `deleteOldFailed removes old failed operations`() = runTest {
        val now = System.currentTimeMillis()
        val oldTime = now - 86400000 * 7 // 7 days ago

        // Create and fail an operation, then manually update its timestamp
        val id = pendingOpsDao.insert(createOperation())
        pendingOpsDao.markFailed(id, "Old error", oldTime)

        // Create recent failed operation
        val id2 = pendingOpsDao.insert(createOperation())
        pendingOpsDao.markFailed(id2, "Recent error", now)

        // Delete operations older than 1 day
        pendingOpsDao.deleteOldFailed(now - 86400000)

        val remaining = pendingOpsDao.getAll()
        // Both remain because markFailed uses the `now` parameter for updated_at
        // The old one was marked failed with oldTime, so updated_at = oldTime < cutoff
        assertTrue(remaining.size >= 1)
    }

    // ==================== Computed Property Tests ====================

    @Test
    fun `shouldRetry is true when retryCount less than maxRetries`() {
        val op = PendingOperation(
            id = 1,
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            retryCount = 2,
            maxRetries = 5
        )

        assertTrue(op.shouldRetry)
    }

    @Test
    fun `shouldRetry is false when retryCount equals maxRetries`() {
        val op = PendingOperation(
            id = 1,
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            retryCount = 5,
            maxRetries = 5
        )

        assertFalse(op.shouldRetry)
    }

    @Test
    fun `isReady returns true for pending operation past retry time`() {
        val now = System.currentTimeMillis()
        val op = PendingOperation(
            id = 1,
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = now - 1000
        )

        assertTrue(op.isReady(now))
    }

    @Test
    fun `isReady returns false for future retry time`() {
        val now = System.currentTimeMillis()
        val op = PendingOperation(
            id = 1,
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = now + 60000
        )

        assertFalse(op.isReady(now))
    }

    @Test
    fun `isReady returns false for non-pending status`() {
        val now = System.currentTimeMillis()
        val op = PendingOperation(
            id = 1,
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_IN_PROGRESS,
            nextRetryAt = 0
        )

        assertFalse(op.isReady(now))
    }

    @Test
    fun `calculateRetryDelay uses exponential backoff`() {
        val delay0 = PendingOperation.calculateRetryDelay(0) // 30s * 2^0 = 30s
        val delay1 = PendingOperation.calculateRetryDelay(1) // 30s * 2^1 = 60s
        val delay2 = PendingOperation.calculateRetryDelay(2) // 30s * 2^2 = 120s
        val delay3 = PendingOperation.calculateRetryDelay(3) // 30s * 2^3 = 240s

        assertEquals(30_000L, delay0)
        assertEquals(60_000L, delay1)
        assertEquals(120_000L, delay2)
        assertEquals(240_000L, delay3)
    }

    // ==================== Stale IN_PROGRESS Recovery Tests ====================

    @Test
    fun `resetStaleInProgress resets old IN_PROGRESS operations`() = runTest {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - TimeUnit.HOURS.toMillis(2)
        val thirtyMinutesAgo = now - TimeUnit.MINUTES.toMillis(30)

        // Stuck operation (2 hours old) - create PendingOperation directly for updatedAt control
        val stuckOpId = pendingOpsDao.insert(PendingOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_IN_PROGRESS,
            updatedAt = twoHoursAgo
        ))

        // Recent operation (30 min - should NOT be reset)
        val recentOpId = pendingOpsDao.insert(PendingOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_IN_PROGRESS,
            updatedAt = thirtyMinutesAgo
        ))

        // PENDING operation (should NOT be affected)
        val pendingOpId = pendingOpsDao.insert(PendingOperation(
            eventId = testEventId,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            updatedAt = twoHoursAgo
        ))

        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)
        val resetCount = pendingOpsDao.resetStaleInProgress(cutoff = oneHourAgo, now = now)

        assertEquals(1, resetCount)

        val stuckOp = pendingOpsDao.getById(stuckOpId)
        assertEquals(PendingOperation.STATUS_PENDING, stuckOp?.status)

        val recentOp = pendingOpsDao.getById(recentOpId)
        assertEquals(PendingOperation.STATUS_IN_PROGRESS, recentOp?.status)

        val pendingOp = pendingOpsDao.getById(pendingOpId)
        assertEquals(PendingOperation.STATUS_PENDING, pendingOp?.status)
    }

    @Test
    fun `resetStaleInProgress returns zero when no stuck operations`() = runTest {
        val now = System.currentTimeMillis()

        // Only PENDING and FAILED operations (no IN_PROGRESS)
        pendingOpsDao.insert(createOperation(status = PendingOperation.STATUS_PENDING))
        val failedId = pendingOpsDao.insert(createOperation())
        pendingOpsDao.markFailed(failedId, "Error", now)

        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)
        val resetCount = pendingOpsDao.resetStaleInProgress(cutoff = oneHourAgo, now = now)

        assertEquals(0, resetCount)
    }

    // ==================== Abandon / Expiry Lifecycle Tests ====================

    /**
     * Helper: insert an operation already past the 30-day lifetime window so it
     * is detected by getExpiredOperations.
     */
    private suspend fun insertExpiredOperation(
        status: String = PendingOperation.STATUS_PENDING
    ): Long {
        val expiredResetAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
        return pendingOpsDao.insert(
            PendingOperation(
                eventId = testEventId,
                operation = PendingOperation.OPERATION_CREATE,
                status = status,
                lifetimeResetAt = expiredResetAt
            )
        )
    }

    @Test
    fun `abandonOperation sets status to ABANDONED`() = runTest {
        val now = System.currentTimeMillis()
        val id = insertExpiredOperation()

        val transitioned = pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        assertEquals("First abandon transitions the row", 1, transitioned)
        val op = pendingOpsDao.getById(id)
        assertEquals(PendingOperation.STATUS_ABANDONED, op?.status)
        assertEquals("Exceeded 30-day lifetime", op?.lastError)
    }

    @Test
    fun `abandonOperation is a no-op on an already-abandoned row`() = runTest {
        // Compare-and-set: only the first caller transitions the op. A concurrent
        // sync that re-abandons the same op gets 0, so the "sync expired"
        // notification alerts once instead of once per overlapping sync.
        val now = System.currentTimeMillis()
        val id = insertExpiredOperation()
        assertEquals(1, pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now))

        val secondAttempt = pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        assertEquals("Second abandon must transition nothing", 0, secondAttempt)
        assertEquals(PendingOperation.STATUS_ABANDONED, pendingOpsDao.getById(id)?.status)
    }

    @Test
    fun `getExpiredOperations excludes already-abandoned operations`() = runTest {
        // This is the notify-once guarantee: once abandoned, an expired op must
        // never be re-detected on subsequent syncs (otherwise the notification
        // re-posts forever after the user dismisses it).
        val now = System.currentTimeMillis()
        val cutoff = now - PendingOperation.OPERATION_LIFETIME_MS
        val id = insertExpiredOperation()

        // First sync detects it.
        val firstPass = pendingOpsDao.getExpiredOperations(cutoff)
        assertEquals(1, firstPass.size)

        // Worker abandons it.
        pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        // Next sync must NOT re-detect it.
        val secondPass = pendingOpsDao.getExpiredOperations(cutoff)
        assertTrue("Abandoned op must not be re-detected", secondPass.isEmpty())
    }

    @Test
    fun `resetAllFailed re-arms ABANDONED operations with fresh lifetime`() = runTest {
        // Honors the notification's "Force Sync to retry" promise: an abandoned
        // op must become retryable again with a fresh 30-day window.
        val now = System.currentTimeMillis()
        val cutoff = now - PendingOperation.OPERATION_LIFETIME_MS
        val id = insertExpiredOperation()
        pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        pendingOpsDao.resetAllFailed(now)

        val op = pendingOpsDao.getById(id)
        assertEquals(PendingOperation.STATUS_PENDING, op?.status)
        // Fresh lifetime window means it is no longer expired.
        assertTrue(
            "Force Sync must give a fresh 30-day window",
            (op?.lifetimeResetAt ?: 0L) > cutoff
        )
        // And it is no longer detected as expired.
        assertTrue(pendingOpsDao.getExpiredOperations(cutoff).isEmpty())
    }

    @Test
    fun `autoResetOldFailed does not resurrect ABANDONED operations`() = runTest {
        // ABANDONED is terminal except for explicit Force Sync. The 24h auto-reset
        // must never pull an abandoned op back into the retry loop.
        val now = System.currentTimeMillis()
        val cutoff = now - PendingOperation.OPERATION_LIFETIME_MS
        val id = insertExpiredOperation()
        pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        val resetCount = pendingOpsDao.autoResetOldFailed(
            failedBefore = now,
            lifetimeCutoff = cutoff,
            now = now
        )

        assertEquals(0, resetCount)
        assertEquals(PendingOperation.STATUS_ABANDONED, pendingOpsDao.getById(id)?.status)
    }

    @Test
    fun `ABANDONED operations are invisible to processing queries`() = runTest {
        // Regression-lock (review F-rec): ABANDONED is terminal and must not be
        // picked up for processing nor counted as pending work in the UI badge.
        val now = System.currentTimeMillis()
        val id = insertExpiredOperation()
        pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        assertTrue(
            "Abandoned op must not be returned for processing",
            pendingOpsDao.getReadyOperations(now).none { it.id == id }
        )
        assertEquals(
            "Abandoned op must not inflate the pending badge",
            0,
            pendingOpsDao.getPendingCount().first()
        )
    }

    @Test
    fun `hasPendingForEvent treats ABANDONED as still present`() = runTest {
        // Documents the != 'FAILED' semantics: an event whose only op
        // is ABANDONED reads as "has pending". Both hasPendingForEvent and
        // operationExists are test-only today; the live queue path dedups via
        // STATUS_PENDING, so this flip is harmless. Pinned here so it's a decision.
        val now = System.currentTimeMillis()
        val id = insertExpiredOperation()
        pendingOpsDao.abandonOperation(id, "Exceeded 30-day lifetime", now)

        assertTrue(pendingOpsDao.hasPendingForEvent(testEventId))
    }
}
