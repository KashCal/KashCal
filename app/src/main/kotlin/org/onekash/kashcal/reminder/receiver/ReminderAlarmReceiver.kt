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
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * BroadcastReceiver for reminder alarm triggers.
 *
 * Called by AlarmManager when a scheduled reminder fires.
 * Shows the notification with Snooze/Dismiss actions.
 *
 * Per Android best practices:
 * - Uses goAsync() for work that takes > 10ms
 * - Uses Hilt for dependency injection in receiver
 * - Keeps receiver execution fast
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderAlarmReceiver"
    }

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var notificationManager: ReminderNotificationManager

    // Scope for async work in receiver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER_ALARM) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }

        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1)
        if (reminderId == -1L) {
            Log.e(TAG, "Missing reminder ID in intent")
            return
        }

        Log.d(TAG, "Alarm fired for reminder $reminderId")

        // Use goAsync() for database access
        val pendingResult = goAsync()

        scope.launch {
            try {
                handleAlarm(reminderId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm for reminder $reminderId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAlarm(reminderId: Long) {
        // Get the reminder from database
        val reminder = reminderScheduler.getReminder(reminderId)
        if (reminder == null) {
            Log.w(TAG, "Reminder $reminderId not found in database")
            return
        }

        // Check if already dismissed or fired (avoid duplicate notifications)
        if (reminder.status == ReminderStatus.DISMISSED) {
            Log.d(TAG, "Reminder $reminderId already dismissed, skipping")
            return
        }

        // Show the notification
        notificationManager.showNotification(reminder)

        // Mark as fired
        reminderScheduler.markAsFired(reminderId)

        Log.d(TAG, "Showed notification for reminder $reminderId: ${reminder.eventTitle}")
    }
}
