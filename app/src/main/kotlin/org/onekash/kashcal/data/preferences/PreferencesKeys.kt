package org.onekash.kashcal.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

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

    /** First day of week: 1=Sunday, 2=Monday, etc. (Calendar.SUNDAY, etc.) */
    val FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week")

    /** Show week numbers in calendar view */
    val SHOW_WEEK_NUMBERS = booleanPreferencesKey("show_week_numbers")

    /** Render the event form's tag row above the notes/attendees block. */
    val TAGS_ABOVE_NOTES = booleanPreferencesKey("tags_above_notes")

    /** Show declined events */
    val SHOW_DECLINED_EVENTS = booleanPreferencesKey("show_declined_events")

    /** Default event duration in minutes */
    val DEFAULT_EVENT_DURATION = intPreferencesKey("default_event_duration")

    // ========== Event Defaults ==========

    /** Default calendar ID for new events (legacy - plain Long) */
    val DEFAULT_CALENDAR_ID = longPreferencesKey("default_calendar_id")

    /**
     * Default calendar for new events (prefixed string format).
     * Format: "room:123" or "device:456"
     * See DefaultCalendar sealed class for parsing.
     */
    val DEFAULT_CALENDAR = stringPreferencesKey("default_calendar")

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

    /** Theme face: "system", "light", "dark" (legacy value "teal" migrates to a seed accent) */
    val THEME = stringPreferencesKey("theme")

    /** Color source: "dynamic" (Material You / baseline) or "seed" (accent-derived) */
    val COLOR_SOURCE = stringPreferencesKey("color_source")

    /** Accent seed color as packed ARGB int; drives the generated scheme when source is "seed" */
    val ACCENT_SEED = intPreferencesKey("accent_seed")

    /** Widget color source: "follow_app" (default), "dynamic", or "seed" — independent of the app */
    val WIDGET_COLOR_SOURCE = stringPreferencesKey("widget_color_source")

    /** Widget-only accent seed as packed ARGB int, used when the widget color source is "seed" */
    val WIDGET_ACCENT_SEED = intPreferencesKey("widget_accent_seed")

    /** Widget theme source: "follow_app" (default), "light", or "dark" — tracks or pins the widget face */
    val WIDGET_THEME_SOURCE = stringPreferencesKey("widget_theme_mode")

    /** Enable notification sounds */
    val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")

    /** Enable vibration for notifications */
    val NOTIFICATION_VIBRATE = booleanPreferencesKey("notification_vibrate")

    /** Quick add event enabled (shows FAB) */
    val QUICK_ADD_ENABLED = booleanPreferencesKey("quick_add_enabled")

    /** Event title autocomplete from past events (default true). */
    val TITLE_SUGGESTIONS_ENABLED = booleanPreferencesKey("title_suggestions_enabled")

    // ========== Privacy ==========

    /**
     * App lock enabled — require device biometric / screen-lock to reveal the UI
     * on reopen (default false). Veils visibility only; not a secret, stored plain.
     */
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")

    // ========== Display Settings ==========

    /** Show auto-detected emojis in event titles */
    val SHOW_EVENT_EMOJIS = booleanPreferencesKey("show_event_emojis")

    /** Show the week bar at the top of the Agenda view. Default: true (expanded) */
    val AGENDA_WEEK_BAR_EXPANDED = booleanPreferencesKey("agenda_week_bar_expanded")

    /** Show the week-strip date picker at the top of the Day view. Default: true (expanded) */
    val DAY_WEEK_BAR_EXPANDED = booleanPreferencesKey("day_week_bar_expanded")

    /**
     * Whether the all-day strip in the Day/3-Day/Week time-grid views is expanded
     * (up to 3 rows per day) vs collapsed (1 row, the historical behavior).
     * Default: false (collapsed) so existing users see no change on upgrade.
     */
    val ALL_DAY_ROWS_EXPANDED = booleanPreferencesKey("all_day_rows_expanded")

    /** Time format preference: "system", "12h", or "24h" */
    val TIME_FORMAT = stringPreferencesKey("time_format")

    /** Default calendar view: "month", "agenda", or "three_days" */
    val DEFAULT_CALENDAR_VIEW = stringPreferencesKey("default_calendar_view")

    /** Maximum events shown per day in widgets (agenda + week) */
    val WIDGET_MAX_EVENTS_PER_DAY = intPreferencesKey("widget_max_events_per_day")

    /**
     * Widget row density. false = compact single-line rows (color bar, start time, title);
     * true = detailed two-line rows (title, then start-end time). Applies to the Agenda,
     * Week, and Upcoming list widgets. Default: false (compact).
     */
    val WIDGET_DETAILED_ROWS = booleanPreferencesKey("widget_detailed_rows")

    /**
     * Month widget day-cell event style. false = colored indicator dots below the day
     * number; true = event title rows (mirroring the in-app month view: timed events as
     * color stripe + title, all-day events as filled/tinted chips). Default: false (dots).
     */
    val MONTH_WIDGET_EVENT_TITLES = booleanPreferencesKey("month_widget_event_titles")

    /**
     * Last vertical scroll position of the Day/3-Day/Week time grid, stored as
     * minutes from midnight (0..1439). Restored on cold launch so the timeline
     * opens where the user last left it instead of the default hour. Stored as
     * clock time (not pixels) so pinch-zoom hour-height changes between sessions
     * still restore to the same time. -1 means "never saved" (fresh install).
     */
    val WEEK_VIEW_SCROLL_MINUTES = intPreferencesKey("week_view_scroll_minutes")

    /**
     * Pinch-to-zoom level of the Day/3-Day/Week time grid, stored as the hour-row
     * height in dp. Restored on cold launch so the timeline reopens at the same
     * density the user last set instead of the default. Stored and consumed in the
     * same unit (dp), so no conversion is needed. Clamped into the valid pinch range
     * (MIN_HOUR_HEIGHT_DP..MAX_HOUR_HEIGHT_DP) on restore. Absent = default zoom.
     */
    val WEEK_VIEW_HOUR_HEIGHT = floatPreferencesKey("week_view_hour_height")

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

    /** Has shown the share-as-card top-right Share icon coach mark */
    val SHOWN_SHARE_CARD_TOOLTIP = booleanPreferencesKey("shown_share_card_tooltip")

    /** Onboarding sheet dismissed */
    val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")

    /** User declined contact suggestions in the attendee picker — never re-prompt */
    val CONTACT_SUGGESTIONS_DECLINED = booleanPreferencesKey("contact_suggestions_declined")

    /**
     * Highest versionCode whose What's New entry the user has acknowledged.
     * 0 means "never tracked": fresh installs (and users present before this
     * key shipped) record the current version on first launch and stay
     * silent. After that, the sheet appears whenever any authored release
     * has versionCode > this value.
     */
    val LAST_WHATSNEW_VERSION_SHOWN = intPreferencesKey("last_whatsnew_version_shown")

    // ========== Permission Tracking ==========

    /** Number of times notification permission was denied (for rationale/permanently denied logic) */
    val NOTIFICATION_PERMISSION_DENIED_COUNT = intPreferencesKey("notification_permission_denied_count")

    /**
     * True when a background contact sync ran while WRITE_CONTACTS was revoked,
     * so settings can surface an inline re-grant affordance. App-global (the
     * permission is app-wide, not per-account) and device-local runtime state —
     * excluded from backups, mirroring [NOTIFICATION_PERMISSION_DENIED_COUNT].
     */
    val CONTACT_SYNC_PERMISSION_NEEDED = booleanPreferencesKey("contact_sync_permission_needed")

    // ========== Contact Birthdays ==========

    /** Contact birthdays calendar enabled */
    val CONTACT_BIRTHDAYS_ENABLED = booleanPreferencesKey("contact_birthdays_enabled")

    /** Last sync time for contact birthdays */
    val CONTACT_BIRTHDAYS_LAST_SYNC = longPreferencesKey("contact_birthdays_last_sync")

    /** Birthday reminder minutes (uses ALL_DAY_REMINDER_OPTIONS values, default: 540 = 9 AM day of) */
    val BIRTHDAY_REMINDER = intPreferencesKey("birthday_reminder")

    // ========== Contact Anniversaries ==========

    /** Contact anniversaries calendar enabled */
    val CONTACT_ANNIVERSARIES_ENABLED = booleanPreferencesKey("contact_anniversaries_enabled")

    /** Last sync time for contact anniversaries */
    val CONTACT_ANNIVERSARIES_LAST_SYNC = longPreferencesKey("contact_anniversaries_last_sync")

    /** Anniversary reminder minutes (uses ALL_DAY_REMINDER_OPTIONS values, default: 540 = 9 AM day of) */
    val ANNIVERSARY_REMINDER = intPreferencesKey("anniversary_reminder")

    // ========== Device Calendars ==========

    /** Device calendars integration enabled */
    val DEVICE_CALENDARS_ENABLED = booleanPreferencesKey("device_calendars_enabled")

    /** Enabled device calendar IDs (stored as Set<String>, converted to Set<Long>) */
    val ENABLED_DEVICE_CALENDAR_IDS = stringSetPreferencesKey("enabled_device_calendar_ids")

    /** Hidden device calendar IDs — enabled but hidden from view (stored as Set<String>, converted to Set<Long>) */
    val HIDDEN_DEVICE_CALENDAR_IDS = stringSetPreferencesKey("hidden_device_calendar_ids")

    /** Device calendar reminders enabled (KashCal fires reminders for device calendar events) */
    val DEVICE_CALENDAR_REMINDERS_ENABLED = booleanPreferencesKey("device_calendar_reminders_enabled")

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

    // ========== iCloud URL Migration ==========

    /**
     * iCloud URL migration to canonical form completed.
     * One-time migration that normalizes regional URLs (p180-caldav.icloud.com)
     * to canonical form (caldav.icloud.com) for all accounts, calendars, events,
     * and pending operations.
     */
    val ICLOUD_URL_MIGRATION_COMPLETED = booleanPreferencesKey("icloud_url_migration_completed")

    // ========== Share Availability ==========

    /** Number of days to include in shared availability summary (1..14, default 7). */
    val SHARE_AVAILABILITY_DAYS = intPreferencesKey("share_availability_days")

    /** Working-hours window start as minutes from midnight (0..1440, default 540 = 09:00). */
    val SHARE_AVAILABILITY_WORK_START_MIN = intPreferencesKey("share_availability_work_start_min")

    /** Working-hours window end as minutes from midnight (0..1440, default 1020 = 17:00). */
    val SHARE_AVAILABILITY_WORK_END_MIN = intPreferencesKey("share_availability_work_end_min")

    /** Treat all-day events as busy when computing free blocks (default false). */
    val SHARE_AVAILABILITY_INCLUDE_ALL_DAY = booleanPreferencesKey("share_availability_include_all_day")

    // ========== Profile ==========

    /** User's up-to-2-letter initials shown in the top-bar avatar and account hub; empty = generic glyph. */
    val USER_INITIALS = stringPreferencesKey("user_initials")
}
