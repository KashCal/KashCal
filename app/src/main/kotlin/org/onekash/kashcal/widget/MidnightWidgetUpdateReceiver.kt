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
import org.onekash.kashcal.ui.appicon.DateIconRefresher
import javax.inject.Inject

/**
 * Receives the midnight rollover alarm: moves the date launcher icon to the new day and refreshes
 * the widgets.
 *
 * AlarmManager.setExactAndAllowWhileIdle() fires through Doze, unlike WorkManager, which is
 * deferred until the next maintenance window. This alarm is what rolls the Agenda widget and the
 * date icon over to the new day when the phone sat idle overnight, for example in airplane mode.
 */
@AndroidEntryPoint
class MidnightWidgetUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MidnightWidgetUpdate"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var widgetUpdateManager: WidgetUpdateManager

    @Inject
    lateinit var dateIconRefresher: DateIconRefresher

    override fun onReceive(context: Context, intent: Intent?) {
        handleMidnight(widgetUpdateManager, dateIconRefresher, goAsync())
    }

    /**
     * Re-arms tomorrow's alarm, then moves the date icon and refreshes the widgets in the
     * background. The icon step runs before, and outside, the widget timeout, so a slow widget
     * refresh can't cut it short, and a failure there doesn't stop the widgets.
     *
     * Takes its collaborators explicitly because the generated Hilt onReceive re-injects fields
     * on every dispatch, and goAsync() is null when a test calls onReceive directly.
     */
    internal fun handleMidnight(
        widgetUpdateManager: WidgetUpdateManager,
        dateIconRefresher: DateIconRefresher,
        pendingResult: PendingResult?
    ): Job {
        Log.d(TAG, "Midnight alarm fired, refreshing widgets")

        // Reschedule first so a failure in updateAllWidgets doesn't lose tomorrow's alarm.
        widgetUpdateManager.scheduleMidnightUpdate()

        return CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dateIconRefresher.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing the date icon at midnight", e)
            }
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
