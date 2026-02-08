package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Calendar

/**
 * DataStore wrapper for KashCal preferences.
 *
 * Provides type-safe access to user preferences with reactive Flow support.
 *
 * Usage:
 * ```
 * // Inject via Hilt
 * @Inject lateinit var dataStore: KashCalDataStore
 *
 * // Read preference as Flow
 * dataStore.theme.collect { theme -> ... }
 *
 * // Read preference once
 * val theme = dataStore.getTheme()
 *
 * // Write preference
 * dataStore.setTheme("dark")
 * ```
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kashcal_preferences"
)

class KashCalDataStore(private val context: Context) {

    val dataStore: DataStore<Preferences>
        get() = context.dataStore

    // ========== Generic Preference Access ==========

    /**
     * Get a preference value as a Flow with default value.
     *
     * Uses distinctUntilChanged() to prevent unnecessary downstream emissions
     * when the preference value hasn't actually changed. This is important because
     * DataStore emits the entire preferences object on any write, which would
     * otherwise cause all observers to re-emit even for unrelated preference changes.
     */
    fun <T> getPreference(key: Preferences.Key<T>, defaultValue: T): Flow<T> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences[key] ?: defaultValue
            }
            .distinctUntilChanged()
    }

    /**
     * Get an optional preference value as a Flow.
     *
     * Uses distinctUntilChanged() to prevent unnecessary downstream emissions.
     * See getPreference() for rationale.
     */
    fun <T> getOptionalPreference(key: Preferences.Key<T>): Flow<T?> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences[key]
            }
            .distinctUntilChanged()
    }

    /**
     * Set a preference value.
     */
    suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    /**
     * Remove a preference.
     */
    suspend fun <T> removePreference(key: Preferences.Key<T>) {
        dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    /**
     * Update a preference atomically.
     */
    suspend fun <T> updatePreference(key: Preferences.Key<T>, transform: (T?) -> T) {
        dataStore.edit { preferences ->
            preferences[key] = transform(preferences[key])
        }
    }

    val firstDayOfWeek: Flow<Int>
        get() = getPreference(PreferencesKeys.FIRST_DAY_OF_WEEK, Calendar.SUNDAY)

    suspend fun getFirstDayOfWeek(): Int = firstDayOfWeek.first()

    suspend fun setFirstDayOfWeek(day: Int) {
        setPreference(PreferencesKeys.FIRST_DAY_OF_WEEK, day)
    }

    val showWeekNumbers: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOW_WEEK_NUMBERS, false)

    suspend fun setShowWeekNumbers(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_WEEK_NUMBERS, show)
    }

    val showDeclinedEvents: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOW_DECLINED_EVENTS, false)

    suspend fun setShowDeclinedEvents(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_DECLINED_EVENTS, show)
    }

    val defaultEventDuration: Flow<Int>
        get() = getPreference(PreferencesKeys.DEFAULT_EVENT_DURATION, DEFAULT_EVENT_DURATION_MINUTES)

    suspend fun setDefaultEventDuration(minutes: Int) {
        setPreference(PreferencesKeys.DEFAULT_EVENT_DURATION, minutes)
    }

    // ========== Event Default Preferences ==========

    val defaultCalendarId: Flow<Long?>
        get() = getOptionalPreference(PreferencesKeys.DEFAULT_CALENDAR_ID)

    suspend fun getDefaultCalendarId(): Long? = defaultCalendarId.first()

    suspend fun setDefaultCalendarId(calendarId: Long) {
        setPreference(PreferencesKeys.DEFAULT_CALENDAR_ID, calendarId)
    }

    val defaultReminderMinutes: Flow<Int>
        get() = getPreference(PreferencesKeys.DEFAULT_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES)

    suspend fun setDefaultReminderMinutes(minutes: Int) {
        setPreference(PreferencesKeys.DEFAULT_REMINDER_MINUTES, minutes)
    }

    val defaultAllDayReminder: Flow<Int>
        get() = getPreference(PreferencesKeys.DEFAULT_ALL_DAY_REMINDER, DEFAULT_ALL_DAY_REMINDER_MINUTES)

    suspend fun setDefaultAllDayReminder(minutesFromMidnight: Int) {
        setPreference(PreferencesKeys.DEFAULT_ALL_DAY_REMINDER, minutesFromMidnight)
    }

    // ========== Sync Preferences ==========

    val autoSyncEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.AUTO_SYNC_ENABLED, true)

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.AUTO_SYNC_ENABLED, enabled)
    }

    val syncIntervalMinutes: Flow<Int>
        get() = getPreference(PreferencesKeys.SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)

    suspend fun setSyncIntervalMinutes(minutes: Int) {
        setPreference(PreferencesKeys.SYNC_INTERVAL_MINUTES, minutes)
    }

    val syncWifiOnly: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SYNC_WIFI_ONLY, false)

    suspend fun setSyncWifiOnly(wifiOnly: Boolean) {
        setPreference(PreferencesKeys.SYNC_WIFI_ONLY, wifiOnly)
    }

    val lastSyncTime: Flow<Long>
        get() = getPreference(PreferencesKeys.LAST_SYNC_TIME, 0L)

    suspend fun setLastSyncTime(timeMillis: Long) {
        setPreference(PreferencesKeys.LAST_SYNC_TIME, timeMillis)
    }

    val syncPastDays: Flow<Int>
        get() = getPreference(PreferencesKeys.SYNC_PAST_DAYS, DEFAULT_SYNC_PAST_DAYS)

    suspend fun setSyncPastDays(days: Int) {
        setPreference(PreferencesKeys.SYNC_PAST_DAYS, days)
    }

    val syncFutureDays: Flow<Int>
        get() = getPreference(PreferencesKeys.SYNC_FUTURE_DAYS, DEFAULT_SYNC_FUTURE_DAYS)

    suspend fun setSyncFutureDays(days: Int) {
        setPreference(PreferencesKeys.SYNC_FUTURE_DAYS, days)
    }

    // ========== UI Preferences ==========

    val theme: Flow<String>
        get() = getPreference(PreferencesKeys.THEME, THEME_SYSTEM)

    suspend fun getTheme(): String = theme.first()

    suspend fun setTheme(theme: String) {
        setPreference(PreferencesKeys.THEME, theme)
    }

    val notificationSound: Flow<Boolean>
        get() = getPreference(PreferencesKeys.NOTIFICATION_SOUND, true)

    suspend fun setNotificationSound(enabled: Boolean) {
        setPreference(PreferencesKeys.NOTIFICATION_SOUND, enabled)
    }

    val notificationVibrate: Flow<Boolean>
        get() = getPreference(PreferencesKeys.NOTIFICATION_VIBRATE, true)

    suspend fun setNotificationVibrate(enabled: Boolean) {
        setPreference(PreferencesKeys.NOTIFICATION_VIBRATE, enabled)
    }

    val quickAddEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.QUICK_ADD_ENABLED, true)

    suspend fun setQuickAddEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.QUICK_ADD_ENABLED, enabled)
    }

    // ========== Display Settings ==========

    /**
     * Show auto-detected emojis in event titles.
     * Default: true (enabled)
     */
    val showEventEmojis: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOW_EVENT_EMOJIS, true)

    suspend fun setShowEventEmojis(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_EVENT_EMOJIS, show)
    }

    /**
     * Time format preference.
     * - "system": Follow device's 24-hour setting
     * - "12h": Always 12-hour (2:30 PM)
     * - "24h": Always 24-hour (14:30)
     */
    val timeFormat: Flow<String>
        get() = getPreference(PreferencesKeys.TIME_FORMAT, TIME_FORMAT_SYSTEM)

    suspend fun getTimeFormat(): String = timeFormat.first()

    suspend fun setTimeFormat(format: String) {
        require(format in setOf(TIME_FORMAT_SYSTEM, TIME_FORMAT_12H, TIME_FORMAT_24H)) {
            "Invalid time format: $format"
        }
        setPreference(PreferencesKeys.TIME_FORMAT, format)
    }

    // ========== Default Calendar View ==========

    /**
     * Default calendar view preference.
     * - "month": Month grid (default)
     * - "agenda": 30-day upcoming events list
     * - "three_days": 3-day scrollable time grid
     */
    val defaultCalendarView: Flow<String>
        get() = getPreference(PreferencesKeys.DEFAULT_CALENDAR_VIEW, VIEW_MONTH)

    suspend fun getDefaultCalendarView(): String = defaultCalendarView.first()

    suspend fun setDefaultCalendarView(view: String) {
        require(view in VALID_VIEWS) {
            "Invalid calendar view: $view"
        }
        setPreference(PreferencesKeys.DEFAULT_CALENDAR_VIEW, view)
    }

    // ========== Migration Flags ==========

    val migrationV1Completed: Flow<Boolean>
        get() = getPreference(PreferencesKeys.MIGRATION_V1_COMPLETED, false)

    suspend fun setMigrationV1Completed(completed: Boolean) {
        setPreference(PreferencesKeys.MIGRATION_V1_COMPLETED, completed)
    }

    val syncMetadataMigrated: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SYNC_METADATA_MIGRATED, false)

    suspend fun setSyncMetadataMigrated(migrated: Boolean) {
        setPreference(PreferencesKeys.SYNC_METADATA_MIGRATED, migrated)
    }

    // ========== Onboarding ==========

    val onboardingCompleted: Flow<Boolean>
        get() = getPreference(PreferencesKeys.ONBOARDING_COMPLETED, false)

    suspend fun setOnboardingCompleted(completed: Boolean) {
        setPreference(PreferencesKeys.ONBOARDING_COMPLETED, completed)
    }

    val shownLocalCalendarIntro: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOWN_LOCAL_CALENDAR_INTRO, false)

    suspend fun setShownLocalCalendarIntro(shown: Boolean) {
        setPreference(PreferencesKeys.SHOWN_LOCAL_CALENDAR_INTRO, shown)
    }

    val onboardingDismissed: Flow<Boolean>
        get() = getPreference(PreferencesKeys.ONBOARDING_DISMISSED, false)

    suspend fun setOnboardingDismissed(dismissed: Boolean) {
        setPreference(PreferencesKeys.ONBOARDING_DISMISSED, dismissed)
    }

    // ========== Permission Tracking ==========

    /**
     * Number of times notification permission was denied.
     * Used to determine if we should show rationale or consider it permanently denied.
     */
    val notificationPermissionDeniedCount: Flow<Int>
        get() = getPreference(PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT, 0)

    /**
     * Get the denial count synchronously (for permission state check).
     */
    suspend fun getNotificationPermissionDeniedCountBlocking(): Int =
        notificationPermissionDeniedCount.first()

    /**
     * Increment denial count when user denies permission.
     */
    suspend fun incrementNotificationPermissionDeniedCount() {
        updatePreference(PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT) { (it ?: 0) + 1 }
    }

    /**
     * Reset denial count when permission is granted.
     */
    suspend fun resetNotificationPermissionDeniedCount() {
        setPreference(PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT, 0)
    }

    // ========== Contact Birthdays ==========

    /**
     * Whether contact birthdays calendar is enabled.
     */
    val contactBirthdaysEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.CONTACT_BIRTHDAYS_ENABLED, false)

    suspend fun getContactBirthdaysEnabled(): Boolean = contactBirthdaysEnabled.first()

    suspend fun setContactBirthdaysEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.CONTACT_BIRTHDAYS_ENABLED, enabled)
    }

    /**
     * Last sync time for contact birthdays.
     */
    val contactBirthdaysLastSync: Flow<Long>
        get() = getPreference(PreferencesKeys.CONTACT_BIRTHDAYS_LAST_SYNC, 0L)

    suspend fun getContactBirthdaysLastSync(): Long = contactBirthdaysLastSync.first()

    suspend fun setContactBirthdaysLastSync(timeMillis: Long) {
        setPreference(PreferencesKeys.CONTACT_BIRTHDAYS_LAST_SYNC, timeMillis)
    }

    /**
     * Birthday reminder minutes.
     * Uses ALL_DAY_REMINDER_OPTIONS values (540 = 9 AM day of, 1440 = 1 day before, etc.)
     * Default: 540 (9 AM on day of birthday)
     */
    val birthdayReminder: Flow<Int>
        get() = getPreference(PreferencesKeys.BIRTHDAY_REMINDER, DEFAULT_BIRTHDAY_REMINDER_MINUTES)

    suspend fun getBirthdayReminder(): Int = birthdayReminder.first()

    suspend fun setBirthdayReminder(minutes: Int) {
        setPreference(PreferencesKeys.BIRTHDAY_REMINDER, minutes)
    }

    // ========== Parse Failure Retry (v16.7.0) ==========

    /**
     * Parse failure retry counts per calendar as a Flow.
     * Map of calendarId -> retryCount.
     */
    val parseFailureRetryCount: Flow<Map<Long, Int>>
        get() = getPreference(PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS, "")
            .map { json -> parseRetryCountsJson(json) }

    /**
     * Get current retry count for a calendar.
     * Returns 0 if calendar has no tracked failures.
     */
    suspend fun getParseFailureRetryCount(calendarId: Long): Int {
        val json = dataStore.data.first()[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] ?: ""
        return parseRetryCountsJson(json)[calendarId] ?: 0
    }

    /**
     * Increment retry count for a calendar.
     * Returns the new count after incrementing.
     */
    suspend fun incrementParseFailureRetry(calendarId: Long): Int {
        var newCount = 0
        dataStore.edit { preferences ->
            val json = preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] ?: ""
            val counts = parseRetryCountsJson(json).toMutableMap()
            newCount = (counts[calendarId] ?: 0) + 1
            counts[calendarId] = newCount
            preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] = serializeRetryCountsJson(counts)
        }
        return newCount
    }

    /**
     * Reset retry count for a specific calendar.
     * Called when sync succeeds or after giving up (max retries reached).
     */
    suspend fun resetParseFailureRetry(calendarId: Long) {
        dataStore.edit { preferences ->
            val json = preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] ?: ""
            val counts = parseRetryCountsJson(json).toMutableMap()
            counts.remove(calendarId)
            if (counts.isEmpty()) {
                preferences.remove(PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS)
            } else {
                preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] = serializeRetryCountsJson(counts)
            }
        }
    }

    /**
     * Clear all retry counts.
     * Called on force full sync to give a fresh start.
     */
    suspend fun clearAllParseFailureRetries() {
        removePreference(PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS)
    }

    /**
     * Parse JSON string to retry counts map.
     * Format: "calendarId:count,calendarId:count,..."
     * Simple format avoids Gson dependency for this small use case.
     */
    private fun parseRetryCountsJson(json: String): Map<Long, Int> {
        if (json.isBlank()) return emptyMap()
        return try {
            json.split(",")
                .filter { it.contains(":") }
                .associate { entry ->
                    val (id, count) = entry.split(":")
                    id.toLong() to count.toInt()
                }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Serialize retry counts map to JSON string.
     */
    private fun serializeRetryCountsJson(counts: Map<Long, Int>): String {
        return counts.entries.joinToString(",") { "${it.key}:${it.value}" }
    }

    // ========== Reminder Migration ==========

    /**
     * Get the reminder migration version.
     * Returns 0 if no migrations have been applied.
     */
    suspend fun getReminderMigrationVersion(): Int {
        return dataStore.data.first()[PreferencesKeys.REMINDER_MIGRATION_VERSION] ?: 0
    }

    /**
     * Set the reminder migration version.
     * Called after a migration is successfully applied.
     */
    suspend fun setReminderMigrationVersion(version: Int) {
        setPreference(PreferencesKeys.REMINDER_MIGRATION_VERSION, version)
    }

    // ========== Parser Version (v20.12.39) ==========

    /**
     * Get the stored parser version.
     * Returns 0 if never set (pre-v20.12.39 installations).
     */
    suspend fun getParserVersion(): Int {
        return dataStore.data.first()[PreferencesKeys.PARSER_VERSION] ?: 0
    }

    /**
     * Set the parser version after clearing etags.
     */
    suspend fun setParserVersion(version: Int) {
        setPreference(PreferencesKeys.PARSER_VERSION, version)
    }

    // ========== iCloud URL Migration ==========

    /**
     * Check if iCloud URL migration has been completed.
     */
    val icloudUrlMigrationCompleted: Flow<Boolean>
        get() = getPreference(PreferencesKeys.ICLOUD_URL_MIGRATION_COMPLETED, false)

    /**
     * Get migration status synchronously.
     */
    suspend fun getICloudUrlMigrationCompleted(): Boolean = icloudUrlMigrationCompleted.first()

    /**
     * Mark iCloud URL migration as completed.
     */
    suspend fun setICloudUrlMigrationCompleted(completed: Boolean) {
        setPreference(PreferencesKeys.ICLOUD_URL_MIGRATION_COMPLETED, completed)
    }

    /**
     * Reset iCloud URL migration status (for debugging/testing).
     * Allows re-running the migration on next sync.
     */
    suspend fun resetICloudUrlMigration() {
        setICloudUrlMigrationCompleted(false)
    }

    companion object {
        // Reminder constants
        const val REMINDER_OFF = -1  // Sentinel: no reminder set
        const val DEFAULT_REMINDER_MINUTES = 15
        const val DEFAULT_ALL_DAY_REMINDER_MINUTES = 12 * 60 // 12 hours before (720)
        const val DEFAULT_BIRTHDAY_REMINDER_MINUTES = 9 * 60 // 9 AM day of birthday (540)

        // Sync constants
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 60  // 1 hour
        const val DEFAULT_SYNC_INTERVAL_MS = 1L * 60 * 60 * 1000 // 1 hour in ms
        const val MIN_SYNC_INTERVAL_MS = 15L * 60 * 1000 // 15 minutes in ms
        const val DEFAULT_SYNC_PAST_DAYS = 30
        const val DEFAULT_SYNC_FUTURE_DAYS = 365

        // Other defaults
        const val DEFAULT_EVENT_DURATION_MINUTES = 30

        // Theme values
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // View values
        const val VIEW_MONTH = "month"
        const val VIEW_AGENDA = "agenda"
        const val VIEW_THREE_DAYS = "three_days"

        private val VALID_VIEWS = setOf(VIEW_MONTH, VIEW_AGENDA, VIEW_THREE_DAYS)

        // Time format values
        const val TIME_FORMAT_SYSTEM = "system"
        const val TIME_FORMAT_12H = "12h"
        const val TIME_FORMAT_24H = "24h"

        // First day of week values
        /** Special value for "follow system locale" */
        const val FIRST_DAY_SYSTEM = 0

        // Parser version - bump when parsing logic changes to force re-parse
        /**
         * Current parser version. Bump this when iCalendar parsing logic changes.
         *
         * History:
         * - v0: Pre-v20.12.39 (no version tracking)
         * - v1: VALUE=DATE timezone fix (use UTC instead of local timezone)
         * - v2: Windows timezone name resolution (Issue #45)
         */
        const val CURRENT_PARSER_VERSION = 2
    }
}
