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

    // Skip breakdown (categories only, no specific event info)
    val skippedParseError: Int = 0,
    val skippedPendingLocal: Int = 0,
    val skippedEtagUnchanged: Int = 0,
    val skippedOrphanedException: Int = 0,

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
    val fetchFailedCount: Int = 0       // Individual fetches that failed during fallback
) {
    /**
     * Overall status derived from session data.
     */
    val status: SyncStatus get() = when {
        errorType != null -> SyncStatus.FAILED
        hasMissingEvents -> SyncStatus.PARTIAL
        skippedParseError > 0 || skippedOrphanedException > 0 -> SyncStatus.PARTIAL
        else -> SyncStatus.SUCCESS
    }

    /**
     * Total events skipped for any reason.
     */
    val totalSkipped: Int get() =
        skippedParseError + skippedPendingLocal + skippedEtagUnchanged + skippedOrphanedException

    /**
     * Whether this session has any issues worth highlighting.
     */
    val hasIssues: Boolean get() =
        hasMissingEvents || skippedParseError > 0 || skippedOrphanedException > 0 ||
        abandonedParseErrors > 0 || errorType != null || fallbackUsed || fetchFailedCount > 0
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
