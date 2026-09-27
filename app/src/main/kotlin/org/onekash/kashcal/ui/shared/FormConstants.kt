package org.onekash.kashcal.ui.shared

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore

/*
 * UI options and label formatters for forms and settings: reminders, reminder presets, default
 * event duration, sync interval and sync lookback, plus email masking for display. Data-layer
 * sentinels and defaults live in the `KashCalDataStore` companion.
 */

/** Marks "no reminder"; the same value as [KashCalDataStore.REMINDER_OFF]. */
const val REMINDER_OFF = KashCalDataStore.REMINDER_OFF

/**
 * Pairs a reminder picker label with its offset.
 *
 * @property label e.g. "15 minutes before".
 * @property minutes minutes before the event, or [REMINDER_OFF] for no reminder.
 */
data class ReminderOption(
    val label: String,
    val minutes: Int
)

/**
 * Lists the reminder picker's offsets for timed events.
 *
 * Other values, such as 10 or 120 from an external calendar or an older app version, stay valid
 * on existing events; only the picker leaves them out.
 */
val TIMED_REMINDER_MINUTES = listOf(REMINDER_OFF, 0, 5, 15, 30, 60, 240, 1440, 10080)

fun getTimedReminderOptions(resources: Resources): List<ReminderOption> =
    TIMED_REMINDER_MINUTES.map { minutes ->
        ReminderOption(formatReminderOption(minutes, isAllDay = false, resources = resources), minutes)
    }

/**
 * Lists the reminder picker's offsets for all-day events, each firing at 9 AM on the event day or
 * on a day before it.
 *
 * Values are signed minutes before the start (the Android CalendarProvider convention): positive
 * fires before the event's midnight, negative after. 9 AM day of is -540; one day, two days and a
 * week before (900, 2340, 9540) are hour-based offsets, which stay stable across DST.
 *
 * Other values, such as 2880 from an external calendar or an older app version, stay valid on
 * existing events; only the picker leaves them out. Labels are terse and time-independent
 * ("Day of event", "1 day before"); the picker sheet's hint states the 9 AM fire time.
 */
val ALL_DAY_REMINDER_MINUTES = listOf(REMINDER_OFF, -540, 900, 2340, 9540)

fun getAllDayReminderOptions(resources: Resources): List<ReminderOption> =
    ALL_DAY_REMINDER_MINUTES.map { minutes ->
        ReminderOption(formatReminderOption(minutes, isAllDay = true, resources = resources), minutes)
    }

/**
 * Returns the reminder picker's full label for [minutes], for picker values and for any other
 * value an external calendar or an older app version stored.
 *
 * @param isAllDay whether [minutes] is an all-day offset, which maps the picker's all-day values
 *   to day labels.
 */
