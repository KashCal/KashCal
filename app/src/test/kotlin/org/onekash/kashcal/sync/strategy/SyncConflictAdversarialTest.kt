package org.onekash.kashcal.sync.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.PendingOperation

/**
 * Adversarial tests for sync conflict handling and PendingOperation queue.
 *
 * Tests probe edge cases:
 * - Retry exhaustion
 * - Exponential backoff limits
 * - Operation state machine violations
 * - ConflictResult handling
 * - Concurrent operation scenarios
 *
 * These tests verify defensive coding in sync infrastructure.
 */
class SyncConflictAdversarialTest {

    // ==================== PendingOperation State Tests ====================

    @Test
    fun `shouldRetry is true when retryCount less than maxRetries`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            retryCount = 2,
            maxRetries = 5
        )

        assertTrue(op.shouldRetry)
    }

    @Test
    fun `shouldRetry is false when retryCount equals maxRetries`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            retryCount = 5,
            maxRetries = 5
        )

        assertFalse(op.shouldRetry)
    }

    @Test
    fun `shouldRetry is false when retryCount exceeds maxRetries`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            retryCount = 10,
            maxRetries = 5
        )

        assertFalse(op.shouldRetry)
    }

    @Test
    fun `isReady returns true when status is PENDING and time has passed`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = 1000L
        )

        assertTrue(op.isReady(2000L))
    }

    @Test
    fun `isReady returns false when status is PENDING but time not reached`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = 5000L
        )

        assertFalse(op.isReady(2000L))
    }

    @Test
    fun `isReady returns false when status is IN_PROGRESS`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_IN_PROGRESS,
            nextRetryAt = 0L
        )

        assertFalse(op.isReady(System.currentTimeMillis()))
    }

    @Test
    fun `isReady returns false when status is FAILED`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_FAILED,
            nextRetryAt = 0L
        )

        assertFalse(op.isReady(System.currentTimeMillis()))
    }

    // ==================== Exponential Backoff Tests ====================

    @Test
    fun `calculateRetryDelay - first retry is 30 seconds`() {
        val delay = PendingOperation.calculateRetryDelay(0)
        assertEquals(30_000L, delay) // 30s * 2^0 = 30s
    }

    @Test
    fun `calculateRetryDelay - second retry is 60 seconds`() {
        val delay = PendingOperation.calculateRetryDelay(1)
        assertEquals(60_000L, delay) // 30s * 2^1 = 60s
    }

    @Test
    fun `calculateRetryDelay - third retry is 2 minutes`() {
        val delay = PendingOperation.calculateRetryDelay(2)
        assertEquals(120_000L, delay) // 30s * 2^2 = 120s
    }

    @Test
    fun `calculateRetryDelay - fifth retry is 8 minutes`() {
        val delay = PendingOperation.calculateRetryDelay(4)
        assertEquals(480_000L, delay) // 30s * 2^4 = 480s
    }

    @Test
    fun `calculateRetryDelay - caps at MAX_BACKOFF_MS (5 hours)`() {
        val delay10 = PendingOperation.calculateRetryDelay(10)
        val delay15 = PendingOperation.calculateRetryDelay(15)
        val delay100 = PendingOperation.calculateRetryDelay(100)

        // All should be capped at 5 hours (MAX_BACKOFF_MS) per v21.5.3
        assertEquals(PendingOperation.MAX_BACKOFF_MS, delay10)
        assertEquals(delay10, delay15)
        assertEquals(delay10, delay100)
    }

    @Test
    fun `calculateRetryDelay - handles negative retryCount gracefully`() {
        // Negative retryCount is coerced to 0, returning base delay (30s)
        // This prevents undefined bit-shift behavior and immediate retry loops
        val delay = PendingOperation.calculateRetryDelay(-1)
        assertEquals(30_000L, delay) // Same as retryCount = 0
    }

    @Test
    fun `calculateRetryDelay - maximum delay is 5 hours`() {
        val maxDelay = PendingOperation.calculateRetryDelay(10)
        // v21.5.3: Cap at 5 hours (Android WorkManager standard)
        assertEquals(5L * 60 * 60 * 1000, maxDelay)
    }

    // ==================== Operation Type Tests ====================

    @Test
    fun `all operation types are defined`() {
        assertEquals("CREATE", PendingOperation.OPERATION_CREATE)
        assertEquals("UPDATE", PendingOperation.OPERATION_UPDATE)
        assertEquals("DELETE", PendingOperation.OPERATION_DELETE)
        assertEquals("MOVE", PendingOperation.OPERATION_MOVE)
    }

    @Test
    fun `all status types are defined`() {
        assertEquals("PENDING", PendingOperation.STATUS_PENDING)
        assertEquals("IN_PROGRESS", PendingOperation.STATUS_IN_PROGRESS)
        assertEquals("FAILED", PendingOperation.STATUS_FAILED)
    }

    @Test
    fun `MOVE operation stores target context`() {
        val moveOp = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://old-calendar.com/event.ics",
            targetCalendarId = 42L
        )

        assertEquals("https://old-calendar.com/event.ics", moveOp.targetUrl)
        assertEquals(42L, moveOp.targetCalendarId)
    }

    // ==================== ConflictStrategy Tests ====================

    @Test
    fun `ConflictStrategy has all expected values`() {
        val strategies = ConflictStrategy.values()

        assertTrue(strategies.contains(ConflictStrategy.SERVER_WINS))
        assertTrue(strategies.contains(ConflictStrategy.LOCAL_WINS))
        assertTrue(strategies.contains(ConflictStrategy.NEWEST_WINS))
        assertTrue(strategies.contains(ConflictStrategy.MANUAL))
    }

    @Test
    fun `ConflictStrategy count is 4`() {
        assertEquals(4, ConflictStrategy.values().size)
    }

    // ==================== ConflictResult Tests ====================

    @Test
    fun `ConflictResult ServerVersionKept is success`() {
        val result = ConflictResult.ServerVersionKept
        assertTrue(result.isSuccess())
    }

    @Test
    fun `ConflictResult LocalVersionPushed is success`() {
        val result = ConflictResult.LocalVersionPushed
        assertTrue(result.isSuccess())
    }

    @Test
    fun `ConflictResult LocalDeleted is success`() {
        val result = ConflictResult.LocalDeleted
        assertTrue(result.isSuccess())
    }

    @Test
    fun `ConflictResult MarkedForManualResolution is success`() {
        val result = ConflictResult.MarkedForManualResolution
        assertTrue(result.isSuccess())
    }

    @Test
    fun `ConflictResult EventNotFound is not success`() {
        val result = ConflictResult.EventNotFound
        assertFalse(result.isSuccess())
    }

    @Test
    fun `ConflictResult Error is not success`() {
        val result = ConflictResult.Error("Network failed")
        assertFalse(result.isSuccess())
    }

    @Test
    fun `ConflictResult Error preserves message`() {
        val message = "Connection timeout after 30 seconds"
        val result = ConflictResult.Error(message)

        assertTrue(result is ConflictResult.Error)
        assertEquals(message, (result as ConflictResult.Error).message)
    }

    // ==================== Operation Priority Tests ====================

    @Test
    fun `operations should process in FIFO order by createdAt`() {
        val op1 = PendingOperation(eventId = 1L, operation = "CREATE", createdAt = 1000L)
        val op2 = PendingOperation(eventId = 2L, operation = "UPDATE", createdAt = 2000L)
        val op3 = PendingOperation(eventId = 3L, operation = "DELETE", createdAt = 3000L)

        val operations = listOf(op3, op1, op2)
        val sorted = operations.sortedBy { it.createdAt }

        assertEquals(1L, sorted[0].eventId)
        assertEquals(2L, sorted[1].eventId)
        assertEquals(3L, sorted[2].eventId)
    }

    @Test
    fun `operations can be filtered by status`() {
        val ops = listOf(
            PendingOperation(eventId = 1L, operation = "CREATE", status = "PENDING"),
            PendingOperation(eventId = 2L, operation = "UPDATE", status = "IN_PROGRESS"),
            PendingOperation(eventId = 3L, operation = "DELETE", status = "FAILED"),
            PendingOperation(eventId = 4L, operation = "UPDATE", status = "PENDING")
        )

        val pending = ops.filter { it.status == PendingOperation.STATUS_PENDING }
        assertEquals(2, pending.size)
    }

    // ==================== Edge Case: Zero/Negative Values ====================

    @Test
    fun `PendingOperation with zero eventId is valid`() {
        // eventId is not a FK, so 0 is technically valid (though unusual)
        val op = PendingOperation(
            eventId = 0L,
            operation = PendingOperation.OPERATION_DELETE
        )

        assertEquals(0L, op.eventId)
    }

    @Test
    fun `PendingOperation with zero maxRetries means no retries`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            retryCount = 0,
            maxRetries = 0
        )

        assertFalse("Should not retry with maxRetries=0", op.shouldRetry)
    }

    @Test
    fun `PendingOperation with negative nextRetryAt is always ready`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = -1000L
        )

        assertTrue(op.isReady(0L))
    }

    // ==================== Operation Coalescing Logic Tests ====================

    @Test
    fun `CREATE then DELETE can be coalesced to nothing`() {
        val ops = listOf(
            PendingOperation(eventId = 1L, operation = "CREATE", createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = "DELETE", createdAt = 2000L)
        )

        // Coalescing logic: CREATE + DELETE for same eventId = skip both
        val coalesced = ops
            .groupBy { it.eventId }
            .filter { (_, eventOps) ->
                val hasCreate = eventOps.any { it.operation == "CREATE" }
                val hasDelete = eventOps.any { it.operation == "DELETE" }
                !(hasCreate && hasDelete)
            }
            .flatMap { it.value }

        assertTrue("CREATE+DELETE should cancel out", coalesced.isEmpty())
    }

    @Test
    fun `CREATE then UPDATE coalesces to CREATE`() {
        val ops = listOf(
            PendingOperation(eventId = 1L, operation = "CREATE", createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = "UPDATE", createdAt = 2000L)
        )

        // Coalescing: CREATE + UPDATE = CREATE (latest data already in event)
        val coalesced = ops
            .groupBy { it.eventId }
            .map { (_, eventOps) ->
                if (eventOps.any { it.operation == "CREATE" }) {
                    eventOps.first { it.operation == "CREATE" }
                } else {
                    eventOps.maxByOrNull { it.createdAt }!!
                }
            }

        assertEquals(1, coalesced.size)
        assertEquals("CREATE", coalesced[0].operation)
    }

    @Test
    fun `multiple UPDATEs coalesce to single UPDATE`() {
        val ops = listOf(
            PendingOperation(eventId = 1L, operation = "UPDATE", createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = "UPDATE", createdAt = 2000L),
            PendingOperation(eventId = 1L, operation = "UPDATE", createdAt = 3000L)
        )

        // Keep only latest UPDATE
        val coalesced = ops
            .groupBy { it.eventId }
            .map { (_, eventOps) -> eventOps.maxByOrNull { it.createdAt }!! }

        assertEquals(1, coalesced.size)
        assertEquals(3000L, coalesced[0].createdAt)
    }

    @Test
    fun `UPDATE then DELETE coalesces to DELETE`() {
        val ops = listOf(
            PendingOperation(eventId = 1L, operation = "UPDATE", createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = "DELETE", createdAt = 2000L)
        )

        // UPDATE + DELETE = DELETE (no need to update before delete)
        val coalesced = ops
            .groupBy { it.eventId }
            .map { (_, eventOps) ->
                if (eventOps.any { it.operation == "DELETE" }) {
                    eventOps.first { it.operation == "DELETE" }
                } else {
                    eventOps.maxByOrNull { it.createdAt }!!
                }
            }

        assertEquals(1, coalesced.size)
        assertEquals("DELETE", coalesced[0].operation)
    }

    // ==================== Error Message Tests ====================

    @Test
    fun `lastError stores failure reason`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            lastError = "412 Precondition Failed: ETag mismatch"
        )

        assertEquals("412 Precondition Failed: ETag mismatch", op.lastError)
    }

    @Test
    fun `lastError can be null for new operations`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE
        )

        assertEquals(null, op.lastError)
    }

    // ==================== Timestamp Tests ====================

    @Test
    fun `createdAt defaults to current time`() {
        val before = System.currentTimeMillis()
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE
        )
        val after = System.currentTimeMillis()

        assertTrue(op.createdAt >= before)
        assertTrue(op.createdAt <= after)
    }

    @Test
    fun `updatedAt defaults to current time`() {
        val before = System.currentTimeMillis()
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE
        )
        val after = System.currentTimeMillis()

        assertTrue(op.updatedAt >= before)
        assertTrue(op.updatedAt <= after)
    }

    @Test
    fun `createdAt and updatedAt are independent`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        assertNotEquals(op.createdAt, op.updatedAt)
    }
}