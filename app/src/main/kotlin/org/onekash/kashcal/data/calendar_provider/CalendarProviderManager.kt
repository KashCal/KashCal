package org.onekash.kashcal.data.calendar_provider

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for device calendar integration lifecycle.
 *
 * Handles ContentObserver registration/unregistration and exposes a
 * [changeSignal] StateFlow that increments when CalendarProvider data changes.
 * HomeViewModel's combine() re-queries CalendarProvider when signal changes.
 *
 * Key difference from ContactBirthdayManager: No WorkManager, no Room write.
 * Instead exposes [changeSignal] for reactive UI updates.
 */
@Singleton
class CalendarProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: KashCalDataStore,
    private val deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
) {
    companion object {
        private const val TAG = "CalProviderManager"
    }

    private val contentResolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var observer: CalendarProviderObserver? = null

    private val _changeSignal = MutableStateFlow(0)

    /**
     * Incremented when CalendarProvider data changes.
     * Consumers (e.g., DisplayEventRepository) combine this with Room Flow
     * to re-query device events.
     */
    val changeSignal: StateFlow<Int> = _changeSignal.asStateFlow()

    /**
     * Initialize on app startup.
     * Checks if device calendars are enabled and registers the observer if so.
     * If permission was revoked since the feature was enabled, auto-disables it.
     */
    fun initialize() {
        scope.launch {
            val enabled = dataStore.deviceCalendarsEnabled.first()
            if (enabled) {
                if (!hasPermission()) {
                    Log.w(TAG, "READ_CALENDAR permission revoked, auto-disabling device calendars")
                    dataStore.setDeviceCalendarsEnabled(false)
                    return@launch
                }
                Log.d(TAG, "Device calendars enabled on startup, registering observer")
                registerObserver()
            }
        }
    }

    /**
     * Called when user enables device calendars.
     * Registers observer and increments changeSignal to trigger initial load.
     */
    fun onEnabled() {
        registerObserver()
        _changeSignal.value++
    }

    /**
     * Called when user disables device calendars.
     * Unregisters observer, cancels pending reminder alarms.
     * Device events stop appearing because DisplayEventRepository checks the enabled preference.
     */
    fun onDisabled() {
        unregisterObserver()
        deviceCalendarReminderScheduler.cancelPendingAlarm()
        _changeSignal.value++  // Trigger re-query so device events are removed from UI
    }

    /**
     * Bump the change signal immediately for a write the app itself just made.
     *
     * The ContentObserver that normally drives [changeSignal] is debounced
     * (to coalesce bursts of external edits), so relying on it to reflect our
     * own create/edit/delete would leave the UI stale for the debounce window.
     * Callers invoke this right after a successful CalendarProvider write so the
     * reactive views re-query device events with no perceptible lag. Debouncing
     * still applies to changes we don't originate.
     */
    fun notifyLocalChange() {
        _changeSignal.value++
    }

    /**
     * Called when user disables device calendar reminders only.
     * Cancels pending alarm but keeps observer running.
     */
    fun onRemindersDisabled() {
        deviceCalendarReminderScheduler.cancelPendingAlarm()
    }

    /**
     * Called when user enables device calendar reminders.
     * Schedules the next upcoming reminder.
     */
    fun onRemindersEnabled() {
        scope.launch {
            deviceCalendarReminderScheduler.scheduleNextReminder()
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerObserver() {
        if (observer != null) {
            Log.d(TAG, "Observer already registered")
            return
        }

        if (!hasPermission()) {
            Log.w(TAG, "READ_CALENDAR permission revoked, auto-disabling device calendars")
            scope.launch { dataStore.setDeviceCalendarsEnabled(false) }
            return
        }

        observer = CalendarProviderObserver(
            handler = handler,
            scope = scope,
            debounceMs = 3000L
        ) {
            _changeSignal.value++
            // Reschedule reminders when calendar data changes (event added/modified/deleted)
            scope.launch {
                deviceCalendarReminderScheduler.scheduleNextReminder()
            }
        }

        try {
            contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true, // notifyForDescendants
                observer!!
            )
            Log.i(TAG, "Registered calendar provider observer")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering observer, auto-disabling device calendars", e)
            observer = null
            scope.launch { dataStore.setDeviceCalendarsEnabled(false) }
        }
    }

    private fun unregisterObserver() {
        observer?.let {
            it.cancelPending()
            contentResolver.unregisterContentObserver(it)
            observer = null
            Log.i(TAG, "Unregistered calendar provider observer")
        }
    }
}
