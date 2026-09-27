package org.onekash.kashcal.widget

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.io.IOException

private const val TAG = "WidgetRetryWorker"
private const val MAX_RETRY_ATTEMPTS = 3

/**
 * Retries a failed widget refresh.
 *
 * [WidgetUpdateManager] enqueues it only when an immediate refresh fails with a transient error
 * (IOException or RemoteException). Backoff is exponential from 10s (10s, 20s, 40s), and after
 * [MAX_RETRY_ATTEMPTS] retries, or on any other error, the work fails.
 */
class WidgetRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "WidgetRetryWorker running (attempt ${runAttemptCount + 1})")
        return try {
            refreshAllWidgets(applicationContext)
            Log.d(TAG, "Widget update succeeded on retry")
            Result.success()
        } catch (e: CancellationException) {
            throw e
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

    /** Returns whether [e] is worth retrying; SocketTimeoutException counts as an IOException. */
    private fun isTransientError(e: Exception): Boolean = when (e) {
        is IOException -> true              // Network issues (includes SocketTimeoutException)
        is RemoteException -> true          // Binder communication failed
        else -> false                       // Permanent failures (e.g., SecurityException)
    }
}
