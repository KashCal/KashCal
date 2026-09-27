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
 * Sentinel placed in [Event.extraProperties] on a synthetic master row.
 *
 * A synthetic master is a placeholder created when an exception VEVENT arrives without its
 * master in the same pull and none is in Room: the master is outside the lookback window, came
 * in a different multiget batch, or was deleted on the server while the exception lingers. The
 * exception's `originalEventId` FK points at it so the exception survives ingest. When the real
 * master arrives, the master pass finds the synthetic by UID and upserts over its row id (real
 * RRULE, sentinel cleared), so exception FKs stay valid.
 *
 * The value matches `SYNTHETIC_MASTER_EXTRA_KEY` in `IcsSubscriptionRepository`, so the
 * FTS-search and title-suggest exclusions in [EventsDao] and the occurrence skip in
 * [OccurrenceGenerator.generateOccurrences] cover both paths. The two declarations are kept
 * separate on purpose: ICS sync and CalDAV pull are separate code paths.
 */
internal const val PULL_SYNTHETIC_MASTER_EXTRA_KEY = "X-KASHCAL-SYNTHETIC-MASTER"

/** Reports whether this row is a synthetic master the pull made for an orphan exception. */
internal val Event.isPullSyntheticMaster: Boolean
    get() = extraProperties?.get(PULL_SYNTHETIC_MASTER_EXTRA_KEY) == "true"

