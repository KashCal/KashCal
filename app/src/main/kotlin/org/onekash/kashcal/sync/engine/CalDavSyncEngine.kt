package org.onekash.kashcal.sync.engine

import android.util.Log
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.ConflictStrategy
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.util.maskEmail
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates CalDAV synchronization.
 *
 * This is THE entry point for all sync operations. It coordinates:
 * - Pull (server → local) via PullStrategy
 * - Push (local → server) via PushStrategy
 * - Conflict resolution via ConflictResolver
 *
 * Sync order (per Android and CalDAV best practices):
 * 1. Push local changes first (reduces conflict window)
 * 2. Pull server changes
 * 3. Resolve any remaining conflicts
 *
 * Usage:
 * - syncCalendar(calendar) - Sync a single calendar
 * - syncAccount(account) - Sync all calendars for an account
 */
@Singleton
class CalDavSyncEngine @Inject constructor(
    private val pullStrategy: PullStrategy,
    private val pushStrategy: PushStrategy,
    private val conflictResolver: ConflictResolver,
    private val calendarRepository: CalendarRepository,
    private val eventsDao: EventsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val syncLogsDao: SyncLogsDao,
    private val syncSessionStore: SyncSessionStore,
    private val notificationManager: SyncNotificationManager
) {
    companion object {
        private const val TAG = "CalDavSyncEngine"

        /**
         * Maximum sync cycles with conflict before abandoning local changes.
         * Each cycle: push→412→scheduleRetry→retryCount++→conflict resolution.
         * After this many cycles, we give up and let pull overwrite with server version.
         */
        private const val MAX_CONFLICT_SYNC_CYCLES = 3
    }

    /**
     * Sync a single calendar.
     *
     * @param calendar The calendar to sync
     * @param forceFullSync If true, ignores sync token and fetches all events
     * @param conflictStrategy How to resolve conflicts (default: SERVER_WINS)
     * @param quirks Optional provider-specific quirks. If null, uses default (iCloud).
     * @param client CalDavClient to use for HTTP operations (created per-account by caller).
     * @param trigger The trigger source for this sync (for session tracking)
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncCalendar(
        calendar: Calendar,
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        quirks: CalDavQuirks? = null,
        client: CalDavClient,
        trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL
    ): SyncResult {
        val startTime = System.currentTimeMillis()

        // Create session builder for tracking
        val syncType = if (forceFullSync || calendar.syncToken == null) SyncType.FULL else SyncType.INCREMENTAL
        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = syncType,
            triggerSource = trigger
        )

        // Track statistics
        var pushCreated = 0
        var pushUpdated = 0
        var pushDeleted = 0
        var pullAdded = 0
        var pullUpdated = 0
        var pullDeleted = 0
        var conflictsResolved = 0
        val errors = mutableListOf<SyncError>()
        val changes = mutableListOf<SyncChange>()
        var anyConflictsAbandoned = false

        try {
            // Step 1: Push local changes first
            val pushResult = pushStrategy.pushForCalendar(calendar, client)

            when (pushResult) {
                is PushResult.Success -> {
                    pushCreated = pushResult.eventsCreated
                    pushUpdated = pushResult.eventsUpdated
                    pushDeleted = pushResult.eventsDeleted

                    if (pushResult.operationsFailed > 0) {
                        // Some operations failed - try to resolve conflicts
                        Log.d(TAG, "Step 1b: Resolving ${pushResult.operationsFailed} push conflicts")
                        val conflictOps = getConflictOperationsForCalendar(calendar.id)
                        var abandonedCount = 0
                        var lastAbandonedTitle: String? = null

                        for (op in conflictOps) {
                            val resolved = conflictResolver.resolve(op, strategy = conflictStrategy, client = client)
                            if (resolved.isSuccess()) {
                                conflictsResolved++
                            } else {
                                // Check if we should abandon after max sync cycles
                                if (op.retryCount >= MAX_CONFLICT_SYNC_CYCLES) {
                                    val title = abandonConflictedOperation(op)
                                    if (abandonedCount == 0) lastAbandonedTitle = title
                                    abandonedCount++
                                    conflictsResolved++ // Count as resolved (abandoned)
                                    anyConflictsAbandoned = true
                                } else {
                                    errors.add(SyncError(
                                        phase = SyncPhase.CONFLICT_RESOLUTION,
                                        eventId = op.eventId,
                                        message = "Conflict resolution failed (cycle ${op.retryCount}/$MAX_CONFLICT_SYNC_CYCLES)"
                                    ))
                                }
                            }
                        }

                        // Notify user if any conflicts were abandoned
                        if (abandonedCount > 0) {
                            val titleForNotification = if (abandonedCount == 1) lastAbandonedTitle else null
                            notificationManager.showConflictAbandonedNotification(titleForNotification, abandonedCount)
                        }
                    }
                }
                is PushResult.NoPendingOperations -> {
                    Log.d(TAG, "No pending operations to push")
                }
                is PushResult.Error -> {
                    Log.e(TAG, "Push failed: ${pushResult.message}")
                    errors.add(SyncError(
                        phase = SyncPhase.PUSH,
                        code = pushResult.code,
                        message = pushResult.message
                    ))

                    // Check if it's an auth error - don't continue
                    if (pushResult.code == 401) {
                        return SyncResult.AuthError(
                            message = pushResult.message,
                            calendarId = calendar.id
                        )
                    }
                }
            }

            // Pass push stats to session builder (regardless of push result)
            sessionBuilder.setPushStats(pushCreated, pushUpdated, pushDeleted)

            // Step 2: Pull server changes
            // Re-read calendar if conflicts were abandoned (ctag was cleared in DB)
            val calendarForPull = if (anyConflictsAbandoned) {
                calendarRepository.getCalendarById(calendar.id) ?: calendar
            } else {
                calendar
            }
            val pullResult = pullStrategy.pull(calendarForPull, forceFullSync, quirks, client, sessionBuilder)

            when (pullResult) {
                is PullResult.Success -> {
                    pullAdded = pullResult.eventsAdded
                    pullUpdated = pullResult.eventsUpdated
                    pullDeleted = pullResult.eventsDeleted
                    sessionBuilder.addDeleted(pullResult.eventsDeleted)
                    // Collect changes for UI notification
                    changes.addAll(pullResult.changes)
                }
                is PullResult.NoChanges -> {
                    Log.d(TAG, "No changes on server")
                }
                is PullResult.Error -> {
                    Log.e(TAG, "Pull failed: ${pullResult.message}")
                    sessionBuilder.setError(
                        type = mapErrorCodeToType(pullResult.code),
                        stage = "pull",
                        message = pullResult.message
                    )
                    errors.add(SyncError(
                        phase = SyncPhase.PULL,
                        code = pullResult.code,
                        message = pullResult.message
                    ))

                    // Auth error
                    if (pullResult.code == 401) {
                        // Still record the session
                        syncSessionStore.add(sessionBuilder.build())
                        return SyncResult.AuthError(
                            message = pullResult.message,
                            calendarId = calendar.id
                        )
                    }
                }
            }

            // Record sync session
            val session = sessionBuilder.build()
            syncSessionStore.add(session)

            // Notify user if parse errors were abandoned after max retries (v16.7.0)
            if (session.abandonedParseErrors > 0) {
                notificationManager.showParseFailureNotification(
                    calendarName = calendar.displayName,
                    abandonedCount = session.abandonedParseErrors
                )
            }

            // Log sync completion
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Sync complete in ${duration}ms: " +
                "pushed(c=$pushCreated,u=$pushUpdated,d=$pushDeleted) " +
                "pulled(a=$pullAdded,u=$pullUpdated,d=$pullDeleted) " +
                "conflicts=$conflictsResolved errors=${errors.size}")

            // Record sync log
            logSync(calendar.id, "SYNC_COMPLETE", "SUCCESS", duration, errors.size)

            // Return result
            return if (errors.isEmpty()) {
                SyncResult.Success(
                    calendarsSynced = 1,
                    eventsPushedCreated = pushCreated,
                    eventsPushedUpdated = pushUpdated,
                    eventsPushedDeleted = pushDeleted,
                    eventsPulledAdded = pullAdded,
                    eventsPulledUpdated = pullUpdated,
                    eventsPulledDeleted = pullDeleted,
                    conflictsResolved = conflictsResolved,
                    durationMs = duration,
                    changes = changes
                )
            } else {
                SyncResult.PartialSuccess(
                    calendarsSynced = 1,
                    eventsPushedCreated = pushCreated,
                    eventsPushedUpdated = pushUpdated,
                    eventsPushedDeleted = pushDeleted,
                    eventsPulledAdded = pullAdded,
                    eventsPulledUpdated = pullUpdated,
                    eventsPulledDeleted = pullDeleted,
                    conflictsResolved = conflictsResolved,
                    errors = errors,
                    durationMs = duration,
                    changes = changes
                )
            }
        } catch (e: java.util.concurrent.CancellationException) {
            // Rethrow cancellation to properly handle coroutine cancellation
            Log.d(TAG, "Sync cancelled for calendar: ${calendar.displayName}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            logSync(calendar.id, "SYNC_COMPLETE", "ERROR", 0, 1)

            // Record the error in session history for debugging
            val errorType = when (e) {
                is java.net.SocketTimeoutException -> org.onekash.kashcal.sync.session.ErrorType.TIMEOUT
                is java.io.IOException -> org.onekash.kashcal.sync.session.ErrorType.NETWORK
                else -> org.onekash.kashcal.sync.session.ErrorType.PARSE
            }
            sessionBuilder.setError(errorType, "exception", e.message)
            syncSessionStore.add(sessionBuilder.build())

            return SyncResult.Error(
                code = -1,
                message = e.message ?: "Unknown error",
                isRetryable = true
            )
        }
    }

    /**
     * Sync all calendars for an account.
     *
     * @param account The account to sync
     * @param forceFullSync If true, ignores sync tokens
     * @param conflictStrategy How to resolve conflicts
     * @param quirks Optional provider-specific quirks. If null, uses default (iCloud).
     * @param client CalDavClient to use for HTTP operations (created per-account by caller).
     * @param trigger The trigger source for this sync (for session tracking)
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncAccount(
        account: Account,
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        quirks: CalDavQuirks? = null,
        client: CalDavClient,
        trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL
    ): SyncResult {
        Log.i(TAG, "Starting sync for account: ${account.email.maskEmail()}")
        val startTime = System.currentTimeMillis()

        val calendars = calendarRepository.getCalendarsForAccountOnce(account.id)
        if (calendars.isEmpty()) {
            Log.d(TAG, "No calendars for account")
            // Record session for debugging
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "Account: ${account.email.maskEmail()}",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            sessionBuilder.setError(org.onekash.kashcal.sync.session.ErrorType.SERVER, "no_calendars", "No calendars found for account")
            syncSessionStore.add(sessionBuilder.build())
            return SyncResult.Success(calendarsSynced = 0, durationMs = 0)
        }

        // Aggregate results
        var totalCalendars = 0
        var totalPushCreated = 0
        var totalPushUpdated = 0
        var totalPushDeleted = 0
        var totalPullAdded = 0
        var totalPullUpdated = 0
        var totalPullDeleted = 0
        var totalConflicts = 0
        val allErrors = mutableListOf<SyncError>()
        val allChanges = mutableListOf<SyncChange>()

        for ((index, calendar) in calendars.withIndex()) {
            if (calendar.isReadOnly) {
                // Read-only calendars only need pull - create session builder
                val syncType = if (forceFullSync || calendar.syncToken == null) SyncType.FULL else SyncType.INCREMENTAL
                val sessionBuilder = SyncSessionBuilder(
                    calendarId = calendar.id,
                    calendarName = calendar.displayName,
                    syncType = syncType,
                    triggerSource = trigger
                )
                val pullResult = pullStrategy.pull(calendar, forceFullSync, quirks, client, sessionBuilder)
                when (pullResult) {
                    is PullResult.Success -> {
                        totalPullAdded += pullResult.eventsAdded
                        totalPullUpdated += pullResult.eventsUpdated
                        totalPullDeleted += pullResult.eventsDeleted
                        sessionBuilder.addDeleted(pullResult.eventsDeleted)
                        allChanges.addAll(pullResult.changes)
                        totalCalendars++
                    }
                    is PullResult.NoChanges -> totalCalendars++
                    is PullResult.Error -> {
                        sessionBuilder.setError(
                            type = mapErrorCodeToType(pullResult.code),
                            stage = "pull",
                            message = pullResult.message
                        )
                        if (pullResult.code == 401) {
                            val session = sessionBuilder.build()
                            syncSessionStore.add(session)
                            // Notify if parse errors were abandoned
                            if (session.abandonedParseErrors > 0) {
                                notificationManager.showParseFailureNotification(
                                    calendarName = calendar.displayName,
                                    abandonedCount = session.abandonedParseErrors
                                )
                            }
                            return SyncResult.AuthError(
                                message = pullResult.message,
                                calendarId = calendar.id
                            )
                        }
                        allErrors.add(SyncError(
                            phase = SyncPhase.PULL,
                            calendarId = calendar.id,
                            code = pullResult.code,
                            message = pullResult.message
                        ))
                    }
                }
                val session = sessionBuilder.build()
                syncSessionStore.add(session)
                // Notify if parse errors were abandoned (ICS subscription)
                if (session.abandonedParseErrors > 0) {
                    notificationManager.showParseFailureNotification(
                        calendarName = calendar.displayName,
                        abandonedCount = session.abandonedParseErrors
                    )
                }
            } else {
                // Full sync for writable calendars
                val result = syncCalendar(calendar, forceFullSync, conflictStrategy, quirks, client, trigger)

                when (result) {
                    is SyncResult.Success -> {
                        totalCalendars += result.calendarsSynced
                        totalPushCreated += result.eventsPushedCreated
                        totalPushUpdated += result.eventsPushedUpdated
                        totalPushDeleted += result.eventsPushedDeleted
                        totalPullAdded += result.eventsPulledAdded
                        totalPullUpdated += result.eventsPulledUpdated
                        totalPullDeleted += result.eventsPulledDeleted
                        totalConflicts += result.conflictsResolved
                        allChanges.addAll(result.changes)
                    }
                    is SyncResult.PartialSuccess -> {
                        totalCalendars += result.calendarsSynced
                        totalPushCreated += result.eventsPushedCreated
                        totalPushUpdated += result.eventsPushedUpdated
                        totalPushDeleted += result.eventsPushedDeleted
                        totalPullAdded += result.eventsPulledAdded
                        totalPullUpdated += result.eventsPulledUpdated
                        totalPullDeleted += result.eventsPulledDeleted
                        totalConflicts += result.conflictsResolved
                        allErrors.addAll(result.errors)
                        allChanges.addAll(result.changes)
                    }
                    is SyncResult.AuthError -> return result
                    is SyncResult.Error -> {
                        allErrors.add(SyncError(
                            phase = SyncPhase.SYNC,
                            calendarId = calendar.id,
                            code = result.code,
                            message = result.message
                        ))
                    }
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "Account sync complete in ${duration}ms: $totalCalendars calendars")

        return if (allErrors.isEmpty()) {
            SyncResult.Success(
                calendarsSynced = totalCalendars,
                eventsPushedCreated = totalPushCreated,
                eventsPushedUpdated = totalPushUpdated,
                eventsPushedDeleted = totalPushDeleted,
                eventsPulledAdded = totalPullAdded,
                eventsPulledUpdated = totalPullUpdated,
                eventsPulledDeleted = totalPullDeleted,
                conflictsResolved = totalConflicts,
                durationMs = duration,
                changes = allChanges
            )
        } else {
            SyncResult.PartialSuccess(
                calendarsSynced = totalCalendars,
                eventsPushedCreated = totalPushCreated,
                eventsPushedUpdated = totalPushUpdated,
                eventsPushedDeleted = totalPushDeleted,
                eventsPulledAdded = totalPullAdded,
                eventsPulledUpdated = totalPullUpdated,
                eventsPulledDeleted = totalPullDeleted,
                conflictsResolved = totalConflicts,
                errors = allErrors,
                durationMs = duration,
                changes = allChanges
            )
        }
    }

    /**
     * Sync all calendars for an account using provider context.
     *
     * This is the preferred method when using ProviderRegistry.
     * Extracts quirks from the provider and passes to sync operations.
     *
     * @param account The account to sync
     * @param quirks Provider-specific quirks for parsing and handling server responses.
     * @param forceFullSync If true, ignores sync tokens
     * @param conflictStrategy How to resolve conflicts
     * @param client CalDavClient to use for HTTP operations (created per-account by caller).
     * @param trigger The trigger source for this sync (for session tracking)
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncAccountWithQuirks(
        account: Account,
        quirks: CalDavQuirks?,
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        client: CalDavClient,
        trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL
    ): SyncResult {
        return syncAccount(account, forceFullSync, conflictStrategy, quirks, client, trigger)
    }

    /**
     * Get pending operations that are in conflict state for a calendar.
     */
    private suspend fun getConflictOperationsForCalendar(calendarId: Long): List<PendingOperation> {
        val allConflicts = pendingOperationsDao.getConflictOperations()
        return allConflicts // For now return all - could filter by calendar if needed
    }

    /**
     * Abandon a conflicted operation after max sync cycles.
     * Resets event to SYNCED so pull can update it with server version.
     *
     * @param op The pending operation to abandon
     * @return The event title if available (for notification)
     */
    private suspend fun abandonConflictedOperation(op: PendingOperation): String? {
        val now = System.currentTimeMillis()
        val event = eventsDao.getById(op.eventId)

        Log.w(TAG, "Abandoning conflict for event ${op.eventId} (${event?.title}) after ${op.retryCount} sync cycles")

        // Reset event to SYNCED so pull can update it (if event still exists)
        if (event != null) {
            eventsDao.updateSyncStatus(op.eventId, SyncStatus.SYNCED, now)

            // Clear calendar ctag to force pull to fetch server version
            calendarRepository.updateCtag(event.calendarId, null)
            Log.d(TAG, "Cleared ctag for calendar ${event.calendarId} to force server fetch")
        }

        // Always delete the stuck pending operation
        pendingOperationsDao.deleteById(op.id)

        return event?.title
    }

    /**
     * Map error code to ErrorType for sync session tracking.
     *
     * Error code conventions:
     * - HTTP status codes (401, 403, 5xx) map to their respective types
     * - -408: SocketTimeoutException (mirrors HTTP 408)
     * - 0: Generic network error (IOException)
     * - -1: Non-IO exceptions (parse, processing errors)
     */
    private fun mapErrorCodeToType(code: Int): org.onekash.kashcal.sync.session.ErrorType = when (code) {
        401, 403 -> org.onekash.kashcal.sync.session.ErrorType.AUTH
        408, -408 -> org.onekash.kashcal.sync.session.ErrorType.TIMEOUT
        in 500..599 -> org.onekash.kashcal.sync.session.ErrorType.SERVER
        -1 -> org.onekash.kashcal.sync.session.ErrorType.PARSE
        else -> org.onekash.kashcal.sync.session.ErrorType.NETWORK
    }

    /**
     * Record a sync log entry.
     */
    private suspend fun logSync(
        calendarId: Long?,
        action: String,
        result: String,
        durationMs: Long,
        errorCount: Int
    ) {
        try {
            syncLogsDao.insert(SyncLog(
                timestamp = System.currentTimeMillis(),
                calendarId = calendarId,
                eventUid = null,
                action = action,
                result = result,
                details = "duration=${durationMs}ms, errors=$errorCount",
                httpStatus = null
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log sync: ${e.message}")
        }
    }
}

/**
 * Phases of the sync process.
 */
enum class SyncPhase {
    PUSH,
    PULL,
    CONFLICT_RESOLUTION,
    AUTH,
    SYNC
}

/**
 * A sync error with context.
 */
data class SyncError(
    val phase: SyncPhase,
    val calendarId: Long? = null,
    val eventId: Long? = null,
    val code: Int = -1,
    val message: String
)

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    /**
     * Sync completed successfully.
     */
    data class Success(
        val calendarsSynced: Int,
        val eventsPushedCreated: Int = 0,
        val eventsPushedUpdated: Int = 0,
        val eventsPushedDeleted: Int = 0,
        val eventsPulledAdded: Int = 0,
        val eventsPulledUpdated: Int = 0,
        val eventsPulledDeleted: Int = 0,
        val conflictsResolved: Int = 0,
        val durationMs: Long,
        /** Individual changes for UI notification (snackbar, bottom sheet) */
        val changes: List<SyncChange> = emptyList()
    ) : SyncResult() {
        val totalChanges: Int get() =
            eventsPushedCreated + eventsPushedUpdated + eventsPushedDeleted +
                eventsPulledAdded + eventsPulledUpdated + eventsPulledDeleted
    }

    /**
     * Sync completed with some errors.
     */
    data class PartialSuccess(
        val calendarsSynced: Int,
        val eventsPushedCreated: Int = 0,
        val eventsPushedUpdated: Int = 0,
        val eventsPushedDeleted: Int = 0,
        val eventsPulledAdded: Int = 0,
        val eventsPulledUpdated: Int = 0,
        val eventsPulledDeleted: Int = 0,
        val conflictsResolved: Int = 0,
        val errors: List<SyncError>,
        val durationMs: Long,
        /** Individual changes for UI notification (snackbar, bottom sheet) */
        val changes: List<SyncChange> = emptyList()
    ) : SyncResult() {
        val totalChanges: Int get() =
            eventsPushedCreated + eventsPushedUpdated + eventsPushedDeleted +
                eventsPulledAdded + eventsPulledUpdated + eventsPulledDeleted
    }

    /**
     * Authentication error - credentials need refresh.
     */
    data class AuthError(
        val message: String,
        val calendarId: Long? = null
    ) : SyncResult()

    /**
     * Sync failed completely.
     */
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = true
    ) : SyncResult()

    fun isSuccess() = this is Success
    fun isPartialSuccess() = this is PartialSuccess
    fun hasChanges() = when (this) {
        is Success -> totalChanges > 0
        is PartialSuccess -> totalChanges > 0
        else -> false
    }
}
