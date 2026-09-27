package org.onekash.kashcal.reminder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import java.util.concurrent.TimeUnit

/**
 * Arms every reminder due within the scheduler's window
 * ([ReminderScheduler.scheduleUpcomingReminders]), then schedules the next device calendar
 * reminder.
 *
 * This catches reminders that:
 * - Came into the window since the last check
 * - Were left without an alarm by a reboot (only rows inside the window are re-armed)
 * - Were missed because the refill after a fired reminder did not run
 *
 * The periodic run ([schedule]) runs daily, needs no network and waits while the battery is low.
 */
@HiltWorker
class ReminderRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminderScheduler: ReminderScheduler,
    private val deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler,
    private val dataStore: KashCalDataStore
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ReminderRefreshWorker"

        const val WORK_NAME = "reminder_refresh"
        const val TAG_REMINDER_REFRESH = "reminder_refresh"

        const val REFRESH_INTERVAL_HOURS = 24L

        // Retries before a run gives up and leaves the next period to try again
        private const val MAX_RETRY_ATTEMPTS = 3

        // Reminder migration version stored in the data store. v1 recalculates every pending
        // all-day reminder's trigger time in the device zone.
        private const val MIGRATION_VERSION_TZ_FIX = 1

        /**
         * Enqueues the daily refresh. KEEP leaves an already enqueued spec, and its settings,
         * in place.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<ReminderRefreshWorker>(
                REFRESH_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .addTag(TAG_REMINDER_REFRESH)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Scheduled periodic reminder refresh every $REFRESH_INTERVAL_HOURS hours")
        }

        /**
         * Enqueues a one-shot refresh with no constraints; called after boot, an app update, and
         * a timezone or clock change.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderRefreshWorker>()
                .addTag(TAG_REMINDER_REFRESH)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Triggered immediate reminder refresh")
        }

        /** Cancels the periodic refresh. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic reminder refresh")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting reminder refresh scan")

        return try {
            // Runs once per install; see MIGRATION_VERSION_TZ_FIX
            val migrationVersion = dataStore.getReminderMigrationVersion()
            if (migrationVersion < MIGRATION_VERSION_TZ_FIX) {
                Log.i(TAG, "Running reminder timezone migration v$MIGRATION_VERSION_TZ_FIX")
                reminderScheduler.rescheduleAllPending()
                dataStore.setReminderMigrationVersion(MIGRATION_VERSION_TZ_FIX)
            }

            val scheduled = reminderScheduler.scheduleUpcomingReminders()

            // Cleanup is best-effort: a failure doesn't fail the run
            try {
                reminderScheduler.cleanupOldReminders()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed, continuing", e)
            }

            // Device calendar reminders use one alarm for the next reminder; best-effort too
            try {
                deviceCalendarReminderScheduler.scheduleNextReminder()
                Log.d(TAG, "Device calendar reminder scheduled")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Device calendar reminder scheduling failed, continuing", e)
            }

            Log.i(TAG, "Reminder refresh complete: $scheduled new Room reminders scheduled")
            Result.success()
        } catch (e: CancellationException) {
            // A stopped worker must stay stopped. Reporting retry or success for a
            // cancellation logs a scan failure that never happened.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reminder refresh failed", e)

            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                // Never end in failure. Failure is terminal for a periodic work spec:
                // WorkManager stops scheduling it and only re-arming with KEEP revives
                // it, so one stretch of bad runs would stop the reminder scan until the
                // next app start. The next period is the retry.
                //
                // This applies to the one-shots from `runNow` too, because no caller
                // reads this worker's result. Telling the periodic run
                // apart by tag, as the sync workers do, would be worse: `schedule` arms
                // with KEEP, so a tag added now never reaches a spec that is already
                // enqueued, and the installs still carrying an untagged one are the ones
                // this protects.
                Result.success()
            }
        }
    }
}
