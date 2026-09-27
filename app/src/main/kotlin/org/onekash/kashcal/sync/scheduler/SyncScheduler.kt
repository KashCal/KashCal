package org.onekash.kashcal.sync.scheduler

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.contacts.ContactSyncWorker
import org.onekash.kashcal.sync.util.SyncNetworkConstraints
import org.onekash.kashcal.sync.worker.CalDavSyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules calendar and contact sync on WorkManager, and holds the sync UI state shared
 * across ViewModels (banner flag, last sync changes).
 *
 * Provides periodic background sync (configurable, at least 15 minutes), one-shot sync for
 * user-initiated refresh, expedited sync after a local change, per-calendar and per-account
 * sync, and status observation. Every request carries a network constraint and a unique work
 * name so schedules don't duplicate; all but the expedited one use exponential backoff.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Whether the current or next sync shows the UI banner; the source of truth for banner
     * visibility across ViewModels. Callers set it before [requestImmediateSync]; the UI
     * resets it after the sync succeeds or fails.
     */
    private val _showBannerForSync = MutableStateFlow(false)
    val showBannerForSync: StateFlow<Boolean> = _showBannerForSync.asStateFlow()

    /** Sets banner visibility for the current or next sync; call before [requestImmediateSync]. */
    fun setShowBannerForSync(show: Boolean) {
        Log.d(TAG, "setShowBannerForSync: $show")
        _showBannerForSync.value = show
    }

    /** Resets the banner flag; the UI calls it after handling the sync's success or failure. */
    fun resetBannerFlag() {
        Log.d(TAG, "resetBannerFlag: resetting to false")
        _showBannerForSync.value = false
    }

    /**
     * Changes from the most recent sync. `CalDavSyncWorker` sets them when a sync with changes
     * completes; HomeViewModel observes them to show the snackbar.
     */
    private val _lastSyncChanges = MutableStateFlow<List<SyncChange>>(emptyList())
    val lastSyncChanges: StateFlow<List<SyncChange>> = _lastSyncChanges.asStateFlow()

    /** Sets the changes of a completed sync; called by `CalDavSyncWorker`. */
    fun setSyncChanges(changes: List<SyncChange>) {
        Log.d(TAG, "setSyncChanges: ${changes.size} changes")
        _lastSyncChanges.value = changes
    }

    /** Clears the sync changes once the UI has shown them (snackbar or bottom sheet dismissed). */
    fun clearSyncChanges() {
        Log.d(TAG, "clearSyncChanges: clearing")
        _lastSyncChanges.value = emptyList()
    }

    companion object {
        private const val TAG = "SyncScheduler"

        // Work names
        const val PERIODIC_SYNC_WORK = "periodic_sync"
        const val PERIODIC_CONTACT_SYNC_WORK = ContactSyncWorker.SYNC_WORK
        const val ONE_SHOT_SYNC_WORK = "one_shot_sync"
        const val ONE_SHOT_CONTACT_SYNC_WORK = "one_shot_contact_sync"
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
     * Requires a connected, internet-capable network but not public-internet validation, so
     * sync runs against self-hosted CalDAV servers on a LAN or VPN (#296). See
     * [SyncNetworkConstraints].
     */
    private val networkConstraints = SyncNetworkConstraints.builder()
        .build()

    /** Constraints for expedited work; the same network rule as [networkConstraints]. */
    private val expeditedConstraints = SyncNetworkConstraints.builder()
        .build()

    /**
     * Schedules periodic background sync of all accounts, and the contact job alongside it.
     *
     * KEEP leaves a pending schedule alone. WorkManager keeps it across device restarts.
     *
     * A recurring request must stay a full sync. Failure is terminal for a periodic work
     * spec: WorkManager stops scheduling it, and only re-arming with KEEP revives it. The
     * worker's full-sync path is the only one that can't end in failure, since it folds each
     * account's auth and transport errors into a partial success. The per-calendar and
     * per-account paths return failure on an expired password, so scheduling either
     * recurringly would let one bad credential stop background sync for good. Give a
     * narrower recurring sync the same folding first.
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

        scheduleContactSync(actualInterval, ExistingPeriodicWorkPolicy.KEEP)
    }

    /**
     * Enqueues the periodic contact-sync job at [actualInterval] with [policy].
     *
     * Contact sync reuses the calendar sync interval and lifecycle, with no scheduling
     * mechanism of its own. Only CardDAV-capable, contact-sync-enabled accounts sync; the
     * worker is a no-op when none qualify, so scheduling it unconditionally is cheap.
     */
    private fun scheduleContactSync(
        actualInterval: Long,
        policy: ExistingPeriodicWorkPolicy,
    ) {
        val contactWork = PeriodicWorkRequestBuilder<ContactSyncWorker>(
            actualInterval, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag(TAG_PERIODIC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_CONTACT_SYNC_WORK,
            policy,
            contactWork
        )
    }

    /**
     * Schedules the periodic contact-sync job unless one is pending; a finished (FAILED or
     * cancelled) spec is replaced.
     *
     * Unlike [schedulePeriodicSync], it doesn't touch calendar sync. A login whose calendar
     * job was armed before the contact job existed has no contact job, so without this an
     * enable would import once and never sync again. KEEP makes it a no-op when a job is
     * pending.
     */
    fun ensureContactSyncScheduled(intervalMinutes: Long) {
        val actualInterval = maxOf(intervalMinutes, MIN_SYNC_INTERVAL_MINUTES)
        scheduleContactSync(actualInterval, ExistingPeriodicWorkPolicy.KEEP)
    }

    /**
     * Requests a one-shot contact pull (user-initiated, e.g. enabling contact sync) that runs
     * as soon as the network constraint is met, without waiting for the periodic tick.
     * REPLACE collapses repeated toggles to one pending run.
     *
     * @param accountId when non-null, only that login's contacts sync (a "Sync now" from one
     *   account's sheet); null syncs every contact-sync login (the enable path,
     *   pull-to-refresh).
     * @return the work request's id, for status tracking
     */
    fun requestImmediateContactSync(accountId: Long? = null): java.util.UUID {
        Log.i(TAG, "Requesting immediate contact sync (accountId=$accountId)")

        val oneShotWork = OneTimeWorkRequestBuilder<ContactSyncWorker>()
            .applyOneShotSyncDefaults()
            .apply {
                if (accountId != null) {
                    setInputData(ContactSyncWorker.createScopedInput(accountId))
                }
            }
            .build()

        workManager.enqueueUniqueWork(
            ONE_SHOT_CONTACT_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            oneShotWork
        )

        return oneShotWork.id
    }

    /**
     * Applies the defaults of user-initiated one-shot sync work: the LAN-friendly network
     * constraint, exponential backoff, and the sync and one-shot tags. Callers add
     * worker-specific input data.
     */
    private fun OneTimeWorkRequest.Builder.applyOneShotSyncDefaults():
        OneTimeWorkRequest.Builder =
        setConstraints(networkConstraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_SYNC)
            .addTag(TAG_ONE_SHOT)

    /** Cancels periodic calendar and contact sync; called when the user picks manual-only. */
    fun cancelPeriodicSync() {
        Log.i(TAG, "Cancelling periodic sync")
        workManager.cancelUniqueWork(PERIODIC_SYNC_WORK)
        workManager.cancelUniqueWork(PERIODIC_CONTACT_SYNC_WORK)
    }

    /**
     * Moves periodic calendar and contact sync to [intervalMinutes] (at least 15) with UPDATE.
     * UPDATE doesn't apply to a finished spec, so this doesn't revive a FAILED job.
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

        scheduleContactSync(actualInterval, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /**
     * Requests a one-shot full sync that runs as soon as the network is available.
     *
     * @param forceFullSync if true, ignores ctag and sync-token
     * @param trigger the trigger source recorded in sync history
     * @param showNotification if true, the worker posts sync notifications (progress,
     *   completion, error). Only user-initiated force syncs opt in; silent app-open and
     *   resume syncs leave it false.
     * @return the work request's id, for status tracking
     */
    fun requestImmediateSync(
        forceFullSync: Boolean = false,
        trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL,
        showNotification: Boolean = false
    ): java.util.UUID {
        Log.i(TAG, "Requesting immediate sync (force=$forceFullSync, trigger=${trigger.name})")

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync, showNotification, trigger)

        val oneShotWork = OneTimeWorkRequestBuilder<CalDavSyncWorker>()
            .setInputData(inputData)
            .applyOneShotSyncDefaults()
            .build()

        workManager.enqueueUniqueWork(
            ONE_SHOT_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            oneShotWork
        )

        return oneShotWork.id
    }

    /**
     * Requests an expedited full sync, which EventCoordinator issues after a local change to
     * a synced calendar. Expedited work has quotas; past the quota it runs as regular work.
     *
     * @param forceFullSync if true, ignores ctag and sync-token
     * @return the work request's id
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
     * Requests a one-shot sync of [calendarId]. Never schedule it recurringly (see
     * [schedulePeriodicSync]).
     *
     * @param forceFullSync if true, ignores the sync-token
     * @return the work request's id
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
     * Requests a one-shot sync of every calendar of [accountId]. Never schedule it recurringly
     * (see [schedulePeriodicSync]).
     *
     * @param forceFullSync if true, ignores ctag and sync-token
     * @return the work request's id
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

    /** Cancels all sync work, periodic and one-shot. */
    fun cancelAllSync() {
        Log.i(TAG, "Cancelling all sync work")
        workManager.cancelAllWorkByTag(TAG_SYNC)
    }

    /** Cancels a pending [syncCalendar] request. */
    fun cancelCalendarSync(calendarId: Long) {
        Log.i(TAG, "Cancelling sync for calendar: $calendarId")
        workManager.cancelUniqueWork("sync_calendar_$calendarId")
    }

    /** Cancels a pending [syncAccount] request. */
    fun cancelAccountSync(accountId: Long) {
        Log.i(TAG, "Cancelling sync for account: $accountId")
        workManager.cancelUniqueWork("sync_account_$accountId")
    }

    /** Observes the status of the [requestImmediateSync] work. */
    fun observeImmediateSyncStatus(): Flow<SyncStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(ONE_SHOT_SYNC_WORK)
            .map { workInfoList ->
                workInfoList.firstOrNull()?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /** Observes the status of the periodic calendar sync. */
    fun observePeriodicSyncStatus(): Flow<SyncStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_SYNC_WORK)
            .map { workInfoList ->
                workInfoList.firstOrNull()?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /** Observes the status of the [requestExpeditedSync] work. */
    fun observeExpeditedSyncStatus(): Flow<SyncStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(EXPEDITED_SYNC_WORK)
            .map { workInfoList ->
                workInfoList.firstOrNull()?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /** Observes the status of the work with [workId]. */
    fun observeSyncStatus(workId: java.util.UUID): Flow<SyncStatus> {
        return workManager.getWorkInfoByIdFlow(workId)
            .map { workInfo ->
                workInfo?.toSyncStatus() ?: SyncStatus.Idle
            }
    }

    /**
     * Returns the periodic calendar sync's state and next run, or null when WorkManager has
     * no record of it.
     */
    fun getPeriodicSyncInfo(): PeriodicSyncInfo? {
        val workInfo = workManager.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).get()
            .firstOrNull() ?: return null

        return PeriodicSyncInfo(
            isEnabled = workInfo.state != WorkInfo.State.CANCELLED,
            state = workInfo.toSyncStatus(),
            nextScheduledRunTime = workInfo.nextScheduleTimeMillis
        )
    }

    /** Returns whether a periodic calendar sync spec exists that isn't cancelled. */
    fun isPeriodicSyncEnabled(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).get()
        return workInfos.any { it.state != WorkInfo.State.CANCELLED }
    }

    /** Prunes finished work from WorkManager's database. */
    fun pruneCompletedWork() {
        Log.d(TAG, "Pruning completed work")
        workManager.pruneWork()
    }
}

/** Status of a sync work request, for the UI. */
sealed class SyncStatus {
    /** No sync in progress or scheduled. */
    data object Idle : SyncStatus()

    /** Queued and waiting for constraints. */
    data object Enqueued : SyncStatus()

    /** Running. */
    data object Running : SyncStatus()

    /** Completed; may include partial errors. */
    data class Succeeded(
        val calendarsSynced: Int = 0,
        val eventsPushed: Int = 0,
        val eventsPulled: Int = 0,
        val durationMs: Long = 0,
        val errorMessage: String? = null
    ) : SyncStatus()

    /** Failed. */
    data class Failed(
        val errorMessage: String?
    ) : SyncStatus()

    /** Cancelled. */
    data object Cancelled : SyncStatus()

    /** Blocked by unfinished prerequisite work. */
    data object Blocked : SyncStatus()
}

/** State and next run time of the periodic calendar sync. */
data class PeriodicSyncInfo(
    val isEnabled: Boolean,
    val state: SyncStatus,
    val nextScheduledRunTime: Long
)

/** Maps a [WorkInfo] to its [SyncStatus], reading counts from `CalDavSyncWorker`'s output. */
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
