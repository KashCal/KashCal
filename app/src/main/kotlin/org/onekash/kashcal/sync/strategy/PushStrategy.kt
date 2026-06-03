package org.onekash.kashcal.sync.strategy

import android.util.Log
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import javax.inject.Inject

/**
 * Handles pushing local changes to CalDAV server.
 *
 * Processes pending operations (CREATE, UPDATE, DELETE) in FIFO order.
 * Uses exponential backoff for failed operations.
 *
 * Process:
 * 1. Get ready operations from pending queue
 * 2. For each operation:
 *    - CREATE: Serialize event → PUT with If-None-Match
 *    - UPDATE: Serialize event → PUT with If-Match
 *    - DELETE: DELETE with If-Match
 * 3. Update event sync status on success
 * 4. Schedule retry on failure
 */
class PushStrategy @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val eventsDao: EventsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val accountRepository: AccountRepository,
    private val attendeesDao: AttendeesDao
) {
    companion object {
        private const val TAG = "PushStrategy"

        /** Extract .ics filename from caldavUrl for privacy-safe warning messages. */
        private fun filenameOf(url: String?): String =
            url?.substringAfterLast('/')?.ifEmpty { url } ?: "unknown"

        /** Human-readable operation name for warnings. */
        private fun operationName(op: String): String = when (op) {
            PendingOperation.OPERATION_CREATE -> "CREATE"
            PendingOperation.OPERATION_UPDATE -> "UPDATE"
            PendingOperation.OPERATION_DELETE -> "DELETE"
            PendingOperation.OPERATION_MOVE -> "MOVE"
            else -> op
        }
    }

    /**
     * Push all pending operations to the server.
     *
     * @param client CalDavClient to use for HTTP operations (created per-account by caller).
     * @return PushResult with statistics and any errors
     */
    suspend fun pushAll(client: CalDavClient): PushResult {
        val effectiveClient = client
        val now = System.currentTimeMillis()
        val readyOperations = pendingOperationsDao.getReadyOperations(now)

        if (readyOperations.isEmpty()) {
            Log.d(TAG, "No pending operations to push")
            return PushResult.NoPendingOperations
        }

        Log.d(TAG, "Processing ${readyOperations.size} pending operations")

        // Batch load all events and calendars upfront (fixes N+1 query pattern)
        val eventIds = readyOperations.map { it.eventId }.distinct()
        val eventsCache = eventsDao.getByIds(eventIds).associateBy { it.id }

        val calendarIds = eventsCache.values.map { it.calendarId }.distinct()
        val calendarsCache = calendarRepository.getCalendarsByIds(calendarIds).associateBy { it.id }

        Log.d(TAG, "Batch loaded ${eventsCache.size} events, ${calendarsCache.size} calendars")

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        val pushedEventIds = mutableSetOf<Long>()
        val warnings = mutableListOf<String>()

        for (operation in readyOperations) {
            // Mark as in progress
            pendingOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

            val event = eventsCache[operation.eventId]
            val result = processOperation(operation, eventsCache, calendarsCache, effectiveClient)

            when (result) {
                is SinglePushResult.Success -> {
                    // Delete the operation - it's done
                    pendingOperationsDao.deleteById(operation.id)

                    when (operation.operation) {
                        PendingOperation.OPERATION_CREATE -> {
                            created++
                            pushedEventIds.add(operation.eventId)
                        }
                        PendingOperation.OPERATION_UPDATE -> {
                            updated++
                            pushedEventIds.add(operation.eventId)
                        }
                        PendingOperation.OPERATION_DELETE -> deleted++
                        PendingOperation.OPERATION_MOVE -> {
                            created++; deleted++
                            pushedEventIds.add(operation.eventId)
                        }
                    }
                    // Forward any warnings from the operation (e.g., MOVE orphan)
                    result.warning?.let { warnings.add(it) }
                }
                is SinglePushResult.PhaseAdvanced -> {
                    // MOVE operation advanced from DELETE to CREATE phase
                    // Operation stays in queue with movePhase=1, retry_count=0
                    // Count DELETE as done; CREATE will happen in next sync cycle
                    deleted++
                    Log.d(TAG, "MOVE operation ${operation.id} advanced to CREATE phase")
                }
                is SinglePushResult.Conflict -> {
                    // Conflict - needs resolution
                    // For now, reschedule and let ConflictResolver handle it
                    scheduleRetry(operation, "Conflict: server has newer version")
                    failed++
                    warnings.add("Push ${operationName(operation.operation)} conflict (412) for ${filenameOf(event?.caldavUrl)}")
                }
                is SinglePushResult.RsvpModified -> {
                    handleRsvpModified(operation, result, warnings)
                    failed++
                }
                is SinglePushResult.Error -> {
                    if (result.isRetryable && operation.shouldRetry) {
                        scheduleRetry(operation, result.message)
                    } else {
                        // Mark as permanently failed
                        pendingOperationsDao.markFailed(
                            operation.id,
                            result.message,
                            System.currentTimeMillis()
                        )
                        // If linked CREATE permanently failed, remove the linked DELETE
                        // (cross-account move: prevents orphan DELETE after CREATE gives up)
                        if (operation.operation == PendingOperation.OPERATION_CREATE &&
                            operation.linkedMoveId != null) {
                            pendingOperationsDao.deleteLinkedDelete(operation.linkedMoveId)
                            Log.d(TAG, "Removed linked DELETE for failed CREATE (linkedMoveId=${operation.linkedMoveId})")
                        }
                    }
                    failed++
                    warnings.add("Push ${operationName(operation.operation)} failed (${result.code}) for ${filenameOf(event?.caldavUrl)}: ${result.message}")
                }
            }
        }

        Log.d(TAG, "Push complete: created=$created, updated=$updated, deleted=$deleted, failed=$failed")

        return PushResult.Success(
            eventsCreated = created,
            eventsUpdated = updated,
            eventsDeleted = deleted,
            operationsProcessed = readyOperations.size,
            operationsFailed = failed,
            pushedEventIds = pushedEventIds,
            pushWarnings = warnings
        )
    }

    /**
     * Push operations for a specific calendar.
     *
     * @param calendar The calendar to push operations for
     * @param client CalDavClient to use for HTTP operations (created per-account by caller).
     */
    suspend fun pushForCalendar(
        calendar: Calendar,
        client: CalDavClient
    ): PushResult {
        val effectiveClient = client
        val now = System.currentTimeMillis()
        val allReady = pendingOperationsDao.getReadyOperations(now)

        // Batch load events upfront (fixes N+1 query pattern)
        val eventIds = allReady.map { it.eventId }.distinct()
        val eventsCache = eventsDao.getByIds(eventIds).associateBy { it.id }

        // Filter operations for this calendar using correct filtering logic:
        // - DELETE: Use sourceCalendarId if present (from MOVE or cross-account), else event.calendarId
        // - MOVE DELETE phase: Use sourceCalendarId
        // - MOVE CREATE phase: Use targetCalendarId
        // - Other operations: Use event.calendarId
        val calendarOperations = allReady.filter { op ->
            when {
                // DELETE operation: Use sourceCalendarId if present (from synced→local or cross-account move)
                op.operation == PendingOperation.OPERATION_DELETE ->
                    op.sourceCalendarId?.let { it == calendar.id }
                        ?: (eventsCache[op.eventId]?.calendarId == calendar.id)

                // MOVE operation: Filter by phase
                op.operation == PendingOperation.OPERATION_MOVE ->
                    when (op.movePhase) {
                        PendingOperation.MOVE_PHASE_DELETE ->
                            op.sourceCalendarId?.let { it == calendar.id } ?: false
                        PendingOperation.MOVE_PHASE_CREATE ->
                            op.targetCalendarId == calendar.id
                        else -> false
                    }

                // CREATE, UPDATE: Use event's current calendarId
                else -> eventsCache[op.eventId]?.calendarId == calendar.id
            }
        }

        if (calendarOperations.isEmpty()) {
            return PushResult.NoPendingOperations
        }

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        val pushedEventIds = mutableSetOf<Long>()
        val warnings = mutableListOf<String>()

        for (operation in calendarOperations) {
            pendingOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

            val event = eventsCache[operation.eventId]
            val result = processOperation(operation, eventsCache, emptyMap(), effectiveClient)

            when (result) {
                is SinglePushResult.Success -> {
                    pendingOperationsDao.deleteById(operation.id)
                    when (operation.operation) {
                        PendingOperation.OPERATION_CREATE -> {
                            created++
                            pushedEventIds.add(operation.eventId)
                        }
                        PendingOperation.OPERATION_UPDATE -> {
                            updated++
                            pushedEventIds.add(operation.eventId)
                        }
                        PendingOperation.OPERATION_DELETE -> deleted++
                        PendingOperation.OPERATION_MOVE -> {
                            created++; deleted++
                            pushedEventIds.add(operation.eventId)
                        }
                    }
                    result.warning?.let { warnings.add(it) }
                }
                is SinglePushResult.PhaseAdvanced -> {
                    // MOVE operation advanced from DELETE to CREATE phase
                    deleted++
                }
                is SinglePushResult.Conflict -> {
                    scheduleRetry(operation, "Conflict: server has newer version")
                    failed++
                    warnings.add("Push ${operationName(operation.operation)} conflict (412) for ${filenameOf(event?.caldavUrl)}")
                }
                is SinglePushResult.RsvpModified -> {
                    handleRsvpModified(operation, result, warnings)
                    failed++
                }
                is SinglePushResult.Error -> {
                    if (result.isRetryable && operation.shouldRetry) {
                        scheduleRetry(operation, result.message)
                    } else {
                        pendingOperationsDao.markFailed(
                            operation.id,
                            result.message,
                            System.currentTimeMillis()
                        )
                        // If linked CREATE permanently failed, remove the linked DELETE
                        // (cross-account move: prevents orphan DELETE after CREATE gives up)
                        if (operation.operation == PendingOperation.OPERATION_CREATE &&
                            operation.linkedMoveId != null) {
                            pendingOperationsDao.deleteLinkedDelete(operation.linkedMoveId)
                            Log.d(TAG, "Removed linked DELETE for failed CREATE (linkedMoveId=${operation.linkedMoveId})")
                        }
                    }
                    failed++
                    warnings.add("Push ${operationName(operation.operation)} failed (${result.code}) for ${filenameOf(event?.caldavUrl)}: ${result.message}")
                }
            }
        }

        return PushResult.Success(
            eventsCreated = created,
            eventsUpdated = updated,
            eventsDeleted = deleted,
            operationsProcessed = calendarOperations.size,
            operationsFailed = failed,
            pushedEventIds = pushedEventIds,
            pushWarnings = warnings
        )
    }

    /**
     * Process a single pending operation.
     *
     * @param operation The operation to process
     * @param eventsCache Pre-loaded events for batch efficiency (empty map triggers DB lookup)
     * @param calendarsCache Pre-loaded calendars for batch efficiency (empty map triggers DB lookup)
     * @param clientToUse CalDavClient to use for HTTP operations
     */
    private suspend fun processOperation(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        calendarsCache: Map<Long, Calendar> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        return when (operation.operation) {
            PendingOperation.OPERATION_CREATE -> processCreate(operation, eventsCache, calendarsCache, clientToUse)
            PendingOperation.OPERATION_UPDATE -> processUpdate(operation, eventsCache, clientToUse)
            PendingOperation.OPERATION_DELETE -> processDelete(operation, eventsCache, clientToUse)
            PendingOperation.OPERATION_MOVE -> processMove(operation, eventsCache, calendarsCache, clientToUse)
            else -> SinglePushResult.Error(-1, "Unknown operation: ${operation.operation}", false)
        }
    }

    /**
     * Process CREATE operation - push new event to server.
     */
    private suspend fun processCreate(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        calendarsCache: Map<Long, Calendar> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)
            ?: return SinglePushResult.Error(-1, "Event not found", false)

        // Skip exception events - they're bundled with their master via serializeWithExceptions()
        // Exception events have originalEventId set, pointing to their master recurring event
        if (event.originalEventId != null) {
            Log.d(TAG, "Skipping exception event ${event.id} - bundled with master ${event.originalEventId}")
            return SinglePushResult.Success() // No-op, master push includes this exception
        }

        val calendar = calendarsCache[event.calendarId]
            ?: calendarRepository.getCalendarById(event.calendarId)
            ?: return SinglePushResult.Error(-1, "Calendar not found", false)

        // Serialize event to iCal (captures exceptions at this point in time)
        val (icalData, serializedExceptions) = serializeEventWithExceptions(event)

        Log.d(TAG, "Creating event on server: ${event.title} (${event.uid})")

        // Create on server
        val result = clientToUse.createEvent(calendar.caldavUrl, event.uid, icalData)

        return when {
            result.isSuccess() -> {
                val (url, etag) = result.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Unexpected null result from create", false)

                // Update local event with server URL and etag
                eventsDao.markCreatedOnServer(
                    event.id,
                    url,
                    etag,
                    System.currentTimeMillis()
                )

                // Update etags only for exceptions that were actually serialized and pushed
                // (avoids race condition where new exception created during push gets etag but wasn't pushed)
                for (exception in serializedExceptions) {
                    eventsDao.markSynced(exception.id, etag, System.currentTimeMillis())
                }
                if (serializedExceptions.isNotEmpty()) {
                    Log.d(TAG, "Updated etag for ${serializedExceptions.size} bundled exceptions")
                }

                Log.d(TAG, "Event created successfully: $url")
                SinglePushResult.Success(newEtag = etag, newUrl = url)
            }
            result.isConflict() -> {
                Log.w(TAG, "Event already exists on server")
                SinglePushResult.Conflict()
            }
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return SinglePushResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to create event: ${error.message}")
                SinglePushResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Process UPDATE operation - update existing event on server.
     */
    private suspend fun processUpdate(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)
            ?: return SinglePushResult.Error(-1, "Event not found", false)

        // Skip exception events - they're bundled with their master via serializeWithExceptions()
        // Exception events have originalEventId set, pointing to their master recurring event
        if (event.originalEventId != null) {
            Log.d(TAG, "Skipping exception event ${event.id} - bundled with master ${event.originalEventId}")
            return SinglePushResult.Success() // No-op, master push includes this exception
        }

        // Routed BEFORE the event.caldavUrl null-check below: the queued op
        // captures caldavUrl at queue time into operation.targetUrl, so an
        // RSVP must drain even when Event.caldavUrl was cleared between
        // queue and drain.
        if (operation.partstatOnly && operation.partstatTarget != null) {
            return processPartstatOnlyUpdate(operation, event, clientToUse)
        }

        if (event.caldavUrl == null) {
            // Event was never created on server - shouldn't happen
            Log.w(TAG, "Event has no caldavUrl, treating as CREATE")
            return processCreate(operation, eventsCache, emptyMap(), clientToUse)
        }

        val caldavUrl = event.caldavUrl // Guaranteed non-null (guard above)

        // Recover etag via PROPFIND if null or empty (server may have omitted <getetag> during pull)
        val effectiveEtag: String
        if (!event.etag.isNullOrEmpty()) {
            effectiveEtag = event.etag
        } else {
            // Same recovery pattern as 412 conflict retry below.
            Log.w(TAG, "Event has no etag, fetching via PROPFIND for ${filenameOf(caldavUrl)}")
            val fetchResult = clientToUse.fetchEtag(caldavUrl)
            when (fetchResult) {
                is CalDavResult.Success -> {
                    val fetched = fetchResult.data
                    if (fetched != null) {
                        // Note: the in-memory `event` object still has etag=null.
                        // We use `effectiveEtag` directly for the PUT, not event.etag.
                        eventsDao.updateEtag(event.id, fetched)
                        Log.d(TAG, "Recovered etag via PROPFIND: ${fetched.take(8)}...")
                        effectiveEtag = fetched
                    } else {
                        // Server returned success but no etag - non-retryable
                        Log.e(TAG, "PROPFIND returned null etag for event ${event.id}")
                        return SinglePushResult.Error(-1, "No etag for update", false)
                    }
                }
                is CalDavResult.Error -> {
                    // Propagate error's retryability (network errors retry, auth errors don't)
                    Log.e(TAG, "PROPFIND failed for event ${event.id}: ${fetchResult.message}")
                    return SinglePushResult.Error(
                        fetchResult.code,
                        "PROPFIND fallback failed: ${fetchResult.message}",
                        fetchResult.isRetryable
                    )
                }
            }
        }

        // Serialize event to iCal (captures exceptions at this point in time)
        val (icalData, serializedExceptions) = serializeEventWithExceptions(event)

        Log.d(TAG, "Updating event on server: ${event.title} with etag='$effectiveEtag'")

        // Update on server with If-Match
        val result = clientToUse.updateEvent(caldavUrl, icalData, effectiveEtag)

        return when {
            result.isSuccess() -> {
                val newEtag = result.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Unexpected null result from update", false)

                // Update local event
                eventsDao.markSynced(event.id, newEtag, System.currentTimeMillis())

                // Update etags only for exceptions that were actually serialized and pushed
                // (avoids race condition where new exception created during push gets etag but wasn't pushed)
                for (exception in serializedExceptions) {
                    eventsDao.markSynced(exception.id, newEtag, System.currentTimeMillis())
                }
                if (serializedExceptions.isNotEmpty()) {
                    Log.d(TAG, "Updated etag for ${serializedExceptions.size} bundled exceptions")
                }

                Log.d(TAG, "Event updated successfully")
                SinglePushResult.Success(newEtag = newEtag)
            }
            result.isConflict() -> {
                // 412 Precondition Failed: server etag changed since our last pull.
                // Common in shared calendars (another user's edit) or iCloud housekeeping.
                // Retry once with a fresh etag before falling through to ConflictResolver.
                Log.w(TAG, "412 Conflict for ${event.title}, fetching fresh etag for retry")
                val freshEtagResult = clientToUse.fetchEtag(caldavUrl)
                val freshEtag = freshEtagResult.getOrNull()

                if (freshEtag != null) {
                    // Update DB etag for retry. Note: the in-memory `event` object is now
                    // stale — the retry uses `freshEtag` variable directly, not event.etag.
                    // The eventsCache (batch-loaded at push start) is also not updated,
                    // but this is safe since each operation processes independently.
                    eventsDao.updateEtag(event.id, freshEtag)
                    val retryResult = clientToUse.updateEvent(caldavUrl, icalData, freshEtag)
                    when {
                        retryResult.isSuccess() -> {
                            val newEtag = retryResult.getOrNull()
                                ?: return SinglePushResult.Error(-1, "Null result from retry", false)
                            val now = System.currentTimeMillis()
                            eventsDao.markSynced(event.id, newEtag, now)
                            for (exception in serializedExceptions) {
                                eventsDao.markSynced(exception.id, newEtag, now)
                            }
                            Log.d(TAG, "412 retry succeeded for ${event.title}")
                            SinglePushResult.Success(newEtag = newEtag)
                        }
                        else -> {
                            Log.w(TAG, "412 retry also failed for ${event.title}")
                            SinglePushResult.Conflict()
                        }
                    }
                } else {
                    Log.w(TAG, "fetchEtag failed for ${event.title}, deferring to conflict resolution")
                    SinglePushResult.Conflict()
                }
            }
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return SinglePushResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to update event: ${error.message}")
                SinglePushResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Process a PARTSTAT-only RSVP write.
     *
     * The body sent to the server is built by patching the original rawIcal
     * to update only the current user's PARTSTAT — every other ATTENDEE row,
     * ORGANIZER, SUMMARY, DESCRIPTION, RRULE, and SEQUENCE survives verbatim.
     * SEQUENCE is intentionally not bumped (RFC 5546 §2.1.4 — attendee
     * PARTSTAT-only PUT must not bump). Some servers (iCloud) auto-bump on
     * the wire; we tolerate that on the next pull but never assert higher
     * SEQUENCE on the client side.
     *
     * 412 retry strategy:
     * 1. First PUT 412 → fetch fresh ETag AND fresh body via fetchEvent,
     *    re-run the patch against the new body, retry once.
     * 2. Second 412 → return [SinglePushResult.RsvpModified] so the caller
     *    surfaces a "this event was modified — please re-respond" snackbar
     *    rather than auto-retrying indefinitely.
     */
    private suspend fun processPartstatOnlyUpdate(
        operation: PendingOperation,
        event: Event,
        clientToUse: CalDavClient
    ): SinglePushResult {
        val partstatTarget = operation.partstatTarget
            ?: return SinglePushResult.Error(-1, "partstat_only without partstat_target", false)
        // Prefer the URL captured at queue time so the PUT survives any path
        // that cleared Event.caldavUrl between queue and drain. Falls back to
        // event.caldavUrl for ops queued by older app versions (pre-targetUrl).
        val caldavUrl = operation.targetUrl ?: event.caldavUrl
            ?: return SinglePushResult.Error(-1, "PARTSTAT-only on event with no caldavUrl", false)

        val calendar = calendarRepository.getCalendarById(event.calendarId)
            ?: return SinglePushResult.Error(-1, "Calendar not found for RSVP push", false)
        val account = accountRepository.getAccountById(calendar.accountId)
            ?: return SinglePushResult.Error(-1, "Account not found for RSVP push", false)

        val firstBody = IcsPatcher.patchAttendeeReply(event.rawIcal, account, partstatTarget)
            ?: return SinglePushResult.Error(
                -1,
                "Could not patch RSVP body (rawIcal missing or self attendee absent)",
                false
            )

        // Recover etag if missing — same recovery pattern used by full-event
        // updates above.
        val effectiveEtag = if (!event.etag.isNullOrEmpty()) {
            event.etag
        } else {
            val fetched = clientToUse.fetchEtag(caldavUrl).getOrNull()
            if (fetched != null) {
                eventsDao.updateEtag(event.id, fetched)
                fetched
            } else {
                Log.w(TAG, "RSVP push: missing etag and PROPFIND fallback failed")
                return SinglePushResult.Error(-1, "No etag for RSVP update", true)
            }
        }

        Log.d(TAG, "RSVP PUT: ${event.title} (PARTSTAT=$partstatTarget)")
        val firstResult = clientToUse.updateEvent(caldavUrl, firstBody, effectiveEtag)

        return when {
            firstResult.isSuccess() -> {
                val newEtag = firstResult.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Null etag from RSVP PUT", false)
                eventsDao.markSynced(event.id, newEtag, System.currentTimeMillis())
                SinglePushResult.Success(newEtag = newEtag)
            }
            firstResult.isConflict() -> {
                // GET-replay-retry: refresh both ETag and rawIcal, re-patch,
                // try once more. The body refresh is what distinguishes the
                // RSVP retry from the full-event retry — an organizer edit
                // may have added/removed attendees we need to preserve.
                Log.w(TAG, "RSVP 412 for ${event.title}, refreshing body for retry")
                val freshEtag = clientToUse.fetchEtag(caldavUrl).getOrNull()
                val freshFetch = clientToUse.fetchEvent(caldavUrl)
                val freshIcal = (freshFetch as? CalDavResult.Success)?.data?.icalData
                if (freshEtag == null || freshIcal == null) {
                    Log.w(TAG, "RSVP retry setup failed for ${event.title}")
                    return SinglePushResult.RsvpModified(event.title)
                }
                eventsDao.updateEtag(event.id, freshEtag)
                val retryBody = IcsPatcher.patchAttendeeReply(freshIcal, account, partstatTarget)
                if (retryBody == null) {
                    Log.w(TAG, "RSVP retry patch failed for ${event.title}")
                    return SinglePushResult.RsvpModified(event.title)
                }
                val retryResult = clientToUse.updateEvent(caldavUrl, retryBody, freshEtag)
                when {
                    retryResult.isSuccess() -> {
                        val newEtag = retryResult.getOrNull()
                            ?: return SinglePushResult.Error(-1, "Null etag from RSVP retry", false)
                        eventsDao.markSynced(event.id, newEtag, System.currentTimeMillis())
                        Log.d(TAG, "RSVP 412 retry succeeded for ${event.title}")
                        SinglePushResult.Success(newEtag = newEtag)
                    }
                    else -> {
                        Log.w(TAG, "RSVP 412 retry also failed for ${event.title}")
                        SinglePushResult.RsvpModified(event.title)
                    }
                }
            }
            else -> {
                val error = (firstResult as? CalDavResult.Error)
                    ?: return SinglePushResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "RSVP PUT failed: ${error.message}")
                SinglePushResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Process DELETE operation - delete event from server.
     *
     * Uses operation.targetUrl if available (for calendar moves where
     * event.caldavUrl was already cleared), otherwise falls back to event.caldavUrl.
     */
    private suspend fun processDelete(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)

        // Use targetUrl from operation if available (for MOVE operations),
        // otherwise fall back to event's caldavUrl
        val caldavUrl = operation.targetUrl ?: event?.caldavUrl

        // Event might already be deleted locally
        if (event == null && caldavUrl == null) {
            Log.d(TAG, "Event already deleted locally and no targetUrl")
            return SinglePushResult.Success()
        }

        if (caldavUrl == null) {
            // Never synced to server - just delete locally
            Log.d(TAG, "Event was never on server, deleting locally")
            event?.let { eventsDao.deleteById(it.id) }
            return SinglePushResult.Success()
        }

        // Delete from server
        val etag = event?.etag.orEmpty()
        Log.d(TAG, "Deleting event from server: ${event?.title ?: "unknown"} with etag='$etag'")

        val result = clientToUse.deleteEvent(caldavUrl, etag)

        return when {
            result.isSuccess() -> {
                // Delete locally
                event?.let { eventsDao.deleteById(it.id) }
                Log.d(TAG, "Event deleted successfully")
                SinglePushResult.Success()
            }
            result.isConflict() -> {
                Log.w(TAG, "Event modified on server before delete")
                SinglePushResult.Conflict()
            }
            result.isNotFound() -> {
                // Already deleted on server - delete locally
                Log.d(TAG, "Event already deleted on server")
                event?.let { eventsDao.deleteById(it.id) }
                SinglePushResult.Success()
            }
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return SinglePushResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to delete event: ${error.message}")
                SinglePushResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Process MOVE operation - move event between calendars on same account.
     *
     * Strategy (v21.6.0):
     * 1. Try WebDAV MOVE first (atomic, avoids UID conflicts on iCloud)
     * 2. If MOVE not supported (403/405), fall back to DELETE+CREATE
     *
     * Phase-aware retry:
     * - Phase 0 (MOVE/DELETE): Try MOVE, fallback to DELETE, then advance to Phase 1
     * - Phase 1 (CREATE): Execute CREATE with independent retry budget
     *
     * Each phase gets its own retries to prevent event loss.
     */
    private suspend fun processMove(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        calendarsCache: Map<Long, Calendar> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)
            ?: return SinglePushResult.Error(-1, "Event not found for MOVE", false)

        val targetCalendarId = operation.targetCalendarId
            ?: return SinglePushResult.Error(-1, "No target calendar for MOVE", false)

        val calendar = calendarsCache[targetCalendarId]
            ?: calendarRepository.getCalendarById(targetCalendarId)
            ?: return SinglePushResult.Error(-1, "Target calendar not found for MOVE", false)

        // Phase 0: Try WebDAV MOVE first
        if (operation.movePhase == PendingOperation.MOVE_PHASE_DELETE) {
            val sourceUrl = operation.targetUrl
            if (sourceUrl == null) {
                // No source URL - just advance to CREATE phase
                Log.d(TAG, "MOVE Phase 0: No source URL, advancing to CREATE")
                pendingOperationsDao.advanceToCreatePhase(operation.id, System.currentTimeMillis())
                return SinglePushResult.PhaseAdvanced
            }

            // Try WebDAV MOVE first (atomic operation)
            Log.d(TAG, "MOVE Phase 0: Trying WebDAV MOVE from $sourceUrl to ${calendar.caldavUrl}")
            val moveResult = clientToUse.moveEvent(sourceUrl, calendar.caldavUrl, event.uid)

            when {
                moveResult.isSuccess() -> {
                    // MOVE succeeded - we're done! Skip CREATE phase entirely.
                    val (newUrl, newEtag) = moveResult.getOrNull()
                        ?: return SinglePushResult.Error(-1, "Null result from MOVE", false)

                    eventsDao.markCreatedOnServer(event.id, newUrl, newEtag, System.currentTimeMillis())
                    pendingOperationsDao.deleteById(operation.id)
                    Log.d(TAG, "MOVE succeeded: Event moved atomically to $newUrl")
                    return SinglePushResult.Success(newEtag = newEtag, newUrl = newUrl)
                }

                moveResult.isNotFound() -> {
                    // Source already gone - advance to CREATE phase (no DELETE needed)
                    Log.d(TAG, "MOVE Phase 0: Source not found (404), advancing to CREATE")
                    pendingOperationsDao.advanceToCreatePhase(operation.id, System.currentTimeMillis())
                    return SinglePushResult.PhaseAdvanced
                }

                else -> {
                    val error = moveResult as? CalDavResult.Error
                    val code = error?.code ?: -1

                    // 403/405/412 = MOVE not supported or failed, fall back to CREATE+DELETE
                    // - 403: Forbidden (cross-server move)
                    // - 405: Method Not Allowed (server doesn't support MOVE)
                    // - 412: Precondition Failed (iCloud returns this for MOVE)
                    // Safety: CREATE first, DELETE second (ensures no data loss)
                    if (code == 403 || code == 405 || code == 412) {
                        Log.w(TAG, "MOVE failed ($code), falling back to CREATE+DELETE")
                        // Just advance to CREATE phase - DELETE will happen after CREATE succeeds
                        pendingOperationsDao.advanceToCreatePhase(operation.id, System.currentTimeMillis())
                        return SinglePushResult.PhaseAdvanced
                    }

                    // Other error - retry MOVE
                    Log.w(TAG, "MOVE Phase 0 failed: ${error?.message}")
                    return SinglePushResult.Error(
                        code,
                        "MOVE failed: ${error?.message}",
                        error?.isRetryable ?: true
                    )
                }
            }
        }

        // Phase 1: CREATE first, then DELETE (safety: ensure event exists before deleting source)
        Log.d(TAG, "MOVE Phase 1: Creating in new calendar: ${calendar.displayName}")

        val (icalData, _) = serializeEventWithExceptions(event)
        val createResult = clientToUse.createEvent(calendar.caldavUrl, event.uid, icalData)

        return when {
            createResult.isSuccess() -> {
                val (url, etag) = createResult.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Null result from create", false)

                eventsDao.markCreatedOnServer(event.id, url, etag, System.currentTimeMillis())
                Log.d(TAG, "MOVE Phase 1: Event created successfully at $url")

                // Now DELETE from source (after CREATE succeeded - safe order)
                var moveOrphanWarning: String? = null
                val sourceUrl = operation.targetUrl
                if (sourceUrl != null) {
                    Log.d(TAG, "MOVE Phase 1: Deleting from source: $sourceUrl")
                    val deleteResult = clientToUse.deleteEvent(sourceUrl, "")
                    when {
                        deleteResult.isSuccess() || deleteResult.isNotFound() -> {
                            Log.d(TAG, "MOVE complete: CREATE+DELETE succeeded")
                        }
                        else -> {
                            // DELETE failed but CREATE succeeded - event is safe in target
                            // Log warning but don't fail the operation (may leave orphan on source)
                            val delError = deleteResult as? CalDavResult.Error
                            Log.w(TAG, "MOVE: DELETE from source failed (${delError?.code}): ${delError?.message}")
                            Log.w(TAG, "Event exists in target but may remain in source as orphan")
                            moveOrphanWarning = "MOVE: event may be duplicated — DELETE from source failed (${delError?.code})"
                        }
                    }
                }

                SinglePushResult.Success(newEtag = etag, newUrl = url, warning = moveOrphanWarning)
            }
            createResult.isConflict() -> {
                Log.w(TAG, "MOVE Phase 1: Conflict creating in new calendar (UID exists)")
                SinglePushResult.Conflict()
            }
            else -> {
                val error = createResult as? CalDavResult.Error
                Log.e(TAG, "MOVE Phase 1: Failed to create in new calendar: ${error?.message}")
                SinglePushResult.Error(
                    error?.code ?: -1,
                    error?.message ?: "MOVE failed",
                    error?.isRetryable ?: true
                )
            }
        }
    }

    /**
     * Serialize event, including exceptions if it's a recurring master.
     *
     * Returns both the iCal data and the list of exceptions that were serialized.
     * This is important for correctly updating etags - we must only update etags
     * for exceptions that were actually included in the push, to avoid a race
     * condition where a newly created exception gets an etag but wasn't pushed.
     */
    private suspend fun serializeEventWithExceptions(event: Event): Pair<String, List<Event>> {
        // Load the authoritative ATTENDEE set from the table (populated on pull)
        // before serializing, rather than relying on the rawIcal body — locally
        // created events have no rawIcal, and exception VEVENTs carry their own
        // per-instance attendees that the master's body doesn't include.
        val masterAttendees = attendeesDao.getForEventOnce(event.id)
        return if (event.rrule != null && event.originalEventId == null) {
            // Master recurring event - include exceptions, each with its own
            // attendee set so per-occurrence attendees round-trip on push.
            val exceptions = eventsDao.getExceptionsForMaster(event.id)
            val exceptionsWithAttendees = exceptions.map { exception ->
                exception to attendeesDao.getForEventOnce(exception.id)
            }
            val icalData = IcsPatcher.serializeWithExceptions(
                master = event,
                masterAttendees = masterAttendees,
                exceptionsWithAttendees = exceptionsWithAttendees
            )
            icalData to exceptions
        } else {
            // Single event or exception event
            IcsPatcher.serialize(event, masterAttendees) to emptyList()
        }
    }

    /**
     * Schedule retry for failed operation.
     */
    /**
     * Mark an RSVP write that hit a second 412 as failed and append the
     * user-facing warning. Caller increments the failed counter.
     */
    private suspend fun handleRsvpModified(
        operation: PendingOperation,
        result: SinglePushResult.RsvpModified,
        warnings: MutableList<String>
    ) {
        pendingOperationsDao.markFailed(
            operation.id,
            "RSVP modified — user re-confirmation required",
            System.currentTimeMillis()
        )
        warnings.add(
            "RSVP for ${result.eventTitle} failed — event was modified. Please re-respond."
        )
    }

    private suspend fun scheduleRetry(operation: PendingOperation, error: String) {
        val delay = PendingOperation.calculateRetryDelay(operation.retryCount)
        val nextRetryAt = System.currentTimeMillis() + delay

        Log.d(TAG, "Scheduling retry for operation ${operation.id} at ${java.util.Date(nextRetryAt)}")

        pendingOperationsDao.scheduleRetry(
            operation.id,
            nextRetryAt,
            error,
            System.currentTimeMillis()
        )

        // Also record error on event
        eventsDao.recordSyncError(operation.eventId, error, System.currentTimeMillis())
    }
}
