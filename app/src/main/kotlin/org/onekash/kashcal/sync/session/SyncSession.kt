package org.onekash.kashcal.sync.session

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Records one finished sync of a single calendar for the Sync History UI.
 *
 * Privacy: holds counts, the user-created calendar name and short diagnostic text, never event
 * titles, sync-tokens or full event URLs. [warnings] name a resource by the last segment of its
 * URL, which for an event this app created is `<UID>.ics`.
 */
@Serializable
data class SyncSession(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),

    // Calendar names are user-created, so safe to store.
    val calendarId: Long,
    val calendarName: String,

    val syncType: SyncType,
    val triggerSource: SyncTrigger,
    val durationMs: Long,

    // Pipeline counts. [missingCount] is hrefsReported minus eventsFetched.
    val hrefsReported: Int,        // Hrefs from sync-collection or the etag listing
    val eventsFetched: Int,        // Events returned by calendar-multiget
    val eventsWritten: Int,        // New events persisted
    val eventsUpdated: Int,        // Existing events updated
    val eventsDeleted: Int,

    // Push counts (local to server)
    val eventsPushedCreated: Int = 0,
    val eventsPushedUpdated: Int = 0,
    val eventsPushedDeleted: Int = 0,

    // Skip counts by reason; no per-event detail
    val skippedParseError: Int = 0,
    val skippedPendingLocal: Int = 0,
    val skippedEtagUnchanged: Int = 0,
    val skippedOrphanedException: Int = 0,
    val skippedAlreadySynced: Int = 0,
    val skippedRecentlyPushed: Int = 0,

    val hasMissingEvents: Boolean = false,
    val missingCount: Int = 0,
    val tokenAdvanced: Boolean = true,

    // Events still unparseable after the maximum parse retries; the sync-token advanced past them.
    val abandonedParseErrors: Int = 0,

    // Set only when the sync failed
    val errorType: ErrorType? = null,
    val errorStage: String? = null,
    val errorMessage: String? = null,

    // The server truncated the sync-collection reply with 507 (RFC 6578 §3.6); the next sync
    // continues from the returned token.
    val truncated: Boolean = false,

    // Issues handled without failing the sync. Resources are named by URL filename, never by
    // event title. Nullable with a null default so session files written without it still decode.
    val warnings: List<String>? = null
) {
    /**
     * Derives the session status: FAILED when [errorType] is set, PARTIAL when any event failed
     * to parse, SUCCESS otherwise. Missing events and fallbacks don't lower it.
     */
    val status: SyncStatus get() = when {
        errorType != null -> SyncStatus.FAILED
        skippedParseError > 0 -> SyncStatus.PARTIAL
        else -> SyncStatus.SUCCESS
    }

    /** Pulled events added, updated or deleted. */
    val totalChanges: Int get() = eventsWritten + eventsUpdated + eventsDeleted

    val hasChanges: Boolean get() = totalChanges > 0

    val hasParseFailures: Boolean get() = skippedParseError > 0

    /** Whether this session skipped events already stored by a prior session. */
    val hasAlreadySynced: Boolean get() = skippedAlreadySynced > 0

    val hasWarnings: Boolean get() = !warnings.isNullOrEmpty()

    /** Events pushed to the server: created, updated and deleted. */
    val totalPushed: Int get() = eventsPushedCreated + eventsPushedUpdated + eventsPushedDeleted

    val hasPushChanges: Boolean get() = totalPushed > 0

    /** Same as [totalChanges]; named to pair with [totalPushed]. */
    val totalPullChanges: Int get() = totalChanges

    /** Same as [hasChanges]; named to pair with [hasPushChanges]. */
    val hasPullChanges: Boolean get() = hasChanges

    val hasAnyChanges: Boolean get() = hasChanges || hasPushChanges
}

@Serializable
enum class SyncType {
    INCREMENTAL,  // sync-collection delta from the stored sync-token
    FULL          // Lists the calendar's etags and fetches the changed events
}

/** Session outcome; see [SyncSession.status] for how it is derived. */
@Serializable
enum class SyncStatus {
    SUCCESS,
    PARTIAL,   // Some events failed to parse
    FAILED
}

/**
 * Categorizes a failed sync. `CalDavSyncEngine` maps result codes and exceptions;
 * `CalDavSyncWorker` also sets it for setup failures and exceptions outside a calendar sync.
 */
@Serializable
enum class ErrorType {
    NETWORK,   // IOException, or a result code no other type claims
    AUTH,      // 401/403, or missing credentials, credential provider or accounts
    PARSE,     // Code -1, or a non-IO exception inside a calendar sync
    TIMEOUT,   // Socket timeout, 408 or -408
    SERVER     // 5xx, no calendars, missing provider quirks, or a non-IO exception in the worker
}
