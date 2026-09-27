package org.onekash.kashcal.sync.engine

import android.util.Log
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.ConflictStrategy
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.util.maskEmail
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs CalDAV sync for one calendar ([syncCalendar]) or every calendar of an account
 * ([syncAccount]), coordinating [PushStrategy], [ConflictResolver] and [PullStrategy].
 *
 * Order per writable calendar:
 * 1. Push local changes first, which narrows the conflict window.
 * 2. Resolve the push's conflicts, abandoning one after [MAX_CONFLICT_SYNC_CYCLES].
 * 3. Pull server changes.
 *
 * Read-only calendars are only pulled.
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
         * Sync cycles a conflict may fail to resolve before its local change is abandoned and
         * the pull takes the server version. Each cycle is a push that gets 412, a retry that
         * increments retryCount, and a failed resolution.
         */
        private const val MAX_CONFLICT_SYNC_CYCLES = 3
    }

    /**
     * Pushes, resolves conflicts for, and pulls one calendar.
     *
     * @param forceFullSync ignores the sync-token and fetches all events.
     * @param quirks provider quirks; null uses the iCloud default.
     * @param client the account's client, created by the caller.
     * @param trigger the sync's source, recorded in the session.
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

        val syncType = if (forceFullSync || calendar.syncToken == null) SyncType.FULL else SyncType.INCREMENTAL
        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = syncType,
            triggerSource = trigger
        )

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
            // Step 1: push local changes.
            val pushResult = pushStrategy.pushForCalendar(calendar, client)

            when (pushResult) {
                is PushResult.Success -> {
                    pushCreated = pushResult.eventsCreated
                    pushUpdated = pushResult.eventsUpdated
                    pushDeleted = pushResult.eventsDeleted

                    // Retryable per-operation problems are session warnings.
                    pushResult.pushWarnings.forEach { sessionBuilder.addWarning(it) }
                    // A permanent per-operation failure didn't reach the server and won't be
                    // retried, so it must surface as an error, not a warning. Its HTTP code is
                    // kept so later classification (auth, server, not found) still works.
                    pushResult.pushErrors.forEach {
                        errors.add(SyncError(phase = SyncPhase.PUSH, code = it.code, message = it.message))
                    }

                    if (pushResult.operationsFailed > 0) {
                        // Step 2: resolve the failed operations' conflicts.
                        Log.d(TAG, "Step 1b: Resolving ${pushResult.operationsFailed} push conflicts")
                        val conflictOps = pendingOperationsDao.getConflictOperationsForCalendar(calendar.id)
                        var abandonedCount = 0
                        var lastAbandonedTitle: String? = null

                        for (op in conflictOps) {
                            val resolved = conflictResolver.resolve(op, strategy = conflictStrategy, client = client)
                            if (resolved.isSuccess()) {
                                conflictsResolved++
                            } else {
                                if (op.retryCount >= MAX_CONFLICT_SYNC_CYCLES) {
                                    val title = abandonConflictedOperation(op)
                                    if (abandonedCount == 0) lastAbandonedTitle = title
                                    abandonedCount++
                                    conflictsResolved++ // An abandoned conflict counts as resolved.
                                    anyConflictsAbandoned = true
                                } else {
                                    errors.add(SyncError(
                                        phase = SyncPhase.CONFLICT_RESOLUTION,
                                        eventId = op.eventId,
                                        message = "Conflict resolution failed (cycle ${op.retryCount}/$MAX_CONFLICT_SYNC_CYCLES)"
                                    ))
                                    sessionBuilder.addWarning("Conflict resolution pending (attempt ${op.retryCount}/$MAX_CONFLICT_SYNC_CYCLES)")
                                }
                            }
                        }

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
                    sessionBuilder.addWarning("Push failed (${pushResult.code}): ${pushResult.message}")
                    errors.add(SyncError(
                        phase = SyncPhase.PUSH,
                        code = pushResult.code,
                        message = pushResult.message
                    ))

                    // An auth failure stops the sync before the pull.
                    if (pushResult.code == 401) {
                        return SyncResult.AuthError(
                            message = pushResult.message,
                            calendarId = calendar.id
                        )
                    }
                }
            }

            // Recorded whatever the push result.
            sessionBuilder.setPushStats(pushCreated, pushUpdated, pushDeleted)

            // Step 3: pull server changes. Abandoning a conflict cleared the ctag in the
            // database, so the calendar is re-read to pull the server version.
            val calendarForPull = if (anyConflictsAbandoned) {
                calendarRepository.getCalendarById(calendar.id) ?: calendar
            } else {
                calendar
            }
            // The pull skips just-pushed events so a stale CDN read can't overwrite them, except
            // those whose write carried a server change their rows lack.
            val pushed = pushResult as? PushResult.Success
            val pullResult = pullStrategy.pull(calendarForPull, forceFullSync, quirks, client, sessionBuilder,
                recentlyPushedEventIds = pushed?.pushedEventIds.orEmpty(),
                refetchEventIds = pushed?.refetchEventIds.orEmpty())

            when (pullResult) {
                is PullResult.Success -> {
                    pullAdded = pullResult.eventsAdded
                    pullUpdated = pullResult.eventsUpdated
                    pullDeleted = pullResult.eventsDeleted
                    sessionBuilder.addDeleted(pullResult.eventsDeleted)
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

                    if (pullResult.code == 401) {
                        // The session is recorded even on an auth failure.
                        syncSessionStore.add(sessionBuilder.build())
                        return SyncResult.AuthError(
                            message = pullResult.message,
                            calendarId = calendar.id
                        )
                    }
                }
            }

            val session = sessionBuilder.build()
            syncSessionStore.add(session)

            // Events that failed to parse too many times are given up on; tell the user.
            if (session.abandonedParseErrors > 0) {
                notificationManager.showParseFailureNotification(
                    calendarName = calendar.displayName,
                    abandonedCount = session.abandonedParseErrors
                )
            }

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Sync complete in ${duration}ms: " +
                "pushed(c=$pushCreated,u=$pushUpdated,d=$pushDeleted) " +
                "pulled(a=$pullAdded,u=$pullUpdated,d=$pullDeleted) " +
                "conflicts=$conflictsResolved errors=${errors.size}")

            logSync(calendar.id, "SYNC_COMPLETE", "SUCCESS", duration, errors.size)

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
            // Coroutine cancellation must propagate.
            Log.d(TAG, "Sync cancelled for calendar: ${calendar.displayName}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            logSync(calendar.id, "SYNC_COMPLETE", "ERROR", 0, 1)

            // Recorded in the session history for debugging.
            val errorType = when (e) {
                is java.net.SocketTimeoutException -> org.onekash.kashcal.sync.session.ErrorType.TIMEOUT
                is java.io.IOException -> org.onekash.kashcal.sync.session.ErrorType.NETWORK
                else -> org.onekash.kashcal.sync.session.ErrorType.PARSE
            }
            sessionBuilder.setError(errorType, "exception", e.message)
            syncSessionStore.add(sessionBuilder.build())

            return SyncResult.Error(
                code = -1,
                message = e.message ?: e.javaClass.simpleName,
                isRetryable = true
            )
        }
    }

    /**
     * Syncs every calendar of [account] and aggregates the results. The first auth failure
     * returns [SyncResult.AuthError] and stops the remaining calendars.
     *
     * @param forceFullSync ignores sync-tokens.
     * @param quirks provider quirks; null uses the iCloud default.
     * @param client the account's client, created by the caller.
     * @param trigger the sync's source, recorded in the session.
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
            // Recorded for debugging.
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

        for ((_, calendar) in calendars.withIndex()) {
            if (calendar.isReadOnly) {
                // Read-only calendars have nothing to push, so they are only pulled.
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
                if (session.abandonedParseErrors > 0) {
                    notificationManager.showParseFailureNotification(
                        calendarName = calendar.displayName,
                        abandonedCount = session.abandonedParseErrors
                    )
                }
            } else {
                // Writable calendars push, resolve conflicts and pull.
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
     * Calls [syncAccount] with [quirks] as a required, leading argument, for callers that take
     * the quirks from the provider registry.
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
     * Abandons a conflicted operation: deletes it and marks the event SYNCED so the pull
     * overwrites it with the server version.
     *
     * @return the event's title for the notification, or null if the event is gone.
     */
    private suspend fun abandonConflictedOperation(op: PendingOperation): String? {
        val now = System.currentTimeMillis()
        val event = eventsDao.getById(op.eventId)

        Log.w(TAG, "Abandoning conflict for event ${op.eventId} (${event?.title}) after ${op.retryCount} sync cycles")

        if (event != null) {
            eventsDao.updateSyncStatus(op.eventId, SyncStatus.SYNCED, now)

            // Without a ctag the pull can't skip the calendar as unchanged.
            calendarRepository.updateCtag(event.calendarId, null)
            Log.d(TAG, "Cleared ctag for calendar ${event.calendarId} to force server fetch")
        }

        // Deleted even when the event is gone.
        pendingOperationsDao.deleteById(op.id)

        return event?.title
    }

    /**
     * Maps a result code to the session's ErrorType.
     *
     * - HTTP 401 and 403 are AUTH, 408 is TIMEOUT, 5xx is SERVER.
     * - -408 ([org.onekash.kashcal.sync.client.model.CalDavResult.CODE_TIMEOUT]) is a socket
     *   timeout, mirroring HTTP 408.
     * - -1 is a non-IO exception (parse or processing error), mapped to PARSE.
     * - Everything else, including 0 (a generic IOException), is NETWORK.
     */
    private fun mapErrorCodeToType(code: Int): org.onekash.kashcal.sync.session.ErrorType = when (code) {
        401, 403 -> org.onekash.kashcal.sync.session.ErrorType.AUTH
        408, -408 -> org.onekash.kashcal.sync.session.ErrorType.TIMEOUT
        in 500..599 -> org.onekash.kashcal.sync.session.ErrorType.SERVER
        -1 -> org.onekash.kashcal.sync.session.ErrorType.PARSE
        else -> org.onekash.kashcal.sync.session.ErrorType.NETWORK
    }

    /** Writes a sync_logs row; a failed write is logged and ignored. */
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

