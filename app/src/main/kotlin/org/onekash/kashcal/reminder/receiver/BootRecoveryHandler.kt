package org.onekash.kashcal.reminder.receiver

import android.util.Log
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * Handles reminder recovery after device boot or app update.
 *
 * Extracted from BootCompletedReceiver to enable unit testing without
 * Hilt injection or Android framework dependencies.
 */
class BootRecoveryHandler @Inject constructor(
    private val reminderScheduler: ReminderScheduler
) {
    companion object {
        private const val TAG = "BootRecoveryHandler"
    }

    /**
     * Reschedule all pending reminders and clean up old ones.
     *
     * Called after device boot or app update when Android clears all AlarmManager alarms.
     * Re-registers alarms for ScheduledReminder rows that already exist in the DB.
     */
    suspend fun rescheduleReminders() {
        reminderScheduler.rescheduleAllPending()
        reminderScheduler.cleanupOldReminders()
        Log.d(TAG, "Successfully rescheduled all pending reminders")
    }
}
