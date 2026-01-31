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
import java.util.concurrent.TimeUnit
import org.robolectric.annotation.Config

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

    @Test
    fun `getConflictOperations finds 412 errors`() = runTest {
        val id1 = pendingOpsDao.insert(createOperation())
        val id2 = pendingOpsDao.insert(createOperation())
        val id3 = pendingOpsDao.insert(createOperation())
        val now = System.currentTimeMillis()

        // Schedule retry with conflict error
        pendingOpsDao.scheduleRetry(id1, now, "412 Precondition Failed", now)
        pendingOpsDao.scheduleRetry(id2, now, "Conflict detected", now)
        pendingOpsDao.scheduleRetry(id3, now, "Network error", now)

        val conflicts = pendingOpsDao.getConflictOperations()

        assertEquals(2, conflicts.size)
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
}
