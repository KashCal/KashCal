package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.PendingOperation

/**
 * Data Access Object for PendingOperation operations.
 *
 * Manages the offline-first sync queue.
 * Operations are processed FIFO by WorkManager with exponential backoff.
 */
@Dao
interface PendingOperationsDao {

    // ========== Read Operations ==========

    /**
     * Get all operations ready for processing.
     * Ready = PENDING status AND next_retry_at <= now.
     */
    @Query("""
        SELECT * FROM pending_operations
        WHERE status = 'PENDING'
        AND next_retry_at <= :now
        ORDER BY created_at ASC
    """)
    suspend fun getReadyOperations(now: Long): List<PendingOperation>

    /**
     * Get all pending operations (any status).
     */
    @Query("SELECT * FROM pending_operations ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingOperation>

    /**
     * Alias for getAll (consistent naming with other DAOs).
     */
    @Query("SELECT * FROM pending_operations ORDER BY created_at ASC")
    suspend fun getAllOnce(): List<PendingOperation>

    /**
     * Get pending operations for an event.
     */
    @Query("SELECT * FROM pending_operations WHERE event_id = :eventId ORDER BY created_at ASC")
    suspend fun getForEvent(eventId: Long): List<PendingOperation>

    /**
     * Get operation by ID.
     */
    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingOperation?

    /**
     * Get count of pending operations (for UI badge).
     */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    /**
     * Get count of failed operations.
     */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'FAILED'")
    fun getFailedCount(): Flow<Int>

    /**
     * Get total operation count.
     */
    @Query("SELECT COUNT(*) FROM pending_operations")
    suspend fun getTotalCount(): Int

    /**
     * Check if event has pending operations.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM pending_operations WHERE event_id = :eventId AND status != 'FAILED')")
    suspend fun hasPendingForEvent(eventId: Long): Boolean

    // ========== Write Operations ==========

    /**
     * Insert new operation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingOperation): Long

    /**
     * Update operation.
     */
    @Update
    suspend fun update(operation: PendingOperation)

    /**
     * Delete operation by ID.
     */
    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all operations for an event.
     */
    @Query("DELETE FROM pending_operations WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /**
     * Delete all completed/failed operations older than cutoff.
     */
    @Query("DELETE FROM pending_operations WHERE status = 'FAILED' AND updated_at < :cutoff")
    suspend fun deleteOldFailed(cutoff: Long)

    /**
     * Delete all operations (for testing/reset).
     */
    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()

    // ========== Status Updates ==========

    /**
     * Mark operation as in progress.
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'IN_PROGRESS',
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markInProgress(id: Long, now: Long)

    /**
     * Schedule retry with exponential backoff.
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'PENDING',
            retry_count = retry_count + 1,
            next_retry_at = :nextRetryAt,
            last_error = :error,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun scheduleRetry(id: Long, nextRetryAt: Long, error: String, now: Long)

    /**
     * Mark operation as permanently failed.
     * Sets failed_at for 24h auto-reset tracking (v21.5.3).
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'FAILED',
            last_error = :error,
            failed_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markFailed(id: Long, error: String, now: Long)

    /**
     * Reset operation to pending (for manual retry).
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun resetToPending(id: Long, now: Long)

    /**
     * Reset all failed operations (for Force Full Sync).
     * Also resets lifetime_reset_at to give fresh 30-day window (v21.5.3).
     *
     * @return Number of operations reset
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            failed_at = NULL,
            lifetime_reset_at = :now,
            updated_at = :now
        WHERE status = 'FAILED'
    """)
    suspend fun resetAllFailed(now: Long): Int

    /**
     * Reset operations stuck in IN_PROGRESS state back to PENDING.
     * Called at sync startup to recover from app crashes during processing.
     * Uses 1-hour timeout to avoid resetting legitimately active operations.
     *
     * @param cutoff Operations with updated_at before this are considered stuck
     * @param now Current timestamp for updated_at
     * @return Number of operations reset
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'PENDING',
            updated_at = :now
        WHERE status = 'IN_PROGRESS'
        AND updated_at < :cutoff
    """)
    suspend fun resetStaleInProgress(cutoff: Long, now: Long): Int

    /**
     * Advance MOVE operation to CREATE phase with fresh retry budget.
     *
     * Called after DELETE phase succeeds. Resets retry_count to 0 so CREATE
     * phase gets its own independent 5-retry budget.
     *
     * This prevents event loss when DELETE succeeds but CREATE fails:
     * - Before: DELETE succeeds at retry 4, CREATE fails, total retries = 5 → FAILED (event lost!)
     * - After: DELETE succeeds, CREATE starts at retry 0, has 5 more attempts
     */
    @Query("""
        UPDATE pending_operations
        SET move_phase = 1,
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            status = 'PENDING',
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun advanceToCreatePhase(id: Long, now: Long)

    // ========== Deduplication ==========

    /**
     * Check if operation exists for event with same type.
     * Used to prevent duplicate operations.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM pending_operations
            WHERE event_id = :eventId
            AND operation = :operation
            AND status != 'FAILED'
        )
    """)
    suspend fun operationExists(eventId: Long, operation: String): Boolean

    /**
     * Consolidate operations - if CREATE exists, remove UPDATE.
     * Returns number of deleted rows.
     */
    @Query("""
        DELETE FROM pending_operations
        WHERE event_id = :eventId
        AND operation = 'UPDATE'
        AND EXISTS(
            SELECT 1 FROM pending_operations
            WHERE event_id = :eventId AND operation = 'CREATE'
        )
    """)
    suspend fun consolidateOperations(eventId: Long): Int

    /**
     * Get operations in conflict state (412 errors).
     * These have last_error containing "Conflict" or "412".
     */
    @Query("""
        SELECT * FROM pending_operations
        WHERE status = 'PENDING'
        AND (last_error LIKE '%Conflict%' OR last_error LIKE '%412%')
        ORDER BY created_at ASC
    """)
    suspend fun getConflictOperations(): List<PendingOperation>

    // ========== Retry Lifecycle Methods (v21.5.3) ==========

    /**
     * Auto-reset FAILED operations older than 24 hours.
     * Uses failed_at column to measure time since failure, not updated_at.
     * Excludes operations that have exceeded 30-day lifetime.
     *
     * @param failedBefore Reset ops that failed before this timestamp
     * @param lifetimeCutoff Exclude ops with lifetime_reset_at before this (expired)
     * @param now Current timestamp for updated_at
     * @return Number of operations reset
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            failed_at = NULL,
            updated_at = :now
        WHERE status = 'FAILED'
        AND failed_at IS NOT NULL
        AND failed_at < :failedBefore
        AND lifetime_reset_at > :lifetimeCutoff
    """)
    suspend fun autoResetOldFailed(failedBefore: Long, lifetimeCutoff: Long, now: Long): Int

    /**
     * Find operations that have exceeded 30-day lifetime.
     * These should be abandoned to prevent infinite retry loops.
     *
     * @param cutoff Operations with lifetime_reset_at before this are expired
     * @return List of expired operations to abandon
     */
    @Query("""
        SELECT * FROM pending_operations
        WHERE status IN ('PENDING', 'FAILED')
        AND lifetime_reset_at < :cutoff
        ORDER BY lifetime_reset_at ASC
    """)
    suspend fun getExpiredOperations(cutoff: Long): List<PendingOperation>

    /**
     * Abandon an expired operation permanently.
     * Sets status to FAILED with a reason explaining why.
     *
     * @param id Operation ID to abandon
     * @param reason Human-readable reason for abandonment
     * @param now Current timestamp for updated_at
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'FAILED',
            last_error = :reason,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun abandonOperation(id: Long, reason: String, now: Long)

    /**
     * Refresh lifetime clock when user interacts with event.
     * Extends the 30-day retry window from this moment.
     *
     * Only refreshes non-FAILED operations (FAILED ops need explicit reset).
     *
     * @param eventId Event ID whose operations should be refreshed
     * @param now Current timestamp to set as new lifetime_reset_at
     */
    @Query("""
        UPDATE pending_operations
        SET lifetime_reset_at = :now
        WHERE event_id = :eventId
        AND status != 'FAILED'
    """)
    suspend fun refreshOperationLifetime(eventId: Long, now: Long)

    // ========== iCloud URL Migration ==========

    /**
     * Update target URL for a pending operation (for iCloud URL normalization migration).
     */
    @Query("UPDATE pending_operations SET target_url = :targetUrl WHERE id = :id")
    suspend fun updateTargetUrl(id: Long, targetUrl: String)
}
