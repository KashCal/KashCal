package org.onekash.kashcal.ui.shared

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Shared constants for forms and settings across the app.
 * UI-layer options for reminder pickers, sync interval selectors, etc.
 *
 * Data-layer constants (sentinel values, defaults) live in [KashCalDataStore.Companion].
 * This file provides UI options with human-readable labels.
 */

/** Sentinel value indicating no reminder is set. References [KashCalDataStore.REMINDER_OFF]. */
const val REMINDER_OFF = KashCalDataStore.REMINDER_OFF

/**
 * Represents a reminder option with display label and minutes before event.
 *
 * @property label Human-readable description (e.g., "15 minutes before")
 * @property minutes Minutes before event to trigger reminder. Use [REMINDER_OFF] for no reminder.
 */
data class ReminderOption(
    val label: String,
    val minutes: Int
)

/**
 * Reminder options for timed events (meetings, appointments with specific start times).
 * These options make sense when the event has a precise start time.
 *
 * Note: Legacy values (0, 5, 10, 120) are still valid for existing events
 * from external calendars or older app versions - they just aren't shown in the picker.
 */
val TIMED_REMINDER_MINUTES = listOf(REMINDER_OFF, 0, 5, 15, 30, 60, 240, 1440, 10080)

fun getTimedReminderOptions(resources: Resources): List<ReminderOption> =
    TIMED_REMINDER_MINUTES.map { minutes ->
        ReminderOption(formatReminderOption(minutes, isAllDay = false, resources = resources), minutes)
    }

/**
 * Reminder options for all-day events (birthdays, holidays, deadlines).
 * These options are relative to 9 AM on the event day or days before.
 *
 * Note: Legacy value (2880 - 2 days before) is still valid for existing events
 * from external calendars or older app versions - it just isn't shown in the picker.
 *
 * Labels are terse and time-independent ("Day of event", "1 day before"); the
 * 9 AM fire time is conveyed by the picker sheet's hint.
 */
// Signed "minutes before start" (Android CalendarProvider convention): positive = before midnight,
// negative = after midnight. 9AM-day-of fires after midnight so it is negative (-540).
// 1d/2d/1w fire 9 AM on the prior day(s) = hour-based before-offsets (DST-stable).
val ALL_DAY_REMINDER_MINUTES = listOf(REMINDER_OFF, -540, 900, 2340, 9540)

fun getAllDayReminderOptions(resources: Resources): List<ReminderOption> =
    ALL_DAY_REMINDER_MINUTES.map { minutes ->
        ReminderOption(formatReminderOption(minutes, isAllDay = true, resources = resources), minutes)
    }

/**
 * Format reminder option for display.
 * Handles both current and legacy values (grandfathered from older versions),
 * plus arbitrary values from external calendars.
 *
 * @param minutes Reminder minutes value
 * @param isAllDay Whether the event is all-day
 * @return Human-readable label for the reminder
 */
fun formatReminderOption(minutes: Int, isAllDay: Boolean, resources: Resources): String {
    // All-day reminders use signed "minutes before midnight": negative = after the
    // event's local midnight (e.g. -540 = 9 AM day of), positive = before.
    if (isAllDay) {
        // The 9 AM fire time is conveyed by the sheet hint, so labels are terse and
        // format-independent.
        when (minutes) {
            -540 -> return resources.getString(R.string.reminder_day_of_event)
            900 -> return resources.getQuantityString(R.plurals.reminder_days_before, 1, 1)
            2340 -> return resources.getQuantityString(R.plurals.reminder_days_before, 2, 2)
            9540 -> return resources.getQuantityString(R.plurals.reminder_weeks_before, 1, 1)
        }
    }
    return when (minutes) {
        REMINDER_OFF -> resources.getString(R.string.reminder_none)
        0 -> resources.getString(R.string.reminder_at_time_of_event)
        // 540 is a timed "9 hours before" value. (Under the signed all-day model
        // "9 AM day of event" is -540, handled in the isAllDay block above; a legacy
        // all-day 540 falls here and renders "9 hours before", matching its fire time.)
        540 -> resources.getQuantityString(R.plurals.reminder_hours_before, 9, 9)
        else -> when {
            minutes >= 10080 && minutes % 10080 == 0 -> {
                val weeks = minutes / 10080
                resources.getQuantityString(R.plurals.reminder_weeks_before, weeks, weeks)
            }
            minutes >= 1440 && minutes % 1440 == 0 -> {
                val days = minutes / 1440
                resources.getQuantityString(R.plurals.reminder_days_before, days, days)
            }
            minutes >= 60 && minutes % 60 == 0 -> {
                val hours = minutes / 60
                resources.getQuantityString(R.plurals.reminder_hours_before, hours, hours)
            }
            else -> {
                resources.getQuantityString(R.plurals.reminder_minutes_before, minutes, minutes)
            }
        }
    }
}

