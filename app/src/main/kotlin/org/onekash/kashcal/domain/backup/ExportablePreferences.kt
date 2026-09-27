package org.onekash.kashcal.domain.backup

import androidx.datastore.preferences.core.Preferences
import org.onekash.kashcal.data.preferences.PreferencesKeys

/**
 * Decides which DataStore preference keys a settings backup includes.
 *
 * [KEYS] is the allow-list. [EXCLUDED_KEY_NAMES] names every preference that must not leave the
 * device: runtime state, migration and onboarding flags, device-local choices, IDs that aren't
 * portable across installs (device-calendar selections, the default calendar), and superseded
 * keys.
 *
 * `ExportablePreferencesTest` checks by reflection that every key in [PreferencesKeys] is in
 * exactly one of the two, so a new preference that isn't classified fails the test suite.
 */
object ExportablePreferences {

    enum class PrefKind { BOOL, INT, LONG, STRING, STRING_SET }

    private val KIND_BY_KEY: Map<String, PrefKind> = mapOf(
        PreferencesKeys.FIRST_DAY_OF_WEEK.name to PrefKind.INT,
        PreferencesKeys.SHOW_WEEK_NUMBERS.name to PrefKind.BOOL,
        PreferencesKeys.AGENDA_WEEK_BAR_EXPANDED.name to PrefKind.BOOL,
        PreferencesKeys.DAY_WEEK_BAR_EXPANDED.name to PrefKind.BOOL,
        PreferencesKeys.ALL_DAY_ROWS_EXPANDED.name to PrefKind.BOOL,
        PreferencesKeys.TAGS_ABOVE_NOTES.name to PrefKind.BOOL,
        PreferencesKeys.SHOW_DECLINED_EVENTS.name to PrefKind.BOOL,
        PreferencesKeys.SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP.name to PrefKind.BOOL,
        PreferencesKeys.DEFAULT_EVENT_DURATION.name to PrefKind.INT,
        PreferencesKeys.DEFAULT_REMINDER_MINUTES.name to PrefKind.INT,
        PreferencesKeys.DEFAULT_ALL_DAY_REMINDER.name to PrefKind.INT,
        PreferencesKeys.AUTO_SYNC_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.SYNC_INTERVAL_MINUTES.name to PrefKind.INT,
        PreferencesKeys.SYNC_WIFI_ONLY.name to PrefKind.BOOL,
        PreferencesKeys.SYNC_PAST_DAYS.name to PrefKind.INT,
        PreferencesKeys.SYNC_FUTURE_DAYS.name to PrefKind.INT,
        PreferencesKeys.THEME.name to PrefKind.STRING,
        PreferencesKeys.COLOR_SOURCE.name to PrefKind.STRING,
        PreferencesKeys.ACCENT_SEED.name to PrefKind.INT,
        PreferencesKeys.WIDGET_COLOR_SOURCE.name to PrefKind.STRING,
        PreferencesKeys.WIDGET_ACCENT_SEED.name to PrefKind.INT,
        PreferencesKeys.WIDGET_THEME_SOURCE.name to PrefKind.STRING,
        PreferencesKeys.NOTIFICATION_SOUND.name to PrefKind.BOOL,
        PreferencesKeys.NOTIFICATION_VIBRATE.name to PrefKind.BOOL,
        PreferencesKeys.QUICK_ADD_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.TITLE_SUGGESTIONS_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.SHOW_EVENT_EMOJIS.name to PrefKind.BOOL,
        PreferencesKeys.TIME_FORMAT.name to PrefKind.STRING,
        PreferencesKeys.DEFAULT_CALENDAR_VIEW.name to PrefKind.STRING,
        PreferencesKeys.WIDGET_MAX_EVENTS_PER_DAY.name to PrefKind.INT,
        PreferencesKeys.WIDGET_DETAILED_ROWS.name to PrefKind.BOOL,
        PreferencesKeys.CONTACT_BIRTHDAYS_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.BIRTHDAY_REMINDER.name to PrefKind.INT,
        PreferencesKeys.CONTACT_ANNIVERSARIES_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.ANNIVERSARY_REMINDER.name to PrefKind.INT,
        PreferencesKeys.DEVICE_CALENDARS_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.DEVICE_CALENDAR_REMINDERS_ENABLED.name to PrefKind.BOOL,
        PreferencesKeys.SHARE_AVAILABILITY_DAYS.name to PrefKind.INT,
        PreferencesKeys.SHARE_AVAILABILITY_WORK_START_MIN.name to PrefKind.INT,
        PreferencesKeys.SHARE_AVAILABILITY_WORK_END_MIN.name to PrefKind.INT,
        PreferencesKeys.SHARE_AVAILABILITY_INCLUDE_ALL_DAY.name to PrefKind.BOOL,
        PreferencesKeys.USER_INITIALS.name to PrefKind.STRING,
    )

