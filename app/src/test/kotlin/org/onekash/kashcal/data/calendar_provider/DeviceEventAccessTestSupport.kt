package org.onekash.kashcal.data.calendar_provider

import android.content.ContentUris
import android.content.Context
import android.os.Looper
import android.provider.CalendarContract
import io.mockk.coJustRun
import io.mockk.justRun
import io.mockk.mockk
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.reader.DeviceEventReader
import org.onekash.kashcal.domain.writer.DeviceEventWriter
import org.robolectric.Shadows

/**
 * Returns a strict [CalendarProviderManager] that accepts only the device change signal. Any
 * other call throws; tests check the signal count with
 * `verify(exactly = n) { notifyDeviceCalendarChanged() }`.
 */
fun deviceChangeNotifier(): CalendarProviderManager = mockk {
    justRun { notifyDeviceCalendarChanged() }
}

/**
 * Returns a strict [CalendarProviderManager] for the settings and backup-restore ViewModel tests:
 * it accepts the master switch (onEnabled, onDisabled), the settings-change hook, the restore
 * re-apply ([CalendarProviderManager.applyDeviceCalendarsSetting]) and the change signal, and
 * throws on anything else. Assert calls with `verify` or `coVerify`.
 */
fun settingsManagerMock(): CalendarProviderManager = mockk {
    justRun { onEnabled() }
    justRun { onDisabled() }
    justRun { notifyDeviceCalendarChanged() }
    coJustRun { onDeviceCalendarSettingsChanged() }
    coJustRun { applyDeviceCalendarsSetting() }
}

/** The real device reader over [this] repository (usually [FakeCalendarProviderRepository]). */
fun CalendarProviderRepository.deviceEventReader(): DeviceEventReader = DeviceEventReader(this)

/** The real device writer over [this] repository, signalling through [manager]. */
fun CalendarProviderRepository.deviceEventWriter(
    dataStore: KashCalDataStore,
    manager: CalendarProviderManager = deviceChangeNotifier(),
): DeviceEventWriter = DeviceEventWriter(this, manager, dataStore)

/** How many observers are registered on the CalendarProvider events URI (Robolectric). */
fun eventsObserverCount(context: Context): Int =
    Shadows.shadowOf(context.contentResolver)
        .getContentObservers(CalendarContract.Events.CONTENT_URI).size

/**
 * Delivers [count] provider change notifications on event rows, then idles the main looper so
 * each reaches the observer's debounce (Robolectric).
 */
fun notifyEventsChanged(context: Context, count: Int = 1) {
    repeat(count) { i ->
        context.contentResolver.notifyChange(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 42L + i),
            null,
        )
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}
