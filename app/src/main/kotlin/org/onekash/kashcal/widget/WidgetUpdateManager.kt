package org.onekash.kashcal.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * Manages widget update triggers:
 * - Periodic updates every 30 minutes (WorkManager; cosmetic, OK to drift in Doze)
 * - Midnight day-rollover updates (AlarmManager setExactAndAllowWhileIdle — fires through Doze)
 * - Manual updates after event changes
 *
 * Midnight uses AlarmManager instead of WorkManager because Doze defers
 * JobScheduler (and therefore WorkManager) entirely; setExactAndAllowWhileIdle
 * is documented to fire "even if battery-saving measures are in effect."
 */
@Singleton
class WidgetUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Immediately update all widget instances.
     * Call this after event CRUD operations.
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
     * Update all widgets after a change that affects their appearance rather than their data
     * (accent color, color source). Unlike [updateAllWidgets] this also refreshes the DateWidget,
     * which the event-driven path skips because its content is date-only.
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

    /**
     * Schedule periodic widget updates every 30 minutes.
     * Should be called once at app startup.
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
     * Schedule an exact alarm at next local midnight to refresh the widget for
     * the new day. Uses setExactAndAllowWhileIdle so the refresh fires through
     * Doze (e.g., phone in airplane mode overnight).
     *
     * Called from app startup and re-armed by the receiver itself and by
     * BootRecoveryHandler (since AlarmManager alarms clear on reboot).
     */
    fun scheduleMidnightUpdate() {
        val now = System.currentTimeMillis()
        val midnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        Log.d(TAG, "Scheduling midnight widget update in ${(midnight - now) / 1000 / 60} minutes")

        val pendingIntent = createMidnightPendingIntent()

        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
                Log.d(TAG, "Scheduled exact midnight widget alarm")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
                Log.d(TAG, "Scheduled inexact midnight widget alarm (exact permission unavailable)")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm failed, falling back to inexact", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
            } catch (e2: SecurityException) {
                Log.e(TAG, "Cannot schedule any midnight alarm", e2)
            }
        }
    }

    /**
     * Cancel all scheduled widget updates.
     * Call this when the app is being uninstalled or widgets removed.
     */
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

    /**
     * For Android 12+, USE_EXACT_ALARM is auto-granted for calendar apps.
     * This is belt-and-suspenders in case the permission is ever revoked/denied.
     */
    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
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