/**
 * Format reminder as short label for summary display.
 * Handles both current and legacy values, plus arbitrary values from external calendars.
 *
 * @param minutes Reminder minutes value
 * @param use24Hour Whether to use 24-hour format for the 9 AM option (default: false)
 * @param isAllDay Whether this is an all-day reminder (changes how signed offsets are labelled)
 * @return Short label (e.g., "15m", "1h", "1d", "09:00" or "9AM")
 */
fun formatReminderShort(minutes: Int, use24Hour: Boolean = false, isAllDay: Boolean = false, resources: Resources): String {
    // All-day signed offsets: -540 = 9 AM day of; 900/2340/9540 = 9 AM N days before.
    // Gated on isAllDay so timed reminders (e.g. 900 = a 15h-before alarm) are unaffected.
    if (isAllDay && minutes != REMINDER_OFF) {
        when (minutes) {
            -540 -> return resources.getString(if (use24Hour) R.string.reminder_short_9am_24h else R.string.reminder_short_9am_12h)
            900 -> return resources.getString(R.string.reminder_short_days, 1)
            2340 -> return resources.getString(R.string.reminder_short_days, 2)
            9540 -> return resources.getString(R.string.reminder_short_weeks, 1)
            // Any other all-day offset (e.g. a synced after-start alarm) renders by
            // magnitude so it never shows a negative "-540m"; sign meaning is conveyed
            // by the firing time, not this compact summary chip.
            else -> return reminderShortByMagnitude(kotlin.math.abs(minutes), resources)
        }
    }
    return when (minutes) {
        REMINDER_OFF -> resources.getString(R.string.reminder_short_off)
        0 -> resources.getString(R.string.reminder_short_at_event)
        540 -> resources.getString(if (use24Hour) R.string.reminder_short_9am_24h else R.string.reminder_short_9am_12h)
        else -> reminderShortByMagnitude(minutes, resources)
    }
}

private fun reminderShortByMagnitude(minutes: Int, resources: Resources): String = when {
    minutes >= 10080 && minutes % 10080 == 0 -> resources.getString(R.string.reminder_short_weeks, minutes / 10080)
    minutes >= 1440 && minutes % 1440 == 0 -> resources.getString(R.string.reminder_short_days, minutes / 1440)
    minutes >= 60 && minutes % 60 == 0 -> resources.getString(R.string.reminder_short_hours, minutes / 60)
    else -> resources.getString(R.string.reminder_short_minutes, minutes)
}

/**
 * Medium-length reminder/duration label for Settings row values: abbreviated words
 * ("30 min", "1 hr", "1 day"/"2 days", "1 wk") — longer than the compact chip form
 * ([formatReminderShort]) used in the event form, shorter than the sheet's full
 * "N minutes before" phrasing.
 *
 * All-day 9 AM offsets map to their day/week meaning (900 -> "1 day"), matching the
 * picker labels; the day-of case ([REMINDER_OFF] aside) reads "Day of" (the 9 AM
 * fire time is conveyed by the sheet hint, not the row). Mirrors the explicit
 * offset handling in [formatReminderShort] so an all-day value never renders by its
 * raw magnitude (e.g. 900 must not read "15 hr").
 */
