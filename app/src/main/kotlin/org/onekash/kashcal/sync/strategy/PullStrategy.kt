package org.onekash.kashcal.sync.strategy

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
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
import org.onekash.kashcal.sync.parser.ICalParser
import org.onekash.kashcal.sync.parser.ParsedEvent
import org.onekash.kashcal.sync.quirks.CalDavQuirks
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
    private val client: CalDavClient,
    private val parser: ICalParser,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    @Suppress("DEPRECATION") private val defaultQuirks: CalDavQuirks,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "PullStrategy"

        // Sync window: 1 year back, unlimited future (far-future date for CalDAV spec compliance)
        private const val PAST_WINDOW_MS = 365L * 24 * 60 * 60 * 1000
        private const val FUTURE_END_MS = 4102444800000L  // Jan 1, 2100 UTC

        // Occurrence expansion window (local generation) - shared across codebase
        const val OCCURRENCE_EXPANSION_MS = 2 * 365L * 24 * 60 * 60 * 1000  // 2 years
    }

    /**
     * Pull events from server for a calendar.
     *
     * @param calendar The calendar to sync
     * @param forceFullSync If true, ignores sync token and fetches all events
     * @param quirks Optional provider-specific quirks. If null, uses default (iCloud).
     * @param clientOverride Optional CalDavClient to use instead of the injected one.
     *                       Used by CalDavSyncWorker to provide isolated per-account clients.
     * @param sessionBuilder Optional builder for tracking sync session stats.
     * @return PullResult indicating success/failure and statistics
     */
    suspend fun pull(
        calendar: Calendar,
        forceFullSync: Boolean = false,
        quirks: CalDavQuirks? = null,
        clientOverride: CalDavClient? = null,
        sessionBuilder: SyncSessionBuilder? = null
    ): PullResult {
        val effectiveQuirks = quirks ?: defaultQuirks
        val effectiveClient = clientOverride ?: client

        Log.d(TAG, "Starting pull for calendar: ${calendar.displayName}")

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
                calendarsDao.updateSyncToken(
                    id = calendar.id,
                    syncToken = result.newSyncToken ?: calendar.syncToken,
                    ctag = result.newCtag ?: serverCtag
                )
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed: ${e.message}", e)
            PullResult.Error(
                code = -1,
                message = e.message ?: "Unknown error",
                isRetryable = true
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
            // 403/410 means sync token expired - fall back to full sync
            if (error.code == 403 || error.code == 410) {
                Log.w(TAG, "Sync token expired, falling back to full sync")
                return pullFull(calendar, quirks, clientToUse, sessionBuilder)
            }
            return PullResult.Error(error.code, error.message, error.isRetryable)
        }

        val syncReport = (reportResult as CalDavResult.Success).data
        Log.d(TAG, "syncCollection: ${syncReport.changed.size} changed, ${syncReport.deleted.size} deleted")

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
        val changedHrefs = syncReport.changed
            .filter { it.status == SyncItemStatus.OK }
            .map { it.href }

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

        // Fetch events in batches
        val eventsResult = clientToUse.fetchEventsByHref(calendar.caldavUrl, changedHrefs)
        if (eventsResult.isError()) {
            val error = eventsResult as CalDavResult.Error
            return PullResult.Error(error.code, error.message, error.isRetryable)
        }

        val serverEvents = (eventsResult as CalDavResult.Success).data

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

        // Don't advance sync token if events are missing (eventual consistency)
        // This ensures missing events are re-fetched on next sync
        val effectiveSyncToken = if (hasMissingEvents) {
            Log.w(TAG, "NOT advancing sync token due to ${missingHrefs.size} missing events")
            sessionBuilder?.setTokenAdvanced(false)
            calendar.syncToken  // Keep old token - next sync will re-fetch
        } else {
            sessionBuilder?.setTokenAdvanced(true)
            syncReport.syncToken  // Advance to new token (normal case)
        }

        return PullResult.Success(
            eventsAdded = processResult.added,
            eventsUpdated = processResult.updated,
            eventsDeleted = deleted,
            newSyncToken = effectiveSyncToken,
            newCtag = null,
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
        Log.d(TAG, "Full sync for calendar: ${calendar.displayName}")

        // Clean up any duplicate master events before processing
        // This handles edge cases where duplicates were created due to race conditions
        // (e.g., iCloud returning same event from multiple servers with different caldavUrls)
        val dedupedCount = eventsDao.deleteDuplicateMasterEvents()
        if (dedupedCount > 0) {
            Log.w(TAG, "Cleaned up $dedupedCount duplicate master events")
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
            val parseResult = parser.parse(serverEvent.icalData)
            if (!parseResult.isSuccess() || parseResult.events.isEmpty()) {
                Log.w(TAG, "Failed to parse event at ${serverEvent.url}")
                sessionBuilder?.incrementSkipParseError()
                continue
            }

            for (parsed in parseResult.events) {
                val meta = ParsedEventWithMeta(
                    parsed = parsed,
                    caldavUrl = serverEvent.url,
                    etag = serverEvent.etag
                )
                if (parsed.isException()) {
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

            val event = EventMapper.toEntity(
                parsed = meta.parsed,
                calendarId = calendar.id,
                caldavUrl = meta.caldavUrl,
                etag = meta.etag,
                existingEvent = existingEvent,
                defaultReminderMinutes = defaultReminderMinutes,
                defaultAllDayReminderMinutes = defaultAllDayReminderMinutes
            )

            // TRANSACTION: Upsert event and generate occurrences atomically
            // Prevents orphaned events (no occurrences) if crash occurs mid-operation
            val savedEvent = try {
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
            } catch (e: SQLiteConstraintException) {
                // Duplicate UID detected - fetch existing event and use that
                // This can happen due to unique constraint on (uid, calendar_id) for master events
                Log.w(TAG, "Duplicate UID detected for ${meta.parsed.uid}, fetching existing event")
                val existing = eventsDao.getMasterByUidAndCalendar(meta.parsed.uid, calendar.id)
                if (existing != null) {
                    uidToMasterEvent[meta.parsed.uid] = existing
                    continue // Skip to next event
                } else {
                    // Unexpected - constraint violation but no existing event found
                    throw e
                }
            }

            uidToMasterEvent[meta.parsed.uid] = savedEvent

            // Track change for UI notification
            val changeType = if (existingEvent == null) ChangeType.NEW else ChangeType.MODIFIED
            if (existingEvent == null) {
                added++
                sessionBuilder?.incrementWritten()
            } else {
                updated++
                sessionBuilder?.incrementUpdated()
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
                    |  RECURRENCE-ID: ${meta.parsed.recurrenceId}
                    |  caldavUrl: ${meta.caldavUrl}
                    |  Title: ${meta.parsed.summary}
                    |  Possible causes: Master outside sync window, master deleted, or sync order issue.
                """.trimMargin())
                sessionBuilder?.incrementSkipOrphanedException()
                continue
            }

            val existingException = findExistingException(
                masterEventId = masterEvent.id,
                originalInstanceTime = EventMapper.parseOriginalInstanceTime(meta.parsed.recurrenceId)
            )

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

            val event = EventMapper.toEntity(
                parsed = meta.parsed,
                calendarId = calendar.id,
                caldavUrl = meta.caldavUrl,
                etag = meta.etag,
                existingEvent = existingException,
                defaultReminderMinutes = defaultReminderMinutes,
                defaultAllDayReminderMinutes = defaultAllDayReminderMinutes
            ).copy(
                originalEventId = masterEvent.id,
                originalSyncId = meta.parsed.uid
            )

            // TRANSACTION: Upsert exception, generate occurrence, cancel original atomically
            // Prevents orphaned exception events (no occurrences) if crash occurs mid-operation
            val savedExceptionEvent = database.runInTransaction {
                val eventId = eventsDao.upsert(event)
                val saved = event.copy(id = if (eventId != -1L) eventId else event.id)

                // Generate occurrence for exception event (uses exception's DTSTART/DTEND)
                // This creates the occurrence for the NEW date (e.g., Jan 4 for a moved event)
                occurrenceGenerator.regenerateOccurrences(saved)

                // Mark original occurrence as cancelled (it's been moved/modified)
                val originalTime = event.originalInstanceTime
                if (originalTime != null) {
                    occurrenceGenerator.cancelOccurrence(masterEvent.id, originalTime)
                }

                saved // Return from transaction
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
     * Find an existing exception event by master ID and instance time.
     */
    private suspend fun findExistingException(
        masterEventId: Long,
        originalInstanceTime: Long?
    ): Event? {
        if (originalInstanceTime == null) return null
        return eventsDao.getExceptionForOccurrence(masterEventId, originalInstanceTime)
    }

    /**
     * Helper class to hold parsed event with metadata.
     */
    private data class ParsedEventWithMeta(
        val parsed: ParsedEvent,
        val caldavUrl: String,
        val etag: String?
    )
}
