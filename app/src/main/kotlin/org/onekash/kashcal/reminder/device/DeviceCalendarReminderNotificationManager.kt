package org.onekash.kashcal.reminder.device

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
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
 * Builds and shows reminder notifications for device events.
 *
 * Shares the Room reminders' channel ([ReminderNotificationChannels.CHANNEL_REMINDERS]) but not
 * their notification IDs: Room reminders use 2000-11999, device reminders 20000-29999.
 *
 * @see org.onekash.kashcal.reminder.notification.ReminderNotificationManager
 */
@Singleton
class DeviceCalendarReminderNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: ReminderNotificationChannels,
    private val dataStore: KashCalDataStore
) {
    companion object {
        const val NOTIFICATION_ID_BASE = 20000

        const val ACTION_DEVICE_SNOOZE = "org.onekash.kashcal.DEVICE_SNOOZE_REMINDER"
        const val ACTION_DEVICE_DISMISS = "org.onekash.kashcal.DEVICE_DISMISS_REMINDER"
        const val ACTION_DEVICE_SHOW_EVENT = "org.onekash.kashcal.DEVICE_SHOW_EVENT"

        // Same keys as DeviceCalendarReminderScheduler's extras.
        const val EXTRA_EVENT_ID = "device_event_id"
        const val EXTRA_OCCURRENCE_TS = "device_occurrence_ts"
        const val EXTRA_CALENDAR_ID = "device_calendar_id"
        const val EXTRA_NOTIFICATION_ID = "device_notification_id"
        const val EXTRA_TITLE = "device_title"
        const val EXTRA_LOCATION = "device_location"
        const val EXTRA_IS_ALL_DAY = "device_is_all_day"
        const val EXTRA_CALENDAR_COLOR = "device_calendar_color"

        const val DEFAULT_SNOOZE_MINUTES = 15

        // One non-overlapping range of REQUEST_CODE_RANGE codes per action. Room reminder
        // PendingIntents can reuse these codes; their actions differ, so they stay distinct.
        private const val REQUEST_CODE_OPEN = 200_000
        private const val REQUEST_CODE_SNOOZE = 300_000
        private const val REQUEST_CODE_DISMISS = 400_000

        /** Bucket range for request codes. 100K buckets gives <0.01% collision at 5 events. */
        const val REQUEST_CODE_RANGE = 100_000

        fun computeOpenRequestCode(eventId: Long, occurrenceTs: Long): Int {
            return REQUEST_CODE_OPEN + abs((eventId xor occurrenceTs) % REQUEST_CODE_RANGE).toInt()
        }

        fun computeSnoozeRequestCode(eventId: Long, occurrenceTs: Long): Int {
            return REQUEST_CODE_SNOOZE + abs((eventId xor occurrenceTs) % REQUEST_CODE_RANGE).toInt()
        }

        fun computeDismissRequestCode(notificationId: Int): Int {
            return REQUEST_CODE_DISMISS + abs(notificationId % REQUEST_CODE_RANGE)
        }
    }

    /**
     * Shows the reminder for one occurrence of a device event and returns its notification ID.
     *
     * @param occurrenceTs the occurrence's start
     * @param calendarColor the notification's accent color
     * @param triggerTime when the reminder was scheduled to fire
     */
    suspend fun showNotification(
        eventId: Long,
        occurrenceTs: Long,
        title: String,
        location: String?,
        isAllDay: Boolean,
        calendarColor: Int,
        calendarId: Long,
        triggerTime: Long
    ): Int {
        val notificationId = getNotificationId(eventId, occurrenceTs)
        val notification = buildNotification(
            eventId = eventId,
            occurrenceTs = occurrenceTs,
            title = title,
            location = location,
            isAllDay = isAllDay,
            calendarColor = calendarColor,
            calendarId = calendarId,
            triggerTime = triggerTime,
            notificationId = notificationId
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        notificationManager.notify(notificationId, notification)

        return notificationId
    }

    /** Builds the reminder notification with Snooze and Dismiss actions. */
    @VisibleForTesting
    internal suspend fun buildNotification(
        eventId: Long,
        occurrenceTs: Long,
        title: String,
        location: String?,
        isAllDay: Boolean,
        calendarColor: Int,
        calendarId: Long,
        triggerTime: Long,
        notificationId: Int
    ): Notification {
        val contentText = formatNotificationContent(occurrenceTs, triggerTime, isAllDay)

        val builder = NotificationCompat.Builder(context, ReminderNotificationChannels.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setColor(calendarColor)
            .setContentIntent(createOpenAppIntent(eventId, occurrenceTs, calendarId))

        // An all-day occurrenceTs is UTC midnight, which the header would show as a
        // zone-shifted clock time, so all-day reminders hide it and the body's relative day
        // stands alone. Timed reminders show it as a countdown to the start.
        if (!isAllDay) {
            builder
                .setWhen(occurrenceTs + 30_000L)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        if (!location.isNullOrBlank()) {
            builder.setSubText(location)
        }

        builder.addAction(
            android.R.drawable.ic_popup_reminder,
            context.getString(R.string.action_snooze),
            createSnoozeIntent(
                eventId = eventId,
                occurrenceTs = occurrenceTs,
                title = title,
                location = location,
                isAllDay = isAllDay,
                calendarColor = calendarColor,
                calendarId = calendarId,
                notificationId = notificationId
            )
        )

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.action_dismiss),
            createDismissIntent(notificationId)
        )

        return builder.build()
    }

    /**
     * Formats the body. A timed event shows its start time, with "Tomorrow" or the date when it
     * isn't today, or "Starting now" when the reminder is due at or after the start. An all-day
     * event shows the relative day.
     */
    private suspend fun formatNotificationContent(
        occurrenceTs: Long,
        triggerTime: Long,
        isAllDay: Boolean
    ): String {
        val diffMs = occurrenceTs - triggerTime

        return when {
            isAllDay -> {
                // Today, Tomorrow or In N days, from the event's date.
                when (val days = DateTimeUtils.allDayRelativeDays(occurrenceTs, triggerTime)) {
                    0 -> context.getString(R.string.label_today)
                    1 -> context.getString(R.string.label_tomorrow)
                    else -> context.resources.getQuantityString(R.plurals.reminder_in_days, days, days)
                }
            }
            diffMs <= 0 -> context.getString(R.string.notification_starting_now)
            else -> {
                val timeFormatPref = dataStore.getTimeFormat()
                val is24Hour = DateFormat.is24HourFormat(context)
                val pattern = DateTimeUtils.getTimePattern(timeFormatPref, is24Hour)
                val zone = ZoneId.systemDefault()
                val eventZdt = Instant.ofEpochMilli(occurrenceTs).atZone(zone)
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

    /**
     * Returns the notification ID for one occurrence, in 20000-29999. Keyed on the occurrence
     * so reminders for different occurrences of one event don't replace each other; distinct
     * occurrences can still share an ID.
     */
    fun getNotificationId(eventId: Long, occurrenceTs: Long): Int {
        val combined = (eventId xor (occurrenceTs / 60000)) % 10000
        return (NOTIFICATION_ID_BASE + combined).toInt()
    }

    /** Creates the tap intent, which opens the device event's quick view in MainActivity. */
    private fun createOpenAppIntent(eventId: Long, occurrenceTs: Long, calendarId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_DEVICE_SHOW_EVENT
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OCCURRENCE_TS, occurrenceTs)
            putExtra(EXTRA_CALENDAR_ID, calendarId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val requestCode = computeOpenRequestCode(eventId, occurrenceTs)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Creates the Snooze intent; it carries the whole event, which the snooze alarm needs. */
    private fun createSnoozeIntent(
        eventId: Long,
        occurrenceTs: Long,
        title: String,
        location: String?,
        isAllDay: Boolean,
        calendarColor: Int,
        calendarId: Long,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, DeviceCalendarReminderActionReceiver::class.java).apply {
            action = ACTION_DEVICE_SNOOZE
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OCCURRENCE_TS, occurrenceTs)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_LOCATION, location)
            putExtra(EXTRA_IS_ALL_DAY, isAllDay)
            putExtra(EXTRA_CALENDAR_COLOR, calendarColor)
            putExtra(EXTRA_CALENDAR_ID, calendarId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val requestCode = computeSnoozeRequestCode(eventId, occurrenceTs)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createDismissIntent(notificationId: Int): PendingIntent {
        val intent = Intent(context, DeviceCalendarReminderActionReceiver::class.java).apply {
            action = ACTION_DEVICE_DISMISS
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val requestCode = computeDismissRequestCode(notificationId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelNotification(notificationId: Int) {
        channels.cancel(notificationId)
    }

    /** Returns true when app notifications and the reminders channel are both enabled. */
    fun areNotificationsEnabled(): Boolean {
        return channels.areNotificationsEnabled() && channels.isChannelEnabled()
    }
}