fun formatReminderMedium(minutes: Int, isAllDay: Boolean, resources: Resources): String {
    if (isAllDay && minutes != REMINDER_OFF) {
        return when (minutes) {
            -540 -> resources.getString(R.string.reminder_med_day_of)
            900 -> resources.getQuantityString(R.plurals.reminder_med_days, 1, 1)
            2340 -> resources.getQuantityString(R.plurals.reminder_med_days, 2, 2)
            9540 -> resources.getString(R.string.reminder_med_weeks, 1)
            else -> reminderMediumByMagnitude(kotlin.math.abs(minutes), resources)
        }
    }
    return when (minutes) {
        REMINDER_OFF -> resources.getString(R.string.reminder_short_off)
        0 -> resources.getString(R.string.reminder_short_at_event)
        else -> reminderMediumByMagnitude(minutes, resources)
    }
}

private fun reminderMediumByMagnitude(minutes: Int, resources: Resources): String = when {
    minutes >= 10080 && minutes % 10080 == 0 -> resources.getString(R.string.reminder_med_weeks, minutes / 10080)
    minutes >= 1440 && minutes % 1440 == 0 -> resources.getQuantityString(R.plurals.reminder_med_days, minutes / 1440, minutes / 1440)
    minutes >= 60 && minutes % 60 == 0 -> resources.getString(R.string.reminder_med_hours, minutes / 60)
    else -> resources.getString(R.string.reminder_med_minutes, minutes)
}

// ==================== Custom Reminders: Duration Helpers ====================

/** Maximum number of reminders per event. */
const val MAX_REMINDERS = 5

/**
 * Represents a quick preset chip for the duration wheel picker.
 *
 * @property label Short display label (e.g., "15m", "1h")
 * @property minutes Duration in minutes
 */
data class PresetChip(
    val label: String,
    val minutes: Int
)

/** Quick preset chips for timed events: 15m, 30m, 1h, 1d */
val TIMED_PRESET_CHIPS = listOf(
    PresetChip("15m", 15),
    PresetChip("30m", 30),
    PresetChip("1h", 60),
    PresetChip("1d", 1440)
)

/**
 * Quick preset chips for all-day events. Stored values are signed "minutes before
 * the event's local midnight" (positive = before, negative = after):
 * - 9AM day of   = -540  ("PT9H", fires 9 AM on the event day, after midnight)
 * - 1d before    =  900  ("-PT15H", fires 9 AM the day before)
 * - 2d before    = 2340  ("-PT39H", fires 9 AM two days before)
 * - 1w before    = 9540  ("-PT159H", fires 9 AM a week before)
 */
val ALL_DAY_PRESET_CHIPS = listOf(
    PresetChip("9AM", -540),
    PresetChip("1d", 900),
    PresetChip("2d", 2340),
    PresetChip("1w", 9540)
)

/**
 * Convert total minutes to (days, hours, minutes) components.
 *
 * @param totalMinutes Total duration in minutes (non-negative)
 * @return Triple of (days, hours, minutes)
 */
fun minutesToComponents(totalMinutes: Int): Triple<Int, Int, Int> {
    val abs = kotlin.math.abs(totalMinutes)
    val days = abs / 1440
    val remaining = abs % 1440
    val hours = remaining / 60
    val minutes = remaining % 60
    return Triple(days, hours, minutes)
}

/**
 * Convert (days, hours, minutes) components to total minutes.
 *
 * @return Total minutes
 */
fun componentsToMinutes(days: Int, hours: Int, minutes: Int): Int =
    days * 1440 + hours * 60 + minutes

/**
 * Round minutes to nearest wheel step (default 5).
 * Used to snap odd-minute values from server to the nearest wheel position.
 *
 * @param minutes Raw minute value
 * @param step Wheel step size (default 5)
 * @return Rounded minute value
 */
fun roundToWheelStep(minutes: Int, step: Int = 5): Int =
    ((minutes + step / 2) / step) * step

