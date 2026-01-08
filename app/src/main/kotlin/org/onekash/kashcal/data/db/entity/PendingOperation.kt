package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Pending sync operation for offline-first architecture.
 *
 * Every local mutation queues an operation here.
 * WorkManager job drains in FIFO order with exponential backoff.
 *
 * Operations are retried up to maxRetries times with increasing delay.
 */
@Entity(
    tableName = "pending_operations",
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["status", "next_retry_at"])
    ]
)
data class PendingOperation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Referenced event ID.
     * Note: Not a FK to allow operations for deleted events.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * Operation type.
     * Values: "CREATE", "UPDATE", "DELETE"
     */
    @ColumnInfo(name = "operation")
    val operation: String,

    /**
     * Current operation status.
     * Values: "PENDING", "IN_PROGRESS", "FAILED"
     */
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: String = "PENDING",

    /**
     * Number of retry attempts made.
     */
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,

    /**
     * Maximum retry attempts before giving up.
     */
    @ColumnInfo(name = "max_retries", defaultValue = "5")
    val maxRetries: Int = 5,

    /**
     * Earliest time to attempt next retry (epoch millis).
     * 0 means ready immediately.
     */
    @ColumnInfo(name = "next_retry_at", defaultValue = "0")
    val nextRetryAt: Long = 0,

    /**
     * Last error message for diagnostics.
     */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    /**
     * When operation was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * When operation was last updated.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Target URL for DELETE/MOVE operations.
     * Captures caldavUrl at queue time (before Event.caldavUrl is cleared).
     * Used by processDelete/processMove to know where to delete from.
     */
    @ColumnInfo(name = "target_url")
    val targetUrl: String? = null,

    /**
     * Target calendar ID for MOVE operations.
     * Specifies which calendar to create the event in after deletion.
     */
    @ColumnInfo(name = "target_calendar_id")
    val targetCalendarId: Long? = null,

    /**
     * Current phase for MOVE operations (0 = DELETE, 1 = CREATE).
     * Each phase gets independent retry budget to prevent event loss.
     *
     * - Phase 0: DELETE from old calendar (uses retry_count for DELETE retries)
     * - Phase 1: CREATE in new calendar (retry_count reset to 0 for CREATE retries)
     *
     * This prevents the bug where DELETE succeeds but CREATE fails repeatedly,
     * exhausting the shared retry budget and losing the event.
     */
    @ColumnInfo(name = "move_phase", defaultValue = "0")
    val movePhase: Int = 0
) {
    // ========== Computed Properties ==========

    /**
     * Whether this operation can be retried.
     */
    val shouldRetry: Boolean
        get() = retryCount < maxRetries

    /**
     * Whether this operation is ready to process.
     */
    fun isReady(currentTimeMillis: Long): Boolean =
        status == "PENDING" && currentTimeMillis >= nextRetryAt

    companion object {
        const val OPERATION_CREATE = "CREATE"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_DELETE = "DELETE"
        const val OPERATION_MOVE = "MOVE"  // Atomic DELETE from old + CREATE in new calendar

        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_FAILED = "FAILED"

        // Move operation phases (each phase has independent 5-retry budget)
        const val MOVE_PHASE_DELETE = 0  // Phase 0: DELETE from old calendar
        const val MOVE_PHASE_CREATE = 1  // Phase 1: CREATE in new calendar

        /**
         * Calculate next retry delay with exponential backoff.
         * Delays: 30s, 1m, 2m, 4m, 8m, 16m...
         * Negative retryCount is treated as 0 (defensive coding).
         */
        fun calculateRetryDelay(retryCount: Int): Long {
            val baseDelayMs = 30_000L // 30 seconds
            val multiplier = 1L shl retryCount.coerceIn(0, 10) // 2^retryCount, bounded [0, 10]
            return baseDelayMs * multiplier
        }
    }
}