    val KEYS: List<Preferences.Key<*>> = listOf(
        // Calendar view
        PreferencesKeys.FIRST_DAY_OF_WEEK,
        PreferencesKeys.SHOW_WEEK_NUMBERS,
        // Agenda week bar expanded/collapsed: a persistent display choice, so it's exported.
        PreferencesKeys.AGENDA_WEEK_BAR_EXPANDED,
        // Day view week-strip date picker expanded/collapsed: a persistent display choice.
        PreferencesKeys.DAY_WEEK_BAR_EXPANDED,
        // All-day strip expanded/collapsed in the time-grid views: a persistent display choice.
        PreferencesKeys.ALL_DAY_ROWS_EXPANDED,
        PreferencesKeys.TAGS_ABOVE_NOTES,
        PreferencesKeys.SHOW_DECLINED_EVENTS,
        // Whether multi-day timed events show in the all-day strip: a persistent display choice.
        PreferencesKeys.SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP,
        PreferencesKeys.DEFAULT_EVENT_DURATION,
        // Event defaults (DEFAULT_CALENDAR is excluded: it stores non-portable row IDs)
        PreferencesKeys.DEFAULT_REMINDER_MINUTES,
        PreferencesKeys.DEFAULT_ALL_DAY_REMINDER,
        // Sync
        PreferencesKeys.AUTO_SYNC_ENABLED,
        PreferencesKeys.SYNC_INTERVAL_MINUTES,
        PreferencesKeys.SYNC_WIFI_ONLY,
        PreferencesKeys.SYNC_PAST_DAYS,
        PreferencesKeys.SYNC_FUTURE_DAYS,
        // UI
        PreferencesKeys.THEME,
        PreferencesKeys.COLOR_SOURCE,
        PreferencesKeys.ACCENT_SEED,
        // Widget appearance: persistent choices, like the app's theme and colors above
        PreferencesKeys.WIDGET_COLOR_SOURCE,
        PreferencesKeys.WIDGET_ACCENT_SEED,
        PreferencesKeys.WIDGET_THEME_SOURCE,
        PreferencesKeys.NOTIFICATION_SOUND,
        PreferencesKeys.NOTIFICATION_VIBRATE,
        PreferencesKeys.QUICK_ADD_ENABLED,
        PreferencesKeys.TITLE_SUGGESTIONS_ENABLED,
        // Display
        PreferencesKeys.SHOW_EVENT_EMOJIS,
        PreferencesKeys.TIME_FORMAT,
        PreferencesKeys.DEFAULT_CALENDAR_VIEW,
        PreferencesKeys.WIDGET_MAX_EVENTS_PER_DAY,
        PreferencesKeys.WIDGET_DETAILED_ROWS,
        // Contact birthdays & anniversaries
        PreferencesKeys.CONTACT_BIRTHDAYS_ENABLED,
        PreferencesKeys.BIRTHDAY_REMINDER,
        PreferencesKeys.CONTACT_ANNIVERSARIES_ENABLED,
        PreferencesKeys.ANNIVERSARY_REMINDER,
        // Device calendar toggles only (the ID sets are excluded: not portable)
        PreferencesKeys.DEVICE_CALENDARS_ENABLED,
        PreferencesKeys.DEVICE_CALENDAR_REMINDERS_ENABLED,
        // Share-availability sheet preferences
        PreferencesKeys.SHARE_AVAILABILITY_DAYS,
        PreferencesKeys.SHARE_AVAILABILITY_WORK_START_MIN,
        PreferencesKeys.SHARE_AVAILABILITY_WORK_END_MIN,
        PreferencesKeys.SHARE_AVAILABILITY_INCLUDE_ALL_DAY,
        // Profile: the user's avatar initials
        PreferencesKeys.USER_INITIALS,
    ).also {
        // Bump this and the matching ExportablePreferencesTest assertion together
        // whenever a key is added to or removed from KEYS above.
        require(it.size == 42) {
            "KEYS size drifted; expected 42 allowed keys but got ${it.size}. Update ExportablePreferencesTest expectations too."
        }
    }

