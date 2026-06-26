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
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.util.maskEventId
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
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var notificationManager: ReminderNotificationManager

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

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    handleAlarm(reminderScheduler, notificationManager, reminderId)
                }
                if (completed == null) {
                    Log.w(TAG, "Alarm handling timed out for reminder $reminderId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm for reminder $reminderId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Extracted for test access: `@AndroidEntryPoint`'s generated `onReceive`
     * re-runs field injection on every dispatch, clobbering any values set
     * manually by a test. Callers must pass the dependencies explicitly.
     */
    internal suspend fun handleAlarm(
        reminderScheduler: ReminderScheduler,
        notificationManager: ReminderNotificationManager,
        reminderId: Long,
    ) {
        // Get the reminder from database
        val reminder = reminderScheduler.getReminder(reminderId)
        if (reminder == null) {
            Log.w(TAG, "Reminder $reminderId not found in database")
            return
        }

        // Check if already dismissed (avoid duplicate notifications)
        if (reminder.status == ReminderStatus.DISMISSED) {
            Log.d(TAG, "Reminder $reminderId already dismissed, skipping")
            return
        }

        // The reminder row denormalizes event data, so it can fire "blind"
        // after its whole event was deleted or soft-deleted (e.g. a CalDAV
        // delete not yet pushed, or a server-side event delete pulled in the
        // background). Suppress the notification and clean up the stale row +
        // sibling alarms.
        if (!reminderScheduler.shouldFireReminder(reminder.eventId)) {
            Log.d(TAG, "Suppressed stale reminder $reminderId for event ${reminder.eventId.maskEventId()}")
            reminderScheduler.cancelRemindersForEvent(reminder.eventId)
            return
        }

        // The whole event is still live, but this reminder is for ONE occurrence
        // of it — and that single instance may have been cancelled (an organizer
        // skipped one meeting of a recurring series, pulled in via CalDAV as an
        // EXDATE or a cancelled exception). Suppress this slot's notification and
        // clean up only its row + alarm; the series' other live occurrences keep
        // their reminders.
        if (!reminderScheduler.hasLiveOccurrenceForReminder(reminder)) {
            Log.d(TAG, "Suppressed reminder $reminderId for cancelled occurrence of event ${reminder.eventId.maskEventId()}")
            reminderScheduler.cancelReminderForOccurrence(reminder.eventId, reminder.occurrenceTime)
            return
        }

        // Show the notification
        notificationManager.showNotification(reminder)

        // Mark as fired
        reminderScheduler.markAsFired(reminderId)

        Log.d(TAG, "Showed notification for reminder $reminderId: ${reminder.eventTitle}")
    }
}
