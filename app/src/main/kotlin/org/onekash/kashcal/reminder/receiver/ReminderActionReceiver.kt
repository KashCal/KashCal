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
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * Handles the Snooze and Dismiss actions of a reminder notification.
 *
 * Any action that carries a reminder id cancels the notification at once; the database and
 * alarm work then runs under goAsync() and a 9-second timeout.
 * - Snooze: re-arms the reminder the chosen number of minutes from now.
 * - Dismiss: marks it DISMISSED, so a later alarm for it posts nothing.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderActionReceiver"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var notificationManager: ReminderNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, -1)
        if (reminderId == -1L) {
            Log.e(TAG, "Missing reminder ID in intent")
            return
        }

        Log.d(TAG, "Action received for reminder $reminderId: ${intent.action}")

        // Cancel notification immediately for responsive UX
        notificationManager.cancelNotification(reminderId)

        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    when (intent.action) {
                        ReminderNotificationManager.ACTION_SNOOZE -> {
                            val snoozeDuration = intent.getIntExtra(
                                ReminderNotificationManager.EXTRA_SNOOZE_DURATION_MINUTES,
                                ReminderNotificationManager.DEFAULT_SNOOZE_MINUTES
                            )
                            handleSnooze(reminderId, snoozeDuration)
                        }
                        ReminderNotificationManager.ACTION_DISMISS -> {
                            handleDismiss(reminderId)
                        }
                        else -> {
                            Log.w(TAG, "Unknown action: ${intent.action}")
                        }
                    }
                }
                if (completed == null) {
                    Log.w(TAG, "Action handling timed out for reminder $reminderId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling action for reminder $reminderId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSnooze(reminderId: Long, snoozeDurationMinutes: Int) {
        reminderScheduler.snoozeReminder(reminderId, snoozeDurationMinutes)
        Log.d(TAG, "Snoozed reminder $reminderId for $snoozeDurationMinutes minutes")
    }

    private suspend fun handleDismiss(reminderId: Long) {
        reminderScheduler.markAsDismissed(reminderId)
        Log.d(TAG, "Dismissed reminder $reminderId")
    }
}
