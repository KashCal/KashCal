package org.onekash.kashcal.sync.strategy

/** Outcome of pushing queued local changes to the server. */
sealed class PushResult {
    /**
     * The push ran through its ready operations; individual operations may still have failed.
     *
     * @param operationsFailed operations that didn't complete: rescheduled for retry, left for
     *   conflict resolution, or marked failed. A marked-failed [SinglePushResult.Error] is also
     *   in [pushErrors]; a [SinglePushResult.RsvpModified] is in [pushWarnings].
     * @param pushedEventIds rows pushed this cycle, which the same cycle's pull doesn't reap,
     *   including a row a DELETE spared ([SinglePushResult.Success.sparedEventId]) and the
     *   changed occurrences a merged retry sent ([SinglePushResult.Success.sentOccurrenceIds]).
     * @param refetchEventIds the pushed rows whose write carried a server change they don't hold
     *   yet ([SinglePushResult.Success.refetch]); the same cycle's pull refreshes them.
     */
    data class Success(
        val eventsCreated: Int,
        val eventsUpdated: Int,
        val eventsDeleted: Int,
        val operationsProcessed: Int,
        val operationsFailed: Int,
        val pushedEventIds: Set<Long> = emptySet(),
        val refetchEventIds: Set<Long> = emptySet(),
        /**
         * Conditions surfaced to the sync session as warnings: a retryable operation rescheduled
         * for the next cycle, a 412 conflict pending resolution, a MOVE-orphan note, an RSVP
         * that needs the user to respond again.
         */
        val pushWarnings: List<String> = emptyList(),
        /**
         * Permanent failures: operations that exhausted retries or were non-retryable and were
         * marked failed. The change didn't reach the server and won't be retried, so the engine
         * reports each as a `SyncError`, not a warning. Each keeps its error code so auth, server
         * and not-found failures stay distinguishable downstream.
         */
        val pushErrors: List<PushFailure> = emptyList()
    ) : PushResult()

    /** Describes one permanent operation failure and its HTTP or internal error code. */
    data class PushFailure(val code: Int, val message: String)

    /** No ready operations were queued for this push. */
    data object NoPendingOperations : PushResult()

    /**
     * The push as a whole failed.
     *
     * @param code HTTP status, or -1 for internal errors.
     */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = true
    ) : PushResult()
}

/** Outcome of pushing one pending operation. */
sealed class SinglePushResult {
    /**
     * The operation completed and is removed from the queue.
     *
     * @param newEtag server ETag after a create or update.
     * @param newUrl CalDAV URL of a created resource.
     * @param sparedEventId set when a DELETE removed the server resource but kept the local
     *   row because it has since moved to another calendar. The caller must treat this row as
     *   pushed so the same cycle's pull doesn't reap it either.
     * @param refetch set when the body written carries a change made elsewhere that the rows
     *   don't hold: a 412 retry built on the server's copy (an update or a reply), or a later
     *   reply in the same push built on a retried reply. No row stores that body or its etag, and
     *   the caller reports the event in [PushResult.Success.refetchEventIds] so the same cycle's
     *   pull brings the server copy in.
     * @param sentOccurrenceIds changed occurrences a merged retry wrote with the series. The
     *   caller counts them as pushed, so a stale read in the same cycle's pull can't prune one
     *   whose instance the server's copy still excludes.
     */
    data class Success(
        val newEtag: String? = null,
        val newUrl: String? = null,
        val warning: String? = null,
        val sparedEventId: Long? = null,
        val refetch: Boolean = false,
        val sentOccurrenceIds: List<Long> = emptyList()
    ) : SinglePushResult()

    /** The server refused the write with a 412 conflict; the operation is rescheduled. */
    data class Conflict(
        val serverEtag: String? = null
    ) : SinglePushResult()

    /** The operation failed; retried while [isRetryable] and retries remain, else marked failed. */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = true
    ) : SinglePushResult()

    /**
     * A MOVE advanced from its DELETE phase to its CREATE phase.
     *
     * The operation is already updated in the database (movePhase=1, retryCount=0) and must
     * not be deleted from the queue; the next sync cycle runs its CREATE phase.
     */
    data object PhaseAdvanced : SinglePushResult()

    /**
     * An RSVP write hit a 412 and the GET-and-replay retry couldn't be built or also failed.
     * The caller marks the operation failed instead of retrying forever and adds a warning,
     * naming [eventTitle], that asks the user to respond again.
     */
    data class RsvpModified(val eventTitle: String) : SinglePushResult()
}
