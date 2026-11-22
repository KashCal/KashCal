package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.JobSchedulerIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a JobScheduler job watching the calendar provider, so a change another app or a sync
 * adapter makes while KashCal's process is dead still re-picks the next device reminder.
 *
 * The provider ContentObserver in CalendarProviderManager only exists while the process runs; the
 * job is how the system starts the process for a change instead. It triggers on
 * [CalendarContract.CONTENT_URI] and every descendant, which the provider notifies for every
 * write, Reminders-only writes included (those send no PROVIDER_CHANGED broadcast). Content
 * triggers can't be persisted or periodic, so the job is scheduled again after each run
 * ([DeviceCalendarChangeJobService]), replaced on every process start, boot and app update
 * ([refresh]), and brought in step when a switch changes ([reconcile]).
 *
 * The job is wanted only while device calendars and their reminders are on and READ_CALENDAR is
 * granted ([shouldWatch]). Which calendars notify (ticked, not muted) is left to
 * [DeviceCalendarReminderScheduler.scheduleNextReminder]. JobScheduler failures, thrown or
 * returned as [JobScheduler.RESULT_FAILURE], are logged, never thrown: this runs on every process
 * start, including ones the job, an alarm or boot started.
 */
@Singleton
class DeviceCalendarChangeWatcher internal constructor(
    private val context: Context,
    private val dataStore: KashCalDataStore,
    private val jobs: Jobs,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dataStore: KashCalDataStore,
    ) : this(context, dataStore, PlatformJobs(context))

    /** The JobScheduler calls the watcher makes, so tests can record them or make them fail. */
    internal interface Jobs {
        fun pending(id: Int): JobInfo?
        fun schedule(job: JobInfo): Int
        fun cancel(id: Int)
    }

    /** [Jobs] backed by the platform JobScheduler. */
    internal class PlatformJobs(context: Context) : Jobs {
        private val scheduler = context.getSystemService(JobScheduler::class.java)
        override fun pending(id: Int): JobInfo? = scheduler.getPendingJob(id)
        override fun schedule(job: JobInfo): Int = scheduler.schedule(job)
        override fun cancel(id: Int) = scheduler.cancel(id)
    }

    companion object {
        private const val TAG = "DeviceCalChangeWatcher"

        /**
         * Wait after the last change before the job runs. The provider inserts its own alert rows
         * about 5 s after a write, which notifies again; waiting past that folds both into one run.
         */
        const val UPDATE_DELAY_MS = 6_000L

        /** Longest a stream of changes can hold the job back. */
        const val MAX_DELAY_MS = 60_000L

        private val TRIGGER = JobInfo.TriggerContentUri(
            CalendarContract.CONTENT_URI,
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
        )
    }

    private val component = ComponentName(context, DeviceCalendarChangeJobService::class.java)

    /**
     * Returns whether device calendars and their reminders are on and READ_CALENDAR is granted.
     */
    suspend fun shouldWatch(): Boolean =
        dataStore.getDeviceCalendarsEnabled() &&
            dataStore.getDeviceCalendarRemindersEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Schedules the job when [shouldWatch] holds and cancels it otherwise, for the switches. A
     * pending job of the current shape is left alone: scheduling the same id stops a running job,
     * and the running job counts as pending. A pending job of another shape (an older build's) is
     * replaced.
     */
    suspend fun reconcile() = update(replaceCurrent = false)

    /**
     * Schedules the job when [shouldWatch] holds, replacing any pending copy, and cancels it
     * otherwise; for process start, boot and app update.
     *
     * A pending copy can look current and still not run: when the process is killed during a
     * run, JobScheduler keeps a failure-retry copy whose backoff can run minutes late, and
     * changes meanwhile wake nothing. Replacing it stops a run that is starting or in flight and
     * releases that run's wakelock, so callers re-pick the next device reminder first.
     */
    suspend fun refresh() = update(replaceCurrent = true)

    private suspend fun update(replaceCurrent: Boolean) {
        try {
            if (!shouldWatch()) {
                jobs.cancel(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)
                return
            }
            if (!replaceCurrent) {
                val pending = jobs.pending(JobSchedulerIds.DEVICE_CALENDAR_CHANGE)
                if (pending != null && isCurrentShape(pending)) return
            }
            if (jobs.schedule(buildJob()) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "JobScheduler refused the device calendar change job")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't update the device calendar change job", e)
        }
    }

    /**
     * Schedules the next job whether or not one is pending, returning whether it was accepted.
     * Only for the end of a run: the running job still counts as pending, so [reconcile] would
     * skip, and scheduling it is what ends the run.
     */
    fun scheduleNext(): Boolean =
        try {
            jobs.schedule(buildJob()) == JobScheduler.RESULT_SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't schedule the next device calendar change job", e)
            false
        }

    private fun buildJob(): JobInfo =
        JobInfo.Builder(JobSchedulerIds.DEVICE_CALENDAR_CHANGE, component)
            .addTriggerContentUri(TRIGGER)
            .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(MAX_DELAY_MS)
            .build()

    private fun isCurrentShape(job: JobInfo): Boolean =
        job.service == component &&
            job.triggerContentUris?.toList() == listOf(TRIGGER) &&
            job.triggerContentUpdateDelay == UPDATE_DELAY_MS &&
            job.triggerContentMaxDelay == MAX_DELAY_MS
}
