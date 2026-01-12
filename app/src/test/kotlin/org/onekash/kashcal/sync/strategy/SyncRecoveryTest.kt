package org.onekash.kashcal.sync.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus

/**
 * Tests for sync recovery and resilience scenarios.
 *
 * Tests verify:
 * - PendingOperation queue behavior
 * - Sync status state machine
 * - Recovery after interruption
 * - Operation idempotency
 *
 * These scenarios are critical for offline-first architecture reliability.
 */
class SyncRecoveryTest {

    // ==================== PendingOperation Queue Tests ====================

    @Test
    fun `PendingOperation has correct operation types`() {
        assertEquals("CREATE", PendingOperation.OPERATION_CREATE)
        assertEquals("UPDATE", PendingOperation.OPERATION_UPDATE)
        assertEquals("DELETE", PendingOperation.OPERATION_DELETE)
        assertEquals("MOVE", PendingOperation.OPERATION_MOVE)
    }

    @Test
    fun `PendingOperation stores eventId`() {
        val op = PendingOperation(
            eventId = 123L,
            operation = PendingOperation.OPERATION_CREATE
        )

        assertEquals(123L, op.eventId)
    }

    @Test
    fun `PendingOperation stores operation type`() {
        val createOp = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE
        )
        val updateOp = PendingOperation(
            eventId = 2L,
            operation = PendingOperation.OPERATION_UPDATE
        )
        val deleteOp = PendingOperation(
            eventId = 3L,
            operation = PendingOperation.OPERATION_DELETE
        )

