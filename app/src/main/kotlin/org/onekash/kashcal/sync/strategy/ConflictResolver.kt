package org.onekash.kashcal.sync.strategy

import android.util.Log
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import javax.inject.Inject

/**
 * Resolves a pending operation the server refused because its version differs (412).
 *
 * The strategies are described on [ConflictStrategy]. Production sync uses only the default,
 * server wins (`CalDavSyncEngine`). As a sync-layer component it uses the DAOs directly.
 */
class ConflictResolver @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val eventsDao: EventsDao,
    private val attendeesDao: AttendeesDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val database: KashCalDatabase
) {
    private val icalParser = ICalParser()

    companion object {
        private const val TAG = "ConflictResolver"
    }

    /**
     * Resolves the conflict behind [operation] with [strategy].
     *
     * @param expectedCalendarId when set, an event now in another calendar returns
     *   [ConflictResult.CalendarMismatch] untouched, so one calendar's sync can't resolve
     *   another's event.
     * @param client the caller's per-account client.
     */
    suspend fun resolve(
        operation: PendingOperation,
        expectedCalendarId: Long? = null,
        strategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        client: CalDavClient
    ): ConflictResult {
        val event = eventsDao.getById(operation.eventId)
            ?: return ConflictResult.EventNotFound

        if (expectedCalendarId != null && event.calendarId != expectedCalendarId) {
            Log.w(TAG, "Event ${event.id} moved to different calendar during conflict resolution")
            return ConflictResult.CalendarMismatch
        }

        val effectiveClient = client
        return when (strategy) {
            ConflictStrategy.SERVER_WINS -> resolveServerWins(event, operation, effectiveClient)
            ConflictStrategy.LOCAL_WINS -> resolveLocalWins(event, operation, effectiveClient)
            ConflictStrategy.NEWEST_WINS -> resolveNewestWins(event, operation, effectiveClient)
            ConflictStrategy.MANUAL -> resolveManual(event, operation)
        }
    }

    /** Resolves each of [operations] in order with [strategy], without a calendar check. */
    suspend fun resolveAll(
        operations: List<PendingOperation>,
        strategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        client: CalDavClient
    ): List<ConflictResult> {
        return operations.map { resolve(it, strategy = strategy, client = client) }
    }

    /**
     * Fetches the server version and overwrites the local event with it. A pending delete is
     * cancelled instead, keeping the local event; a 404 or a calendar deleted meanwhile deletes
     * the local event.
     */
    private suspend fun resolveServerWins(event: Event, operation: PendingOperation, client: CalDavClient): ConflictResult {
        Log.d(TAG, "Resolving conflict with SERVER_WINS for event: ${event.title}")

        // The server still has the event, so cancel the delete and keep the local copy.
        if (operation.operation == PendingOperation.OPERATION_DELETE) {
            eventsDao.updateSyncStatus(event.id, SyncStatus.SYNCED, System.currentTimeMillis())
            pendingOperationsDao.deleteById(operation.id)
            Log.d(TAG, "DELETE cancelled - server version preserved")
            return ConflictResult.ServerVersionKept
        }

        val caldavUrl = event.caldavUrl
        if (caldavUrl == null) {
            // No URL means the event was never on the server: no server version to take. The event
            // and its operation are left as they are.
            Log.w(TAG, "Event has no caldavUrl, cannot fetch server version")
            return ConflictResult.Error("Event has no server URL")
        }

        val fetchResult = client.fetchEvent(caldavUrl)
        if (fetchResult.isError()) {
            val error = fetchResult as CalDavResult.Error
            if (error.code == 404) {
                eventsDao.deleteById(event.id)
                pendingOperationsDao.deleteById(operation.id)
                Log.d(TAG, "Event deleted on server - removed locally")
                return ConflictResult.LocalDeleted
            }
            return ConflictResult.Error("Failed to fetch server version: ${error.message}")
        }

        val serverEvent = (fetchResult as CalDavResult.Success).data

        val parseResult = icalParser.parseAllEvents(serverEvent.icalData)
        val parsedEvents = when (parseResult) {
            is ParseResult.Success -> parseResult.value
            is ParseResult.Error -> return ConflictResult.Error("Failed to parse server event: ${parseResult.error.message}")
        }
        if (parsedEvents.isEmpty()) {
            val isNonEvent = !serverEvent.icalData.contains("BEGIN:VEVENT") &&
                (serverEvent.icalData.contains("BEGIN:VTODO") ||
                 serverEvent.icalData.contains("BEGIN:VJOURNAL") ||
                 serverEvent.icalData.contains("BEGIN:VFREEBUSY"))
            return if (isNonEvent) {
                Log.d(TAG, "Conflict resolution: server resource is non-event (VTODO/VJOURNAL/VFREEBUSY)")
                ConflictResult.Error("Server resource is non-event (VTODO/VJOURNAL)")
            } else {
                ConflictResult.Error("Failed to parse server event: no events found")
            }
        }

        val parsedEvent = parsedEvents.first()

        // The calendar may have been deleted mid-sync; upserting into it would violate the FK.
        val calendar = calendarRepository.getCalendarById(event.calendarId)
        if (calendar == null) {
            Log.w(TAG, "Calendar ${event.calendarId} deleted during conflict resolution")
            eventsDao.deleteById(event.id)
            pendingOperationsDao.deleteById(operation.id)
            return ConflictResult.LocalDeleted
        }

        val mapped = ICalEventMapper.toEntity(
            icalEvent = parsedEvent,
            rawIcal = serverEvent.icalData,
            calendarId = event.calendarId,
            caldavUrl = serverEvent.url,
            etag = serverEvent.etag
        )
        // Keep the local row id and timestamps.
        var updatedEvent = mapped.event.copy(
            id = event.id,
            createdAt = event.createdAt,
            localModifiedAt = event.localModifiedAt
        )

        // Upsert, attendee replace and occurrence regeneration must run in one transaction: a
        // failure part way would otherwise leave the event row updated with stale attendees or
        // occurrences.
        database.runInTransaction {
            eventsDao.upsert(updatedEvent)
            attendeesDao.replaceForEvent(
                updatedEvent.id,
                mapped.attendees.map { it.copy(eventId = updatedEvent.id) }
            )

            if (updatedEvent.rrule != null) {
                val now = System.currentTimeMillis()
                occurrenceGenerator.generateOccurrences(
                    updatedEvent,
                    now - (365L * 24 * 60 * 60 * 1000),  // 1 year back
                    now + PullStrategy.OCCURRENCE_EXPANSION_MS  // 2 years forward
                )
            } else {
                occurrenceGenerator.regenerateOccurrences(updatedEvent)
            }
        }

        // Delete the operation only after the transaction commits.
        pendingOperationsDao.deleteById(operation.id)

        Log.d(TAG, "Local event updated with server version")
        return ConflictResult.ServerVersionKept
    }

    /**
     * Deletes the server copy for a pending delete, then the local event. Any other operation
     * returns [ConflictResult.Error]: overwriting without an etag check isn't implemented.
     */
    private suspend fun resolveLocalWins(event: Event, operation: PendingOperation, client: CalDavClient): ConflictResult {
        Log.d(TAG, "Resolving conflict with LOCAL_WINS for event: ${event.title}")

        if (operation.operation == PendingOperation.OPERATION_DELETE) {
            val caldavUrl = event.caldavUrl
            if (caldavUrl != null) {
                // An empty etag sends `If-Match: ""`, which matches nothing, so this isn't a
                // force delete ([CalDavClient.deleteEvent] wants null for that).
                val deleteResult = client.deleteEvent(caldavUrl, "")
                if (deleteResult.isError()) {
                    val error = deleteResult as CalDavResult.Error
                    if (error.code != 404) {
                        return ConflictResult.Error("Failed to force delete: ${error.message}")
                    }
                    // 404: already gone from the server
                }
            }
            eventsDao.deleteById(event.id)
            pendingOperationsDao.deleteById(operation.id)
            Log.d(TAG, "Event force deleted")
            return ConflictResult.LocalVersionPushed
        }

        // Overwriting CREATE/UPDATE would need the server to accept a PUT without an etag
        // check, which can overwrite other users' changes. Most CalDAV servers will reject this.
        Log.w(TAG, "LOCAL_WINS for UPDATE not implemented - use SERVER_WINS instead")
        return ConflictResult.Error("LOCAL_WINS for UPDATE not supported")
    }

    /**
     * Keeps the version with the higher SEQUENCE, or on a tie the later of the server DTSTAMP and
     * the local modification time. A server win goes through [resolveServerWins]; a local win
     * takes the server etag and re-queues an UPDATE for immediate push.
     *
     * With no caldavUrl or a server 404 this returns [ConflictResult.LocalVersionPushed] without
     * touching the operation.
     */
    private suspend fun resolveNewestWins(event: Event, operation: PendingOperation, client: CalDavClient): ConflictResult {
        Log.d(TAG, "Resolving conflict with NEWEST_WINS for event: ${event.title}")

        val caldavUrl = event.caldavUrl
        if (caldavUrl == null) {
            return ConflictResult.LocalVersionPushed
        }

        val fetchResult = client.fetchEvent(caldavUrl)
        if (fetchResult.isError()) {
            val error = fetchResult as CalDavResult.Error
            if (error.code == 404) {
                return ConflictResult.LocalVersionPushed
            }
            return ConflictResult.Error("Failed to fetch server version: ${error.message}")
        }

        val serverEvent = (fetchResult as CalDavResult.Success).data
        val parseResult = icalParser.parseAllEvents(serverEvent.icalData)
        val parsedEvents = when (parseResult) {
            is ParseResult.Success -> parseResult.value
            is ParseResult.Error -> return ConflictResult.Error("Failed to parse server event: ${parseResult.error.message}")
        }
        if (parsedEvents.isEmpty()) {
            val isNonEvent = !serverEvent.icalData.contains("BEGIN:VEVENT") &&
                (serverEvent.icalData.contains("BEGIN:VTODO") ||
                 serverEvent.icalData.contains("BEGIN:VJOURNAL") ||
                 serverEvent.icalData.contains("BEGIN:VFREEBUSY"))
            return if (isNonEvent) {
                Log.d(TAG, "Conflict resolution: server resource is non-event (VTODO/VJOURNAL/VFREEBUSY)")
                ConflictResult.Error("Server resource is non-event (VTODO/VJOURNAL)")
            } else {
                ConflictResult.Error("Failed to parse server event: no events found")
            }
        }

        val parsedICalEvent = parsedEvents.first()

        // Compare sequence numbers (RFC 5545 requires incrementing SEQUENCE on changes)
        val serverSequence = parsedICalEvent.sequence
        val localSequence = event.sequence

        val serverWins = when {
            serverSequence > localSequence -> true
            serverSequence < localSequence -> false
            // Equal sequence: compare modification times
            else -> {
                val serverModified = parsedICalEvent.dtstamp?.timestamp ?: 0L
                val localModified = event.localModifiedAt ?: event.updatedAt
                serverModified > localModified // Both epoch millis
            }
        }

        return if (serverWins) {
            Log.d(TAG, "Server version is newer (seq: $serverSequence vs $localSequence)")
            resolveServerWins(event, operation, client)
        } else {
            Log.d(TAG, "Local version is newer (seq: $localSequence vs $serverSequence)")
            // Take the server's current etag first; the retry would otherwise send the stale
            // etag, get 412 again and loop forever.
            eventsDao.updateEtag(event.id, serverEvent.etag)
            // Replace the operation with a fresh one (retry count 0, due now) so the local
            // version is pushed on the next drain.
            pendingOperationsDao.deleteById(operation.id)

            val newOperation = PendingOperation(
                eventId = event.id,
                operation = PendingOperation.OPERATION_UPDATE,
                status = PendingOperation.STATUS_PENDING,
                retryCount = 0,
                nextRetryAt = 0,  // Ready immediately
                createdAt = System.currentTimeMillis()
            )
            pendingOperationsDao.insert(newOperation)

            eventsDao.updateSyncStatus(event.id, SyncStatus.PENDING_UPDATE, System.currentTimeMillis())
            ConflictResult.LocalVersionPushed
        }
    }

    /**
     * Marks the operation failed and records a conflict error on the event. No UI reads
     * `lastSyncError` today, so the user isn't shown the conflict or offered a choice.
     */
    private suspend fun resolveManual(event: Event, operation: PendingOperation): ConflictResult {
        Log.d(TAG, "Marking event for manual conflict resolution: ${event.title}")

        pendingOperationsDao.markFailed(
            operation.id,
            "Conflict detected - manual resolution required",
            System.currentTimeMillis()
        )

        eventsDao.recordSyncError(
            event.id,
            "Conflict: Server has a different version. Please resolve manually.",
            System.currentTimeMillis()
        )

        return ConflictResult.MarkedForManualResolution
    }

}

