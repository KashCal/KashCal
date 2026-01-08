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
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * Triggers widget update and reminder reschedule when device timezone or time changes.
 * Ensures event times display correctly after travel or DST transitions.
 *
 * Per Android best practices:
 * - Uses goAsync() for work that takes > 10ms
 * - Uses Hilt DI for singleton services
 */
@AndroidEntryPoint
class TimezoneChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimezoneChangeReceiver"
    }

    @Inject
    lateinit var widgetUpdateManager: WidgetUpdateManager

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

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
                // Update widgets for correct time display
                widgetUpdateManager.updateAllWidgets(reason = reason)

                // CRITICAL: Reschedule reminders for new timezone
                // Alarms are based on absolute timestamps, but user perception
                // of "3pm" changes when timezone changes
                reminderScheduler.rescheduleAllPending()

                Log.d(TAG, "Successfully updated widgets and rescheduled reminders")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $reason", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
