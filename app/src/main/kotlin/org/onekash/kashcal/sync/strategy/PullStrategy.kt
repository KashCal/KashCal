package org.onekash.kashcal.sync.strategy

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.parser.ServerColorParser
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.util.CaldavUrlNormalizer
import org.onekash.kashcal.sync.strategy.PullStrategy.Companion.MULTIGET_BATCH_SIZE
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

/**
 * Sentinel placed in [Event.extraProperties] on a synthetic master row.
 *
 * A synthetic master is a placeholder created when an exception VEVENT
 * arrives without its master VEVENT in the same pull (master outside the
 * sync lookback window, server returned them in different multiget batches,
 * or the master was deleted server-side but exception lingers). The
 * exception's `originalEventId` FK points at the synthetic so the row
 * survives ingest. When the real master arrives in a later sync, the
 * UID-keyed `@Upsert` mutates the synthetic in place — same row id, real
 * RRULE populated, sentinel cleared — so existing exception FKs survive
 * without churn.
 *
 * The string value matches the constant in IcsSubscriptionRepository so
 * the existing FTS-search and title-suggest exclusions cover both paths.
 * The two declarations are intentionally independent: ICS sync and CalDAV
 * pull are separate code paths with different surrounding logic.
 */
internal const val PULL_SYNTHETIC_MASTER_EXTRA_KEY = "X-KASHCAL-SYNTHETIC-MASTER"

/**
 * Build a placeholder master Event for an orphan exception. The synthetic
 * carries `rrule = null`, `status = "CANCELLED"`, and the sentinel above
 * in [Event.extraProperties]. Inserting it via `eventsDao.upsert` returns
 * a real row id that the orphan exception can reference via
 * `originalEventId`. When the real master arrives later, pass 2's upsert
 * matches by (uid, calendarId, original_event_id IS NULL) and mutates
 * this row in place.
 *
 * Caller is responsible for skipping `regenerateOccurrences` on synthetics
 * — the row has no rrule so the non-recurring path would emit a phantom
 * occurrence at `recurrenceIdMs`.
 */
internal fun synthesizeMasterForOrphanException(
    uid: String,
    calendarId: Long,
    recurrenceIdMs: Long,
    placeholderTitle: String,
): Event {
    val now = System.currentTimeMillis()
    return Event(
        uid = uid,
        importId = uid,
        calendarId = calendarId,
        title = placeholderTitle,
        startTs = recurrenceIdMs,
        endTs = recurrenceIdMs,
        dtstamp = now,
        status = "CANCELLED",
        rrule = null,
        syncStatus = SyncStatus.SYNCED,
        extraProperties = mapOf(PULL_SYNTHETIC_MASTER_EXTRA_KEY to "true"),
    )
}

