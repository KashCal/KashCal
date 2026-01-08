package org.onekash.kashcal.sync.engine

import android.util.Log
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.debug.SyncDebugLog
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.provider.CalendarProvider
import org.onekash.kashcal.sync.quirks.CalDavQuirks
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
 * - syncAll() - Sync all enabled accounts
 */
@Singleton
class CalDavSyncEngine @Inject constructor(
    private val pullStrategy: PullStrategy,
    private val pushStrategy: PushStrategy,
    private val conflictResolver: ConflictResolver,
    private val accountsDao: AccountsDao,
    private val calendarsDao: CalendarsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val syncLogsDao: SyncLogsDao
) {
    companion object {
        private const val TAG = "CalDavSyncEngine"
    }

    /**
     * Sync a single calendar.
     *
     * @param calendar The calendar to sync
     * @param forceFullSync If true, ignores sync token and fetches all events
     * @param conflictStrategy How to resolve conflicts (default: SERVER_WINS)
     * @param quirks Optional provider-specific quirks. If null, uses default (iCloud).
     * @param clientOverride Optional CalDavClient to use instead of the injected one.
     *                       Used by CalDavSyncWorker to provide isolated per-account clients.
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncCalendar(
        calendar: Calendar,
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        quirks: CalDavQuirks? = null,
        clientOverride: CalDavClient? = null
    ): SyncResult {
        Log.i(TAG, "Starting sync for calendar: ${calendar.displayName}")
        val startTime = System.currentTimeMillis()

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

        try {
            // Step 1: Push local changes first
            Log.d(TAG, "Step 1: Pushing local changes")
            val pushResult = pushStrategy.pushForCalendar(calendar, clientOverride)

            when (pushResult) {
                is PushResult.Success -> {
                    pushCreated = pushResult.eventsCreated
                    pushUpdated = pushResult.eventsUpdated
                    pushDeleted = pushResult.eventsDeleted

                    if (pushResult.operationsFailed > 0) {
                        // Some operations failed - try to resolve conflicts
                        Log.d(TAG, "Step 1b: Resolving ${pushResult.operationsFailed} push conflicts")
                        val conflictOps = getConflictOperationsForCalendar(calendar.id)
                        for (op in conflictOps) {
                            val resolved = conflictResolver.resolve(op, conflictStrategy)
                            if (resolved.isSuccess()) {
                                conflictsResolved++
                            } else {
                                errors.add(SyncError(
                                    phase = SyncPhase.CONFLICT_RESOLUTION,
                                    eventId = op.eventId,
                                    message = "Conflict resolution failed"
                                ))
                            }
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

            // Step 2: Pull server changes
            Log.d(TAG, "Step 2: Pulling server changes")
            val pullResult = pullStrategy.pull(calendar, forceFullSync, quirks, clientOverride)

            when (pullResult) {
                is PullResult.Success -> {
                    pullAdded = pullResult.eventsAdded
                    pullUpdated = pullResult.eventsUpdated
                    pullDeleted = pullResult.eventsDeleted
                    // Collect changes for UI notification
                    changes.addAll(pullResult.changes)
                }
                is PullResult.NoChanges -> {
                    Log.d(TAG, "No changes on server")
                }
                is PullResult.Error -> {
                    Log.e(TAG, "Pull failed: ${pullResult.message}")
                    errors.add(SyncError(
                        phase = SyncPhase.PULL,
                        code = pullResult.code,
                        message = pullResult.message
                    ))

                    // Auth error
                    if (pullResult.code == 401) {
                        return SyncResult.AuthError(
                            message = pullResult.message,
                            calendarId = calendar.id
                        )
                    }
                }
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
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            logSync(calendar.id, "SYNC_COMPLETE", "ERROR", 0, 1)
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
     * @param clientOverride Optional CalDavClient to use instead of the injected one.
     *                       Used by CalDavSyncWorker to provide isolated per-account clients.
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncAccount(
        account: Account,
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        quirks: CalDavQuirks? = null,
        clientOverride: CalDavClient? = null
    ): SyncResult {
        Log.i(TAG, "Starting sync for account: ${account.email.maskEmail()}")
        SyncDebugLog.i(TAG, "syncAccount() for ${account.email.maskEmail()}")
        val startTime = System.currentTimeMillis()

        val calendars = calendarsDao.getByAccountIdOnce(account.id)
        SyncDebugLog.i(TAG, "Found ${calendars.size} calendars for account")
        if (calendars.isEmpty()) {
            Log.d(TAG, "No calendars for account")
            SyncDebugLog.w(TAG, "NO CALENDARS for account!")
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

        for (calendar in calendars) {
            if (calendar.isReadOnly) {
                // Read-only calendars only need pull
                val pullResult = pullStrategy.pull(calendar, forceFullSync, quirks, clientOverride)
                when (pullResult) {
                    is PullResult.Success -> {
                        totalPullAdded += pullResult.eventsAdded
                        totalPullUpdated += pullResult.eventsUpdated
                        totalPullDeleted += pullResult.eventsDeleted
                        allChanges.addAll(pullResult.changes)
                        totalCalendars++
                    }
                    is PullResult.NoChanges -> totalCalendars++
                    is PullResult.Error -> {
                        if (pullResult.code == 401) {
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
            } else {
                // Full sync for writable calendars
                val result = syncCalendar(calendar, forceFullSync, conflictStrategy, quirks, clientOverride)

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
     * @param provider The CalendarProvider for this account
     * @param forceFullSync If true, ignores sync tokens
     * @param conflictStrategy How to resolve conflicts
     * @param clientOverride Optional CalDavClient to use instead of the injected one.
     *                       Used by CalDavSyncWorker to provide isolated per-account clients.
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncAccountWithProvider(
        account: Account,
        provider: CalendarProvider,
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS,
        clientOverride: CalDavClient? = null
    ): SyncResult {
        val quirks = provider.getQuirks()
        return syncAccount(account, forceFullSync, conflictStrategy, quirks, clientOverride)
    }

    /**
     * Sync all enabled accounts.
     *
     * @param forceFullSync If true, ignores sync tokens
     * @param conflictStrategy How to resolve conflicts
     * @return SyncResult with aggregated statistics
     */
    suspend fun syncAll(
        forceFullSync: Boolean = false,
        conflictStrategy: ConflictStrategy = ConflictStrategy.SERVER_WINS
    ): SyncResult {
        Log.i(TAG, "Starting sync for all accounts")
        val startTime = System.currentTimeMillis()

        val accounts = accountsDao.getEnabledAccounts()
        if (accounts.isEmpty()) {
            Log.d(TAG, "No enabled accounts")
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

        for (account in accounts) {
            val result = syncAccount(account, forceFullSync, conflictStrategy)

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
                is SyncResult.AuthError -> {
                    // Continue with other accounts, but record error
                    allErrors.add(SyncError(
                        phase = SyncPhase.AUTH,
                        calendarId = result.calendarId,
                        code = 401,
                        message = result.message
                    ))
                }
                is SyncResult.Error -> {
                    allErrors.add(SyncError(
                        phase = SyncPhase.SYNC,
                        code = result.code,
                        message = result.message
                    ))
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "Full sync complete in ${duration}ms: ${accounts.size} accounts, $totalCalendars calendars")

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
     * Get pending operations that are in conflict state for a calendar.
     */
    private suspend fun getConflictOperationsForCalendar(calendarId: Long): List<org.onekash.kashcal.data.db.entity.PendingOperation> {
        val allConflicts = pendingOperationsDao.getConflictOperations()
        return allConflicts // For now return all - could filter by calendar if needed
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
