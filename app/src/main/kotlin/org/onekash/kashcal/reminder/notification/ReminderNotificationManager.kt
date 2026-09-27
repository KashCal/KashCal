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

/**
 * Builds, shows and cancels Room event reminder notifications, with Snooze and Dismiss actions.
 *
 * Notifications are CATEGORY_REMINDER, PRIORITY_HIGH for heads-up, and clear on tap. Every
 * PendingIntent names its target class (CWE-927).
 */
@Singleton
class ReminderNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: ReminderNotificationChannels,
    private val dataStore: KashCalDataStore
) {
    companion object {
        const val ACTION_SNOOZE = "org.onekash.kashcal.SNOOZE_REMINDER"
        const val ACTION_DISMISS = "org.onekash.kashcal.DISMISS_REMINDER"
        const val ACTION_SHOW_EVENT = "org.onekash.kashcal.SHOW_REMINDER_EVENT"

        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SNOOZE_DURATION_MINUTES = "snooze_duration_minutes"
        const val EXTRA_EVENT_ID = "reminder_event_id"
        const val EXTRA_OCCURRENCE_TS = "reminder_occurrence_ts"

        const val DEFAULT_SNOOZE_MINUTES = 15

        // One range of 700M request codes per action; the ids wrap within it, so the highest
        // code stays below Int.MAX_VALUE.
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_SNOOZE = 700_000_000
        private const val REQUEST_CODE_DISMISS = 1_400_000_000
    }

    /**
     * Builds and posts the notification for [reminder] and returns its ID.
     *
     * The id is derived from the reminder row, so an occurrence with several
     * reminders posts one notification per offset unless the caller clears the
     * others first, as the alarm path does.
     */
    suspend fun showNotification(reminder: ScheduledReminder): Int {
        return postNotification(reminder, buildNotification(reminder))
    }

    /**
     * Posts an already-built [notification] for [reminder] and returns its ID.
     *
     * Deliberately not a suspending function. Composing the content reads
     * preferences and can suspend; posting must not, so a caller that clears the
     * occurrence's other notifications first has no suspension point between the
     * clear and the post. Without that, a cancelled job (the alarm handler's
     * timeout expiring, say) could clear the notification already on screen and
     * then never post its replacement, leaving the user with nothing.
     */
    fun postNotification(reminder: ScheduledReminder, notification: Notification): Int {
        val notificationId = channels.getNotificationId(reminder.id)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        notificationManager.notify(notificationId, notification)

        return notificationId
    }

    /** Builds the notification for [reminder]; it can suspend to read the time format. */
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

        // An all-day occurrenceTime is UTC midnight, which the header would show as a
        // zone-shifted clock time, so all-day reminders hide it and the body's relative day
        // stands alone. Timed reminders show it as a countdown to the start.
        if (!reminder.isAllDay) {
            builder
                .setWhen(reminder.occurrenceTime + 30_000L)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        reminder.eventLocation?.let { location ->
            if (location.isNotBlank()) {
                builder.setSubText(location)
            }
        }

        builder.addAction(
            android.R.drawable.ic_popup_reminder,
            context.getString(R.string.action_snooze),
            createSnoozeIntent(reminder)
        )

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.action_dismiss),
            createDismissIntent(reminder)
        )

        return builder.build()
    }

    /**
     * Formats the body text.
     *
     * A timed event shows its start time ("10:30 AM"), with a date qualifier when it isn't today
     * ("Tomorrow, 10:30 AM", "Wed Jan 15, 10:30 AM"), or "Starting now" when the reminder is due
     * at or after the start. The header's countdown adds how long until then. An all-day event
     * shows [formatAllDayRelative].
     */
    private suspend fun formatNotificationContent(reminder: ScheduledReminder): String {
        val diffMs = reminder.occurrenceTime - reminder.triggerTime

        return when {
            reminder.isAllDay -> {
                formatAllDayRelative(reminder.occurrenceTime, reminder.triggerTime)
            }
            diffMs <= 0 -> context.getString(R.string.notification_starting_now)
            else -> {
                // The 12/24-hour preference; its "system" value follows the device setting.
                val timeFormatPref = dataStore.getTimeFormat()
                val is24Hour = DateFormat.is24HourFormat(context)
                val pattern = DateTimeUtils.getTimePattern(timeFormatPref, is24Hour)
                val zone = ZoneId.systemDefault()
                val eventZdt = Instant.ofEpochMilli(reminder.occurrenceTime).atZone(zone)
                val timeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                val timeStr = eventZdt.format(timeFormatter)

                val today = LocalDate.now(zone)
                val eventDate = eventZdt.toLocalDate()
                when {
                    eventDate == today -> timeStr
                    eventDate == today.plusDays(1) -> context.getString(R.string.notification_tomorrow_time, timeStr)
                    else -> {
                        val dateFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEMMMd"), Locale.getDefault())
                        context.getString(R.string.notification_date_time, eventDate.format(dateFormatter), timeStr)
                    }
                }
            }
        }
    }

    /** Returns an all-day reminder's body: "Today", "Tomorrow" or "In N days". */
    fun formatAllDayRelative(occurrenceTimeUtcMidnight: Long, triggerTime: Long): String {
        return when (val days = DateTimeUtils.allDayRelativeDays(occurrenceTimeUtcMidnight, triggerTime)) {
            0 -> context.getString(R.string.label_today)
            1 -> context.getString(R.string.label_tomorrow)
            else -> context.resources.getQuantityString(R.plurals.reminder_in_days, days, days)
        }
    }

    /**
     * Creates the tap intent, which opens the event's quick view in MainActivity.
     *
     * SINGLE_TOP reuses a MainActivity already on top (it gets onNewIntent); CLEAR_TOP closes
     * the activities above an existing MainActivity.
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

    fun cancelNotification(reminderId: Long) {
        channels.cancelForReminder(reminderId)
    }

    fun cancelNotificationById(notificationId: Int) {
        channels.cancel(notificationId)
    }

    /** Returns true when app notifications and the reminders channel are both enabled. */
    fun areNotificationsEnabled(): Boolean {
        return channels.areNotificationsEnabled() && channels.isChannelEnabled()
    }
}
