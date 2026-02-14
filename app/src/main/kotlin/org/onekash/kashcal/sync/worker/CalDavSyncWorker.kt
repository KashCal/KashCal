package org.onekash.kashcal.sync.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.sync.session.ErrorType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.provider.icloud.ICloudUrlMigration
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.util.maskEmail
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for CalDAV synchronization.
 *
 * Handles both periodic background sync and user-initiated one-shot sync.
 * Uses Hilt for dependency injection.
 *
 * Features:
 * - Periodic background sync (configurable interval, minimum 15 min)
 * - One-shot sync for user-initiated or push notification triggered sync
 * - Expedited work for immediate sync needs with foreground service
 * - Calendar-specific sync via input data
 * - Progress reporting via WorkInfo
 * - Foreground notification for long-running sync
 *
 * Per Android WorkManager best practices:
 * - Uses CoroutineWorker for suspend function support
 * - Returns appropriate Result for retry/backoff handling
 * - Respects constraints (network, battery)
 * - Uses setForeground() for expedited/long-running work
 */
@HiltWorker
class CalDavSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: CalDavSyncEngine,
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository,
    private val notificationManager: SyncNotificationManager,
    private val providerRegistry: ProviderRegistry,
    private val calDavClientFactory: CalDavClientFactory,
    private val syncScheduler: SyncScheduler,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val reminderScheduler: ReminderScheduler,
    private val eventReader: EventReader,
    private val pendingOperationsDao: PendingOperationsDao,
    private val syncSessionStore: SyncSessionStore,
    private val syncLogsDao: SyncLogsDao,
    private val iCloudUrlMigration: ICloudUrlMigration
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CalDavSyncWorker"

        // Input data keys
        const val KEY_CALENDAR_ID = "calendar_id"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_FORCE_FULL_SYNC = "force_full_sync"
        const val KEY_SYNC_TYPE = "sync_type"
        const val KEY_SHOW_NOTIFICATION = "show_notification"
        const val KEY_SYNC_TRIGGER = "sync_trigger"

        // Output data keys
        const val KEY_CALENDARS_SYNCED = "calendars_synced"
        const val KEY_EVENTS_PUSHED = "events_pushed"
        const val KEY_EVENTS_PULLED = "events_pulled"
        const val KEY_CONFLICTS_RESOLVED = "conflicts_resolved"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_DURATION_MS = "duration_ms"

        // Sync types
        const val SYNC_TYPE_FULL = "full"
        const val SYNC_TYPE_CALENDAR = "calendar"
        const val SYNC_TYPE_ACCOUNT = "account"

        // Sync log retention (7 days)
        private const val SYNC_LOG_RETENTION_DAYS = 7

        // Consecutive sync failures before notifying user
        private const val SYNC_FAILURE_NOTIFICATION_THRESHOLD = 3

        /**
         * Create input data for full sync (all accounts).
         */
        fun createFullSyncInput(
            forceFullSync: Boolean = false,
            showNotification: Boolean = false,
            trigger: SyncTrigger = SyncTrigger.BACKGROUND_PERIODIC
        ): Data {
            return Data.Builder()
                .putString(KEY_SYNC_TYPE, SYNC_TYPE_FULL)
                .putBoolean(KEY_FORCE_FULL_SYNC, forceFullSync)
                .putBoolean(KEY_SHOW_NOTIFICATION, showNotification)
                .putString(KEY_SYNC_TRIGGER, trigger.name)
                .build()
        }

        /**
         * Create input data for calendar-specific sync.
         */
        fun createCalendarSyncInput(
            calendarId: Long,
            forceFullSync: Boolean = false,
            showNotification: Boolean = false,
            trigger: SyncTrigger = SyncTrigger.BACKGROUND_PERIODIC
        ): Data {
            return Data.Builder()
                .putString(KEY_SYNC_TYPE, SYNC_TYPE_CALENDAR)
                .putLong(KEY_CALENDAR_ID, calendarId)
                .putBoolean(KEY_FORCE_FULL_SYNC, forceFullSync)
                .putBoolean(KEY_SHOW_NOTIFICATION, showNotification)
                .putString(KEY_SYNC_TRIGGER, trigger.name)
                .build()
        }

        /**
         * Create input data for account-specific sync.
         */
        fun createAccountSyncInput(
            accountId: Long,
            forceFullSync: Boolean = false,
            showNotification: Boolean = false,
            trigger: SyncTrigger = SyncTrigger.BACKGROUND_PERIODIC
        ): Data {
            return Data.Builder()
                .putString(KEY_SYNC_TYPE, SYNC_TYPE_ACCOUNT)
                .putLong(KEY_ACCOUNT_ID, accountId)
                .putBoolean(KEY_FORCE_FULL_SYNC, forceFullSync)
                .putBoolean(KEY_SHOW_NOTIFICATION, showNotification)
                .putString(KEY_SYNC_TRIGGER, trigger.name)
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL
        val forceFullSync = inputData.getBoolean(KEY_FORCE_FULL_SYNC, false)
        val showNotification = inputData.getBoolean(KEY_SHOW_NOTIFICATION, false)
        val trigger = parseTrigger(inputData)

        Log.i(TAG, "Starting sync: type=$syncType, force=$forceFullSync, trigger=${trigger.name}, attempt=${runAttemptCount + 1}")

        // Set foreground for expedited work (shows progress notification)
        try {
            val foregroundInfo = notificationManager.createForegroundInfo(
                progress = "Syncing calendars...",
                cancelIntent = createCancelPendingIntent()
            )
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            // setForeground may fail if work is not expedited, that's OK
            Log.d(TAG, "Could not set foreground (non-expedited work): ${e.message}")
        }

        try {
            // Recover any operations stuck in IN_PROGRESS from previous crashed syncs
            // Uses 1-hour timeout - operations legitimately in progress complete in <2 min
            val staleCount = run {
                val oneHourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
                pendingOperationsDao.resetStaleInProgress(
                    cutoff = oneHourAgo,
                    now = System.currentTimeMillis()
                )
            }
            if (staleCount > 0) {
                Log.w(TAG, "Recovered $staleCount stuck IN_PROGRESS operations from previous sync")
            }

            // === iCloud URL Migration (one-time) ===
            // Normalizes regional URLs (p180-caldav.icloud.com) to canonical form (caldav.icloud.com)
            // to handle Apple server rotation gracefully
            val migrated = iCloudUrlMigration.migrateIfNeeded()
            if (migrated) {
                Log.i(TAG, "iCloud URLs normalized to canonical form")
            }

            // === Retry Lifecycle Management (v21.5.3) ===
            val now = System.currentTimeMillis()
            val thirtyDaysAgo = now - PendingOperation.OPERATION_LIFETIME_MS

            // A: Force full sync resets ALL failed operations (runs FIRST)
            // Also resets lifetime_reset_at so they get a fresh 30-day window
            if (forceFullSync) {
                val failedResetCount = pendingOperationsDao.resetAllFailed(now)
                if (failedResetCount > 0) {
                    Log.i(TAG, "Reset $failedResetCount failed operations for retry (force full sync)")
                }
            }

            // D: Abandon operations exceeding 30-day lifetime (skipped if force sync just reset them)
            val expiredOps = pendingOperationsDao.getExpiredOperations(thirtyDaysAgo)
            for (op in expiredOps) {
                pendingOperationsDao.abandonOperation(
                    op.id,
                    "Operation exceeded 30-day lifetime without user interaction",
                    now
                )
            }
            if (expiredOps.isNotEmpty()) {
                Log.w(TAG, "Abandoned ${expiredOps.size} operations exceeding 30-day lifetime")
                notificationManager.showOperationExpiredNotification(expiredOps.size)
            }

            // B: Auto-retry failed operations older than 24 hours (excludes expired)
            val twentyFourHoursAgo = now - PendingOperation.AUTO_RESET_FAILED_MS
            val oldFailedResetCount = pendingOperationsDao.autoResetOldFailed(
                failedBefore = twentyFourHoursAgo,
                lifetimeCutoff = thirtyDaysAgo,
                now = now
            )
            if (oldFailedResetCount > 0) {
                Log.i(TAG, "Auto-reset $oldFailedResetCount failed operations older than 24h for retry")
            }

            val syncResult = when (syncType) {
                SYNC_TYPE_CALENDAR -> {
                    val calendarId = inputData.getLong(KEY_CALENDAR_ID, -1)
                    if (calendarId == -1L) {
                        Log.e(TAG, "Calendar sync requested but no calendar_id provided")
                        return@withContext Result.failure(
                            createErrorOutput("No calendar_id provided")
                        )
                    }
                    syncCalendar(calendarId, forceFullSync, trigger)
                }
                SYNC_TYPE_ACCOUNT -> {
                    val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1)
                    if (accountId == -1L) {
                        Log.e(TAG, "Account sync requested but no account_id provided")
                        return@withContext Result.failure(
                            createErrorOutput("No account_id provided")
                        )
                    }
                    syncAccount(accountId, forceFullSync, trigger)
                }
                else -> {
                    syncAll(forceFullSync, trigger)
                }
            }

            // Show completion notification if requested
            if (showNotification) {
                notificationManager.showCompletionNotification(syncResult, showOnlyOnChanges = false)
            }

            // Cancel progress notification
            notificationManager.cancelProgressNotification()

            // Update home screen widgets if sync had changes
            if (syncResult.hasChanges()) {
                Log.d(TAG, "Updating widgets after sync with changes")
                widgetUpdateManager.updateAllWidgets()
            }

            // Schedule reminders for synced events (NEW and MODIFIED)
            val changes = when (syncResult) {
                is SyncResult.Success -> syncResult.changes
                is SyncResult.PartialSuccess -> syncResult.changes
                else -> emptyList()
            }
            if (changes.isNotEmpty()) {
                scheduleRemindersForSyncedEvents(changes)
            }

            // Cleanup old sync logs (7 day retention)
            // Best-effort - don't fail sync if cleanup throws
            try {
                val cutoff = System.currentTimeMillis() - (SYNC_LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
                val deleted = syncLogsDao.deleteOldLogs(cutoff)
                if (deleted > 0) {
                    Log.d(TAG, "Cleaned up $deleted old sync logs")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync log cleanup failed, continuing", e)
            }

            handleSyncResult(syncResult)
        } catch (e: java.util.concurrent.CancellationException) {
            // Rethrow cancellation to properly handle coroutine cancellation
            Log.d(TAG, "Sync cancelled")
            notificationManager.cancelProgressNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            notificationManager.cancelProgressNotification()

            // Record failure in sync session for debugging
            // Use placeholder values for calendar since error happened before reaching sync engine
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "Exception: ${e.javaClass.simpleName}",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            val errorType = when (e) {
                is java.net.SocketTimeoutException -> ErrorType.TIMEOUT
                is java.io.IOException -> ErrorType.NETWORK
                else -> ErrorType.SERVER  // Generic server/unknown error
            }
            sessionBuilder.setError(errorType, "worker_exception", e.message)
            syncSessionStore.add(sessionBuilder.build())
            Log.d(TAG, "Recorded sync session with error: ${errorType.name}")

            if (showNotification) {
                notificationManager.showErrorNotification("Sync Failed", e.message ?: "Unknown error")
            }

            Result.retry()
        }
    }

    /**
     * Create a cancel pending intent for the foreground notification.
     */
    private fun createCancelPendingIntent(): android.app.PendingIntent? {
        return try {
            val cancelIntent = androidx.work.WorkManager.getInstance(applicationContext)
                .createCancelPendingIntent(id)
            cancelIntent
        } catch (e: Exception) {
            Log.w(TAG, "Could not create cancel intent: ${e.message}")
            null
        }
    }

    /**
     * Sync a specific calendar.
     */
    private suspend fun syncCalendar(calendarId: Long, forceFullSync: Boolean, trigger: SyncTrigger): SyncResult {
        val calendar = calendarRepository.getCalendarById(calendarId)
        if (calendar == null) {
            Log.w(TAG, "Calendar $calendarId not found")
            return SyncResult.Error(-1, "Calendar not found", false)
        }

        // Load account for this calendar
        val account = accountRepository.getAccountById(calendar.accountId)
        if (account == null) {
            Log.w(TAG, "Account ${calendar.accountId} not found for calendar")
            return SyncResult.Error(-1, "Account not found", false)
        }

        // Get credential provider from registry
        val credProvider = providerRegistry.getCredentialProvider(account.provider)
        if (credProvider == null) {
            Log.w(TAG, "No credential provider for account ${account.email.maskEmail()}")
            return SyncResult.Error(-1, "No credential provider for ${account.provider}", false)
        }

        // Load credentials for this account
        val credentials = credProvider.getCredentials(account.id)
        if (credentials == null) {
            Log.w(TAG, "No credentials for account ${account.email.maskEmail()}")
            return SyncResult.AuthError("Credentials not found")
        }

        // Get quirks for this provider
        val quirks = providerRegistry.getQuirksForAccount(account)
        if (quirks == null) {
            Log.w(TAG, "No quirks for account ${account.email.maskEmail()}")
            return SyncResult.Error(-1, "No quirks for ${account.provider}", false)
        }

        // Create isolated client for this account
        val client = calDavClientFactory.createClient(credentials, quirks)

        Log.d(TAG, "Syncing calendar: ${calendar.displayName}")
        return syncEngine.syncCalendar(
            calendar = calendar,
            forceFullSync = forceFullSync,
            quirks = quirks,
            client = client,
            trigger = trigger
        )
    }

    /**
     * Sync all calendars for an account using provider registry.
     *
     * Uses same pattern as syncAll() for consistency:
     * 1. Get provider from AccountProvider enum
     * 2. Skip if doesn't support CalDAV
     * 3. Load credentials from provider's credential provider
     * 4. Create isolated client for this account
     * 5. Sync using provider-specific quirks
     */
    private suspend fun syncAccount(accountId: Long, forceFullSync: Boolean, trigger: SyncTrigger): SyncResult {
        val account = accountRepository.getAccountById(accountId)
        if (account == null) {
            Log.w(TAG, "Account $accountId not found")
            return SyncResult.Error(-1, "Account not found", false)
        }

        Log.d(TAG, "Syncing account: ${account.email.maskEmail()}")

        // Skip disabled accounts
        if (!account.isEnabled) {
            Log.d(TAG, "Account $accountId is disabled, skipping sync")
            return SyncResult.Success(calendarsSynced = 0, durationMs = 0)
        }

        // Skip accounts that don't support CalDAV
        if (!account.provider.supportsCalDAV) {
            Log.d(TAG, "Skipping non-CalDAV account ${account.email.maskEmail()} (${account.provider})")
            return SyncResult.Success(calendarsSynced = 0, durationMs = 0)
        }

        // Get credential provider from registry
        val credProvider = providerRegistry.getCredentialProvider(account.provider)
        if (credProvider == null) {
            Log.w(TAG, "No credential provider for account ${account.email.maskEmail()}")
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "Account: ${account.email.maskEmail()}",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            sessionBuilder.setError(ErrorType.AUTH, "no_cred_provider", "Credential provider not found for ${account.provider}")
            syncSessionStore.add(sessionBuilder.build())
            return SyncResult.Error(-1, "No credential provider for ${account.provider}", false)
        }

        // Load credentials for this account
        val credentials = credProvider.getCredentials(account.id)
        if (credentials == null) {
            Log.w(TAG, "No credentials for account ${account.email.maskEmail()}")
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "Account: ${account.email.maskEmail()}",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            sessionBuilder.setError(ErrorType.AUTH, "no_credentials", "Credentials not found for account")
            syncSessionStore.add(sessionBuilder.build())
            return SyncResult.AuthError("Credentials not found")
        }

        // Get quirks for this provider
        val quirks = providerRegistry.getQuirksForAccount(account)
        if (quirks == null) {
            Log.w(TAG, "No quirks for account ${account.email.maskEmail()}")
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "Account: ${account.email.maskEmail()}",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            sessionBuilder.setError(ErrorType.SERVER, "no_quirks", "Provider quirks not found for ${account.provider}")
            syncSessionStore.add(sessionBuilder.build())
            return SyncResult.Error(-1, "No quirks for ${account.provider}", false)
        }

        // Create isolated client for this account (prevents credential race condition)
        val isolatedClient = calDavClientFactory.createClient(credentials, quirks)
        Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}***")

        // Sync this account with its quirks and isolated client
        val result = try {
            syncEngine.syncAccountWithQuirks(
                account = account,
                quirks = quirks,
                forceFullSync = forceFullSync,
                client = isolatedClient,
                trigger = trigger
            )
        } catch (e: Exception) {
            accountRepository.recordSyncFailure(accountId, System.currentTimeMillis())
            try { checkSyncFailureThreshold(accountId) } catch (_: Exception) { }
            throw e
        }

        // Record sync metadata on account
        val now = System.currentTimeMillis()
        when (result) {
            is SyncResult.Success ->
                accountRepository.recordSyncSuccess(accountId, now)
            is SyncResult.PartialSuccess -> {
                accountRepository.recordSyncFailure(accountId, now)
                checkSyncFailureThreshold(accountId)
            }
            is SyncResult.AuthError -> {
                accountRepository.recordSyncFailure(accountId, now)
                // Skip threshold notification — showAuthErrorNotification is more specific
            }
            is SyncResult.Error -> {
                accountRepository.recordSyncFailure(accountId, now)
                checkSyncFailureThreshold(accountId)
            }
        }

        return result
    }

    /**
     * Check if an account's consecutive sync failures just hit the notification threshold.
     * Uses == (not >=) to fire exactly once at the threshold.
     */
    private suspend fun checkSyncFailureThreshold(accountId: Long) {
        val account = accountRepository.getAccountById(accountId) ?: return
        if (account.consecutiveSyncFailures == SYNC_FAILURE_NOTIFICATION_THRESHOLD) {
            notificationManager.showSyncFailureThresholdNotification(
                accountName = account.displayName ?: account.provider.displayName,
                failureCount = account.consecutiveSyncFailures
            )
        }
    }

    /**
     * Sync all enabled accounts using provider registry.
     *
     * For each account:
     * 1. Get provider from registry
     * 2. Skip local providers (don't need network sync)
     * 3. Load credentials from provider's credential provider
     * 4. Sync using provider-specific quirks
     */
    private suspend fun syncAll(forceFullSync: Boolean, trigger: SyncTrigger): SyncResult {
        val accounts = accountRepository.getEnabledAccounts()
        Log.d(TAG, "syncAll() found ${accounts.size} enabled accounts")

        if (accounts.isEmpty()) {
            Log.d(TAG, "No enabled accounts to sync")
            // Record session for debugging
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "No accounts",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            sessionBuilder.setError(ErrorType.AUTH, "no_accounts", "No enabled accounts found")
            syncSessionStore.add(sessionBuilder.build())
            return SyncResult.Success(
                calendarsSynced = 0,
                durationMs = 0
            )
        }

        // Filter to accounts with network providers (using AccountProvider enum)
        val networkAccounts = accounts.filter { account ->
            account.provider.requiresNetwork
        }

        if (networkAccounts.isEmpty()) {
            Log.d(TAG, "No network accounts to sync")
            // Record session for debugging
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "No network accounts",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            sessionBuilder.setError(ErrorType.AUTH, "no_network_accounts", "No network accounts found (${accounts.size} local only)")
            syncSessionStore.add(sessionBuilder.build())
            return SyncResult.Success(calendarsSynced = 0, durationMs = 0)
        }

        Log.d(TAG, "Syncing ${networkAccounts.size} network accounts")
        val startTime = System.currentTimeMillis()

        // Aggregate results from all accounts
        var totalCalendars = 0
        var totalPushCreated = 0
        var totalPushUpdated = 0
        var totalPushDeleted = 0
        var totalPullAdded = 0
        var totalPullUpdated = 0
        var totalPullDeleted = 0
        var totalConflicts = 0
        val allErrors = mutableListOf<org.onekash.kashcal.sync.engine.SyncError>()
        val allChanges = mutableListOf<org.onekash.kashcal.sync.model.SyncChange>()

        for ((index, account) in networkAccounts.withIndex()) {
            // Skip accounts that don't support CalDAV
            if (!account.provider.supportsCalDAV) {
                Log.d(TAG, "Skipping non-CalDAV account ${account.email.maskEmail()} (${account.provider})")
                continue
            }

            // Get credential provider from registry
            val credProvider = providerRegistry.getCredentialProvider(account.provider)
            if (credProvider == null) {
                Log.w(TAG, "No credential provider for account ${account.email.maskEmail()}, skipping")
                val sessionBuilder = SyncSessionBuilder(
                    calendarId = -1L,
                    calendarName = "Account: ${account.email.maskEmail()}",
                    syncType = SyncType.FULL,
                    triggerSource = trigger
                )
                sessionBuilder.setError(ErrorType.AUTH, "no_cred_provider", "Credential provider not found for ${account.provider}")
                syncSessionStore.add(sessionBuilder.build())
                continue
            }

            // Load credentials for this account
            val credentials = credProvider.getCredentials(account.id)
            if (credentials == null) {
                Log.w(TAG, "No credentials for account ${account.email.maskEmail()}, skipping")
                // Record session for debugging
                val sessionBuilder = SyncSessionBuilder(
                    calendarId = -1L,
                    calendarName = "Account: ${account.email.maskEmail()}",
                    syncType = SyncType.FULL,
                    triggerSource = trigger
                )
                sessionBuilder.setError(ErrorType.AUTH, "no_credentials", "Credentials not found for account")
                syncSessionStore.add(sessionBuilder.build())
                continue
            }

            // Get quirks for this provider
            val quirks = providerRegistry.getQuirksForAccount(account)
            if (quirks == null) {
                Log.w(TAG, "No quirks for account ${account.email.maskEmail()}, skipping")
                // Record session for debugging
                val sessionBuilder = SyncSessionBuilder(
                    calendarId = -1L,
                    calendarName = "Account: ${account.email.maskEmail()}",
                    syncType = SyncType.FULL,
                    triggerSource = trigger
                )
                sessionBuilder.setError(ErrorType.SERVER, "no_quirks", "Provider quirks not found for ${account.provider}")
                syncSessionStore.add(sessionBuilder.build())
                continue
            }

            // Create isolated client for this account (prevents credential race condition)
            val isolatedClient = calDavClientFactory.createClient(credentials, quirks)
            Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}***")

            // Sync this account with its quirks and isolated client
            val result = try {
                syncEngine.syncAccountWithQuirks(
                    account = account,
                    quirks = quirks,
                    forceFullSync = forceFullSync,
                    client = isolatedClient,
                    trigger = trigger
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancellation - rethrow to handle properly
                Log.d(TAG, "syncEngine cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "syncEngine threw exception for account ${account.email.maskEmail()}", e)
                throw e  // Re-throw to be caught by outer handler
            }

            // Record per-account sync metadata (same as single-account path)
            val now = System.currentTimeMillis()
            when (result) {
                is SyncResult.Success -> {
                    accountRepository.recordSyncSuccess(account.id, now)
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
                    accountRepository.recordSyncFailure(account.id, now)
                    checkSyncFailureThreshold(account.id)
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
                    accountRepository.recordSyncFailure(account.id, now)
                    checkSyncFailureThreshold(account.id)
                    Log.e(TAG, "Auth error for account ${account.email.maskEmail()}: ${result.message}")
                    allErrors.add(org.onekash.kashcal.sync.engine.SyncError(
                        phase = org.onekash.kashcal.sync.engine.SyncPhase.AUTH,
                        code = 401,
                        message = result.message
                    ))
                }
                is SyncResult.Error -> {
                    accountRepository.recordSyncFailure(account.id, now)
                    checkSyncFailureThreshold(account.id)
                    Log.e(TAG, "Sync error for account ${account.email.maskEmail()}: ${result.message}")
                    allErrors.add(org.onekash.kashcal.sync.engine.SyncError(
                        phase = org.onekash.kashcal.sync.engine.SyncPhase.SYNC,
                        code = result.code,
                        message = result.message
                    ))
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "syncAll complete in ${duration}ms: $totalCalendars calendars")

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
     * Convert SyncResult to WorkManager Result with output data.
     */
    private fun handleSyncResult(syncResult: SyncResult): Result {
        return when (syncResult) {
            is SyncResult.Success -> {
                Log.i(TAG, "Sync SUCCESS: ${syncResult.totalChanges} changes in ${syncResult.durationMs}ms")
                // Set sync changes for UI notification (snackbar, bottom sheet)
                if (syncResult.changes.isNotEmpty()) {
                    syncScheduler.setSyncChanges(syncResult.changes)
                }
                Result.success(createSuccessOutput(syncResult))
            }
            is SyncResult.PartialSuccess -> {
                Log.w(TAG, "Sync PARTIAL: ${syncResult.totalChanges} changes, ${syncResult.errors.size} errors")
                // Partial success is still considered success for WorkManager
                // The UI can check the error count in output data
                // Set sync changes for UI notification
                if (syncResult.changes.isNotEmpty()) {
                    syncScheduler.setSyncChanges(syncResult.changes)
                }
                Result.success(createPartialOutput(syncResult))
            }
            is SyncResult.AuthError -> {
                Log.e(TAG, "Sync AUTH ERROR: ${syncResult.message}")
                // Auth errors are not retryable - user needs to re-authenticate
                Result.failure(createErrorOutput(syncResult.message))
            }
            is SyncResult.Error -> {
                Log.e(TAG, "Sync ERROR: ${syncResult.message} (retryable=${syncResult.isRetryable})")
                if (syncResult.isRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(createErrorOutput(syncResult.message))
                }
            }
        }
    }

    /**
     * Schedule reminders for events that were added or modified during sync.
     *
     * For NEW events: schedules reminders for all occurrences in the schedule window.
     * For MODIFIED events: cancels existing reminders and reschedules (handles time changes).
     * For DELETED events: skipped (reminders cleaned up with event deletion).
     *
     * Follows same pattern as EventCoordinator.scheduleRemindersForEvent() for consistency.
     * Per Android best practice: uses absolute trigger times with AlarmManager, so must
     * recalculate when event times change.
     */
    private suspend fun scheduleRemindersForSyncedEvents(changes: List<SyncChange>) {
        var scheduled = 0
        var skipped = 0

        for (change in changes) {
            // Skip DELETED events (reminders already cleaned up)
            if (change.type == ChangeType.DELETED || change.eventId == null) {
                skipped++
                continue
            }

            try {
                val event = eventReader.getEventById(change.eventId)
                if (event == null) {
                    Log.w(TAG, "Event ${change.eventId} not found for reminder scheduling")
                    skipped++
                    continue
                }

                // For MODIFIED events, cancel existing reminders first (before null check,
                // handles transition from having reminders to no reminders)
                if (change.type == ChangeType.MODIFIED) {
                    reminderScheduler.cancelRemindersForEvent(event.id)
                }

                // Skip events without reminders
                if (event.reminders.isNullOrEmpty()) {
                    skipped++
                    continue
                }

                val calendar = eventReader.getCalendarById(event.calendarId)
                if (calendar == null) {
                    Log.w(TAG, "Calendar ${event.calendarId} not found for event ${event.id}")
                    skipped++
                    continue
                }

                // Get occurrences - handle exception events specially
                val occurrences = if (event.originalEventId != null) {
                    // Exception event - get the linked occurrence by exception event ID
                    listOfNotNull(eventReader.getOccurrenceByExceptionEventId(event.id))
                } else {
                    // Regular/master event - get all occurrences in schedule window
                    eventReader.getOccurrencesForEventInScheduleWindow(event.id)
                }

                if (occurrences.isEmpty()) {
                    skipped++
                    continue
                }

                reminderScheduler.scheduleRemindersForEvent(
                    event = event,
                    occurrences = occurrences,
                    calendarColor = calendar.color ?: 0xFF2196F3.toInt()
                )
                scheduled++
            } catch (e: Exception) {
                // Log but don't fail sync for reminder scheduling errors
                Log.e(TAG, "Failed to schedule reminders for event ${change.eventId}: ${e.message}")
                skipped++
            }
        }

        Log.i(TAG, "Reminder scheduling complete: $scheduled scheduled, $skipped skipped")
    }

    private fun createSuccessOutput(result: SyncResult.Success): Data {
        return Data.Builder()
            .putInt(KEY_CALENDARS_SYNCED, result.calendarsSynced)
            .putInt(KEY_EVENTS_PUSHED, result.eventsPushedCreated + result.eventsPushedUpdated + result.eventsPushedDeleted)
            .putInt(KEY_EVENTS_PULLED, result.eventsPulledAdded + result.eventsPulledUpdated + result.eventsPulledDeleted)
            .putInt(KEY_CONFLICTS_RESOLVED, result.conflictsResolved)
            .putLong(KEY_DURATION_MS, result.durationMs)
            .build()
    }

    private fun createPartialOutput(result: SyncResult.PartialSuccess): Data {
        return Data.Builder()
            .putInt(KEY_CALENDARS_SYNCED, result.calendarsSynced)
            .putInt(KEY_EVENTS_PUSHED, result.eventsPushedCreated + result.eventsPushedUpdated + result.eventsPushedDeleted)
            .putInt(KEY_EVENTS_PULLED, result.eventsPulledAdded + result.eventsPulledUpdated + result.eventsPulledDeleted)
            .putInt(KEY_CONFLICTS_RESOLVED, result.conflictsResolved)
            .putLong(KEY_DURATION_MS, result.durationMs)
            .putString(KEY_ERROR_MESSAGE, "Partial sync: ${result.errors.size} errors")
            .build()
    }

    private fun createErrorOutput(message: String): Data {
        return Data.Builder()
            .putString(KEY_ERROR_MESSAGE, message)
            .build()
    }

    /**
     * Parse SyncTrigger from input data.
     * Returns BACKGROUND_PERIODIC if missing or invalid (backward compatibility).
     */
    private fun parseTrigger(inputData: Data): SyncTrigger {
        val triggerStr = inputData.getString(KEY_SYNC_TRIGGER)
        return if (triggerStr != null) {
            try {
                SyncTrigger.valueOf(triggerStr)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid trigger string: $triggerStr, defaulting to BACKGROUND_PERIODIC")
                SyncTrigger.BACKGROUND_PERIODIC
            }
        } else {
            SyncTrigger.BACKGROUND_PERIODIC
        }
    }
}

private const val MAX_RETRY_ATTEMPTS = 3
