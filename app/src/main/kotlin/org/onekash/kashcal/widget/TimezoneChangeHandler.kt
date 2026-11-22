package org.onekash.kashcal.widget

import android.util.Log
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.ui.appicon.DateIconRefresher
import org.onekash.kashcal.util.ZoneSource
import javax.inject.Inject

/**
 * Refreshes the app's zone, moves the date icon, re-arms the midnight alarm, updates widgets and
 * reschedules reminders after a timezone or clock change.
 *
 * Kept apart from [TimezoneChangeReceiver] so it can be unit-tested without Hilt injection or
 * the Android framework.
 */
class TimezoneChangeHandler @Inject constructor(
    private val widgetUpdateManager: WidgetUpdateManager,
    private val reminderScheduler: ReminderScheduler,
    private val deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler,
    private val dateIconRefresher: DateIconRefresher,
    /** Hilt passes the app's singleton; the default is for tests that build the handler. */
    private val zoneSource: ZoneSource = ZoneSource(),
) {
    companion object {
        private const val TAG = "TimezoneChangeHandler"
    }

    /**
     * Refreshes [ZoneSource] so open screens re-read in the new zone, moves the date icon to the
     * new local day, re-arms the midnight alarm for the new zone (it was set for midnight in the
     * old one), updates every widget but DateWidget, then reschedules Room and device calendar
     * reminders.
     *
     * Date icon, widget and device calendar failures are logged and swallowed; a Room reschedule
     * failure propagates. The zone and the icon go first so a reschedule failure or the
     * receiver's timeout can't skip them.
     *
     * @param reason "timezone_changed" or "time_changed", passed through to the widget update.
     */
    suspend fun handleChange(reason: String) {
        zoneSource.refresh()
        try {
            dateIconRefresher.refresh()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh the date icon", e)
        }
        widgetUpdateManager.scheduleMidnightUpdate()
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
