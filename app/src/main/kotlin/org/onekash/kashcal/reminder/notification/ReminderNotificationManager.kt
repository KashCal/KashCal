package org.onekash.kashcal.reminder.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.receiver.ReminderActionReceiver
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Builds and shows reminder notifications with Snooze/Dismiss actions.
 *
 * Responsibilities:
 * - Build notification for a scheduled reminder
 * - Format absolute event time for timed events
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
    private val channels: ReminderNotificationChannels,
    private val dataStore: KashCalDataStore
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
    suspend fun showNotification(reminder: ScheduledReminder): Int {
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
    suspend fun buildNotification(reminder: ScheduledReminder): Notification {
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
     *
     * For timed events: shows absolute event start time (e.g., "10:30 AM").
     * Adds date qualifier when the event is on a different day than today
     * (e.g., "Tomorrow, 10:30 AM" or "Wed Jan 15, 10:30 AM").
     *
     * For all-day events: shows "Today" or relative duration.
     *
     * The absolute time complements Android's live countdown in the
     * notification header (set via setWhen), giving users two useful
     * pieces of information: when the event starts and how long until then.
     *
     * @param reminder The scheduled reminder
     * @return Formatted content text
     */
    private suspend fun formatNotificationContent(reminder: ScheduledReminder): String {
        val diffMs = reminder.occurrenceTime - reminder.triggerTime

        return when {
            reminder.isAllDay -> {
                if (diffMs < 0) "Today" else formatTimeUntil(diffMs)
            }
            diffMs <= 0 -> "Starting now"
            else -> {
                // Format absolute event start time respecting user preference
                val timeFormatPref = dataStore.getTimeFormat()
                val is24Hour = DateFormat.is24HourFormat(context)
                val pattern = DateTimeUtils.getTimePattern(timeFormatPref, is24Hour)
                val zone = ZoneId.systemDefault()
                val eventZdt = Instant.ofEpochMilli(reminder.occurrenceTime).atZone(zone)
                val timeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                val timeStr = eventZdt.format(timeFormatter)

                // Add date qualifier if event is not today
                val today = LocalDate.now(zone)
                val eventDate = eventZdt.toLocalDate()
                when {
                    eventDate == today -> timeStr
                    eventDate == today.plusDays(1) -> "Tomorrow, $timeStr"
                    else -> {
                        val dateFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())
                        "${eventDate.format(dateFormatter)}, $timeStr"
                    }
                }
            }
        }
    }

    /**
     * Format time duration for display.
     * Used by the all-day event path in [formatNotificationContent].
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
