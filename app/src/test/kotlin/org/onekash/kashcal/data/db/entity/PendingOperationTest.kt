package org.onekash.kashcal.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PendingOperation entity.
 *
 * Tests computed properties and exponential backoff calculation.
 */
class PendingOperationTest {

    private fun createOperation(
        status: String = PendingOperation.STATUS_PENDING,
        retryCount: Int = 0,
        maxRetries: Int = 5,
        nextRetryAt: Long = 0
    ) = PendingOperation(
        id = 1L,
        eventId = 100L,
        operation = PendingOperation.OPERATION_CREATE,
        status = status,
        retryCount = retryCount,
        maxRetries = maxRetries,
        nextRetryAt = nextRetryAt
    )

    // ========== shouldRetry Tests ==========

    @Test
    fun `shouldRetry returns true when retryCount less than maxRetries`() {
        val op = createOperation(retryCount = 0, maxRetries = 5)
        assertTrue(op.shouldRetry)
    }

    @Test
    fun `shouldRetry returns true at boundary`() {
        val op = createOperation(retryCount = 4, maxRetries = 5)
        assertTrue(op.shouldRetry)
    }

    @Test
    fun `shouldRetry returns false when retryCount equals maxRetries`() {
        val op = createOperation(retryCount = 5, maxRetries = 5)
        assertFalse(op.shouldRetry)
    }

    @Test
    fun `shouldRetry returns false when retryCount exceeds maxRetries`() {
        val op = createOperation(retryCount = 10, maxRetries = 5)
        assertFalse(op.shouldRetry)
    }

    // ========== isReady Tests ==========

    @Test
    fun `isReady returns true when PENDING and time has passed`() {
        val op = createOperation(
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = 1000L
        )
        assertTrue(op.isReady(2000L))
    }

    @Test
    fun `isReady returns true when PENDING and time equals nextRetryAt`() {
        val op = createOperation(
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = 1000L
        )
        assertTrue(op.isReady(1000L))
    }

    @Test
    fun `isReady returns false when time before nextRetryAt`() {
        val op = createOperation(
            status = PendingOperation.STATUS_PENDING,
            nextRetryAt = 2000L
        )
        assertFalse(op.isReady(1000L))
    }

    @Test
    fun `isReady returns false when IN_PROGRESS`() {
        val op = createOperation(
            status = PendingOperation.STATUS_IN_PROGRESS,
            nextRetryAt = 0L
        )
        assertFalse(op.isReady(System.currentTimeMillis()))
    }

    @Test
    fun `isReady returns false when FAILED`() {
        val op = createOperation(
            status = PendingOperation.STATUS_FAILED,
            nextRetryAt = 0L
        )
        assertFalse(op.isReady(System.currentTimeMillis()))
    }

    // ========== calculateRetryDelay Tests ==========

    @Test
    fun `calculateRetryDelay returns 30 seconds for first retry`() {
        val delay = PendingOperation.calculateRetryDelay(0)
        assertEquals(30_000L, delay)
    }

    @Test
    fun `calculateRetryDelay returns 1 minute for second retry`() {
        val delay = PendingOperation.calculateRetryDelay(1)
        assertEquals(60_000L, delay)
    }

    @Test
    fun `calculateRetryDelay returns 2 minutes for third retry`() {
        val delay = PendingOperation.calculateRetryDelay(2)
        assertEquals(120_000L, delay)
    }

    @Test
    fun `calculateRetryDelay returns 4 minutes for fourth retry`() {
        val delay = PendingOperation.calculateRetryDelay(3)
        assertEquals(240_000L, delay)
    }

    @Test
    fun `calculateRetryDelay returns 8 minutes for fifth retry`() {
        val delay = PendingOperation.calculateRetryDelay(4)
        assertEquals(480_000L, delay)
    }

    @Test
    fun `calculateRetryDelay caps at 2^10 multiplier`() {
        // 30s * 2^10 = 30s * 1024 = 30,720s = 512 minutes
        val delayAt10 = PendingOperation.calculateRetryDelay(10)
        val delayAt15 = PendingOperation.calculateRetryDelay(15)
        assertEquals(delayAt10, delayAt15) // Both should be capped
    }

    @Test
    fun `calculateRetryDelay returns base delay for negative retryCount`() {
        // Negative retryCount should be treated as 0 (defensive coding)
        assertEquals(30_000L, PendingOperation.calculateRetryDelay(-1))
        assertEquals(30_000L, PendingOperation.calculateRetryDelay(Int.MIN_VALUE))
    }

    @Test
    fun `calculateRetryDelay never returns zero`() {
        // Ensure we never get a 0 delay that could cause immediate retry loops
        listOf(Int.MIN_VALUE, -1, 0, 1, 10, Int.MAX_VALUE).forEach { value ->
            assertTrue(
                "calculateRetryDelay($value) should be > 0",
                PendingOperation.calculateRetryDelay(value) > 0
            )
        }
    }

    // ========== Constants Tests ==========

    @Test
    fun `operation constants have correct values`() {
        assertEquals("CREATE", PendingOperation.OPERATION_CREATE)
        assertEquals("UPDATE", PendingOperation.OPERATION_UPDATE)
        assertEquals("DELETE", PendingOperation.OPERATION_DELETE)
    }

    @Test
    fun `status constants have correct values`() {
        assertEquals("PENDING", PendingOperation.STATUS_PENDING)
        assertEquals("IN_PROGRESS", PendingOperation.STATUS_IN_PROGRESS)
        assertEquals("FAILED", PendingOperation.STATUS_FAILED)
    }

    // ========== Default Value Tests ==========

    @Test
    fun `default values are correct`() {
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE
        )
        assertEquals(0L, op.id)
        assertEquals(PendingOperation.STATUS_PENDING, op.status)
        assertEquals(0, op.retryCount)
        assertEquals(5, op.maxRetries)
        assertEquals(0L, op.nextRetryAt)
        assertEquals(null, op.lastError)
    }
}
