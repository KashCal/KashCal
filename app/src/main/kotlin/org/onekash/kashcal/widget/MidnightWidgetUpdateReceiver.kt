package org.onekash.kashcal.widget

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
import javax.inject.Inject

/**
 * BroadcastReceiver for the midnight widget refresh alarm.
 *
 * AlarmManager.setExactAndAllowWhileIdle() fires through Doze, unlike
 * WorkManager, which is deferred until the next maintenance window.
 * This is what makes the Agenda widget roll over to today's date even
 * when the phone has been idle (e.g. airplane mode) overnight.
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
        Log.d(TAG, "Midnight alarm fired, refreshing widgets")

        // Reschedule BEFORE updating so a failure in updateAllWidgets doesn't orphan tomorrow's alarm.
        widgetUpdateManager.scheduleMidnightUpdate()

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
                pendingResult.finish()
            }
        }
    }
}
