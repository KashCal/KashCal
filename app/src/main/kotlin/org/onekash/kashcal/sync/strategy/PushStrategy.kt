package org.onekash.kashcal.sync.strategy

import android.util.Log
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
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
    private val pendingOperationsDao: PendingOperationsDao
) {
    companion object {
        private const val TAG = "PushStrategy"
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

        for (operation in readyOperations) {
            // Mark as in progress
            pendingOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

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
                        PendingOperation.OPERATION_MOVE -> { created++; deleted++ }  // Counts as both
                    }
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
                    }
                    failed++
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
            pushedEventIds = pushedEventIds
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

        for (operation in calendarOperations) {
            pendingOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

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
                        PendingOperation.OPERATION_MOVE -> { created++; deleted++ }  // Counts as both
                    }
                }
                is SinglePushResult.PhaseAdvanced -> {
                    // MOVE operation advanced from DELETE to CREATE phase
                    deleted++
                }
                is SinglePushResult.Conflict -> {
                    scheduleRetry(operation, "Conflict: server has newer version")
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
                    }
                    failed++
                }
            }
        }

        return PushResult.Success(
            eventsCreated = created,
            eventsUpdated = updated,
            eventsDeleted = deleted,
            operationsProcessed = calendarOperations.size,
            operationsFailed = failed,
            pushedEventIds = pushedEventIds
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

        if (event.caldavUrl == null) {
            // Event was never created on server - shouldn't happen
            Log.w(TAG, "Event has no caldavUrl, treating as CREATE")
            return processCreate(operation, eventsCache, emptyMap(), clientToUse)
        }

        if (event.etag == null) {
            Log.w(TAG, "Event has no etag, cannot update safely")
            return SinglePushResult.Error(-1, "No etag for update", false)
        }

        // Serialize event to iCal (captures exceptions at this point in time)
        val (icalData, serializedExceptions) = serializeEventWithExceptions(event)

        // Safe access - we've already checked these aren't null above
        val caldavUrl = event.caldavUrl
            ?: return SinglePushResult.Error(-1, "Event has no CalDAV URL", false)
        val etag = event.etag
            ?: return SinglePushResult.Error(-1, "Event has no ETag", false)

        Log.d(TAG, "Updating event on server: ${event.title} with etag='$etag'")

        // Update on server with If-Match
        val result = clientToUse.updateEvent(caldavUrl, icalData, etag)

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
        val etag = event?.etag ?: ""
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
                        }
                    }
                }

                SinglePushResult.Success(newEtag = etag, newUrl = url)
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
                    error?.message ?: "Unknown error",
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
        return if (event.rrule != null && event.originalEventId == null) {
            // Master recurring event - include exceptions
            val exceptions = eventsDao.getExceptionsForMaster(event.id)
            val icalData = IcsPatcher.serializeWithExceptions(event, exceptions)
            icalData to exceptions
        } else {
            // Single event or exception event
            IcsPatcher.serialize(event) to emptyList()
        }
    }

    /**
     * Schedule retry for failed operation.
     */
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