class PullStrategy @Inject constructor(
    private val database: KashCalDatabase,
    private val calendarRepository: CalendarRepository,
    private val eventsDao: EventsDao,
    private val attendeesDao: AttendeesDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    @Suppress("DEPRECATION") private val defaultQuirks: CalDavQuirks,
    private val dataStore: KashCalDataStore,
    private val inviteNotifier: org.onekash.kashcal.sync.notification.InviteNotifier,
    private val accountRepository: org.onekash.kashcal.data.repository.AccountRepository,
    private val reminderScheduler: org.onekash.kashcal.reminder.scheduler.ReminderScheduler
) {
    // icaldav parser instance
    private val icalParser = ICalParser()

    private val categoryDao by lazy { database.categoryDao() }

    /**
     * Seed the tag metadata table for each category carried by a pulled event
     * so a tag first seen on the server appears in suggestions and the
     * management screen. Color-preserving (never clobbers a user's chosen
     * color), case-insensitive (the NOCASE primary key collapses cased
     * duplicates). Runs inside the event's upsert transaction.
     *
     * Recency is dated to the event's own last-modified/start time, not wall-
     * clock now: a bulk pull of old events must not rank their tags as
     * just-used, and the raise-only update never rolls back a newer local use.
     *
     * Note: this can resurrect a tag the user deleted locally if the server
     * still has events carrying it — accepted behavior, since a tag that labels
     * live events shouldn't silently vanish.
     */
    private suspend fun seedCategories(event: Event) {
        val recency = event.localModifiedAt ?: event.startTs
        event.categories?.forEach { name ->
            if (name.isNotBlank()) categoryDao.seedFromPull(name, recency)
        }
    }

    companion object {
        private const val TAG = "PullStrategy"

        /** Extract .ics filename from caldavUrl for privacy-safe warning messages. */
        private fun filenameOf(caldavUrl: String): String =
            caldavUrl.substringAfterLast('/').ifEmpty { caldavUrl }

        /**
         * Validate that mapped event has sane timestamps.
         * Rejects endTs < startTs (RFC 5545 violation, always corrupt server data).
         * Does NOT reject historical events or epoch-zero — those are legitimate dates.
         */
        internal fun hasValidTimestamps(event: Event): Boolean =
            event.endTs >= event.startTs

        /**
         * CalDAV uses one etag per .ics resource. When any VEVENT in a recurring
         * series changes, the etag changes for ALL of them. This comparison detects
         * VEVENTs whose content is unchanged — we still upsert (new etag) but skip
         * the UI notification.
         */
        internal fun hasContentChanged(existing: Event, incoming: Event): Boolean =
            stripSyncMetadata(existing) != stripSyncMetadata(incoming)

        private fun stripSyncMetadata(e: Event): Event = e.copy(
            id = 0, etag = null, syncStatus = SyncStatus.SYNCED,
            caldavUrl = null, rawIcal = null, dtstamp = 0,
            importId = null, originalEventId = null, originalInstanceTime = null,
            originalSyncId = null, calendarId = 0, uid = "",
            createdAt = 0, updatedAt = 0, localModifiedAt = null,
            serverModifiedAt = null, lastSyncError = null, syncRetryCount = 0
        )

        // Sync window: 1 year back, unlimited future (far-future date for CalDAV spec compliance)
        private const val PAST_WINDOW_MS = 365L * 24 * 60 * 60 * 1000
        // Upper bound for the LOCAL Room range queries (stale-event deletion, etag map).
        // SQL needs a concrete numeric bound; 2100 is unreachable by real events, so it means
        // "forever" for the DB. This is NOT the server fetch reach: the CalDAV client drops the
        // upper bound on the wire past 2038 (32-bit time_t servers like SOGo silently truncate
        // results otherwise), so future reach is already unbounded. Raising this number does not
        // fetch more from the server — don't bump it to "reach further."
        private const val FUTURE_END_MS = 4102444800000L  // Jan 1, 2100 UTC

        // Occurrence expansion window (local generation) - shared across codebase
        const val OCCURRENCE_EXPANSION_MS = 2 * 365L * 24 * 60 * 60 * 1000  // 2 years

        // Parse failure retry: hold token for N syncs before giving up (v16.7.0)
        private const val MAX_PARSE_RETRIES = 3

        // Batched multiget: max hrefs per calendar-multiget request (v22.5.11)
        private const val MULTIGET_BATCH_SIZE = 20

        // DB retry configuration
        private const val MAX_DB_RETRIES = 3
        private const val INITIAL_DB_RETRY_DELAY_MS = 100L

        /**
         * Check if ICS data contains non-event RFC 5545 components but no VEVENTs.
         * Used to distinguish "valid non-event resource" from "genuinely failed to parse"
         * when parseAllEvents() returns empty.
         */
        private fun isNonEventResource(icalData: String): Boolean {
            return !icalData.contains("BEGIN:VEVENT") &&
                (icalData.contains("BEGIN:VTODO") ||
                 icalData.contains("BEGIN:VJOURNAL") ||
                 icalData.contains("BEGIN:VFREEBUSY"))
        }
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
                        // Bounded bit-shift to cap exponential backoff
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
     * Refresh calendar metadata from the server probe. Server non-null-and-
     * valid wins; null/invalid preserves local. Skips the DB write when no
     * field changed to avoid churning getVisibleCalendarsFlow consumers.
     */
    private suspend fun maybeRefreshMetadata(
        calendar: Calendar,
        probe: CalendarMetadataProbe
    ) {
        val newColor = ServerColorParser.parseCaldavColorToArgb(probe.color)
            ?.takeIf { it != calendar.color }
        val newDisplayName = probe.displayName?.takeIf { it != calendar.displayName }
        val newIsReadOnly = probe.isReadOnly?.takeIf { it != calendar.isReadOnly }

        if (newColor == null && newDisplayName == null && newIsReadOnly == null) return

        calendarRepository.updateMetadata(
            calendarId = calendar.id,
            color = newColor,
            displayName = newDisplayName,
            isReadOnly = newIsReadOnly
        )
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
        sessionBuilder: SyncSessionBuilder? = null,
        recentlyPushedEventIds: Set<Long> = emptySet()
    ): PullResult {
        val effectiveQuirks = quirks ?: defaultQuirks
        val effectiveClient = client

        return try {
            // Step 1: Probe ctag + metadata (displayName, color, isReadOnly)
            val ctagResult = effectiveClient.getCtag(calendar.caldavUrl)
            val probe: CalendarMetadataProbe? = if (ctagResult.isSuccess()) {
                (ctagResult as CalDavResult.Success).data
            } else {
                val error = ctagResult as CalDavResult.Error
                // Auth/permission errors are systemic — abort immediately
                if (error.code == 401 || error.code == 403) {
                    Log.e(TAG, "getCtag failed: ${error.code} - ${error.message}")
                    return PullResult.Error(code = error.code, message = error.message, isRetryable = error.isRetryable)
                }
                // getctag is a CalendarServer extension, not core CalDAV RFC 4791.
                // Servers like Zoho don't support it. Skip the ctag optimization and
                // metadata refresh, proceed with event sync.
                Log.w(TAG, "getCtag unavailable (${error.code}: ${error.message}), proceeding without ctag")
                null
            }
            val serverCtag: String? = probe?.ctag

            // Refresh calendar metadata before the NoChanges early return because
            // some servers don't bump ctag on metadata-only changes.
            if (probe != null) {
                maybeRefreshMetadata(calendar, probe)
            }

            Log.d(TAG, "ctag check: server=$serverCtag, local=${calendar.ctag}, force=$forceFullSync")
            if (!forceFullSync && serverCtag == calendar.ctag && calendar.ctag != null) {
                Log.d(TAG, "No changes (ctag unchanged)")
                return PullResult.NoChanges
            }

            // Step 2: Determine sync method
            val result = if (!forceFullSync && calendar.syncToken != null) {
                // Incremental sync using sync-token
                pullIncremental(calendar, effectiveQuirks, effectiveClient, sessionBuilder, recentlyPushedEventIds)
            } else {
                // Full sync - fetch all events in time window
                pullFull(calendar, effectiveQuirks, effectiveClient, sessionBuilder, recentlyPushedEventIds, forceFullSync = forceFullSync)
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
                message = e.message ?: e.javaClass.simpleName,
                isRetryable = false
            )
        }
    }

    /**
     * Build a resolver that maps a server-reported resource URL to the local
     * event whose stored caldav_url matches it, tolerating percent-encoding
     * differences. Some servers (e.g. Radicale) echo a resource href with
     * pchar-legal reserved characters percent-encoded (a literal '@' comes back
     * as %40) while KashCal stored the URL with the literal character. An exact
     * match is tried first (index-backed and the common case); on a miss the URL
     * is compared canonically against the calendar's stored URLs.
     *
     * Reuse a single resolver across a whole deletion loop: the heavier canonical
     * candidate map is loaded at most once — on the first exact-match miss — then
     * reused. This matters on servers that re-encode every href, where every
     * lookup misses the fast path; a per-lookup reload would be O(deletions x
     * calendar size). Create it per loop (its cache must not outlive the loop).
     */
    private fun caldavUrlResolver(calendarId: Long): suspend (String) -> Event? {
        var canonicalMap: Map<String, Event>? = null
        return resolve@{ url ->
            eventsDao.getByCaldavUrl(url)?.let { return@resolve it }
            val target = CaldavUrlNormalizer.canonicalize(url) ?: return@resolve null
            val map = canonicalMap ?: eventsDao.getEventsWithCaldavUrl(calendarId)
                .mapNotNull { e ->
                    val u = e.caldavUrl ?: return@mapNotNull null
                    (CaldavUrlNormalizer.canonicalize(u) ?: u) to e
                }
                .toMap()
                .also { canonicalMap = it }
            map[target]
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
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long> = emptySet()
    ): PullResult {
        Log.d(TAG, "Incremental sync with token: ${calendar.syncToken?.take(8)}...")

        val reportResult = clientToUse.syncCollection(calendar.caldavUrl, calendar.syncToken)
        if (reportResult.isError()) {
            val error = reportResult as CalDavResult.Error
            // 403/410 means sync token expired - try etag comparison first, then full sync
            // Etag comparison saves ~96% bandwidth (33KB vs 834KB for 231 events)
            if (error.code == 403 || error.code == 410) {
                Log.w(TAG, "Sync token expired (${error.code}), trying etag-based fallback")
                val etagResult = pullWithEtagComparison(calendar, quirks, clientToUse, sessionBuilder, recentlyPushedEventIds)
                if (etagResult != null) {
                    Log.d(TAG, "Etag-based fallback succeeded")
                    return etagResult
                }
                Log.w(TAG, "Etag fallback returned null, falling back to full sync")
                return pullFull(calendar, quirks, clientToUse, sessionBuilder, recentlyPushedEventIds)
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
        val resolveLocalEvent = caldavUrlResolver(calendar.id)
        // Dedupe like the changed-href list below: a server may report the same
        // deleted href more than once (iCloud is known to). The resolver caches a
        // per-loop candidate map, so a repeated href would otherwise resolve the
        // just-deleted row again and double-count the deletion + notification.
        for (href in syncReport.deleted.distinct()) {
            val url = quirks.buildEventUrl(href, calendar.caldavUrl)
            val event = resolveLocalEvent(url)
            if (event != null) {
                // LOCAL-FIRST: Don't delete events with pending local changes
                // They may have been modified/recreated locally while offline
                // RFC 4791: Don't delete recently pushed events — server may not
                // have indexed them yet (no immediate visibility guarantee after PUT)
                if (event.hasPendingChanges() || event.id in recentlyPushedEventIds) {
                    Log.d(TAG, "Skipping deletion of $url - " +
                        if (event.hasPendingChanges()) "has pending local changes (${event.syncStatus})"
                        else "recently pushed in this sync cycle")
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
                    calendarColor = calendar.color
                ))
                eventsDao.deleteById(event.id)
                deleted++
            } else {
                Log.d(TAG, "Deletion href matched no local event: $url")
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

        // Fetch events in batched concurrent multiget (v22.5.11)
        val fetchResult = fetchEventsBatched(clientToUse, calendar.caldavUrl, changedHrefs, sessionBuilder)
        val serverEvents = fetchResult.events
        sessionBuilder?.setEventsFetched(serverEvents.size)

        // Detect missing events due to iCloud eventual consistency
        // sync-collection may return hrefs before calendar-data server has the actual data
        val receivedHrefs = serverEvents.map { it.href }.toSet()
        val missingHrefs = changedHrefs.filter { it !in receivedHrefs }
        val hasMissingEvents = missingHrefs.isNotEmpty()

        if (hasMissingEvents) {
            Log.w(TAG, "fetchEventsByHref: requested=${changedHrefs.size}, received=${serverEvents.size}, missing: $missingHrefs")
        }

        // Incremental sync: syncToken exists, so this is never initial sync
        val processResult = processEvents(calendar, serverEvents, sessionBuilder, recentlyPushedEventIds, isInitialSync = false)

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
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long> = emptySet(),
        forceFullSync: Boolean = false
    ): PullResult {
        // Clean up any duplicate master events before processing
        val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
        if (dedupedCount > 0) {
            Log.d(TAG, "Cleaned up $dedupedCount duplicate master events")
        }

        // Read configurable lookback window (matches pullWithEtagComparison pattern)
        val syncPastDays = dataStore.syncPastDays.first()
        val isAllLookback = syncPastDays == Int.MAX_VALUE
        val now = System.currentTimeMillis()
        val pastWindowMs = if (isAllLookback) Long.MAX_VALUE else syncPastDays.toLong() * 24 * 60 * 60 * 1000
        val startMs = if (isAllLookback) 0L else now - pastWindowMs
        val endMs = FUTURE_END_MS  // Unlimited future - fetch all future events

        // Step 1: Fetch etags only (lightweight — calendar-query or PROPFIND Depth:1)
        val etagResult = if (forceFullSync || calendar.syncToken != null) {
            // Force refresh or server known to support sync-token — calendar-query with time filter
            clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
        } else {
            // syncToken is null and not force sync — probe if server supports sync-token
            val tokenProbe = clientToUse.getSyncToken(calendar.caldavUrl)
            val serverSupportsSyncToken = tokenProbe.isSuccess() && tokenProbe.getOrNull() != null
            if (serverSupportsSyncToken) {
                // First sync on a capable server (iCloud, Nextcloud) — calendar-query works
                clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
            } else {
                // Server genuinely doesn't support sync-token (Purelymail) — use PROPFIND
                val propfindResult = clientToUse.fetchAllEtags(calendar.caldavUrl)
                if (propfindResult.isError()) {
                    val error = propfindResult as CalDavResult.Error
                    Log.w(TAG, "PROPFIND Depth:1 failed (${error.code}: ${error.message}), falling back to calendar-query")
                    sessionBuilder?.addWarning("PROPFIND Depth:1 failed (${error.code}), using calendar-query fallback")
                    clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
                } else {
                    propfindResult
                }
            }
        }
        if (etagResult.isError()) {
            val error = etagResult as CalDavResult.Error
            return PullResult.Error(error.code, error.message, error.isRetryable)
        }
        val serverEtags = (etagResult as CalDavResult.Success).data
        sessionBuilder?.setHrefsReported(serverEtags.size)

        // Delete local events not on server (skip when forceFullSync to prevent data loss).
        // Force full sync may get a truncated server response (time-range REPORT limitations,
        // RRULE expansion bugs, URL mismatches). Ghost events from server-side deletions
        // are a less harmful failure mode than losing events that exist on the server.
        // The next incremental sync will handle reconciliation properly.
        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        // Membership is compared on a CANONICAL form of the URL so a resource the
        // server echoes with different (but equivalent) percent-encoding — e.g.
        // Radicale re-encoding a literal '@' in the filename as '%40' — is not
        // mistaken for a server-side deletion and destroyed locally (issue #333).
        val serverUrls = serverEtags.mapTo(HashSet(serverEtags.size)) { (href, _) ->
            val url = quirks.buildEventUrl(href, calendar.caldavUrl)
            CaldavUrlNormalizer.canonicalize(url) ?: url
        }
        fun Event.isStaleOnServer(): Boolean =
            caldavUrl != null &&
            (CaldavUrlNormalizer.canonicalize(caldavUrl) ?: caldavUrl) !in serverUrls &&
            !hasPendingChanges() &&
            id !in recentlyPushedEventIds
        if (forceFullSync) {
            // Count how many events *would have been* deleted (skipped to avoid data loss).
            val localEvents = eventsDao.getByCalendarIdInRange(calendar.id, startMs, endMs)
            val wouldDelete = localEvents.count { it.isStaleOnServer() }
            if (wouldDelete > 0) {
                Log.d(TAG, "forceFullSync: skipping deletion of $wouldDelete local events not in server response")
            }
        } else {
            // Normal path: delete stale local events
            val localEvents = eventsDao.getByCalendarIdInRange(calendar.id, startMs, endMs)
            val toDelete = localEvents.filter { it.isStaleOnServer() }
            for (event in toDelete) {
                Log.d(TAG, "Deleting stale event: ${event.caldavUrl}")
                deletedChanges.add(SyncChange(
                    type = ChangeType.DELETED,
                    eventId = null,
                    eventTitle = event.title,
                    eventStartTs = event.startTs,
                    isAllDay = event.isAllDay,
                    isRecurring = !event.rrule.isNullOrBlank(),
                    calendarName = calendar.displayName,
                    calendarColor = calendar.color
                ))
                eventsDao.deleteById(event.id)
                deleted++
            }
        }

        // Step 2: Compare etags to skip unchanged events (bandwidth optimization).
        // Keyed on the canonical URL form so an '@'-in-filename event the server
        // re-encodes as '%40' still matches its local etag and isn't re-downloaded.
        val localEtagEntries = eventsDao.getEtagMapForCalendar(calendar.id, startMs, endMs)
        val localEtagMap = localEtagEntries.associate {
            (CaldavUrlNormalizer.canonicalize(it.caldavUrl) ?: it.caldavUrl) to it.etag
        }

        val hrefsToFetch = mutableListOf<String>()
        var skippedCount = 0

        for ((href, serverEtag) in serverEtags) {
            val eventUrl = quirks.buildEventUrl(href, calendar.caldavUrl)
            val localEtag = localEtagMap[CaldavUrlNormalizer.canonicalize(eventUrl) ?: eventUrl]
            if (localEtag == null || localEtag != serverEtag) {
                hrefsToFetch.add(href)
            } else {
                skippedCount++
            }
        }

        if (skippedCount > 0) {
            Log.d(TAG, "Etag comparison: skipped $skippedCount unchanged, downloading ${hrefsToFetch.size}")
        }

        if (hrefsToFetch.isEmpty()) {
            // No events on server — only deletions
            sessionBuilder?.setEventsFetched(0)
            val syncTokenResult = clientToUse.getSyncToken(calendar.caldavUrl)
            return PullResult.Success(
                eventsAdded = 0,
                eventsUpdated = 0,
                eventsDeleted = deleted,
                newSyncToken = syncTokenResult.getOrNull(),
                newCtag = null,
                changes = deletedChanges
            )
        }

        val fetchResult = fetchEventsBatched(clientToUse, calendar.caldavUrl, hrefsToFetch, sessionBuilder)
        val serverEvents = fetchResult.events
        sessionBuilder?.setEventsFetched(serverEvents.size)

        // Process server events
        // Skip default reminders for:
        // - Initial sync (syncToken == null): first time syncing, don't spam defaults
        // - Force sync (forceFullSync == true): user is refreshing, not seeing truly new events
        val skipDefaultReminders = (calendar.syncToken == null) || forceFullSync
        val processResult = processEvents(calendar, serverEvents, sessionBuilder, recentlyPushedEventIds, skipDefaultReminders)

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
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long> = emptySet()
    ): PullResult? {
        Log.d(TAG, "Attempting etag-based fallback sync for calendar: ${calendar.displayName}")

        // Read configurable lookback window
        val syncPastDays = dataStore.syncPastDays.first()
        val isAllLookback = syncPastDays == Int.MAX_VALUE
        val now = System.currentTimeMillis()
        val pastWindowMs = if (isAllLookback) Long.MAX_VALUE else syncPastDays.toLong() * 24 * 60 * 60 * 1000
        val startMs = if (isAllLookback) 0L else now - pastWindowMs
        val endMs = FUTURE_END_MS

        // Step 1: Load local etags
        // When lookback is "All", use unfiltered query (matches server which also has no time filter).
        // Otherwise, use time-filtered query matching the server's window to prevent
        // asymmetric deletion of events outside the lookback (issue #87, bug 2).
        val localEtags = if (isAllLookback) {
            eventsDao.getEtagsByCalendarId(calendar.id)
        } else {
            eventsDao.getEtagsByCalendarIdInRange(calendar.id, startMs, endMs)
        }
        if (localEtags.isEmpty()) {
            Log.d(TAG, "No local events with etags - falling through to pullFull")
            return null  // No local data to compare, fall through to full sync
        }

        // Build local lookup map (caldavUrl -> etag)
        val localEtagMap = localEtags.associate { it.caldavUrl to it.etag }
        Log.d(TAG, "Local etags loaded: ${localEtagMap.size} events (lookback=${if (isAllLookback) "All" else "${syncPastDays}d"})")

        // Step 2: Fetch server etags (lightweight - no iCal data)
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
        //
        // Membership is compared on a CANONICAL form of the URL so a resource
        // the server echoes with different (but equivalent) percent-encoding —
        // e.g. Radicale re-encoding a literal '@' in the filename as '%40' —
        // is recognized as the same event rather than being misclassified as
        // both deleted and new. We only canonicalize for comparison; changed/new
        // keep the ORIGINAL server URL (so multiget fetches the exact server
        // path) and deleted keeps the ORIGINAL local URL (for the row lookup).
        val canonicalLocalEtags = HashMap<String, String?>(localEtagMap.size)
        for ((url, etag) in localEtagMap) {
            canonicalLocalEtags[CaldavUrlNormalizer.canonicalize(url) ?: url] = etag
        }

        val changedUrls = mutableListOf<String>()
        val newUrls = mutableListOf<String>()

        for ((serverUrl, serverEtag) in serverEtagMap) {
            val canonicalServerUrl = CaldavUrlNormalizer.canonicalize(serverUrl) ?: serverUrl
            if (!canonicalLocalEtags.containsKey(canonicalServerUrl)) {
                // New event on server
                newUrls.add(serverUrl)
            } else if (canonicalLocalEtags[canonicalServerUrl] != serverEtag) {
                // Etag differs - event changed
                changedUrls.add(serverUrl)
            }
            // else: etag matches, no change needed
        }

        // Find deleted events (on local but not server), comparing canonically.
        val canonicalServerKeys = serverEtagMap.keys
            .mapTo(HashSet(serverEtagMap.size)) { CaldavUrlNormalizer.canonicalize(it) ?: it }
        val deletedUrls = localEtagMap.keys.filter {
            (CaldavUrlNormalizer.canonicalize(it) ?: it) !in canonicalServerKeys
        }

        Log.d(TAG, "Etag comparison: changed=${changedUrls.size}, new=${newUrls.size}, deleted=${deletedUrls.size}")

        // Step 4: Handle deletions (respecting pending local changes)
        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        val resolveLocalEvent = caldavUrlResolver(calendar.id)
        for (url in deletedUrls) {
            val event = resolveLocalEvent(url)
            if (event != null) {
                // LOCAL-FIRST: Don't delete events with pending local changes
                // RFC 4791: Don't delete recently pushed events — server may not
                // have indexed them yet (no immediate visibility guarantee after PUT)
                if (event.hasPendingChanges() || event.id in recentlyPushedEventIds) {
                    Log.d(TAG, "Skipping deletion of $url - " +
                        if (event.hasPendingChanges()) "has pending local changes (${event.syncStatus})"
                        else "recently pushed in this sync cycle")
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
                    calendarColor = calendar.color
                ))
                eventsDao.deleteById(event.id)
                deleted++
            } else {
                Log.d(TAG, "Deletion url matched no local event: $url")
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

        // Fetch events in batched concurrent multiget (v22.5.11)
        val fetchResult = fetchEventsBatched(clientToUse, calendar.caldavUrl, hrefsToFetch, sessionBuilder)
        val serverEvents = fetchResult.events
        sessionBuilder?.setEventsFetched(serverEvents.size)

        Log.d(TAG, "Fetched ${serverEvents.size} events via multiget (requested ${hrefsToFetch.size})")

        // Step 6: Process fetched events
        // Etag fallback is only called during incremental sync attempts, so isInitialSync = false
        val processResult = processEvents(calendar, serverEvents, sessionBuilder, recentlyPushedEventIds, isInitialSync = false)

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
     * Cancel armed alarms when a server pull brings a fresh self-decline
     * (server-side DECLINED) or removes the user from the attendee list
     * (uninvite). AlarmManager is process-wide and not transactional, so
     * this fires post-transaction; failures here must not abort the pull.
     */
    private suspend fun cancelRemindersIfSelfDeclined(
        eventId: Long,
        priorAttendees: List<Attendee>,
        newAttendees: List<Attendee>,
        account: Account?
    ) {
        if (account == null) return
        val newSelf = newAttendees.firstOrNull { account.matchesAttendee(it.address) }
        val priorHadSelf = priorAttendees.any { account.matchesAttendee(it.address) }
        val newSelfDeclined = newSelf?.partstat?.uppercase() == "DECLINED"
        val uninvited = priorHadSelf && newSelf == null
        if (!newSelfDeclined && !uninvited) return
        try {
            reminderScheduler.cancelRemindersForEvent(eventId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Decline cancel failed for event $eventId: ${e.message}")
        }
    }

    /**
     * Record a per-event processing failure and route it through the same
     * accounting a malformed-ICS parse failure gets. Shared by both processing
     * passes so a change to the isolation policy can't drift between them.
     *
     * Recovery depends on which pull path is running. The incremental path
     * (pullIncremental) reads getSkippedParseError() and holds the sync token
     * for a bounded retry (MAX_PARSE_RETRIES) before abandoning, so a transient
     * failure gets re-fetched. The full-sync and etag-fallback paths do NOT
     * consult this count: they advance the token/ctag unconditionally, so a
     * skipped event is re-fetched only when the server next changes it (or on a
     * forced full sync). Either way the failure is isolated to the one event and
     * the rest of the batch lands.
     */
    private fun recordProcessingFailure(
        sessionBuilder: SyncSessionBuilder?,
        caldavUrl: String,
        label: String,
        e: Exception,
    ) {
        Log.e(TAG, "Skipping $caldavUrl - failed to process $label: ${e.message}", e)
        sessionBuilder?.incrementSkipParseError()
        sessionBuilder?.addWarning("Failed to process $label at ${filenameOf(caldavUrl)}: ${e.message}")
    }

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
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long> = emptySet(),
        isInitialSync: Boolean = false
    ): ProcessEventsResult {
        var added = 0
        var updated = 0
        val changes = mutableListOf<SyncChange>()

        // Resolve the calendar's account once for the whole batch — feeds
        // the per-event invite-notification check without re-querying the
        // account row for every event in the pull.
        val accountForInvites = accountRepository.getAccountById(calendar.accountId)

        // First pass: collect all parsed events, separate masters from exceptions
        val masterEvents = mutableListOf<ParsedEventWithMeta>()
        val exceptionEvents = mutableListOf<ParsedEventWithMeta>()

        for (serverEvent in serverEvents) {
            val parseResult = try {
                icalParser.parseAllEvents(serverEvent.icalData)
            } catch (e: Exception) {
                Log.e(TAG, "Parse exception for ${serverEvent.url}: ${e.javaClass.simpleName}: ${e.message}")
                sessionBuilder?.incrementSkipParseError()
                sessionBuilder?.addWarning("Failed to parse ${filenameOf(serverEvent.url)}: ${e.javaClass.simpleName}: ${e.message}")
                continue
            }

            // Check for parse success
            val parsedEvents = when (parseResult) {
                is ParseResult.Success -> parseResult.value
                is ParseResult.Error -> {
                    Log.w(TAG, "Parse error for ${serverEvent.url}: ${parseResult.error.message}")
                    sessionBuilder?.incrementSkipParseError()
                    sessionBuilder?.addWarning("Parse error for ${filenameOf(serverEvent.url)}: ${parseResult.error.message}")
                    continue
                }
            }

            if (parsedEvents.isEmpty()) {
                if (isNonEventResource(serverEvent.icalData)) {
                    Log.d(TAG, "Skipping non-event resource at ${serverEvent.url} (VTODO/VJOURNAL/VFREEBUSY)")
                    continue
                }
                Log.w(TAG, "Failed to parse event at ${serverEvent.url}: no VEVENT components found")
                sessionBuilder?.incrementSkipParseError()
                sessionBuilder?.addWarning("No VEVENT found in ${filenameOf(serverEvent.url)}")
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
        // Tracks UIDs whose master was actually re-processed (occurrences
        // regenerated). Pass 3 must NOT skip bundled exceptions for these
        // UIDs even if the exception's own etag matches — the master regen
        // wiped and re-inserted occurrences at master-time, and the
        // exception link must be re-applied so the day-card doesn't show
        // both the master's RRULE-expanded instance AND the exception.
        val uidsWithRegeneratedMaster = mutableSetOf<String>()
        // The parsed master DTSTART, indexed by UID, so pass 3 can
        // normalize value-type-mismatched RECURRENCE-IDs against the
        // master before storing originalInstanceTime.
        val uidToMasterDtStart = masterEvents
            .associate { it.parsed.uid to it.parsed.dtStart }

        // Resolve the master DTSTART to normalize a value-type-mismatched
        // RECURRENCE-ID against. Prefer the master parsed in THIS batch;
        // otherwise reconstruct it from the master already in Room, so the
        // incremental path (exception .ics pulled without its unchanged
        // master) normalizes the same way the bundled path stored it, using
        // the shared Room-Event→dtStart reconstruction so it can't drift from
        // the wire serialization. A synthetic placeholder or a non-recurring
        // row is not a usable reference — return null so normalizeRecurrenceId
        // passes the RECURRENCE-ID through unchanged. RDATE-only masters are
        // recurring too (RFC 5545 §3.8.5.2), so the guard checks rdate as well
        // as rrule — Event.isRecurring covers only rrule.
        fun masterDtStartFor(uid: String, resolvedMaster: Event?): ICalDateTime? {
            uidToMasterDtStart[uid]?.let { return it }
            val m = resolvedMaster ?: return null
            val recurs = m.rrule != null || m.rdate != null
            if (!recurs) return null
            if (m.extraProperties?.get(PULL_SYNTHETIC_MASTER_EXTRA_KEY) == "true") return null
            return EventToICalEventMapper.dtStartOf(m)
        }

        // Derive an exception's instance-time key: the RECURRENCE-ID normalized
        // against the resolved master DTSTART. The lookup and the store both
        // route through this so they can never disagree on the stored/queried
        // value. (The orphan synthetic-master path below cannot use it — no
        // master DTSTART exists yet — and deliberately anchors on the raw
        // RECURRENCE-ID; masterDtStartFor's null-for-synthetic result keeps the
        // two consistent for that case.)
        fun resolveInstanceTime(parsed: ICalEvent, resolvedMaster: Event?): Long? =
            ICalEventMapper.normalizeRecurrenceId(
                recurrenceId = parsed.recurrenceId,
                masterDtStart = masterDtStartFor(parsed.uid, resolvedMaster),
            )?.timestamp

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

            // CDN PROTECTION: Skip events we just pushed in this sync cycle.
            // iCloud CDN may return stale data for recently-modified events.
            // Trust our local version since we just successfully pushed it.
            if (existingEvent != null && existingEvent.id in recentlyPushedEventIds) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - recently pushed in this sync cycle")
                sessionBuilder?.incrementSkipRecentlyPushed()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // Skip if etag unchanged (prevents overwrite with stale data after push)
            // After successful push, local event has the new etag from server.
            // If server returns same etag, data is identical - no need to upsert.
            // This handles iCloud eventual consistency where pull may return stale data.
            if (existingEvent != null && existingEvent.etag != null && existingEvent.etag == meta.etag) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - etag unchanged (${meta.etag})")
                sessionBuilder?.incrementSkipEtagUnchanged()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // Fault isolation spans the whole map->validate->upsert->occurrence
            // pipeline: the map step (toEntity) and the pre-transaction reads run
            // before the transaction, and a parseable-but-hostile event can make
            // any of them throw a shape no fixture anticipated. Isolating only the
            // transaction would still let a map-step throw abort the whole
            // calendar's pull and strand every other event in the batch. The
            // `continue`s inside are ordinary skip paths (invalid ts, race), not
            // failures.
            // Triple carries (saved event, prior attendees, new attendees) out of
            // the isolated block; `mapped.attendees` is needed post-transaction by
            // the decline-cancel hook but is scoped inside the try.
            val savedTriple: Triple<Event, List<Attendee>, List<Attendee>> = try {
                // Map ICalEvent to Event entity + Attendee rows using ICalEventMapper
                val mapped = ICalEventMapper.toEntity(
                    icalEvent = meta.parsed,
                    rawIcal = meta.rawIcal,
                    calendarId = calendar.id,
                    caldavUrl = meta.caldavUrl,
                    etag = meta.etag
                )
                var event = mapped.event

                // Reject events with invalid timestamps (RFC 5545 violation)
                if (!hasValidTimestamps(event)) {
                    Log.w(TAG, "Skipping ${meta.caldavUrl} - invalid timestamps: startTs=${event.startTs}, endTs=${event.endTs}")
                    sessionBuilder?.incrementSkipParseError()
                    sessionBuilder?.addWarning("Invalid timestamps in ${filenameOf(meta.caldavUrl)} (endTs < startTs)")
                    continue
                }

                // Preserve existing event ID and timestamps
                if (existingEvent != null) {
                    event = event.copy(
                        id = existingEvent.id,
                        createdAt = existingEvent.createdAt,
                        localModifiedAt = existingEvent.localModifiedAt,
                        // Preserve existing etag when server omits <getetag> from response
                        // (RFC 4791 says SHOULD include etag, but some servers/CDN may omit it)
                        etag = meta.etag ?: existingEvent.etag,
                        color = event.color ?: existingEvent.color
                    )
                }

                // RACE PREVENTION: Re-check sync status just before the transaction.
                // The outer hasPendingChanges() check (line 865) uses a stale in-memory
                // object. A user edit between that check and this transaction would set
                // syncStatus to PENDING_UPDATE. Without this re-check, the upsert would
                // silently overwrite the user's local edit with server data (data loss).
                if (existingEvent != null) {
                    val freshStatus = eventsDao.getSyncStatus(existingEvent.id)
                    if (freshStatus != null && freshStatus != SyncStatus.SYNCED) {
                        Log.d(TAG, "Race detected: ${meta.caldavUrl} gained pending changes ($freshStatus) between check and transaction")
                        sessionBuilder?.incrementSkipPendingLocal()
                        uidToMasterEvent[meta.parsed.uid] = existingEvent
                        continue
                    }
                }

                // TRANSACTION: Upsert event and generate occurrences atomically
                // Prevents orphaned events (no occurrences) if crash occurs mid-operation
                // Wrapped in withDbRetry for resilience against database lock errors
                withDbRetry {
                    database.runInTransaction {
                        val eventId = eventsDao.upsert(event)
                        val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                        seedCategories(saved)

                        // Pre-replace snapshot lets the post-transaction
                        // decline-cancel hook detect uninvites.
                        val priorAttendees = attendeesDao.getForEventOnce(saved.id)

                        // Persist server-authoritative attendee set inside the
                        // same transaction (replace-not-merge per A2).
                        attendeesDao.replaceForEvent(
                            saved.id,
                            mapped.attendees.map { it.copy(eventId = saved.id) }
                        )

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

                        Triple(saved, priorAttendees, mapped.attendees)
                    }
                }
            } catch (_: SQLiteConstraintException) {
                // Check if this is a duplicate UID (unique constraint on uid, calendar_id)
                val existing = eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)
                if (existing != null) {
                    Log.w(TAG, "Duplicate UID detected for ${meta.parsed.uid}, using existing event")
                    sessionBuilder?.addWarning("Duplicate event skipped at ${filenameOf(meta.caldavUrl)}")
                    uidToMasterEvent[meta.parsed.uid] = existing
                    continue
                }
                // Not a duplicate — already synced in a prior session. Skip to prevent sync abort loop (issue #55)
                Log.d(TAG, "Skipped already-synced master ${meta.parsed.uid} (${meta.caldavUrl})")
                sessionBuilder?.incrementSkipAlreadySynced()
                continue
            } catch (e: CancellationException) {
                throw e  // Never swallow coroutine cancellation
            } catch (e: Exception) {
                // Fault isolation: a single event whose map/upsert/occurrence
                // generation throws must not abort the whole calendar's pull and
                // strand every other event in the batch. Any transaction rolled
                // back this event's partial write, so skip it and continue.
                // Recovery (token hold vs. advance) is path-dependent — see
                // recordProcessingFailure.
                recordProcessingFailure(sessionBuilder, meta.caldavUrl, "event", e)
                continue
            }

            val savedEvent = savedTriple.first
            val priorAttendees = savedTriple.second
            val newAttendees = savedTriple.third

            uidToMasterEvent[meta.parsed.uid] = savedEvent
            uidsWithRegeneratedMaster.add(meta.parsed.uid)

            // Fire the per-invite system notification after the
            // transaction commits. The notifier filters for self-on-list +
            // PARTSTAT=NEEDS-ACTION + notified_at IS NULL, so the call is
            // cheap no-op for events that don't qualify. Skipped entirely
            // when the calendar's account couldn't be resolved (orphan
            // calendar — no user identity to match against attendees).
            if (accountForInvites != null) {
                try {
                    inviteNotifier.notifyNew(savedEvent, accountForInvites)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Invite notify failed for event ${savedEvent.id}: ${e.message}")
                }
            }

            // Run BEFORE the etag-only short-circuit below so PARTSTAT-only
            // deltas — which leave hasContentChanged returning false — still
            // cancel the alarm.
            cancelRemindersIfSelfDeclined(
                eventId = savedEvent.id,
                priorAttendees = priorAttendees,
                newAttendees = newAttendees,
                account = accountForInvites
            )

            // Skip notification for etag-only updates (sibling VEVENT in same resource changed)
            if (existingEvent != null && !hasContentChanged(existingEvent, savedEvent)) {
                Log.d(TAG, "Etag-only update for: ${savedEvent.title}")
                continue
            }

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
                calendarColor = calendar.color,
                isFromInitialSync = isInitialSync
            ))
        }

        // Third pass: link and upsert exception events (respecting pending local changes)
        for (meta in exceptionEvents) {
            // First lookup: in-memory map populated by pass 2 (covers the
            // common case where master and exception are bundled). Fallback
            // to a UID query in case the master was upserted in a prior sync
            // and isn't in this batch's map. Accept synthetic placeholders
            // (rrule=null but `original_event_id IS NULL`) so a real master
            // arriving in a later batch finds and mutates it in place.
            var masterEvent = uidToMasterEvent[meta.parsed.uid]
                ?: eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)

            if (masterEvent == null) {
                // Orphan exception: no master in this batch, no master in
                // Room, no synthetic from a prior pull. Synthesize a
                // placeholder so the exception's FK has a target. When the
                // real master eventually arrives (window expanded, scroll-
                // back fetch, server-side fix), pass 2's getMasterBy...
                // lookup finds this synthetic and the upsert path mutates
                // it in place — same row id, sentinel cleared, real RRULE
                // populated, exception FKs survive untouched.
                val recurrenceIdMs = meta.parsed.recurrenceId?.timestamp
                if (recurrenceIdMs == null) {
                    // No RECURRENCE-ID — can't synthesize without an anchor
                    // timestamp. Drop with the legacy warning.
                    Log.w(TAG, "Orphaned exception with no RECURRENCE-ID dropped: ${meta.caldavUrl}")
                    sessionBuilder?.incrementSkipOrphanedException()
                    sessionBuilder?.addWarning(
                        "Orphaned exception at ${filenameOf(meta.caldavUrl)} (no RECURRENCE-ID)"
                    )
                    continue
                }
                val synthetic = synthesizeMasterForOrphanException(
                    uid = meta.parsed.uid,
                    calendarId = calendar.id,
                    recurrenceIdMs = recurrenceIdMs,
                    placeholderTitle = meta.parsed.summary?.ifBlank { null } ?: "Untitled",
                )
                // The synthetic insert runs before the map/upsert try below, so
                // isolate it here: a DB-layer throw on this one placeholder must
                // skip this exception, not abort the whole calendar's pull.
                // withDbRetry mirrors the master (upsert) and exception (upsert)
                // write paths so a transient "database is locked" retries rather
                // than being caught and silently dropping the orphan — full-sync
                // and etag paths advance the token unconditionally, so a dropped
                // orphan would not be re-fetched until the server next changes it.
                val syntheticId = try {
                    withDbRetry { eventsDao.insert(synthetic) }
                } catch (e: CancellationException) {
                    throw e  // Never swallow coroutine cancellation
                } catch (e: Exception) {
                    recordProcessingFailure(sessionBuilder, meta.caldavUrl, "exception", e)
                    continue
                }
                masterEvent = synthetic.copy(id = syntheticId)
                uidToMasterEvent[meta.parsed.uid] = masterEvent
                Log.i(
                    TAG,
                    "Orphan exception promoted via synthetic master: " +
                        "uid=${meta.parsed.uid}, id=$syntheticId, recurrenceId=$recurrenceIdMs"
                )
                sessionBuilder?.addWarning(
                    "Orphan exception at ${filenameOf(meta.caldavUrl)} promoted via synthetic master"
                )
            }

            // Get original instance time from RECURRENCE-ID. Normalize a
            // value-type-mismatched RECURRENCE-ID (a DATE-form value against a
            // timed master — RFC 5545 §3.8.4.4 says the types MUST match, but
            // peer clients emit the mismatch and servers preserve it) against
            // the master's DTSTART, exactly as the store path does when writing
            // originalInstanceTime. Passing the resolved masterEvent lets this
            // normalize on the incremental path too (exception pulled without
            // its master in-batch); keying off the raw value would miss the
            // stored row and re-add the exception as a new event.
            val originalInstanceTime = resolveInstanceTime(meta.parsed, masterEvent)

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

            // Skip if etag unchanged (prevents overwrite with stale data after push).
            // BUT: when the master in this same .ics resource was just
            // regenerated, re-link first. Master regen wipes occurrences
            // and re-expands from RRULE; without re-running linkException
            // here, the master-time instance (e.g. Jun 01 19:00) and the
            // exception's modified-time instance (e.g. Jun 01 10:00) both
            // render on the day card.
            val etagsKnownUnchanged = existingException != null &&
                existingException.etag != null &&
                existingException.etag == meta.etag
            val masterRegenerated = meta.parsed.uid in uidsWithRegeneratedMaster
            if (etagsKnownUnchanged && !masterRegenerated) {
                // Self-heal: a previous pull may have crashed mid-link,
                // leaving the master's RRULE-expanded occurrence at this
                // exception's instance time WITHOUT exception_event_id set
                // while the exception row itself was committed. Both etags
                // match this round so neither pass would otherwise touch
                // the row. Detect the unlinked state and re-run linkException.
                val recurrenceIdTime = existingException?.originalInstanceTime
                if (recurrenceIdTime != null) {
                    val occ = database.occurrencesDao()
                        .getByEventIdAndStartTs(masterEvent.id, recurrenceIdTime)
                    if (occ != null && occ.exceptionEventId == null) {
                        occurrenceGenerator.linkException(
                            masterEvent.id,
                            recurrenceIdTime,
                            existingException
                        )
                        Log.i(TAG, "Self-healed unlinked exception ${meta.caldavUrl} on etag-skip path")
                    }
                }
                Log.d(TAG, "Skipping exception ${meta.caldavUrl} - etag unchanged (${meta.etag})")
                sessionBuilder?.incrementSkipEtagUnchanged()
                continue
            }
            if (etagsKnownUnchanged && masterRegenerated) {
                // The exception's own row data is unchanged, but its master
                // was regenerated and needs the link re-applied. Re-run
                // linkException — Step 3 matches the freshly-inserted
                // master row by `ABS(start_ts - recurrenceIdTime) < 60s`
                // and updates it to the exception's modified time with
                // exception_event_id set.
                val recurrenceIdTime = existingException!!.originalInstanceTime
                if (recurrenceIdTime != null) {
                    occurrenceGenerator.linkException(
                        masterEvent.id,
                        recurrenceIdTime,
                        existingException
                    )
                    Log.d(TAG, "Re-linked exception ${meta.caldavUrl} after master regen")
                }
                continue
            }

            // Fault isolation covers the map->validate->upsert->link pipeline for
            // this override, mirroring the master pass: the map step (toEntity)
            // runs before the transaction and a parseable-but-hostile override
            // event can make it throw a shape no fixture anticipated. Isolating
            // only the transaction would still let a map-step throw abort the
            // whole calendar's pull. The `continue`s inside are ordinary skip
            // paths (invalid ts, race), not failures.
            //
            // Scope note: the RECURRENCE-ID resolution and etag-unchanged re-link
            // above (resolveInstanceTime / the self-heal linkException) sit before
            // this try and are not isolated — they operate on already-resolved
            // values, not the untrusted map step, so a throw there is treated as a
            // real error, same as before this change.
            //
            // NOTE: a synthetic master may have been inserted above (outside any
            // transaction) to give this exception's FK a target. It is left in
            // place on failure by design — it is an invisible CANCELLED, rrule-null
            // placeholder that generates no occurrence of its own, is cached in
            // uidToMasterEvent for sibling exceptions in this batch, and is mutated
            // in place when the real master arrives. Deleting it here would orphan
            // a sibling exception that already linked to it.
            val savedExceptionTriple: Triple<Event, List<Attendee>, List<Attendee>> = try {
                // Map ICalEvent to Event entity + Attendee rows using ICalEventMapper.
                // Pass the master's DTSTART so the mapper normalizes a
                // value-type-mismatched RECURRENCE-ID before writing
                // originalInstanceTime — see ICalEventMapper.normalizeRecurrenceId.
                // Uses the same resolver as the lookup above so the stored key and
                // the queried key are always derived identically.
                val mappedException = ICalEventMapper.toEntity(
                    icalEvent = meta.parsed,
                    rawIcal = meta.rawIcal,
                    calendarId = calendar.id,
                    caldavUrl = meta.caldavUrl,
                    etag = meta.etag,
                    masterDtStart = masterDtStartFor(meta.parsed.uid, masterEvent),
                )
                var event = mappedException.event

                // Reject exception events with invalid timestamps (RFC 5545 violation)
                if (!hasValidTimestamps(event)) {
                    Log.w(TAG, "Skipping exception ${meta.caldavUrl} - invalid timestamps: startTs=${event.startTs}, endTs=${event.endTs}")
                    sessionBuilder?.incrementSkipParseError()
                    sessionBuilder?.addWarning("Invalid timestamps in exception at ${filenameOf(meta.caldavUrl)} (endTs < startTs)")
                    continue
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
                        localModifiedAt = existingException.localModifiedAt,
                        // Preserve existing etag when server omits <getetag> from response
                        etag = meta.etag ?: existingException.etag,
                        color = event.color ?: existingException.color
                    )
                }

                // RACE PREVENTION: Re-check sync status for exception events (same as master events above)
                if (existingException != null) {
                    val freshStatus = eventsDao.getSyncStatus(existingException.id)
                    if (freshStatus != null && freshStatus != SyncStatus.SYNCED) {
                        Log.d(TAG, "Race detected: exception ${meta.caldavUrl} gained pending changes ($freshStatus)")
                        sessionBuilder?.incrementSkipPendingLocal()
                        continue
                    }
                }

                // TRANSACTION: Upsert exception, link to master's occurrence atomically
                // Uses Model B (linked occurrence) consistently to prevent duplicates.
                // linkException handles: delete Model A occurrence (if exists), update/create Model B.
                // Wrapped in withDbRetry for resilience against database lock errors
                withDbRetry {
                    database.runInTransaction {
                        val eventId = eventsDao.upsert(event)
                        val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                        seedCategories(saved)

                        val priorAttendees = attendeesDao.getForEventOnce(saved.id)

                        // Persist attendee rows for the exception event in
                        // the same transaction. Exception events have their
                        // own per-instance attendee list (RFC 5545 §3.8.4.1).
                        attendeesDao.replaceForEvent(
                            saved.id,
                            mappedException.attendees.map { it.copy(eventId = saved.id) }
                        )

                        // Link exception to master's occurrence (Model B)
                        // This normalizes any existing Model A to Model B, preventing duplicates
                        val originalTime = event.originalInstanceTime
                        if (originalTime != null) {
                            occurrenceGenerator.linkException(masterEvent.id, originalTime, saved)
                        } else {
                            // Fallback: no original time means standalone occurrence
                            occurrenceGenerator.regenerateOccurrences(saved)
                        }

                        Triple(saved, priorAttendees, mappedException.attendees)
                    }
                }
            } catch (_: SQLiteConstraintException) {
                // Already synced in a prior session. Skip to prevent sync abort loop (issue #55)
                Log.d(TAG, "Skipped already-synced exception ${meta.parsed.uid} " +
                    "(RECURRENCE-ID: ${meta.parsed.recurrenceId?.timestamp})")
                sessionBuilder?.incrementSkipAlreadySynced()
                sessionBuilder?.addWarning("Already-synced exception skipped at ${filenameOf(meta.caldavUrl)}")
                continue
            } catch (e: CancellationException) {
                throw e  // Never swallow coroutine cancellation
            } catch (e: Exception) {
                // Fault isolation for the exception pass, mirroring the master
                // pass above: one override event that throws during map/upsert/
                // link must not abort the pull. Any transaction rolled back its
                // partial write; skip and continue. Recovery is path-dependent —
                // see recordProcessingFailure.
                recordProcessingFailure(sessionBuilder, meta.caldavUrl, "exception", e)
                continue
            }

            val savedExceptionEvent = savedExceptionTriple.first
            val priorExceptionAttendees = savedExceptionTriple.second
            val newExceptionAttendees = savedExceptionTriple.third

            cancelRemindersIfSelfDeclined(
                eventId = savedExceptionEvent.id,
                priorAttendees = priorExceptionAttendees,
                newAttendees = newExceptionAttendees,
                account = accountForInvites
            )

            // Skip notification for etag-only updates (sibling VEVENT in same resource changed)
            if (existingException != null && !hasContentChanged(existingException, savedExceptionEvent)) {
                Log.d(TAG, "Etag-only update for exception: ${savedExceptionEvent.title}")
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
                calendarColor = calendar.color,
                isFromInitialSync = isInitialSync
            ))
        }

        // Fourth pass: prune local exceptions the server genuinely excluded.
        // When a modified occurrence is deleted on another client, the server
        // adds an EXDATE to the master (RFC 5545 §3.8.5.1) and drops the
        // override VEVENT. Without pruning, the stale exception lingers on the
        // calendar (the row survives because pass 3 only visits exceptions the
        // server still sends).
        //
        // The prune fires ONLY when BOTH hold for a local exception:
        //   (a) its instance is absent from the batch's exception set, AND
        //   (b) the master's EXDATE now covers that instance.
        // (b) is the load-bearing guard. Absent-alone is NOT sufficient — that
        // would wrongly delete live overrides in two reachable cases:
        //   • a server that violates RFC 4791 §4.1 by splitting same-UID
        //     components across resources (only the master's resource is
        //     re-fetched, so its overrides are absent from this batch), and
        //   • an override VEVENT the parser dropped (parseAllEvents silently
        //     skips unparseable components), which is present on the server but
        //     absent from `present`.
        // Requiring the EXDATE match means we only remove what the master
        // itself declares excluded — matching EXDATE-precedence semantics and
        // erring toward keeping data when the signals are ambiguous.
        //
        // Guarded to UIDs whose master arrived in this batch: §4.1 also permits
        // a resource carrying ONLY overrides without the master, which is not
        // authoritative for pruning.
        val presentInstancesByUid = exceptionEvents
            .groupBy { it.parsed.uid }
            .mapValues { (_, metas) ->
                metas.mapNotNull { resolveInstanceTime(it.parsed, uidToMasterEvent[it.parsed.uid]) }.toSet()
            }
        for (uid in uidsWithRegeneratedMaster) {
            val master = uidToMasterEvent[uid] ?: continue
            // CDN protection: if we just pushed this master's resource this
            // cycle, a stale read may omit a bundled exception we added. Don't
            // prune its exceptions now — pass 2 applies the same guard to the
            // master, and bundled exception ids aren't tracked individually.
            if (master.id in recentlyPushedEventIds) continue
            val present = presentInstancesByUid[uid].orEmpty()
            // Master EXDATE set (stored as epoch-ms CSV, normalized against the
            // master's own DTSTART — the same basis as the exception instance
            // keys, so directly comparable).
            val exdateSet = master.exdate
                ?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?.toSet()
                .orEmpty()
            val localExceptions = eventsDao.getExceptionsForMaster(master.id)
            for (ex in localExceptions) {
                val instance = ex.originalInstanceTime ?: continue
                if (instance in present) continue
                // Only prune what the master's EXDATE actually excludes (see above).
                if (instance !in exdateSet) continue
                // Local-first: never drop an exception with unsynced local edits;
                // it will be pushed (or resurrected) on the next cycle.
                if (ex.hasPendingChanges()) continue
                if (ex.id in recentlyPushedEventIds) continue
                Log.d(TAG, "Pruning exception ${ex.id} (uid=$uid, instance=$instance) — EXDATE-excluded on master")
                // Atomic: cancel the linked occurrence AND delete the row
                // together, so a failure between them can't leave a cancelled
                // occurrence with a surviving, still-linked exception row that
                // no later etag-matching pass would revisit.
                database.runInTransaction {
                    database.occurrencesDao().markCancelledByException(ex.id)
                    eventsDao.deleteById(ex.id)
                }
                // Emit the notification only after the delete committed.
                changes.add(SyncChange(
                    type = ChangeType.DELETED,
                    eventId = null,
                    eventTitle = ex.title,
                    eventStartTs = ex.startTs,
                    isAllDay = ex.isAllDay,
                    isRecurring = true,
                    calendarName = calendar.displayName,
                    calendarColor = calendar.color,
                    isFromInitialSync = isInitialSync
                ))
                sessionBuilder?.addDeleted(1)
            }
        }

        return ProcessEventsResult(added, updated, changes)
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

    private data class FetchResult(
        val events: List<CalDavEvent>
    )

    /**
     * Fetch events in batched concurrent multiget requests (v22.5.11).
     *
     * Splits hrefs into batches of [MULTIGET_BATCH_SIZE] and fetches them concurrently.
     * OkHttp Dispatcher throttles to 5 concurrent requests per host.
     *
     * Per-batch resilience: if a batch multiget fails, falls back to individual
     * single-href fetches for that batch via [fetchSingleHrefConcurrent]. Other
     * batches continue normally. This also handles Zoho's empty-response quirk
     * (HTTP 200 empty body for multi-href calendar-multiget).
     */
    private suspend fun fetchEventsBatched(
        client: CalDavClient,
        calendarUrl: String,
        hrefs: List<String>,
        sessionBuilder: SyncSessionBuilder? = null
    ): FetchResult {
        if (hrefs.isEmpty()) return FetchResult(emptyList())

        val batches = hrefs.chunked(MULTIGET_BATCH_SIZE)
        Log.d(TAG, "fetchEventsBatched: ${hrefs.size} hrefs in ${batches.size} batches of $MULTIGET_BATCH_SIZE")

        val allEvents = coroutineScope {
            batches.mapIndexed { index, batch ->
                async {
                    Log.d(TAG, "fetchEventsBatched: batch ${index + 1}/${batches.size}, ${batch.size} hrefs")
                    val result = client.fetchEventsByHref(calendarUrl, batch)
                    if (result.isError()) {
                        val error = result as CalDavResult.Error
                        Log.w(TAG, "fetchEventsBatched: batch ${index + 1} multiget failed " +
                            "(code=${error.code}): ${error.message}, falling back to individual fetches")
                        sessionBuilder?.addWarning("Batch fetch failed (${error.code}): ${error.message}, retrying individually")
                        val recovered = fetchSingleHrefConcurrent(client, calendarUrl, batch, sessionBuilder)
                        Log.d(TAG, "fetchEventsBatched: recovered ${recovered.size} of ${batch.size} events via individual fallback")
                        return@async recovered
                    }
                    val events = (result as CalDavResult.Success).data
                    if (events.isEmpty() && batch.size > 1) {
                        Log.w(TAG, "fetchEventsBatched: batch ${index + 1} returned 0 events " +
                            "for ${batch.size} hrefs, falling back to single-href fetches")
                        fetchSingleHrefConcurrent(client, calendarUrl, batch, sessionBuilder)
                    } else {
                        events
                    }
                }
            }.awaitAll().flatten()
        }

        Log.d(TAG, "fetchEventsBatched: fetched ${allEvents.size} events from ${hrefs.size} hrefs")
        return FetchResult(allEvents)
    }

    /**
     * Fetch events one href at a time, concurrently. Used as fallback when a server
     * returns empty for multi-href calendar-multiget (Zoho quirk).
     * OkHttp Dispatcher throttles to 5 concurrent requests per host.
     * Skips individual failures — partial data is better than none.
     */
    private suspend fun fetchSingleHrefConcurrent(
        client: CalDavClient,
        calendarUrl: String,
        hrefs: List<String>,
        sessionBuilder: SyncSessionBuilder? = null
    ): List<CalDavEvent> = coroutineScope {
        hrefs.map { href ->
            async {
                val result = client.fetchEventsByHref(calendarUrl, listOf(href))
                if (result.isSuccess()) {
                    result.getOrNull()!!
                } else {
                    val error = result as CalDavResult.Error
                    Log.w(TAG, "fetchSingleHrefConcurrent: failed for $href " +
                        "(code=${error.code}): ${error.message}")
                    sessionBuilder?.addWarning("Fetch failed for ${filenameOf(href)} (${error.code}): ${error.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
}
