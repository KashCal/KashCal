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
 * Manager for contact event (birthday + anniversary) calendar features.
 *
 * Handles:
 * - ContentObserver registration/unregistration
 * - Initialization on app startup (if either feature enabled)
 * - Triggering sync via WorkManager
 *
 * Lifecycle:
 * - On app start: Check if either feature enabled, register observer if so
 * - On enable (birthdays or anniversaries): Register observer if not registered, trigger sync
 * - On disable: Unregister observer only if BOTH disabled
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
     * Initialize on app startup.
     * Checks if either contact birthdays or anniversaries feature is enabled and registers
     * the ContentObserver if so.
     * If permission was revoked since a feature was enabled, auto-disables both.
     */
    fun initialize() {
        scope.launch {
            // Legacy cleanup: cancel any in-flight work under old name
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

                // Sync on startup to recover from killed WorkManager jobs (Issue #146)
                // syncContactBirthdays/Anniversaries are idempotent (diff-based, fast no-op if events exist)
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

    /**
     * Called when user enables contact birthdays.
     *
     * Registers ContentObserver (if not already registered) and triggers sync.
     */
    fun onBirthdaysEnabled() {
        registerObserver()
        ContactEventSyncWorker.requestImmediateSync(context)
    }

    /**
     * Called when user disables contact birthdays.
     *
     * Unregisters ContentObserver only if anniversaries are also disabled.
     * Calendar deletion is handled separately by EventCoordinator.disableContactBirthdays().
     */
    fun onBirthdaysDisabled() {
        scope.launch {
            val anniversariesEnabled = dataStore.contactAnniversariesEnabled.first()
            if (!anniversariesEnabled) {
                unregisterObserver()
                ContactEventSyncWorker.cancelSync(context)
            }
            // If anniversaries still enabled, keep observer active
        }
    }

    /**
     * Called when user enables contact anniversaries.
     *
     * Registers ContentObserver (if not already registered) and triggers sync.
     */
    fun onAnniversariesEnabled() {
        registerObserver()
        ContactEventSyncWorker.requestImmediateSync(context)
    }

    /**
     * Called when user disables contact anniversaries.
     *
     * Unregisters ContentObserver only if birthdays are also disabled.
     * Calendar deletion is handled separately by EventCoordinator.disableContactAnniversaries().
     */
    fun onAnniversariesDisabled() {
        scope.launch {
            val birthdaysEnabled = dataStore.contactBirthdaysEnabled.first()
            if (!birthdaysEnabled) {
                unregisterObserver()
                ContactEventSyncWorker.cancelSync(context)
            }
            // If birthdays still enabled, keep observer active
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
            // Trigger sync when contacts change
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
     * Full cleanup when READ_CONTACTS permission is revoked.
     *
     * Uses EventCoordinator (domain layer) for calendar deletion, same as the
     * toggle-off path in AccountSettingsViewModel. This ensures:
     * - Calendar, events, occurrences, and reminders are deleted via removeCalendar()
     * - Widgets are updated via triggerWidgetUpdate()
     *
     * disableContactBirthdays/Anniversaries() is a no-op if the calendar doesn't
     * exist, so safe to call unconditionally for both features.
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