fun formatReminderOption(minutes: Int, isAllDay: Boolean, resources: Resources): String {
    // Signed offsets are described on ALL_DAY_REMINDER_MINUTES; the sheet hint states the 9 AM
    // fire time, so these labels leave it out.
    if (isAllDay) {
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
        // A timed "9 hours before". An all-day 540 lands here too and reads "9 hours before",
        // matching its fire time; the all-day "9 AM day of" is -540.
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
 * Returns the compact reminder label used in [formatReminderSummary], for any stored value.
 *
 * @param use24Hour whether the 9 AM label reads "09:00" instead of "9AM".
 * @param isAllDay whether [minutes] is a signed all-day offset.
 * @return e.g. "15m", "1h", "1d", "09:00" or "9AM".
 */
fun formatReminderShort(minutes: Int, use24Hour: Boolean = false, isAllDay: Boolean = false, resources: Resources): String {
    // All-day offsets: -540 is 9 AM day of; 900, 2340 and 9540 are 9 AM 1, 2 and 7 days before.
    // Gated on isAllDay so a timed value such as 900 (15 hours before) keeps its own label.
    if (isAllDay && minutes != REMINDER_OFF) {
        when (minutes) {
            -540 -> return resources.getString(if (use24Hour) R.string.reminder_short_9am_24h else R.string.reminder_short_9am_12h)
            900 -> return resources.getString(R.string.reminder_short_days, 1)
            2340 -> return resources.getString(R.string.reminder_short_days, 2)
            9540 -> return resources.getString(R.string.reminder_short_weeks, 1)
            // Any other all-day offset, such as a synced after-start alarm, renders by magnitude
            // so it never shows a negative like "-540m"; this chip doesn't show the sign.
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
 * Returns the medium-length label for a reminder or duration in a Settings row, in abbreviated
 * words ("30 min", "1 hr", "1 day", "2 days", "1 wk").
 *
 * It sits between the compact [formatReminderShort] and the sheet's full "N minutes before". The
 * picker's all-day offsets map to their day or week meaning like [formatReminderShort] does:
 * 900 must read "1 day", not "15 hr", and -540 reads "Day of" (the sheet hint states the 9 AM
 * time). Any other all-day offset renders by magnitude.
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
 * Pairs a reminder preset chip's label with its offset in minutes.
 *
 * @property label e.g. "15m" or "1h".
 */
data class PresetChip(
    val label: String,
    val minutes: Int
)

/** Lists the reminder preset chips for timed events. */
val TIMED_PRESET_CHIPS = listOf(
    PresetChip("15m", 15),
    PresetChip("30m", 30),
    PresetChip("1h", 60),
    PresetChip("1d", 1440)
)

/**
 * Lists the reminder preset chips for all-day events, as signed minutes before the event's local
 * midnight (positive before, negative after), with the TRIGGER each becomes:
 * - 9AM day of: -540 ("PT9H", 9 AM on the event day)
 * - 1d before: 900 ("-PT15H", 9 AM the day before)
 * - 2d before: 2340 ("-PT39H", 9 AM two days before)
 * - 1w before: 9540 ("-PT159H", 9 AM a week before)
 */
val ALL_DAY_PRESET_CHIPS = listOf(
    PresetChip("9AM", -540),
    PresetChip("1d", 900),
    PresetChip("2d", 2340),
    PresetChip("1w", 9540)
)

/** Splits [totalMinutes] into (days, hours, minutes); a negative value splits by its magnitude. */
fun minutesToComponents(totalMinutes: Int): Triple<Int, Int, Int> {
    val abs = kotlin.math.abs(totalMinutes)
    val days = abs / 1440
    val remaining = abs % 1440
    val hours = remaining / 60
    val minutes = remaining % 60
    return Triple(days, hours, minutes)
}

/** Joins (days, hours, minutes) into total minutes; the inverse of [minutesToComponents]. */
fun componentsToMinutes(days: Int, hours: Int, minutes: Int): Int =
    days * 1440 + hours * 60 + minutes

/**
 * Rounds [minutes] to the nearest multiple of [step], half up, so an odd minute value from a
 * server lands on a wheel position.
 */
fun roundToWheelStep(minutes: Int, step: Int = 5): Int =
    ((minutes + step / 2) / step) * step

/**
 * Returns the full label for a reminder offset, as in "15 minutes before", "1 hour 30 min
 * before" or "At time of event".
 *
 * For all-day events the picker's offsets name the fire time ("9 AM day of event", "1 day before
 * at 9 AM"). Any other value reads as a plain duration before the event, a negative one by its
 * magnitude.
 *
 * @param use24Hour whether the all-day fire time reads "09:00" instead of "9 AM".
 */
fun formatReminderDuration(minutes: Int, isAllDay: Boolean, use24Hour: Boolean, resources: Resources): String {
    if (minutes == 0 && !isAllDay) return resources.getString(R.string.reminder_at_time_of_event)

    if (isAllDay) {
        // -540 is 9 AM day of; 900, 2340 and 9540 are 9 AM 1, 2 and 7 days before.
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

/** Returns [reminders] without duplicates, in ascending order. */
fun deduplicateAndSortReminders(reminders: List<Int>): List<Int> =
    reminders.distinct().sorted()

/**
 * Returns [reminderMinutes] as a comma-separated [formatReminderShort] summary for the reminder
 * picker's collapsed header, e.g. "15m, 1h, 1d", or "None" when empty.
 */
fun formatReminderSummary(reminderMinutes: List<Int>, use24Hour: Boolean, resources: Resources, isAllDay: Boolean = false): String {
    if (reminderMinutes.isEmpty()) return resources.getString(R.string.reminder_summary_none)
    return reminderMinutes.joinToString(", ") { formatReminderShort(it, use24Hour, isAllDay, resources) }
}

/** Pairs a duration option's label, e.g. "30 minutes", with its length in minutes. */
data class DurationOption(
    val label: String,
    val minutes: Int
)

/** Lists the default-duration choices for new events offered in Settings. */
val EVENT_DURATION_MINUTES = listOf(15, 30, 60, 120)

fun getEventDurationOptions(resources: Resources): List<DurationOption> =
    EVENT_DURATION_MINUTES.map { DurationOption(formatDuration(it, resources), it) }

/**
 * Returns a duration label: minutes under an hour ("30 minutes"), whole hours ("1 hour"), else
 * the compact hours-and-minutes form.
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
 * Pairs a sync frequency label, e.g. "1 hour", with its interval.
 *
 * @property intervalMs sync interval in milliseconds, or [Long.MAX_VALUE] for manual only.
 */
data class SyncOption(
    val label: String,
    val intervalMs: Long
)

/** Lists the background calendar sync intervals offered; [Long.MAX_VALUE] means manual only. */
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
 * Pairs a sync lookback label, e.g. "3 months", with its length.
 *
 * @property days days to look back, or [Int.MAX_VALUE] for all events.
 */
data class SyncLookbackOption(
    val label: String,
    val days: Int
)

/** Lists the choices for how far back calendar events sync. */
val SYNC_LOOKBACK_DAYS = listOf(90, 180, 365, 730, 1825, Int.MAX_VALUE)

fun getSyncLookbackOptions(resources: Resources): List<SyncLookbackOption> =
    SYNC_LOOKBACK_DAYS.map { SyncLookbackOption(formatSyncLookback(it, resources), it) }

/**
 * Returns the label for a lookback of [days], a listed choice or any other value, in the largest
 * of years, months, weeks or days that divides it evenly.
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
 * Masks an email address for display: the first character, `***`, then the domain.
 *
 * - "john@icloud.com" -> "j***@icloud.com"
 * - "a@b.com" -> "a@b.com": a local part under two characters, or no `@`, stays unchanged
 * - null -> ""
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
