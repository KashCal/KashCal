package org.onekash.kashcal.ui.shared

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
val TIMED_REMINDER_OPTIONS = listOf(
    ReminderOption("No reminder", REMINDER_OFF),
    ReminderOption("At time of event", 0),
    ReminderOption("5 minutes before", 5),
    ReminderOption("15 minutes before", 15),
    ReminderOption("30 minutes before", 30),
    ReminderOption("1 hour before", 60),
    ReminderOption("4 hours before", 240),
    ReminderOption("1 day before", 1440),
    ReminderOption("1 week before", 10080)
)

/**
 * Reminder options for all-day events (birthdays, holidays, deadlines).
 * These options are relative to 9 AM on the event day or days before.
 *
 * Note: Legacy value (2880 - 2 days before) is still valid for existing events
 * from external calendars or older app versions - it just isn't shown in the picker.
 *
 * For time-format-aware labels, use [getAllDayReminderOptions] instead.
 */
val ALL_DAY_REMINDER_OPTIONS = listOf(
    ReminderOption("No reminder", REMINDER_OFF),
    ReminderOption("9 AM day of event", 540),
    ReminderOption("12 hours before", 720),
    ReminderOption("1 day before", 1440),
    ReminderOption("1 week before", 10080)
)

/**
 * Returns all-day reminder options with time-format-aware labels.
 *
 * The 540-minute option ("9 AM day of event") label changes based on [use24Hour]:
 * - 24-hour format: "09:00 day of event"
 * - 12-hour format: "9 AM day of event"
 *
 * @param use24Hour Whether to use 24-hour time format
 * @return List of [ReminderOption] with appropriate labels
 */
fun getAllDayReminderOptions(use24Hour: Boolean): List<ReminderOption> =
    ALL_DAY_REMINDER_OPTIONS.map { option ->
        if (option.minutes == 540) {
            option.copy(label = if (use24Hour) "09:00 day of event" else "9 AM day of event")
        } else {
            option
        }
    }

/**
 * Returns the appropriate reminder options based on event type.
 *
 * @param isAllDay True for all-day events, false for timed events
 * @return List of [ReminderOption] appropriate for the event type
 */
fun getReminderOptionsForEventType(isAllDay: Boolean): List<ReminderOption> =
    if (isAllDay) ALL_DAY_REMINDER_OPTIONS else TIMED_REMINDER_OPTIONS

/**
 * Format reminder option for display.
 * Handles both current and legacy values (grandfathered from older versions),
 * plus arbitrary values from external calendars.
 *
 * @param minutes Reminder minutes value
 * @param isAllDay Whether the event is all-day
 * @param use24Hour Whether to use 24-hour format for time-based labels (default: false)
 * @return Human-readable label for the reminder
 */
fun formatReminderOption(minutes: Int, isAllDay: Boolean, use24Hour: Boolean = false): String {
    // For all-day events, use time-format-aware options
    val options = if (isAllDay) getAllDayReminderOptions(use24Hour) else TIMED_REMINDER_OPTIONS
    // Try to find in current options first
    options.find { it.minutes == minutes }?.let { return it.label }
    // Handle legacy and arbitrary values
    return when (minutes) {
        0 -> "At time of event"
        5 -> "5 minutes before"
        10 -> "10 minutes before"
        30 -> "30 minutes before"
        120 -> "2 hours before"
        2880 -> "2 days before"
        // Handle arbitrary values in most readable unit
        else -> when {
            minutes >= 10080 && minutes % 10080 == 0 -> {
                val weeks = minutes / 10080
                if (weeks == 1) "1 week before" else "$weeks weeks before"
            }
            minutes >= 1440 && minutes % 1440 == 0 -> {
                val days = minutes / 1440
                if (days == 1) "1 day before" else "$days days before"
            }
            minutes >= 60 && minutes % 60 == 0 -> {
                val hours = minutes / 60
                if (hours == 1) "1 hour before" else "$hours hours before"
            }
            else -> {
                if (minutes == 1) "1 minute before" else "$minutes minutes before"
            }
        }
    }
}

/**
 * Format reminder as short label for summary display.
 * Handles both current and legacy values, plus arbitrary values from external calendars.
 *
 * @param minutes Reminder minutes value
 * @param use24Hour Whether to use 24-hour format for the 540-minute option (default: false)
 * @return Short label (e.g., "15m", "1h", "1d", "09:00" or "9AM")
 */
fun formatReminderShort(minutes: Int, use24Hour: Boolean = false): String {
    return when (minutes) {
        // Known preset values
        REMINDER_OFF -> "Off"
        0 -> "At event"
        540 -> if (use24Hour) "09:00" else "9AM"  // All-day events "9 AM day of"
        // For arbitrary values, show in most readable unit
        else -> when {
            minutes >= 10080 && minutes % 10080 == 0 -> "${minutes / 10080}w"
            minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440}d"
            minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes}m"
        }
    }
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

