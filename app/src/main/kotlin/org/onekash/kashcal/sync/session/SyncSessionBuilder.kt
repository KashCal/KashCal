package org.onekash.kashcal.sync.session

/**
 * Accumulates one calendar's sync counts as the sync runs; [build] returns the [SyncSession].
 * The duration is measured from construction to [build].
 */
class SyncSessionBuilder(
    private val calendarId: Long,
    private val calendarName: String,
    private val syncType: SyncType,
    private val triggerSource: SyncTrigger
) {
    private val startTime = System.currentTimeMillis()

    // Pipeline counts
    private var hrefsReported = 0
    private var eventsFetched = 0
    private var eventsWritten = 0
    private var eventsUpdated = 0
    private var eventsDeleted = 0

    // Push counts
    private var eventsPushedCreated = 0
    private var eventsPushedUpdated = 0
    private var eventsPushedDeleted = 0

    // Skip counts
    private var skippedParseError = 0
    private var skippedPendingLocal = 0
    private var skippedEtagUnchanged = 0
    private var skippedOrphanedException = 0
    private var skippedAlreadySynced = 0
    private var skippedRecentlyPushed = 0

    // Capped at maxWarnings; later warnings are dropped.
    private val warnings = mutableListOf<String>()
    private val maxWarnings = 20

    private var tokenAdvanced = true

    private var errorType: ErrorType? = null
    private var errorStage: String? = null

    fun setHrefsReported(count: Int) = apply { hrefsReported = count }
    fun setEventsFetched(count: Int) = apply { eventsFetched = count }

    fun incrementWritten() = apply { eventsWritten++ }
    fun incrementUpdated() = apply { eventsUpdated++ }
    fun incrementDeleted() = apply { eventsDeleted++ }
    fun addDeleted(count: Int) = apply { eventsDeleted += count }

    fun setPushStats(created: Int, updated: Int, deleted: Int) = apply {
        eventsPushedCreated = created
        eventsPushedUpdated = updated
        eventsPushedDeleted = deleted
    }

    fun incrementSkipParseError() = apply { skippedParseError++ }
    fun incrementSkipPendingLocal() = apply { skippedPendingLocal++ }
    fun incrementSkipEtagUnchanged() = apply { skippedEtagUnchanged++ }
    fun incrementSkipOrphanedException() = apply { skippedOrphanedException++ }
    fun incrementSkipAlreadySynced() = apply { skippedAlreadySynced++ }
    fun incrementSkipRecentlyPushed() = apply { skippedRecentlyPushed++ }

    // Read by the pull's parse-failure retry to decide whether to hold the sync-token.
    fun getSkippedParseError(): Int = skippedParseError

    // Synchronized: the concurrent multiget fetch coroutines add warnings in parallel.
    fun addWarning(message: String) = apply {
        synchronized(warnings) {
            if (warnings.size < maxWarnings) {
                warnings.add(message)
            }
        }
    }

    private var abandonedParseErrors = 0
    fun setAbandonedParseErrors(count: Int) = apply { abandonedParseErrors = count }

    fun setTokenAdvanced(advanced: Boolean) = apply { tokenAdvanced = advanced }
    fun setError(type: ErrorType, stage: String, message: String? = null) = apply {
        errorType = type
        errorStage = stage
        errorMessage = message
    }

    private var errorMessage: String? = null

    // Sync-collection reply truncated with 507 (RFC 6578 §3.6)
    private var truncated = false
    fun setTruncated(value: Boolean) = apply { truncated = value }

    /** Builds the session; call once at the end of the sync, since it stamps the duration. */
    fun build(): SyncSession {
        val missingCount = (hrefsReported - eventsFetched).coerceAtLeast(0)
        return SyncSession(
            calendarId = calendarId,
            calendarName = calendarName,
            syncType = syncType,
            triggerSource = triggerSource,
            durationMs = System.currentTimeMillis() - startTime,
            hrefsReported = hrefsReported,
            eventsFetched = eventsFetched,
            eventsWritten = eventsWritten,
            eventsUpdated = eventsUpdated,
            eventsDeleted = eventsDeleted,
            eventsPushedCreated = eventsPushedCreated,
            eventsPushedUpdated = eventsPushedUpdated,
            eventsPushedDeleted = eventsPushedDeleted,
            skippedParseError = skippedParseError,
            skippedPendingLocal = skippedPendingLocal,
            skippedEtagUnchanged = skippedEtagUnchanged,
            skippedOrphanedException = skippedOrphanedException,
            skippedAlreadySynced = skippedAlreadySynced,
            skippedRecentlyPushed = skippedRecentlyPushed,
            hasMissingEvents = missingCount > 0,
            missingCount = missingCount,
            tokenAdvanced = tokenAdvanced,
            abandonedParseErrors = abandonedParseErrors,
            errorType = errorType,
            errorStage = errorStage,
            errorMessage = errorMessage,
            truncated = truncated,
            warnings = synchronized(warnings) { warnings.toList() }
        )
    }
}
