package org.onekash.kashcal.widget

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import java.net.SocketTimeoutException

private const val TAG = "WidgetRetryWorker"
private const val MAX_RETRY_ATTEMPTS = 3

/**
 * WorkManager worker for retrying failed widget updates.
 *
 * Uses simple constructor (no @HiltWorker) since no DI needed.
 * Follows pattern of existing WidgetUpdateWorker, MidnightWidgetUpdateWorker.
 *
 * This worker is only enqueued when the immediate update fails with a
 * transient error (IOException, TimeoutException, RemoteException).
 * It uses exponential backoff: 10s -> 20s -> 40s.
 */
class WidgetRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "WidgetRetryWorker running (attempt ${runAttemptCount + 1})")
        return try {
            AgendaWidget().updateAll(applicationContext)
            Log.d(TAG, "Widget update succeeded on retry")
            Result.success()
        } catch (e: Exception) {
            if (isTransientError(e) && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Log.w(TAG, "Transient error, retrying (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS)", e)
                Result.retry()
            } else {
                Log.e(TAG, "Widget update failed permanently after ${runAttemptCount + 1} attempts", e)
                Result.failure()
            }
        }
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
}
