package org.onekash.kashcal.sync.strategy

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.inject.Inject

/**
 * Handles pulling events from CalDAV server to local database.
 *
 * Implements both incremental sync (using sync-token/ctag) and full sync.
 *
 * Process:
 * 1. Check ctag for quick "any changes?" detection
 * 2. Use sync-collection REPORT (incremental) or calendar-query (initial)
 * 3. Parse iCal data and map to Event entities
 * 4. Link exception events to master events
 * 5. Regenerate occurrences for recurring events
 * 6. Update sync metadata
 *
 * Provider Support:
 * The pull() method accepts an optional quirks parameter for provider-specific
 * parsing. If not provided, falls back to the injected CalDavQuirks (iCloud by default).
 *
 * @see org.onekash.kashcal.sync.provider.CalendarProvider
 */
class PullStrategy @Inject constructor(
    private val database: KashCalDatabase,
    private val calendarRepository: CalendarRepository,
    private val eventsDao: EventsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    @Suppress("DEPRECATION") private val defaultQuirks: CalDavQuirks,
    private val dataStore: KashCalDataStore,
    private val syncSessionStore: org.onekash.kashcal.sync.session.SyncSessionStore
) {
    // icaldav parser instance
    private val icalParser = ICalParser()

    companion object {
        private const val TAG = "PullStrategy"

        // Sync window: 1 year back, unlimited future (far-future date for CalDAV spec compliance)
        private const val PAST_WINDOW_MS = 365L * 24 * 60 * 60 * 1000
        private const val FUTURE_END_MS = 4102444800000L  // Jan 1, 2100 UTC

        // Occurrence expansion window (local generation) - shared across codebase
        const val OCCURRENCE_EXPANSION_MS = 2 * 365L * 24 * 60 * 60 * 1000  // 2 years

        // Parse failure retry: hold token for N syncs before giving up (v16.7.0)
        private const val MAX_PARSE_RETRIES = 3

        // Multiget fallback: retry delay and batch size for individual fetches (v16.8.0)
        private const val MULTIGET_RETRY_DELAY_MS = 2000L
        private const val INDIVIDUAL_FETCH_BATCH_SIZE = 10

        // DB retry configuration
        private const val MAX_DB_RETRIES = 3
        private const val INITIAL_DB_RETRY_DELAY_MS = 100L
    }

    /**
     * Retry database operations on lock errors only.
     *
     * Room sets SQLite's busy_timeout, but if that expires we get "database is locked".
     * This provides a safety net for extreme contention during sync.
     *
     * Does NOT retry on:
     * - SQLiteConstraintException (handled separately)
     * - Other SQLite errors (likely bugs, should propagate)
     */
    private suspend inline fun <T> withDbRetry(block: () -> T): T {
        var lastException: SQLiteException? = null
        repeat(MAX_DB_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: SQLiteException) {
                // ONLY retry on lock errors - let other SQLite errors propagate
                if (e.message?.contains("database is locked", ignoreCase = true) == true) {
                    lastException = e
                    Log.w(TAG, "DB locked (attempt ${attempt + 1}/$MAX_DB_RETRIES), retrying...")
                    if (attempt < MAX_DB_RETRIES - 1) {
                        // Bounded bit-shift per CLAUDE.md pattern 10
                        val backoff = INITIAL_DB_RETRY_DELAY_MS * (1L shl attempt.coerceIn(0, 4))
                        delay(backoff)
                    }
                } else {
                    throw e  // Not a lock error - don't retry
                }
            }
        }
        throw lastException!!
    }

    /**
     * Pull events from server for a calendar.
     *
     * @param calendar The calendar to sync
     * @param forceFullSync If true, ignores sync token and fetches all events
     * @param quirks Optional provider-specific quirks. If null, uses default (iCloud).
     * @param client CalDavClient to use for HTTP operations (created per-account by caller).
     * @param sessionBuilder Optional builder for tracking sync session stats.
     * @return PullResult indicating success/failure and statistics
     */
    suspend fun pull(
        calendar: Calendar,
        forceFullSync: Boolean = false,
        quirks: CalDavQuirks? = null,
        client: CalDavClient,
        sessionBuilder: SyncSessionBuilder? = null
    ): PullResult {
        val effectiveQuirks = quirks ?: defaultQuirks
        val effectiveClient = client

        return try {
            // Step 1: Quick ctag check
            val ctagResult = effectiveClient.getCtag(calendar.caldavUrl)
            if (ctagResult.isError()) {
                val error = ctagResult as CalDavResult.Error
                Log.e(TAG, "getCtag FAILED: ${error.code} - ${error.message}")
                return PullResult.Error(
                    code = error.code,
                    message = error.message,
                    isRetryable = error.isRetryable
                )
            }

            val serverCtag = (ctagResult as CalDavResult.Success).data
            Log.d(TAG, "ctag check: server=$serverCtag, local=${calendar.ctag}, force=$forceFullSync")
            if (!forceFullSync && serverCtag == calendar.ctag && calendar.ctag != null) {
                Log.d(TAG, "No changes (ctag unchanged)")
                return PullResult.NoChanges
            }

            // Step 2: Determine sync method
            val result = if (!forceFullSync && calendar.syncToken != null) {
                // Incremental sync using sync-token
                pullIncremental(calendar, effectiveQuirks, effectiveClient, sessionBuilder)
            } else {
                // Full sync - fetch all events in time window
                pullFull(calendar, effectiveQuirks, effectiveClient, sessionBuilder)
            }

            // Step 3: Update calendar metadata on success
            if (result is PullResult.Success) {
                calendarRepository.updateSyncToken(
                    calendarId = calendar.id,
                    syncToken = result.newSyncToken ?: calendar.syncToken,
                    ctag = result.newCtag ?: serverCtag
                )
            }

            result
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Pull timed out: ${e.message}", e)
            PullResult.Error(
                code = CalDavResult.CODE_TIMEOUT,
                message = "Timeout: ${e.message}",
                isRetryable = true
            )
        } catch (e: IOException) {
            Log.e(TAG, "Pull network error: ${e.message}", e)
            PullResult.Error(
                code = 0,
                message = "Network: ${e.message}",
                isRetryable = true
            )
        } catch (e: CancellationException) {
            // Rethrow cancellation to properly handle coroutine cancellation
            Log.d(TAG, "Pull cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed: ${e.message}", e)
            PullResult.Error(
                code = -1,
                message = e.message ?: "Unknown error",
                isRetryable = false
            )
        }
    }

    /**
     * Incremental sync using sync-collection REPORT.
     * Only fetches changed/deleted items since last sync.
     */
    private suspend fun pullIncremental(
        calendar: Calendar,
        quirks: CalDavQuirks,
        clientToUse: CalDavClient,
        sessionBuilder: SyncSessionBuilder?
    ): PullResult {
        Log.d(TAG, "Incremental sync with token: ${calendar.syncToken?.take(8)}...")

        val reportResult = clientToUse.syncCollection(calendar.caldavUrl, calendar.syncToken)
        if (reportResult.isError()) {
            val error = reportResult as CalDavResult.Error
            // 403/410 means sync token expired - try etag comparison first, then full sync
            // Etag comparison saves ~96% bandwidth (33KB vs 834KB for 231 events)
            if (error.code == 403 || error.code == 410) {
                Log.w(TAG, "Sync token expired (${error.code}), trying etag-based fallback")
                val etagResult = pullWithEtagComparison(calendar, quirks, clientToUse, sessionBuilder)
                if (etagResult != null) {
                    Log.d(TAG, "Etag-based fallback succeeded")
                    return etagResult
                }
                Log.w(TAG, "Etag fallback returned null, falling back to full sync")
                return pullFull(calendar, quirks, clientToUse, sessionBuilder)
            }
            return PullResult.Error(error.code, error.message, error.isRetryable)
        }

        val syncReport = (reportResult as CalDavResult.Success).data
        Log.d(TAG, "syncCollection: ${syncReport.changed.size} changed, ${syncReport.deleted.size} deleted")

        // RFC 6578 Section 3.6: 507 means server truncated results
        // Results are still valid - save the new token and next sync will continue
        if (syncReport.truncated) {
            Log.w(TAG, "Server returned truncated results (507). Will continue on next sync.")
            sessionBuilder?.setTruncated(true)
        }

        // Handle deletions (respecting pending local changes)
        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        for (href in syncReport.deleted) {
            val url = quirks.buildEventUrl(href, calendar.caldavUrl)
            val event = eventsDao.getByCaldavUrl(url)
            if (event != null) {
                // LOCAL-FIRST: Don't delete events with pending local changes
                // They may have been modified/recreated locally while offline
                if (event.hasPendingChanges()) {
                    Log.d(TAG, "Skipping deletion of $url - has pending local changes (${event.syncStatus})")
                    continue
                }
                // Track deletion for UI notification before deleting
                deletedChanges.add(SyncChange(
                    type = ChangeType.DELETED,
                    eventId = null, // Event will be deleted, so ID won't be valid
                    eventTitle = event.title,
                    eventStartTs = event.startTs,
                    isAllDay = event.isAllDay,
                    isRecurring = !event.rrule.isNullOrBlank(),
                    calendarName = calendar.displayName,
                    calendarColor = calendar.color ?: 0xFF2196F3.toInt()
                ))
                eventsDao.deleteById(event.id)
                deleted++
            }
        }

        // Fetch full iCal data for changed items
        // IMPORTANT: Apply .distinct() to handle iCloud returning duplicate hrefs
        // Without this, hrefsReported != receivedHrefs.size even when all events are fetched,
        // causing confusing "Missing: N" in Sync History when nothing is actually missing
        val rawHrefs = syncReport.changed
            .filter { it.status == SyncItemStatus.OK }
            .map { it.href }
        val changedHrefs = rawHrefs.distinct()

        // Log duplicate hrefs if detected (helps diagnose sync issues)
        val duplicateCount = rawHrefs.size - changedHrefs.size
        if (duplicateCount > 0) {
            Log.w(TAG, "sync-collection returned $duplicateCount duplicate hrefs (raw=${rawHrefs.size}, deduped=${changedHrefs.size})")
        }

        if (changedHrefs.isEmpty()) {
            // Clean up any duplicate master events even when no events changed
            // This handles accumulated duplicates from previous syncs
            val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
            if (dedupedCount > 0) {
                Log.w(TAG, "Cleaned up $dedupedCount duplicate master events during incremental sync (no changes)")
            }
            sessionBuilder?.addDeleted(deleted)
            return PullResult.Success(
                eventsAdded = 0,
                eventsUpdated = 0,
                eventsDeleted = deleted,
                newSyncToken = syncReport.syncToken,
                newCtag = null,
                changes = deletedChanges
            )
        }

        // Track hrefs reported by sync-collection
        sessionBuilder?.setHrefsReported(changedHrefs.size)

        // Fetch events with retry and fallback to individual fetches (v16.8.0)
        val fetchResult = fetchEventsWithFallback(
            clientToUse,
            calendar.caldavUrl,
            changedHrefs
        )
        val serverEvents = fetchResult.events

        // Track fallback usage in session
        if (fetchResult.fallbackUsed) {
            sessionBuilder?.setFallbackUsed(true)
            sessionBuilder?.setFetchFailedCount(fetchResult.fetchFailedCount)
        }

        // Track events fetched
        sessionBuilder?.setEventsFetched(serverEvents.size)

        // Detect missing events due to iCloud eventual consistency
        // sync-collection may return hrefs before calendar-data server has the actual data
        val receivedHrefs = serverEvents.map { it.href }.toSet()
        val missingHrefs = changedHrefs.filter { it !in receivedHrefs }
        val hasMissingEvents = missingHrefs.isNotEmpty()

        if (hasMissingEvents) {
            Log.w(TAG, "fetchEventsByHref: requested=${changedHrefs.size}, received=${serverEvents.size}, missing: $missingHrefs")
        }

        val processResult = processEvents(calendar, serverEvents, sessionBuilder)

        // Clean up any duplicate master events that may have accumulated
        // This handles edge cases where duplicates were created due to:
        // - iCloud hostname changes (p180 → p181)
        // - Race conditions during concurrent syncs
        val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
        if (dedupedCount > 0) {
            Log.w(TAG, "Cleaned up $dedupedCount duplicate master events during incremental sync")
        }

        // Combine deletion changes with add/update changes
        val allChanges = deletedChanges + processResult.changes

        // Determine if we should hold or advance the sync token
        // Priority: 1) Missing events (eventual consistency) 2) Parse errors (retry logic)
        val parseErrorCount = sessionBuilder?.getSkippedParseError() ?: 0
        val currentRetryCount = dataStore.getParseFailureRetryCount(calendar.id)

        val effectiveSyncToken = when {
            // Priority 1: Missing events - hold token for eventual consistency
            hasMissingEvents -> {
                Log.w(TAG, "NOT advancing sync token due to ${missingHrefs.size} missing events")
                sessionBuilder?.setTokenAdvanced(false)
                calendar.syncToken  // Keep old token - next sync will re-fetch
            }

            // Priority 2: Parse errors - retry logic (v16.7.0)
            parseErrorCount > 0 && currentRetryCount < MAX_PARSE_RETRIES -> {
                val newCount = dataStore.incrementParseFailureRetry(calendar.id)
                Log.w(TAG, "NOT advancing sync token due to $parseErrorCount parse errors (retry $newCount/$MAX_PARSE_RETRIES)")
                sessionBuilder?.setTokenAdvanced(false)
                calendar.syncToken  // Keep old token - retry on next sync
            }

            // Parse errors exceeded max retries - give up and advance
            parseErrorCount > 0 && currentRetryCount >= MAX_PARSE_RETRIES -> {
                Log.w(TAG, "Advancing sync token despite $parseErrorCount parse errors (max retries reached)")
                dataStore.resetParseFailureRetry(calendar.id)
                sessionBuilder?.setAbandonedParseErrors(parseErrorCount)
                sessionBuilder?.setTokenAdvanced(true)
                syncReport.syncToken  // Advance - abandon unrecoverable events
            }

            // No issues - normal advancement
            else -> {
                // Reset retry count on successful sync (no parse errors)
                if (currentRetryCount > 0) {
                    dataStore.resetParseFailureRetry(calendar.id)
                    Log.d(TAG, "Reset parse failure retry count for calendar ${calendar.id}")
                }
                sessionBuilder?.setTokenAdvanced(true)
                syncReport.syncToken  // Advance to new token (normal case)
            }
        }

        return PullResult.Success(
            eventsAdded = processResult.added,
            eventsUpdated = processResult.updated,
            eventsDeleted = deleted,
            newSyncToken = effectiveSyncToken,
            newCtag = if (effectiveSyncToken == calendar.syncToken) calendar.ctag else null,
            changes = allChanges
        )
    }

    /**
     * Full sync - fetch all events in time window.
     * Used for initial sync or when sync token is invalid.
     */
    private suspend fun pullFull(
        calendar: Calendar,
        quirks: CalDavQuirks,
        clientToUse: CalDavClient,
        sessionBuilder: SyncSessionBuilder?
    ): PullResult {
        // Clean up any duplicate master events before processing
        val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
        if (dedupedCount > 0) {
            Log.d(TAG, "Cleaned up $dedupedCount duplicate master events")
        }

        val now = System.currentTimeMillis()
        val startMs = now - PAST_WINDOW_MS
        val endMs = FUTURE_END_MS  // Unlimited future - fetch all future events

        // Fetch all events in range
        val eventsResult = clientToUse.fetchEventsInRange(calendar.caldavUrl, startMs, endMs)
        if (eventsResult.isError()) {
            val error = eventsResult as CalDavResult.Error
            return PullResult.Error(error.code, error.message, error.isRetryable)
        }

        val serverEvents = (eventsResult as CalDavResult.Success).data

        // Track events fetched (for full sync, hrefs reported = events fetched)
        sessionBuilder?.setHrefsReported(serverEvents.size)
        sessionBuilder?.setEventsFetched(serverEvents.size)

        // Get all server URLs for deletion detection
        val serverUrls = serverEvents.map { it.url }.toSet()

        // Find local events not on server anymore (within sync window)
        // LOCAL-FIRST: Exclude events with pending local changes from deletion
        val localEvents = eventsDao.getByCalendarIdInRange(calendar.id, startMs, endMs)
        val toDelete = localEvents.filter { event ->
            event.caldavUrl != null &&
            event.caldavUrl !in serverUrls &&
            !event.hasPendingChanges()  // Don't delete events with pending local changes
        }

        // Delete stale local events (only those SYNCED with server, not pending)
        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        for (event in toDelete) {
            Log.d(TAG, "Deleting stale event: ${event.caldavUrl}")
            // Track deletion for UI notification before deleting
            deletedChanges.add(SyncChange(
                type = ChangeType.DELETED,
                eventId = null,
                eventTitle = event.title,
                eventStartTs = event.startTs,
                isAllDay = event.isAllDay,
                isRecurring = !event.rrule.isNullOrBlank(),
                calendarName = calendar.displayName,
                calendarColor = calendar.color ?: 0xFF2196F3.toInt()
            ))
            eventsDao.deleteById(event.id)
            deleted++
        }

        // Process server events
        val processResult = processEvents(calendar, serverEvents, sessionBuilder)

        // Combine deletion changes with add/update changes
        val allChanges = deletedChanges + processResult.changes

        // Get new sync token if available
        val syncTokenResult = clientToUse.getSyncToken(calendar.caldavUrl)
        val newSyncToken = syncTokenResult.getOrNull()

        return PullResult.Success(
            eventsAdded = processResult.added,
            eventsUpdated = processResult.updated,
            eventsDeleted = deleted,
            newSyncToken = newSyncToken,
            newCtag = null,
            changes = allChanges
        )
    }

    /**
     * Etag-based fallback sync when sync-token expires (403/410).
     *
     * Instead of fetching all events (~834KB), this fetches only etags (~33KB),
     * compares with local database, and multigets only changed events.
     * Saves ~96% bandwidth for large calendars.
     *
     * @return PullResult.Success if sync worked, null if should fall through to pullFull()
     */
    private suspend fun pullWithEtagComparison(
        calendar: Calendar,
        quirks: CalDavQuirks,
        clientToUse: CalDavClient,
        sessionBuilder: SyncSessionBuilder?
    ): PullResult? {
        Log.d(TAG, "Attempting etag-based fallback sync for calendar: ${calendar.displayName}")

        // Step 1: Load local etags
        val localEtags = eventsDao.getEtagsByCalendarId(calendar.id)
        if (localEtags.isEmpty()) {
            Log.d(TAG, "No local events with etags - falling through to pullFull")
            return null  // No local data to compare, fall through to full sync
        }

        // Build local lookup map (caldavUrl -> etag)
        val localEtagMap = localEtags.associate { it.caldavUrl to it.etag }
        Log.d(TAG, "Local etags loaded: ${localEtagMap.size} events")

        // Step 2: Fetch server etags (lightweight - no iCal data)
        val now = System.currentTimeMillis()
        val startMs = now - PAST_WINDOW_MS
        val endMs = FUTURE_END_MS

        val etagResult = clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
        if (etagResult.isError()) {
            val error = etagResult as CalDavResult.Error
            Log.w(TAG, "fetchEtagsInRange failed: ${error.code} - ${error.message}, falling through to pullFull")
            return null  // Etag fetch failed, fall through to full sync
        }

        val serverEtags = (etagResult as CalDavResult.Success).data
        Log.d(TAG, "Server etags fetched: ${serverEtags.size} events")

        // Build server lookup map (full URL -> etag)
        val serverEtagMap = serverEtags.associate { (href, etag) ->
            quirks.buildEventUrl(href, calendar.caldavUrl) to etag
        }

        // Step 3: Compare etags to find changes
        // Changed: etag differs (including null -> non-null)
        // New: on server but not local
        // Deleted: on local but not server (handled separately)

        val changedUrls = mutableListOf<String>()
        val newUrls = mutableListOf<String>()

        for ((serverUrl, serverEtag) in serverEtagMap) {
            val localEtag = localEtagMap[serverUrl]
            if (localEtag == null) {
                // New event on server
                newUrls.add(serverUrl)
            } else if (localEtag != serverEtag) {
                // Etag differs - event changed
                changedUrls.add(serverUrl)
            }
            // else: etag matches, no change needed
        }

        // Find deleted events (on local but not server)
        val deletedUrls = localEtagMap.keys.filter { it !in serverEtagMap }

        Log.d(TAG, "Etag comparison: changed=${changedUrls.size}, new=${newUrls.size}, deleted=${deletedUrls.size}")

        // Step 4: Handle deletions (respecting pending local changes)
        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        for (url in deletedUrls) {
            val event = eventsDao.getByCaldavUrl(url)
            if (event != null) {
                // LOCAL-FIRST: Don't delete events with pending local changes
                if (event.hasPendingChanges()) {
                    Log.d(TAG, "Skipping deletion of $url - has pending local changes (${event.syncStatus})")
                    continue
                }
                deletedChanges.add(SyncChange(
                    type = ChangeType.DELETED,
                    eventId = null,
                    eventTitle = event.title,
                    eventStartTs = event.startTs,
                    isAllDay = event.isAllDay,
                    isRecurring = !event.rrule.isNullOrBlank(),
                    calendarName = calendar.displayName,
                    calendarColor = calendar.color ?: 0xFF2196F3.toInt()
                ))
                eventsDao.deleteById(event.id)
                deleted++
            }
        }

        // Step 5: Fetch changed + new events via multiget
        val hrefsToFetch = (changedUrls + newUrls).map { url ->
            // Convert full URL back to href for multiget
            if (url.contains("://")) {
                "/" + url.substringAfter("://").substringAfter("/")
            } else {
                url
            }
        }

        if (hrefsToFetch.isEmpty()) {
            // No changes to fetch, just deletions
            Log.d(TAG, "No events to fetch - only deletions")
            sessionBuilder?.addDeleted(deleted)

            // Get new sync token
            val syncTokenResult = clientToUse.getSyncToken(calendar.caldavUrl)
            val newSyncToken = syncTokenResult.getOrNull()

            return PullResult.Success(
                eventsAdded = 0,
                eventsUpdated = 0,
                eventsDeleted = deleted,
                newSyncToken = newSyncToken,
                newCtag = null,
                changes = deletedChanges
            )
        }

        // Track hrefs for session
        sessionBuilder?.setHrefsReported(hrefsToFetch.size)

        // Fetch events with fallback
        val fetchResult = fetchEventsWithFallback(
            clientToUse,
            calendar.caldavUrl,
            hrefsToFetch
        )
        val serverEvents = fetchResult.events

        if (fetchResult.fallbackUsed) {
            sessionBuilder?.setFallbackUsed(true)
            sessionBuilder?.setFetchFailedCount(fetchResult.fetchFailedCount)
        }
        sessionBuilder?.setEventsFetched(serverEvents.size)

        Log.d(TAG, "Fetched ${serverEvents.size} events via multiget (requested ${hrefsToFetch.size})")

        // Step 6: Process fetched events
        val processResult = processEvents(calendar, serverEvents, sessionBuilder)

        // Combine deletion changes with add/update changes
        val allChanges = deletedChanges + processResult.changes

        // Get new sync token
        val syncTokenResult = clientToUse.getSyncToken(calendar.caldavUrl)
        val newSyncToken = syncTokenResult.getOrNull()

        return PullResult.Success(
            eventsAdded = processResult.added,
            eventsUpdated = processResult.updated,
            eventsDeleted = deleted,
            newSyncToken = newSyncToken,
            newCtag = null,
            changes = allChanges
        )
    }

    /**
     * Result of processing events - includes counts and individual changes for UI.
     */
    private data class ProcessEventsResult(
        val added: Int,
        val updated: Int,
        val changes: List<SyncChange>
    )

    /**
     * Process fetched events: parse, map, and save to database.
     * Returns counts and individual SyncChange objects for UI notification.
     *
     * IMPORTANT: Respects local-first architecture by skipping events with pending
     * local changes (PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE). These events
     * will be pushed to server first, and any conflicts resolved via ETag/sequence.
     *
     * See: https://developer.android.com/topic/architecture/data-layer/offline-first
     */
    private suspend fun processEvents(
        calendar: Calendar,
        serverEvents: List<CalDavEvent>,
        sessionBuilder: SyncSessionBuilder? = null
    ): ProcessEventsResult {
        var added = 0
        var updated = 0
        val changes = mutableListOf<SyncChange>()

        // Read user's default reminder settings
        val defaultReminderMinutes = dataStore.defaultReminderMinutes.first()
        val defaultAllDayReminderMinutes = dataStore.defaultAllDayReminder.first()

        // First pass: collect all parsed events, separate masters from exceptions
        val masterEvents = mutableListOf<ParsedEventWithMeta>()
        val exceptionEvents = mutableListOf<ParsedEventWithMeta>()

        for (serverEvent in serverEvents) {
            val parseResult = try {
                icalParser.parseAllEvents(serverEvent.icalData)
            } catch (e: Throwable) {
                // Catch Throwable to catch Errors too (OutOfMemoryError, etc.)
                Log.e(TAG, "Parse exception for ${serverEvent.url}: ${e.javaClass.name}: ${e.message}")
                throw e
            }

            // Check for parse success
            val parsedEvents = when (parseResult) {
                is ParseResult.Success -> parseResult.value
                is ParseResult.Error -> {
                    Log.w(TAG, "Parse error for ${serverEvent.url}: ${parseResult.error.message}")
                    sessionBuilder?.incrementSkipParseError()
                    continue
                }
            }

            if (parsedEvents.isEmpty()) {
                Log.w(TAG, "Failed to parse event at ${serverEvent.url}: no VEVENT components found")
                sessionBuilder?.incrementSkipParseError()
                continue
            }

            for (parsed in parsedEvents) {
                val meta = ParsedEventWithMeta(
                    parsed = parsed,
                    rawIcal = serverEvent.icalData,
                    caldavUrl = serverEvent.url,
                    etag = serverEvent.etag
                )
                if (ICalEventMapper.isException(parsed)) {
                    exceptionEvents.add(meta)
                } else {
                    masterEvents.add(meta)
                }
            }
        }

        // Second pass: upsert master events (respecting pending local changes)
        val uidToMasterEvent = mutableMapOf<String, Event>()
        for (meta in masterEvents) {
            // PRIMARY: UID lookup (stable across server hostname changes like p180 vs p181)
            // SECONDARY: caldavUrl lookup (fallback for edge cases)
            val existingEvent = eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)
                ?: eventsDao.getByCaldavUrl(meta.caldavUrl)

            // LOCAL-FIRST: Skip events with pending local changes
            // These will be pushed to server first via PushStrategy
            // Server wins only AFTER local changes are synced (prevents data loss)
            if (existingEvent != null && existingEvent.hasPendingChanges()) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - has pending local changes (${existingEvent.syncStatus})")
                sessionBuilder?.incrementSkipPendingLocal()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // Skip if etag unchanged (prevents overwrite with stale data after push)
            // After successful push, local event has the new etag from server.
            // If server returns same etag, data is identical - no need to upsert.
            // This handles iCloud eventual consistency where pull may return stale data.
            if (existingEvent != null && existingEvent.etag == meta.etag) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - etag unchanged (${meta.etag})")
                sessionBuilder?.incrementSkipEtagUnchanged()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // Map ICalEvent to Event entity using ICalEventMapper
            var event = ICalEventMapper.toEntity(
                icalEvent = meta.parsed,
                rawIcal = meta.rawIcal,
                calendarId = calendar.id,
                caldavUrl = meta.caldavUrl,
                etag = meta.etag
            )

            // Apply default reminders if server didn't provide any
            if (event.reminders.isNullOrEmpty()) {
                val defaultMinutes = if (event.isAllDay) defaultAllDayReminderMinutes else defaultReminderMinutes
                if (defaultMinutes > 0) {
                    val defaultReminder = minutesToIsoDuration(defaultMinutes)
                    event = event.copy(reminders = listOf(defaultReminder))
                }
            }

            // Preserve existing event ID and timestamps
            if (existingEvent != null) {
                event = event.copy(
                    id = existingEvent.id,
                    createdAt = existingEvent.createdAt,
                    localModifiedAt = existingEvent.localModifiedAt
                )
            }

            // TRANSACTION: Upsert event and generate occurrences atomically
            // Prevents orphaned events (no occurrences) if crash occurs mid-operation
            // Wrapped in withDbRetry for resilience against database lock errors
            val savedEvent = try {
                withDbRetry {
                    database.runInTransaction {
                        val eventId = eventsDao.upsert(event)
                        val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                        // Regenerate occurrences for recurring events
                        if (saved.rrule != null) {
                            val now = System.currentTimeMillis()
                            occurrenceGenerator.generateOccurrences(
                                event = saved,
                                rangeStartMs = now - PAST_WINDOW_MS,
                                rangeEndMs = now + OCCURRENCE_EXPANSION_MS
                            )
                        } else {
                            // Non-recurring: generate single occurrence
                            occurrenceGenerator.regenerateOccurrences(saved)
                        }

                        saved // Return from transaction
                    }
                }
            } catch (e: SQLiteConstraintException) {
                // Check if this is a duplicate UID (unique constraint on uid, calendar_id)
                val existing = eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)
                if (existing != null) {
                    Log.w(TAG, "Duplicate UID detected for ${meta.parsed.uid}, using existing event")
                    uidToMasterEvent[meta.parsed.uid] = existing
                    continue
                }
                // Not a duplicate — FK or other constraint error. Skip to prevent sync abort loop (issue #55)
                Log.w(TAG, "Constraint error for master ${meta.parsed.uid} (${meta.caldavUrl}), skipping: ${e.message}")
                sessionBuilder?.incrementSkipConstraintError()
                continue
            }

            uidToMasterEvent[meta.parsed.uid] = savedEvent

            // Track change for UI notification
            val changeType = if (existingEvent == null) ChangeType.NEW else ChangeType.MODIFIED
            if (existingEvent == null) {
                added++
                sessionBuilder?.incrementWritten()
                Log.d(TAG, "Pulled new event: ${savedEvent.title} with etag='${savedEvent.etag}'")
            } else {
                updated++
                sessionBuilder?.incrementUpdated()
                Log.d(TAG, "Updated event: ${savedEvent.title} with etag='${savedEvent.etag}'")
            }

            changes.add(SyncChange(
                type = changeType,
                eventId = savedEvent.id,
                eventTitle = savedEvent.title,
                eventStartTs = savedEvent.startTs,
                isAllDay = savedEvent.isAllDay,
                isRecurring = !savedEvent.rrule.isNullOrBlank(),
                calendarName = calendar.displayName,
                calendarColor = calendar.color ?: 0xFF2196F3.toInt() // Default blue
            ))
        }

        // Third pass: link and upsert exception events (respecting pending local changes)
        for (meta in exceptionEvents) {
            val masterEvent = uidToMasterEvent[meta.parsed.uid]
                ?: eventsDao.getByUid(meta.parsed.uid).firstOrNull { it.rrule != null }

            if (masterEvent == null) {
                // Enhanced logging for orphaned exception events
                // This can happen when: master is outside sync window, master was deleted,
                // or sync order issue (exception synced before master)
                Log.w(TAG, """
                    |Orphaned exception event dropped:
                    |  UID: ${meta.parsed.uid}
                    |  RECURRENCE-ID: ${meta.parsed.recurrenceId?.timestamp}
                    |  caldavUrl: ${meta.caldavUrl}
                    |  Title: ${meta.parsed.summary}
                    |  Possible causes: Master outside sync window, master deleted, or sync order issue.
                """.trimMargin())
                sessionBuilder?.incrementSkipOrphanedException()
                continue
            }

            // Get original instance time from RECURRENCE-ID
            val originalInstanceTime = meta.parsed.recurrenceId?.timestamp

            // Find existing exception by UID + instance time (RFC 5545 compliant)
            // Uses server-stable identifiers - doesn't break when master ID changes
            val existingException = originalInstanceTime?.let {
                eventsDao.getExceptionByUidAndInstanceTime(
                    uid = meta.parsed.uid,
                    calendarId = calendar.id,
                    originalInstanceTime = it
                )
            }

            // LOCAL-FIRST: Skip exception events with pending local changes
            if (existingException != null && existingException.hasPendingChanges()) {
                Log.d(TAG, "Skipping exception ${meta.caldavUrl} - has pending local changes (${existingException.syncStatus})")
                sessionBuilder?.incrementSkipPendingLocal()
                continue
            }

            // Skip if etag unchanged (prevents overwrite with stale data after push)
            if (existingException != null && existingException.etag == meta.etag) {
                Log.d(TAG, "Skipping exception ${meta.caldavUrl} - etag unchanged (${meta.etag})")
                sessionBuilder?.incrementSkipEtagUnchanged()
                continue
            }

            // Map ICalEvent to Event entity using ICalEventMapper
            var event = ICalEventMapper.toEntity(
                icalEvent = meta.parsed,
                rawIcal = meta.rawIcal,
                calendarId = calendar.id,
                caldavUrl = meta.caldavUrl,
                etag = meta.etag
            )

            // Apply default reminders if server didn't provide any
            if (event.reminders.isNullOrEmpty()) {
                val defaultMinutes = if (event.isAllDay) defaultAllDayReminderMinutes else defaultReminderMinutes
                if (defaultMinutes > 0) {
                    val defaultReminder = minutesToIsoDuration(defaultMinutes)
                    event = event.copy(reminders = listOf(defaultReminder))
                }
            }

            // Link to master event
            event = event.copy(
                originalEventId = masterEvent.id,
                originalSyncId = meta.parsed.uid
            )

            // Preserve existing event ID and timestamps
            if (existingException != null) {
                event = event.copy(
                    id = existingException.id,
                    createdAt = existingException.createdAt,
                    localModifiedAt = existingException.localModifiedAt
                )
            }

            // TRANSACTION: Upsert exception, link to master's occurrence atomically
            // Uses Model B (linked occurrence) consistently to prevent duplicates.
            // linkException handles: delete Model A occurrence (if exists), update/create Model B.
            // Wrapped in withDbRetry for resilience against database lock errors
            val savedExceptionEvent = try {
                withDbRetry {
                    database.runInTransaction {
                        val eventId = eventsDao.upsert(event)
                        val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                        // Link exception to master's occurrence (Model B)
                        // This normalizes any existing Model A to Model B, preventing duplicates
                        val originalTime = event.originalInstanceTime
                        if (originalTime != null) {
                            occurrenceGenerator.linkException(masterEvent.id, originalTime, saved)
                        } else {
                            // Fallback: no original time means standalone occurrence
                            occurrenceGenerator.regenerateOccurrences(saved)
                        }

                        saved // Return from transaction
                    }
                }
            } catch (e: SQLiteConstraintException) {
                // FK or other constraint error on exception event. Skip to prevent sync abort loop (issue #55)
                Log.w(TAG, "Constraint error for exception ${meta.parsed.uid} " +
                    "(RECURRENCE-ID: ${meta.parsed.recurrenceId?.timestamp}), skipping: ${e.message}")
                sessionBuilder?.incrementSkipConstraintError()
                continue
            }

            // Track change for UI notification (exception events are shown like regular events)
            val changeType = if (existingException == null) ChangeType.NEW else ChangeType.MODIFIED
            if (existingException == null) {
                added++
                sessionBuilder?.incrementWritten()
            } else {
                updated++
                sessionBuilder?.incrementUpdated()
            }

            changes.add(SyncChange(
                type = changeType,
                eventId = savedExceptionEvent.id,
                eventTitle = savedExceptionEvent.title,
                eventStartTs = savedExceptionEvent.startTs,
                isAllDay = savedExceptionEvent.isAllDay,
                isRecurring = true, // Exception events are always from recurring series
                calendarName = calendar.displayName,
                calendarColor = calendar.color ?: 0xFF2196F3.toInt()
            ))
        }

        return ProcessEventsResult(added, updated, changes)
    }

    /**
     * Convert reminder minutes to ISO 8601 duration format.
     * Positive minutes = before event (negative trigger in iCal).
     */
    private fun minutesToIsoDuration(minutes: Int): String {
        return when {
            minutes <= 0 -> "PT0M"
            minutes < 60 -> "-PT${minutes}M"
            minutes < 1440 -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0) "-PT${hours}H" else "-PT${hours}H${mins}M"
            }
            minutes < 10080 -> { // Less than 1 week
                val days = minutes / 1440
                val hours = (minutes % 1440) / 60
                if (hours == 0) "-P${days}D" else "-P${days}DT${hours}H"
            }
            else -> {
                val weeks = minutes / 10080
                "-P${weeks}W"
            }
        }
    }

    /**
     * Helper class to hold parsed event with metadata.
     */
    private data class ParsedEventWithMeta(
        val parsed: ICalEvent,
        val rawIcal: String,
        val caldavUrl: String,
        val etag: String?
    )

    /**
     * Result of fetchEventsWithFallback containing events and tracking info.
     */
    private data class FetchResult(
        val events: List<CalDavEvent>,
        val fallbackUsed: Boolean,
        val fetchFailedCount: Int
    )

    /**
     * Fetch events with retry and fallback to individual fetches (v16.8.0).
     *
     * Strategy:
     * 1. Try batch multiget (normal path)
     * 2. If batch fails, retry once after delay
     * 3. If still fails, fall back to individual fetches in small batches
     * 4. Return whatever events we successfully fetched (missing handled by caller)
     *
     * This handles transient network issues and isolates problematic events.
     */
    private suspend fun fetchEventsWithFallback(
        client: CalDavClient,
        calendarUrl: String,
        hrefs: List<String>
    ): FetchResult {
        if (hrefs.isEmpty()) return FetchResult(emptyList(), fallbackUsed = false, fetchFailedCount = 0)

        // Step 1: Try batch multiget
        Log.d(TAG, "fetchEventsWithFallback: attempting batch fetch of ${hrefs.size} events")
        val batchResult = client.fetchEventsByHref(calendarUrl, hrefs)

        if (batchResult is CalDavResult.Success) {
            Log.d(TAG, "fetchEventsWithFallback: batch fetch succeeded, got ${batchResult.data.size} events")
            return FetchResult(batchResult.data, fallbackUsed = false, fetchFailedCount = 0)
        }

        // Log the error details
        val batchError = batchResult as CalDavResult.Error
        Log.w(TAG, "fetchEventsWithFallback: batch fetch FAILED - code=${batchError.code}, " +
                "message='${batchError.message}', retryable=${batchError.isRetryable}, hrefs=${hrefs.size}")

        // Step 2: Retry once after delay (handles transient issues)
        Log.d(TAG, "fetchEventsWithFallback: retrying batch after ${MULTIGET_RETRY_DELAY_MS}ms delay")
        delay(MULTIGET_RETRY_DELAY_MS)

        val retryResult = client.fetchEventsByHref(calendarUrl, hrefs)
        if (retryResult is CalDavResult.Success) {
            Log.d(TAG, "fetchEventsWithFallback: batch retry succeeded, got ${retryResult.data.size} events")
            return FetchResult(retryResult.data, fallbackUsed = false, fetchFailedCount = 0)
        }

        val retryError = retryResult as CalDavResult.Error
        Log.w(TAG, "fetchEventsWithFallback: batch retry FAILED - code=${retryError.code}, " +
                "message='${retryError.message}', falling back to individual fetches")

        // Step 3: Fall back to individual fetches in small batches
        val fetchedEvents = mutableListOf<CalDavEvent>()
        val failedHrefs = mutableListOf<String>()

        // Process in small batches to balance efficiency and isolation
        hrefs.chunked(INDIVIDUAL_FETCH_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            Log.d(TAG, "fetchEventsWithFallback: individual batch ${batchIndex + 1}, " +
                    "fetching ${batch.size} events")

            val smallBatchResult = client.fetchEventsByHref(calendarUrl, batch)
            if (smallBatchResult is CalDavResult.Success) {
                fetchedEvents.addAll(smallBatchResult.data)
                Log.d(TAG, "fetchEventsWithFallback: small batch succeeded, got ${smallBatchResult.data.size}")
            } else {
                // Small batch failed, try one-by-one
                val smallBatchError = smallBatchResult as CalDavResult.Error
                Log.w(TAG, "fetchEventsWithFallback: small batch failed - code=${smallBatchError.code}, " +
                        "trying individual fetches")

                for (href in batch) {
                    val singleResult = client.fetchEventsByHref(calendarUrl, listOf(href))
                    if (singleResult is CalDavResult.Success && singleResult.data.isNotEmpty()) {
                        fetchedEvents.addAll(singleResult.data)
                    } else {
                        failedHrefs.add(href)
                        val singleError = singleResult as? CalDavResult.Error
                        Log.w(TAG, "fetchEventsWithFallback: individual fetch FAILED for href=$href, " +
                                "code=${singleError?.code}, message='${singleError?.message}'")
                    }
                }
            }
        }

        if (failedHrefs.isNotEmpty()) {
            Log.w(TAG, "fetchEventsWithFallback: completed with ${failedHrefs.size} failed hrefs: " +
                    failedHrefs.take(5).joinToString())
        }

        Log.d(TAG, "fetchEventsWithFallback: fallback complete - requested=${hrefs.size}, " +
                "fetched=${fetchedEvents.size}, failed=${failedHrefs.size}")

        return FetchResult(fetchedEvents, fallbackUsed = true, fetchFailedCount = failedHrefs.size)
    }
}
