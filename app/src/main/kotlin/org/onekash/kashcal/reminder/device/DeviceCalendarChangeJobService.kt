package org.onekash.kashcal.reminder.device

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Runs when the calendar provider changed, scheduled by [DeviceCalendarChangeWatcher], and
 * re-picks the next device reminder alarm. The system starts KashCal's process for it when the
 * process is dead.
 */
@AndroidEntryPoint
class DeviceCalendarChangeJobService : JobService() {

    @Inject
    lateinit var scheduler: DeviceCalendarReminderScheduler

    @Inject
    lateinit var watcher: DeviceCalendarChangeWatcher

    // One scope per run: a replaced run's onStopJob must not cancel the run that replaced it.
    private val runs = mutableMapOf<JobParameters, CoroutineScope>()

    companion object {
        private const val TAG = "DeviceCalChangeJob"

        /**
         * Bound on one re-pick, well under JobScheduler's limit for a job. It can't interrupt a
         * blocking provider query, only give up on its result.
         */
        private const val REPICK_TIMEOUT_MS = 30_000L

        /**
         * Re-picks the next device reminder, then schedules the next job in place of finishing,
         * as the JobInfo.Builder.addTriggerContentUri docs require: changes made during the run
         * carry over to the next job, while scheduling before or during the re-pick would stop
         * this one. A re-pick that throws or times out still schedules the next job, so one
         * failure doesn't end watching. [finish] runs only when no next job is scheduled (the
         * watch conditions no longer hold, or JobScheduler refused).
         *
         * Takes its dependencies as parameters so it can be tested without Hilt.
         */
        internal suspend fun handleChange(
            scheduler: DeviceCalendarReminderScheduler,
            watcher: DeviceCalendarChangeWatcher,
            timeoutMs: Long = REPICK_TIMEOUT_MS,
            finish: () -> Unit,
        ) {
            try {
                withTimeout(timeoutMs) { scheduler.scheduleNextReminder() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Re-picking the next device reminder timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't re-pick the next device reminder", e)
            }

            val keepWatching = try {
                watcher.shouldWatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't read the device calendar settings, still watching", e)
                true
            }
            if (keepWatching && watcher.scheduleNext()) return
            finish()
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "Device calendar changed, re-picking the next reminder")
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(runs) { runs[params] = runScope }
        runScope.launch {
            try {
                handleChange(scheduler, watcher) { jobFinished(params, false) }
            } finally {
                synchronized(runs) { runs.remove(params) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(runs) { runs.remove(params) }?.cancel()
        // Ask JobScheduler to run it again, so a stopped run doesn't end watching.
        return true
    }
}
