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
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.reminder.worker.ReminderRefreshWorker
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
 *
 * Recovery is two-phase:
 * 1. Immediate: rescheduleAllPending() re-registers alarms for existing ScheduledReminder rows
 * 2. Deferred: ReminderRefreshWorker creates missing rows for events that had reminders
 *    fired/dismissed/cleaned up (runs via WorkManager, not subject to 10s receiver limit)
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var handler: BootRecoveryHandler

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

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    handler.rescheduleReminders()
                }
                if (completed == null) {
                    Log.w(TAG, "Reminder reschedule timed out, WorkManager will complete")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling reminders after boot", e)
            } finally {
                pendingResult.finish()
            }
        }

        // Trigger immediate reminder refresh to create ScheduledReminder rows
        // for events that are missing them. Runs via WorkManager (no 10s limit).
        try {
            ReminderRefreshWorker.runNow(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger reminder refresh worker", e)
        }
    }
}