/** Quick preset chips for all-day events: 9AM day of, 1d before, 2d before, 1w before */
val ALL_DAY_PRESET_CHIPS = listOf(
    PresetChip("9AM", 540),
    PresetChip("1d", 1440),
    PresetChip("2d", 2880),
    PresetChip("1w", 10080)
)

/**
 * Convert total minutes to (days, hours, minutes) components.
 *
 * @param totalMinutes Total duration in minutes (non-negative)
 * @return Triple of (days, hours, minutes)
 */
fun minutesToComponents(totalMinutes: Int): Triple<Int, Int, Int> {
    val days = totalMinutes / 1440
    val remaining = totalMinutes % 1440
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
fun formatReminderDuration(minutes: Int, isAllDay: Boolean, use24Hour: Boolean): String {
    if (minutes == 0 && !isAllDay) return "At time of event"

    // All-day contextual labels
    if (isAllDay) {
        val timeLabel = if (use24Hour) "09:00" else "9 AM"
        when {
            // Exactly 9 hours = "9 AM day of event"
            minutes == 540 -> return "$timeLabel day of event"
            // Exact days (multiples of 1440) = "N day(s) before at 9 AM"
            minutes >= 1440 && minutes % 1440 == 0 -> {
                val days = minutes / 1440
                val dayStr = if (days == 1) "1 day" else "$days days"
                return "$dayStr before at $timeLabel"
            }
        }
        // Fall through to generic formatting for non-standard all-day values
    }

    // Generic duration formatting
    return buildGenericDuration(minutes)
}

private fun buildGenericDuration(minutes: Int): String {
    val (days, hours, mins) = minutesToComponents(minutes)
    val parts = mutableListOf<String>()
    if (days > 0) parts.add(if (days == 1) "1 day" else "$days days")
    if (hours > 0) parts.add(if (hours == 1) "1 hour" else "$hours hours")
    if (mins > 0) {
        // Use full "minutes" when it's the only component, "min" when combined
        val minLabel = if (days == 0 && hours == 0) {
            if (mins == 1) "1 minute" else "$mins minutes"
        } else {
            "$mins min"
        }
        parts.add(minLabel)
    }
    if (parts.isEmpty()) return "At time of event"
    return "${parts.joinToString(" ")} before"
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
fun formatReminderSummary(reminderMinutes: List<Int>, use24Hour: Boolean): String {
    if (reminderMinutes.isEmpty()) return "None"
    return reminderMinutes.joinToString(", ") { formatReminderShort(it, use24Hour) }
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
val EVENT_DURATION_OPTIONS = listOf(
    DurationOption("15 minutes", 15),
    DurationOption("30 minutes", 30),
    DurationOption("1 hour", 60),
    DurationOption("2 hours", 120)
)

/**
 * Format duration for display.
 *
 * @param minutes Duration in minutes
 * @return Human-readable label (e.g., "30 minutes", "1 hour")
 */
fun formatDuration(minutes: Int): String {
    return when {
        minutes < 60 -> "$minutes minutes"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            "${hours}h ${mins}m"
        }
    }
}

/**
 * Format duration as short label for summary display.
 *
 * @param minutes Duration in minutes
 * @return Short label (e.g., "30m", "1h", "2h")
 */
fun formatDurationShort(minutes: Int): String {
    return when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            "${hours}h${mins}m"
        }
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
val SYNC_OPTIONS = listOf(
    SyncOption("1 hour", 1 * 60 * 60 * 1000L),
    SyncOption("6 hours", 6 * 60 * 60 * 1000L),
    SyncOption("12 hours", 12 * 60 * 60 * 1000L),
    SyncOption("24 hours", 24 * 60 * 60 * 1000L),
    SyncOption("Manual only", Long.MAX_VALUE)
)

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
val SYNC_LOOKBACK_OPTIONS = listOf(
    SyncLookbackOption("3 months", 90),
    SyncLookbackOption("6 months", 180),
    SyncLookbackOption("1 year", 365),
    SyncLookbackOption("2 years", 730),
    SyncLookbackOption("5 years", 1825),
    SyncLookbackOption("All events", Int.MAX_VALUE)
)

/**
 * Format sync lookback days for display.
 * Handles both known options and arbitrary values.
 *
 * @param days Sync lookback in days
 * @return Human-readable label
 */
fun formatSyncLookback(days: Int): String {
    if (days == Int.MAX_VALUE) return "All events"
    SYNC_LOOKBACK_OPTIONS.find { it.days == days }?.let { return it.label }
    return when {
        days >= 365 && days % 365 == 0 -> {
            val years = days / 365
            if (years == 1) "1 year" else "$years years"
        }
        days >= 30 && days % 30 == 0 -> {
            val months = days / 30
            if (months == 1) "1 month" else "$months months"
        }
        days >= 7 && days % 7 == 0 -> {
            val weeks = days / 7
            if (weeks == 1) "1 week" else "$weeks weeks"
        }
        else -> "$days days"
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
