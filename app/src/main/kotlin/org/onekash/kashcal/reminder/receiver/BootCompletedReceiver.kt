package org.onekash.kashcal.reminder.receiver

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
import org.onekash.kashcal.ui.appicon.DateIconRefresher
import javax.inject.Inject

/**
 * Re-arms reminders after device boot (BOOT_COMPLETED) or an app update (MY_PACKAGE_REPLACED),
 * both of which clear every AlarmManager alarm.
 *
 * Recovery has two phases:
 * 1. Inside the broadcast, under goAsync() and a 9-second timeout,
 *    [BootRecoveryHandler.rescheduleReminders] re-arms the existing ScheduledReminder rows due
 *    within the scheduler's window, the device calendar reminder and the widget midnight alarm.
 *    Rows further out are armed once they come into the window.
 * 2. [ReminderRefreshWorker.runNow] creates the rows missing for reminders now due within the
 *    window and re-arms the existing ones there. It runs in WorkManager, outside the receiver's
 *    10-second limit.
 *
 * Before phase 1 it moves the date launcher icon to today, since a phone that was off over
 * midnight missed the rollover alarm.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var handler: BootRecoveryHandler

    @Inject
    lateinit var dateIconRefresher: DateIconRefresher

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device boot completed, rescheduling reminders")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App updated, rescheduling reminders")
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
                return
            }
        }

        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dateIconRefresher.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing the date icon after boot", e)
            }
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    handler.rescheduleReminders()
                }
                if (completed == null) {
                    Log.w(TAG, "Reminder reschedule timed out, WorkManager will complete")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling reminders after boot", e)
            } finally {
                pendingResult.finish()
            }
        }

        try {
            ReminderRefreshWorker.runNow(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger reminder refresh worker", e)
        }
    }
}
