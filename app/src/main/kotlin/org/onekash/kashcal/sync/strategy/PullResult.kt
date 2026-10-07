package org.onekash.kashcal.sync.strategy

import org.onekash.kashcal.sync.model.SyncChange

/** Outcome of one calendar's pull; [isSuccess] counts [NoChanges] as a success. */
sealed class PullResult {
    /** The pull finished; the counts and [changes] are this run's. */
    data class Success(
        val eventsAdded: Int,
        val eventsUpdated: Int,
        val eventsDeleted: Int,
        val newSyncToken: String?,
        val newCtag: String?,
        /** Per-event changes, for reminder scheduling and the Recent changes log. */
        val changes: List<SyncChange> = emptyList()
    ) : PullResult() {
        val totalChanges: Int
            get() = eventsAdded + eventsUpdated + eventsDeleted
    }

    /** The calendar's ctag matched the stored one, so nothing was listed or fetched. */
    data object NoChanges : PullResult()

    /** The pull failed with [code]. */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false
    ) : PullResult()

    fun isSuccess() = this is Success || this is NoChanges
    fun isError() = this is Error
}
