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
 */
val ALL_DAY_REMINDER_OPTIONS = listOf(
    ReminderOption("No reminder", REMINDER_OFF),
    ReminderOption("9 AM day of event", 540),
    ReminderOption("12 hours before", 720),
    ReminderOption("1 day before", 1440),
    ReminderOption("1 week before", 10080)
)

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
 * @return Human-readable label for the reminder
 */
fun formatReminderOption(minutes: Int, isAllDay: Boolean): String {
    val options = if (isAllDay) ALL_DAY_REMINDER_OPTIONS else TIMED_REMINDER_OPTIONS
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
 * @return Short label (e.g., "15m", "1h", "1d")
 */
fun formatReminderShort(minutes: Int): String {
    return when (minutes) {
        // Known preset values
        REMINDER_OFF -> "Off"
        0 -> "At event"
        540 -> "9AM"  // Special case for all-day events "9 AM day of"
        // For arbitrary values, show in most readable unit
        else -> when {
            minutes >= 10080 && minutes % 10080 == 0 -> "${minutes / 10080}w"
            minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440}d"
            minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes}m"
        }
    }
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
