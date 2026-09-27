package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.PendingOperation

/**
 * Reads and writes the offline-first sync queue; [PendingOperation] says how the push drains it.
 */
@Dao
interface PendingOperationsDao {

    // ========== Read Operations ==========

    /**
     * Returns the PENDING operations due by [now], oldest first.
     *
     * A DELETE with a [PendingOperation.linkedMoveId] waits while a PENDING CREATE with the same
     * id exists, so a cross-account move lands the copy before removing the original; the
     * other order would lose the event.
     */
    @Query("""
        SELECT * FROM pending_operations po
        WHERE status = 'PENDING'
          AND next_retry_at <= :now
          AND (
            -- Not a linked DELETE, OR
            po.linked_move_id IS NULL
            OR po.operation != 'DELETE'
            -- Linked DELETE: only ready if no pending CREATE with same linkedMoveId
            OR NOT EXISTS (
                SELECT 1 FROM pending_operations linked
                WHERE linked.linked_move_id = po.linked_move_id
                  AND linked.operation = 'CREATE'
                  AND linked.status = 'PENDING'
            )
          )
        ORDER BY created_at ASC
    """)
    suspend fun getReadyOperations(now: Long): List<PendingOperation>

    /** Returns every operation, any status, oldest first. */
    @Query("SELECT * FROM pending_operations ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingOperation>

    /** Returns the same rows as [getAll]; named like the other DAOs' one-shot reads. */
    @Query("SELECT * FROM pending_operations ORDER BY created_at ASC")
    suspend fun getAllOnce(): List<PendingOperation>

    /** Returns [eventId]'s operations, any status, oldest first. */
    @Query("SELECT * FROM pending_operations WHERE event_id = :eventId ORDER BY created_at ASC")
    suspend fun getForEvent(eventId: Long): List<PendingOperation>

    /** Returns the operation with [id], or null. */
    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingOperation?

    /**
     * Observes the PENDING count. `EventCoordinator.getPendingOperationCount` exposes it; no UI
     * reads it today.
     */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    /** Observes the FAILED count. */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'FAILED'")
    fun getFailedCount(): Flow<Int>

    /** Returns the number of operations, any status. */
    @Query("SELECT COUNT(*) FROM pending_operations")
    suspend fun getTotalCount(): Int

    /** Returns whether [eventId] has an operation in any status but FAILED. */
    @Query("SELECT EXISTS(SELECT 1 FROM pending_operations WHERE event_id = :eventId AND status != 'FAILED')")
    suspend fun hasPendingForEvent(eventId: Long): Boolean

    // ========== Write Operations ==========

    /** Inserts [operation], replacing a row with the same id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingOperation): Long

    @Update
    suspend fun update(operation: PendingOperation)

    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Deletes every operation of [eventId], any status. */
    @Query("DELETE FROM pending_operations WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /** Deletes FAILED operations last updated before [cutoff]. */
    @Query("DELETE FROM pending_operations WHERE status = 'FAILED' AND updated_at < :cutoff")
    suspend fun deleteOldFailed(cutoff: Long)

    /** Deletes every operation. */
    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()

    // ========== Status Updates ==========

    @Query("""
        UPDATE pending_operations
        SET status = 'IN_PROGRESS',
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markInProgress(id: Long, now: Long)

    /** Requeues an operation for [nextRetryAt], counting the retry and recording [error]. */
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

    /** Marks an operation FAILED; `failed_at` is the clock [autoResetOldFailed] measures. */
    @Query("""
        UPDATE pending_operations
        SET status = 'FAILED',
            last_error = :error,
            failed_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markFailed(id: Long, error: String, now: Long)

    /** Returns one operation to PENDING, ready now, with a fresh retry count. */
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
     * Returns every FAILED and ABANDONED operation to PENDING for a forced full sync, and
     * returns how many.
     *
     * ABANDONED is included because this is the recovery the "sync expired" notification
     * advertises ("Force Sync to retry"). A fresh `lifetime_reset_at` starts a new 30-day
     * window, so the worker's expiry check that follows doesn't abandon the op again.
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
        WHERE status IN ('FAILED', 'ABANDONED')
    """)
    suspend fun resetAllFailed(now: Long): Int

    /**
     * Returns IN_PROGRESS operations last updated before [cutoff] to PENDING, and returns how
     * many.
     *
     * Runs at sync start to recover operations a crash left in progress; the caller's one-hour
     * cutoff spares operations another sync is still working on.
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
     * Advances a MOVE to its CREATE phase with a fresh retry budget, once its DELETE phase
     * succeeds.
     *
     * The CREATE gets its own full `max_retries` attempts. Sharing the DELETE's budget would
     * let a DELETE that succeeded on a late retry leave the CREATE too few attempts, and a
     * CREATE that gives up after the DELETE has run loses the event.
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

    /** Returns whether [eventId] has a non-FAILED operation of type [operation]. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM pending_operations
            WHERE event_id = :eventId
            AND operation = :operation
            AND status != 'FAILED'
        )
    """)
    suspend fun operationExists(eventId: Long, operation: String): Boolean

    /** Deletes [eventId]'s UPDATE operations when it has a CREATE, and returns how many. */
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
     * Returns the PENDING operations whose last error was a conflict (412 or "Conflict") and
     * whose effective calendar is [calendarId].
     *
     * The calendar scoping mirrors `PushStrategy.pushForCalendar`; keep the two in step.
     */
    @Query("""
        SELECT po.* FROM pending_operations po
        LEFT JOIN events e ON po.event_id = e.id
        WHERE po.status = 'PENDING'
          AND (po.last_error LIKE '%Conflict%' OR po.last_error LIKE '%412%')
          AND (
            (po.operation = 'DELETE'
               AND COALESCE(po.source_calendar_id, e.calendar_id) = :calendarId)
            OR (po.operation = 'MOVE' AND po.move_phase = 0 AND po.source_calendar_id = :calendarId)
            OR (po.operation = 'MOVE' AND po.move_phase = 1 AND po.target_calendar_id = :calendarId)
            OR (po.operation IN ('CREATE', 'UPDATE') AND e.calendar_id = :calendarId)
          )
        ORDER BY po.created_at ASC
    """)
    suspend fun getConflictOperationsForCalendar(calendarId: Long): List<PendingOperation>

    // ========== Retry Lifecycle Methods (v21.5.3) ==========

    /**
     * Returns FAILED operations that failed before [failedBefore] (the caller passes 24 hours
     * ago) to PENDING with a fresh retry count, and returns how many.
     *
     * Measures from `failed_at`, not `updated_at`. Operations whose `lifetime_reset_at` is not
     * after [lifetimeCutoff] (past the 30-day lifetime) are left alone.
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
     * Returns PENDING and FAILED operations whose `lifetime_reset_at` is before [cutoff] (the
     * caller passes 30 days ago), for [abandonOperation]; otherwise they would retry forever.
     */
    @Query("""
        SELECT * FROM pending_operations
        WHERE status IN ('PENDING', 'FAILED')
        AND lifetime_reset_at < :cutoff
        ORDER BY lifetime_reset_at ASC
    """)
    suspend fun getExpiredOperations(cutoff: Long): List<PendingOperation>

    /**
     * Abandons an expired operation; returns 1 if this call abandoned it, 0 if it was already
     * terminal (for example abandoned by a concurrent sync).
     *
     * ABANDONED, unlike FAILED, is outside [getExpiredOperations], so the "sync expired"
     * notification fires once instead of on every later background sync. The row is kept so
     * [resetAllFailed] (Force Sync) can recover it.
     *
     * The `status IN ('PENDING','FAILED')` guard makes this a compare-and-set. Two overlapping
     * background syncs (different WorkManager unique-work names, not otherwise serialized) can
     * read the same expired op, but only one abandons it and counts it toward its notification.
     * Syncs racing over disjoint ops can each post; the notification stays single because it
     * reposts on a fixed id with setOnlyAlertOnce (`SyncNotificationManager`). The per-run
     * count can then undercount, but the user sees one silent update instead of repeated alerts.
     */
    @Query("""
        UPDATE pending_operations
        SET status = 'ABANDONED',
            last_error = :reason,
            updated_at = :now
        WHERE id = :id
        AND status IN ('PENDING', 'FAILED')
    """)
    suspend fun abandonOperation(id: Long, reason: String, now: Long): Int

    /**
     * Restarts the 30-day lifetime of [eventId]'s non-FAILED operations at [now]; EventWriter
     * calls it when a change is queued onto an event that already has a PENDING operation.
     *
     * FAILED operations need an explicit reset. An ABANDONED op also matches, which is
     * harmless: its status stays ABANDONED, so it stays out of [getReadyOperations] and
     * [getExpiredOperations]. Re-editing the event queues a fresh op.
     */
    @Query("""
        UPDATE pending_operations
        SET lifetime_reset_at = :now
        WHERE event_id = :eventId
        AND status != 'FAILED'
    """)
    suspend fun refreshOperationLifetime(eventId: Long, now: Long)

    // ========== iCloud URL Migration ==========

    /** Sets an operation's target URL, for the iCloud URL normalization migration. */
    @Query("UPDATE pending_operations SET target_url = :targetUrl WHERE id = :id")
    suspend fun updateTargetUrl(id: Long, targetUrl: String)

    // ========== Cross-Account Move Linked Operations (v23.2.0) ==========

    /**
     * Deletes the DELETE half of a cross-account move once its CREATE fails for good.
     *
     * Both halves share [linkedMoveId]. Without the CREATE the DELETE is orphaned, and running
     * it would remove the only copy.
     */
    @Query("""
        DELETE FROM pending_operations
        WHERE linked_move_id = :linkedMoveId
          AND operation = 'DELETE'
    """)
    suspend fun deleteLinkedDelete(linkedMoveId: String)
}
