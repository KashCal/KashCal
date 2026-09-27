package org.onekash.kashcal.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Declares the DataStore preference keys, grouped by feature.
 *
 * Every key must be allow-listed or excluded in
 * [org.onekash.kashcal.domain.backup.ExportablePreferences]; `ExportablePreferencesTest` fails
 * otherwise.
 */
object PreferencesKeys {

    // ========== Calendar View ==========

    /**
     * First day of week as a `java.util.Calendar` day constant (1 = Sunday, 2 = Monday), or
     * [KashCalDataStore.FIRST_DAY_SYSTEM] to follow the locale.
     */
    val FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week")

    /** Shows week numbers in the calendar views. */
    val SHOW_WEEK_NUMBERS = booleanPreferencesKey("show_week_numbers")

    /** Render the event form's tag row above the notes/attendees block. */
    val TAGS_ABOVE_NOTES = booleanPreferencesKey("tags_above_notes")

    /** Shows events the user declined. */
    val SHOW_DECLINED_EVENTS = booleanPreferencesKey("show_declined_events")

    /** Shows multi-day timed events in the all-day strip instead of the timed grid. */
    val SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP = booleanPreferencesKey("show_multiday_timed_in_allday_strip")

    /**
     * Default for [SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP], read by [KashCalDataStore], the account
     * settings ViewModel and screen, and [org.onekash.kashcal.ui.viewmodels.HomeViewModel] so
     * the four agree. It is false: true would move every existing user's multi-day timed events
     * into the all-day strip on update.
     */
    const val DEFAULT_SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP = false

    /** Default event duration in minutes. */
    val DEFAULT_EVENT_DURATION = intPreferencesKey("default_event_duration")

    // ========== Event Defaults ==========

    /**
     * Legacy default calendar: a plain Room calendar id. [KashCalDataStore.getDefaultCalendar]
     * reads it only when [DEFAULT_CALENDAR] is unset.
     */
    val DEFAULT_CALENDAR_ID = longPreferencesKey("default_calendar_id")

    /** Default calendar for new events, in the [DefaultCalendar] stored form. */
    val DEFAULT_CALENDAR = stringPreferencesKey("default_calendar")

    /**
     * Default timed-event reminder in minutes before start; [KashCalDataStore.REMINDER_OFF]
     * means no reminder.
     */
    val DEFAULT_REMINDER_MINUTES = intPreferencesKey("default_reminder_minutes")

    /**
     * Default all-day reminder in signed minutes before the event's midnight (900 = 9 AM the day
     * before, -540 = 9 AM the day of); [KashCalDataStore.REMINDER_OFF] means no reminder.
     */
    val DEFAULT_ALL_DAY_REMINDER = intPreferencesKey("default_all_day_reminder")

    // ========== Sync Settings ==========

    /** Auto-sync on or off. */
    val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")

    /** Sync interval in minutes. */
    val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")

    /** Syncs on Wi-Fi only. */
    val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")

    /** Last successful sync time in epoch millis. */
    val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")

    /** Sync window: days in the past. */
    val SYNC_PAST_DAYS = intPreferencesKey("sync_past_days")

    /** Sync window: days in the future. */
    val SYNC_FUTURE_DAYS = intPreferencesKey("sync_future_days")

    // ========== UI Settings ==========

    /** Theme face: "system", "light" or "dark"; a legacy "teal" migrates to a seed accent. */
    val THEME = stringPreferencesKey("theme")

    /** Color source: "dynamic" (Material You or baseline) or "seed" (from [ACCENT_SEED]). */
    val COLOR_SOURCE = stringPreferencesKey("color_source")

    /** Accent seed as a packed ARGB int; drives the generated scheme when the source is "seed". */
    val ACCENT_SEED = intPreferencesKey("accent_seed")

    /** Widget color source, apart from the app's: "follow_app" (default), "dynamic" or "seed". */
    val WIDGET_COLOR_SOURCE = stringPreferencesKey("widget_color_source")

    /** Widget-only accent seed as a packed ARGB int, used when the widget source is "seed". */
    val WIDGET_ACCENT_SEED = intPreferencesKey("widget_accent_seed")

    /** Widget theme source: "follow_app" (default), "light" or "dark". */
    val WIDGET_THEME_SOURCE = stringPreferencesKey("widget_theme_mode")

    /** Plays notification sounds. */
    val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")

    /** Vibrates on notifications. */
    val NOTIFICATION_VIBRATE = booleanPreferencesKey("notification_vibrate")

    /** Quick Add on or off (shows the FAB). */
    val QUICK_ADD_ENABLED = booleanPreferencesKey("quick_add_enabled")

    /** Autocompletes event titles from past events (default true). */
    val TITLE_SUGGESTIONS_ENABLED = booleanPreferencesKey("title_suggestions_enabled")

