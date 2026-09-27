package org.onekash.kashcal.sync.strategy

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingCancelsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.icaldav.scheduling.ITipBuilder
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.domain.identity.canEditAsOrganizer
import org.onekash.kashcal.domain.identity.effectiveAddresses
import org.onekash.kashcal.domain.scheduling.DeliveryAction
import org.onekash.kashcal.domain.scheduling.DeliveryState
import org.onekash.kashcal.domain.scheduling.classifyDelivery
import org.onekash.kashcal.domain.scheduling.routeDelivery
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.OutboxDeliveryClass
import org.onekash.kashcal.sync.client.model.classifyRequestStatus
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.util.AddressNormalizer
import javax.inject.Inject

/**
 * Pushes the ready pending operations to the CalDAV server in FIFO order.
 *
 * - CREATE: PUT the serialized event with `If-None-Match: *`.
 * - UPDATE: PUT with `If-Match`, and after a 412 once more on the server's current copy
 *   ([retryOnServerCopy]); a PARTSTAT-only RSVP patches the stored server copy instead.
 * - DELETE: DELETE with `If-Match`.
 * - MOVE: WebDAV MOVE, falling back to CREATE in the target then DELETE from the source.
 *
 * A success removes the op. A retryable failure within the op's retry budget
 * ([PendingOperation.shouldRetry]) is rescheduled with exponential backoff
 * ([PendingOperation.calculateRetryDelay]), and so is an unresolved 412 whatever the budget. Any
 * other failure, and an RSVP whose 412 retry failed, marks the op failed.
 */
