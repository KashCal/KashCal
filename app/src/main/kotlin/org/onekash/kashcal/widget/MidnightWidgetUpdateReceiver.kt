package org.onekash.kashcal.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Receives the midnight widget refresh alarm.
 *
 * AlarmManager.setExactAndAllowWhileIdle() fires through Doze, unlike WorkManager, which is
 * deferred until the next maintenance window. This alarm is what rolls the Agenda widget over
 * to the new day when the phone sat idle overnight, for example in airplane mode.
 */
@AndroidEntryPoint
class MidnightWidgetUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MidnightWidgetUpdate"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var widgetUpdateManager: WidgetUpdateManager

    override fun onReceive(context: Context, intent: Intent?) {
        handleMidnight(widgetUpdateManager, goAsync())
    }

    /**
     * Re-arms tomorrow's alarm, then refreshes the widgets in the background.
     *
     * Takes its collaborators explicitly because the generated Hilt onReceive re-injects fields
     * on every dispatch, and goAsync() is null when a test calls onReceive directly.
     */
    internal fun handleMidnight(
        widgetUpdateManager: WidgetUpdateManager,
        pendingResult: PendingResult?
    ): Job {
        Log.d(TAG, "Midnight alarm fired, refreshing widgets")

        // Reschedule first so a failure in updateAllWidgets doesn't lose tomorrow's alarm.
        widgetUpdateManager.scheduleMidnightUpdate()

        return CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    widgetUpdateManager.updateAllWidgets("midnight")
                }
                if (completed == null) {
                    Log.w(TAG, "Midnight widget update timed out")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widgets at midnight", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
