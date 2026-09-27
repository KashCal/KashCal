package org.onekash.kashcal.data.contacts

import android.database.ContentObserver
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onContactsChanged] once changes to the observed contacts URI stop for [debounceMs].
 *
 * One contact edit fires several onChange() calls (one per changed field), and each sync
 * rereads every birthday or anniversary row, so a burst of changes runs one sync.
 */
class ContactEventObserver(
    handler: Handler,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L,
    private val onContactsChanged: () -> Unit
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "ContactEventObserver"
    }

    private var debounceJob: Job? = null

    override fun onChange(selfChange: Boolean) {
        Log.d(TAG, "Contacts changed (selfChange=$selfChange)")

        debounceJob?.cancel()

        debounceJob = scope.launch {
            delay(debounceMs)
            Log.d(TAG, "Debounce complete, triggering contact event sync")
            onContactsChanged()
        }
    }

    /** Cancels a pending callback; call it when unregistering the observer. */
    fun cancelPending() {
        debounceJob?.cancel()
        debounceJob = null
    }
}