class PushStrategy @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val eventsDao: EventsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val accountRepository: AccountRepository,
    private val attendeesDao: AttendeesDao,
    private val pendingCancelsDao: PendingCancelsDao
) {
    private val icalParser = ICalParser()

    companion object {
        private const val TAG = "PushStrategy"

        /**
         * Caps CANCEL delivery attempts before a pending_cancels row is abandoned, so a row
         * whose server never offers a usable channel (declined with no outbox, no receipt ever
         * stamped, or transient failures) can't retry forever. The guest stays removed locally
         * either way.
         */
        private const val MAX_CANCEL_ATTEMPTS = 10

        /** Returns the .ics filename of [url], so warnings don't carry the full URL. */
        private fun filenameOf(url: String?): String =
            url?.substringAfterLast('/')?.ifEmpty { url } ?: "unknown"

        /** Returns the operation name used in warnings. */
        private fun operationName(op: String): String = when (op) {
            PendingOperation.OPERATION_CREATE -> "CREATE"
            PendingOperation.OPERATION_UPDATE -> "UPDATE"
            PendingOperation.OPERATION_DELETE -> "DELETE"
            PendingOperation.OPERATION_MOVE -> "MOVE"
            else -> op
        }
    }

    /**
     * Pushes every ready pending operation through [client], which the caller creates per
     * account.
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

        // Load every event and calendar once up front, not per operation.
        val eventIds = readyOperations.map { it.eventId }.distinct()
        val eventsCache = eventsDao.getByIds(eventIds).associateBy { it.id }

        val calendarIds = eventsCache.values.map { it.calendarId }.distinct()
        val calendarsCache = calendarRepository.getCalendarsByIds(calendarIds).associateBy { it.id }

        Log.d(TAG, "Batch loaded ${eventsCache.size} events, ${calendarsCache.size} calendars")
        val sentReplies = mutableMapOf<String, SentReply>()

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        val pushedEventIds = mutableSetOf<Long>()
        val refetchEventIds = mutableSetOf<Long>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<PushResult.PushFailure>()

        for (operation in readyOperations) {
            pendingOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

            val event = eventsCache[operation.eventId]
            val result = processOperation(operation, eventsCache, calendarsCache, effectiveClient, sentReplies)

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
                        PendingOperation.OPERATION_DELETE -> {
                            deleted++
                            // A row the DELETE spared (it moved elsewhere) must be
                            // shielded from this cycle's pull too.
                            result.sparedEventId?.let { pushedEventIds.add(it) }
                        }
                        PendingOperation.OPERATION_MOVE -> {
                            created++; deleted++
                            pushedEventIds.add(operation.eventId)
                        }
                    }
                    if (result.refetch) refetchEventIds.add(operation.eventId)
                    pushedEventIds.addAll(result.sentOccurrenceIds)
                    // E.g. a MOVE that may have left a copy in the source.
                    result.warning?.let { warnings.add(it) }
                }
                is SinglePushResult.PhaseAdvanced -> {
                    // The MOVE op stays queued in its CREATE phase with a fresh retry budget;
                    // the CREATE runs next sync cycle.
                    deleted++
                    Log.d(TAG, "MOVE operation ${operation.id} advanced to CREATE phase")
                }
                is SinglePushResult.Conflict -> {
                    // The recorded "Conflict" error hands the op to ConflictResolver.
                    scheduleRetry(operation, "Conflict: server has newer version")
                    failed++
                    warnings.add("Push ${operationName(operation.operation)} conflict (412) for ${filenameOf(event?.caldavUrl)}")
                }
                is SinglePushResult.RsvpModified -> {
                    handleRsvpModified(operation, result, warnings)
                    failed++
                }
                is SinglePushResult.Error -> {
                    val msg = "Push ${operationName(operation.operation)} failed (${result.code}) for ${filenameOf(event?.caldavUrl)}: ${result.message}"
                    if (result.isRetryable && operation.shouldRetry) {
                        // Retried next sync, so a warning, not an error.
                        scheduleRetry(operation, result.message)
                        warnings.add(msg)
                    } else {
                        pendingOperationsDao.markFailed(
                            operation.id,
                            result.message,
                            System.currentTimeMillis()
                        )
                        // A cross-account move's DELETE waits on its linked CREATE; once the
                        // CREATE gives up, drop the DELETE so the source copy survives.
                        if (operation.operation == PendingOperation.OPERATION_CREATE &&
                            operation.linkedMoveId != null) {
                            pendingOperationsDao.deleteLinkedDelete(operation.linkedMoveId)
                            Log.d(TAG, "Removed linked DELETE for failed CREATE (linkedMoveId=${operation.linkedMoveId})")
                        }
                        // The change didn't reach the server and won't be retried: an error.
                        errors.add(PushResult.PushFailure(result.code, msg))
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
            pushedEventIds = pushedEventIds,
            refetchEventIds = refetchEventIds,
            pushWarnings = warnings,
            pushErrors = errors
        )
    }

    /**
     * Pushes the ready operations that belong to [calendar] through [client], which the caller
     * creates per account.
     */
    suspend fun pushForCalendar(
        calendar: Calendar,
        client: CalDavClient
    ): PushResult {
        val effectiveClient = client
        val now = System.currentTimeMillis()
        val allReady = pendingOperationsDao.getReadyOperations(now)

        // Load every event once up front, not per operation.
        val eventIds = allReady.map { it.eventId }.distinct()
        val eventsCache = eventsDao.getByIds(eventIds).associateBy { it.id }

        // Which calendar an op belongs to. A move has already changed the event's calendarId,
        // so a move's DELETE belongs to its source.
        // PendingOperationsDao.getConflictOperationsForCalendar scopes the same way; keep the
        // two in step.
        val calendarOperations = allReady.filter { op ->
            when {
                // sourceCalendarId is set by a move to a local calendar or another account.
                op.operation == PendingOperation.OPERATION_DELETE ->
                    op.sourceCalendarId?.let { it == calendar.id }
                        ?: (eventsCache[op.eventId]?.calendarId == calendar.id)

                // A MOVE's DELETE phase belongs to the source, its CREATE phase to the target.
                op.operation == PendingOperation.OPERATION_MOVE ->
                    when (op.movePhase) {
                        PendingOperation.MOVE_PHASE_DELETE ->
                            op.sourceCalendarId?.let { it == calendar.id } ?: false
                        PendingOperation.MOVE_PHASE_CREATE ->
                            op.targetCalendarId == calendar.id
                        else -> false
                    }

                else -> eventsCache[op.eventId]?.calendarId == calendar.id
            }
        }

        if (calendarOperations.isEmpty()) {
            return PushResult.NoPendingOperations
        }
        val sentReplies = mutableMapOf<String, SentReply>()

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        val pushedEventIds = mutableSetOf<Long>()
        val refetchEventIds = mutableSetOf<Long>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<PushResult.PushFailure>()

        for (operation in calendarOperations) {
            pendingOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

            val event = eventsCache[operation.eventId]
            val result = processOperation(operation, eventsCache, emptyMap(), effectiveClient, sentReplies)

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
                        PendingOperation.OPERATION_DELETE -> {
                            deleted++
                            // A row the DELETE spared (it moved elsewhere) must be
                            // shielded from this cycle's pull too.
                            result.sparedEventId?.let { pushedEventIds.add(it) }
                        }
                        PendingOperation.OPERATION_MOVE -> {
                            created++; deleted++
                            pushedEventIds.add(operation.eventId)
                        }
                    }
                    if (result.refetch) refetchEventIds.add(operation.eventId)
                    pushedEventIds.addAll(result.sentOccurrenceIds)
                    result.warning?.let { warnings.add(it) }
                }
                is SinglePushResult.PhaseAdvanced -> {
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
                    val msg = "Push ${operationName(operation.operation)} failed (${result.code}) for ${filenameOf(event?.caldavUrl)}: ${result.message}"
                    if (result.isRetryable && operation.shouldRetry) {
                        // Retried next sync, so a warning, not an error.
                        scheduleRetry(operation, result.message)
                        warnings.add(msg)
                    } else {
                        pendingOperationsDao.markFailed(
                            operation.id,
                            result.message,
                            System.currentTimeMillis()
                        )
                        // A cross-account move's DELETE waits on its linked CREATE; once the
                        // CREATE gives up, drop the DELETE so the source copy survives.
                        if (operation.operation == PendingOperation.OPERATION_CREATE &&
                            operation.linkedMoveId != null) {
                            pendingOperationsDao.deleteLinkedDelete(operation.linkedMoveId)
                            Log.d(TAG, "Removed linked DELETE for failed CREATE (linkedMoveId=${operation.linkedMoveId})")
                        }
                        // The change didn't reach the server and won't be retried: an error.
                        errors.add(PushResult.PushFailure(result.code, msg))
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
            pushedEventIds = pushedEventIds,
            refetchEventIds = refetchEventIds,
            pushWarnings = warnings,
            pushErrors = errors
        )
    }

    /**
     * Dispatches [operation] by type.
     *
     * @param eventsCache events loaded before the loop; a miss reads the database.
     * @param calendarsCache calendars loaded before the loop; a miss reads the database.
     */
    private suspend fun processOperation(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        calendarsCache: Map<Long, Calendar> = emptyMap(),
        clientToUse: CalDavClient,
        sentReplies: MutableMap<String, SentReply>
    ): SinglePushResult {
        return when (operation.operation) {
            PendingOperation.OPERATION_CREATE -> processCreate(operation, eventsCache, calendarsCache, clientToUse)
            PendingOperation.OPERATION_UPDATE -> processUpdate(operation, eventsCache, clientToUse, sentReplies)
            PendingOperation.OPERATION_DELETE -> processDelete(operation, eventsCache, clientToUse)
            PendingOperation.OPERATION_MOVE -> processMove(operation, eventsCache, calendarsCache, clientToUse)
            else -> SinglePushResult.Error(-1, "Unknown operation: ${operation.operation}", false)
        }
    }

    /** Creates the event on the server. */
    private suspend fun processCreate(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        calendarsCache: Map<Long, Calendar> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)
            ?: return SinglePushResult.Error(-1, "Event not found", false)

        // An exception rides in its master's body (IcsPatcher.serializeWithExceptions), so
        // its own op is a no-op.
        if (event.originalEventId != null) {
            Log.d(TAG, "Skipping exception event ${event.id} - bundled with master ${event.originalEventId}")
            return SinglePushResult.Success()
        }

        val calendar = calendarsCache[event.calendarId]
            ?: calendarRepository.getCalendarById(event.calendarId)
            ?: return SinglePushResult.Error(-1, "Calendar not found", false)

        // Captures the exception set as of now; only those are marked synced after.
        val (icalData, serializedExceptions) = serializeEventWithExceptions(event)

        Log.d(TAG, "Creating event on server: ${event.title} (${event.uid})")

        val result = clientToUse.createEvent(calendar.caldavUrl, event.uid, icalData)

        return when {
            result.isSuccess() -> {
                val (url, etag) = result.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Unexpected null result from create", false)

                eventsDao.markCreatedOnServerWithCopy(event.id, url, etag, icalData, System.currentTimeMillis())

                markBundledExceptionsSynced(serializedExceptions, url, etag, icalData)

                // Capture the server's scheduling decision (RFC 6638 §3.2.1).
                readBackScheduleStatus(event, url, clientToUse, serializedExceptions)
                drainPendingCancels(event, clientToUse)

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

    /** Updates the event on the server, or sends a PARTSTAT-only RSVP. */
    private suspend fun processUpdate(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        clientToUse: CalDavClient,
        sentReplies: MutableMap<String, SentReply>
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)
            ?: return SinglePushResult.Error(-1, "Event not found", false)

        // Routed before the exception skip and the caldavUrl check below: a reply to a changed
        // occurrence is its own write (the series push doesn't carry it), and an RSVP must
        // drain from operation.targetUrl even when Event.caldavUrl was cleared after queueing.
        if (operation.partstatOnly && operation.partstatTarget != null) {
            return processPartstatOnlyUpdate(operation, event, eventsCache, clientToUse, sentReplies)
        }

        // An exception rides in its master's body (IcsPatcher.serializeWithExceptions), so
        // its own op is a no-op.
        if (event.originalEventId != null) {
            Log.d(TAG, "Skipping exception event ${event.id} - bundled with master ${event.originalEventId}")
            return SinglePushResult.Success()
        }

        if (event.caldavUrl == null) {
            // Never created on the server.
            Log.w(TAG, "Event has no caldavUrl, treating as CREATE")
            return processCreate(operation, eventsCache, emptyMap(), clientToUse)
        }

        val caldavUrl = event.caldavUrl

        // Recover a null or empty etag via PROPFIND (a server may omit <getetag> on pull).
        val effectiveEtag: String
        if (!event.etag.isNullOrEmpty()) {
            effectiveEtag = event.etag
        } else {
            Log.w(TAG, "Event has no etag, fetching via PROPFIND for ${filenameOf(caldavUrl)}")
            val fetchResult = clientToUse.fetchEtag(caldavUrl)
            when (fetchResult) {
                is CalDavResult.Success -> {
                    val fetched = fetchResult.data
                    if (fetched != null) {
                        // The in-memory event keeps etag=null; the PUT uses effectiveEtag.
                        eventsDao.updateEtag(event.id, fetched)
                        Log.d(TAG, "Recovered etag via PROPFIND: ${fetched.take(8)}...")
                        effectiveEtag = fetched
                    } else {
                        // Success with no etag: not retryable.
                        Log.e(TAG, "PROPFIND returned null etag for event ${event.id}")
                        return SinglePushResult.Error(-1, "No etag for update", false)
                    }
                }
                is CalDavResult.Error -> {
                    // Keeps the error's retryability (network errors retry, auth errors don't).
                    Log.e(TAG, "PROPFIND failed for event ${event.id}: ${fetchResult.message}")
                    return SinglePushResult.Error(
                        fetchResult.code,
                        "PROPFIND fallback failed: ${fetchResult.message}",
                        fetchResult.isRetryable
                    )
                }
            }
        }

        // Captures the exception set as of now; only those are marked synced after.
        val (icalData, serializedExceptions) = serializeEventWithExceptions(event)

        Log.d(TAG, "Updating event on server: ${event.title} with etag='$effectiveEtag'")

        val result = clientToUse.updateEvent(caldavUrl, icalData, effectiveEtag)

        return when {
            result.isSuccess() -> {
                val newEtag = result.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Unexpected null result from update", false)

                eventsDao.markSyncedWithCopy(event.id, newEtag, icalData, System.currentTimeMillis())
                val storedUrl = adoptRedirectedUrl(event.id, caldavUrl, result)

                markBundledExceptionsSynced(serializedExceptions, storedUrl, newEtag, icalData)

                // Capture the server's scheduling decision (RFC 6638 §3.2.1).
                readBackScheduleStatus(event, storedUrl, clientToUse, serializedExceptions)
                drainPendingCancels(event, clientToUse)

                Log.d(TAG, "Event updated successfully")
                SinglePushResult.Success(newEtag = newEtag)
            }
            result.isConflict() -> retryOnServerCopy(event, caldavUrl, effectiveEtag, serializedExceptions, clientToUse)
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return SinglePushResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to update event: ${error.message}")
                SinglePushResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Retries an update the server refused with a 412, once, without undoing the change that
     * made the etag move unless the user changed the same field group: a guest's answer the
     * server wrote into the organizer's copy (Cyrus rewrites it when it processes a reply, #311),
     * another device's edit.
     *
     * The server's copy is fetched and the user's changes are applied on top of it
     * ([ServerChangeMerge]), then written with the etag of that same GET. After the write no row
     * stores the merged body or the new etag: the series keeps its old etag and body, and each
     * changed occurrence sent takes that old etag at the series' URL and keeps its body. Their
     * fields lack the server's side of the merge, and a stored merged body would make the next
     * merge read that side as the user undoing it. The old etag, with
     * [SinglePushResult.Success.refetch] lifting the pull's skip of a just-pushed event, makes
     * this cycle's pull, the last whose sync-collection listing names the resource, store the
     * merged copy. A server that serves a stale read right after a write leaves the rows on that
     * read until a later pull lists the resource.
     *
     * A resource gone from the server (GET 404 or 410) and a GET with no etag return
     * [SinglePushResult.Conflict] without writing, so this retry never re-creates the resource; a
     * second 412 on the merged write returns one too. Any other failure of the GET or the write is
     * a [SinglePushResult.Error] with its retryability (a 5xx GET counts as retryable), and a
     * server copy that doesn't parse or can't be merged is a permanent one: neither goes to
     * conflict resolution, where server wins would drop the edit.
     */
    private suspend fun retryOnServerCopy(
        event: Event,
        caldavUrl: String,
        storedEtag: String,
        serializedExceptions: List<Event>,
        clientToUse: CalDavClient
    ): SinglePushResult {
        Log.w(TAG, "412 for ${event.title}, applying the edit to the server's current copy")
        val fetched = clientToUse.fetchEvent(caldavUrl)
        val current = when (fetched) {
            is CalDavResult.Success -> fetched.data
            is CalDavResult.Error -> return if (fetched.code == 404 || fetched.code == 410) {
                Log.w(TAG, "Resource gone after a 412 for ${event.title}, leaving it to conflict resolution")
                SinglePushResult.Conflict()
            } else {
                // fetchEvent reports a 5xx as not retryable; for a write waiting on this read
                // it is transient, as it is for the write itself.
                val retryable = fetched.isRetryable || fetched.code in 500..599
                SinglePushResult.Error(fetched.code, "Refetch after a server change failed: ${fetched.message}", retryable)
            }
        }
        val currentEtag = current.etag?.takeIf { it.isNotEmpty() }
            ?: return SinglePushResult.Conflict()

        val account = calendarRepository.getCalendarById(event.calendarId)
            ?.let { accountRepository.getAccountById(it.accountId) }
        val merged = ServerChangeMerge.merge(
            baseline = event.rawIcal,
            series = event,
            seriesAttendees = attendeesDao.getForEventOnce(event.id),
            occurrences = serializedExceptions.map { it to attendeesDao.getForEventOnce(it.id) },
            server = current.icalData,
            account = account
        ) ?: return SinglePushResult.Error(-1, "Could not apply the edit to the server's changed copy", false)

        val retryResult = clientToUse.updateEvent(caldavUrl, merged.body, currentEtag)
        return when {
            retryResult.isSuccess() -> {
                eventsDao.markSynced(event.id, storedEtag, System.currentTimeMillis())
                val storedUrl = adoptRedirectedUrl(event.id, caldavUrl, retryResult)
                markBundledExceptionsSynced(merged.occurrences, storedUrl, storedEtag, null)
                // Capture the server's scheduling decision (RFC 6638 §3.2.1), from the merged
                // fields and SEQUENCE the server now holds.
                readBackScheduleStatus(merged.series, storedUrl, clientToUse, merged.occurrences)
                drainPendingCancels(event, clientToUse)
                Log.d(TAG, "Merged retry succeeded for ${event.title}")
                SinglePushResult.Success(
                    newEtag = retryResult.getOrNull(), refetch = true, sentOccurrenceIds = merged.occurrences.map { it.id }
                )
            }
            retryResult.isConflict() -> {
                Log.w(TAG, "Merged retry refused again for ${event.title}")
                SinglePushResult.Conflict()
            }
            else -> {
                val error = retryResult as CalDavResult.Error
                SinglePushResult.Error(error.code, "Merged retry failed: ${error.message}", error.isRetryable)
            }
        }
    }

    /**
     * A reply this push already wrote: the body sent and the etag the server
     * returned for it, so a later reply to the same resource in the same push
     * builds on it instead of on the copy stored before the push.
     *
     * [rowsBehind] is set when the body carries a change made elsewhere that the
     * rows haven't taken in (a reply retried on the server's copy, or a reply
     * built on one): a reply built on it leaves the rows' etag and body as they
     * are, and the pull refreshes them ([SinglePushResult.Success.refetch]).
     */
    private class SentReply(val body: String, val etag: String, val rowsBehind: Boolean = false)

    /**
     * Sends a PARTSTAT-only RSVP.
     *
     * The body is the stored server copy of the resource with only the current user's PARTSTAT
     * changed on one VEVENT ([IcsPatcher.patchAttendeeReply]): the series for a reply on the
     * series row, the changed occurrence for a reply on an occurrence row. Every other VEVENT,
     * ATTENDEE, ORGANIZER, SUMMARY and SEQUENCE goes back as the server holds it; a PUT replaces
     * the whole resource, so a reply that left a changed occurrence out would delete it.
     * SEQUENCE is never bumped (RFC 5546 §2.1.4: an attendee's PARTSTAT-only PUT must not bump
     * it). Some servers (iCloud) bump it on the wire; the next pull accepts that, and the client
     * never sends a higher SEQUENCE itself.
     *
     * Changed occurrences share their series' resource (one UID, RFC 5545
     * §3.8.4.4), so an occurrence reply is patched into the series row's
     * stored copy and sent to that resource. When the series row is a
     * placeholder pull made for a file holding only changed occurrences, the
     * occurrence row's own stored copy (the same resource) is used.
     *
     * Which copy a reply starts from: a reply already written to this resource in this push
     * ([sentReplies]) wins; otherwise the row as loaded at the start of the push, whose old etag
     * makes a reply after a full update earlier in the push meet a 412 and start from the server's
     * copy. A series row no op in this push touched isn't in [eventsCache] and is read from the
     * database, where no write of this push can have changed it.
     *
     * After a first-attempt success, every row of the resource stores the
     * returned etag with the body just sent, so a later reply starts from the
     * answers this app last sent; a reply built on a retried one is the exception
     * (step 1 below).
     *
     * On a 412:
     * 1. Fetch a fresh etag and body, re-patch the fresh body, and retry once. No row keeps the
     *    new etag: the fresh body may carry an organizer edit the rows haven't taken in, and
     *    their old etag makes this cycle's pull fetch it ([SinglePushResult.Success.refetch])
     *    and any later write meet a 412 first. A later reply in the same push built on the
     *    retry leaves the rows as they are too ([SentReply.rowsBehind]).
     * 2. If the retry fails, or its setup does, return [SinglePushResult.RsvpModified]: the op
     *    is marked failed with a warning asking the user to respond again, instead of
     *    retrying forever.
     */
    private suspend fun processPartstatOnlyUpdate(
        operation: PendingOperation,
        event: Event,
        eventsCache: Map<Long, Event>,
        clientToUse: CalDavClient,
        sentReplies: MutableMap<String, SentReply>
    ): SinglePushResult {
        val partstatTarget = operation.partstatTarget
            ?: return SinglePushResult.Error(-1, "partstat_only without partstat_target", false)

        val occurrence = if (event.originalEventId != null) {
            event.originalInstanceTime
                ?: return SinglePushResult.Error(-1, "RSVP on a changed occurrence with no instance time", false)
        } else {
            null
        }
        val seriesId = event.originalEventId ?: event.id
        val series = event.originalEventId
            ?.let { eventsCache[it] ?: eventsDao.getById(it) }
            ?.takeUnless { it.isPullSyntheticMaster }
        // The row whose stored copy is the resource being answered.
        val resourceRow = series ?: event

        // Prefer the URL captured at queue time, so the PUT survives a path that cleared
        // Event.caldavUrl after queueing. Falls back to the row's URL when the op captured none
        // (the row had no URL at queue time, or an older app version queued it).
        val caldavUrl = operation.targetUrl ?: resourceRow.caldavUrl ?: event.caldavUrl
            ?: return SinglePushResult.Error(-1, "PARTSTAT-only on event with no caldavUrl", false)

        val calendar = calendarRepository.getCalendarById(event.calendarId)
            ?: return SinglePushResult.Error(-1, "Calendar not found for RSVP push", false)
        val account = accountRepository.getAccountById(calendar.accountId)
            ?: return SinglePushResult.Error(-1, "Account not found for RSVP push", false)

        val sent = sentReplies[caldavUrl]
        val firstBody = IcsPatcher.patchAttendeeReply(
            sent?.body ?: resourceRow.rawIcal, account, partstatTarget, occurrence
        ) ?: return SinglePushResult.Error(
            -1,
            "Could not patch RSVP body (rawIcal missing, occurrence absent, or self attendee absent)",
            false
        )

        // Recover a missing etag, as processUpdate does.
        val storedEtag = sent?.etag ?: resourceRow.etag
        val effectiveEtag = if (!storedEtag.isNullOrEmpty()) {
            storedEtag
        } else {
            val fetched = clientToUse.fetchEtag(caldavUrl).getOrNull()
            if (fetched != null) {
                eventsDao.updateEtag(resourceRow.id, fetched)
                fetched
            } else {
                Log.w(TAG, "RSVP push: missing etag and PROPFIND fallback failed")
                return SinglePushResult.Error(-1, "No etag for RSVP update", true)
            }
        }

        Log.d(TAG, "RSVP PUT: ${event.title} (PARTSTAT=$partstatTarget)")
        val firstResult = clientToUse.updateEvent(caldavUrl, firstBody, effectiveEtag)

        return when {
            firstResult.isSuccess() && sent?.rowsBehind == true -> {
                val newEtag = firstResult.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Null etag from RSVP PUT", false)
                // Built on a retried reply: the body carries an edit the rows lack, so they keep
                // the etag and body they had, as after that retry.
                val storedUrl = keepRowsBehind(event, seriesId, caldavUrl, firstResult)
                rememberReply(sentReplies, caldavUrl, storedUrl, SentReply(firstBody, newEtag, rowsBehind = true))
                SinglePushResult.Success(newEtag = newEtag, refetch = true)
            }
            firstResult.isSuccess() -> {
                val newEtag = firstResult.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Null etag from RSVP PUT", false)
                eventsDao.markSynced(event.id, newEtag, System.currentTimeMillis())
                val storedUrl = redirectedUrl(firstResult) ?: caldavUrl
                // One statement: URL, etag and body move together, so a crash between writes
                // can't leave a row pairing one with a stale other.
                eventsDao.updateResourceCopy(seriesId, caldavUrl, storedUrl, newEtag, firstBody)
                rememberReply(sentReplies, caldavUrl, storedUrl, SentReply(firstBody, newEtag))
                SinglePushResult.Success(newEtag = newEtag)
            }
            firstResult.isConflict() -> {
                // Refresh the body and re-patch it: an organizer edit may have added or removed
                // attendees that must be kept.
                Log.w(TAG, "RSVP 412 for ${event.title}, refreshing body for retry")
                val freshEtag = clientToUse.fetchEtag(caldavUrl).getOrNull()
                val freshFetch = clientToUse.fetchEvent(caldavUrl)
                val freshIcal = (freshFetch as? CalDavResult.Success)?.data?.icalData
                if (freshEtag == null || freshIcal == null) {
                    Log.w(TAG, "RSVP retry setup failed for ${event.title}")
                    return SinglePushResult.RsvpModified(event.title)
                }
                val retryBody = IcsPatcher.patchAttendeeReply(freshIcal, account, partstatTarget, occurrence)
                if (retryBody == null) {
                    Log.w(TAG, "RSVP retry patch failed for ${event.title}")
                    return SinglePushResult.RsvpModified(event.title)
                }
                val retryResult = clientToUse.updateEvent(caldavUrl, retryBody, freshEtag)
                when {
                    retryResult.isSuccess() -> {
                        val newEtag = retryResult.getOrNull()
                            ?: return SinglePushResult.Error(-1, "Null etag from RSVP retry", false)
                        val storedUrl = keepRowsBehind(event, seriesId, caldavUrl, retryResult)
                        rememberReply(sentReplies, caldavUrl, storedUrl, SentReply(retryBody, newEtag, rowsBehind = true))
                        Log.d(TAG, "RSVP 412 retry succeeded for ${event.title}")
                        SinglePushResult.Success(newEtag = newEtag, refetch = true)
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
     * Marks the replied row synced after a reply whose body carries an organizer edit no row
     * has taken in, leaving every row's etag and body as they are: pull keys a resource's etag
     * on any one of its rows, so a fresh etag would hide the edit from the pull and let a later
     * write send the stale body without a 412. Only a redirect moves the rows. Returns the URL
     * the rows are at after the write.
     */
    private suspend fun keepRowsBehind(event: Event, seriesId: Long, caldavUrl: String, result: CalDavResult<*>): String {
        eventsDao.markSynced(event.id, eventsDao.getById(event.id)?.etag, System.currentTimeMillis())
        val storedUrl = redirectedUrl(result) ?: caldavUrl
        if (storedUrl != caldavUrl) eventsDao.updateResourceUrl(seriesId, caldavUrl, storedUrl)
        return storedUrl
    }

    private fun rememberReply(
        sentReplies: MutableMap<String, SentReply>,
        requestedUrl: String,
        storedUrl: String,
        reply: SentReply
    ) {
        sentReplies[requestedUrl] = reply
        sentReplies[storedUrl] = reply
    }

    /**
     * Returns where the server redirected a successful write, or null when it wasn't redirected.
     */
    private fun redirectedUrl(result: CalDavResult<*>): String? =
        (result as? CalDavResult.Success<*>)?.finalUrl

    /**
     * Returns the event's URL after a successful write: where the server redirected it, stored
     * so later edits go straight there, or [current] when it wasn't redirected.
     */
    private suspend fun adoptRedirectedUrl(eventId: Long, current: String, result: CalDavResult<*>): String {
        val moved = redirectedUrl(result) ?: return current
        Log.i(TAG, "Server redirected the write for event $eventId; storing the new URL")
        eventsDao.updateCaldavUrl(eventId, moved)
        return moved
    }

    /**
     * Marks every exception bundled into the body just pushed as synced at the master's URL and
     * etag.
     *
     * An exception lives in its master's resource (RFC 5545 §3.8.4.4 RECURRENCE-ID), so both
     * share one URL and etag. Writing only the etag leaves the row on its old URL (null on a
     * first create, the source account's after a move); a later pull finds that resource absent
     * and reaps the row, so the series survives while the edited occurrence vanishes (#365).
     *
     * Only [serializedExceptions] are touched: an exception created while the request was in
     * flight wasn't in the body, so it keeps its pending state.
     *
     * [sentBody] is the body the write sent, stored with [etag] on each row as pull stores the
     * whole resource on every row of it ([EventsDao.markCreatedOnServerWithCopy]); null leaves
     * each row's stored copy as it was.
     *
     * One statement per row, not etag then URL: a crash between two writes would leave the
     * stale-URL state this prevents.
     */
    private suspend fun markBundledExceptionsSynced(
        serializedExceptions: List<Event>,
        masterUrl: String,
        etag: String?,
        sentBody: String?
    ) {
        if (serializedExceptions.isEmpty()) return
        val now = System.currentTimeMillis()
        for (exception in serializedExceptions) {
            if (sentBody != null) {
                eventsDao.markCreatedOnServerWithCopy(exception.id, masterUrl, etag, sentBody, now)
            } else {
                eventsDao.markCreatedOnServer(exception.id, masterUrl, etag, now)
            }
        }
        Log.d(TAG, "Synced ${serializedExceptions.size} bundled exceptions to the master's resource")
    }

    /**
     * Returns whether a DELETE still owns [event]'s local row.
     *
     * A queued DELETE owns a resource in one calendar collection (RFC 4791 §5.3.2 scopes
     * calendar-object identity to the collection), not an event row. A move out of that
     * collection reuses the row for the destination copy, so by drain time the row can be live
     * data in its new home; reaping it would destroy the moved event while reporting success
     * (#365). The DELETE owns the row when any of these hold:
     * - the row is a tombstone (a user delete soft-deletes to PENDING_DELETE first, and nothing
     *   else ever reaps such a row; the pull skips them),
     * - the op carries no source scope, so there is no move to protect,
     * - the row is still in the collection the DELETE came from.
     *
     * Both the tombstone and the scope clause are needed: queueing updates an existing pending
     * op in place, so a real delete can inherit a prior move's source scope, and scope alone
     * would leave an invisible row behind forever.
     */
    private fun deleteOwnsLocalRow(operation: PendingOperation, event: Event): Boolean =
        event.syncStatus == SyncStatus.PENDING_DELETE ||
            operation.sourceCalendarId == null ||
            event.calendarId == operation.sourceCalendarId

    /**
     * Deletes the local row if this DELETE still owns it ([deleteOwnsLocalRow]); otherwise
     * returns the spared row's id so the caller can shield it from the pull.
     *
     * Ownership is checked against a fresh read, never the push loop's snapshot: the user can
     * move the event while the server DELETE is in flight, and the snapshot would still show it
     * in the source calendar and destroy the moved row.
     */
    private suspend fun reapLocalRowIfOwned(
        operation: PendingOperation,
        event: Event?
    ): SinglePushResult.Success {
        if (event == null) return SinglePushResult.Success()
        val current = eventsDao.getById(event.id) ?: return SinglePushResult.Success()
        if (deleteOwnsLocalRow(operation, current)) {
            eventsDao.deleteById(current.id)
            return SinglePushResult.Success()
        }
        Log.d(
            TAG,
            "Server copy deleted but keeping local row ${current.id}: it moved to " +
                "calendar ${current.calendarId} (delete was scoped to ${operation.sourceCalendarId})"
        )
        return SinglePushResult.Success(sparedEventId = current.id)
    }

    /**
     * Deletes the event from the server, at operation.targetUrl when set (a move has already
     * cleared event.caldavUrl), else at event.caldavUrl.
     */
    private suspend fun processDelete(
        operation: PendingOperation,
        eventsCache: Map<Long, Event> = emptyMap(),
        clientToUse: CalDavClient
    ): SinglePushResult {
        val event = eventsCache[operation.eventId]
            ?: eventsDao.getById(operation.eventId)

        val caldavUrl = operation.targetUrl ?: event?.caldavUrl

        if (event == null && caldavUrl == null) {
            Log.d(TAG, "Event already deleted locally and no targetUrl")
            return SinglePushResult.Success()
        }

        if (caldavUrl == null) {
            // Never on the server: delete locally only.
            Log.d(TAG, "Event was never on server, deleting locally")
            return reapLocalRowIfOwned(operation, event)
        }

        val etag = event?.etag.orEmpty()
        Log.d(TAG, "Deleting event from server: ${event?.title ?: "unknown"} with etag='$etag'")

        val result = clientToUse.deleteEvent(caldavUrl, etag)

        return when {
            result.isSuccess() -> {
                Log.d(TAG, "Event deleted successfully")
                reapLocalRowIfOwned(operation, event)
            }
            result.isConflict() -> {
                // 412: the resource exists but the If-Match etag no longer matches. For a
                // scheduling object this is often server-side drift: the server processed an
                // attendee reply and rewrote the organizer's copy (RFC 6638 §3.2.10). The user
                // asked to delete the resource, so refetch the etag and retry the delete once.
                Log.w(TAG, "412 on delete for ${event?.title ?: "unknown"}, fetching fresh etag for retry")
                val freshEtagResult = clientToUse.fetchEtag(caldavUrl)
                when {
                    // Removed elsewhere between the delete and the refetch: done.
                    freshEtagResult.isNotFound() -> {
                        Log.d(TAG, "Refetch shows event already gone, treating delete as done")
                        reapLocalRowIfOwned(operation, event)
                    }
                    else -> {
                        val freshEtag = freshEtagResult.getOrNull()
                        if (freshEtag.isNullOrEmpty()) {
                            // No etag to retry with: reschedule as a conflict.
                            Log.w(TAG, "fetchEtag returned no etag for delete of ${event?.title}, deferring")
                            SinglePushResult.Conflict()
                        } else {
                            val retryResult = clientToUse.deleteEvent(caldavUrl, freshEtag)
                            when {
                                retryResult.isSuccess() || retryResult.isNotFound() -> {
                                    Log.d(TAG, "412 delete retry succeeded for ${event?.title}")
                                    reapLocalRowIfOwned(operation, event)
                                }
                                retryResult.isConflict() -> {
                                    // Drifted again: reschedule as a conflict, don't loop.
                                    Log.w(TAG, "412 delete retry re-conflicted for ${event?.title}")
                                    SinglePushResult.Conflict()
                                }
                                else -> {
                                    // Any other failure (network, auth, 5xx, 403) is an Error
                                    // so the caller honors isRetryable: a permanent error is
                                    // marked failed at once instead of rescheduled as a
                                    // conflict for the op's 30-day lifetime.
                                    val error = (retryResult as? CalDavResult.Error)
                                        ?: return SinglePushResult.Error(-1, "Unexpected result type from delete retry", false)
                                    Log.e(TAG, "412 delete retry failed for ${event?.title}: ${error.message}")
                                    SinglePushResult.Error(error.code, error.message, error.isRetryable)
                                }
                            }
                        }
                    }
                }
            }
            result.isNotFound() -> {
                Log.d(TAG, "Event already deleted on server")
                reapLocalRowIfOwned(operation, event)
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
     * Moves the event between two calendars of the same account.
     *
     * - Phase 0: WebDAV MOVE, which is atomic and avoids UID conflicts on iCloud. On success
     *   the op becomes an UPDATE that PUTs the current body. A 403, 405 or 412 (server declined
     *   the MOVE), a 404 (source gone) or a missing source URL advances to phase 1 without
     *   deleting anything.
     * - Phase 1: CREATE in the target, then DELETE from the source, so the event exists
     *   somewhere at every step. It starts with a fresh retry budget.
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

        if (operation.movePhase == PendingOperation.MOVE_PHASE_DELETE) {
            val sourceUrl = operation.targetUrl
            if (sourceUrl == null) {
                Log.d(TAG, "MOVE Phase 0: No source URL, advancing to CREATE")
                pendingOperationsDao.advanceToCreatePhase(operation.id, System.currentTimeMillis())
                return SinglePushResult.PhaseAdvanced
            }

            Log.d(TAG, "MOVE Phase 0: Trying WebDAV MOVE from $sourceUrl to ${calendar.caldavUrl}")
            val moveResult = clientToUse.moveEvent(sourceUrl, calendar.caldavUrl, event.uid)

            when {
                moveResult.isSuccess() -> {
                    // MOVE carries no body (RFC 4918 §9.9, copy then delete), so the
                    // destination holds the old body. An edit made in the same save (title or
                    // note changed while moving) lives only in the local row and would be
                    // silently lost without a PUT of the current body to the new URL.
                    //
                    // processUpdate owns that PUT (etag recovery, 412 retry, scheduling
                    // read-back, cancel draining). Two invariants keep a failing PUT safe:
                    //   1. The row stays PENDING_UPDATE until the body lands, so a pull in
                    //      between treats it as dirty and won't overwrite the edit.
                    //   2. The op becomes a plain UPDATE (no MOVE fields), so a retry re-PUTs
                    //      to the new URL and never re-runs the MOVE, whose source is gone
                    //      (404, then CREATE, then an account-wide UID clash).
                    // retryCount is not reset: the caller decides retries from its in-memory
                    // operation, so the PUT shares the MOVE's budget, and a database-only
                    // reset would have no effect.
                    val (newUrl, movedEtag) = moveResult.getOrNull()
                        ?: return SinglePushResult.Error(-1, "Null result from MOVE", false)

                    val now = System.currentTimeMillis()
                    eventsDao.updateCaldavUrl(event.id, newUrl)
                    eventsDao.updateEtag(event.id, movedEtag)
                    eventsDao.updateSyncStatus(event.id, SyncStatus.PENDING_UPDATE, now)

                    val updateOp = operation.copy(
                        operation = PendingOperation.OPERATION_UPDATE,
                        targetUrl = null,
                        targetCalendarId = null
                    )
                    pendingOperationsDao.update(updateOp)

                    Log.d(TAG, "MOVE succeeded: relocated to $newUrl; pushing current body via UPDATE path")
                    // A fresh cache, so processUpdate sees the row just written (new URL,
                    // PENDING_UPDATE), not the loop's snapshot with caldavUrl=null.
                    val updated = eventsDao.getById(event.id)
                    val refreshedCache = if (updated != null) mapOf(updated.id to updated) else emptyMap()
                    return processUpdate(updateOp, refreshedCache, clientToUse, mutableMapOf())
                }

                moveResult.isNotFound() -> {
                    // Source already gone: no DELETE needed.
                    Log.d(TAG, "MOVE Phase 0: Source not found (404), advancing to CREATE")
                    pendingOperationsDao.advanceToCreatePhase(operation.id, System.currentTimeMillis())
                    return SinglePushResult.PhaseAdvanced
                }

                else -> {
                    val error = moveResult as? CalDavResult.Error
                    val code = error?.code ?: -1

                    // The server declined the MOVE: 403 (e.g. Nextcloud/Sabre builds that
                    // reject MOVE), 405 (MOVE unsupported) or 412. Phase 1 re-serializes the
                    // current body, so edits survive. Servers that accept MOVE (iCloud, some
                    // Nextcloud builds) take the success branch above.
                    if (code == 403 || code == 405 || code == 412) {
                        Log.w(TAG, "MOVE failed ($code), falling back to CREATE+DELETE")
                        pendingOperationsDao.advanceToCreatePhase(operation.id, System.currentTimeMillis())
                        return SinglePushResult.PhaseAdvanced
                    }

                    // Any other error retries the MOVE.
                    Log.w(TAG, "MOVE Phase 0 failed: ${error?.message}")
                    return SinglePushResult.Error(
                        code,
                        "MOVE failed: ${error?.message}",
                        error?.isRetryable ?: true
                    )
                }
            }
        }

        // Phase 1: the event must exist in the target before the source is deleted.
        Log.d(TAG, "MOVE Phase 1: Creating in new calendar: ${calendar.displayName}")

        val (icalData, serializedExceptions) = serializeEventWithExceptions(event)
        val createResult = clientToUse.createEvent(calendar.caldavUrl, event.uid, icalData)

        return when {
            createResult.isSuccess() -> {
                val (url, etag) = createResult.getOrNull()
                    ?: return SinglePushResult.Error(-1, "Null result from create", false)

                eventsDao.markCreatedOnServerWithCopy(event.id, url, etag, icalData, System.currentTimeMillis())
                // The exceptions in this body live in the new resource too.
                markBundledExceptionsSynced(serializedExceptions, url, etag, icalData)
                Log.d(TAG, "MOVE Phase 1: Event created successfully at $url")

                // No SCHEDULE-STATUS read-back: a move is a relocation, not a re-invite. The
                // next normal pull picks up any receipt the target server stamps.

                var moveOrphanWarning: String? = null
                val sourceUrl = operation.targetUrl
                if (sourceUrl != null) {
                    Log.d(TAG, "MOVE Phase 1: Deleting from source: $sourceUrl")
                    // Delete the source only as it is now: ask for its current ETag and
                    // make the DELETE conditional on it (an empty If-Match never matches,
                    // RFC 9110 section 13.1.1). Unconditional only when the source isn't
                    // found (already gone, or only reachable by DELETE); with no usable
                    // ETag the source is left alone rather than deleted blind.
                    val sourceEtag = clientToUse.fetchEtag(sourceUrl)
                    val deleteResult = when {
                        sourceEtag is CalDavResult.Success && !sourceEtag.data.isNullOrEmpty() ->
                            clientToUse.deleteEvent(sourceUrl, sourceEtag.data)
                        sourceEtag.isNotFound() -> clientToUse.deleteEvent(sourceUrl, null)
                        sourceEtag is CalDavResult.Error -> sourceEtag
                        else -> CalDavResult.error(-1, "source has no ETag")
                    }
                    when {
                        deleteResult.isSuccess() || deleteResult.isNotFound() -> {
                            Log.d(TAG, "MOVE complete: CREATE+DELETE succeeded")
                        }
                        else -> {
                            // The event is safe in the target; the op still succeeds, with a
                            // warning that a copy may remain in the source.
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
     * Reads back the server's scheduling decision after a successful PUT of an organizer event
     * with attendees (RFC 6638 §3.2.1), then sends through the outbox where the server won't.
     *
     * A scheduling-aware server delivers the invitation on the implicit PUT and stamps the
     * outcome onto the stored resource as `SCHEDULE-STATUS` / `SCHEDULE-AGENT` parameters
     * (§3.2.9, §7.1, §7.3). The client never sends those, so re-fetching is the only way to
     * learn the decision, and [maybeSendViaOutbox] routes on it. Receipts are captured for the
     * master and for each bundled exception.
     *
     * Never fails the push: any failure (no URL, fetch error, parse error) leaves the columns
     * for the next normal pull. Issues no request unless the master or a bundled exception has
     * attendees and the account is the organizer.
     */
    private suspend fun readBackScheduleStatus(
        event: Event,
        serverUrl: String?,
        client: CalDavClient,
        serializedExceptions: List<Event> = emptyList()
    ) {
        try {
            if (serverUrl == null) return

            // Exceptions ride the master's PUT, but their own attendees (a guest added to one
            // occurrence) live on their own rows and need the same read-back and outbox send.
            // Use the set the caller serialized, not a re-query, so delivery matches what
            // went on the wire.
            val exceptions = serializedExceptions

            // A per-occurrence add can leave the master with no attendees while an exception
            // has the new guest, so checking the master alone would skip delivery. The
            // receipts themselves come from the re-fetch below, not these rows.
            val masterHasAttendees = attendeesDao.getForEventOnce(event.id).isNotEmpty()
            val anyExceptionHasAttendees = exceptions.any { attendeesDao.getForEventOnce(it.id).isNotEmpty() }
            if (!masterHasAttendees && !anyExceptionHasAttendees) return

            val calendar = calendarRepository.getCalendarById(event.calendarId) ?: return
            val account = accountRepository.getAccountById(calendar.accountId) ?: return
            if (!account.canEditAsOrganizer(event)) return

            // Re-fetch at the URL written: the client-built {calendar}/{uid}.ics, or where a
            // redirect sent the write. No Location header is read, so a server that stores the
            // resource at another path (observed only on a non-delivering server) 404s here
            // and nothing is captured this cycle; the next normal pull reconciles.
            val fetched = (client.fetchEvent(serverUrl) as? CalDavResult.Success)?.data ?: return
            val parsedEvents = icalParser.parseAllEvents(fetched.icalData).getOrNull().orEmpty()
            // The master carries the series-level ATTENDEE and ORGANIZER receipts.
            val master = parsedEvents.firstOrNull { it.recurrenceId == null } ?: return

            // The re-fetched ATTENDEE set is the server's; replaceForEvent keeps any earlier
            // receipt the server didn't restate (a stamp still in progress), per RFC 6638 §7.3.
            //
            // An empty set doesn't mean "no attendees": the event has attendees (checked
            // above), so the server returned a minimal body without the ATTENDEE block.
            // Replacing with it would wipe the rows and their receipts; the next normal pull
            // reconciles instead. serializeEventWithExceptions treats an empty set the same
            // way.
            val attendeeRows = ICalEventMapper.toAttendeeRows(master, eventId = event.id)
            if (attendeeRows.isNotEmpty()) {
                attendeesDao.replaceForEvent(event.id, attendeeRows)
            }

            // ORGANIZER-line SCHEDULE-STATUS (§7.3): the reply-delivery receipt.
            val organizerStatus = master.organizer?.scheduleStatus?.firstOrNull()?.code
            if (organizerStatus != null) {
                eventsDao.updateOrganizerScheduleStatus(event.id, organizerStatus)
            }

            // RFC 6638 §6: POST through the outbox to any attendee the server declined to
            // deliver to (SCHEDULE-AGENT=CLIENT). This runs after the re-fetch so it routes on
            // the server's fresh decision; when the re-fetch failed (early return above),
            // nothing is sent this cycle and the marker isn't advanced, so the next push
            // retries and no invite is lost or duplicated. maybeSendViaOutbox catches its own
            // failures so a send failure isn't logged as a read-back failure.
            maybeSendViaOutbox(event, account, client)

            // Each bundled exception VEVENT has its own attendees. Match it to its row by
            // RECURRENCE-ID == originalInstanceTime, store its receipts on that row, and run
            // the outbox send on the exception's own sequence and organizer, so an
            // exception-only guest is reached on servers that don't schedule a per-occurrence
            // attendee themselves. Each exception is isolated so one failure can't starve the
            // rest.
            //
            // originalInstanceTime is stored normalized to the master's DTSTART value type (a
            // DATE RECURRENCE-ID on a timed master takes the master's time of day;
            // ICalEventMapper.normalizeRecurrenceId, RFC 5545 §3.8.4.4). The re-fetched VEVENT
            // can carry the raw form, so the parsed side is normalized the same way before
            // matching, or the occurrence's receipts and send would be skipped. With matching
            // value types normalizeRecurrenceId returns its input unchanged.
            val masterDtStart = EventToICalEventMapper.dtStartOf(event)
            for (exception in exceptions) {
                try {
                    // Only a real occurrence time can match an exception VEVENT; a null
                    // RECURRENCE-ID is the master's.
                    val instanceTime = exception.originalInstanceTime ?: continue
                    val parsedException = parsedEvents.firstOrNull {
                        it.recurrenceId != null &&
                            ICalEventMapper.normalizeRecurrenceId(
                                it.recurrenceId, masterDtStart
                            )?.timestamp == instanceTime
                    } ?: continue

                    val exceptionRows = ICalEventMapper.toAttendeeRows(parsedException, eventId = exception.id)
                    if (exceptionRows.isNotEmpty()) {
                        attendeesDao.replaceForEvent(exception.id, exceptionRows)
                    }

                    val exceptionOrganizerStatus =
                        parsedException.organizer?.scheduleStatus?.firstOrNull()?.code
                    if (exceptionOrganizerStatus != null) {
                        eventsDao.updateOrganizerScheduleStatus(exception.id, exceptionOrganizerStatus)
                    }

                    maybeSendViaOutbox(exception, account, client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Exception read-back failed for ${exception.id}: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SCHEDULE-STATUS read-back failed for event ${event.id}: ${e.message}")
        }
    }

    /**
     * Posts a `METHOD:REQUEST` to the account's scheduling outbox for each attendee the server
     * won't deliver to (RFC 6638 §6). A server that stamps `SCHEDULE-AGENT=CLIENT` delivers
     * nothing on the implicit PUT.
     *
     * An attendee is sent to only when all hold:
     * - the account has a discovered scheduling-outbox URL;
     * - [routeDelivery] over [classifyDelivery] gives [DeliveryAction.ClientOutboxPost] (the
     *   shared rule, never re-derived here);
     * - its `itip_request_sequence` marker is below the event's SEQUENCE, or null (never sent,
     *   including a guest added later). This stops a re-push from sending duplicate invites
     *   (RFC 5546 §3.2.2.1/.2: a same-SEQUENCE re-REQUEST is an update, not a reschedule).
     *
     * One POST per recipient: RFC 6638 §5 requires one CALDAV:response per recipient, but Zoho,
     * the only server that reaches this path, returns one per POST whatever the recipient count.
     * A batched POST would leave the un-echoed recipients' markers unadvanced, and every push
     * would re-send to them (the server re-delivering the whole ATTENDEE body). Each recipient is
     * the single ATTENDEE in its body and the `Recipient` header ([CalDavClient.postToOutbox]).
     * The ORGANIZER and Originator are the account's own calendar-user-address (RFC 6638 §6:
     * the ORGANIZER must match the outbox owner, or the server rewrites or rejects it), never
     * built from the username; with several addresses, the one matching the event's stored
     * organizer wins, so a multi-alias account keeps one ORGANIZER across the PUT and the REQUEST.
     *
     * The outcome per recipient ([classifyRequestStatus], RFC 6638 §3.6): `2.x` success advances
     * the marker; a permanent `3.x` or `5.x` other than `5.1` also advances it, to stop the loop
     * (a later SEQUENCE bump or address fix recovers); a transient `5.1`, a missing or unknown
     * status, or a network failure leaves it so the next push retries.
     * The raw status is stored too; nothing reads it yet. Never fails the push, including when
     * building the REQUEST throws.
     */
    private suspend fun maybeSendViaOutbox(
        event: Event,
        account: Account,
        client: CalDavClient
    ) {
        try {
            val outboxUrl = account.scheduleOutboxUrl ?: return

            // The rows after the read-back merge: schedule_agent and schedule_status hold the
            // server's decision, itip_request_sequence the kept marker.
            val rows = attendeesDao.getForEventOnce(event.id)
            val toSend = rows.filter { row ->
                routeDelivery(
                    classifyDelivery(row.scheduleStatus, row.scheduleAgent),
                    // From the URL itself, so the gate holds if the early return above moves.
                    hasOutboxUrl = outboxUrl.isNotBlank(),
                ) == DeliveryAction.ClientOutboxPost &&
                    (row.itipRequestSequence == null || event.sequence > row.itipRequestSequence)
            }
            if (toSend.isEmpty()) return

            // The address matching the stored ORGANIZER, else the account's first (preferred).
            val addresses = account.effectiveAddresses().map { AddressNormalizer.stripMailto(it) }
            val organizerBare = event.organizerEmail?.let { AddressNormalizer.stripMailto(it) }
            val originator = addresses.firstOrNull {
                organizerBare != null &&
                    AddressNormalizer.canonical(it) == AddressNormalizer.canonical(organizerBare)
            } ?: addresses.firstOrNull() ?: return

            // The body ORGANIZER is forced to the originator (rule in the KDoc). A non-blank
            // organizer also stops the mapper dropping the ATTENDEE block on an event with
            // organizerEmail == null. The copy is local; the stored event is untouched.
            val organizerEvent = event.copy(organizerEmail = originator)

            for (row in toSend) {
                // A failure for one recipient (mapper, ITipBuilder, client) is logged and
                // skipped so it can't starve the others; its marker stays, so it retries next
                // push.
                try {
                    val recipient = AddressNormalizer.stripMailto(row.address)

                    // SEQUENCE is sent as stored; any bump happened on the PUT path.
                    val icalEvent = EventToICalEventMapper.toICalEvent(organizerEvent, listOf(row))
                    val ics = ITipBuilder.default.createRequest(icalEvent, icalEvent.attendees)

                    val result = client.postToOutbox(outboxUrl, originator, listOf(recipient), ics)
                    val response = (result as? CalDavResult.Success)?.data ?: run {
                        // Transport or HTTP failure: the marker stays and the next push
                        // retries (RFC 6638 §3.6 transient).
                        Log.w(TAG, "Outbox POST failed for event ${event.id} recipient: $result")
                        return@run null
                    } ?: continue

                    // One recipient was POSTed. Prefer the response whose recipient matches this
                    // row; otherwise (a non-mailto echo, or a bare single response) take the
                    // first status, or the marker would never advance and the invite would
                    // re-POST every cycle.
                    val rawStatus = response.recipients
                        .firstOrNull { AddressNormalizer.canonical(it.recipient) == AddressNormalizer.canonical(row.address) }
                        ?.requestStatus
                        ?: response.recipients.firstOrNull()?.requestStatus

                    when (classifyRequestStatus(rawStatus)) {
                        OutboxDeliveryClass.SUCCESS,
                        OutboxDeliveryClass.PERMANENT ->
                            // Advance the marker: SUCCESS is done; PERMANENT stops the loop
                            // until a SEQUENCE bump or address fix.
                            attendeesDao.markItipRequestSent(row.id, event.sequence, rawStatus)
                        OutboxDeliveryClass.TRANSIENT ->
                            // The marker stays, so the next push retries.
                            Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Outbox send to a recipient failed for event ${event.id}: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Outbox iTIP send failed for event ${event.id}: ${e.message}")
        }
    }

    /**
     * Delivers the iTIP CANCEL to guests removed from [event] (RFC 5546 §3.2.2.6), draining
     * its pending_cancels rows after a successful push.
     *
     * Called after the push succeeds, not from [readBackScheduleStatus], whose early returns (no
     * attendees left, read-back GET failed) would skip the drain exactly when the last guest
     * was removed. Each row is routed on the delivery context captured at removal:
     * - [DeliveryState.ServerOwnsDelivery]: the shrunk PUT already cancelled them server-side
     *   (RFC 6638 §3.2.1.2); delete the row.
     * - [DeliveryState.ClientMustDeliver] with a discovered outbox: POST a per-attendee
     *   METHOD:CANCEL (ITipBuilder.createCancel bumps SEQUENCE, RFC 5546 §2.1.4); delete on
     *   success, a permanent failure or an empty response, count an attempt on a transient one.
     * - [DeliveryState.ClientMustDeliver] with no outbox, or [DeliveryState.NoReceipt] (server
     *   stance not yet known): keep the row for a later cycle, up to [MAX_CANCEL_ATTEMPTS].
     *
     * Like [maybeSendViaOutbox], isolates each recipient and never fails the push.
     */
    private suspend fun drainPendingCancels(event: Event, client: CalDavClient) {
        try {
            val pending = pendingCancelsDao.getForEvent(event.id)
            if (pending.isEmpty()) return

            val calendar = calendarRepository.getCalendarById(event.calendarId) ?: return
            val account = accountRepository.getAccountById(calendar.accountId) ?: return
            if (!account.canEditAsOrganizer(event)) return
            val outboxUrl = account.scheduleOutboxUrl

            val addresses = account.effectiveAddresses().map { AddressNormalizer.stripMailto(it) }
            val organizerBare = event.organizerEmail?.let { AddressNormalizer.stripMailto(it) }
            val originator = addresses.firstOrNull {
                organizerBare != null &&
                    AddressNormalizer.canonical(it) == AddressNormalizer.canonical(organizerBare)
            } ?: addresses.firstOrNull()

            for (row in pending) {
                try {
                    val state = classifyDelivery(row.scheduleStatus, row.scheduleAgent)
                    val action = routeDelivery(state, hasOutboxUrl = !outboxUrl.isNullOrBlank())

                    when (action) {
                        // The shrunk PUT cancelled server-side: nothing to send.
                        DeliveryAction.ServerHandles -> pendingCancelsDao.deleteById(row.id)

                        DeliveryAction.ClientOutboxPost -> {
                            if (originator == null || outboxUrl.isNullOrBlank()) {
                                abandonOrRetry(row)
                                continue
                            }
                            val recipient = AddressNormalizer.stripMailto(row.address)
                            // A CANCEL for this one recipient, ORGANIZER forced to the account's
                            // own address (RFC 6638 §6); createCancel bumps SEQUENCE on the
                            // wire (RFC 5546 §2.1.4).
                            val cancelRow = row.toAttendee(eventId = event.id)
                            val cancelBase = event.copy(organizerEmail = originator, sequence = row.sequence)
                            val recurrenceId = row.recurrenceId
                            val icalEvent = if (recurrenceId != null) {
                                // Per-occurrence cancel: the exception overload gives a body with
                                // RECURRENCE-ID and no RRULE, so the guest is uninvited from that
                                // one occurrence, not the series. It derives RECURRENCE-ID and
                                // DTSTART from originalInstanceTime and startTs, so both are set
                                // to the occurrence's time, not the master's first start, keeping
                                // the event's duration.
                                val duration = event.endTs - event.startTs
                                EventToICalEventMapper.toICalEvent(
                                    masterUid = event.uid,
                                    exception = cancelBase.copy(
                                        originalInstanceTime = recurrenceId,
                                        startTs = recurrenceId,
                                        endTs = recurrenceId + duration,
                                        rrule = null,
                                    ),
                                    attendees = listOf(cancelRow),
                                )
                            } else {
                                EventToICalEventMapper.toICalEvent(cancelBase, listOf(cancelRow))
                            }
                            val ics = ITipBuilder.default.createCancel(icalEvent, icalEvent.attendees)
                            val result = client.postToOutbox(outboxUrl, originator, listOf(recipient), ics)
                            val response = (result as? CalDavResult.Success)?.data
                            if (response == null) {
                                // Transport failure: retry next cycle, up to the cap.
                                abandonOrRetry(row)
                                continue
                            }
                            if (response.recipients.isEmpty()) {
                                // HTTP 2xx with an empty schedule-response, observed live on
                                // Zoho, SOGo and Mailbox for a CANCEL even with a real
                                // recipient. The server took it, so the cancel is resolved;
                                // classified, an empty response is TRANSIENT and the CANCEL
                                // would re-POST every cycle up to the attempt cap.
                                pendingCancelsDao.deleteById(row.id)
                                continue
                            }
                            val rawStatus = response.recipients
                                .firstOrNull { AddressNormalizer.canonical(it.recipient) == AddressNormalizer.canonical(row.address) }
                                ?.requestStatus
                                ?: response.recipients.firstOrNull()?.requestStatus
                            when (classifyRequestStatus(rawStatus)) {
                                OutboxDeliveryClass.SUCCESS,
                                OutboxDeliveryClass.PERMANENT ->
                                    // Delivered, or never deliverable: resolved either way.
                                    pendingCancelsDao.deleteById(row.id)
                                OutboxDeliveryClass.TRANSIENT -> abandonOrRetry(row)
                            }
                        }

                        // No usable channel this cycle. A NoReceipt row (no decision stamped
                        // yet) or a declined row with no outbox may become deliverable once
                        // the server stamps a decision or a later sync discovers an outbox,
                        // so keep it up to MAX_CANCEL_ATTEMPTS instead of silently dropping
                        // the CANCEL on the first drain.
                        DeliveryAction.NoRemedy -> abandonOrRetry(row)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "CANCEL send failed for event ${event.id} recipient: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pending-cancel drain failed for event ${event.id}: ${e.message}")
        }
    }

    /**
     * Counts a failed CANCEL attempt on [row], or deletes the row once it reaches
     * [MAX_CANCEL_ATTEMPTS], so an undeliverable cancel doesn't loop forever.
     */
    private suspend fun abandonOrRetry(row: org.onekash.kashcal.data.db.entity.PendingCancel) {
        if (row.attemptCount + 1 >= MAX_CANCEL_ATTEMPTS) {
            pendingCancelsDao.deleteById(row.id)
        } else {
            pendingCancelsDao.incrementAttempt(row.id)
        }
    }

    /**
     * Serializes [event], with its exceptions when it's a recurring master, and returns the body
     * with the exceptions it contains.
     *
     * Only the returned exceptions may be marked synced ([markBundledExceptionsSynced]); an
     * exception created after this call wasn't pushed.
     */
    private suspend fun serializeEventWithExceptions(event: Event): Pair<String, List<Event>> {
        // Attendees come from the attendees table, not rawIcal: locally created events have
        // no rawIcal, and exception VEVENTs carry their own attendees the master's body lacks.
        //
        // An empty table doesn't mean "no attendees": an event synced before the table
        // existed, whose etag hasn't changed since (so the pull never backfilled it), keeps
        // its ATTENDEEs only in rawIcal. Passing empty would tell IcsPatcher to clear them,
        // silently uninviting everyone on a cosmetic edit, so empty becomes null (keep the
        // rawIcal block).
        fun authoritative(rows: List<org.onekash.kashcal.data.db.entity.Attendee>) =
            rows.ifEmpty { null }
        val masterAttendees = authoritative(attendeesDao.getForEventOnce(event.id))
        return if (event.rrule != null && event.originalEventId == null) {
            // Each exception carries its own attendees so per-occurrence guests round-trip.
            val exceptions = eventsDao.getExceptionsForMaster(event.id)
            val exceptionsWithAttendees = exceptions.map { exception ->
                exception to authoritative(attendeesDao.getForEventOnce(exception.id))
            }
            val icalData = IcsPatcher.serializeWithExceptions(
                master = event,
                masterAttendees = masterAttendees,
                exceptionsWithAttendees = exceptionsWithAttendees
            )
            icalData to exceptions
        } else {
            IcsPatcher.serialize(event, masterAttendees) to emptyList()
        }
    }

    /**
     * Marks an RSVP whose 412 retry failed as failed and adds the warning asking the user to
     * respond again. The caller counts the failure.
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

    /** Reschedules [operation] with backoff and records [error] on its event. */
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

        eventsDao.recordSyncError(operation.eventId, error, System.currentTimeMillis())
    }
}
