package org.onekash.kashcal.sync.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.changes.RecentChangesRecorder
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.notification.ExpiredCalendarScope
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.provider.icloud.ICloudUrlMigration
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.session.ErrorType
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.util.maskEmail
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.util.concurrent.TimeUnit

/**
 * Runs CalDAV sync for WorkManager: the periodic background job and the one-shot, expedited,
 * per-account and per-calendar requests [SyncScheduler] enqueues.
 *
 * Input data picks the scope (all accounts, one account, one calendar) and whether the run is
 * user-visible; only a user-visible run goes foreground with a progress notification. After the
 * sync it refreshes widgets when anything changed, schedules reminders for synced events, records
 * them in Recent changes ([RecentChangesRecorder]) and prunes old sync logs. Results go to
 * WorkManager output data; the recorded entries go to [SyncScheduler.setSyncChanges].
 * The worker adds its own sync-history entries ([SyncSessionStore]) only for a throw, an account
 * it couldn't set up, or no account to sync. A periodic run (always the all-accounts scope) never
 * returns failure, which would stop the periodic spec for good; the catch in [doWork] says why.
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
    private val iCloudUrlMigration: ICloudUrlMigration,
    private val eventsDao: EventsDao,
    private val dataStore: KashCalDataStore,
    private val recentChangesRecorder: RecentChangesRecorder
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

        private const val SYNC_LOG_RETENTION_DAYS = 7

        /** Builds input data for a sync of every account. */
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

        /** Builds input data for a sync of one calendar. */
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

        /** Builds input data for a sync of one account. */
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

    /**
     * Supplies the notification for expedited work on API < 31 (Android 10-11), where WorkManager
     * runs it as a foreground service and asks for one here.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return notificationManager.createForegroundInfo(
            progress = applicationContext.getString(R.string.sync_banner_syncing)
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL
        val forceFullSync = inputData.getBoolean(KEY_FORCE_FULL_SYNC, false)
        val showNotification = inputData.getBoolean(KEY_SHOW_NOTIFICATION, false)
        val trigger = parseTrigger(inputData)

        Log.i(TAG, "Starting sync: type=$syncType, force=$forceFullSync, trigger=${trigger.name}, attempt=${runAttemptCount + 1}")

        // Go foreground (the OS progress notification) only for a user-visible sync. Silent
        // syncs (app open, resume, periodic) must not post "Syncing…" for an action the user
        // didn't take.
        if (showNotification) {
            try {
                val foregroundInfo = notificationManager.createForegroundInfo(
                    progress = applicationContext.getString(R.string.sync_banner_syncing),
                    cancelIntent = createCancelPendingIntent()
                )
                setForeground(foregroundInfo)
            } catch (e: Exception) {
                // setForeground may fail when the work isn't expedited; the sync still runs.
                Log.d(TAG, "Could not set foreground (non-expedited work): ${e.message}")
            }
        }

        try {
            // Recover operations left IN_PROGRESS by a crashed sync. The cutoff is 1 hour;
            // an operation in progress completes in under 2 minutes.
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

            // One-time iCloud URL migration: regional hosts (p180-caldav.icloud.com) become
            // caldav.icloud.com, so iCloud's server rotation doesn't strand stored URLs.
            val migrated = iCloudUrlMigration.migrateIfNeeded()
            if (migrated) {
                Log.i(TAG, "iCloud URLs normalized to canonical form")
            }

            // Pending-operation retry lifecycle.
            val now = System.currentTimeMillis()
            val thirtyDaysAgo = now - PendingOperation.OPERATION_LIFETIME_MS

            // A forced full sync resets every FAILED and ABANDONED operation, and its
            // lifetime_reset_at, for a fresh 30-day window. Runs first.
            if (forceFullSync) {
                val failedResetCount = pendingOperationsDao.resetAllFailed(now)
                if (failedResetCount > 0) {
                    Log.i(TAG, "Reset $failedResetCount failed operations for retry (force full sync)")
                }
            }

            // Abandon operations past the 30-day lifetime; ones a forced sync just reset aren't
            // expired. getExpiredOperations isn't scoped to this worker's account or calendar
            // and runs on every sync, so overlapping syncs can read the same list.
            // abandonOperation is a compare-and-set: only the run that transitions an op keeps
            // it, so a concurrent sync that already abandoned it doesn't notify again.
            val expiredOps = pendingOperationsDao.getExpiredOperations(thirtyDaysAgo)
            val abandonedOps = mutableListOf<PendingOperation>()
            for (op in expiredOps) {
                val transitioned = pendingOperationsDao.abandonOperation(
                    op.id,
                    "Operation exceeded 30-day lifetime without user interaction",
                    now
                )
                if (transitioned > 0) abandonedOps.add(op)
            }
            if (abandonedOps.isNotEmpty()) {
                Log.w(TAG, "Abandoned ${abandonedOps.size} operations exceeding 30-day lifetime")
                notificationManager.showOperationExpiredNotification(
                    abandonedOps.size,
                    resolveExpiredCalendarScope(abandonedOps)
                )
            }

            // Retry FAILED operations older than 24 hours, except expired ones.
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

            if (showNotification) {
                notificationManager.showCompletionNotification(syncResult, showOnlyOnChanges = false)
            }

            notificationManager.cancelProgressNotification()

            if (syncResult.hasChanges()) {
                Log.d(TAG, "Updating widgets after sync with changes")
                widgetUpdateManager.updateAllWidgets()
            }

            // Reminders and the Recent changes log take the pulled changes of a successful sync.
            val changes = when (syncResult) {
                is SyncResult.Success -> syncResult.changes
                is SyncResult.PartialSuccess -> syncResult.changes
                else -> emptyList()
            }
            if (changes.isNotEmpty()) {
                scheduleRemindersForSyncedEvents(changes)
                recordRecentChanges(changes)
            }

            // Delete sync logs past the 7-day retention; a cleanup failure doesn't fail the sync.
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
            // Rethrow so the coroutine cancels.
            Log.d(TAG, "Sync cancelled")
            notificationManager.cancelProgressNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            notificationManager.cancelProgressNotification()

            val errorMessage = e.message ?: e.javaClass.simpleName

            // Record the failure in sync history, with placeholder calendar values: the error
            // may come from before the sync engine picked a calendar.
            val sessionBuilder = SyncSessionBuilder(
                calendarId = -1L,
                calendarName = "Exception: ${e.javaClass.simpleName}",
                syncType = SyncType.FULL,
                triggerSource = trigger
            )
            val errorType = when (e) {
                is java.net.SocketTimeoutException -> ErrorType.TIMEOUT
                is java.io.IOException -> ErrorType.NETWORK
                else -> ErrorType.SERVER  // Any other error, server or unknown
            }
            sessionBuilder.setError(errorType, "worker_exception", errorMessage)
            syncSessionStore.add(sessionBuilder.build())
            Log.d(TAG, "Recorded sync session with error: ${errorType.name}")

            if (showNotification) {
                notificationManager.showErrorNotification("Sync Failed", errorMessage)
            }

            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else if (isPeriodicRun()) {
                // Retries are spent, but failure is terminal for a periodic spec: WorkManager
                // stops scheduling it, and only creating an account re-arms periodic sync. A
                // throw from outside the per-account loop (the pending-operation lifecycle
                // sweep, for example) repeats on every attempt and lands here, so background
                // sync would be dead for good. The user sees the error in sync history,
                // recorded above; nothing observes the periodic work's own state, so this
                // output data is diagnostic. The next period retries.
                Result.success(createErrorOutput(errorMessage))
            } else {
                // A one-shot has no future run to lose, and the screen that asked for the sync
                // renders SyncStatus.Failed.
                Result.failure(createErrorOutput(errorMessage))
            }
        }
    }

    /**
     * Returns whether this run is the periodic background job. Only the periodic request carries
     * [SyncScheduler.TAG_PERIODIC]; the sync trigger can't tell, since the expedited and
     * per-calendar requests inherit the [SyncTrigger.BACKGROUND_PERIODIC] default.
     */
    private fun isPeriodicRun(): Boolean = SyncScheduler.TAG_PERIODIC in tags

    /** Returns the foreground notification's cancel intent, or null if it can't be made. */
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

    /** Syncs one calendar with a client built from its account's credentials and quirks. */
    private suspend fun syncCalendar(calendarId: Long, forceFullSync: Boolean, trigger: SyncTrigger): SyncResult {
        val calendar = calendarRepository.getCalendarById(calendarId)
        if (calendar == null) {
            Log.w(TAG, "Calendar $calendarId not found")
            return SyncResult.Error(-1, "Calendar not found", false)
        }

        val account = accountRepository.getAccountById(calendar.accountId)
        if (account == null) {
            Log.w(TAG, "Account ${calendar.accountId} not found for calendar")
            return SyncResult.Error(-1, "Account not found", false)
        }

        val credProvider = providerRegistry.getCredentialProvider(account.provider)
        if (credProvider == null) {
            Log.w(TAG, "No credential provider for account ${account.email.maskEmail()}")
            return SyncResult.Error(-1, "No credential provider for ${account.provider}", false)
        }

        val credentials = credProvider.getCredentials(account.id)
        if (credentials == null) {
            Log.w(TAG, "No credentials for account ${account.email.maskEmail()}")
            return SyncResult.AuthError("Credentials not found")
        }

        val quirks = providerRegistry.getQuirksForAccount(account)
        if (quirks == null) {
            Log.w(TAG, "No quirks for account ${account.email.maskEmail()}")
            return SyncResult.Error(-1, "No quirks for ${account.provider}", false)
        }

        // A client of its own per account, so a concurrent sync can't swap its credentials.
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
     * Syncs every calendar of one account and records the outcome on the account.
     *
     * A disabled or non-CalDAV account returns an empty success. A missing credential provider,
     * credentials or quirks is recorded in sync history and returns an error
     * ([SyncResult.AuthError] for missing credentials). A throw from the engine records a sync
     * failure on the account and propagates.
     */
    private suspend fun syncAccount(accountId: Long, forceFullSync: Boolean, trigger: SyncTrigger): SyncResult {
        val account = accountRepository.getAccountById(accountId)
        if (account == null) {
            Log.w(TAG, "Account $accountId not found")
            return SyncResult.Error(-1, "Account not found", false)
        }

        Log.d(TAG, "Syncing account: ${account.email.maskEmail()}")

        if (!account.isEnabled) {
            Log.d(TAG, "Account $accountId is disabled, skipping sync")
            return SyncResult.Success(calendarsSynced = 0, durationMs = 0)
        }

        if (!account.provider.supportsCalDAV) {
            Log.d(TAG, "Skipping non-CalDAV account ${account.email.maskEmail()} (${account.provider})")
            return SyncResult.Success(calendarsSynced = 0, durationMs = 0)
        }

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

        // A client of its own per account, so a concurrent sync can't swap its credentials.
        val isolatedClient = calDavClientFactory.createClient(credentials, quirks)
        Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}***")

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
            throw e
        }

        // Only a full success records a sync success; a partial one counts as a failure.
        val now = System.currentTimeMillis()
        when (result) {
            is SyncResult.Success ->
                accountRepository.recordSyncSuccess(accountId, now)
            is SyncResult.PartialSuccess ->
                accountRepository.recordSyncFailure(accountId, now)
            is SyncResult.AuthError ->
                accountRepository.recordSyncFailure(accountId, now)
            is SyncResult.Error ->
                accountRepository.recordSyncFailure(accountId, now)
        }

        return result
    }

    /**
     * Returns which calendars the expired operations belong to, for the expired-operation
     * notification: [ExpiredCalendarScope.Single] with the calendar's name,
     * [ExpiredCalendarScope.Multiple] with the count, or [ExpiredCalendarScope.Unknown] when
     * any calendar or its name can't be resolved (the event was hard-deleted, for example).
     *
     * Takes the op's [PendingOperation.sourceCalendarId] when set and falls back to the event's
     * current calendarId, matching the COALESCE(source_calendar_id, e.calendar_id) scoping in
     * [PendingOperationsDao.getConflictOperationsForCalendar]: a MOVE or synced-to-local DELETE
     * has already moved the event row to the target, but the stuck operation concerns the source
     * calendar.
     */
    private suspend fun resolveExpiredCalendarScope(expiredOps: List<PendingOperation>): ExpiredCalendarScope {
        // Collect every distinct calendar; an early exit at size > 1 would cap Multiple's
        // count at 2.
        val calendarIds = mutableSetOf<Long>()
        for (op in expiredOps) {
            val calendarId = op.sourceCalendarId ?: eventsDao.getById(op.eventId)?.calendarId
                ?: return ExpiredCalendarScope.Unknown
            calendarIds.add(calendarId)
        }
        if (calendarIds.size > 1) return ExpiredCalendarScope.Multiple(calendarIds.size)
        val calendarId = calendarIds.singleOrNull() ?: return ExpiredCalendarScope.Unknown
        val name = calendarRepository.getCalendarById(calendarId)?.displayName
            ?: return ExpiredCalendarScope.Unknown
        return ExpiredCalendarScope.Single(name)
    }

    /**
     * Syncs every enabled network account and merges their results.
     *
     * Local-only and non-CalDAV accounts are skipped. An account missing its credential
     * provider, credentials or quirks is recorded in sync history and skipped. An account whose
     * sync throws or fails adds an error, so the merged result is [SyncResult.PartialSuccess]
     * when any account failed.
     */
    private suspend fun syncAll(forceFullSync: Boolean, trigger: SyncTrigger): SyncResult {
        val accounts = accountRepository.getEnabledAccounts()
        Log.d(TAG, "syncAll() found ${accounts.size} enabled accounts")

        if (accounts.isEmpty()) {
            Log.d(TAG, "No enabled accounts to sync")
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

        val networkAccounts = accounts.filter { account ->
            account.provider.requiresNetwork
        }

        if (networkAccounts.isEmpty()) {
            Log.d(TAG, "No network accounts to sync")
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

        for ((_, account) in networkAccounts.withIndex()) {
            if (!account.provider.supportsCalDAV) {
                Log.d(TAG, "Skipping non-CalDAV account ${account.email.maskEmail()} (${account.provider})")
                continue
            }

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

            val credentials = credProvider.getCredentials(account.id)
            if (credentials == null) {
                Log.w(TAG, "No credentials for account ${account.email.maskEmail()}, skipping")
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

            val quirks = providerRegistry.getQuirksForAccount(account)
            if (quirks == null) {
                Log.w(TAG, "No quirks for account ${account.email.maskEmail()}, skipping")
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

            // A client of its own per account, so a concurrent sync can't swap its credentials.
            val isolatedClient = calDavClientFactory.createClient(credentials, quirks)
            Log.d(TAG, "Created isolated client for: ${credentials.username.take(3)}***")

            val result = try {
                syncEngine.syncAccountWithQuirks(
                    account = account,
                    quirks = quirks,
                    forceFullSync = forceFullSync,
                    client = isolatedClient,
                    trigger = trigger
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrow so the coroutine cancels.
                Log.d(TAG, "syncEngine cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "syncEngine threw exception for account ${account.email.maskEmail()}", e)
                val now = System.currentTimeMillis()
                accountRepository.recordSyncFailure(account.id, now)
                allErrors.add(org.onekash.kashcal.sync.engine.SyncError(
                    phase = org.onekash.kashcal.sync.engine.SyncPhase.SYNC,
                    message = e.message ?: e.javaClass.simpleName
                ))
                continue
            }

            // Same per-account recording as syncAccount.
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
                    Log.e(TAG, "Auth error for account ${account.email.maskEmail()}: ${result.message}")
                    allErrors.add(org.onekash.kashcal.sync.engine.SyncError(
                        phase = org.onekash.kashcal.sync.engine.SyncPhase.AUTH,
                        code = 401,
                        message = result.message
                    ))
                }
                is SyncResult.Error -> {
                    accountRepository.recordSyncFailure(account.id, now)
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
     * Maps [syncResult] to a WorkManager result with output data. A partial success is a success;
     * an auth error fails; an error retries only when retryable and attempts remain.
     */
    private fun handleSyncResult(syncResult: SyncResult): Result {
        return when (syncResult) {
            is SyncResult.Success -> {
                Log.i(TAG, "Sync SUCCESS: ${syncResult.totalChanges} changes in ${syncResult.durationMs}ms")
                Result.success(createSuccessOutput(syncResult))
            }
            is SyncResult.PartialSuccess -> {
                Log.w(TAG, "Sync PARTIAL: ${syncResult.totalChanges} changes, ${syncResult.errors.size} errors")
                // The error count goes in the output data.
                Result.success(createPartialOutput(syncResult))
            }
            is SyncResult.AuthError -> {
                Log.e(TAG, "Sync AUTH ERROR: ${syncResult.message}")
                // Not retryable: the user has to sign in again.
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
     * Records [changes] in the Recent changes log and publishes what was recorded for the sync
     * snackbar. A failure is logged and skipped; it never fails the sync.
     */
    private suspend fun recordRecentChanges(changes: List<SyncChange>) {
        val recorded = try {
            recentChangesRecorder.record(changes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recording recent changes failed, continuing", e)
            return
        }
        if (recorded.isNotEmpty()) syncScheduler.setSyncChanges(recorded)
    }

    /**
     * Schedules reminders for events the sync added or modified.
     *
     * - NEW: schedules every occurrence in the schedule window. A new event with no reminders and
     *   no alarms gets the user's default reminder unless it is off, except on initial sync.
     * - MODIFIED: cancels the existing reminders and reschedules. AlarmManager triggers are
     *   absolute times, so they must be recalculated when event times change.
     * - DELETED: skipped; reminders are cleaned up with the event.
     *
     * A failure for one event is logged and skipped; it never fails the sync.
     */
    private suspend fun scheduleRemindersForSyncedEvents(changes: List<SyncChange>) {
        var scheduled = 0
        var skipped = 0
        var defaultsApplied = 0

        // Read the default reminder settings once, before the loop.
        val defaultTimedMinutes = dataStore.defaultReminderMinutes.first()
        val defaultAllDayMinutes = dataStore.defaultAllDayReminder.first()

        for (change in changes) {
            if (change.type == ChangeType.DELETED || change.eventId == null) {
                skipped++
                continue
            }

            try {
                var event = eventReader.getEventById(change.eventId)
                if (event == null) {
                    Log.w(TAG, "Event ${change.eventId} not found for reminder scheduling")
                    skipped++
                    continue
                }

                // Cancel before the empty-reminders check, so an edit that removed every
                // reminder still clears the old alarms.
                if (change.type == ChangeType.MODIFIED) {
                    reminderScheduler.cancelRemindersForEvent(event.id)
                }

                if (change.type == ChangeType.NEW && !change.isFromInitialSync) {
                    val defaultMinutes = if (event.isAllDay) defaultAllDayMinutes else defaultTimedMinutes
                    val shouldApplyDefaults = event.reminders.isNullOrEmpty() &&
                        event.alarmCount == 0 &&
                        defaultMinutes != KashCalDataStore.REMINDER_OFF

                    if (shouldApplyDefaults) {
                        try {
                            val reminder = ContactEventUtils.minutesToIsoDuration(defaultMinutes)
                            val remindersJson = Json.encodeToString(listOf(reminder))
                            eventsDao.updateReminders(event.id, remindersJson, System.currentTimeMillis())
                            event = event.copy(reminders = listOf(reminder))
                            defaultsApplied++
                            Log.d(TAG, "Applied default reminder ($defaultMinutes min) to new event ${event.id}: ${event.title}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to apply default reminder to event ${event.id}", e)
                            // The event goes without a reminder; the sync continues.
                        }
                    }
                }

                // Checked after the default may have been applied.
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

                // An exception has one linked occurrence; a master has every occurrence in the
                // schedule window.
                val occurrences = if (event.originalEventId != null) {
                    listOfNotNull(eventReader.getOccurrenceByExceptionEventId(event.id))
                } else {
                    eventReader.getOccurrencesForEventInScheduleWindow(
                        event.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS
                    )
                }

                if (occurrences.isEmpty()) {
                    skipped++
                    continue
                }

                reminderScheduler.scheduleRemindersForEvent(
                    event = event,
                    occurrences = occurrences,
                    calendarColor = calendar.color
                )
                scheduled++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule reminders for event ${change.eventId}: ${e.message}")
                skipped++
            }
        }

        if (defaultsApplied > 0) {
            Log.i(TAG, "Reminder scheduling complete: $scheduled scheduled, $skipped skipped, $defaultsApplied defaults applied")
        } else {
            Log.i(TAG, "Reminder scheduling complete: $scheduled scheduled, $skipped skipped")
        }
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
     * Reads the [SyncTrigger] from input data, or [SyncTrigger.BACKGROUND_PERIODIC] when it is
     * missing or unknown (work enqueued without one).
     */
    private fun parseTrigger(inputData: Data): SyncTrigger {
        val triggerStr = inputData.getString(KEY_SYNC_TRIGGER)
        return if (triggerStr != null) {
            try {
                SyncTrigger.valueOf(triggerStr)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Invalid trigger string: $triggerStr, defaulting to BACKGROUND_PERIODIC")
                SyncTrigger.BACKGROUND_PERIODIC
            }
        } else {
            SyncTrigger.BACKGROUND_PERIODIC
        }
    }
}

private const val MAX_RETRY_ATTEMPTS = 3
