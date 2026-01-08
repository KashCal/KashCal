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
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that scans upcoming events and schedules missing reminders.
 *
 * This catches events that:
 * - Were created outside the original scheduling window
 * - Entered the window since last check
 * - Had reminders missed due to app not running
 *
 * Per Android WorkManager best practices:
 * - Runs daily with flexible window (battery efficient)
 * - Network not required (local operation)
 * - Respects battery constraints
 */
@HiltWorker
class ReminderRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminderScheduler: ReminderScheduler
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ReminderRefreshWorker"

        const val WORK_NAME = "reminder_refresh"
        const val TAG_REMINDER_REFRESH = "reminder_refresh"

        // Scan 30 days ahead (must match SCHEDULE_WINDOW_DAYS to catch all gaps)
        const val SCAN_WINDOW_DAYS = 30

        // Run once per day
        const val REFRESH_INTERVAL_HOURS = 24L

        /**
         * Schedule periodic reminder refresh.
         * Uses KEEP policy to avoid rescheduling if already scheduled.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)  // Don't run on low battery
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
                    ExistingPeriodicWorkPolicy.KEEP,  // Don't replace existing
                    request
                )

            Log.i(TAG, "Scheduled periodic reminder refresh every $REFRESH_INTERVAL_HOURS hours")
        }

        /**
         * Run immediate one-time refresh (for testing or manual trigger).
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReminderRefreshWorker>()
                .addTag(TAG_REMINDER_REFRESH)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Triggered immediate reminder refresh")
        }

        /**
         * Cancel periodic refresh.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic reminder refresh")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting reminder refresh scan")

        return try {
            val scheduled = reminderScheduler.scheduleUpcomingReminders(SCAN_WINDOW_DAYS)

            // Cleanup is best-effort - don't fail job if it throws
            try {
                reminderScheduler.cleanupOldReminders()
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed, continuing", e)
            }

            Log.i(TAG, "Reminder refresh complete: $scheduled new reminders scheduled")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Reminder refresh failed", e)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
