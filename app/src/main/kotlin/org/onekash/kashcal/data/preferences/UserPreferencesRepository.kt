package org.onekash.kashcal.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_MINUTES
import org.onekash.kashcal.ui.shared.SYNC_INTERVALS_MS
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_MINUTES
import org.onekash.kashcal.ui.theme.ColorSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Exposes [KashCalDataStore] preferences to ViewModels, converting units where the stored form
 * differs (the sync interval is stored in minutes, exposed in milliseconds). Also holds
 * validation helpers for reminder and sync interval values.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: KashCalDataStore
) {
    // ========== Default Calendar ==========

    /** Default calendar ID for new events in the legacy format (a Room calendar ID only). */
    val defaultCalendarId: Flow<Long?>
        get() = dataStore.defaultCalendarId

    suspend fun setDefaultCalendarId(calendarId: Long) {
        dataStore.setDefaultCalendarId(calendarId)
    }

    /** Default calendar for new events, a Room or a device calendar. */
    val defaultCalendar: Flow<DefaultCalendar?>
        get() = dataStore.defaultCalendar

    suspend fun setDefaultCalendar(calendar: DefaultCalendar) {
        dataStore.setDefaultCalendar(calendar)
    }

    // ========== Sync Settings ==========

    /**
     * Sync interval in milliseconds; Long.MAX_VALUE means manual only. A stored value of 0 or less,
     * or Int.MAX_VALUE, reads as manual only.
     */
    val syncIntervalMs: Flow<Long>
        get() = dataStore.syncIntervalMinutes.map { minutes ->
            if (minutes <= 0 || minutes == Int.MAX_VALUE) {
                Long.MAX_VALUE // Manual only
            } else {
                minutes.toLong() * 60 * 1000L
            }
        }

    /** Stores [intervalMs] as whole minutes; Long.MAX_VALUE or 0 or less stores manual only. */
    suspend fun setSyncIntervalMs(intervalMs: Long) {
        val minutes = if (intervalMs == Long.MAX_VALUE || intervalMs <= 0) {
            Int.MAX_VALUE // Manual only
        } else {
            (intervalMs / (60 * 1000L)).toInt()
        }
        dataStore.setSyncIntervalMinutes(minutes)
    }

    val autoSyncEnabled: Flow<Boolean>
        get() = dataStore.autoSyncEnabled

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.setAutoSyncEnabled(enabled)
    }

    val syncWifiOnly: Flow<Boolean>
        get() = dataStore.syncWifiOnly

    suspend fun setSyncWifiOnly(wifiOnly: Boolean) {
        dataStore.setSyncWifiOnly(wifiOnly)
    }

    /** Last successful sync time in epoch millis, 0 if none. */
    val lastSyncTime: Flow<Long>
        get() = dataStore.lastSyncTime

    suspend fun setLastSyncTime(timeMillis: Long) {
        dataStore.setLastSyncTime(timeMillis)
    }

    // ========== Default Reminders ==========

    /** Default reminder for timed events in minutes before start; -1 means none. */
    val defaultReminderTimed: Flow<Int>
        get() = dataStore.defaultReminderMinutes

    suspend fun setDefaultReminderTimed(minutes: Int) {
        dataStore.setDefaultReminderMinutes(minutes)
    }

    /**
     * Default reminder for all-day events in signed minutes before the day's start (negative is
     * after it); defaults to 900, 9 AM the day before. -1 means none.
     */
    val defaultReminderAllDay: Flow<Int>
        get() = dataStore.defaultAllDayReminder

    suspend fun setDefaultReminderAllDay(minutes: Int) {
        dataStore.setDefaultAllDayReminder(minutes)
    }

    // ========== UI Settings ==========

    /** Theme setting: "system", "light" or "dark"; a stored value may be the retired "teal". */
    val theme: Flow<String>
        get() = dataStore.theme

    suspend fun setTheme(theme: String) {
        dataStore.setTheme(theme)
    }

    /** Stored color-source value ("dynamic"/"seed"), or null if never chosen. */
    val colorSource: Flow<String?>
        get() = dataStore.colorSource

    /**
     * Resolves the color source (dynamic or accent seed) from the stored value and the legacy
     * theme, so a user of the retired "teal" theme lands on the seed path. Single source of truth
     * for [org.onekash.kashcal.ui.viewmodels.AppearanceViewModel] and
     * [org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel].
     */
    val resolvedColorSource: Flow<ColorSource>
        get() = combine(dataStore.colorSource, dataStore.theme) { explicit, legacyTheme ->
            ColorSource.fromPrefValue(explicit, legacyTheme)
        }

    /** Accent seed color (packed ARGB), defaulting to brand teal. */
    val accentSeed: Flow<Int>
        get() = dataStore.accentSeed

    /**
     * First day of week as a `java.util.Calendar` day (SUNDAY = 1, MONDAY = 2), or
     * [KashCalDataStore.FIRST_DAY_SYSTEM] (0, the default) to follow the locale.
     */
    val firstDayOfWeek: Flow<Int>
        get() = dataStore.firstDayOfWeek

    suspend fun setFirstDayOfWeek(day: Int) {
        dataStore.setFirstDayOfWeek(day)
    }

    val showWeekNumbers: Flow<Boolean>
        get() = dataStore.showWeekNumbers

    suspend fun setShowWeekNumbers(show: Boolean) {
        dataStore.setShowWeekNumbers(show)
    }

    /** Default event duration in minutes. */
    val defaultEventDuration: Flow<Int>
        get() = dataStore.defaultEventDuration

    suspend fun setDefaultEventDuration(minutes: Int) {
        dataStore.setDefaultEventDuration(minutes)
    }

    // ========== Privacy ==========

    /** Whether reopening the app requires device biometrics or screen lock. Default false. */
    val appLockEnabled: Flow<Boolean>
        get() = dataStore.appLockEnabled

    suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.setAppLockEnabled(enabled)
    }

    // ========== Onboarding ==========

    val onboardingCompleted: Flow<Boolean>
        get() = dataStore.onboardingCompleted

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.setOnboardingCompleted(completed)
    }

    /** Whether the onboarding sheet was dismissed. */
    val onboardingDismissed: Flow<Boolean>
        get() = dataStore.onboardingDismissed

    suspend fun setOnboardingDismissed(dismissed: Boolean) {
        dataStore.setOnboardingDismissed(dismissed)
    }

    // ========== Permission Tracking ==========

    /** Returns how many times the user denied notification permission. */
    suspend fun getNotificationPermissionDeniedCount(): Int =
        dataStore.getNotificationPermissionDeniedCountBlocking()

    suspend fun incrementNotificationPermissionDeniedCount() {
        dataStore.incrementNotificationPermissionDeniedCount()
    }

    /** Resets the denial count; call once the permission is granted. */
    suspend fun resetNotificationPermissionDeniedCount() {
        dataStore.resetNotificationPermissionDeniedCount()
    }

    // ========== Validation Helpers ==========

    /** Returns true if [minutes] is one of the reminder options for the event type. */
    fun isValidReminder(minutes: Int, isAllDay: Boolean): Boolean {
        val validMinutes = if (isAllDay) ALL_DAY_REMINDER_MINUTES else TIMED_REMINDER_MINUTES
        return validMinutes.contains(minutes)
    }

    /** Returns the built-in default reminder for the event type, not the user's setting. */
    fun getDefaultReminder(isAllDay: Boolean): Int {
        return if (isAllDay) {
            KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES
        } else {
            KashCalDataStore.DEFAULT_REMINDER_MINUTES
        }
    }

    /**
     * Returns a reminder valid for the new event type: [currentMinutes] if it is off or already
     * valid, else the new type's [getDefaultReminder].
     */
    fun migrateReminder(currentMinutes: Int, newIsAllDay: Boolean): Int {
        if (currentMinutes == KashCalDataStore.REMINDER_OFF) {
            return KashCalDataStore.REMINDER_OFF
        }

        if (isValidReminder(currentMinutes, newIsAllDay)) {
            return currentMinutes
        }

        return getDefaultReminder(newIsAllDay)
    }

    /** Returns true if [intervalMs] is one of [SYNC_INTERVALS_MS] and at least the minimum. */
    fun isValidSyncInterval(intervalMs: Long): Boolean {
        return intervalMs >= KashCalDataStore.MIN_SYNC_INTERVAL_MS &&
            SYNC_INTERVALS_MS.contains(intervalMs)
    }

    /** Returns the [SYNC_INTERVALS_MS] entry closest to [intervalMs], at least the minimum. */
    fun getClosestSyncInterval(intervalMs: Long): Long {
        if (intervalMs < KashCalDataStore.MIN_SYNC_INTERVAL_MS) {
            return KashCalDataStore.MIN_SYNC_INTERVAL_MS
        }

        return SYNC_INTERVALS_MS
            .minByOrNull { abs(it - intervalMs) }
            ?: KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS
    }
}
