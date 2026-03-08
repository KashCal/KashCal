package org.onekash.kashcal.widget

import android.util.Log
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * Handles widget updates and reminder rescheduling after timezone or time changes.
 *
 * Extracted from TimezoneChangeReceiver to enable unit testing without
 * Hilt injection or Android framework dependencies.
 */
class TimezoneChangeHandler @Inject constructor(
    private val widgetUpdateManager: WidgetUpdateManager,
    private val reminderScheduler: ReminderScheduler,
    private val deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
) {
    companion object {
        private const val TAG = "TimezoneChangeHandler"
    }

    /**
     * Update widgets and reschedule reminders for the new timezone/time.
     *
     * @param reason "timezone_changed" or "time_changed" — passed through to widget update
     */
    suspend fun handleChange(reason: String) {
        widgetUpdateManager.updateAllWidgets(reason = reason)

        // Reschedule Room reminders
        reminderScheduler.rescheduleAllPending()
        Log.d(TAG, "Successfully updated widgets and rescheduled Room reminders")

        // Reschedule device calendar reminders
        try {
            deviceCalendarReminderScheduler.scheduleNextReminder()
            Log.d(TAG, "Successfully rescheduled device calendar reminders")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule device calendar reminders", e)
        }
    }
}
