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
     * Increased to 10 in v21.5.3 to allow more recovery time (~13h total).
     * Note: DB default stays 5 for schema compatibility, but Kotlin default is 10.
     */
    @ColumnInfo(name = "max_retries", defaultValue = "5")
    val maxRetries: Int = 10,

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
    val movePhase: Int = 0,

    /**
     * Source calendar ID for DELETE operations during calendar moves.
     *
     * When event.calendarId is updated to target before push completes,
     * this field preserves the source calendar for proper filtering:
     * - DELETE phase: Filter by sourceCalendarId
     * - CREATE phase: Filter by targetCalendarId
     *
     * For regular DELETE operations (not from MOVE), this is null
     * and filtering falls back to event.calendarId.
     *
     * Set for:
     * - MOVE operations (same-account or cross-account)
     * - DELETE operations from synced→local moves
     * - DELETE operations from cross-account moves
     *
     * Added in v21.6.0 for cross-account move support.
     */
    @ColumnInfo(name = "source_calendar_id")
    val sourceCalendarId: Long? = null,

    /**
     * When the operation's lifetime was last reset due to user interaction.
     * Used for 30-day lifetime cap - operations older than this are abandoned.
     *
     * Reset when:
     * - Operation is created (defaults to now)
     * - User edits/deletes/moves the event
     * - Force Full Sync is triggered
     *
     * Added in v21.5.3.
     */
    @ColumnInfo(name = "lifetime_reset_at", defaultValue = "0")
    val lifetimeResetAt: Long = System.currentTimeMillis(),

    /**
     * When the operation entered FAILED status.
     * Used for 24h auto-reset - operations failed > 24h ago are retried.
     *
     * Set when operation transitions to FAILED, cleared on reset to PENDING.
     * Null means operation is not in FAILED status.
     *
     * Added in v21.5.3.
     */
    @ColumnInfo(name = "failed_at")
    val failedAt: Long? = null
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

        // Move operation phases (each phase has independent retry budget)
        const val MOVE_PHASE_DELETE = 0  // Phase 0: DELETE from old calendar
        const val MOVE_PHASE_CREATE = 1  // Phase 1: CREATE in new calendar

        // Retry behavior constants (v21.5.3)
        const val BASE_DELAY_MS = 30_000L                         // 30 seconds
        const val MAX_BACKOFF_MS = 5L * 60 * 60 * 1000            // 5 hours (Android WorkManager standard)
        const val OPERATION_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
        const val AUTO_RESET_FAILED_MS = 24L * 60 * 60 * 1000     // 24 hours

        /**
         * Calculate next retry delay with exponential backoff capped at 5 hours.
         *
         * Schedule: 30s, 1m, 2m, 4m, 8m, 16m, 32m, 64m, 128m, 256m, 5h (capped)
         * Total time to FAILED with 10 retries: ~13.5 hours
         *
         * Negative retryCount is treated as 0 (defensive coding).
         * Cap matches Android WorkManager's MAX_BACKOFF_MILLIS.
         */
        fun calculateRetryDelay(retryCount: Int): Long {
            val multiplier = 1L shl retryCount.coerceIn(0, 10) // 2^retryCount, bounded [0, 10]
            val delay = BASE_DELAY_MS * multiplier
            return minOf(delay, MAX_BACKOFF_MS)  // Cap at 5 hours
        }
    }
}
