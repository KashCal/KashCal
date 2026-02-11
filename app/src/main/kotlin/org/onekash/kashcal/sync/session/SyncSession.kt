package org.onekash.kashcal.sync.session

import java.util.UUID

/**
 * Represents a completed sync operation for a single calendar.
 * Stores diagnostic information for the Sync History UI.
 *
 * Privacy: Only stores aggregate counts and calendar names (user-created).
 * Does NOT store: event titles, UIDs, sync tokens, or caldavUrls.
 */
data class SyncSession(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),

    // Calendar info (safe: user-created names)
    val calendarId: Long,
    val calendarName: String,

    // Sync metadata
    val syncType: SyncType,
    val triggerSource: SyncTrigger,
    val durationMs: Long,

    // Pipeline numbers - the key diagnostic data
    val hrefsReported: Int,        // From sync-collection
    val eventsFetched: Int,        // From calendar-multiget
    val eventsWritten: Int,        // New events persisted
    val eventsUpdated: Int,        // Existing events updated
    val eventsDeleted: Int,        // Events deleted

    // Push statistics (local → server)
    val eventsPushedCreated: Int = 0,
    val eventsPushedUpdated: Int = 0,
    val eventsPushedDeleted: Int = 0,

    // Skip breakdown (categories only, no specific event info)
    val skippedParseError: Int = 0,
    val skippedPendingLocal: Int = 0,
    val skippedEtagUnchanged: Int = 0,
    val skippedOrphanedException: Int = 0,
    val skippedConstraintError: Int = 0,
    val skippedRecentlyPushed: Int = 0,

    // Issue tracking
    val hasMissingEvents: Boolean = false,
    val missingCount: Int = 0,
    val tokenAdvanced: Boolean = true,

    // Parse failure retry tracking (v16.7.0)
    // Events that couldn't be parsed after max retries and were abandoned
    val abandonedParseErrors: Int = 0,

    // Error info (if failed)
    val errorType: ErrorType? = null,
    val errorStage: String? = null,
    val errorMessage: String? = null,  // Detailed error message (v16.8.0)

    // Fetch fallback tracking (v16.8.0)
    val fallbackUsed: Boolean = false,  // True if batch multiget failed and we fell back
    val fetchFailedCount: Int = 0,      // Individual fetches that failed during fallback

    // RFC 6578 Section 3.6: Server truncated results (507)
    val truncated: Boolean = false      // True if server returned 507 (will continue on next sync)
) {
    /**
     * Overall status derived from session data.
     * - FAILED: sync error occurred
     * - PARTIAL: parse failures (events couldn't be read)
     * - SUCCESS: everything else (fallback is transparent)
     */
    val status: SyncStatus get() = when {
        errorType != null -> SyncStatus.FAILED
        skippedParseError > 0 -> SyncStatus.PARTIAL
        else -> SyncStatus.SUCCESS
    }

    /**
     * Total events changed (for header summary).
     */
    val totalChanges: Int get() = eventsWritten + eventsUpdated + eventsDeleted

    /**
     * Whether there are any changes to show.
     */
    val hasChanges: Boolean get() = totalChanges > 0

    /**
     * Whether this session has parse failures worth showing.
     */
    val hasParseFailures: Boolean get() = skippedParseError > 0

    /**
     * Whether this session has constraint errors (FK violations, etc.) worth showing.
     */
    val hasConstraintErrors: Boolean get() = skippedConstraintError > 0

    /**
     * Total events pushed to server (local → server).
     */
    val totalPushed: Int get() = eventsPushedCreated + eventsPushedUpdated + eventsPushedDeleted

    /**
     * Whether there are any push changes.
     */
    val hasPushChanges: Boolean get() = totalPushed > 0

    /**
     * Alias for totalChanges (pull = server → local).
     * Kept for clarity alongside push stats.
     */
    val totalPullChanges: Int get() = totalChanges

    /**
     * Alias for hasChanges (pull = server → local).
     * Kept for clarity alongside push stats.
     */
    val hasPullChanges: Boolean get() = hasChanges

    /**
     * Whether there are any changes (push or pull).
     */
    val hasAnyChanges: Boolean get() = hasChanges || hasPushChanges
}

/**
 * Type of sync operation.
 */
enum class SyncType {
    INCREMENTAL,  // Uses sync-token for delta changes
    FULL          // Fetches all events in time window
}

/**
 * Overall sync status.
 */
enum class SyncStatus {
    SUCCESS,   // All events synced successfully
    PARTIAL,   // Some events missing or skipped
    FAILED     // Sync failed with error
}

/**
 * Category of sync error.
 */
enum class ErrorType {
    NETWORK,   // Connection failed, timeout
    AUTH,      // 401/403 authentication error
    PARSE,     // Failed to parse server response
    TIMEOUT,   // Request timed out
    SERVER     // 5xx server error
}