    // ========== Privacy ==========

    /**
     * Requires device biometric or screen lock to reveal the UI on reopen (default false). It
     * veils visibility only; not a secret, so stored plain.
     */
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")

    // ========== Display Settings ==========

    /** Shows auto-detected emojis in event titles. */
    val SHOW_EVENT_EMOJIS = booleanPreferencesKey("show_event_emojis")

    /** Shows the week bar at the top of the Agenda view (default true). */
    val AGENDA_WEEK_BAR_EXPANDED = booleanPreferencesKey("agenda_week_bar_expanded")

    /** Shows the week-strip date picker at the top of the Day view (default true). */
    val DAY_WEEK_BAR_EXPANDED = booleanPreferencesKey("day_week_bar_expanded")

    /**
     * Expands the all-day strip of the Day, 3-Day and Week grids to up to 3 rows per day
     * (collapsed: 1 row). Default false, so existing users see no change on upgrade.
     */
    val ALL_DAY_ROWS_EXPANDED = booleanPreferencesKey("all_day_rows_expanded")

    /** Time format: "system", "12h" or "24h". */
    val TIME_FORMAT = stringPreferencesKey("time_format")

    /** Default calendar view: one of the `VIEW_` values in [KashCalDataStore]. */
    val DEFAULT_CALENDAR_VIEW = stringPreferencesKey("default_calendar_view")

    /** Maximum events shown per day in the Agenda and Week widgets. */
    val WIDGET_MAX_EVENTS_PER_DAY = intPreferencesKey("widget_max_events_per_day")

    /**
     * Widget row density for the Agenda, Week and Upcoming widgets: false (default) gives
     * compact one-line rows (color bar, start time, title); true gives two-line rows (title,
     * then start-end time).
     */
    val WIDGET_DETAILED_ROWS = booleanPreferencesKey("widget_detailed_rows")

    /**
     * Last scroll position of the Day, 3-Day and Week grids as minutes from midnight (0..1439),
     * restored on cold launch. Stored as clock time, not pixels, so a zoom change between
     * sessions still restores the same time. -1 means never saved.
     */
    val WEEK_VIEW_SCROLL_MINUTES = intPreferencesKey("week_view_scroll_minutes")

    /**
     * Pinch-to-zoom level of the Day, 3-Day and Week grids as the hour-row height in dp,
     * restored on cold launch. Clamped to `WeekViewUtils.MIN_HOUR_HEIGHT_DP` ..
     * `MAX_HOUR_HEIGHT_DP` on restore. Absent means the default zoom.
     */
    val WEEK_VIEW_HOUR_HEIGHT = floatPreferencesKey("week_view_hour_height")

    // ========== Migration Flags ==========

    /** Data migration from v1 completed. Nothing reads it today. */
    val MIGRATION_V1_COMPLETED = booleanPreferencesKey("migration_v1_completed")

    /** Sync metadata migrated to Room. Nothing reads it today. */
    val SYNC_METADATA_MIGRATED = booleanPreferencesKey("sync_metadata_migrated")

    // ========== Onboarding ==========

    /** Onboarding completed. */
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    /** The local calendar intro has been shown. */
    val SHOWN_LOCAL_CALENDAR_INTRO = booleanPreferencesKey("shown_local_calendar_intro")

    /** The coach mark for the share-as-card Share icon has been shown. */
    val SHOWN_SHARE_CARD_TOOLTIP = booleanPreferencesKey("shown_share_card_tooltip")

    /** Onboarding sheet dismissed. */
    val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")

    /** The user declined contact suggestions in the attendee picker; never re-prompt. */
    val CONTACT_SUGGESTIONS_DECLINED = booleanPreferencesKey("contact_suggestions_declined")

    /**
     * Highest versionCode whose What's New entry the user has acknowledged. 0 means never
     * tracked: on first launch [org.onekash.kashcal.domain.whatsnew.WhatsNewSeeder] seeds it
     * with the current version on a fresh install, or with the previous version after an
     * upgrade so that upgrade's notes show. The sheet then shows every authored release with a
     * versionCode above this value, up to the current one.
     */
    val LAST_WHATSNEW_VERSION_SHOWN = intPreferencesKey("last_whatsnew_version_shown")

    // ========== Permission Tracking ==========

    /** Times the notification permission was denied; decides rationale vs permanently denied. */
    val NOTIFICATION_PERMISSION_DENIED_COUNT = intPreferencesKey("notification_permission_denied_count")

    /**
     * Contact sync lacks its permission ([KashCalDataStore.contactSyncPermissionNeeded]).
     * App-global (the permission is app-wide, not per account) and device-local, so excluded
     * from backups like [NOTIFICATION_PERMISSION_DENIED_COUNT].
     */
    val CONTACT_SYNC_PERMISSION_NEEDED = booleanPreferencesKey("contact_sync_permission_needed")