/**
 * Builds a placeholder master for an orphan exception: `rrule = null`, `status = "CANCELLED"`
 * and [PULL_SYNTHETIC_MASTER_EXTRA_KEY] set. Inserting it gives the exception a row id for
 * `originalEventId`. When the real master arrives, the master pass finds this row with
 * `getMasterByUidAndCalendar` (uid, calendar, `original_event_id IS NULL`) and upserts over it.
 *
 * [OccurrenceGenerator.generateOccurrences] returns 0 for a synthetic; without that skip the
 * rrule-less row would emit a phantom occurrence at [recurrenceIdMs].
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

/**
 * Pulls one CalDAV calendar's events from the server into Room.
 *
 * Each pull probes the ctag (an unchanged ctag ends the pull), then takes the delta through a
 * sync-collection REPORT when a sync-token is stored, or a full listing of etags otherwise. Only
 * hrefs whose etag differs are fetched by calendar-multiget. Fetched resources are parsed and
 * mapped to events, exceptions are linked to their masters, occurrences are regenerated, and
 * the calendar's sync-token and ctag are stored.
 *
 * [pull] takes optional provider quirks and falls back to the injected [CalDavQuirks], which
 * is iCloud's.
 */
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
    private val icalParser = ICalParser()

    private val categoryDao by lazy { database.categoryDao() }

    /**
     * Seeds the tag table with each category on a pulled event, so a tag first seen on the
     * server appears in suggestions and the management screen. Never overwrites a user's
     * chosen color; case-insensitive (the NOCASE primary key collapses cased duplicates). Runs
     * inside the event's upsert transaction.
     *
     * Recency is the event's `localModifiedAt` (kept from the existing row) or its start time,
     * not wall-clock now: a bulk pull of old events must not rank their tags as just used, and
     * the raise-only update never rolls back a newer local use.
     *
     * This can bring back a tag the user deleted locally if server events still carry it. That
     * is accepted: a tag that labels live events shouldn't silently vanish.
     */
    private suspend fun seedCategories(event: Event) {
        val recency = event.localModifiedAt ?: event.startTs
        event.categories?.forEach { name ->
            if (name.isNotBlank()) categoryDao.seedFromPull(name, recency)
        }
    }

    companion object {
        private const val TAG = "PullStrategy"

        /** Returns the .ics filename of [caldavUrl], for privacy-safe warning messages. */
        private fun filenameOf(caldavUrl: String): String =
            caldavUrl.substringAfterLast('/').ifEmpty { caldavUrl }

        /**
         * Rejects endTs < startTs (an RFC 5545 violation, always corrupt server data). Historical
         * and epoch-zero dates are legitimate and pass.
         */
        internal fun hasValidTimestamps(event: Event): Boolean =
            event.endTs >= event.startTs

        /**
         * Reports whether [incoming] differs from [existing] beyond sync metadata. CalDAV has one
         * etag per .ics resource, so a change to any VEVENT in a series changes the etag of all
         * of them; an unchanged VEVENT is still upserted (new etag) but raises no UI notification.
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

        // Past edge of the occurrence expansion for a pulled recurring master (1 year). The
        // sync lookback comes from the syncPastDays setting, not from this.
        private const val PAST_WINDOW_MS = 365L * 24 * 60 * 60 * 1000
        // Upper bound for the Room range queries (stale-event deletion, etag maps). SQL needs a
        // concrete bound; no real event reaches 2100, so for the DB it means "forever". It is
        // also passed to fetchEtagsInRange, but the CalDAV client drops the wire upper bound past
        // 2038 (32-bit time_t servers like SOGo silently truncate results otherwise), so server
        // reach is already unbounded. Raising this fetches nothing more from the server.
        private const val FUTURE_END_MS = 4102444800000L  // Jan 1, 2100 UTC

        // Future edge of local occurrence expansion; EventWriter and ConflictResolver use it too.
        const val OCCURRENCE_EXPANSION_MS = 2 * 365L * 24 * 60 * 60 * 1000  // 2 years

        // Syncs to hold the token for on parse errors before advancing (v16.7.0).
        private const val MAX_PARSE_RETRIES = 3

        // Max hrefs per calendar-multiget request (v22.5.11).
        private const val MULTIGET_BATCH_SIZE = 20

        private const val MAX_DB_RETRIES = 3
        private const val INITIAL_DB_RETRY_DELAY_MS = 100L

        /**
         * Reports whether [icalData] holds VTODO, VJOURNAL or VFREEBUSY but no VEVENT, which
         * separates a valid non-event resource from a parse failure when parseAllEvents()
         * returns empty.
         */
        private fun isNonEventResource(icalData: String): Boolean {
            return !icalData.contains("BEGIN:VEVENT") &&
                (icalData.contains("BEGIN:VTODO") ||
                 icalData.contains("BEGIN:VJOURNAL") ||
                 icalData.contains("BEGIN:VFREEBUSY"))
        }
    }

    /**
     * Retries [block] on "database is locked" only, with exponential backoff.
     *
     * Room sets SQLite's busy_timeout; this covers contention that outlasts it. Every other
     * SQLiteException propagates at once: SQLiteConstraintException has its own handling in the
     * callers, and other SQLite errors are likely bugs.
     */
    private suspend inline fun <T> withDbRetry(block: () -> T): T {
        var lastException: SQLiteException? = null
        repeat(MAX_DB_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: SQLiteException) {
                if (e.message?.contains("database is locked", ignoreCase = true) == true) {
                    lastException = e
                    Log.w(TAG, "DB locked (attempt ${attempt + 1}/$MAX_DB_RETRIES), retrying...")
                    if (attempt < MAX_DB_RETRIES - 1) {
                        // The shift is capped so the backoff can't overflow.
                        val backoff = INITIAL_DB_RETRY_DELAY_MS * (1L shl attempt.coerceIn(0, 4))
                        delay(backoff)
                    }
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    /**
     * Refreshes the calendar's color, display name and read-only flag from [probe]. A non-null,
     * valid server value wins; null or invalid keeps the local value. Skips the write when
     * nothing changed, so getVisibleCalendarsFlow collectors don't re-emit.
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
     * Pulls [calendar]'s server changes into Room and stores its new sync-token and ctag on
     * success.
     *
     * @param forceFullSync ignores the ctag and sync-token and lists every event in the lookback
     *   window; stale local events are then kept, not deleted (see [pullFull]).
     * @param quirks provider quirks; null uses the injected default (iCloud).
     * @param client carries the account's credentials; the caller creates one per account.
     * @param recentlyPushedEventIds events pushed earlier in this sync cycle, which the pull
     *   never deletes. It doesn't overwrite a series or single event among them unless it is also
     *   in [refetchEventIds]; a changed occurrence is overwritten like any other.
     * @param refetchEventIds pushed events whose rows lack a change the push wrote with them
     *   ([PushResult.Success.refetchEventIds]). They are also in [recentlyPushedEventIds], so
     *   still never deleted, but they are refreshed like any changed event, exceptions their EXDATE
     *   covers pruned, since a later sync-collection listing won't name them again.
     * @return [PullResult.NoChanges] when the ctag is unchanged. A 401/403 ctag probe, a failed
     *   listing, a network failure or any other exception returns [PullResult.Error];
     *   cancellation is rethrown.
     */
    suspend fun pull(
        calendar: Calendar,
        forceFullSync: Boolean = false,
        quirks: CalDavQuirks? = null,
        client: CalDavClient,
        sessionBuilder: SyncSessionBuilder? = null,
        recentlyPushedEventIds: Set<Long> = emptySet(),
        refetchEventIds: Set<Long> = emptySet()
    ): PullResult {
        val effectiveQuirks = quirks ?: defaultQuirks
        val effectiveClient = client

        return try {
            // Probe the ctag and metadata (displayName, color, isReadOnly).
            val ctagResult = effectiveClient.getCtag(calendar.caldavUrl)
            val probe: CalendarMetadataProbe? = if (ctagResult.isSuccess()) {
                (ctagResult as CalDavResult.Success).data
            } else {
                val error = ctagResult as CalDavResult.Error
                // Auth and permission errors are systemic: abort.
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

            val result = if (!forceFullSync && calendar.syncToken != null) {
                pullIncremental(calendar, effectiveQuirks, effectiveClient, sessionBuilder, recentlyPushedEventIds, refetchEventIds)
            } else {
                pullFull(calendar, effectiveQuirks, effectiveClient, sessionBuilder, recentlyPushedEventIds, refetchEventIds, forceFullSync = forceFullSync)
            }

            // A null token or ctag in the result keeps the stored token and takes the probed ctag.
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
     * Builds a resolver from a server-reported resource URL to the local event whose stored
     * caldav_url matches it, tolerating percent-encoding differences. Some servers (Radicale)
     * echo an href with pchar-legal reserved characters percent-encoded (a literal '@' comes back
     * as %40) while KashCal stored the literal character. An exact, index-backed match is tried
     * first; on a miss the URL is compared canonically against the calendar's stored URLs.
     *
     * Use one resolver per deletion loop: the canonical candidate map loads once, on the first
     * exact-match miss, and is reused. On servers that re-encode every href every lookup misses
     * the exact match, and a reload per lookup would cost deletions x calendar size. The cache
     * must not outlive the loop.
     */
    private fun caldavUrlResolver(calendarId: Long): suspend (String) -> Event? {
        var canonicalMap: Map<String, Event>? = null
        return resolve@{ url ->
            // caldav_url is not unique: a recurring master and its exceptions share one server
            // resource, and a row moved to another calendar keeps the source URL. The exact
            // query is global and returns any one of those rows, so use its hit only when it is
            // a master in this calendar. Anything else falls through to the canonical map,
            // which is scoped to the calendar and prefers masters.
            eventsDao.getByCaldavUrl(url)
                ?.takeIf { it.calendarId == calendarId && it.originalEventId == null }
                ?.let { return@resolve it }
            val target = CaldavUrlNormalizer.canonicalize(url) ?: return@resolve null
            val map = canonicalMap ?: eventsDao.getEventsWithCaldavUrl(calendarId)
                // A master and its exceptions canonicalize to the same key. toMap() is
                // last-wins, so masters sort last: deleting a master cascades to its
                // exceptions, while resolving to an exception would drop one occurrence and
                // leave the master pointing at a resource the server no longer has.
                .sortedBy { it.originalEventId == null }
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
     * Pulls the delta since the stored sync-token with a sync-collection REPORT (RFC 6578),
     * fetching only changed hrefs. A rejected token (403/410) falls back to
     * [pullWithEtagComparison], then to [pullFull].
     */
    private suspend fun pullIncremental(
        calendar: Calendar,
        quirks: CalDavQuirks,
        clientToUse: CalDavClient,
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long>,
        refetchEventIds: Set<Long>
    ): PullResult {
        Log.d(TAG, "Incremental sync with token: ${calendar.syncToken?.take(8)}...")

        val reportResult = clientToUse.syncCollection(calendar.caldavUrl, calendar.syncToken)
        if (reportResult.isError()) {
            val error = reportResult as CalDavResult.Error
            // 403/410: the sync-token expired.
            if (error.code == 403 || error.code == 410) {
                Log.w(TAG, "Sync token expired (${error.code}), trying etag-based fallback")
                val etagResult = pullWithEtagComparison(calendar, quirks, clientToUse, sessionBuilder, recentlyPushedEventIds, refetchEventIds)
                if (etagResult != null) {
                    Log.d(TAG, "Etag-based fallback succeeded")
                    return etagResult
                }
                Log.w(TAG, "Etag fallback returned null, falling back to full sync")
                return pullFull(calendar, quirks, clientToUse, sessionBuilder, recentlyPushedEventIds, refetchEventIds)
            }
            return PullResult.Error(error.code, error.message, error.isRetryable)
        }

        val syncReport = (reportResult as CalDavResult.Success).data
        Log.d(TAG, "syncCollection: ${syncReport.changed.size} changed, ${syncReport.deleted.size} deleted")

        // RFC 6578 §3.6: a 507 means the server truncated the results. They are still valid,
        // so the new token is saved and the next sync continues from it.
        if (syncReport.truncated) {
            Log.w(TAG, "Server returned truncated results (507). Will continue on next sync.")
            sessionBuilder?.setTruncated(true)
        }

        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        val resolveLocalEvent = caldavUrlResolver(calendar.id)
        // A server may report the same deleted href more than once (iCloud does). The
        // resolver's cached map would resolve the just-deleted row again and count the
        // deletion and its notification twice.
        for (href in syncReport.deleted.distinct()) {
            val url = quirks.buildEventUrl(href, calendar.caldavUrl)
            val event = resolveLocalEvent(url)
            if (event != null) {
                // Local-first: an event with pending local changes may have been edited or
                // recreated offline, so it stays. A recently pushed event stays too: RFC 4791
                // gives no visibility guarantee right after a PUT, so the server may not have
                // indexed it yet.
                if (event.hasPendingChanges() || event.id in recentlyPushedEventIds) {
                    Log.d(TAG, "Skipping deletion of $url - " +
                        if (event.hasPendingChanges()) "has pending local changes (${event.syncStatus})"
                        else "recently pushed in this sync cycle")
                    continue
                }
                deletedChanges.add(SyncChange(
                    type = ChangeType.DELETED,
                    eventId = null, // the row is deleted below
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

        // iCloud returns duplicate hrefs. Without distinct(), hrefsReported exceeds the
        // received count even when every event arrived, and Sync History shows a false
        // "Missing: N".
        val rawHrefs = syncReport.changed
            .filter { it.status == SyncItemStatus.OK }
            .map { it.href }
        val changedHrefs = rawHrefs.distinct()

        val duplicateCount = rawHrefs.size - changedHrefs.size
        if (duplicateCount > 0) {
            Log.w(TAG, "sync-collection returned $duplicateCount duplicate hrefs (raw=${rawHrefs.size}, deduped=${changedHrefs.size})")
        }

        if (changedHrefs.isEmpty()) {
            // Duplicate masters left by earlier syncs are removed even when nothing changed.
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

        sessionBuilder?.setHrefsReported(changedHrefs.size)

        val fetchResult = fetchEventsBatched(clientToUse, calendar.caldavUrl, changedHrefs, sessionBuilder)
        val serverEvents = fetchResult.events
        sessionBuilder?.setEventsFetched(serverEvents.size)

        // iCloud is eventually consistent: sync-collection may report an href before the
        // calendar-data server has its data, so a multiget can come back short.
        val receivedHrefs = serverEvents.map { it.href }.toSet()
        val missingHrefs = changedHrefs.filter { it !in receivedHrefs }
        val hasMissingEvents = missingHrefs.isNotEmpty()

        if (hasMissingEvents) {
            Log.w(TAG, "fetchEventsByHref: requested=${changedHrefs.size}, received=${serverEvents.size}, missing: $missingHrefs")
        }

        // A stored sync-token means this is never the initial sync.
        val processResult = processEvents(calendar, serverEvents, sessionBuilder, recentlyPushedEventIds, refetchEventIds, isInitialSync = false)

        // Duplicate masters can come from iCloud hostname changes (p180 → p181) or concurrent
        // syncs racing.
        val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
        if (dedupedCount > 0) {
            Log.w(TAG, "Cleaned up $dedupedCount duplicate master events during incremental sync")
        }

        val allChanges = deletedChanges + processResult.changes

        // Hold or advance the sync-token. Missing events hold it first; parse errors hold it
        // for up to MAX_PARSE_RETRIES syncs, then it advances and abandons them.
        val parseErrorCount = sessionBuilder?.getSkippedParseError() ?: 0
        val currentRetryCount = dataStore.getParseFailureRetryCount(calendar.id)

        val effectiveSyncToken = when {
            hasMissingEvents -> {
                Log.w(TAG, "NOT advancing sync token due to ${missingHrefs.size} missing events")
                sessionBuilder?.setTokenAdvanced(false)
                calendar.syncToken  // the next sync re-fetches them
            }

            // Parse-error retry (v16.7.0).
            parseErrorCount > 0 && currentRetryCount < MAX_PARSE_RETRIES -> {
                val newCount = dataStore.incrementParseFailureRetry(calendar.id)
                Log.w(TAG, "NOT advancing sync token due to $parseErrorCount parse errors (retry $newCount/$MAX_PARSE_RETRIES)")
                sessionBuilder?.setTokenAdvanced(false)
                calendar.syncToken
            }

            parseErrorCount > 0 && currentRetryCount >= MAX_PARSE_RETRIES -> {
                Log.w(TAG, "Advancing sync token despite $parseErrorCount parse errors (max retries reached)")
                dataStore.resetParseFailureRetry(calendar.id)
                sessionBuilder?.setAbandonedParseErrors(parseErrorCount)
                sessionBuilder?.setTokenAdvanced(true)
                syncReport.syncToken  // abandons the unparseable events
            }

            else -> {
                if (currentRetryCount > 0) {
                    dataStore.resetParseFailureRetry(calendar.id)
                    Log.d(TAG, "Reset parse failure retry count for calendar ${calendar.id}")
                }
                sessionBuilder?.setTokenAdvanced(true)
                syncReport.syncToken
            }
        }

        return PullResult.Success(
            eventsAdded = processResult.added,
            eventsUpdated = processResult.updated,
            eventsDeleted = deleted,
            newSyncToken = effectiveSyncToken,
            // A held token keeps the old ctag too, so the next ctag probe doesn't skip the retry.
            newCtag = if (effectiveSyncToken == calendar.syncToken) calendar.ctag else null,
            changes = allChanges
        )
    }

    /**
     * Lists every server etag in the lookback window, deletes local events the server no longer
     * has, and fetches the new and changed ones. Runs when no sync-token is stored, on a forced
     * full sync, or when both the sync-token and [pullWithEtagComparison] failed.
     */
    private suspend fun pullFull(
        calendar: Calendar,
        quirks: CalDavQuirks,
        clientToUse: CalDavClient,
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long>,
        refetchEventIds: Set<Long>,
        forceFullSync: Boolean = false
    ): PullResult {
        val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
        if (dedupedCount > 0) {
            Log.d(TAG, "Cleaned up $dedupedCount duplicate master events")
        }

        // Same lookback window as pullWithEtagComparison.
        val syncPastDays = dataStore.syncPastDays.first()
        val isAllLookback = syncPastDays == Int.MAX_VALUE
        val now = System.currentTimeMillis()
        val pastWindowMs = if (isAllLookback) Long.MAX_VALUE else syncPastDays.toLong() * 24 * 60 * 60 * 1000
        val startMs = if (isAllLookback) 0L else now - pastWindowMs
        val endMs = FUTURE_END_MS  // no future limit; see FUTURE_END_MS

        // Etags only, no calendar data: a calendar-query or a PROPFIND Depth:1.
        val etagResult = if (forceFullSync || calendar.syncToken != null) {
            // Forced, or the server is known to support sync-tokens: time-filtered
            // calendar-query.
            clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
        } else {
            // No token and not forced: probe whether the server supports sync-tokens.
            val tokenProbe = clientToUse.getSyncToken(calendar.caldavUrl)
            val serverSupportsSyncToken = tokenProbe.isSuccess() && tokenProbe.getOrNull() != null
            if (serverSupportsSyncToken) {
                // First sync on a capable server (iCloud, Nextcloud): calendar-query works.
                clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
            } else {
                // No sync-token support (Purelymail): PROPFIND, falling back to calendar-query.
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

        // Delete local events the server no longer lists, except on a forced full sync: its
        // response may be truncated (time-range REPORT limits, RRULE expansion bugs, URL
        // mismatches), and a ghost of a server-side deletion does less harm than losing an
        // event the server still has. The delta never reports those ghosts; a later unforced
        // listing (this path or pullWithEtagComparison) deletes them.
        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        // Compared on a canonical URL so a resource the server echoes with equivalent
        // percent-encoding (Radicale re-encodes a literal '@' in the filename as '%40') isn't
        // taken for a server-side deletion and destroyed locally (#333).
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
            // Only counts what would have been deleted, for the log.
            val localEvents = eventsDao.getByCalendarIdInRange(calendar.id, startMs, endMs)
            val wouldDelete = localEvents.count { it.isStaleOnServer() }
            if (wouldDelete > 0) {
                Log.d(TAG, "forceFullSync: skipping deletion of $wouldDelete local events not in server response")
            }
        } else {
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

        // Unchanged etags aren't fetched. Keyed on the canonical URL so an '@'-in-filename
        // event the server re-encodes as '%40' still matches its local etag.
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
            // Every listed etag matched, or the server listed none.
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

        // Marks the changes as initial-sync, so CalDavSyncWorker adds no default reminders: on a
        // first sync they would land on every event, and a forced sync re-lists events the user
        // already has.
        val skipDefaultReminders = (calendar.syncToken == null) || forceFullSync
        val processResult = processEvents(calendar, serverEvents, sessionBuilder, recentlyPushedEventIds, refetchEventIds, skipDefaultReminders)

        val allChanges = deletedChanges + processResult.changes

        // Null when the server has no sync-token or the request failed.
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
     * Resyncs after the sync-token is rejected (403/410) by comparing etags: lists only server
     * etags, diffs them with Room, and multigets only new and changed events. That saves ~96% of
     * a full pull's bandwidth (33KB vs 834KB for 231 events).
     *
     * @return null when Room has no etags for the window or the etag listing failed; the
     *   caller then runs [pullFull].
     */
    private suspend fun pullWithEtagComparison(
        calendar: Calendar,
        quirks: CalDavQuirks,
        clientToUse: CalDavClient,
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long>,
        refetchEventIds: Set<Long>
    ): PullResult? {
        Log.d(TAG, "Attempting etag-based fallback sync for calendar: ${calendar.displayName}")

        val syncPastDays = dataStore.syncPastDays.first()
        val isAllLookback = syncPastDays == Int.MAX_VALUE
        val now = System.currentTimeMillis()
        val pastWindowMs = if (isAllLookback) Long.MAX_VALUE else syncPastDays.toLong() * 24 * 60 * 60 * 1000
        val startMs = if (isAllLookback) 0L else now - pastWindowMs
        val endMs = FUTURE_END_MS

        // With an "All" lookback the local query is unfiltered, like the server's. Otherwise it
        // uses the server's window, so events outside the lookback aren't read as deleted
        // on the server (#87).
        val localEtags = if (isAllLookback) {
            eventsDao.getEtagsByCalendarId(calendar.id)
        } else {
            eventsDao.getEtagsByCalendarIdInRange(calendar.id, startMs, endMs)
        }
        if (localEtags.isEmpty()) {
            Log.d(TAG, "No local events with etags - falling through to pullFull")
            return null  // nothing local to compare
        }

        val localEtagMap = localEtags.associate { it.caldavUrl to it.etag }
        Log.d(TAG, "Local etags loaded: ${localEtagMap.size} events (lookback=${if (isAllLookback) "All" else "${syncPastDays}d"})")

        val etagResult = clientToUse.fetchEtagsInRange(calendar.caldavUrl, startMs, endMs)
        if (etagResult.isError()) {
            val error = etagResult as CalDavResult.Error
            Log.w(TAG, "fetchEtagsInRange failed: ${error.code} - ${error.message}, falling through to pullFull")
            return null
        }

        val serverEtags = (etagResult as CalDavResult.Success).data
        Log.d(TAG, "Server etags fetched: ${serverEtags.size} events")

        // Full URL -> etag.
        val serverEtagMap = serverEtags.associate { (href, etag) ->
            quirks.buildEventUrl(href, calendar.caldavUrl) to etag
        }

        // Changed: the etag differs (including null -> non-null). New: on the server only.
        // Deleted: local only, handled below.
        //
        // Compared on a canonical URL so a resource the server echoes with equivalent
        // percent-encoding (Radicale re-encodes a literal '@' in the filename as '%40') isn't
        // classed as both deleted and new. Only the comparison is canonical: changed and new
        // keep the server URL, so multiget fetches the exact server path, and deleted keeps the
        // local URL for the row lookup.
        val canonicalLocalEtags = HashMap<String, String?>(localEtagMap.size)
        for ((url, etag) in localEtagMap) {
            canonicalLocalEtags[CaldavUrlNormalizer.canonicalize(url) ?: url] = etag
        }

        val changedUrls = mutableListOf<String>()
        val newUrls = mutableListOf<String>()

        for ((serverUrl, serverEtag) in serverEtagMap) {
            val canonicalServerUrl = CaldavUrlNormalizer.canonicalize(serverUrl) ?: serverUrl
            if (!canonicalLocalEtags.containsKey(canonicalServerUrl)) {
                newUrls.add(serverUrl)
            } else if (canonicalLocalEtags[canonicalServerUrl] != serverEtag) {
                changedUrls.add(serverUrl)
            }
        }

        val canonicalServerKeys = serverEtagMap.keys
            .mapTo(HashSet(serverEtagMap.size)) { CaldavUrlNormalizer.canonicalize(it) ?: it }
        val deletedUrls = localEtagMap.keys.filter {
            (CaldavUrlNormalizer.canonicalize(it) ?: it) !in canonicalServerKeys
        }

        Log.d(TAG, "Etag comparison: changed=${changedUrls.size}, new=${newUrls.size}, deleted=${deletedUrls.size}")

        var deleted = 0
        val deletedChanges = mutableListOf<SyncChange>()
        val resolveLocalEvent = caldavUrlResolver(calendar.id)
        for (url in deletedUrls) {
            val event = resolveLocalEvent(url)
            if (event != null) {
                // Same keep rule as the delta's deletions in pullIncremental.
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

        // Multiget takes hrefs, so full URLs are cut back to their path.
        val hrefsToFetch = (changedUrls + newUrls).map { url ->
            if (url.contains("://")) {
                "/" + url.substringAfter("://").substringAfter("/")
            } else {
                url
            }
        }

        if (hrefsToFetch.isEmpty()) {
            Log.d(TAG, "No events to fetch - only deletions")
            sessionBuilder?.addDeleted(deleted)

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

        sessionBuilder?.setHrefsReported(hrefsToFetch.size)

        val fetchResult = fetchEventsBatched(clientToUse, calendar.caldavUrl, hrefsToFetch, sessionBuilder)
        val serverEvents = fetchResult.events
        sessionBuilder?.setEventsFetched(serverEvents.size)

        Log.d(TAG, "Fetched ${serverEvents.size} events via multiget (requested ${hrefsToFetch.size})")

        // Only pullIncremental calls this, so it is never the initial sync.
        val processResult = processEvents(calendar, serverEvents, sessionBuilder, recentlyPushedEventIds, refetchEventIds, isInitialSync = false)

        val allChanges = deletedChanges + processResult.changes

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

    /** Counts and per-event changes for the UI from [processEvents]. */
    private data class ProcessEventsResult(
        val added: Int,
        val updated: Int,
        val changes: List<SyncChange>
    )

    /**
     * Cancels the event's armed alarms when the pulled attendees show the user as DECLINED or
     * no longer list the user (uninvited). AlarmManager isn't transactional, so this runs after
     * the transaction commits, and a failure here must not abort the pull.
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
     * Records a per-event processing failure with the same accounting as a malformed-ICS parse
     * failure. The master pass, the synthetic-master insert and the exception pass all call it,
     * so the isolation policy can't drift between them.
     *
     * Recovery depends on the pull path. [pullIncremental] reads getSkippedParseError() and
     * holds the sync-token for up to MAX_PARSE_RETRIES syncs before abandoning, so a transient
     * failure is re-fetched. [pullFull] and [pullWithEtagComparison] don't read the count and
     * advance the token and ctag regardless, so a skipped event is re-fetched only when the
     * server next changes it or on a forced full sync. Either way only the one event is lost;
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
     * Parses, maps and saves fetched resources in four passes: parse, masters, exceptions, and
     * a prune of exceptions the master's EXDATE now excludes. Returns counts and per-event
     * [SyncChange]s for the UI.
     *
     * Local-first: events with pending local changes (PENDING_CREATE, PENDING_UPDATE,
     * PENDING_DELETE) are skipped. They are pushed to the server first, and any conflicts are
     * resolved via ETag/sequence.
     * See https://developer.android.com/topic/architecture/data-layer/offline-first
     */
    private suspend fun processEvents(
        calendar: Calendar,
        serverEvents: List<CalDavEvent>,
        sessionBuilder: SyncSessionBuilder?,
        recentlyPushedEventIds: Set<Long>,
        refetchEventIds: Set<Long>,
        isInitialSync: Boolean = false
    ): ProcessEventsResult {
        var added = 0
        var updated = 0
        val changes = mutableListOf<SyncChange>()

        // Read once per batch for the per-event invite and decline checks.
        val accountForInvites = accountRepository.getAccountById(calendar.accountId)

        // First pass: parse every resource and split masters from exceptions.
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

        // Second pass: upsert masters, skipping those with pending local changes.
        val uidToMasterEvent = mutableMapOf<String, Event>()
        // UIDs whose master was saved and its occurrences regenerated. Pass 3 must re-link
        // these UIDs' exceptions even when an exception's own etag matches: the regeneration
        // re-inserted occurrences at master time, and without the link the day card shows both
        // the master's expanded occurrence and the exception.
        val uidsWithRegeneratedMaster = mutableSetOf<String>()
        // Parsed master DTSTART by UID, so pass 3 can normalize a value-type-mismatched
        // RECURRENCE-ID against it before storing originalInstanceTime.
        val uidToMasterDtStart = masterEvents
            .associate { it.parsed.uid to it.parsed.dtStart }

        // Returns the master DTSTART to normalize a value-type-mismatched RECURRENCE-ID
        // against. The master parsed in this batch wins; otherwise it is rebuilt from the
        // master in Room with EventToICalEventMapper.dtStartOf, the same reconstruction the
        // wire serialization uses, so an exception pulled without its unchanged master
        // normalizes the way the bundled path stored it. A synthetic or non-recurring row
        // returns null, and normalizeRecurrenceId passes the RECURRENCE-ID through unchanged.
        // RDATE-only masters recur too (RFC 5545 §3.8.5.2), so rdate counts; Event.isRecurring
        // checks only rrule.
        fun masterDtStartFor(uid: String, resolvedMaster: Event?): ICalDateTime? {
            uidToMasterDtStart[uid]?.let { return it }
            val m = resolvedMaster ?: return null
            val recurs = m.rrule != null || m.rdate != null
            if (!recurs) return null
            if (m.isPullSyntheticMaster) return null
            return EventToICalEventMapper.dtStartOf(m)
        }

        // Returns an exception's instance-time key: its RECURRENCE-ID normalized against the
        // resolved master DTSTART. The lookup and the store both use it, so the stored and
        // queried values can't disagree. The orphan synthetic-master path below has no master
        // DTSTART and anchors on the raw RECURRENCE-ID; masterDtStartFor returns null for a
        // synthetic, which keeps the two consistent.
        fun resolveInstanceTime(parsed: ICalEvent, resolvedMaster: Event?): Long? =
            ICalEventMapper.normalizeRecurrenceId(
                recurrenceId = parsed.recurrenceId,
                masterDtStart = masterDtStartFor(parsed.uid, resolvedMaster),
            )?.timestamp

        for (meta in masterEvents) {
            // UID first: it is stable across server hostname changes (p180 vs p181). The
            // caldavUrl fallback is a global query, and a row moved to another calendar keeps
            // its old URL, so only a hit in this calendar counts; adopting another calendar's
            // row would overwrite live data and drag it back here.
            val existingEvent = eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)
                ?: eventsDao.getByCaldavUrl(meta.caldavUrl)?.takeIf { it.calendarId == calendar.id }

            // Local-first: PushStrategy sends pending local changes first; the server wins only
            // after they are synced, or the local edit would be lost.
            if (existingEvent != null && existingEvent.hasPendingChanges()) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - has pending local changes (${existingEvent.syncStatus})")
                sessionBuilder?.incrementSkipPendingLocal()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // iCloud's CDN may return stale data for an event pushed moments ago, so an event
            // pushed in this sync cycle keeps its local version, unless the push merged a server
            // change its rows lack.
            if (existingEvent != null && existingEvent.id in recentlyPushedEventIds &&
                existingEvent.id !in refetchEventIds
            ) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - recently pushed in this sync cycle")
                sessionBuilder?.incrementSkipRecentlyPushed()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // An unchanged etag means identical data, so there is nothing to upsert. After a
            // first-attempt write the local row holds the server's new etag, so this also stops
            // an eventually consistent iCloud read from overwriting it with stale data. A merged
            // upload or a retried reply leaves the old etag, so its event passes this check.
            if (existingEvent != null && existingEvent.etag != null && existingEvent.etag == meta.etag) {
                Log.d(TAG, "Skipping ${meta.caldavUrl} - etag unchanged (${meta.etag})")
                sessionBuilder?.incrementSkipEtagUnchanged()
                uidToMasterEvent[meta.parsed.uid] = existingEvent
                continue
            }

            // The try spans map, validate, upsert and occurrences. The map step (toEntity) and
            // the reads run before the transaction, and a parseable but hostile event can make
            // any of them throw; isolating only the transaction would let that abort the whole
            // calendar's pull and strand every other event in the batch. The `continue`s inside
            // are ordinary skips (invalid timestamps, race), not failures.
            // The Triple (saved event, prior attendees, new attendees) carries the attendees
            // out of the try for the post-transaction decline check.
            val savedTriple: Triple<Event, List<Attendee>, List<Attendee>> = try {
                val mapped = ICalEventMapper.toEntity(
                    icalEvent = meta.parsed,
                    rawIcal = meta.rawIcal,
                    calendarId = calendar.id,
                    caldavUrl = meta.caldavUrl,
                    etag = meta.etag
                )
                var event = mapped.event

                if (!hasValidTimestamps(event)) {
                    Log.w(TAG, "Skipping ${meta.caldavUrl} - invalid timestamps: startTs=${event.startTs}, endTs=${event.endTs}")
                    sessionBuilder?.incrementSkipParseError()
                    sessionBuilder?.addWarning("Invalid timestamps in ${filenameOf(meta.caldavUrl)} (endTs < startTs)")
                    continue
                }

                // Keeps the row id, timestamps and a local color when the server sends none.
                if (existingEvent != null) {
                    event = event.copy(
                        id = existingEvent.id,
                        createdAt = existingEvent.createdAt,
                        localModifiedAt = existingEvent.localModifiedAt,
                        // RFC 4791 says the response SHOULD include <getetag>, but some servers
                        // and CDNs omit it; the stored etag is kept then.
                        etag = meta.etag ?: existingEvent.etag,
                        color = event.color ?: existingEvent.color
                    )
                }

                // The hasPendingChanges() check above read an in-memory row. A user edit since
                // then sets PENDING_UPDATE, and without this re-read the upsert would silently
                // overwrite that edit with server data.
                if (existingEvent != null) {
                    val freshStatus = eventsDao.getSyncStatus(existingEvent.id)
                    if (freshStatus != null && freshStatus != SyncStatus.SYNCED) {
                        Log.d(TAG, "Race detected: ${meta.caldavUrl} gained pending changes ($freshStatus) between check and transaction")
                        sessionBuilder?.incrementSkipPendingLocal()
                        uidToMasterEvent[meta.parsed.uid] = existingEvent
                        continue
                    }
                }

                // One transaction, so a crash can't leave an event without occurrences;
                // withDbRetry retries a locked database.
                withDbRetry {
                    database.runInTransaction {
                        val eventId = eventsDao.upsert(event)
                        val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                        seedCategories(saved)

                        // Read before the replace so the decline check can detect an uninvite.
                        val priorAttendees = attendeesDao.getForEventOnce(saved.id)

                        // Server wins: the pulled attendee set replaces the stored one, no merge.
                        attendeesDao.replaceForEvent(
                            saved.id,
                            mapped.attendees.map { it.copy(eventId = saved.id) }
                        )

                        if (saved.rrule != null) {
                            val now = System.currentTimeMillis()
                            occurrenceGenerator.generateOccurrences(
                                event = saved,
                                rangeStartMs = now - PAST_WINDOW_MS,
                                rangeEndMs = now + OCCURRENCE_EXPANSION_MS
                            )
                        } else {
                            occurrenceGenerator.regenerateOccurrences(saved)
                        }

                        Triple(saved, priorAttendees, mapped.attendees)
                    }
                }
            } catch (_: SQLiteConstraintException) {
                // A master with this UID may already exist in the calendar; adopt it.
                val existing = eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)
                if (existing != null) {
                    Log.w(TAG, "Duplicate UID detected for ${meta.parsed.uid}, using existing event")
                    sessionBuilder?.addWarning("Duplicate event skipped at ${filenameOf(meta.caldavUrl)}")
                    uidToMasterEvent[meta.parsed.uid] = existing
                    continue
                }
                // Otherwise it was synced in an earlier session. Skipping it keeps the sync from
                // aborting on every run (#55).
                Log.d(TAG, "Skipped already-synced master ${meta.parsed.uid} (${meta.caldavUrl})")
                sessionBuilder?.incrementSkipAlreadySynced()
                continue
            } catch (e: CancellationException) {
                throw e  // never swallow coroutine cancellation
            } catch (e: Exception) {
                // The transaction, if it started, rolled back this event's write. Recovery is
                // described on recordProcessingFailure.
                recordProcessingFailure(sessionBuilder, meta.caldavUrl, "event", e)
                continue
            }

            val savedEvent = savedTriple.first
            val priorAttendees = savedTriple.second
            val newAttendees = savedTriple.third

            uidToMasterEvent[meta.parsed.uid] = savedEvent
            uidsWithRegeneratedMaster.add(meta.parsed.uid)

            // Runs after the commit. The notifier fires only for the user's own attendee row
            // with PARTSTAT=NEEDS-ACTION and notified_at IS NULL, so it is a no-op for other
            // events. With no account (orphan calendar) there is no identity to match, so it
            // is skipped.
            if (accountForInvites != null) {
                try {
                    inviteNotifier.notifyNew(savedEvent, accountForInvites)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Invite notify failed for event ${savedEvent.id}: ${e.message}")
                }
            }

            // Runs before the etag-only skip below, so a PARTSTAT-only change, which leaves
            // hasContentChanged false, still cancels the alarm.
            cancelRemindersIfSelfDeclined(
                eventId = savedEvent.id,
                priorAttendees = priorAttendees,
                newAttendees = newAttendees,
                account = accountForInvites
            )

            // Etag-only update: another VEVENT in the same resource changed. No notification.
            if (existingEvent != null && !hasContentChanged(existingEvent, savedEvent)) {
                Log.d(TAG, "Etag-only update for: ${savedEvent.title}")
                continue
            }

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

        // Third pass: upsert and link exceptions, skipping those with pending local changes.
        for (meta in exceptionEvents) {
            // Pass 2's map covers a master bundled with its exceptions; the UID query covers a
            // master saved in an earlier sync. It also returns a synthetic placeholder
            // (rrule null, `original_event_id IS NULL`), so exceptions keep linking to one row
            // until the real master replaces it.
            var masterEvent = uidToMasterEvent[meta.parsed.uid]
                ?: eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)

            if (masterEvent == null) {
                // Orphan exception: no master in this batch or in Room, and no synthetic from an
                // earlier pull. The synthetic gives its FK a target until the real master
                // arrives (see PULL_SYNTHETIC_MASTER_EXTRA_KEY).
                val recurrenceIdMs = meta.parsed.recurrenceId?.timestamp
                if (recurrenceIdMs == null) {
                    // Without a RECURRENCE-ID there is no anchor timestamp: drop it.
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
                // This insert runs before the try below, so it has its own: a DB throw on the
                // placeholder must skip this exception, not abort the calendar's pull. withDbRetry,
                // as on the master and exception upserts, retries a transient "database is
                // locked" before the catch drops the orphan; the full and etag paths advance the
                // token regardless, so a dropped orphan isn't re-fetched until the server next
                // changes it.
                val syntheticId = try {
                    withDbRetry { eventsDao.insert(synthetic) }
                } catch (e: CancellationException) {
                    throw e  // never swallow coroutine cancellation
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

            // RFC 5545 §3.8.4.4 says RECURRENCE-ID must match the master DTSTART's value type,
            // but other clients send a DATE against a timed master and servers keep it. The
            // value is normalized against the master's DTSTART the way the store writes
            // originalInstanceTime. Passing the resolved master covers an exception pulled
            // without its master; keying off the raw value would miss the stored row and
            // re-add the exception as a new event.
            val originalInstanceTime = resolveInstanceTime(meta.parsed, masterEvent)

            // UID + instance time (RFC 5545) are server-stable, so the lookup survives a change
            // of master row id.
            val existingException = originalInstanceTime?.let {
                eventsDao.getExceptionByUidAndInstanceTime(
                    uid = meta.parsed.uid,
                    calendarId = calendar.id,
                    originalInstanceTime = it
                )
            }

            if (existingException != null && existingException.hasPendingChanges()) {
                Log.d(TAG, "Skipping exception ${meta.caldavUrl} - has pending local changes (${existingException.syncStatus})")
                sessionBuilder?.incrementSkipPendingLocal()
                continue
            }

            // An unchanged etag skips the upsert, as for masters. When the master in the same
            // resource was regenerated, its occurrences were re-expanded from the RRULE, so the
            // link is re-applied; otherwise the master-time occurrence (Jun 01 19:00) and the
            // exception's moved one (Jun 01 10:00) both render on the day card.
            val etagsKnownUnchanged = existingException != null &&
                existingException.etag != null &&
                existingException.etag == meta.etag
            val masterRegenerated = meta.parsed.uid in uidsWithRegeneratedMaster
            if (etagsKnownUnchanged && !masterRegenerated) {
                // Self-heal: an earlier pull may have committed the exception row and crashed
                // before linking, leaving the master's occurrence at this instance time without
                // exception_event_id. Both etags match now, so nothing else would touch it.
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
                // The exception row is unchanged but its master was regenerated. linkException
                // matches the re-inserted master occurrence by `ABS(start_ts - recurrenceIdTime)
                // < 60s` and moves it to the exception's time with exception_event_id set.
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

            // The try spans map, validate, upsert and link, for the same reason as in the master
            // pass. The `continue`s inside are ordinary skips (invalid timestamps, race).
            //
            // resolveInstanceTime and the etag-unchanged re-links above sit outside it: they
            // work on already-resolved values, not the untrusted map step, so a throw there is
            // a real error.
            //
            // A synthetic master inserted above stays on failure. It is an invisible CANCELLED
            // placeholder with no occurrence of its own, cached in uidToMasterEvent for sibling
            // exceptions, and replaced when the real master arrives; deleting it would orphan a
            // sibling exception already linked to it.
            val savedExceptionTriple: Triple<Event, List<Attendee>, List<Attendee>> = try {
                // The master DTSTART lets the mapper normalize the RECURRENCE-ID
                // (ICalEventMapper.normalizeRecurrenceId) before writing originalInstanceTime.
                // It comes from masterDtStartFor, as the lookup's does, so the stored and
                // queried keys match.
                val mappedException = ICalEventMapper.toEntity(
                    icalEvent = meta.parsed,
                    rawIcal = meta.rawIcal,
                    calendarId = calendar.id,
                    caldavUrl = meta.caldavUrl,
                    etag = meta.etag,
                    masterDtStart = masterDtStartFor(meta.parsed.uid, masterEvent),
                )
                var event = mappedException.event

                if (!hasValidTimestamps(event)) {
                    Log.w(TAG, "Skipping exception ${meta.caldavUrl} - invalid timestamps: startTs=${event.startTs}, endTs=${event.endTs}")
                    sessionBuilder?.incrementSkipParseError()
                    sessionBuilder?.addWarning("Invalid timestamps in exception at ${filenameOf(meta.caldavUrl)} (endTs < startTs)")
                    continue
                }

                event = event.copy(
                    originalEventId = masterEvent.id,
                    originalSyncId = meta.parsed.uid
                )

                // Same preserved fields as the master pass.
                if (existingException != null) {
                    event = event.copy(
                        id = existingException.id,
                        createdAt = existingException.createdAt,
                        localModifiedAt = existingException.localModifiedAt,
                        etag = meta.etag ?: existingException.etag,
                        color = event.color ?: existingException.color
                    )
                }

                // Re-read the sync status, as in the master pass.
                if (existingException != null) {
                    val freshStatus = eventsDao.getSyncStatus(existingException.id)
                    if (freshStatus != null && freshStatus != SyncStatus.SYNCED) {
                        Log.d(TAG, "Race detected: exception ${meta.caldavUrl} gained pending changes ($freshStatus)")
                        sessionBuilder?.incrementSkipPendingLocal()
                        continue
                    }
                }

                // Upsert and link in one transaction; withDbRetry retries a locked database.
                // The exception always ends up as the master's linked occurrence (Model B):
                // linkException deletes any occurrence of its own (Model A), which would
                // otherwise show twice.
                withDbRetry {
                    database.runInTransaction {
                        val eventId = eventsDao.upsert(event)
                        val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                        seedCategories(saved)

                        val priorAttendees = attendeesDao.getForEventOnce(saved.id)

                        // An exception has its own attendee list (RFC 5545 §3.8.4.1).
                        attendeesDao.replaceForEvent(
                            saved.id,
                            mappedException.attendees.map { it.copy(eventId = saved.id) }
                        )

                        val originalTime = event.originalInstanceTime
                        if (originalTime != null) {
                            occurrenceGenerator.linkException(masterEvent.id, originalTime, saved)
                        } else {
                            // No instance time: a standalone occurrence.
                            occurrenceGenerator.regenerateOccurrences(saved)
                        }

                        Triple(saved, priorAttendees, mappedException.attendees)
                    }
                }
            } catch (_: SQLiteConstraintException) {
                // Synced in an earlier session. Skipping it keeps the sync from aborting on
                // every run (#55).
                Log.d(TAG, "Skipped already-synced exception ${meta.parsed.uid} " +
                    "(RECURRENCE-ID: ${meta.parsed.recurrenceId?.timestamp})")
                sessionBuilder?.incrementSkipAlreadySynced()
                sessionBuilder?.addWarning("Already-synced exception skipped at ${filenameOf(meta.caldavUrl)}")
                continue
            } catch (e: CancellationException) {
                throw e  // never swallow coroutine cancellation
            } catch (e: Exception) {
                // As in the master pass: the write rolled back; recovery is described on
                // recordProcessingFailure.
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

            // Etag-only update: another VEVENT in the same resource changed.
            if (existingException != null && !hasContentChanged(existingException, savedExceptionEvent)) {
                Log.d(TAG, "Etag-only update for exception: ${savedExceptionEvent.title}")
                continue
            }

            // Exceptions are notified like regular events.
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
                isRecurring = true, // an exception always belongs to a series
                calendarName = calendar.displayName,
                calendarColor = calendar.color,
                isFromInitialSync = isInitialSync
            ))
        }

        // Fourth pass: prune local exceptions the server excluded. When a changed occurrence is
        // deleted on another client, the server adds an EXDATE to the master (RFC 5545
        // §3.8.5.1) and drops the exception VEVENT. Pass 3 only visits exceptions the server
        // still sends, so without this the stale exception stays on the calendar.
        //
        // A local exception is pruned only when its instance is absent from this batch and the
        // master's EXDATE now covers it. Absence alone would delete live exceptions in two
        // reachable cases:
        //   - a server that breaks RFC 4791 §4.1 by splitting same-UID components across
        //     resources: only the master's resource is re-fetched, so its exceptions are absent;
        //   - an exception VEVENT the parser dropped (parseAllEvents silently skips
        //     unparseable components): on the server but absent from `present`.
        // So only what the master itself declares excluded is removed, and ambiguous signals
        // keep the data.
        //
        // Only UIDs whose master was saved in this batch are pruned: §4.1 also permits a
        // resource with only exceptions, which isn't authoritative for pruning.
        val presentInstancesByUid = exceptionEvents
            .groupBy { it.parsed.uid }
            .mapValues { (_, metas) ->
                metas.mapNotNull { resolveInstanceTime(it.parsed, uidToMasterEvent[it.parsed.uid]) }.toSet()
            }
        for (uid in uidsWithRegeneratedMaster) {
            val master = uidToMasterEvent[uid] ?: continue
            // A stale CDN read of a master pushed this cycle may omit an exception just added.
            // Pass 2 guards the master the same way; an ordinary upload doesn't report its
            // bundled exception ids one by one, so none of this master's exceptions are pruned.
            // A master refreshed after a merged upload is pruned like any other: its exceptions
            // went up as merged, and those it sent are in recentlyPushedEventIds, skipped below.
            if (master.id in recentlyPushedEventIds && master.id !in refetchEventIds) continue
            val present = presentInstancesByUid[uid].orEmpty()
            // EXDATE is stored as epoch-ms CSV normalized against the master's own DTSTART, the
            // same basis as the exception instance keys, so they compare directly.
            val exdateSet = master.exdate
                ?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?.toSet()
                .orEmpty()
            val localExceptions = eventsDao.getExceptionsForMaster(master.id)
            for (ex in localExceptions) {
                val instance = ex.originalInstanceTime ?: continue
                if (instance in present) continue
                if (instance !in exdateSet) continue
                // Local-first: never drop an exception with unsynced local edits; the next cycle
                // pushes (or resurrects) it.
                if (ex.hasPendingChanges()) continue
                if (ex.id in recentlyPushedEventIds) continue
                Log.d(TAG, "Pruning exception ${ex.id} (uid=$uid, instance=$instance) — EXDATE-excluded on master")
                // One transaction: a failure between the two would leave a cancelled occurrence
                // with a surviving, still-linked exception row that no later etag-matching pass
                // revisits.
                database.runInTransaction {
                    database.occurrencesDao().markCancelledByException(ex.id)
                    eventsDao.deleteById(ex.id)
                }
                // Notified only after the delete commits.
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

    /** A parsed VEVENT with the resource it came from. */
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
     * Fetches [hrefs] with concurrent calendar-multiget requests of [MULTIGET_BATCH_SIZE]
     * (v22.5.11). OkHttp's Dispatcher limits them to 5 concurrent requests per host.
     *
     * A batch that fails, or returns no events for more than one href, falls back to one fetch
     * per href via [fetchSingleHrefConcurrent]; other batches are unaffected. The empty case is
     * Zoho's quirk: HTTP 200 with an empty body for a multi-href calendar-multiget.
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
     * Fetches [hrefs] one per request, concurrently, as the fallback for a failed or empty
     * batch in [fetchEventsBatched]. Failed hrefs are skipped with a warning: partial data is
     * better than none.
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
