package org.onekash.kashcal.sync.scheduler

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.worker.CalDavSyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncScheduler manages WorkManager-based synchronization scheduling.
 *
 * Provides:
 * - Periodic background sync (configurable interval, minimum 15 min per Android)
 * - One-shot sync for user-initiated refresh
 * - Expedited sync for immediate needs (e.g., push notification)
 * - Calendar-specific and account-specific sync
 * - Sync status observation
 *
 * Per Android WorkManager best practices:
 * - Uses constraints for network and battery
 * - Unique work names prevent duplicate schedules
 * - Exponential backoff for retries
 * - Respects Doze mode via WorkManager
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Whether the current/next sync should show UI banner.
     * Single Source of Truth for banner visibility across all ViewModels.
     * Set by caller before requestImmediateSync(), read by UI observers.
     * Reset to false after sync completes (success/failure).
     */
    private val _showBannerForSync = MutableStateFlow(false)
    val showBannerForSync: StateFlow<Boolean> = _showBannerForSync.asStateFlow()

    /**
     * Set banner visibility for the current/next sync.
     * Call this BEFORE requestImmediateSync() to show banner feedback.
     */
    fun setShowBannerForSync(show: Boolean) {
        Log.d(TAG, "setShowBannerForSync: $show")
        _showBannerForSync.value = show
    }

    /**
     * Reset banner flag after sync completes.
     * Called by UI layer after handling success/failure.
     */
    fun resetBannerFlag() {
        Log.d(TAG, "resetBannerFlag: resetting to false")
        _showBannerForSync.value = false
    }

    /**
     * Sync changes from most recent sync (for UI notification).
     * Populated by CalDavSyncWorker after sync completes.
     * Observed by HomeViewModel to show snackbar.
     */
    private val _lastSyncChanges = MutableStateFlow<List<SyncChange>>(emptyList())
    val lastSyncChanges: StateFlow<List<SyncChange>> = _lastSyncChanges.asStateFlow()

    /**
     * Set sync changes after sync completes.
     * Called by CalDavSyncWorker with changes from PullStrategy.
     */
    fun setSyncChanges(changes: List<SyncChange>) {
        Log.d(TAG, "setSyncChanges: ${changes.size} changes")
        _lastSyncChanges.value = changes
    }

    /**
     * Clear sync changes after UI consumed them.
     * Called after snackbar/bottom sheet dismissal.
     */
    fun clearSyncChanges() {
        Log.d(TAG, "clearSyncChanges: clearing")
        _lastSyncChanges.value = emptyList()
    }

    companion object {
        private const val TAG = "SyncScheduler"

        // Work names
        const val PERIODIC_SYNC_WORK = "periodic_sync"
        const val ONE_SHOT_SYNC_WORK = "one_shot_sync"
        const val EXPEDITED_SYNC_WORK = "expedited_sync"

        // Intervals
        const val MIN_SYNC_INTERVAL_MINUTES = 15L  // Android minimum

        // Tags for work identification
        const val TAG_SYNC = "sync"
        const val TAG_PERIODIC = "periodic"
        const val TAG_ONE_SHOT = "one_shot"
        const val TAG_EXPEDITED = "expedited"
    }

    /**
     * Standard network constraints for sync operations.
     * Requires any network connection.
     */
    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Relaxed constraints for expedited work.
     * Still requires network but runs immediately.
     */
    private val expeditedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Schedule periodic background sync for all accounts.
     *
     * Uses KEEP existing policy to avoid rescheduling if already scheduled.
     * WorkManager ensures this runs even across device restarts.
     *
     * @param intervalMinutes Sync interval (minimum 15 per Android)
     * @param forceFullSync If true, ignores ctag/sync-token and fetches all events
     */
    fun schedulePeriodicSync(
        intervalMinutes: Long,
        forceFullSync: Boolean = false
    ) {
        val actualInterval = maxOf(intervalMinutes, MIN_SYNC_INTERVAL_MINUTES)

        Log.i(TAG, "Scheduling periodic sync every $actualInterval minutes")

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync)

        val periodicWork = PeriodicWorkRequestBuilder<CalDavSyncWorker>(
            actualInterval, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag(TAG_PERIODIC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }

    /**
     * Cancel periodic sync.
     * Call when user disables sync or removes all accounts.
     */
    fun cancelPeriodicSync() {
        Log.i(TAG, "Cancelling periodic sync")
        workManager.cancelUniqueWork(PERIODIC_SYNC_WORK)
    }

    /**
     * Update periodic sync interval.
     * Replaces existing schedule with new interval.
     *
     * @param intervalMinutes New interval (minimum 15 per Android)
     */
    fun updatePeriodicSyncInterval(intervalMinutes: Long) {
        val actualInterval = maxOf(intervalMinutes, MIN_SYNC_INTERVAL_MINUTES)

        Log.i(TAG, "Updating periodic sync interval to $actualInterval minutes")

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)

        val periodicWork = PeriodicWorkRequestBuilder<CalDavSyncWorker>(
            actualInterval, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag(TAG_PERIODIC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )
    }

    /**
     * Request immediate sync (one-shot, user-initiated).
     * Queues sync to run as soon as network is available.
     *
     * @param forceFullSync If true, ignores ctag/sync-token
     * @param trigger The trigger source for sync history tracking
     * @return UUID of the work request for status tracking
     */
    fun requestImmediateSync(
        forceFullSync: Boolean = false,
        trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL
    ): java.util.UUID {
        Log.i(TAG, "Requesting immediate sync (force=$forceFullSync, trigger=${trigger.name})")

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync, trigger = trigger)

        val oneShotWork = OneTimeWorkRequestBuilder<CalDavSyncWorker>()
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag(TAG_ONE_SHOT)
            .build()

        workManager.enqueueUniqueWork(
            ONE_SHOT_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            oneShotWork
        )

        return oneShotWork.id
    }

    /**
     * Request expedited sync (for push notifications or app foreground).
     * Uses expedited work to run immediately if possible.
     *
     * Note: Expedited work has quotas. If quota exceeded, falls back to regular.
     *
     * @param forceFullSync If true, ignores ctag/sync-token
     * @return UUID of the work request
     */
    fun requestExpeditedSync(forceFullSync: Boolean = false): java.util.UUID {
        Log.i(TAG, "Requesting expedited sync (force=$forceFullSync)")

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync)

        val expeditedWork = OneTimeWorkRequestBuilder<CalDavSyncWorker>()
            .setConstraints(expeditedConstraints)
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_SYNC)
            .addTag(TAG_EXPEDITED)
            .build()

        workManager.enqueueUniqueWork(
            EXPEDITED_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            expeditedWork
        )

        return expeditedWork.id
    }

    /**
     * Sync a specific calendar.
     * Useful for calendar-specific refresh or after local changes.
     *
     * @param calendarId The calendar to sync
     * @param forceFullSync If true, ignores sync-token
     * @return UUID of the work request
     */
    fun syncCalendar(calendarId: Long, forceFullSync: Boolean = false): java.util.UUID {
        Log.i(TAG, "Requesting calendar sync: calendarId=$calendarId")

        val inputData = CalDavSyncWorker.createCalendarSyncInput(calendarId, forceFullSync)
        val workName = "sync_calendar_$calendarId"

        val work = OneTimeWorkRequestBuilder<CalDavSyncWorker>()
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag("calendar_$calendarId")
            .build()

        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            work
        )

        return work.id
    }

    /**
     * Sync all calendars for a specific account.
     *
     * @param accountId The account to sync
     * @param forceFullSync If true, ignores ctag/sync-token
     * @return UUID of the work request
     */
    fun syncAccount(accountId: Long, forceFullSync: Boolean = false): java.util.UUID {
        Log.i(TAG, "Requesting account sync: accountId=$accountId")

        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId, forceFullSync)
        val workName = "sync_account_$accountId"

        val work = OneTimeWorkRequestBuilder<CalDavSyncWorker>()
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag("account_$accountId")
            .build()

        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            work
        )

        return work.id
    }

    /**
     * Cancel all sync work (periodic and one-shot).
     */
    fun cancelAllSync() {
        Log.i(TAG, "Cancelling all sync work")
        workManager.cancelAllWorkByTag(TAG_SYNC)
    }

    /**
     * Cancel sync for a specific calendar.
     */
    fun cancelCalendarSync(calendarId: Long) {
        Log.i(TAG, "Cancelling sync for calendar: $calendarId")
        workManager.cancelUniqueWork("sync_calendar_$calendarId")
    }

    /**
     * Cancel sync for a specific account.
     */
    fun cancelAccountSync(accountId: Long) {
        Log.i(TAG, "Cancelling sync for account: $accountId")
        workManager.cancelUniqueWork("sync_account_$accountId")
    }

    /**
     * Observe sync status for immediate sync.
     * Returns Flow of SyncStatus updates.
     */
    fun observeImmediateSyncStatus(): Flow<SyncStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(ONE_SHOT_SYNC_WORK)
            .map { workInfoList ->
                workInfoList.firstOrNull()?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /**
     * Observe sync status for periodic sync.
     */
    fun observePeriodicSyncStatus(): Flow<SyncStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_SYNC_WORK)
            .map { workInfoList ->
                workInfoList.firstOrNull()?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /**
     * Observe sync status for expedited sync.
     */
    fun observeExpeditedSyncStatus(): Flow<SyncStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(EXPEDITED_SYNC_WORK)
            .map { workInfoList ->
                workInfoList.firstOrNull()?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /**
     * Observe sync status by work UUID.
     */
    fun observeSyncStatus(workId: java.util.UUID): Flow<SyncStatus> {
        return workManager.getWorkInfoByIdFlow(workId)
            .map { workInfo ->
                workInfo?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /**
     * Get current periodic sync info (for settings UI).
     */
    suspend fun getPeriodicSyncInfo(): PeriodicSyncInfo? {
        val workInfo = workManager.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).get()
            .firstOrNull() ?: return null

        return PeriodicSyncInfo(
            isEnabled = workInfo.state != WorkInfo.State.CANCELLED,
            state = workInfo.toSyncStatus(),
            nextScheduledRunTime = workInfo.nextScheduleTimeMillis
        )
    }

    /**
     * Check if periodic sync is currently scheduled.
     */
    fun isPeriodicSyncEnabled(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).get()
        return workInfos.any { it.state != WorkInfo.State.CANCELLED }
    }

    /**
     * Prune completed work to free up WorkManager database.
     */
    fun pruneCompletedWork() {
        Log.d(TAG, "Pruning completed work")
        workManager.pruneWork()
    }
}

/**
 * Sync operation status for UI observation.
 */
sealed class SyncStatus {
    /** No sync in progress or scheduled */
    data object Idle : SyncStatus()

    /** Sync is queued and waiting for constraints */
    data object Enqueued : SyncStatus()

    /** Sync is currently running */
    data object Running : SyncStatus()

    /** Sync completed successfully (may include partial errors) */
    data class Succeeded(
        val calendarsSynced: Int = 0,
        val eventsPushed: Int = 0,
        val eventsPulled: Int = 0,
        val durationMs: Long = 0,
        val errorMessage: String? = null
    ) : SyncStatus()

    /** Sync failed */
    data class Failed(
        val errorMessage: String?
    ) : SyncStatus()

    /** Sync was cancelled */
    data object Cancelled : SyncStatus()

    /** Sync is blocked (e.g., no network) */
    data object Blocked : SyncStatus()
}

/**
 * Information about periodic sync schedule.
 */
data class PeriodicSyncInfo(
    val isEnabled: Boolean,
    val state: SyncStatus,
    val nextScheduledRunTime: Long
)

/**
 * Extension to convert WorkInfo to SyncStatus.
 */
private fun WorkInfo.toSyncStatus(): SyncStatus {
    return when (state) {
        WorkInfo.State.ENQUEUED -> SyncStatus.Enqueued
        WorkInfo.State.RUNNING -> SyncStatus.Running
        WorkInfo.State.SUCCEEDED -> {
            SyncStatus.Succeeded(
                calendarsSynced = outputData.getInt(CalDavSyncWorker.KEY_CALENDARS_SYNCED, 0),
                eventsPushed = outputData.getInt(CalDavSyncWorker.KEY_EVENTS_PUSHED, 0),
                eventsPulled = outputData.getInt(CalDavSyncWorker.KEY_EVENTS_PULLED, 0),
                durationMs = outputData.getLong(CalDavSyncWorker.KEY_DURATION_MS, 0),
                errorMessage = outputData.getString(CalDavSyncWorker.KEY_ERROR_MESSAGE)
            )
        }
        WorkInfo.State.FAILED -> {
            SyncStatus.Failed(
                errorMessage = outputData.getString(CalDavSyncWorker.KEY_ERROR_MESSAGE)
            )
        }
        WorkInfo.State.CANCELLED -> SyncStatus.Cancelled
        WorkInfo.State.BLOCKED -> SyncStatus.Blocked
    }
}
