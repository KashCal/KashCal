package org.onekash.kashcal.sync.strategy

import android.util.Log
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.ICalParser
import javax.inject.Inject

/**
 * Resolves conflicts when local and server versions differ (412 errors).
 *
 * Conflict resolution strategies:
 * - SERVER_WINS: Server version overwrites local (default, safest for shared calendars)
 * - LOCAL_WINS: Force push local version (use with caution)
 * - NEWEST_WINS: Compare sequence/timestamps, keep newest
 * - MANUAL: Mark conflict for user to resolve
 *
 * Per Android Architecture recommendations, this is a domain-layer component
 * that coordinates between data sources without exposing implementation details.
 */
class ConflictResolver @Inject constructor(
    private val client: CalDavClient,
    private val parser: ICalParser,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "ConflictResolver"
    }

    /**
     * Resolve a conflict for a pending operation.
     *
     * @param operation The pending operation that caused the conflict
     * @param expectedCalendarId Optional calendar ID to validate against (prevents cross-calendar conflicts)
     * @param strategy Resolution strategy to use
     * @return ConflictResult indicating outcome
     */
    suspend fun resolve(
        operation: PendingOperation,
        expectedCalendarId: Long? = null,
        strategy: ConflictStrategy = ConflictStrategy.SERVER_WINS
    ): ConflictResult {
        val event = eventsDao.getById(operation.eventId)
            ?: return ConflictResult.EventNotFound

        // Validate calendar context if provided
        if (expectedCalendarId != null && event.calendarId != expectedCalendarId) {
            Log.w(TAG, "Event ${event.id} moved to different calendar during conflict resolution")
            return ConflictResult.CalendarMismatch
        }

        return when (strategy) {
            ConflictStrategy.SERVER_WINS -> resolveServerWins(event, operation)
            ConflictStrategy.LOCAL_WINS -> resolveLocalWins(event, operation)
            ConflictStrategy.NEWEST_WINS -> resolveNewestWins(event, operation)
            ConflictStrategy.MANUAL -> resolveManual(event, operation)
        }
    }

    /**
     * Resolve multiple conflicts at once.
     */
    suspend fun resolveAll(
        operations: List<PendingOperation>,
        strategy: ConflictStrategy = ConflictStrategy.SERVER_WINS
    ): List<ConflictResult> {
        return operations.map { resolve(it, strategy = strategy) }
    }

    /**
     * SERVER_WINS: Fetch server version and overwrite local.
     * This is the safest option for shared calendars.
     */
    private suspend fun resolveServerWins(event: Event, operation: PendingOperation): ConflictResult {
        Log.d(TAG, "Resolving conflict with SERVER_WINS for event: ${event.title}")

        // For DELETE operations, just delete locally
        if (operation.operation == PendingOperation.OPERATION_DELETE) {
            // Server version exists - cancel delete
            eventsDao.updateSyncStatus(event.id, SyncStatus.SYNCED, System.currentTimeMillis())
            pendingOperationsDao.deleteById(operation.id)
            Log.d(TAG, "DELETE cancelled - server version preserved")
            return ConflictResult.ServerVersionKept
        }

        // For CREATE/UPDATE, fetch server version
        val caldavUrl = event.caldavUrl
        if (caldavUrl == null) {
            // No URL means event was never on server
            // Server wins = delete local if server doesn't have it
            Log.w(TAG, "Event has no caldavUrl, cannot fetch server version")
            return ConflictResult.Error("Event has no server URL")
        }

        val fetchResult = client.fetchEvent(caldavUrl)
        if (fetchResult.isError()) {
            val error = fetchResult as CalDavResult.Error
            if (error.code == 404) {
                // Server deleted - delete local too
                eventsDao.deleteById(event.id)
                pendingOperationsDao.deleteById(operation.id)
                Log.d(TAG, "Event deleted on server - removed locally")
                return ConflictResult.LocalDeleted
            }
            return ConflictResult.Error("Failed to fetch server version: ${error.message}")
        }

        val serverEvent = (fetchResult as CalDavResult.Success).data

        // Parse server iCal
        val parseResult = parser.parse(serverEvent.icalData)
        if (!parseResult.isSuccess() || parseResult.events.isEmpty()) {
            return ConflictResult.Error("Failed to parse server event")
        }

        val parsedEvent = parseResult.events.first()

        // Validate calendar still exists (prevents FK violation if deleted mid-sync)
        val calendar = calendarsDao.getById(event.calendarId)
        if (calendar == null) {
            Log.w(TAG, "Calendar ${event.calendarId} deleted during conflict resolution")
            eventsDao.deleteById(event.id)
            pendingOperationsDao.deleteById(operation.id)
            return ConflictResult.LocalDeleted
        }

        // Read user's default reminder settings
        val defaultReminderMinutes = dataStore.defaultReminderMinutes.first()
        val defaultAllDayReminderMinutes = dataStore.defaultAllDayReminder.first()

        // Update local event with server data
        val updatedEvent = EventMapper.toEntity(
            parsed = parsedEvent,
            calendarId = event.calendarId,
            caldavUrl = serverEvent.url,
            etag = serverEvent.etag,
            existingEvent = event,
            defaultReminderMinutes = defaultReminderMinutes,
            defaultAllDayReminderMinutes = defaultAllDayReminderMinutes
        )

        eventsDao.upsert(updatedEvent)

        // Regenerate occurrences if recurring
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

        // Delete the pending operation
        pendingOperationsDao.deleteById(operation.id)

        Log.d(TAG, "Local event updated with server version")
        return ConflictResult.ServerVersionKept
    }

    /**
     * LOCAL_WINS: Force push local version, ignoring server ETag.
     * Use with caution - can overwrite other users' changes.
     */
    private suspend fun resolveLocalWins(event: Event, operation: PendingOperation): ConflictResult {
        Log.d(TAG, "Resolving conflict with LOCAL_WINS for event: ${event.title}")

        // For DELETE operations, force delete
        if (operation.operation == PendingOperation.OPERATION_DELETE) {
            val caldavUrl = event.caldavUrl
            if (caldavUrl != null) {
                // Delete with empty etag (force)
                val deleteResult = client.deleteEvent(caldavUrl, "")
                if (deleteResult.isError()) {
                    val error = deleteResult as CalDavResult.Error
                    if (error.code != 404) {
                        return ConflictResult.Error("Failed to force delete: ${error.message}")
                    }
                    // 404 is OK - already deleted
                }
            }
            eventsDao.deleteById(event.id)
            pendingOperationsDao.deleteById(operation.id)
            Log.d(TAG, "Event force deleted")
            return ConflictResult.LocalVersionPushed
        }

        // LOCAL_WINS for CREATE/UPDATE requires server to support
        // overwriting without ETag check. This is risky.
        // Most CalDAV servers will reject this.
        Log.w(TAG, "LOCAL_WINS for UPDATE not implemented - use SERVER_WINS instead")
        return ConflictResult.Error("LOCAL_WINS for UPDATE not supported")
    }

    /**
     * NEWEST_WINS: Compare sequence numbers and timestamps.
     * Keep whichever version has higher sequence or more recent modification.
     */
    private suspend fun resolveNewestWins(event: Event, operation: PendingOperation): ConflictResult {
        Log.d(TAG, "Resolving conflict with NEWEST_WINS for event: ${event.title}")

        val caldavUrl = event.caldavUrl
        if (caldavUrl == null) {
            // New local event - local wins by default
            return ConflictResult.LocalVersionPushed
        }

        // Fetch server version
        val fetchResult = client.fetchEvent(caldavUrl)
        if (fetchResult.isError()) {
            val error = fetchResult as CalDavResult.Error
            if (error.code == 404) {
                // Server doesn't have it - local wins
                return ConflictResult.LocalVersionPushed
            }
            return ConflictResult.Error("Failed to fetch server version: ${error.message}")
        }

        val serverEvent = (fetchResult as CalDavResult.Success).data
        val parseResult = parser.parse(serverEvent.icalData)
        if (!parseResult.isSuccess() || parseResult.events.isEmpty()) {
            return ConflictResult.Error("Failed to parse server event")
        }

        val parsedEvent = parseResult.events.first()

        // Compare sequence numbers (RFC 5545 requires incrementing SEQUENCE on changes)
        val serverSequence = parsedEvent.sequence
        val localSequence = event.sequence

        val serverWins = when {
            serverSequence > localSequence -> true
            serverSequence < localSequence -> false
            // Equal sequence - compare modification times
            else -> {
                val serverModified = parsedEvent.dtstamp
                val localModified = event.localModifiedAt ?: event.updatedAt
                serverModified > localModified / 1000 // dtstamp is in seconds
            }
        }

        return if (serverWins) {
            Log.d(TAG, "Server version is newer (seq: $serverSequence vs $localSequence)")
            resolveServerWins(event, operation)
        } else {
            Log.d(TAG, "Local version is newer (seq: $localSequence vs $serverSequence)")
            // Delete old operation and create a fresh one for immediate retry
            // This ensures the local version actually gets pushed to server
            pendingOperationsDao.deleteById(operation.id)

            // Create new operation with reset retry count for immediate push
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
     * MANUAL: Mark the event for user resolution.
     * The user will see a conflict indicator and choose which version to keep.
     */
    private suspend fun resolveManual(event: Event, operation: PendingOperation): ConflictResult {
        Log.d(TAG, "Marking event for manual conflict resolution: ${event.title}")

        // Mark the pending operation as needing manual resolution
        pendingOperationsDao.markFailed(
            operation.id,
            "Conflict detected - manual resolution required",
            System.currentTimeMillis()
        )

        // Record error on event
        eventsDao.recordSyncError(
            event.id,
            "Conflict: Server has a different version. Please resolve manually.",
            System.currentTimeMillis()
        )

        return ConflictResult.MarkedForManualResolution
    }
}

/**
 * Conflict resolution strategies.
 */
enum class ConflictStrategy {
    /**
     * Server version overwrites local changes.
     * Safest for shared calendars.
     */
    SERVER_WINS,

    /**
     * Force push local version to server.
     * Can overwrite other users' changes.
     */
    LOCAL_WINS,

    /**
     * Compare sequence numbers and modification times.
     * Keep whichever version is "newer".
     */
    NEWEST_WINS,

    /**
     * Mark for user to resolve manually.
     */
    MANUAL
}

/**
 * Result of conflict resolution.
 */
sealed class ConflictResult {
    /**
     * Server version was kept, local changes discarded.
     */
    data object ServerVersionKept : ConflictResult()

    /**
     * Local version was pushed to server.
     */
    data object LocalVersionPushed : ConflictResult()

    /**
     * Local event was deleted (server version doesn't exist).
     */
    data object LocalDeleted : ConflictResult()

    /**
     * Conflict marked for user to resolve.
     */
    data object MarkedForManualResolution : ConflictResult()

    /**
     * Event not found in local database.
     */
    data object EventNotFound : ConflictResult()

    /**
     * Event moved to a different calendar during conflict resolution.
     */
    data object CalendarMismatch : ConflictResult()

    /**
     * Error during resolution.
     */
    data class Error(val message: String) : ConflictResult()

    fun isSuccess() = this is ServerVersionKept ||
        this is LocalVersionPushed ||
        this is LocalDeleted ||
        this is MarkedForManualResolution
}
