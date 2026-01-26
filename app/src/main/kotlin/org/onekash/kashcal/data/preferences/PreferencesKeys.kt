package org.onekash.kashcal.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Type-safe keys for DataStore preferences.
 *
 * Organized by category:
 * - Calendar View: Display settings
 * - Event Defaults: New event settings
 * - Sync: Sync behavior settings
 * - UI: Theme, notifications
 * - Migration: One-time migration flags
 */
object PreferencesKeys {

    // ========== Calendar View ==========

    /** Selected view type: "day", "week", "month", "agenda" (legacy) */
    val CALENDAR_VIEW = stringPreferencesKey("calendar_view")

    /** Calendar view type for bottom nav: "MONTH" or "WEEK" (v20.2.0) */
    val CALENDAR_VIEW_TYPE = stringPreferencesKey("calendar_view_type")

    /** First day of week: 1=Sunday, 2=Monday, etc. (Calendar.SUNDAY, etc.) */
    val FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week")

    /** Show week numbers in calendar view */
    val SHOW_WEEK_NUMBERS = booleanPreferencesKey("show_week_numbers")

    /** Show declined events */
    val SHOW_DECLINED_EVENTS = booleanPreferencesKey("show_declined_events")

    /** Default event duration in minutes */
    val DEFAULT_EVENT_DURATION = intPreferencesKey("default_event_duration")

    // ========== Event Defaults ==========

    /** Default calendar ID for new events */
    val DEFAULT_CALENDAR_ID = longPreferencesKey("default_calendar_id")

    /** Default reminder minutes before event (0 = no reminder) */
    val DEFAULT_REMINDER_MINUTES = intPreferencesKey("default_reminder_minutes")

    /** Default all-day event reminder time in minutes from midnight */
    val DEFAULT_ALL_DAY_REMINDER = intPreferencesKey("default_all_day_reminder")

    // ========== Sync Settings ==========

    /** Auto-sync enabled */
    val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")

    /** Sync interval in minutes */
    val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")

    /** Sync on Wi-Fi only */
    val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")

    /** Last successful sync timestamp (millis) */
    val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")

    /** Sync events from N days in the past */
    val SYNC_PAST_DAYS = intPreferencesKey("sync_past_days")

    /** Sync events up to N days in the future */
    val SYNC_FUTURE_DAYS = intPreferencesKey("sync_future_days")

    // ========== UI Settings ==========

    /** Theme: "system", "light", "dark" */
    val THEME = stringPreferencesKey("theme")

    /** Enable notification sounds */
    val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")

    /** Enable vibration for notifications */
    val NOTIFICATION_VIBRATE = booleanPreferencesKey("notification_vibrate")

    /** Quick add event enabled (shows FAB) */
    val QUICK_ADD_ENABLED = booleanPreferencesKey("quick_add_enabled")

    // ========== Display Settings ==========

    /** Show auto-detected emojis in event titles */
    val SHOW_EVENT_EMOJIS = booleanPreferencesKey("show_event_emojis")

    /** Time format preference: "system", "12h", or "24h" */
    val TIME_FORMAT = stringPreferencesKey("time_format")

    // ========== Migration Flags ==========

    /** Data migration from v1 completed */
    val MIGRATION_V1_COMPLETED = booleanPreferencesKey("migration_v1_completed")

    /** Sync metadata migrated to Room */
    val SYNC_METADATA_MIGRATED = booleanPreferencesKey("sync_metadata_migrated")

    // ========== Onboarding ==========

    /** Onboarding completed */
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    /** Has shown local calendar intro */
    val SHOWN_LOCAL_CALENDAR_INTRO = booleanPreferencesKey("shown_local_calendar_intro")

    /** Onboarding sheet dismissed */
    val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")

    // ========== Permission Tracking ==========

    /** Number of times notification permission was denied (for rationale/permanently denied logic) */
    val NOTIFICATION_PERMISSION_DENIED_COUNT = intPreferencesKey("notification_permission_denied_count")

    // ========== Contact Birthdays ==========

    /** Contact birthdays calendar enabled */
    val CONTACT_BIRTHDAYS_ENABLED = booleanPreferencesKey("contact_birthdays_enabled")

    /** Last sync time for contact birthdays */
    val CONTACT_BIRTHDAYS_LAST_SYNC = longPreferencesKey("contact_birthdays_last_sync")

    /** Birthday reminder minutes (uses ALL_DAY_REMINDER_OPTIONS values, default: 540 = 9 AM day of) */
    val BIRTHDAY_REMINDER = intPreferencesKey("birthday_reminder")

    // ========== Parse Failure Retry (v16.7.0) ==========

    /**
     * Parse failure retry counts per calendar.
     * Stored as JSON map: {"calendarId": retryCount, ...}
     * Used to hold sync token when parse errors occur, allowing retries before advancing.
     */
    val PARSE_FAILURE_RETRY_COUNTS = stringPreferencesKey("parse_failure_retry_counts")

    // ========== Reminder Migration ==========

    /**
     * Reminder migration version for one-time data fixes.
     * v1: Timezone fix - recalculate all-day reminder trigger times (v21.x)
     */
    val REMINDER_MIGRATION_VERSION = intPreferencesKey("reminder_migration_version")

    // ========== App Version Tracking ==========

    /**
     * Last installed app version code.
     * Used to detect upgrades and perform cleanup (e.g., cancel stale WorkManager jobs).
     * v20.12.36 (281): Added to fix upgrade crashes from v20.11.7
     */
    val LAST_APP_VERSION_CODE = intPreferencesKey("last_app_version_code")

    // ========== Parser Version (v20.12.39) ==========

    /**
     * Parser version for iCalendar data.
     * When parsing logic changes (e.g., timezone handling), bump CURRENT_PARSER_VERSION
     * in KashCalDataStore. On app start, if stored version < current, all event etags
     * are cleared to force re-parsing on next sync.
     *
     * History:
     * - v1: Initial (VALUE=DATE timezone fix - use UTC instead of local)
     */
    val PARSER_VERSION = intPreferencesKey("parser_version")
}