/**
 * Format a reminder duration for human-readable display.
 *
 * For timed events: "15 minutes before", "1 hour 30 min before", "At time of event"
 * For all-day events: contextual labels like "9 AM day of event", "1 day before at 9 AM"
 *
 * @param minutes Duration in minutes before the event
 * @param isAllDay Whether the event is all-day
 * @param use24Hour Whether to use 24-hour format for time labels
 * @return Human-readable duration string
 */
fun formatReminderDuration(minutes: Int, isAllDay: Boolean, use24Hour: Boolean, resources: Resources): String {
    if (minutes == 0 && !isAllDay) return resources.getString(R.string.reminder_at_time_of_event)

    if (isAllDay) {
        // Signed offsets: -540 = 9 AM day of; 900/2340/9540 = 9 AM, N days before.
        when (minutes) {
            -540 -> return resources.getString(
                if (use24Hour) R.string.reminder_day_of_event_24h else R.string.reminder_day_of_event_12h
            )
            900, 2340, 9540 -> {
                val days = when (minutes) { 900 -> 1; 2340 -> 2; else -> 7 }
                val dayStr = resources.getQuantityString(R.plurals.time_days, days, days)
                return resources.getString(
                    if (use24Hour) R.string.reminder_before_at_24h else R.string.reminder_before_at_12h, dayStr
                )
            }
        }
    }

    return buildGenericDuration(minutes, resources)
}

private fun buildGenericDuration(minutes: Int, resources: Resources): String {
    val (days, hours, mins) = minutesToComponents(minutes)
    val parts = mutableListOf<String>()
    if (days > 0) parts.add(resources.getQuantityString(R.plurals.time_days, days, days))
    if (hours > 0) parts.add(resources.getQuantityString(R.plurals.time_hours, hours, hours))
    if (mins > 0) {
        val minLabel = if (days == 0 && hours == 0) {
            resources.getQuantityString(R.plurals.time_minutes, mins, mins)
        } else {
            resources.getQuantityString(R.plurals.time_minutes_short, mins, mins)
        }
        parts.add(minLabel)
    }
    if (parts.isEmpty()) return resources.getString(R.string.reminder_at_time_of_event)
    return resources.getString(R.string.reminder_time_before, parts.joinToString(" "))
}

/**
 * Deduplicate and sort reminders by ascending duration.
 *
 * @param reminders List of reminder durations in minutes
 * @return Deduplicated, sorted list
 */
fun deduplicateAndSortReminders(reminders: List<Int>): List<Int> =
    reminders.distinct().sorted()

/**
 * Format a list of reminder durations as a comma-separated summary.
 * Used for the collapsed Alerts section header.
 *
 * @param reminderMinutes List of reminder durations in minutes
 * @param use24Hour Whether to use 24-hour format
 * @return Summary string like "15m, 1h, 1d" or "None"
 */
fun formatReminderSummary(reminderMinutes: List<Int>, use24Hour: Boolean, resources: Resources, isAllDay: Boolean = false): String {
    if (reminderMinutes.isEmpty()) return resources.getString(R.string.reminder_summary_none)
    return reminderMinutes.joinToString(", ") { formatReminderShort(it, use24Hour, isAllDay, resources) }
}

/**
 * Represents a duration option with display label and minutes.
 *
 * @property label Human-readable description (e.g., "30 minutes")
 * @property minutes Duration in minutes
 */
data class DurationOption(
    val label: String,
    val minutes: Int
)

/**
 * Default event duration options for new events.
 * Used in Settings to configure how long new events should be by default.
 */
val EVENT_DURATION_MINUTES = listOf(15, 30, 60, 120)

fun getEventDurationOptions(resources: Resources): List<DurationOption> =
    EVENT_DURATION_MINUTES.map { DurationOption(formatDuration(it, resources), it) }

/**
 * Format duration for display.
 *
 * @param minutes Duration in minutes
 * @return Human-readable label (e.g., "30 minutes", "1 hour")
 */
