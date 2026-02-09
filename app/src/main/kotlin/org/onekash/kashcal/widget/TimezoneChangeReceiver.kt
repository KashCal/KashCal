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
import org.onekash.kashcal.reminder.worker.ReminderRefreshWorker
import javax.inject.Inject

/**
 * Triggers widget update and reminder reschedule when device timezone or time changes.
 * Ensures event times display correctly after travel or DST transitions.
 *
 * Per Android best practices:
 * - Uses goAsync() for work that takes > 10ms
 * - Uses Hilt DI for singleton services
 *
 * Recovery includes ReminderRefreshWorker to create missing ScheduledReminder rows
 * for events that had reminders fired/dismissed/cleaned up.
 */
@AndroidEntryPoint
class TimezoneChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimezoneChangeReceiver"
    }

    @Inject
    lateinit var handler: TimezoneChangeHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val reason = when (intent?.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            Intent.ACTION_TIME_CHANGED -> "time_changed"
            else -> return
        }

        Log.i(TAG, "Received $reason, updating widgets and rescheduling reminders")

        val pendingResult = goAsync()
        scope.launch {
            try {
                handler.handleChange(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $reason", e)
            } finally {
                pendingResult.finish()
            }
        }

        // Trigger immediate reminder refresh to create ScheduledReminder rows
        // for events that are missing them. Runs via WorkManager (no 10s limit).
        try {
            ReminderRefreshWorker.runNow(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger reminder refresh worker", e)
        }
    }
}