    val EXCLUDED_KEY_NAMES: Set<String> = setOf(
        // Runtime state
        PreferencesKeys.LAST_SYNC_TIME.name,
        // Where the timeline was last scrolled to: per-device view state, not a setting
        PreferencesKeys.WEEK_VIEW_SCROLL_MINUTES.name,
        // The timeline's last pinch-to-zoom hour height: per-device view state, not a setting
        PreferencesKeys.WEEK_VIEW_HOUR_HEIGHT.name,
        PreferencesKeys.CONTACT_BIRTHDAYS_LAST_SYNC.name,
        PreferencesKeys.CONTACT_ANNIVERSARIES_LAST_SYNC.name,
        PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT.name,
        // Set when a background contact sync hit a revoked WRITE_CONTACTS; drives a re-grant
        // banner on this device
        PreferencesKeys.CONTACT_SYNC_PERMISSION_NEEDED.name,
        PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS.name,
        PreferencesKeys.LAST_APP_VERSION_CODE.name,
        // Migration flags
        PreferencesKeys.MIGRATION_V1_COMPLETED.name,
        PreferencesKeys.SYNC_METADATA_MIGRATED.name,
        PreferencesKeys.REMINDER_MIGRATION_VERSION.name,
        PreferencesKeys.PARSER_VERSION.name,
        PreferencesKeys.ICLOUD_URL_MIGRATION_COMPLETED.name,
        // Onboarding flags
        PreferencesKeys.ONBOARDING_COMPLETED.name,
        PreferencesKeys.SHOWN_LOCAL_CALENDAR_INTRO.name,
        PreferencesKeys.SHOWN_SHARE_CARD_TOOLTIP.name,
        PreferencesKeys.ONBOARDING_DISMISSED.name,
        PreferencesKeys.LAST_WHATSNEW_VERSION_SHOWN.name,
        // Permission-banner dismissal, tied to this device's contacts permission
        PreferencesKeys.CONTACT_SUGGESTIONS_DECLINED.name,
        // App lock: tied to this device's enrolled biometric or screen lock, so each device
        // decides its own
        PreferencesKeys.APP_LOCK_ENABLED.name,
        // Device calendar selection IDs: not portable across installs
        PreferencesKeys.ENABLED_DEVICE_CALENDAR_IDS.name,
        PreferencesKeys.HIDDEN_DEVICE_CALENDAR_IDS.name,
        // Stores "room:<id>" or "device:<id>"; neither ID is portable across installs
        PreferencesKeys.DEFAULT_CALENDAR.name,
        // Legacy (superseded)
        PreferencesKeys.DEFAULT_CALENDAR_ID.name,
    )

    fun toBackupValue(key: Preferences.Key<*>, rawValue: Any): BackupPreferenceValue? {
        return when (rawValue) {
            is Boolean -> BackupPreferenceValue.BoolPref(rawValue)
            is Int -> BackupPreferenceValue.IntPref(rawValue)
            is Long -> BackupPreferenceValue.LongPref(rawValue)
            is String -> BackupPreferenceValue.StringPref(rawValue)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                val strings = rawValue.filterIsInstance<String>().toSet()
                if (strings.size != rawValue.size) null
                else BackupPreferenceValue.StringSetPref(strings)
            }
            else -> null
        }
    }

    fun fromBackupValue(
        name: String,
        value: BackupPreferenceValue,
    ): Pair<Preferences.Key<*>, Any>? {
        val known = KEYS.firstOrNull { it.name == name } ?: return null
        return matchValueToKey(known, value)?.let { known to it }
    }

    private fun matchValueToKey(key: Preferences.Key<*>, value: BackupPreferenceValue): Any? {
        val kind = KIND_BY_KEY[key.name] ?: return null
        return when (kind) {
            PrefKind.BOOL -> (value as? BackupPreferenceValue.BoolPref)?.value
            PrefKind.INT -> (value as? BackupPreferenceValue.IntPref)?.value
            PrefKind.LONG -> (value as? BackupPreferenceValue.LongPref)?.value
            PrefKind.STRING -> (value as? BackupPreferenceValue.StringPref)?.value
            PrefKind.STRING_SET -> (value as? BackupPreferenceValue.StringSetPref)?.value
        }
    }
}
