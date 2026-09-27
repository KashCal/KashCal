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
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers and unregisters the CalendarProvider observer and exposes [changeSignal] for
 * device-event re-queries.
 *
 * The observer callback is the one place every device-calendar change arrives, whether KashCal
 * wrote it or another app or sync adapter did, so it also reschedules device reminders and
 * refreshes the home-screen widgets. Keep that fan-out here, so no consumer has to register its
 * own observer.
 *
 * Unlike [org.onekash.kashcal.data.contacts.ContactEventManager], it runs no sync worker and
 * writes nothing to Room.
 */
@Singleton
class CalendarProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: KashCalDataStore,
    private val deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler,
    private val widgetUpdateManager: WidgetUpdateManager
) {
    companion object {
        private const val TAG = "CalProviderManager"

        /** How long the observer waits for a burst of provider changes to settle. */
        internal const val OBSERVER_DEBOUNCE_MS = 3000L
    }

    private val contentResolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var observer: CalendarProviderObserver? = null

    private val _changeSignal = MutableStateFlow(0)

    /**
     * Increments when CalendarProvider data changes. DisplayEventRepository's flows combine it
     * with Room flows and re-query device events on each change.
     */
    val changeSignal: StateFlow<Int> = _changeSignal.asStateFlow()

    /**
     * Registers the observer at app startup when device calendars are on. If READ_CALENDAR was
     * revoked since, switches the feature off instead.
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
     * Starts observing and re-queries once device calendars are switched on. Without
     * READ_CALENDAR it switches the feature back off instead of observing.
     */
    fun onEnabled() {
        registerObserver()
        _changeSignal.value++
    }

    /**
     * Stops observing, cancels the pending device reminder alarm and re-queries once device
     * calendars are switched off, by the user or by a settings restore
     * ([applyDeviceCalendarsSetting]). Device events disappear because DisplayEventRepository
     * checks the enabled preference.
     */
    fun onDisabled() {
        unregisterObserver()
        deviceCalendarReminderScheduler.cancelPendingAlarm()
        _changeSignal.value++  // Re-query so device events leave the UI
    }

    /**
     * Bumps [changeSignal] so device-event views re-query now.
     *
     * Call it right after the app writes to CalendarProvider, or when a settings change needs a
     * re-query while device calendars are off. The observer is debounced to coalesce bursts of
     * external edits, so waiting for it would leave the UI stale for the debounce window;
     * changes the app doesn't originate still wait for it. It never starts or stops observing
     * and runs no permission check.
     */
    fun notifyDeviceCalendarChanged() {
        _changeSignal.value++
    }

    /**
     * Re-queries device events after a KashCal setting changed which are shown (a calendar
     * ticked or unticked, declined events shown or hidden).
     *
     * When device calendars are on it goes through [onEnabled], so a stored "on" that isn't
     * observed yet is healed. When they are off it only re-queries: the provider is observed only
     * while the feature is on.
     */
    suspend fun onDeviceCalendarSettingsChanged() {
        if (dataStore.deviceCalendarsEnabled.first()) {
            onEnabled()
        } else {
            notifyDeviceCalendarChanged()
        }
    }

    /**
     * Makes observing match the stored device-calendars switch after something other than the
     * switch changed it (restoring a settings backup): on runs [onEnabled], off [onDisabled].
     * Safe when already in step: it then re-queries, and when off cancels the pending device
     * reminder alarm again, which is harmless.
     */
    suspend fun applyDeviceCalendarsSetting() {
        if (dataStore.deviceCalendarsEnabled.first()) onEnabled() else onDisabled()
    }

    /**
     * Cancels the pending device reminder alarm but keeps observing. Nothing in the app calls it
     * today; only tests do.
     */
    fun onRemindersDisabled() {
        deviceCalendarReminderScheduler.cancelPendingAlarm()
    }

    /** Schedules the next device reminder. Nothing in the app calls it today; only tests do. */
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
            debounceMs = OBSERVER_DEBOUNCE_MS
        ) {
            _changeSignal.value++
            scope.launch {
                deviceCalendarReminderScheduler.scheduleNextReminder()
            }
            // Widgets show device events too. A separate launch keeps a slow widget update from
            // delaying the reminder reschedule; the debounce keeps a sync adapter's burst of
            // writes down to one refresh.
            scope.launch {
                widgetUpdateManager.updateAllWidgets(reason = "device_calendar_changed")
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
