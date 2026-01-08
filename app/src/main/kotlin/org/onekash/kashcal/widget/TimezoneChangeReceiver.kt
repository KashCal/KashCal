package org.onekash.kashcal.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Triggers widget update when device timezone or time changes.
 * Ensures event times display correctly after travel or DST transitions.
 */
class TimezoneChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimezoneChangeReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val reason = when (intent?.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            Intent.ACTION_TIME_CHANGED -> "time_changed"
            else -> return
        }

        Log.i(TAG, "Received $reason, triggering widget update")

        // Use goAsync() for broadcast receiver coroutine safety
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetUpdateManager(context).updateAllWidgets(reason = reason)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
