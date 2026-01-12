package org.onekash.kashcal.reminder.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.reminder.receiver.ReminderActionReceiver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Builds and shows reminder notifications with Snooze/Dismiss actions.
 *
 * Responsibilities:
 * - Build notification for a scheduled reminder
 * - Format "time until event" text
 * - Create action buttons (Snooze, Dismiss)
 * - Show/cancel notifications
 *
 * Per Android notification best practices:
 * - Uses CATEGORY_REMINDER for proper system handling
 * - HIGH priority for heads-up display
 * - Action buttons for quick user response
 * - Auto-cancel on tap
 */
@Singleton
class ReminderNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: ReminderNotificationChannels
) {
    companion object {
        private const val TAG = "ReminderNotificationMgr"

        // Intent actions
        const val ACTION_SNOOZE = "org.onekash.kashcal.SNOOZE_REMINDER"
        const val ACTION_DISMISS = "org.onekash.kashcal.DISMISS_REMINDER"
        const val ACTION_SHOW_EVENT = "org.onekash.kashcal.SHOW_REMINDER_EVENT"

        // Intent extras
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SNOOZE_DURATION_MINUTES = "snooze_duration_minutes"
        const val EXTRA_EVENT_ID = "reminder_event_id"
        const val EXTRA_OCCURRENCE_TS = "reminder_occurrence_ts"

        // Default snooze duration
        const val DEFAULT_SNOOZE_MINUTES = 15

        // Request code ranges for non-overlapping PendingIntents
        // Each range supports 700M reminders before wraparound (prevents overflow)
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_SNOOZE = 700_000_000
        private const val REQUEST_CODE_DISMISS = 1_400_000_000
    }

    /**
     * Show notification for a reminder.
     *
     * @param reminder The scheduled reminder
     * @return The notification ID used
     */
    fun showNotification(reminder: ScheduledReminder): Int {
        val notificationId = channels.getNotificationId(reminder.id)
        val notification = buildNotification(reminder)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        notificationManager.notify(notificationId, notification)

        return notificationId
    }

    /**
     * Build a notification for a reminder.
     *
     * @param reminder The scheduled reminder
     * @return Built notification ready to show
     */
    fun buildNotification(reminder: ScheduledReminder): Notification {
        val contentText = formatNotificationContent(reminder)

        val builder = NotificationCompat.Builder(context, ReminderNotificationChannels.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.eventTitle)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setColor(reminder.calendarColor)
            .setContentIntent(createOpenAppIntent(reminder))
            .setWhen(reminder.occurrenceTime)
            .setShowWhen(true)

        // Add location if available
        reminder.eventLocation?.let { location ->
            if (location.isNotBlank()) {
                builder.setSubText(location)
            }
        }

        // Add Snooze action
        builder.addAction(
            android.R.drawable.ic_popup_reminder,
            "Snooze",
            createSnoozeIntent(reminder)
        )

        // Add Dismiss action
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Dismiss",
            createDismissIntent(reminder)
        )

        return builder.build()
    }

    /**
     * Format the notification content text.
     * Shows time until event or "now" if event is starting.
     *
     * @param reminder The scheduled reminder
     * @return Formatted content text
     */
    private fun formatNotificationContent(reminder: ScheduledReminder): String {
        val now = System.currentTimeMillis()
        val diffMs = reminder.occurrenceTime - now

        return when {
            reminder.isAllDay -> {
                // All-day events
                if (diffMs < 0) "Today" else formatTimeUntil(diffMs)
            }
            diffMs <= 0 -> "Starting now"
            diffMs < 60_000 -> "Starting in less than a minute"
            else -> "in ${formatTimeUntil(diffMs)}"
        }
    }

    /**
     * Format time duration for display.
     *
     * @param durationMs Duration in milliseconds
     * @return Human-readable duration (e.g., "15 minutes", "1 hour 30 minutes")
     */
    fun formatTimeUntil(durationMs: Long): String {
        val totalMinutes = (abs(durationMs) / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours == 0 -> {
                when (minutes) {
                    1 -> "1 minute"
                    else -> "$minutes minutes"
                }
            }
            minutes == 0 -> {
                when (hours) {
                    1 -> "1 hour"
                    else -> "$hours hours"
                }
            }
            else -> {
                val hourPart = if (hours == 1) "1 hour" else "$hours hours"
                val minutePart = if (minutes == 1) "1 minute" else "$minutes minutes"
                "$hourPart $minutePart"
            }
        }
    }

    /**
     * Create pending intent to open the app and show event quick view when notification is tapped.
     *
     * Uses explicit intent with ACTION_SHOW_EVENT to trigger deep linking.
     * Flags ensure proper task management:
     * - FLAG_ACTIVITY_SINGLE_TOP: Reuse existing MainActivity if at top (triggers onNewIntent)
     * - FLAG_ACTIVITY_CLEAR_TOP: Clear activities above MainActivity if it exists in stack
     */
    private fun createOpenAppIntent(reminder: ScheduledReminder): PendingIntent {
        val intent = Intent(context, org.onekash.kashcal.MainActivity::class.java).apply {
            action = ACTION_SHOW_EVENT
            putExtra(EXTRA_EVENT_ID, reminder.eventId)
            putExtra(EXTRA_OCCURRENCE_TS, reminder.occurrenceTime)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN + (reminder.id % 700_000_000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Create pending intent for Snooze action.
     * Uses explicit intent to avoid security vulnerability.
     */
    private fun createSnoozeIntent(reminder: ScheduledReminder): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_SNOOZE_DURATION_MINUTES, DEFAULT_SNOOZE_MINUTES)
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SNOOZE + (reminder.id % 700_000_000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Create pending intent for Dismiss action.
     * Uses explicit intent to avoid security vulnerability.
     */
    private fun createDismissIntent(reminder: ScheduledReminder): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DISMISS + (reminder.id % 700_000_000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Cancel a notification by reminder ID.
     *
     * @param reminderId The reminder ID
     */
    fun cancelNotification(reminderId: Long) {
        channels.cancelForReminder(reminderId)
    }

    /**
     * Cancel a notification by notification ID.
     *
     * @param notificationId The notification ID
     */
    fun cancelNotificationById(notificationId: Int) {
        channels.cancel(notificationId)
    }

    /**
     * Check if notifications are enabled.
     *
     * @return true if notifications can be shown
     */
    fun areNotificationsEnabled(): Boolean {
        return channels.areNotificationsEnabled() && channels.isChannelEnabled()
    }
}
