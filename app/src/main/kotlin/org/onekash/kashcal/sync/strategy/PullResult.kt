package org.onekash.kashcal.sync.strategy

import org.onekash.kashcal.sync.model.SyncChange

/**
 * Result of a pull sync operation.
 */
sealed class PullResult {
    /**
     * Sync completed successfully.
     */
    data class Success(
        val eventsAdded: Int,
        val eventsUpdated: Int,
        val eventsDeleted: Int,
        val newSyncToken: String?,
        val newCtag: String?,
        /** Individual changes for UI notification (snackbar, bottom sheet) */
        val changes: List<SyncChange> = emptyList()
    ) : PullResult() {
        val totalChanges: Int
            get() = eventsAdded + eventsUpdated + eventsDeleted
    }

    /**
     * No changes detected (ctag unchanged).
     */
    data object NoChanges : PullResult()

    /**
     * Sync failed with error.
     */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false
    ) : PullResult()

    fun isSuccess() = this is Success || this is NoChanges
    fun isError() = this is Error
}
