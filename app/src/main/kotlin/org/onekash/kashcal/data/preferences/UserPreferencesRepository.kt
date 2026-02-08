package org.onekash.kashcal.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.SYNC_OPTIONS
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Repository for user preferences, wrapping KashCalDataStore with convenient APIs.
 *
 * This provides a clean interface for ViewModels to access preferences
 * with appropriate type conversions (e.g., minutes to milliseconds for sync interval).
 * Also includes validation helpers for reminder and sync interval values.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: KashCalDataStore
) {
    // ========== Default Calendar ==========

    /**
     * Default calendar ID for new events.
     */
    val defaultCalendarId: Flow<Long?>
        get() = dataStore.defaultCalendarId

    suspend fun setDefaultCalendarId(calendarId: Long) {
        dataStore.setDefaultCalendarId(calendarId)
    }

    // ========== Sync Settings ==========

    /**
     * Sync interval in milliseconds.
     * Converts from stored minutes to milliseconds.
     * Long.MAX_VALUE represents "manual only".
     */
    val syncIntervalMs: Flow<Long>
        get() = dataStore.syncIntervalMinutes.map { minutes ->
            if (minutes <= 0 || minutes == Int.MAX_VALUE) {
                Long.MAX_VALUE // Manual only
            } else {
                minutes.toLong() * 60 * 1000L
            }
        }

    /**
     * Set sync interval in milliseconds.
     * Converts to minutes for storage.
     */
    suspend fun setSyncIntervalMs(intervalMs: Long) {
        val minutes = if (intervalMs == Long.MAX_VALUE || intervalMs <= 0) {
            Int.MAX_VALUE // Manual only
        } else {
            (intervalMs / (60 * 1000L)).toInt()
        }
        dataStore.setSyncIntervalMinutes(minutes)
    }

    /**
     * Auto-sync enabled state.
     */
    val autoSyncEnabled: Flow<Boolean>
        get() = dataStore.autoSyncEnabled

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.setAutoSyncEnabled(enabled)
    }

    /**
     * Sync on Wi-Fi only.
     */
    val syncWifiOnly: Flow<Boolean>
        get() = dataStore.syncWifiOnly

    suspend fun setSyncWifiOnly(wifiOnly: Boolean) {
        dataStore.setSyncWifiOnly(wifiOnly)
    }

    /**
     * Last successful sync timestamp.
     */
    val lastSyncTime: Flow<Long>
        get() = dataStore.lastSyncTime

    suspend fun setLastSyncTime(timeMillis: Long) {
        dataStore.setLastSyncTime(timeMillis)
    }

    // ========== Default Reminders ==========

    /**
     * Default reminder for timed events (in minutes before event).
     * -1 = no reminder
     */
    val defaultReminderTimed: Flow<Int>
        get() = dataStore.defaultReminderMinutes

    suspend fun setDefaultReminderTimed(minutes: Int) {
        dataStore.setDefaultReminderMinutes(minutes)
    }

    /**
     * Default reminder for all-day events (in minutes before event).
     * Typically a large value like 1440 (1 day before).
     * -1 = no reminder
     */
    val defaultReminderAllDay: Flow<Int>
        get() = dataStore.defaultAllDayReminder

    suspend fun setDefaultReminderAllDay(minutes: Int) {
        dataStore.setDefaultAllDayReminder(minutes)
    }

    // ========== UI Settings ==========

    /**
     * Theme setting: "system", "light", "dark".
     */
    val theme: Flow<String>
        get() = dataStore.theme

    suspend fun setTheme(theme: String) {
        dataStore.setTheme(theme)
    }

    /**
     * First day of week (Calendar.SUNDAY = 1, Calendar.MONDAY = 2, etc.).
     */
    val firstDayOfWeek: Flow<Int>
        get() = dataStore.firstDayOfWeek

    suspend fun setFirstDayOfWeek(day: Int) {
        dataStore.setFirstDayOfWeek(day)
    }

    /**
     * Show week numbers in calendar view.
     */
    val showWeekNumbers: Flow<Boolean>
        get() = dataStore.showWeekNumbers

    suspend fun setShowWeekNumbers(show: Boolean) {
        dataStore.setShowWeekNumbers(show)
    }

    /**
     * Default event duration in minutes.
     */
    val defaultEventDuration: Flow<Int>
        get() = dataStore.defaultEventDuration

    suspend fun setDefaultEventDuration(minutes: Int) {
        dataStore.setDefaultEventDuration(minutes)
    }

    // ========== Onboarding ==========

    /**
     * Onboarding completed flag.
     */
    val onboardingCompleted: Flow<Boolean>
        get() = dataStore.onboardingCompleted

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.setOnboardingCompleted(completed)
    }

    /**
     * Onboarding sheet dismissed flag.
     */
    val onboardingDismissed: Flow<Boolean>
        get() = dataStore.onboardingDismissed

    suspend fun setOnboardingDismissed(dismissed: Boolean) {
        dataStore.setOnboardingDismissed(dismissed)
    }

    // ========== Permission Tracking ==========

    /**
     * Get number of times notification permission was denied.
     */
    suspend fun getNotificationPermissionDeniedCount(): Int =
        dataStore.getNotificationPermissionDeniedCountBlocking()

    /**
     * Increment denial count after user denies notification permission.
     */
    suspend fun incrementNotificationPermissionDeniedCount() {
        dataStore.incrementNotificationPermissionDeniedCount()
    }

    /**
     * Reset denial count after permission is granted.
     */
    suspend fun resetNotificationPermissionDeniedCount() {
        dataStore.resetNotificationPermissionDeniedCount()
    }

    // ========== Validation Helpers ==========

    /**
     * Validate a reminder value for a specific event type.
     *
     * @param minutes Reminder minutes before event
     * @param isAllDay Whether the event is all-day
     * @return True if valid for the event type
     */
    fun isValidReminder(minutes: Int, isAllDay: Boolean): Boolean {
        val options = if (isAllDay) ALL_DAY_REMINDER_OPTIONS else TIMED_REMINDER_OPTIONS
        return options.any { it.minutes == minutes }
    }

    /**
     * Get the default reminder for an event type.
     *
     * @param isAllDay Whether the event is all-day
     * @return Default reminder minutes
     */
    fun getDefaultReminder(isAllDay: Boolean): Int {
        return if (isAllDay) {
            KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES
        } else {
            KashCalDataStore.DEFAULT_REMINDER_MINUTES
        }
    }

    /**
     * Migrate a reminder value when event type changes.
     * If current reminder is invalid for new type, returns default for new type.
     *
     * @param currentMinutes Current reminder value
     * @param newIsAllDay New event type
     * @return Valid reminder for new event type
     */
    fun migrateReminder(currentMinutes: Int, newIsAllDay: Boolean): Int {
        // If no reminder, keep it
        if (currentMinutes == KashCalDataStore.REMINDER_OFF) {
            return KashCalDataStore.REMINDER_OFF
        }

        // If current value is valid for new type, keep it
        if (isValidReminder(currentMinutes, newIsAllDay)) {
            return currentMinutes
        }

        // Otherwise, use default for new type
        return getDefaultReminder(newIsAllDay)
    }

    /**
     * Validate a sync interval value.
     *
     * @param intervalMs Sync interval in milliseconds
     * @return True if valid, false otherwise
     */
    fun isValidSyncInterval(intervalMs: Long): Boolean {
        return intervalMs >= KashCalDataStore.MIN_SYNC_INTERVAL_MS &&
            SYNC_OPTIONS.any { it.intervalMs == intervalMs }
    }

    /**
     * Get the closest valid sync interval for a given value.
     *
     * @param intervalMs Desired interval in milliseconds
     * @return Closest valid sync interval
     */
    fun getClosestSyncInterval(intervalMs: Long): Long {
        if (intervalMs < KashCalDataStore.MIN_SYNC_INTERVAL_MS) {
            return KashCalDataStore.MIN_SYNC_INTERVAL_MS
        }

        return SYNC_OPTIONS
            .minByOrNull { abs(it.intervalMs - intervalMs) }
            ?.intervalMs
            ?: KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS
    }
}
