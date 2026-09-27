package org.onekash.kashcal.data.calendar_provider

import android.database.ContentObserver
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onCalendarChanged] once CalendarProvider changes have been quiet for [debounceMs].
 *
 * The debounce defaults to 3 seconds because a sync adapter writes many events in quick
 * succession during a sync. `selfChange` isn't filtered: the app writes to CalendarProvider
 * too, and its own writes need a UI refresh.
 */
class CalendarProviderObserver(
    handler: Handler,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 3000L,
    private val onCalendarChanged: () -> Unit
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "CalProviderObserver"
    }

    private var debounceJob: Job? = null

    override fun onChange(selfChange: Boolean) {
        Log.d(TAG, "Calendar data changed (selfChange=$selfChange)")

        debounceJob?.cancel()

        debounceJob = scope.launch {
            delay(debounceMs)
            Log.d(TAG, "Debounce complete, triggering calendar refresh")
            onCalendarChanged()
        }
    }

    /** Cancels a pending callback; call it when unregistering the observer. */
    fun cancelPending() {
        debounceJob?.cancel()
        debounceJob = null
    }
}
