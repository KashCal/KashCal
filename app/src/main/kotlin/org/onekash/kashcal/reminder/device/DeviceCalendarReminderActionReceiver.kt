package org.onekash.kashcal.reminder.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import org.onekash.kashcal.util.maskEventId
import javax.inject.Inject

/**
 * BroadcastReceiver for device calendar reminder actions (Snooze/Dismiss).
 *
 * Handles:
 * - ACTION_DEVICE_SNOOZE: Snooze reminder for 15 minutes
 * - ACTION_DEVICE_DISMISS: Dismiss notification
 *
 * @see DeviceCalendarReminderNotificationManager for notification creation
 */
@AndroidEntryPoint
class DeviceCalendarReminderActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DeviceCalActionReceiver"
    }

    @Inject
    lateinit var notificationManager: DeviceCalendarReminderNotificationManager

    @Inject
    lateinit var scheduler: DeviceCalendarReminderScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_SNOOZE -> {
                handleSnooze(intent)
            }
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_DISMISS -> {
                handleDismiss(intent)
            }
            else -> {
                Log.d(TAG, "Ignoring intent with action: ${intent?.action}")
            }
        }
    }

    private fun handleSnooze(intent: Intent) {
        val eventId = intent.getLongExtra(DeviceCalendarReminderNotificationManager.EXTRA_EVENT_ID, -1L)
        val occurrenceTs = intent.getLongExtra(DeviceCalendarReminderNotificationManager.EXTRA_OCCURRENCE_TS, -1L)
        val title = intent.getStringExtra(DeviceCalendarReminderNotificationManager.EXTRA_TITLE) ?: "Event"
        val location = intent.getStringExtra(DeviceCalendarReminderNotificationManager.EXTRA_LOCATION)
        val isAllDay = intent.getBooleanExtra(DeviceCalendarReminderNotificationManager.EXTRA_IS_ALL_DAY, false)
        val calendarColor = intent.getIntExtra(DeviceCalendarReminderNotificationManager.EXTRA_CALENDAR_COLOR, 0)
        val calendarId = intent.getLongExtra(DeviceCalendarReminderNotificationManager.EXTRA_CALENDAR_ID, -1L)
        val notificationId = intent.getIntExtra(DeviceCalendarReminderNotificationManager.EXTRA_NOTIFICATION_ID, -1)

        if (notificationId == -1) {
            Log.w(TAG, "Missing notification ID for snooze")
            return
        }

        if (eventId == -1L || occurrenceTs == -1L) {
            Log.w(TAG, "Missing event data for snooze")
            notificationManager.cancelNotification(notificationId)
            return
        }

        Log.d(TAG, "Snoozing device calendar reminder for event ${eventId.maskEventId()}")

        // Cancel current notification
        notificationManager.cancelNotification(notificationId)

        // Schedule snooze alarm with all event data
        scheduler.scheduleSnooze(
            eventId = eventId,
            occurrenceTs = occurrenceTs,
            title = title,
            location = location,
            isAllDay = isAllDay,
            calendarColor = calendarColor,
            calendarId = calendarId,
            snoozeDurationMinutes = DeviceCalendarReminderNotificationManager.DEFAULT_SNOOZE_MINUTES
        )

        Log.d(TAG, "Snooze scheduled for ${DeviceCalendarReminderNotificationManager.DEFAULT_SNOOZE_MINUTES} minutes")
    }

    private fun handleDismiss(intent: Intent) {
        val notificationId = intent.getIntExtra(DeviceCalendarReminderNotificationManager.EXTRA_NOTIFICATION_ID, -1)

        if (notificationId == -1) {
            Log.w(TAG, "Missing notification ID for dismiss")
            return
        }

        Log.d(TAG, "Dismissing device calendar reminder notification $notificationId")
        notificationManager.cancelNotification(notificationId)
    }
}
