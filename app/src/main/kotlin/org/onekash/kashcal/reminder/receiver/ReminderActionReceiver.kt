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
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * BroadcastReceiver for reminder notification actions.
 *
 * Handles user interactions with reminder notifications:
 * - Snooze: Reschedule reminder for later
 * - Dismiss: Cancel the notification and mark as dismissed
 *
 * Per Android best practices:
 * - Uses goAsync() for work that takes > 10ms
 * - Cancels notification immediately on action
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderActionReceiver"
    }

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var notificationManager: ReminderNotificationManager

    // Scope for async work in receiver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(ReminderNotificationManager.EXTRA_REMINDER_ID, -1)
        if (reminderId == -1L) {
            Log.e(TAG, "Missing reminder ID in intent")
            return
        }

        Log.d(TAG, "Action received for reminder $reminderId: ${intent.action}")

        // Cancel notification immediately for responsive UX
        notificationManager.cancelNotification(reminderId)

        // Use goAsync() for database/alarm operations
        val pendingResult = goAsync()

        scope.launch {
            try {
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
