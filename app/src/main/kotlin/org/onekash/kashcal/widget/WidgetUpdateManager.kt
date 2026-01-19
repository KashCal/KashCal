package org.onekash.kashcal.widget

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WidgetUpdateManager"
private const val WORK_NAME_PERIODIC = "widget_periodic_update"
private const val WORK_NAME_MIDNIGHT = "widget_midnight_update"
private const val WORK_NAME_RETRY = "widget_retry_update"

/**
 * Manages widget update triggers:
 * - Periodic updates every 30 minutes
 * - Midnight updates when the day changes
 * - Manual updates after event changes
 */
@Singleton
class WidgetUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Immediately update all widget instances.
     * Call this after event CRUD operations.
     *
     * Uses hybrid approach: immediate update for instant feedback,
     * with WorkManager retry fallback for transient failures.
     *
     * @param reason Debug context for logging (e.g., "sync_changes", "timezone_changed")
     */
    suspend fun updateAllWidgets(reason: String = "unknown") {
        Log.d(TAG, "Updating all widgets (reason: $reason)")
        try {
            AgendaWidget().updateAll(context)
            WeekWidget().updateAll(context)
            DateWidget().updateAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "Immediate widget update failed", e)
            if (isTransientError(e)) {
                Log.d(TAG, "Scheduling retry for transient error")
                scheduleRetryUpdate()
            }
        }
    }

    /**
     * Schedule a retry update via WorkManager.
     * Uses exponential backoff: 10s -> 20s -> 40s.
     */
    private fun scheduleRetryUpdate() {
        val workRequest = OneTimeWorkRequestBuilder<WidgetRetryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_RETRY,
            ExistingWorkPolicy.REPLACE,  // Coalesces rapid retry requests
            workRequest
        )
    }

    /**
     * Determine if error is transient and worth retrying.
     * Note: SocketTimeoutException extends IOException, included for clarity.
     */
    private fun isTransientError(e: Exception): Boolean = when (e) {
        is IOException -> true              // Network issues, file system
        is SocketTimeoutException -> true   // Network timeout (subclass of IOException)
        is RemoteException -> true          // Binder communication failed
        else -> false                       // Permanent failures (e.g., SecurityException)
    }

    /**
     * Schedule periodic widget updates every 30 minutes.
     * Should be called once at app startup.
     */
    fun schedulePeriodicUpdates() {
        Log.d(TAG, "Scheduling periodic widget updates")

        val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            30, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES  // Flex interval
        )
            .setConstraints(Constraints.Builder().build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * Schedule a one-time update at midnight to refresh the widget for the new day.
     * Reschedules itself after firing.
     */
    fun scheduleMidnightUpdate() {
        Log.d(TAG, "Scheduling midnight widget update")

        // Calculate delay until midnight local time
        val now = System.currentTimeMillis()
        val midnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val delay = midnight - now

        Log.d(TAG, "Midnight update scheduled in ${delay / 1000 / 60} minutes")

        val workRequest = OneTimeWorkRequestBuilder<MidnightWidgetUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_MIDNIGHT,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Cancel all scheduled widget updates.
     * Call this when the app is being uninstalled or widgets removed.
     */
    fun cancelAllUpdates() {
        Log.d(TAG, "Cancelling all widget updates")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_MIDNIGHT)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_RETRY)
    }
}

/**
 * Worker for periodic widget updates (every 30 minutes).
 */
class WidgetUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "WidgetUpdateWorker running")
        try {
            AgendaWidget().updateAll(applicationContext)
            WeekWidget().updateAll(applicationContext)
            DateWidget().updateAll(applicationContext)
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            return Result.retry()
        }
    }
}

/**
 * Worker for midnight widget updates.
 * Updates widget and reschedules for next midnight.
 */
class MidnightWidgetUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "MidnightWidgetUpdateWorker running - new day!")
        try {
            // Update widgets with new day's events
            AgendaWidget().updateAll(applicationContext)
            WeekWidget().updateAll(applicationContext)
            DateWidget().updateAll(applicationContext)

            // Reschedule for next midnight
            val manager = WidgetUpdateManager(applicationContext)
            manager.scheduleMidnightUpdate()

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Midnight widget update failed", e)
            return Result.retry()
        }
    }
}
