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
 * Builds and shows notifications for device calendar reminders.
 *
 * Uses the same notification channel as Room reminders but different notification ID range
 * to avoid collisions:
 * - Room reminders: 2000-11999
 * - Device calendar reminders: 20000-29999
 *
 * @see ReminderNotificationManager for Room reminder notifications
 */
@Singleton
class DeviceCalendarReminderNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: ReminderNotificationChannels,
    private val dataStore: KashCalDataStore
) {
    companion object {
        // Notification ID base - separate range from Room reminders (2000-11999)
        const val NOTIFICATION_ID_BASE = 20000

        // Intent actions for device calendar reminders
        const val ACTION_DEVICE_SNOOZE = "org.onekash.kashcal.DEVICE_SNOOZE_REMINDER"
        const val ACTION_DEVICE_DISMISS = "org.onekash.kashcal.DEVICE_DISMISS_REMINDER"
        const val ACTION_DEVICE_SHOW_EVENT = "org.onekash.kashcal.DEVICE_SHOW_EVENT"

        // Intent extras
        const val EXTRA_EVENT_ID = "device_event_id"
        const val EXTRA_OCCURRENCE_TS = "device_occurrence_ts"
        const val EXTRA_CALENDAR_ID = "device_calendar_id"
        const val EXTRA_NOTIFICATION_ID = "device_notification_id"
        const val EXTRA_TITLE = "device_title"
        const val EXTRA_LOCATION = "device_location"
        const val EXTRA_IS_ALL_DAY = "device_is_all_day"
        const val EXTRA_CALENDAR_COLOR = "device_calendar_color"

        // Default snooze duration
        const val DEFAULT_SNOOZE_MINUTES = 15

        // Request code ranges for non-overlapping PendingIntents.
        // Each range has 100K buckets with 100K spacing between bases.
        // (Room reminders use 0-2.1B range partitioned by action type with different components.)
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
     * Show notification for a device calendar reminder.
     *
     * @param eventId Device calendar event ID
     * @param occurrenceTs Event occurrence start timestamp
     * @param title Event title
     * @param location Event location (optional)
     * @param isAllDay Whether this is an all-day event
     * @param calendarColor Calendar color for notification accent
     * @param calendarId Calendar ID (for deep linking)
     * @param triggerTime When the reminder was scheduled to fire
     * @return The notification ID used
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

    /**
     * Build a notification for a device calendar reminder.
     */
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
            .setWhen(occurrenceTs + 30_000L)
            .setShowWhen(true)

        // Add location if available
        if (!location.isNullOrBlank()) {
            builder.setSubText(location)
        }

        // Add Snooze action
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

        // Add Dismiss action
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.action_dismiss),
            createDismissIntent(notificationId)
        )

        return builder.build()
    }

    /**
     * Format the notification content text.
     *
     * For timed events: shows absolute event start time.
     * For all-day events: shows relative duration.
     */
    private suspend fun formatNotificationContent(
        occurrenceTs: Long,
        triggerTime: Long,
        isAllDay: Boolean
    ): String {
        val diffMs = occurrenceTs - triggerTime

        return when {
            isAllDay -> {
                if (diffMs < 0) context.getString(R.string.label_today) else formatTimeUntil(diffMs)
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
     * Format time duration for display.
     */
    private fun formatTimeUntil(durationMs: Long): String {
        val totalMinutes = (abs(durationMs) / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours == 0 -> context.resources.getQuantityString(R.plurals.time_minutes, minutes, minutes)
            minutes == 0 -> context.resources.getQuantityString(R.plurals.time_hours, hours, hours)
            else -> {
                val hourPart = context.resources.getQuantityString(R.plurals.time_hours, hours, hours)
                val minutePart = context.resources.getQuantityString(R.plurals.time_minutes, minutes, minutes)
                "$hourPart $minutePart"
            }
        }
    }

    /**
     * Generate unique notification ID from event ID and occurrence timestamp.
     * Uses composite key to support multiple reminders for same event (different occurrences).
     */
    fun getNotificationId(eventId: Long, occurrenceTs: Long): Int {
        // Combine eventId and occurrenceTs for uniqueness, keep within 10000 range
        val combined = (eventId xor (occurrenceTs / 60000)) % 10000
        return (NOTIFICATION_ID_BASE + combined).toInt()
    }

    /**
     * Create pending intent to open the app when notification is tapped.
     * Note: Deep linking to device calendar events is limited - opens app at current day.
     */
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

    /**
     * Create pending intent for Snooze action.
     * Includes all event data so the snoozed reminder can be rescheduled with correct info.
     */
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

    /**
     * Create pending intent for Dismiss action.
     */
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

    /**
     * Cancel a notification by ID.
     */
    fun cancelNotification(notificationId: Int) {
        channels.cancel(notificationId)
    }

    /**
     * Check if notifications are enabled.
     */
    fun areNotificationsEnabled(): Boolean {
        return channels.areNotificationsEnabled() && channels.isChannelEnabled()
    }
}
