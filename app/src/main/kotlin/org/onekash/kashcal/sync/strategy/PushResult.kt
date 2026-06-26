package org.onekash.kashcal.sync.strategy

/**
 * Result of a push operation (local → server).
 */
sealed class PushResult {
    /**
     * Push completed successfully.
     * @param eventsCreated Number of events created on server
     * @param eventsUpdated Number of events updated on server
     * @param eventsDeleted Number of events deleted from server
     * @param operationsProcessed Total operations processed
     * @param operationsFailed Operations that failed and were rescheduled
     */
    data class Success(
        val eventsCreated: Int,
        val eventsUpdated: Int,
        val eventsDeleted: Int,
        val operationsProcessed: Int,
        val operationsFailed: Int,
        val pushedEventIds: Set<Long> = emptySet(),
        /**
         * Soft, recoverable conditions surfaced to the sync session as
         * warnings: a retryable operation rescheduled for the next cycle, a
         * 412 conflict pending resolution, a MOVE-orphan note. These will
         * self-resolve or are being handled — not a lost change.
         */
        val pushWarnings: List<String> = emptyList(),
        /**
         * Permanent push failures — an operation that exhausted retries or is
         * non-retryable and was `markFailed`. The user's change did NOT reach
         * the server and will NOT be retried, so this is surfaced as a sync
         * ERROR (not a warning). Distinct channel so the engine routes these to
         * `SyncError` rather than `addWarning`. Carries the HTTP/error [code] so
         * downstream classification (auth vs server vs not-found) is preserved
         * rather than collapsed to a generic message string.
         */
        val pushErrors: List<PushFailure> = emptyList()
    ) : PushResult()

    /** A permanent per-operation push failure, with its error code preserved. */
    data class PushFailure(val code: Int, val message: String)

    /**
     * No pending operations to push.
     */
    data object NoPendingOperations : PushResult()

    /**
     * Push failed with an error.
     * @param code Error code (HTTP status or -1 for internal errors)
     * @param message Error description
     * @param isRetryable Whether the error is recoverable
     */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = true
    ) : PushResult()
}

/**
 * Result of a single operation push.
 */
sealed class SinglePushResult {
    /**
     * Operation succeeded.
     * @param newEtag Updated ETag from server (for create/update)
     * @param newUrl CalDAV URL (for create)
     */
    data class Success(
        val newEtag: String? = null,
        val newUrl: String? = null,
        val warning: String? = null
    ) : SinglePushResult()

    /**
     * Conflict detected - server has newer version.
     * @param serverEtag ETag from server
     */
    data class Conflict(
        val serverEtag: String? = null
    ) : SinglePushResult()

    /**
     * Operation failed with error.
     * @param code Error code
     * @param message Error description
     * @param isRetryable Whether to retry
     */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = true
    ) : SinglePushResult()

    /**
     * MOVE operation advanced to next phase (DELETE → CREATE).
     *
     * The operation has been updated in the database (movePhase=1, retryCount=0)
     * but should NOT be deleted from the queue. It will be processed again
     * in the next sync cycle for the CREATE phase.
     */
    data object PhaseAdvanced : SinglePushResult()

    /**
     * RSVP write hit a second 412 after the GET-replay-retry. Caller should
     * surface a "this event was modified — please re-respond" snackbar
     * rather than auto-retry indefinitely. Carries the event title for the
     * user-facing message.
     */
    data class RsvpModified(val eventTitle: String) : SinglePushResult()
}
