package org.onekash.kashcal.reminder.receiver

import android.util.Log
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

/**
 * Re-arms reminders and the widget midnight alarm after device boot or app update.
 *
 * Kept out of [BootCompletedReceiver] so it can be unit tested without Hilt injection or a
 * BroadcastReceiver.
 */
class BootRecoveryHandler @Inject constructor(
    private val reminderScheduler: ReminderScheduler,
    private val deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler,
    private val widgetUpdateManager: WidgetUpdateManager
) {
    companion object {
        private const val TAG = "BootRecoveryHandler"
    }

    /**
     * Re-arms the reminder and widget alarms that boot or an app update cleared.
     *
     * Re-arms the existing Room reminder rows due within the scheduler's window
     * ([ReminderScheduler.rescheduleAllPending]) and deletes old fired and dismissed rows, then
     * schedules the next device calendar reminder (one alarm at a time) and the widget
     * day-rollover alarm. A device calendar failure is logged and skipped; a Room failure throws
     * before the device calendar and widget alarms are set.
     */
    suspend fun rescheduleReminders() {
        // Room reminders: reschedule from database
        reminderScheduler.rescheduleAllPending()
        reminderScheduler.cleanupOldReminders()
        Log.d(TAG, "Successfully rescheduled Room reminders")

        // Device calendar reminders: re-query and schedule next
        try {
            deviceCalendarReminderScheduler.scheduleNextReminder()
            Log.d(TAG, "Successfully rescheduled device calendar reminders")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule device calendar reminders", e)
        }

        // Widget midnight alarm
        widgetUpdateManager.scheduleMidnightUpdate()
        Log.d(TAG, "Rescheduled widget midnight alarm")
    }
}
