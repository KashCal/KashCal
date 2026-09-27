package org.onekash.kashcal.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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

// A corrupt preferences file is replaced with empty preferences, so every setting reads its
// default.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kashcal_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w("KashCalDataStore", "Preferences file corrupted, resetting to defaults")
        emptyPreferences()
    }
)

/**
 * Wraps the app's preferences DataStore with typed properties per [PreferencesKeys] entry.
 *
 * Each setting is a `Flow` property, often with a suspend getter that reads it once and a
 * setter. [overrideDataStore] replaces the file-backed store in tests.
 */
class KashCalDataStore(
    private val context: Context,
    private val overrideDataStore: DataStore<Preferences>? = null
) {

    val dataStore: DataStore<Preferences>
        get() = overrideDataStore ?: context.dataStore

    // ========== Generic Preference Access ==========

    /**
     * Returns [key]'s value as a Flow, or [defaultValue] when unset or when the read fails
     * with an IOException.
     *
     * DataStore emits the whole preferences object on any write, so without
     * `distinctUntilChanged()` every observer would re-emit on unrelated writes.
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

    /** Returns [key]'s value as a Flow, or null when unset; otherwise as [getPreference]. */
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

    suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    suspend fun <T> removePreference(key: Preferences.Key<T>) {
        dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    /** Replaces [key]'s value with [transform] of the current one, atomically. */
    suspend fun <T> updatePreference(key: Preferences.Key<T>, transform: (T?) -> T) {
        dataStore.edit { preferences ->
            preferences[key] = transform(preferences[key])
        }
    }

    /** Applies several preference writes in one DataStore transaction and one disk write. */
    suspend fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { preferences -> block(preferences) }
    }

    val firstDayOfWeek: Flow<Int>
        get() = getPreference(PreferencesKeys.FIRST_DAY_OF_WEEK, FIRST_DAY_SYSTEM)

    suspend fun getFirstDayOfWeek(): Int = firstDayOfWeek.first()

    suspend fun setFirstDayOfWeek(day: Int) {
        setPreference(PreferencesKeys.FIRST_DAY_OF_WEEK, day)
    }

    val showWeekNumbers: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOW_WEEK_NUMBERS, false)

    suspend fun setShowWeekNumbers(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_WEEK_NUMBERS, show)
    }

    /** Whether the event form's tag row sits above the notes and attendees block. */
    val tagsAboveNotes: Flow<Boolean>
        get() = getPreference(PreferencesKeys.TAGS_ABOVE_NOTES, false)

    suspend fun setTagsAboveNotes(above: Boolean) {
        setPreference(PreferencesKeys.TAGS_ABOVE_NOTES, above)
    }

    val showDeclinedEvents: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOW_DECLINED_EVENTS, false)

    suspend fun getShowDeclinedEvents(): Boolean = showDeclinedEvents.first()

    suspend fun setShowDeclinedEvents(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_DECLINED_EVENTS, show)
    }

    /** Whether multi-day timed events are shown in the all-day strip instead of the timed grid. */
    val showMultiDayTimedInAllDayStrip: Flow<Boolean>
        get() = getPreference(
            PreferencesKeys.SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP,
            PreferencesKeys.DEFAULT_SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP
        )

    suspend fun setShowMultiDayTimedInAllDayStrip(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP, show)
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

    /**
     * Default calendar for new events from [PreferencesKeys.DEFAULT_CALENDAR] alone, or null
     * when unset or unparseable. [getDefaultCalendar] also reads the legacy key.
     */
    val defaultCalendar: Flow<DefaultCalendar?>
        get() = getOptionalPreference(PreferencesKeys.DEFAULT_CALENDAR)
            .map { value -> DefaultCalendar.parse(value) }

    /**
     * Returns the default calendar for new events, or null if none is set.
     *
     * A set [PreferencesKeys.DEFAULT_CALENDAR] wins, even when it doesn't parse (null). Only
     * when it is unset is the legacy [PreferencesKeys.DEFAULT_CALENDAR_ID] read, as a Room
     * calendar.
     */
    suspend fun getDefaultCalendar(): DefaultCalendar? {
        val newValue = dataStore.data.first()[PreferencesKeys.DEFAULT_CALENDAR]
        if (newValue != null) {
            return DefaultCalendar.parse(newValue)
        }

        val legacyId = dataStore.data.first()[PreferencesKeys.DEFAULT_CALENDAR_ID]
        return if (legacyId != null && legacyId >= 0) {
            DefaultCalendar.Room(legacyId)
        } else {
            null
        }
    }

    /** Stores [calendar] under [PreferencesKeys.DEFAULT_CALENDAR]; the legacy key is left as is. */
    suspend fun setDefaultCalendar(calendar: DefaultCalendar) {
        setPreference(PreferencesKeys.DEFAULT_CALENDAR, calendar.toStorageString())
    }

    /** Removes [PreferencesKeys.DEFAULT_CALENDAR]; a legacy id, if stored, then applies again. */
    suspend fun clearDefaultCalendar() {
        removePreference(PreferencesKeys.DEFAULT_CALENDAR)
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

    /** User's up-to-2-letter avatar initials; empty when unset (avatar shows its generic glyph). */
    val userInitials: Flow<String>
        get() = getPreference(PreferencesKeys.USER_INITIALS, "")

    suspend fun setUserInitials(initials: String) {
        setPreference(PreferencesKeys.USER_INITIALS, initials)
    }

    /** Stored color-source value ("dynamic" or "seed"), or null if the user never chose one. */
    val colorSource: Flow<String?>
        get() = getOptionalPreference(PreferencesKeys.COLOR_SOURCE)

    suspend fun setColorSource(value: String) {
        setPreference(PreferencesKeys.COLOR_SOURCE, value)
    }

    val accentSeed: Flow<Int>
        get() = getPreference(PreferencesKeys.ACCENT_SEED, ACCENT_SEED_DEFAULT)

    suspend fun setAccentSeed(seed: Int) {
        setPreference(PreferencesKeys.ACCENT_SEED, seed)
    }

    /**
     * Stored widget color-source value ("follow_app", "dynamic" or "seed"), or null if the user
     * never chose one; the widgets then mirror the app's colors
     * ([org.onekash.kashcal.widget.WidgetColorSource]).
     */
    val widgetColorSource: Flow<String?>
        get() = getOptionalPreference(PreferencesKeys.WIDGET_COLOR_SOURCE)

    suspend fun setWidgetColorSource(value: String) {
        setPreference(PreferencesKeys.WIDGET_COLOR_SOURCE, value)
    }

    /** Widget-only accent seed, apart from [accentSeed]; used when the widget source is "seed". */
    val widgetAccentSeed: Flow<Int>
        get() = getPreference(PreferencesKeys.WIDGET_ACCENT_SEED, ACCENT_SEED_DEFAULT)

    suspend fun setWidgetAccentSeed(seed: Int) {
        setPreference(PreferencesKeys.WIDGET_ACCENT_SEED, seed)
    }

    /**
     * Stored widget theme-source value ("follow_app", "light" or "dark"), or null if the user
     * never chose one; the widget then follows the app's face
     * ([org.onekash.kashcal.widget.WidgetThemeSource]). A legacy "system" value also falls back
     * to follow-app, which is the intended target.
     */
    val widgetThemeSource: Flow<String?>
        get() = getOptionalPreference(PreferencesKeys.WIDGET_THEME_SOURCE)

    suspend fun setWidgetThemeSource(value: String) {
        setPreference(PreferencesKeys.WIDGET_THEME_SOURCE, value)
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
        get() = getPreference(PreferencesKeys.QUICK_ADD_ENABLED, false)

    suspend fun setQuickAddEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.QUICK_ADD_ENABLED, enabled)
    }

    val titleSuggestionsEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.TITLE_SUGGESTIONS_ENABLED, true)

    suspend fun getTitleSuggestionsEnabled(): Boolean = titleSuggestionsEnabled.first()

    suspend fun setTitleSuggestionsEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.TITLE_SUGGESTIONS_ENABLED, enabled)
    }

    // ========== Privacy ==========

    /** Requires device biometric or screen lock on reopen (default false). */
    val appLockEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.APP_LOCK_ENABLED, false)

    suspend fun setAppLockEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.APP_LOCK_ENABLED, enabled)
    }

    // ========== Display Settings ==========

    /** Shows auto-detected emojis in event titles (default true). */
    val showEventEmojis: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOW_EVENT_EMOJIS, true)

    suspend fun setShowEventEmojis(show: Boolean) {
        setPreference(PreferencesKeys.SHOW_EVENT_EMOJIS, show)
    }

    /** Whether the Agenda view's top week bar is shown (default true); the last choice reopens. */
    val agendaWeekBarExpanded: Flow<Boolean>
        get() = getPreference(PreferencesKeys.AGENDA_WEEK_BAR_EXPANDED, true)

    suspend fun setAgendaWeekBarExpanded(expanded: Boolean) {
        setPreference(PreferencesKeys.AGENDA_WEEK_BAR_EXPANDED, expanded)
    }

    /**
     * Whether the Day view's top week-strip date picker is shown (default true). Stored apart
     * from [agendaWeekBarExpanded], so collapsing one leaves the other.
     */
    val dayWeekBarExpanded: Flow<Boolean>
        get() = getPreference(PreferencesKeys.DAY_WEEK_BAR_EXPANDED, true)

    suspend fun setDayWeekBarExpanded(expanded: Boolean) {
        setPreference(PreferencesKeys.DAY_WEEK_BAR_EXPANDED, expanded)
    }

    /** Whether the all-day strip is expanded; see [PreferencesKeys.ALL_DAY_ROWS_EXPANDED]. */
    val allDayRowsExpanded: Flow<Boolean>
        get() = getPreference(PreferencesKeys.ALL_DAY_ROWS_EXPANDED, false)

    suspend fun setAllDayRowsExpanded(expanded: Boolean) {
        setPreference(PreferencesKeys.ALL_DAY_ROWS_EXPANDED, expanded)
    }

    /**
     * Maximum events per day in the Agenda and Week widgets. Default 5; the setter stores any
     * value other than 3, 5, 8, 10 or 15 as 5.
     */
    val widgetMaxEventsPerDay: Flow<Int>
        get() = getPreference(PreferencesKeys.WIDGET_MAX_EVENTS_PER_DAY, 5)

    suspend fun setWidgetMaxEventsPerDay(count: Int) {
        val validOptions = setOf(3, 5, 8, 10, 15)
        val safeCount = if (count in validOptions) count else 5
        setPreference(PreferencesKeys.WIDGET_MAX_EVENTS_PER_DAY, safeCount)
    }

    /** Whether widget rows use the two-line style; see [PreferencesKeys.WIDGET_DETAILED_ROWS]. */
    val widgetDetailedRows: Flow<Boolean>
        get() = getPreference(PreferencesKeys.WIDGET_DETAILED_ROWS, false)

    suspend fun setWidgetDetailedRows(detailed: Boolean) {
        setPreference(PreferencesKeys.WIDGET_DETAILED_ROWS, detailed)
    }

    /**
     * Last time-grid scroll position as minutes from midnight (0..1439), or
     * [WEEK_VIEW_SCROLL_NOT_SAVED] when never saved; the grid then opens at its default hour.
     */
    val weekViewScrollMinutes: Flow<Int>
        get() = getPreference(PreferencesKeys.WEEK_VIEW_SCROLL_MINUTES, WEEK_VIEW_SCROLL_NOT_SAVED)

    suspend fun getWeekViewScrollMinutes(): Int = weekViewScrollMinutes.first()

    suspend fun setWeekViewScrollMinutes(minutesOfDay: Int) {
        // Clamp into the day so a bad input never stores the never-saved sentinel or a value
        // off the grid.
        val safe = minutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        setPreference(PreferencesKeys.WEEK_VIEW_SCROLL_MINUTES, safe)
    }

    /**
     * Pinch-to-zoom level of the time grid as hour-row height in dp, or
     * [DEFAULT_HOUR_HEIGHT_DP] when never saved. Not clamped here: the ViewModel clamps on
     * restore, so the pinch bounds have one source of truth in `WeekViewUtils`.
     */
    val weekViewHourHeight: Flow<Float>
        get() = getPreference(PreferencesKeys.WEEK_VIEW_HOUR_HEIGHT, DEFAULT_HOUR_HEIGHT_DP)

    suspend fun getWeekViewHourHeight(): Float = weekViewHourHeight.first()

    suspend fun setWeekViewHourHeight(hourHeightDp: Float) {
        setPreference(PreferencesKeys.WEEK_VIEW_HOUR_HEIGHT, hourHeightDp)
    }

    /**
     * Time format: "system" follows the device's 24-hour setting, "12h" shows 2:30 PM, "24h"
     * shows 14:30. The setter throws on any other value.
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
     * Default calendar view, one of the `VIEW_` values (default [VIEW_MONTH]). The setter throws
     * on any other value.
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

    val shownShareCardTooltip: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHOWN_SHARE_CARD_TOOLTIP, false)

    suspend fun setShownShareCardTooltip(shown: Boolean) {
        setPreference(PreferencesKeys.SHOWN_SHARE_CARD_TOOLTIP, shown)
    }

    val onboardingDismissed: Flow<Boolean>
        get() = getPreference(PreferencesKeys.ONBOARDING_DISMISSED, false)

    suspend fun setOnboardingDismissed(dismissed: Boolean) {
        setPreference(PreferencesKeys.ONBOARDING_DISMISSED, dismissed)
    }

    /**
     * True once the user tapped "No thanks" on the attendee picker's contacts-permission card
     * or denied the system dialog. It hides the banner for good: Android exposes no "user said
     * no for good" signal, so the app stores the decision. It gates only the banner; if
     * contacts are later granted in system settings, suggestions still work.
     */
    val contactSuggestionsDeclined: Flow<Boolean>
        get() = getPreference(PreferencesKeys.CONTACT_SUGGESTIONS_DECLINED, false)

    suspend fun setContactSuggestionsDeclined(declined: Boolean) {
        setPreference(PreferencesKeys.CONTACT_SUGGESTIONS_DECLINED, declined)
    }

    /**
     * True when contact sync lacks its permission: a background sync skipped because
     * WRITE_CONTACTS was revoked, or settings found the contacts permissions missing on
     * enable or sync-now. Drives an inline re-grant row in settings; cleared when either finds
     * the permission granted.
     */
    val contactSyncPermissionNeeded: Flow<Boolean>
        get() = getPreference(PreferencesKeys.CONTACT_SYNC_PERMISSION_NEEDED, false)

    suspend fun setContactSyncPermissionNeeded(needed: Boolean) {
        setPreference(PreferencesKeys.CONTACT_SYNC_PERMISSION_NEEDED, needed)
    }

    val lastWhatsNewVersionShown: Flow<Int>
        get() = getPreference(PreferencesKeys.LAST_WHATSNEW_VERSION_SHOWN, 0)

    suspend fun getLastWhatsNewVersionShown(): Int = lastWhatsNewVersionShown.first()

    suspend fun setLastWhatsNewVersionShown(version: Int) {
        setPreference(PreferencesKeys.LAST_WHATSNEW_VERSION_SHOWN, version)
    }

    // ========== Permission Tracking ==========

    /** Times the notification permission was denied; decides rationale vs permanently denied. */
    val notificationPermissionDeniedCount: Flow<Int>
        get() = getPreference(PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT, 0)

    /** Reads the denial count once, for the permission state check. */
    suspend fun getNotificationPermissionDeniedCountBlocking(): Int =
        notificationPermissionDeniedCount.first()

    /** Adds one denial; called when the user denies the permission. */
    suspend fun incrementNotificationPermissionDeniedCount() {
        updatePreference(PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT) { (it ?: 0) + 1 }
    }

    /** Zeroes the denial count; called when the permission is granted. */
    suspend fun resetNotificationPermissionDeniedCount() {
        setPreference(PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT, 0)
    }

    // ========== Contact Birthdays ==========

    val contactBirthdaysEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.CONTACT_BIRTHDAYS_ENABLED, false)

    suspend fun getContactBirthdaysEnabled(): Boolean = contactBirthdaysEnabled.first()

    suspend fun setContactBirthdaysEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.CONTACT_BIRTHDAYS_ENABLED, enabled)
    }

    /** Last sync time for contact birthdays, in epoch millis (0 = never). */
    val contactBirthdaysLastSync: Flow<Long>
        get() = getPreference(PreferencesKeys.CONTACT_BIRTHDAYS_LAST_SYNC, 0L)

    suspend fun getContactBirthdaysLastSync(): Long = contactBirthdaysLastSync.first()

    suspend fun setContactBirthdaysLastSync(timeMillis: Long) {
        setPreference(PreferencesKeys.CONTACT_BIRTHDAYS_LAST_SYNC, timeMillis)
    }

    /**
     * Birthday reminder in signed minutes before local midnight (negative = after), from the
     * `ALL_DAY_REMINDER_MINUTES` options: -540 = 9 AM the day of, 900 = 9 AM the day before.
     * Default -540.
     */
    val birthdayReminder: Flow<Int>
        get() = getPreference(PreferencesKeys.BIRTHDAY_REMINDER, DEFAULT_BIRTHDAY_REMINDER_MINUTES)

    suspend fun getBirthdayReminder(): Int = birthdayReminder.first()

    suspend fun setBirthdayReminder(minutes: Int) {
        setPreference(PreferencesKeys.BIRTHDAY_REMINDER, minutes)
    }

    // ========== Contact Anniversaries ==========

    val contactAnniversariesEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.CONTACT_ANNIVERSARIES_ENABLED, false)

    suspend fun getContactAnniversariesEnabled(): Boolean = contactAnniversariesEnabled.first()

    suspend fun setContactAnniversariesEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.CONTACT_ANNIVERSARIES_ENABLED, enabled)
    }

    /** Last sync time for contact anniversaries, in epoch millis (0 = never). */
    val contactAnniversariesLastSync: Flow<Long>
        get() = getPreference(PreferencesKeys.CONTACT_ANNIVERSARIES_LAST_SYNC, 0L)

    suspend fun getContactAnniversariesLastSync(): Long = contactAnniversariesLastSync.first()

    suspend fun setContactAnniversariesLastSync(timeMillis: Long) {
        setPreference(PreferencesKeys.CONTACT_ANNIVERSARIES_LAST_SYNC, timeMillis)
    }

    /** Anniversary reminder, in the units of [birthdayReminder]. Default -540 (9 AM the day of). */
    val anniversaryReminder: Flow<Int>
        get() = getPreference(PreferencesKeys.ANNIVERSARY_REMINDER, DEFAULT_ANNIVERSARY_REMINDER_MINUTES)

    suspend fun getAnniversaryReminder(): Int = anniversaryReminder.first()

    suspend fun setAnniversaryReminder(minutes: Int) {
        setPreference(PreferencesKeys.ANNIVERSARY_REMINDER, minutes)
    }

    // ========== Device Calendars ==========

    val deviceCalendarsEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.DEVICE_CALENDARS_ENABLED, false)

    suspend fun getDeviceCalendarsEnabled(): Boolean = deviceCalendarsEnabled.first()

    suspend fun setDeviceCalendarsEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.DEVICE_CALENDARS_ENABLED, enabled)
    }

    /**
     * Enabled device calendar ids. DataStore has no Long set, so they are stored as strings;
     * an entry that isn't a number is dropped on read.
     */
    val enabledDeviceCalendarIds: Flow<Set<Long>>
        get() = getPreference(PreferencesKeys.ENABLED_DEVICE_CALENDAR_IDS, emptySet<String>())
            .map { strings -> strings.mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun getEnabledDeviceCalendarIds(): Set<Long> = enabledDeviceCalendarIds.first()

    suspend fun setEnabledDeviceCalendarIds(ids: Set<Long>) {
        setPreference(PreferencesKeys.ENABLED_DEVICE_CALENDAR_IDS, ids.map { it.toString() }.toSet())
    }

    /**
     * Device calendar ids that stay enabled, reminders included, but are hidden from the
     * calendar view. Stored as strings like [enabledDeviceCalendarIds].
     */
    val hiddenDeviceCalendarIds: Flow<Set<Long>>
        get() = getPreference(PreferencesKeys.HIDDEN_DEVICE_CALENDAR_IDS, emptySet<String>())
            .map { strings -> strings.mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun getHiddenDeviceCalendarIds(): Set<Long> = hiddenDeviceCalendarIds.first()

    suspend fun setHiddenDeviceCalendarIds(ids: Set<Long>) {
        setPreference(PreferencesKeys.HIDDEN_DEVICE_CALENDAR_IDS, ids.map { it.toString() }.toSet())
    }

    /** Hides [calendarId] if visible, shows it if hidden. */
    suspend fun toggleDeviceCalendarHidden(calendarId: Long) {
        val current = getHiddenDeviceCalendarIds().toMutableSet()
        if (calendarId in current) current.remove(calendarId) else current.add(calendarId)
        setHiddenDeviceCalendarIds(current)
    }

    /**
     * Unhides [calendarId]. Called when a device calendar is disabled, so it comes back visible
     * if re-enabled.
     */
    suspend fun removeFromHiddenDeviceCalendarIds(calendarId: Long) {
        val current = getHiddenDeviceCalendarIds().toMutableSet()
        if (current.remove(calendarId)) {
            setHiddenDeviceCalendarIds(current)
        }
    }

    /**
     * Whether KashCal fires reminders for device calendar events. Default true: users expect
     * reminders when they add device calendars to KashCal.
     */
    val deviceCalendarRemindersEnabled: Flow<Boolean>
        get() = getPreference(PreferencesKeys.DEVICE_CALENDAR_REMINDERS_ENABLED, true)

    suspend fun getDeviceCalendarRemindersEnabled(): Boolean = deviceCalendarRemindersEnabled.first()

    suspend fun setDeviceCalendarRemindersEnabled(enabled: Boolean) {
        setPreference(PreferencesKeys.DEVICE_CALENDAR_REMINDERS_ENABLED, enabled)
    }

    // ========== Parse Failure Retry (v16.7.0) ==========

    /** Parse failure retry counts, calendarId to count. */
    val parseFailureRetryCount: Flow<Map<Long, Int>>
        get() = getPreference(PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS, "")
            .map { json -> parseRetryCountsJson(json) }

    /** Returns [calendarId]'s retry count, or 0 when it has none. */
    suspend fun getParseFailureRetryCount(calendarId: Long): Int {
        val json = dataStore.data.first()[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS].orEmpty()
        return parseRetryCountsJson(json)[calendarId] ?: 0
    }

    /** Adds one to [calendarId]'s retry count and returns the new count. */
    suspend fun incrementParseFailureRetry(calendarId: Long): Int {
        var newCount = 0
        dataStore.edit { preferences ->
            val json = preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS].orEmpty()
            val counts = parseRetryCountsJson(json).toMutableMap()
            newCount = (counts[calendarId] ?: 0) + 1
            counts[calendarId] = newCount
            preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] = serializeRetryCountsJson(counts)
        }
        return newCount
    }

    /**
     * Drops [calendarId]'s retry count. The pull calls it when it advances the sync-token: with
     * no parse errors, or after giving up at the maximum retries.
     */
    suspend fun resetParseFailureRetry(calendarId: Long) {
        dataStore.edit { preferences ->
            val json = preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS].orEmpty()
            val counts = parseRetryCountsJson(json).toMutableMap()
            counts.remove(calendarId)
            if (counts.isEmpty()) {
                preferences.remove(PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS)
            } else {
                preferences[PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS] = serializeRetryCountsJson(counts)
            }
        }
    }

    /** Drops every retry count; a forced full sync calls it for a fresh start. */
    suspend fun clearAllParseFailureRetries() {
        removePreference(PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS)
    }

    /**
     * Parses "calendarId:count,calendarId:count" (not JSON, despite the name), which needs no
     * JSON library. Skips entries without a colon; returns an empty map if any id or count
     * doesn't parse.
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
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Writes [counts] in the format [parseRetryCountsJson] reads. */
    private fun serializeRetryCountsJson(counts: Map<Long, Int>): String {
        return counts.entries.joinToString(",") { "${it.key}:${it.value}" }
    }

    // ========== Reminder Migration ==========

    /** Returns the reminder migration version, or 0 if none has been applied. */
    suspend fun getReminderMigrationVersion(): Int {
        return dataStore.data.first()[PreferencesKeys.REMINDER_MIGRATION_VERSION] ?: 0
    }

    /** Records [version]; called after that migration has been applied. */
    suspend fun setReminderMigrationVersion(version: Int) {
        setPreference(PreferencesKeys.REMINDER_MIGRATION_VERSION, version)
    }

    // ========== Parser Version (v20.12.39) ==========

    /** Returns the stored parser version, or 0 if never set (installs before v20.12.39). */
    suspend fun getParserVersion(): Int {
        return dataStore.data.first()[PreferencesKeys.PARSER_VERSION] ?: 0
    }

    /** Records [version]; called after the etags have been cleared. */
    suspend fun setParserVersion(version: Int) {
        setPreference(PreferencesKeys.PARSER_VERSION, version)
    }

    // ========== iCloud URL Migration ==========

    /** Whether the iCloud URL migration has completed. */
    val icloudUrlMigrationCompleted: Flow<Boolean>
        get() = getPreference(PreferencesKeys.ICLOUD_URL_MIGRATION_COMPLETED, false)

    suspend fun getICloudUrlMigrationCompleted(): Boolean = icloudUrlMigrationCompleted.first()

    suspend fun setICloudUrlMigrationCompleted(completed: Boolean) {
        setPreference(PreferencesKeys.ICLOUD_URL_MIGRATION_COMPLETED, completed)
    }

    /** Clears the iCloud URL migration flag so the next sync reruns it (debugging and tests). */
    suspend fun resetICloudUrlMigration() {
        setICloudUrlMigrationCompleted(false)
    }

    // ========== Share Availability ==========

    /**
     * Days in the share-availability summary (default 7, valid 1..14); the setter stores an
     * out-of-range value as the default.
     */
    val shareAvailabilityDays: Flow<Int>
        get() = getPreference(PreferencesKeys.SHARE_AVAILABILITY_DAYS, SHARE_AVAILABILITY_DEFAULT_DAYS)

    suspend fun setShareAvailabilityDays(days: Int) {
        setPreference(PreferencesKeys.SHARE_AVAILABILITY_DAYS, sanitizeShareAvailabilityDays(days))
    }

    /**
     * Working-hours window start in minutes from midnight (default 540, 09:00; valid 0..1439).
     * The setter checks it against the stored end with [sanitizeWorkStartMin].
     */
    val shareAvailabilityWorkStartMinutes: Flow<Int>
        get() = getPreference(
            PreferencesKeys.SHARE_AVAILABILITY_WORK_START_MIN,
            SHARE_AVAILABILITY_DEFAULT_WORK_START_MIN
        )

    suspend fun setShareAvailabilityWorkStartMinutes(minutes: Int) {
        val safe = sanitizeWorkStartMin(
            minutes,
            currentEnd = shareAvailabilityWorkEndMinutes.first()
        )
        setPreference(PreferencesKeys.SHARE_AVAILABILITY_WORK_START_MIN, safe)
    }

    /**
     * Working-hours window end in minutes from midnight (default 1020, 17:00; valid 1..1440,
     * 1440 = end of day). The setter checks it against the stored start with
     * [sanitizeWorkEndMin].
     */
    val shareAvailabilityWorkEndMinutes: Flow<Int>
        get() = getPreference(
            PreferencesKeys.SHARE_AVAILABILITY_WORK_END_MIN,
            SHARE_AVAILABILITY_DEFAULT_WORK_END_MIN
        )

    suspend fun setShareAvailabilityWorkEndMinutes(minutes: Int) {
        val safe = sanitizeWorkEndMin(
            minutes,
            currentStart = shareAvailabilityWorkStartMinutes.first()
        )
        setPreference(PreferencesKeys.SHARE_AVAILABILITY_WORK_END_MIN, safe)
    }

    /** Treats all-day events as busy when computing free blocks (default false: ignored). */
    val shareAvailabilityIncludeAllDay: Flow<Boolean>
        get() = getPreference(PreferencesKeys.SHARE_AVAILABILITY_INCLUDE_ALL_DAY, false)

    suspend fun setShareAvailabilityIncludeAllDay(include: Boolean) {
        setPreference(PreferencesKeys.SHARE_AVAILABILITY_INCLUDE_ALL_DAY, include)
    }

    companion object {
        const val REMINDER_OFF = -1  // no reminder set
        const val DEFAULT_REMINDER_MINUTES = 15
        // Signed minutes before start (the Android CalendarProvider convention): positive =
        // before, negative = after.
        const val DEFAULT_ALL_DAY_REMINDER_MINUTES = 15 * 60 // 9 AM the day before (-PT15H)
        const val DEFAULT_BIRTHDAY_REMINDER_MINUTES = -9 * 60 // 9 AM the day of (PT9H)
        const val DEFAULT_ANNIVERSARY_REMINDER_MINUTES = -9 * 60 // 9 AM the day of (PT9H)

        const val DEFAULT_SYNC_INTERVAL_MINUTES = 60
        const val DEFAULT_SYNC_INTERVAL_MS = 1L * 60 * 60 * 1000
        const val MIN_SYNC_INTERVAL_MS = 15L * 60 * 1000
        const val DEFAULT_SYNC_PAST_DAYS = 365
        const val DEFAULT_SYNC_FUTURE_DAYS = 365

        const val DEFAULT_EVENT_DURATION_MINUTES = 30

        const val WEEK_VIEW_SCROLL_NOT_SAVED = -1  // no position saved yet
        const val MINUTES_PER_DAY = 24 * 60

        // Default hour-row height in dp; must match `WeekViewUtils.HOUR_HEIGHT`.
        const val DEFAULT_HOUR_HEIGHT_DP = 60f

        const val SHARE_AVAILABILITY_DEFAULT_DAYS = 7
        const val SHARE_AVAILABILITY_DEFAULT_WORK_START_MIN = 9 * 60 // 09:00
        const val SHARE_AVAILABILITY_DEFAULT_WORK_END_MIN = 17 * 60 // 17:00
        const val SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN = 60
        const val SHARE_AVAILABILITY_MAX_DAYS = 14
        const val SHARE_AVAILABILITY_MAX_MINUTES = 1440 // end of day

        /**
         * Returns [days] if in 1..[SHARE_AVAILABILITY_MAX_DAYS], else the default. Shared by the
         * setter and the settings backup importer, so a malformed backup can't store an
         * out-of-range value.
         */
        fun sanitizeShareAvailabilityDays(days: Int): Int =
            if (days in 1..SHARE_AVAILABILITY_MAX_DAYS) days else SHARE_AVAILABILITY_DEFAULT_DAYS

        /**
         * Returns [minutes] as the window start, or the default start when it is outside
         * 0..1439 or leaves under [SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN] before [currentEnd].
         * An out-of-range [currentEnd] is read as the default end.
         */
        fun sanitizeWorkStartMin(minutes: Int, currentEnd: Int): Int {
            if (minutes !in 0 until SHARE_AVAILABILITY_MAX_MINUTES) {
                return SHARE_AVAILABILITY_DEFAULT_WORK_START_MIN
            }
            val end = if (currentEnd in (SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN)..SHARE_AVAILABILITY_MAX_MINUTES) {
                currentEnd
            } else {
                SHARE_AVAILABILITY_DEFAULT_WORK_END_MIN
            }
            return if (end - minutes >= SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN) {
                minutes
            } else {
                SHARE_AVAILABILITY_DEFAULT_WORK_START_MIN
            }
        }

        /**
         * Returns [minutes] as the window end, or the default end when it is outside 1..1440
         * (1440 = end of day) or leaves under [SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN] after
         * [currentStart]. An out-of-range [currentStart] is read as the default start.
         */
        fun sanitizeWorkEndMin(minutes: Int, currentStart: Int): Int {
            if (minutes !in 1..SHARE_AVAILABILITY_MAX_MINUTES) {
                return SHARE_AVAILABILITY_DEFAULT_WORK_END_MIN
            }
            val start = if (currentStart in 0 until SHARE_AVAILABILITY_MAX_MINUTES) {
                currentStart
            } else {
                SHARE_AVAILABILITY_DEFAULT_WORK_START_MIN
            }
            return if (minutes - start >= SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN) {
                minutes
            } else {
                SHARE_AVAILABILITY_DEFAULT_WORK_END_MIN
            }
        }

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        // Retired theme option, kept only to migrate its users onto a seed accent.
        const val THEME_TEAL = "teal"

        /** Default accent seed: brand teal as a packed ARGB int. */
        const val ACCENT_SEED_DEFAULT: Int = 0xFF0E6E62.toInt()

        const val VIEW_MONTH = "month"
        const val VIEW_AGENDA = "agenda"
        const val VIEW_DAY = "day"
        const val VIEW_THREE_DAYS = "three_days"
        const val VIEW_MONTH_FULL = "month_full"
        const val VIEW_WEEK = "week"
        const val VIEW_YEAR = "year"

        private val VALID_VIEWS = setOf(VIEW_MONTH, VIEW_AGENDA, VIEW_DAY, VIEW_THREE_DAYS, VIEW_WEEK, VIEW_MONTH_FULL, VIEW_YEAR)

        const val TIME_FORMAT_SYSTEM = "system"
        const val TIME_FORMAT_12H = "12h"
        const val TIME_FORMAT_24H = "24h"

        /** First-day-of-week value that follows the system locale. */
        const val FIRST_DAY_SYSTEM = 0

        /**
         * Current iCalendar parser version. Bump it when iCalendar parsing logic changes: a bump
         * clears every event etag on the next app start, so the next sync re-parses all events.
         *
         * Versions:
         * - v0: before v20.12.39, no version tracking
         * - v1: VALUE=DATE dates in UTC, not the local zone
         * - v2: Windows timezone names resolved (#45)
         * - v3: a local VTIMEZONE with a non-IANA TZID keeps its events (#346): a resolvable
         *   X-LIC-LOCATION zone is used, the rest fall back to floating instead of being lost
         */
        const val CURRENT_PARSER_VERSION = 3
    }
}
