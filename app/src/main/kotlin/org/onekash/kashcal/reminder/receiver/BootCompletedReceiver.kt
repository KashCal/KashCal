package org.onekash.kashcal.reminder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject

/**
 * BroadcastReceiver for device boot and app update events.
 *
 * Reschedules all pending reminders after:
 * - Device boot (BOOT_COMPLETED)
 * - App update (MY_PACKAGE_REPLACED)
 *
 * This is critical because AlarmManager alarms are cleared on:
 * - Device reboot
 * - App update/reinstall
 *
 * Per Android best practices:
 * - Uses goAsync() for work that takes > 10ms
 * - Reschedules from persistent database storage
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    // Scope for async work in receiver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device boot completed, rescheduling reminders")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App updated, rescheduling reminders")
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
                return
            }
        }

        // Use goAsync() for database access and alarm scheduling
        val pendingResult = goAsync()

        scope.launch {
            try {
                // Reschedule all pending reminders
                reminderScheduler.rescheduleAllPending()

                // Clean up old reminders while we're at it
                reminderScheduler.cleanupOldReminders()

                Log.d(TAG, "Successfully rescheduled all pending reminders")
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling reminders after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