/** The sync step an error came from. */
enum class SyncPhase {
    PUSH,
    PULL,
    CONFLICT_RESOLUTION,
    AUTH,
    SYNC
}

/** One error from a sync, with the calendar or event it concerns when known. */
data class SyncError(
    val phase: SyncPhase,
    val calendarId: Long? = null,
    val eventId: Long? = null,
    val code: Int = -1,
    val message: String
)

/** Outcome of a calendar or account sync. */
sealed class SyncResult {
    /** Every step succeeded. */
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
        /** The pulled changes, one per event. */
        val changes: List<SyncChange> = emptyList()
    ) : SyncResult() {
        val totalChanges: Int get() =
            eventsPushedCreated + eventsPushedUpdated + eventsPushedDeleted +
                eventsPulledAdded + eventsPulledUpdated + eventsPulledDeleted
    }

    /** The sync finished but some steps or calendars failed ([errors]). */
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
        /** The pulled changes, one per event. */
        val changes: List<SyncChange> = emptyList()
    ) : SyncResult() {
        val totalChanges: Int get() =
            eventsPushedCreated + eventsPushedUpdated + eventsPushedDeleted +
                eventsPulledAdded + eventsPulledUpdated + eventsPulledDeleted
    }

    /** The server returned 401, or no credentials are stored; the user must sign in again. */
    data class AuthError(
        val message: String,
        val calendarId: Long? = null
    ) : SyncResult()

    /**
     * The sync failed outright: it threw, or its calendar, account, credential provider or
     * quirks are missing.
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
