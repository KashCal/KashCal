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

    /** Selected view type: "day", "week", "month", "agenda" */
    val CALENDAR_VIEW = stringPreferencesKey("calendar_view")

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
}
