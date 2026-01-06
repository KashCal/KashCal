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
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'FAILED',
            last_error = :error,
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
     * Reset all failed operations (for "retry all" action).
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            updated_at = :now
        WHERE status = 'FAILED'
    """)
    suspend fun resetAllFailed(now: Long)

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
}
