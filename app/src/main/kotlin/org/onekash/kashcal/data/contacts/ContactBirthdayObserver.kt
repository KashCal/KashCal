package org.onekash.kashcal.data.contacts

import android.database.ContentObserver
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ContentObserver for contact changes.
 *
 * Monitors ContactsContract.Contacts.CONTENT_URI for changes and triggers
 * a debounced sync when contacts are modified.
 *
 * Debouncing is important because:
 * - Contact editing triggers multiple onChange() calls (one per field changed)
 * - Syncing contacts is relatively expensive
 * - We want to batch rapid changes into a single sync
 */
class ContactBirthdayObserver(
    handler: Handler,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L,
    private val onContactsChanged: () -> Unit
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "ContactBirthdayObserver"
    }

    private var debounceJob: Job? = null

    override fun onChange(selfChange: Boolean) {
        Log.d(TAG, "Contacts changed (selfChange=$selfChange)")

        // Cancel any pending debounce
        debounceJob?.cancel()

        // Schedule debounced callback
        debounceJob = scope.launch {
            delay(debounceMs)
            Log.d(TAG, "Debounce complete, triggering birthday sync")
            onContactsChanged()
        }
    }

    /**
     * Cancel any pending debounced sync.
     * Call this when unregistering the observer.
     */
    fun cancelPending() {
        debounceJob?.cancel()
        debounceJob = null
    }
}
