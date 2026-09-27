package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Queues a sync operation for a local change to an event in a server calendar.
 *
 * The push drains ready operations oldest first, retrying a failure up to [maxRetries] times
 * with exponential backoff ([calculateRetryDelay]).
 */
@Entity(
    tableName = "pending_operations",
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["status", "next_retry_at"]),
        Index(value = ["linked_move_id"])  // For the linked-DELETE guard in getReadyOperations()
    ]
)
data class PendingOperation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The event. Not a foreign key, so an operation outlives its deleted event. */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /** One of the OPERATION_ constants: CREATE, UPDATE, DELETE or MOVE. */
    @ColumnInfo(name = "operation")
    val operation: String,

    /** One of the STATUS_ constants: PENDING, IN_PROGRESS, FAILED or ABANDONED. */
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: String = "PENDING",

    /** Retries made so far. */
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,

    /**
     * Retries allowed before the operation fails. The column default is 5, but every operation
     * the app queues takes the Kotlin default of 10.
     */
    @ColumnInfo(name = "max_retries", defaultValue = "5")
    val maxRetries: Int = 10,

    /** Earliest time of the next attempt, epoch millis; 0 is ready now. */
    @ColumnInfo(name = "next_retry_at", defaultValue = "0")
    val nextRetryAt: Long = 0,

    /** Last error message, for diagnostics. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Server URL captured at queue time, so the push still knows it after Event.caldavUrl is
     * cleared. Set for DELETE and MOVE (the source to delete or move from) and for a
     * PARTSTAT-only UPDATE (the resource to answer).
     */
    @ColumnInfo(name = "target_url")
    val targetUrl: String? = null,

    /** Calendar a MOVE takes the event to. */
    @ColumnInfo(name = "target_calendar_id")
    val targetCalendarId: Long? = null,

    /**
     * Phase of a MOVE, [MOVE_PHASE_DELETE] or [MOVE_PHASE_CREATE].
     *
     * - Phase 0: a WebDAV MOVE; on success the operation becomes an UPDATE. A server that
     *   declines it, a missing source or a missing source URL advances to phase 1.
     * - Phase 1: CREATE in the target, then DELETE from the source. It starts with
     *   retry_count reset to 0.
     *
     * The reset gives the CREATE a full budget however many retries phase 0 used.
     */
    @ColumnInfo(name = "move_phase", defaultValue = "0")
    val movePhase: Int = 0,

    /**
     * Calendar the event moves out of. Event.calendarId already names the target before the
     * push runs, so per-calendar scoping reads this instead: a DELETE and a phase-0 MOVE are
     * scoped by it, a phase-1 MOVE by [targetCalendarId]. A DELETE without it falls back to
     * Event.calendarId.
     *
     * Set for a MOVE (same account), the DELETE of a move from a synced to a local calendar,
     * and the DELETE of a cross-account move.
     */
    @ColumnInfo(name = "source_calendar_id")
    val sourceCalendarId: Long? = null,

    /**
     * Start of the operation's 30-day lifetime; an operation whose lifetime has run out is
     * abandoned. Set on creation, refreshed when the user changes the event again, and reset
     * by a forced full sync.
     */
    @ColumnInfo(name = "lifetime_reset_at", defaultValue = "0")
    val lifetimeResetAt: Long = System.currentTimeMillis(),

    /**
     * When the operation became FAILED, or null when it isn't FAILED. An operation FAILED for
     * over 24 hours and still within its lifetime is reset to PENDING and retried.
     */
    @ColumnInfo(name = "failed_at")
    val failedAt: Long? = null,

    /**
     * UUID pairing the CREATE and DELETE of a cross-account move; null for every other
     * operation, including a same-account MOVE.
     *
     * getReadyOperations() holds back the DELETE while a PENDING CREATE with the same id
     * exists, so the source copy isn't deleted before the other account's sync creates the
     * target copy.
     */
    @ColumnInfo(name = "linked_move_id")
    val linkedMoveId: String? = null,

    /**
     * Marks an UPDATE as a PARTSTAT-only RSVP. The push then uses
     * `IcsPatcher.patchAttendeeReply`, which keeps every ATTENDEE of rawIcal and changes only
     * the user's PARTSTAT; the full-event serializer would write the local attendee table
     * instead. Queue state only, not an RFC field.
     */
    @ColumnInfo(name = "partstat_only", defaultValue = "0")
    val partstatOnly: Boolean = false,

    /**
     * PARTSTAT to send for a PARTSTAT-only RSVP (RFC 5545 §3.2.12: `ACCEPTED`, `TENTATIVE`,
     * `DECLINED`, `NEEDS-ACTION`), upper-cased at write time; null when [partstatOnly] is
     * false. Queue state only.
     */
    @ColumnInfo(name = "partstat_target")
    val partstatTarget: String? = null
) {
    // ========== Computed Properties ==========

    /** Whether the retry budget has room left. */
    val shouldRetry: Boolean
        get() = retryCount < maxRetries

    /** Whether the operation is PENDING and its retry time has come. */
    fun isReady(currentTimeMillis: Long): Boolean =
        status == "PENDING" && currentTimeMillis >= nextRetryAt

    companion object {
        const val OPERATION_CREATE = "CREATE"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_DELETE = "DELETE"
        const val OPERATION_MOVE = "MOVE"  // Same-account move; phases on movePhase

        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_FAILED = "FAILED"

        /**
         * Terminal status of an operation past its 30-day lifetime. Unlike FAILED, it isn't picked
         * up again by [org.onekash.kashcal.data.db.dao.PendingOperationsDao.getExpiredOperations]
         * or by the 24-hour auto-reset, so the "sync expired" notification fires once instead of on
         * every background sync. A forced sync (`resetAllFailed`) still re-arms it with a fresh
         * lifetime, as the notification's "Force Sync to retry" promises.
         */
        const val STATUS_ABANDONED = "ABANDONED"

        // Move phases; the CREATE phase starts with a fresh retry budget
        const val MOVE_PHASE_DELETE = 0  // Phase 0: WebDAV MOVE from the source
        const val MOVE_PHASE_CREATE = 1  // Phase 1: CREATE in the target, then DELETE the source

        const val BASE_DELAY_MS = 30_000L                         // 30 seconds
        const val MAX_BACKOFF_MS = 5L * 60 * 60 * 1000            // 5 hours, WorkManager's cap
        const val OPERATION_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
        const val AUTO_RESET_FAILED_MS = 24L * 60 * 60 * 1000     // 24 hours

        /**
         * Returns the delay before the next retry: [BASE_DELAY_MS] doubled per retry, capped at
         * [MAX_BACKOFF_MS]. A negative [retryCount] counts as 0.
         *
         * Retry counts 0 to 9 wait 30s, 1m, 2m and so on up to 256m, so the default 10 retries
         * add up to about 8.5 hours. The 5-hour cap applies from count 10, which only an
         * operation retried past its budget reaches (an unresolved 412).
         */
        fun calculateRetryDelay(retryCount: Int): Long {
            val multiplier = 1L shl retryCount.coerceIn(0, 10) // 2^retryCount, bounded [0, 10]
            val delay = BASE_DELAY_MS * multiplier
            return minOf(delay, MAX_BACKOFF_MS)
        }
    }
}