/** Selects how [ConflictResolver] settles a 412; each strategy's details are on its resolver. */
enum class ConflictStrategy {
    /** The server version overwrites local changes. Safest for shared calendars; the default. */
    SERVER_WINS,

    /** Deletes the server copy for a pending delete; any other operation is refused. */
    LOCAL_WINS,

    /** Keeps whichever version has the higher SEQUENCE, then the later modification time. */
    NEWEST_WINS,

    /** Marks the operation failed and records the conflict on the event. */
    MANUAL
}

/** Outcome of [ConflictResolver.resolve]; [isSuccess] is true for the outcomes that settled it. */
sealed class ConflictResult {
    /** The server version was kept and the local change discarded, or a delete cancelled. */
    data object ServerVersionKept : ConflictResult()

    /**
     * The local version won: it was deleted on the server, re-queued for push, or left queued
     * because the server has no copy. Nothing is pushed by the resolver itself except a delete.
     */
    data object LocalVersionPushed : ConflictResult()

    /** The local event was deleted: the server returned 404 or its calendar was deleted. */
    data object LocalDeleted : ConflictResult()

    /** The operation was marked failed for manual resolution. */
    data object MarkedForManualResolution : ConflictResult()

    /** The operation's event isn't in Room. */
    data object EventNotFound : ConflictResult()

    /** The event is now in a calendar other than the expected one. */
    data object CalendarMismatch : ConflictResult()

    /** Resolution failed; the operation is left as it was. */
    data class Error(val message: String) : ConflictResult()

    fun isSuccess() = this is ServerVersionKept ||
        this is LocalVersionPushed ||
        this is LocalDeleted ||
        this is MarkedForManualResolution
}