fun formatDuration(minutes: Int, resources: Resources): String {
    return when {
        minutes < 60 -> resources.getQuantityString(R.plurals.time_minutes, minutes, minutes)
        minutes % 60 == 0 -> {
            val hours = minutes / 60
            resources.getQuantityString(R.plurals.time_hours, hours, hours)
        }
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            resources.getString(R.string.duration_compact, hours, mins)
        }
    }
}

fun formatDurationShort(minutes: Int, resources: Resources): String {
    return when {
        minutes < 60 -> resources.getString(R.string.reminder_short_minutes, minutes)
        minutes % 60 == 0 -> resources.getString(R.string.reminder_short_hours, minutes / 60)
        else -> resources.getString(R.string.duration_compact_short, minutes / 60, minutes % 60)
    }
}

/**
 * Represents a sync frequency option.
 *
 * @property label Human-readable description (e.g., "1 hour")
 * @property intervalMs Sync interval in milliseconds. Use [Long.MAX_VALUE] for manual only.
 */
data class SyncOption(
    val label: String,
    val intervalMs: Long
)

/**
 * Available sync frequency options for background calendar sync.
 */
val SYNC_INTERVALS_MS = listOf(
    15 * 60 * 1000L,
    30 * 60 * 1000L,
    1 * 60 * 60 * 1000L,
    6 * 60 * 60 * 1000L,
    12 * 60 * 60 * 1000L,
    24 * 60 * 60 * 1000L,
    Long.MAX_VALUE
)

fun getSyncOptions(resources: Resources): List<SyncOption> =
    SYNC_INTERVALS_MS.map { intervalMs ->
        val label = if (intervalMs == Long.MAX_VALUE) {
            resources.getString(R.string.sync_manual_only)
        } else {
            val minutes = (intervalMs / (60 * 1000L)).toInt()
            formatDuration(minutes, resources)
        }
        SyncOption(label, intervalMs)
    }

/**
 * Represents a sync lookback option with display label and days.
 *
 * @property label Human-readable description (e.g., "3 months")
 * @property days Number of days to look back. Use [Int.MAX_VALUE] for all events.
 */
data class SyncLookbackOption(
    val label: String,
    val days: Int
)

/**
 * Available sync lookback options for how far back to sync calendar events.
 */
val SYNC_LOOKBACK_DAYS = listOf(90, 180, 365, 730, 1825, Int.MAX_VALUE)

fun getSyncLookbackOptions(resources: Resources): List<SyncLookbackOption> =
    SYNC_LOOKBACK_DAYS.map { SyncLookbackOption(formatSyncLookback(it, resources), it) }

/**
 * Format sync lookback days for display.
 * Handles both known options and arbitrary values.
 *
 * @param days Sync lookback in days
 * @return Human-readable label
 */
fun formatSyncLookback(days: Int, resources: Resources): String {
    if (days == Int.MAX_VALUE) return resources.getString(R.string.sync_lookback_all)
    return when {
        days >= 365 && days % 365 == 0 -> {
            val years = days / 365
            resources.getQuantityString(R.plurals.time_years, years, years)
        }
        days >= 30 && days % 30 == 0 -> {
            val months = days / 30
            resources.getQuantityString(R.plurals.time_months, months, months)
        }
        days >= 7 && days % 7 == 0 -> {
            val weeks = days / 7
            resources.getQuantityString(R.plurals.time_weeks, weeks, weeks)
        }
        else -> resources.getQuantityString(R.plurals.time_days, days, days)
    }
}

/**
 * Mask an email address for privacy display.
 * Shows first character, masked middle, and domain.
 *
 * Examples:
 * - "john@icloud.com" -> "j***@icloud.com"
 * - "a@b.com" -> "a@b.com" (short prefix unchanged)
 * - null -> "" (null-safe)
 *
 * @param email The email to mask, or null
 * @return Masked email string, or empty string if null
 */
fun maskEmail(email: String?): String {
    if (email == null) return ""
    val atIndex = email.indexOf('@')
    return if (atIndex > 1) {
        "${email.first()}***${email.substring(atIndex)}"
    } else {
        email
    }
}
