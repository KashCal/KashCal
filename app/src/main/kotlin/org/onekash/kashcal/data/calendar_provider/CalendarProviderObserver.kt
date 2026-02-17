package org.onekash.kashcal.data.calendar_provider

import android.database.ContentObserver
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ContentObserver for CalendarProvider changes.
 *
 * Monitors CalendarContract.Events.CONTENT_URI for changes and triggers
 * a debounced callback when calendar data is modified.
 *
 * Uses a 3-second debounce (vs 500ms for contacts) because sync adapters
 * can write many events in rapid succession during a sync cycle.
 *
 * Unlike ContactBirthdayObserver, selfChange is NOT filtered — KashCal writes
 * to CalendarProvider so self-changes need a UI refresh too.
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

        // Cancel any pending debounce
        debounceJob?.cancel()

        // Schedule debounced callback
        debounceJob = scope.launch {
            delay(debounceMs)
            Log.d(TAG, "Debounce complete, triggering calendar refresh")
            onCalendarChanged()
        }
    }

    /**
     * Cancel any pending debounced callback.
     * Call this when unregistering the observer.
     */
    fun cancelPending() {
        debounceJob?.cancel()
        debounceJob = null
    }
}