    // ========== Contact Birthdays ==========

    /** Contact birthdays calendar enabled. */
    val CONTACT_BIRTHDAYS_ENABLED = booleanPreferencesKey("contact_birthdays_enabled")

    /** Last sync time for contact birthdays. */
    val CONTACT_BIRTHDAYS_LAST_SYNC = longPreferencesKey("contact_birthdays_last_sync")

    /** Birthday reminder; units and default on [KashCalDataStore.birthdayReminder]. */
    val BIRTHDAY_REMINDER = intPreferencesKey("birthday_reminder")

    // ========== Contact Anniversaries ==========

    /** Contact anniversaries calendar enabled. */
    val CONTACT_ANNIVERSARIES_ENABLED = booleanPreferencesKey("contact_anniversaries_enabled")

    /** Last sync time for contact anniversaries. */
    val CONTACT_ANNIVERSARIES_LAST_SYNC = longPreferencesKey("contact_anniversaries_last_sync")

    /** Anniversary reminder; units and default on [KashCalDataStore.anniversaryReminder]. */
    val ANNIVERSARY_REMINDER = intPreferencesKey("anniversary_reminder")

    // ========== Device Calendars ==========

    /** Device calendar integration enabled. */
    val DEVICE_CALENDARS_ENABLED = booleanPreferencesKey("device_calendars_enabled")

    /** Enabled device calendar ids, stored as strings. */
    val ENABLED_DEVICE_CALENDAR_IDS = stringSetPreferencesKey("enabled_device_calendar_ids")

    /** Device calendar ids that are enabled but hidden from view, stored as strings. */
    val HIDDEN_DEVICE_CALENDAR_IDS = stringSetPreferencesKey("hidden_device_calendar_ids")

    /** KashCal fires reminders for device calendar events. */
    val DEVICE_CALENDAR_REMINDERS_ENABLED = booleanPreferencesKey("device_calendar_reminders_enabled")

    // ========== Parse Failure Retry (v16.7.0) ==========

    /**
     * Parse failure retry counts per calendar, stored as "calendarId:count,calendarId:count".
     * While a calendar has retries left, the pull holds its sync-token on parse errors.
     */
    val PARSE_FAILURE_RETRY_COUNTS = stringPreferencesKey("parse_failure_retry_counts")

    // ========== Reminder Migration ==========

    /**
     * Version of the one-time reminder data fixes applied.
     * v1: timezone fix, recalculates all-day reminder trigger times (v21.x).
     */
    val REMINDER_MIGRATION_VERSION = intPreferencesKey("reminder_migration_version")

    // ========== App Version Tracking ==========

    /**
     * Last installed app version code, added in v20.12.36 (281). Nothing reads it today; the
     * backup excludes it.
     */
    val LAST_APP_VERSION_CODE = intPreferencesKey("last_app_version_code")

    // ========== Parser Version (v20.12.39) ==========

    /**
     * iCalendar parser version last applied. On app start, a stored value below
     * [KashCalDataStore.CURRENT_PARSER_VERSION] clears every event etag so the next sync
     * re-parses all events; the version history is on that constant.
     */
    val PARSER_VERSION = intPreferencesKey("parser_version")

    // ========== iCloud URL Migration ==========

    /**
     * Set once [org.onekash.kashcal.sync.provider.icloud.ICloudUrlMigration] has rewritten
     * regional iCloud URLs (p180-caldav.icloud.com) to the canonical host (caldav.icloud.com).
     */
    val ICLOUD_URL_MIGRATION_COMPLETED = booleanPreferencesKey("icloud_url_migration_completed")

    // ========== Share Availability ==========

    /** Days in the shared availability summary (1..14, default 7). */
    val SHARE_AVAILABILITY_DAYS = intPreferencesKey("share_availability_days")

    /** Working-hours window start as minutes from midnight (0..1439, default 540 = 09:00). */
    val SHARE_AVAILABILITY_WORK_START_MIN = intPreferencesKey("share_availability_work_start_min")

    /** Working-hours window end as minutes from midnight (1..1440, default 1020 = 17:00). */
    val SHARE_AVAILABILITY_WORK_END_MIN = intPreferencesKey("share_availability_work_end_min")

    /** Treat all-day events as busy when computing free blocks (default false). */
    val SHARE_AVAILABILITY_INCLUDE_ALL_DAY = booleanPreferencesKey("share_availability_include_all_day")

    // ========== Profile ==========

    /**
     * User's initials, up to 2 letters, shown in the top-bar avatar and account hub; empty shows
     * the generic glyph.
     */
    val USER_INITIALS = stringPreferencesKey("user_initials")
}
