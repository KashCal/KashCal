package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.preferences.KashCalDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for contact birthday calendar feature.
 *
 * Handles:
 * - ContentObserver registration/unregistration
 * - Initialization on app startup (if enabled)
 * - Triggering sync via WorkManager
 *
 * Lifecycle:
 * - On app start: Check if enabled, register observer if so
 * - On enable: Register observer, trigger sync
 * - On disable: Unregister observer
 */
@Singleton
class ContactBirthdayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "ContactBirthdayManager"
    }

    private val contentResolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var observer: ContactBirthdayObserver? = null

    /**
     * Initialize on app startup.
     *
     * Checks if contact birthdays feature is enabled and registers
     * the ContentObserver if so.
     */
    fun initialize() {
        scope.launch {
            val enabled = dataStore.contactBirthdaysEnabled.first()
            if (enabled) {
                Log.d(TAG, "Contact birthdays enabled on startup, registering observer")
                registerObserver()
            }
        }
    }

    /**
     * Called when user enables contact birthdays.
     *
     * Registers ContentObserver and triggers initial sync.
     */
    fun onEnabled() {
        registerObserver()
        // Trigger immediate sync
        ContactBirthdayWorker.requestImmediateSync(context)
    }

    /**
     * Called when user disables contact birthdays.
     *
     * Unregisters ContentObserver. Calendar deletion is handled separately
     * by EventCoordinator.disableContactBirthdays().
     */
    fun onDisabled() {
        unregisterObserver()
        ContactBirthdayWorker.cancelSync(context)
    }

    private fun registerObserver() {
        if (observer != null) {
            Log.d(TAG, "Observer already registered")
            return
        }

        observer = ContactBirthdayObserver(
            handler = handler,
            scope = scope,
            debounceMs = 500L
        ) {
            // Trigger sync when contacts change
            ContactBirthdayWorker.requestImmediateSync(context)
        }

        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true, // notifyForDescendants
            observer!!
        )

        Log.i(TAG, "Registered contact birthday observer")
    }

    private fun unregisterObserver() {
        observer?.let {
            it.cancelPending()
            contentResolver.unregisterContentObserver(it)
            observer = null
            Log.i(TAG, "Unregistered contact birthday observer")
        }
    }
}
