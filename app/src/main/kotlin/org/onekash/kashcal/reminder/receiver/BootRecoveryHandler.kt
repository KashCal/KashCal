package org.onekash.kashcal.reminder.receiver

import android.util.Log
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

/**
 * Handles reminder recovery after device boot or app update.
 *
 * Extracted from BootCompletedReceiver to enable unit testing without
 * Hilt injection or Android framework dependencies.
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
     * Reschedule all pending reminders and the widget midnight alarm, and clean up old reminders.
     *
     * Called after device boot or app update when Android clears all AlarmManager alarms.
     * Re-registers alarms for ScheduledReminder rows that already exist in the DB.
     * Also reschedules device calendar reminders (single-alarm model) and the
     * widget day-rollover alarm (also cleared by boot/update).
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

        // Widget midnight alarm: AlarmManager clears all alarms on reboot / package replace
        widgetUpdateManager.scheduleMidnightUpdate()
        Log.d(TAG, "Rescheduled widget midnight alarm")
    }
}
