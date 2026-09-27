package org.onekash.kashcal.widget

import android.util.Log
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * Updates widgets and reschedules reminders after a timezone or clock change.
 *
 * Kept apart from [TimezoneChangeReceiver] so it can be unit-tested without Hilt injection or
 * the Android framework.
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
     * Updates every widget but DateWidget, then reschedules Room and device calendar reminders.
     *
     * Widget and device calendar failures are logged and swallowed; a Room reschedule failure
     * propagates.
     *
     * @param reason "timezone_changed" or "time_changed", passed through to the widget update.
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
