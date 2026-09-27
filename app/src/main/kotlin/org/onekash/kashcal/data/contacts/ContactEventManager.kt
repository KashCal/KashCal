package org.onekash.kashcal.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the birthday and anniversary calendars in step with the phone's contacts.
 *
 * - App start: if either feature is on, register the contacts observer and sync directly.
 * - Enable (either feature): register the observer if needed and enqueue a
 *   [ContactEventSyncWorker] run; each contacts change enqueues another.
 * - Disable: unregister the observer and cancel the worker only when both features are off.
 *
 * When app start or observer registration finds READ_CONTACTS revoked, both features are
 * turned off and their calendars removed.
 */
@Singleton
class ContactEventManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: KashCalDataStore,
    private val eventCoordinator: EventCoordinator
) {
    companion object {
        private const val TAG = "ContactEventManager"
    }

    private val contentResolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var observer: ContactEventObserver? = null

    /**
     * Registers the observer and syncs on app startup when either feature is on. If
     * READ_CONTACTS was revoked since, turns both features off instead.
     */
    fun initialize() {
        scope.launch {
            // Cancel work still queued under the old unique-work name.
            try {
                WorkManager.getInstance(context).cancelUniqueWork("contact_birthday_sync")
            } catch (_: Exception) {
                // WorkManager may not be initialized yet in tests
            }

            val birthdaysEnabled = dataStore.contactBirthdaysEnabled.first()
            val anniversariesEnabled = dataStore.contactAnniversariesEnabled.first()

            if (birthdaysEnabled || anniversariesEnabled) {
                if (!hasPermission()) {
                    Log.w(TAG, "READ_CONTACTS permission revoked, cleaning up contact features")
                    cleanupAllContactFeatures()
                    return@launch
                }
                Log.d(TAG, "Contact events enabled on startup (birthdays=$birthdaysEnabled, anniversaries=$anniversariesEnabled), registering observer")
                registerObserver()

                // Sync on startup to recover from killed WorkManager jobs (#146). The sync is a
                // diff, so it writes nothing when no contact changed.
                try {
                    if (birthdaysEnabled) {
                        eventCoordinator.syncContactBirthdays()
                    }
                    if (anniversariesEnabled) {
                        eventCoordinator.syncContactAnniversaries()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during startup sync", e)
                }
            }
        }
    }

    /** Registers the observer if needed and enqueues a sync, on enabling birthdays. */
    fun onBirthdaysEnabled() {
        registerObserver()
        ContactEventSyncWorker.requestImmediateSync(context)
    }

    /**
     * Unregisters the observer and cancels the worker if anniversaries are also off. The
     * calendar is removed separately by [EventCoordinator.disableContactBirthdays].
     */
    fun onBirthdaysDisabled() {
        scope.launch {
            val anniversariesEnabled = dataStore.contactAnniversariesEnabled.first()
            if (!anniversariesEnabled) {
                unregisterObserver()
                ContactEventSyncWorker.cancelSync(context)
            }
        }
    }

    /** Registers the observer if needed and enqueues a sync, on enabling anniversaries. */
    fun onAnniversariesEnabled() {
        registerObserver()
        ContactEventSyncWorker.requestImmediateSync(context)
    }

    /**
     * Unregisters the observer and cancels the worker if birthdays are also off. The calendar is
     * removed separately by [EventCoordinator.disableContactAnniversaries].
     */
    fun onAnniversariesDisabled() {
        scope.launch {
            val birthdaysEnabled = dataStore.contactBirthdaysEnabled.first()
            if (!birthdaysEnabled) {
                unregisterObserver()
                ContactEventSyncWorker.cancelSync(context)
            }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerObserver() {
        if (observer != null) {
            Log.d(TAG, "Observer already registered")
            return
        }

        if (!hasPermission()) {
            Log.w(TAG, "READ_CONTACTS permission revoked, cleaning up contact features")
            scope.launch { cleanupAllContactFeatures() }
            return
        }

        observer = ContactEventObserver(
            handler = handler,
            scope = scope,
            debounceMs = 500L
        ) {
            ContactEventSyncWorker.requestImmediateSync(context)
        }

        try {
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true, // notifyForDescendants
                observer!!
            )
            Log.i(TAG, "Registered contact event observer")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering observer, cleaning up contact features", e)
            observer = null
            scope.launch { cleanupAllContactFeatures() }
        }
    }

    /**
     * Turns both features off after READ_CONTACTS is revoked: stops the observer and worker,
     * clears the settings, and removes both calendars through the same [EventCoordinator]
     * calls as the settings toggle, which also refresh the widgets. Removing a calendar that
     * doesn't exist is a no-op, so both are called unconditionally.
     */
    private suspend fun cleanupAllContactFeatures() {
        unregisterObserver()
        ContactEventSyncWorker.cancelSync(context)
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactBirthdaysLastSync(0L)
        dataStore.setContactAnniversariesEnabled(false)
        dataStore.setContactAnniversariesLastSync(0L)
        eventCoordinator.disableContactBirthdays()
        eventCoordinator.disableContactAnniversaries()
        Log.i(TAG, "Cleaned up all contact features (permission revoked)")
    }

    private fun unregisterObserver() {
        observer?.let {
            it.cancelPending()
            contentResolver.unregisterContentObserver(it)
            observer = null
            Log.i(TAG, "Unregistered contact event observer")
        }
    }
}
