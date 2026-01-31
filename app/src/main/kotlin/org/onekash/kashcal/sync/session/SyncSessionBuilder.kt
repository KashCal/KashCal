package org.onekash.kashcal.sync.session

/**
 * Builder for constructing SyncSession during a sync operation.
 *
 * Usage:
 * 1. Create at start of sync: SyncSessionBuilder(calendar, syncType, trigger)
 * 2. Call setters as sync progresses
 * 3. Call build() at end to get final SyncSession
 */
class SyncSessionBuilder(
    private val calendarId: Long,
    private val calendarName: String,
    private val syncType: SyncType,
    private val triggerSource: SyncTrigger
) {
    private val startTime = System.currentTimeMillis()

    // Pipeline numbers
    private var hrefsReported = 0
    private var eventsFetched = 0
    private var eventsWritten = 0
    private var eventsUpdated = 0
    private var eventsDeleted = 0

    // Push statistics
    private var eventsPushedCreated = 0
    private var eventsPushedUpdated = 0
    private var eventsPushedDeleted = 0

    // Skip counts
    private var skippedParseError = 0
    private var skippedPendingLocal = 0
    private var skippedEtagUnchanged = 0
    private var skippedOrphanedException = 0

    // Token tracking
    private var tokenAdvanced = true

    // Error info
    private var errorType: ErrorType? = null
    private var errorStage: String? = null

    // Pipeline setters
    fun setHrefsReported(count: Int) = apply { hrefsReported = count }
    fun setEventsFetched(count: Int) = apply { eventsFetched = count }

    // Increment methods for processing loop
    fun incrementWritten() = apply { eventsWritten++ }
    fun incrementUpdated() = apply { eventsUpdated++ }
    fun incrementDeleted() = apply { eventsDeleted++ }
    fun addDeleted(count: Int) = apply { eventsDeleted += count }

    // Push statistics setter
    fun setPushStats(created: Int, updated: Int, deleted: Int) = apply {
        eventsPushedCreated = created
        eventsPushedUpdated = updated
        eventsPushedDeleted = deleted
    }

    // Skip reason tracking
    fun incrementSkipParseError() = apply { skippedParseError++ }
    fun incrementSkipPendingLocal() = apply { skippedPendingLocal++ }
    fun incrementSkipEtagUnchanged() = apply { skippedEtagUnchanged++ }
    fun incrementSkipOrphanedException() = apply { skippedOrphanedException++ }

    // Accessor for parse error count (for retry logic)
    fun getSkippedParseError(): Int = skippedParseError

    // Parse failure retry tracking (v16.7.0)
    private var abandonedParseErrors = 0
    fun setAbandonedParseErrors(count: Int) = apply { abandonedParseErrors = count }

    // Token and error
    fun setTokenAdvanced(advanced: Boolean) = apply { tokenAdvanced = advanced }
    fun setError(type: ErrorType, stage: String, message: String? = null) = apply {
        errorType = type
        errorStage = stage
        errorMessage = message
    }

    // Error message (v16.8.0)
    private var errorMessage: String? = null

    // Fetch fallback tracking (v16.8.0)
    private var fallbackUsed = false
    private var fetchFailedCount = 0
    fun setFallbackUsed(used: Boolean) = apply { fallbackUsed = used }
    fun setFetchFailedCount(count: Int) = apply { fetchFailedCount = count }

    // RFC 6578 Section 3.6: Server truncated results (507)
    private var truncated = false
    fun setTruncated(value: Boolean) = apply { truncated = value }

    /**
     * Build the final SyncSession.
     * Call this at the end of sync operation.
     */
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
            hasMissingEvents = missingCount > 0,
            missingCount = missingCount,
            tokenAdvanced = tokenAdvanced,
            abandonedParseErrors = abandonedParseErrors,
            errorType = errorType,
            errorStage = errorStage,
            errorMessage = errorMessage,
            fallbackUsed = fallbackUsed,
            fetchFailedCount = fetchFailedCount,
            truncated = truncated
        )
    }
}
