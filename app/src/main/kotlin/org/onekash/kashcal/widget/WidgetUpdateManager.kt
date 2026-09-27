package org.onekash.kashcal.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.util.AlarmArming
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WidgetUpdateManager"
private const val WORK_NAME_PERIODIC = "widget_periodic_update"
private const val WORK_NAME_RETRY = "widget_retry_update"
private const val REQUEST_CODE_MIDNIGHT = 1

/**
 * Schedules and runs widget updates:
 * - every 30 minutes through WorkManager; cosmetic, so drifting in Doze is fine;
 * - at local midnight through an allow-while-idle AlarmManager alarm, for the day rollover;
 * - on demand, after data or settings changes ([updateAllWidgets],
 *   [updateAllWidgetsForColorChange]).
 *
 * Midnight uses AlarmManager because Doze defers JobScheduler, and so WorkManager, entirely;
 * setExactAndAllowWhileIdle is documented to fire "even if battery-saving measures are in effect."
 */
@Singleton
class WidgetUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Refreshes every event widget now, DateWidget excepted ([refreshAllWidgets]). Rethrows
     * cancellation; any other failure is logged, and a transient one (IOException or
     * RemoteException) schedules [WidgetRetryWorker].
     */
    suspend fun updateAllWidgets(reason: String = "unknown") {
        Log.d(TAG, "Updating all widgets (reason: $reason)")
        try {
            refreshAllWidgets(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Immediate widget update failed", e)
            if (isTransientError(e)) {
                Log.d(TAG, "Scheduling retry for transient error")
                scheduleRetryUpdate()
            }
        }
    }

    /**
     * Refreshes every widget after a change to their appearance, such as the accent color, color
     * source or widget theme. Unlike [updateAllWidgets] it includes DateWidget. Fails the same way.
     */
    suspend fun updateAllWidgetsForColorChange(reason: String = "color_change") {
        Log.d(TAG, "Updating all widgets incl. DateWidget (reason: $reason)")
        try {
            refreshAllWidgets(context, includeDateWidget = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Immediate widget color update failed", e)
            if (isTransientError(e)) {
                Log.d(TAG, "Scheduling retry for transient error")
                scheduleRetryUpdate()
            }
        }
    }

    private fun scheduleRetryUpdate() {
        val workRequest = OneTimeWorkRequestBuilder<WidgetRetryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_RETRY,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun isTransientError(e: Exception): Boolean = when (e) {
        is IOException -> true
        is RemoteException -> true
        else -> false
    }

    /** Schedules the periodic and midnight updates; app startup calls it. */
    fun scheduleUpdates() {
        schedulePeriodicUpdates()
        scheduleMidnightUpdate()
    }

    /**
     * Schedules [WidgetUpdateWorker] every 30 minutes, keeping an existing schedule. App startup
     * calls it through [scheduleUpdates].
     */
    fun schedulePeriodicUpdates() {
        Log.d(TAG, "Scheduling periodic widget updates")

        val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            30, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
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
     * Sets the allow-while-idle alarm for the next local midnight, exact when exact alarms are
     * allowed ([AlarmArming.setAllowWhileIdle]), so the day rollover fires through Doze, for
     * example with the phone in airplane mode overnight.
     *
     * Called at app startup and re-armed by [MidnightWidgetUpdateReceiver] and by
     * `BootRecoveryHandler` after a boot or an app update, since a reboot clears AlarmManager
     * alarms.
     */
    fun scheduleMidnightUpdate() {
        val now = System.currentTimeMillis()
        val midnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        Log.d(TAG, "Scheduling midnight widget update in ${(midnight - now) / 1000 / 60} minutes")

        // Startup, the midnight receiver and boot recovery all run this where an
        // exception would crash, so a refusal must skip the alarm, not throw.
        AlarmArming.setAllowWhileIdle(
            alarmManager = alarmManager,
            triggerTime = midnight,
            pendingIntent = createMidnightPendingIntent(),
            tag = TAG,
            label = "Midnight widget refresh"
        )
    }

    /** Cancels the periodic, retry and midnight updates. Nothing calls it. */
    fun cancelAllUpdates() {
        Log.d(TAG, "Cancelling all widget updates")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_RETRY)
        alarmManager.cancel(createMidnightPendingIntent())
    }

    private fun createMidnightPendingIntent(): PendingIntent {
        val intent = Intent(context, MidnightWidgetUpdateReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MIDNIGHT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Runs the 30-minute widget refresh, retrying on any failure. */
class WidgetUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "WidgetUpdateWorker running")
        return try {
            refreshAllWidgets(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            Result.retry()
        }
    }
}
