package org.onekash.kashcal.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.reminder.worker.ReminderRefreshWorker
import javax.inject.Inject

/**
 * Updates widgets and reschedules reminders when the device timezone or clock changes, so event
 * times and reminders follow the new local time after travel or DST transitions.
 *
 * [TimezoneChangeHandler] runs under goAsync() with a 9 s timeout, inside the broadcast's 10 s
 * limit. The receiver also starts [ReminderRefreshWorker], which creates missing ScheduledReminder
 * rows for events whose reminders were fired, dismissed or cleaned up.
 */
@AndroidEntryPoint
class TimezoneChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimezoneChangeReceiver"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var handler: TimezoneChangeHandler

    override fun onReceive(context: Context, intent: Intent?) {
        val reason = when (intent?.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            Intent.ACTION_TIME_CHANGED -> "time_changed"
            else -> return
        }

        Log.i(TAG, "Received $reason, updating widgets and rescheduling reminders")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    handler.handleChange(reason)
                }
                if (completed == null) {
                    Log.w(TAG, "Timezone change handling timed out, WorkManager will complete")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $reason", e)
            } finally {
                pendingResult.finish()
            }
        }

        // Runs in WorkManager, outside the broadcast's 10 s limit.
        try {
            ReminderRefreshWorker.runNow(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger reminder refresh worker", e)
        }
    }
}