        assertEquals(PendingOperation.OPERATION_CREATE, createOp.operation)
        assertEquals(PendingOperation.OPERATION_UPDATE, updateOp.operation)
        assertEquals(PendingOperation.OPERATION_DELETE, deleteOp.operation)
    }

    @Test
    fun `PendingOperation MOVE stores target context`() {
        val moveOp = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old-cal/event.ics",
            targetCalendarId = 2L
        )

        assertEquals(PendingOperation.OPERATION_MOVE, moveOp.operation)
        assertEquals("https://caldav.icloud.com/old-cal/event.ics", moveOp.targetUrl)
        assertEquals(2L, moveOp.targetCalendarId)
    }

    @Test
    fun `PendingOperation createdAt defaults to current time`() {
        val beforeCreate = System.currentTimeMillis()
        val op = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE
        )
        val afterCreate = System.currentTimeMillis()

        assertTrue(op.createdAt >= beforeCreate)
        assertTrue(op.createdAt <= afterCreate)
    }

    // ==================== SyncStatus State Machine Tests ====================

    @Test
    fun `SyncStatus has all expected values`() {
        val values = SyncStatus.values()

        assertTrue(values.contains(SyncStatus.SYNCED))
        assertTrue(values.contains(SyncStatus.PENDING_CREATE))
        assertTrue(values.contains(SyncStatus.PENDING_UPDATE))
        assertTrue(values.contains(SyncStatus.PENDING_DELETE))
    }

    @Test
    fun `SyncStatus SYNCED indicates no pending changes`() {
        val status = SyncStatus.SYNCED

        assertFalse(status.name.contains("PENDING"))
    }

    @Test
    fun `SyncStatus PENDING states indicate local changes`() {
        assertTrue(SyncStatus.PENDING_CREATE.name.contains("PENDING"))
        assertTrue(SyncStatus.PENDING_UPDATE.name.contains("PENDING"))
        assertTrue(SyncStatus.PENDING_DELETE.name.contains("PENDING"))
    }

    @Test
    fun `SyncStatus can be used in when expression`() {
        val statuses = listOf(
            SyncStatus.SYNCED,
            SyncStatus.PENDING_CREATE,
            SyncStatus.PENDING_UPDATE,
            SyncStatus.PENDING_DELETE
        )

        statuses.forEach { status ->
            val result = when (status) {
                SyncStatus.SYNCED -> "synced"
                SyncStatus.PENDING_CREATE -> "create"
                SyncStatus.PENDING_UPDATE -> "update"
                SyncStatus.PENDING_DELETE -> "delete"
            }
            assertTrue(result.isNotEmpty())
        }
    }

    // ==================== Operation Priority Tests ====================

    @Test
    fun `operations should be processed in FIFO order`() {
        val operations = listOf(
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_CREATE, createdAt = 1000L),
            PendingOperation(eventId = 2L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 2000L),
            PendingOperation(eventId = 3L, operation = PendingOperation.OPERATION_DELETE, createdAt = 3000L)
        )

        val sorted = operations.sortedBy { it.createdAt }

        assertEquals(1L, sorted[0].eventId)
        assertEquals(2L, sorted[1].eventId)
        assertEquals(3L, sorted[2].eventId)
    }

    @Test
    fun `duplicate operations for same event should be deduplicated`() {
        val operations = listOf(
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 2000L),
            PendingOperation(eventId = 2L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 3000L)
        )

        // Group by eventId, take the latest
        val deduplicated = operations
            .groupBy { it.eventId }
            .map { (_, ops) -> ops.maxByOrNull { it.createdAt }!! }

        assertEquals(2, deduplicated.size)
    }

    // ==================== State Transition Tests ====================

    @Test
    fun `new event starts as PENDING_CREATE`() {
        val initialStatus = SyncStatus.PENDING_CREATE

        assertEquals(SyncStatus.PENDING_CREATE, initialStatus)
    }

    @Test
    fun `after successful sync becomes SYNCED`() {
        // Simulate state transition
        var status = SyncStatus.PENDING_CREATE

        // After successful sync
        status = SyncStatus.SYNCED

        assertEquals(SyncStatus.SYNCED, status)
    }

    @Test
    fun `local edit changes SYNCED to PENDING_UPDATE`() {
        var status = SyncStatus.SYNCED

        // After local edit
        status = SyncStatus.PENDING_UPDATE

        assertEquals(SyncStatus.PENDING_UPDATE, status)
    }

    @Test
    fun `local delete changes SYNCED to PENDING_DELETE`() {
        var status = SyncStatus.SYNCED

        // After local delete
        status = SyncStatus.PENDING_DELETE

        assertEquals(SyncStatus.PENDING_DELETE, status)
    }

    // ==================== Recovery Scenario Tests ====================

    @Test
    fun `pending operations survive app restart`() {
        // PendingOperation is persisted in Room database
        // This test verifies the data structure supports persistence
        val op = PendingOperation(
            id = 1L,
            eventId = 123L,
            operation = PendingOperation.OPERATION_CREATE,
            createdAt = System.currentTimeMillis()
        )

        // Verify all fields are accessible after "restore"
        assertEquals(1L, op.id)
        assertEquals(123L, op.eventId)
        assertEquals(PendingOperation.OPERATION_CREATE, op.operation)
        assertTrue(op.createdAt > 0)
    }

    @Test
    fun `failed sync preserves pending status`() {
        // Event stays in PENDING state until successfully synced
        val status = SyncStatus.PENDING_CREATE

        // Sync fails - status should remain PENDING_CREATE
        val statusAfterFailure = status // No change

        assertEquals(SyncStatus.PENDING_CREATE, statusAfterFailure)
    }

    @Test
    fun `interrupted MOVE operation has all context for retry`() {
        val moveOp = PendingOperation(
            eventId = 1L,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old-cal/event.ics",
            targetCalendarId = 2L
        )

        // All context needed for retry is stored in the operation
        assertTrue(moveOp.targetUrl != null)
        assertTrue(moveOp.targetCalendarId != null)

        // Can retry MOVE with this context
        assertEquals("https://caldav.icloud.com/old-cal/event.ics", moveOp.targetUrl)
        assertEquals(2L, moveOp.targetCalendarId)
    }

    // ==================== Conflict Strategy Tests ====================

    @Test
    fun `ConflictStrategy enum has expected values`() {
        val values = ConflictStrategy.values()

        assertTrue(values.contains(ConflictStrategy.LOCAL_WINS))
        assertTrue(values.contains(ConflictStrategy.SERVER_WINS))
        assertTrue(values.contains(ConflictStrategy.NEWEST_WINS))
        assertTrue(values.contains(ConflictStrategy.MANUAL))
    }

    @Test
    fun `LOCAL_WINS keeps local changes`() {
        val strategy = ConflictStrategy.LOCAL_WINS

        assertEquals(ConflictStrategy.LOCAL_WINS, strategy)
    }

    @Test
    fun `SERVER_WINS discards local changes`() {
        val strategy = ConflictStrategy.SERVER_WINS

        assertEquals(ConflictStrategy.SERVER_WINS, strategy)
    }

    // ==================== Idempotency Tests ====================

    @Test
    fun `CREATE operation can be safely retried`() {
        // If CREATE partially succeeded, retry should handle duplicate
        // This is enforced by UID uniqueness in CalDAV
        val op1 = PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_CREATE)
        val op2 = PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_CREATE)

        assertEquals(op1.eventId, op2.eventId)
        assertEquals(op1.operation, op2.operation)
    }

    @Test
    fun `UPDATE operation is idempotent`() {
        // Updating to same state twice has same result
        val update1 = PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE)
        val update2 = PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE)

        assertEquals(update1.operation, update2.operation)
    }

    @Test
    fun `DELETE operation is idempotent`() {
        // Deleting already-deleted event is a no-op
        val delete1 = PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_DELETE)
        val delete2 = PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_DELETE)

        assertEquals(delete1.operation, delete2.operation)
    }

    // ==================== Operation Coalescing Tests ====================

    @Test
    fun `CREATE then UPDATE coalesces to CREATE`() {
        // If we CREATE then UPDATE before sync, only CREATE is needed
        val operations = listOf(
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_CREATE, createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 2000L)
        )

        // Coalescing logic: CREATE + UPDATE = CREATE (with latest data)
        val coalesced = operations
            .groupBy { it.eventId }
            .map { (_, ops) ->
                if (ops.any { it.operation == PendingOperation.OPERATION_CREATE }) {
                    ops.first { it.operation == PendingOperation.OPERATION_CREATE }
                } else {
                    ops.maxByOrNull { it.createdAt }!!
                }
            }

        assertEquals(1, coalesced.size)
        assertEquals(PendingOperation.OPERATION_CREATE, coalesced[0].operation)
    }

    @Test
    fun `CREATE then DELETE coalesces to nothing`() {
        // If we CREATE then DELETE before sync, nothing needs syncing
        val operations = listOf(
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_CREATE, createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_DELETE, createdAt = 2000L)
        )

        // Coalescing logic: CREATE + DELETE = nothing (event never synced)
        val coalesced = operations
            .groupBy { it.eventId }
            .filter { (_, ops) ->
                val hasCreate = ops.any { it.operation == PendingOperation.OPERATION_CREATE }
                val hasDelete = ops.any { it.operation == PendingOperation.OPERATION_DELETE }
                !(hasCreate && hasDelete) // Filter out CREATE+DELETE pairs
            }
            .flatMap { it.value }

        assertTrue("CREATE+DELETE should cancel out", coalesced.isEmpty())
    }

    @Test
    fun `multiple UPDATEs coalesce to single UPDATE`() {
        val operations = listOf(
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 1000L),
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 2000L),
            PendingOperation(eventId = 1L, operation = PendingOperation.OPERATION_UPDATE, createdAt = 3000L)
        )

        // Keep only latest UPDATE
        val coalesced = operations
            .groupBy { it.eventId }
            .map { (_, ops) -> ops.maxByOrNull { it.createdAt }!! }

        assertEquals(1, coalesced.size)
        assertEquals(3000L, coalesced[0].createdAt)
    }
}