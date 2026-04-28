package org.onekash.kashcal.util

import android.content.res.Resources
import android.text.format.DateFormat
import android.text.format.DateUtils
import org.onekash.kashcal.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale

/**
 * Central date/time utilities for event handling.
 *
 * Key principle: All-day events are stored as UTC midnight and must use UTC
 * for date calculations to preserve the calendar date.
 *
 * Example: Jan 6 00:00 UTC in America/New_York (UTC-5):
 * - Local TZ: Jan 5 19:00 EST → Jan 5 (WRONG)
 * - UTC: Jan 6 00:00 UTC → Jan 6 (CORRECT)
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.4">RFC 5545 DATE</a>
 */
object DateTimeUtils {

    // ==================== Locale-Aware Pattern Helper ====================

    fun localizedPattern(skeleton: String): String =
        DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)

    /**
     * Returns true if the given locale orders day before month in its short-form date pattern.
     * Consults [DateTimeFormatterBuilder.getLocalizedDateTimePattern] (e.g., "dd/MM/yyyy" → DMY,
     * "M/d/yyyy" → MDY, "y/MM/dd" → MDY).
     *
     * Returns false (MDY) when neither 'd' nor 'M' appears — unrecognized pattern falls back
     * to the safer MDY interpretation rather than failing.
     */
    fun isDayFirstLocale(locale: Locale = Locale.getDefault()): Boolean {
        val pattern = try {
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale
            )
        } catch (_: IllegalArgumentException) {
            return false
        }
        val dIdx = pattern.indexOf('d')
        val mIdx = pattern.indexOf('M')
        if (dIdx < 0 || mIdx < 0) return false
        return dIdx < mIdx
    }

    // ==================== Time Format Preference ====================

    /**
     * Time format preference enum for type-safe handling.
     */
    enum class TimeFormatPreference {
        SYSTEM,
        TWELVE_HOUR,
        TWENTY_FOUR_HOUR;

        companion object {
            fun fromString(value: String): TimeFormatPreference = when (value) {
                "12h" -> TWELVE_HOUR
                "24h" -> TWENTY_FOUR_HOUR
                else -> SYSTEM
            }
        }
    }

    /**
     * Get the appropriate time pattern based on user preference and device setting.
     *
     * @param preference User's stored preference
     * @param is24HourDevice Result of DateFormat.is24HourFormat(context)
     * @return Pattern string for DateTimeFormatter ("h:mm a" or "HH:mm")
     */
    fun getTimePattern(preference: TimeFormatPreference, is24HourDevice: Boolean): String {
        return when (preference) {
            TimeFormatPreference.TWELVE_HOUR -> "h:mm a"
            TimeFormatPreference.TWENTY_FOUR_HOUR -> "HH:mm"
            TimeFormatPreference.SYSTEM -> if (is24HourDevice) "HH:mm" else "h:mm a"
        }
    }

    /**
     * Convenience overload that takes string preference directly.
     */
    fun getTimePattern(preferenceString: String, is24HourDevice: Boolean): String {
        return getTimePattern(TimeFormatPreference.fromString(preferenceString), is24HourDevice)
    }

    /**
     * Determine if 24-hour format should be used based on preference and device setting.
     *
     * @param timeFormat Time format preference string ("system", "12h", or "24h")
     * @param is24HourDevice Whether the device is set to 24-hour format
     * @return True if 24-hour format should be used
     */
    fun isUse24Hour(timeFormat: String, is24HourDevice: Boolean): Boolean {
        return when (TimeFormatPreference.fromString(timeFormat)) {
            TimeFormatPreference.TWENTY_FOUR_HOUR -> true
            TimeFormatPreference.TWELVE_HOUR -> false
            TimeFormatPreference.SYSTEM -> is24HourDevice
        }
    }

    // ==================== First Day of Week Preference ====================

    /**
     * Get ordered days of week starting from the specified first day.
     *
     * @param firstDayOfWeek Calendar constant (SUNDAY=1, MONDAY=2, SATURDAY=7) or 0 for system default
     * @return List of DayOfWeek in display order (7 elements)
     */
    fun getOrderedDaysOfWeek(firstDayOfWeek: Int): List<DayOfWeek> {
        val effectiveFirst = resolveFirstDayOfWeek(firstDayOfWeek)
        return when (effectiveFirst) {
            Calendar.SUNDAY -> listOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
            )
            Calendar.MONDAY -> listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
            )
            Calendar.SATURDAY -> listOf(
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            )
            else -> getOrderedDaysOfWeek(Calendar.SUNDAY) // Fallback
        }
    }

    /**
     * Resolve first day preference to actual Calendar constant.
     *
     * @param preference User preference: 0 = system default, or Calendar.SUNDAY/MONDAY/SATURDAY
     * @return Resolved Calendar constant (SUNDAY=1, MONDAY=2, or SATURDAY=7)
     */
    fun resolveFirstDayOfWeek(preference: Int): Int {
        return if (preference == 0) {
            // Use java.time.temporal.WeekFields for locale-aware detection
            val localeFirstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            when (localeFirstDay) {
                DayOfWeek.SUNDAY -> Calendar.SUNDAY
                DayOfWeek.MONDAY -> Calendar.MONDAY
                DayOfWeek.SATURDAY -> Calendar.SATURDAY
                else -> Calendar.SUNDAY // Rare cases (Friday-first in some locales)
            }
        } else {
            preference // User explicitly chose
        }
    }

    /**
     * Get locale-aware WeekFields for week number calculation.
     * Combines the user's first-day-of-week preference with the locale's minimalDaysInFirstWeek.
     *
     * @param firstDayOfWeek User preference: 0 = system default, or Calendar.SUNDAY/MONDAY/SATURDAY
     * @return WeekFields configured for locale-aware week numbering
     */
    fun getLocaleWeekFields(firstDayOfWeek: Int): WeekFields {
        val resolved = resolveFirstDayOfWeek(firstDayOfWeek)
        val dow = when (resolved) {
            Calendar.SUNDAY -> DayOfWeek.SUNDAY
            Calendar.MONDAY -> DayOfWeek.MONDAY
            Calendar.SATURDAY -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }
        return WeekFields.of(dow, WeekFields.of(Locale.getDefault()).minimalDaysInFirstWeek)
    }

    /**
     * Get the locale's default first day of week.
     *
     * NOTE: Do NOT wrap calls to this in remember{} - must respond to system locale changes.
     * (Same pattern as is24HourDevice fix in commit afd7a76)
     *
     * @return DayOfWeek representing the locale's first day
     */
    fun getLocaleFirstDayOfWeek(): DayOfWeek {
        return WeekFields.of(Locale.getDefault()).firstDayOfWeek
    }

    /**
     * Calculate grid offset for month 1st day placement.
     *
     * @param calendar Calendar instance set to the 1st of the month
     * @param firstDayOfWeek Calendar constant or 0 for system default
     * @return Offset (0-6) for where day 1 should appear in the grid
     */
    fun getFirstDayOffset(calendar: Calendar, firstDayOfWeek: Int): Int {
        val effectiveFirst = resolveFirstDayOfWeek(firstDayOfWeek)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 7=Saturday
        val offset = dayOfWeek - effectiveFirst
        return if (offset < 0) offset + 7 else offset
    }

    /**
     * Calculate days since the specified first day of week.
     * Used by DateFilter for "This Week"/"Next Week" calculations.
     *
     * @param date The date to check
     * @param firstDayOfWeek Calendar constant or 0 for system default
     * @return 0 for first day of week, 6 for last day
     */
    fun getDayOfWeekOffset(date: LocalDate, firstDayOfWeek: Int): Int {
        val effectiveFirst = resolveFirstDayOfWeek(firstDayOfWeek)
        val dayValue = date.dayOfWeek.value // Monday=1, Sunday=7

        return when (effectiveFirst) {
            Calendar.SUNDAY -> if (dayValue == 7) 0 else dayValue // Sun=0, Mon=1, ..., Sat=6
            Calendar.MONDAY -> dayValue - 1 // Mon=0, Tue=1, ..., Sun=6
            Calendar.SATURDAY -> (dayValue + 1) % 7 // Sat=0, Sun=1, ..., Fri=6
            else -> if (dayValue == 7) 0 else dayValue // Fallback to Sunday-first
        }
    }

    // ==================== Date Conversion Functions ====================

    /**
     * Convert event timestamp to LocalDate.
     *
     * For all-day events: Uses UTC to preserve calendar date
     * For timed events: Uses local timezone for user's perspective
     *
     * @param timestampMs Timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     */
    fun eventTsToLocalDate(
        timestampMs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): LocalDate {
        val zone = if (isAllDay) ZoneOffset.UTC else localZone
        return Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
    }

    /**
     * Convert event timestamp to ZonedDateTime.
     *
     * For all-day events: Returns UTC ZonedDateTime
     * For timed events: Returns local ZonedDateTime
     *
     * @param timestampMs Timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     */
    fun eventTsToZonedDateTime(
        timestampMs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): ZonedDateTime {
        val zone = if (isAllDay) ZoneOffset.UTC else localZone
        return Instant.ofEpochMilli(timestampMs).atZone(zone)
    }

    /**
     * Convert timestamp to day code (YYYYMMDD format).
     *
     * For all-day events: Uses UTC
     * For timed events: Uses local timezone
     *
     * @param timestampMs Timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     * @return Day code as Int (e.g., 20260106 for Jan 6, 2026)
     */
    fun eventTsToDayCode(
        timestampMs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val date = eventTsToLocalDate(timestampMs, isAllDay, localZone)
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    /**
     * Check if two timestamps represent different calendar days.
     *
     * Correctly handles all-day events by using UTC.
     *
     * @param startTs Start timestamp in milliseconds
     * @param endTs End timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     * @return true if end date is after start date
     */
    fun spansMultipleDays(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val startDate = eventTsToLocalDate(startTs, isAllDay, localZone)
        val endDate = eventTsToLocalDate(endTs, isAllDay, localZone)
        return endDate.isAfter(startDate)
    }

    /**
     * Calculate total days an event spans.
     * Returns 1 for single-day events.
     *
     * @param startTs Start timestamp in milliseconds
     * @param endTs End timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     * @return Number of days the event spans (minimum 1)
     */
    fun calculateTotalDays(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val startDate = eventTsToLocalDate(startTs, isAllDay, localZone)
        val endDate = eventTsToLocalDate(endTs, isAllDay, localZone)
        return ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
    }

    /**
     * Calculate which day number the selected date is within a multi-day event.
     *
     * @param startTs Event start timestamp in milliseconds
     * @param selectedTs Selected date timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     * @return Day number (1-based, e.g., "Day 1", "Day 2")
     */
    fun calculateCurrentDay(
        startTs: Long,
        selectedTs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val startDate = eventTsToLocalDate(startTs, isAllDay, localZone)
        val selectedDate = eventTsToLocalDate(selectedTs, isAllDay, localZone)
        return ChronoUnit.DAYS.between(startDate, selectedDate).toInt() + 1
    }

    // ==================== Formatting Functions ====================

    /**
     * Format event date for display.
     *
     * For all-day events: Uses UTC to preserve the calendar date.
     * For timed events: Uses local timezone for user's perspective.
     *
     * This is the canonical way to format event dates in KashCal.
     * Use this instead of SimpleDateFormat to ensure timezone correctness.
     *
     * @param timestampMs Timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param pattern DateTimeFormatter pattern (default: "EEE, MMM d, yyyy")
     * @param localZone Timezone for timed events (default: system)
     * @return Formatted date string
     */
    fun formatEventDate(
        timestampMs: Long,
        isAllDay: Boolean,
        pattern: String = localizedPattern("yEEEMMMd"),
        localZone: ZoneId = ZoneId.systemDefault()
    ): String {
        val date = eventTsToLocalDate(timestampMs, isAllDay, localZone)
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        return date.format(formatter)
    }

    /**
     * Format event date with short pattern (e.g., "Thu, Dec 25").
     * Convenience wrapper for common use case.
     *
     * @param timestampMs Timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param localZone Timezone for timed events (default: system)
     * @return Formatted date string
     */
    fun formatEventDateShort(
        timestampMs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): String {
        return formatEventDate(timestampMs, isAllDay, localizedPattern("EEEMMMd"), localZone)
    }

    /**
     * Format event time for display.
     *
     * For all-day events: Returns empty string (no time component)
     * For timed events: Returns formatted time in local timezone
     *
     * @param timestampMs Timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @param pattern DateTimeFormatter pattern (default: "h:mm a")
     * @param localZone Timezone for timed events (default: system)
     * @return Formatted time string, or empty for all-day events
     */
    fun formatEventTime(
        timestampMs: Long,
        isAllDay: Boolean,
        pattern: String = "h:mm a",
        localZone: ZoneId = ZoneId.systemDefault()
    ): String {
        if (isAllDay) return ""
        val zdt = eventTsToZonedDateTime(timestampMs, isAllDay, localZone)
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        return zdt.format(formatter)
    }

    // ==================== Conversion Functions ====================

    /**
     * Convert a local date (from UI picker) to UTC midnight timestamp.
     *
     * This is used when creating/editing all-day events. The UI date picker
     * returns a local time (e.g., Jan 6 00:00 local), but all-day events
     * must be stored as UTC midnight (Jan 6 00:00 UTC) for consistency
     * with iCal/CalDAV parsing.
     *
     * @param localDateMillis Timestamp from date picker (local midnight)
     * @param localZone Timezone of the date picker (default: system)
     * @return UTC midnight timestamp in milliseconds
     */
    fun localDateToUtcMidnight(
        localDateMillis: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Long {
        // Parse local timestamp to get the calendar date in local timezone
        val localDate = Instant.ofEpochMilli(localDateMillis).atZone(localZone).toLocalDate()
        // Convert that date to UTC midnight
        return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /**
     * Convert UTC midnight timestamp to local date representation.
     *
     * This is the inverse of localDateToUtcMidnight(). Used when loading
     * an all-day event for editing - converts the stored UTC midnight
     * back to the local date for display in the date picker.
     *
     * @param utcMidnightMillis UTC midnight timestamp in milliseconds
     * @param localZone Target timezone for display (default: system)
     * @return Local midnight timestamp in the target timezone
     */
    fun utcMidnightToLocalDate(
        utcMidnightMillis: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Long {
        // Get the date in UTC (this is the canonical calendar date)
        val utcDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        // Return midnight in local timezone for that same calendar date
        return utcDate.atStartOfDay(localZone).toInstant().toEpochMilli()
    }

    /**
     * Get the end-of-day timestamp for an all-day event.
     *
     * For single-day all-day events, endTs should be 23:59:59.999 UTC
     * of the same day. This ensures the event spans the correct day.
     *
     * @param utcMidnightMillis UTC midnight timestamp (start of day)
     * @return UTC end-of-day timestamp (23:59:59.999)
     */
    fun utcMidnightToEndOfDay(utcMidnightMillis: Long): Long {
        // Add 24 hours minus 1 millisecond to get end of day
        return utcMidnightMillis + (24 * 60 * 60 * 1000) - 1
    }

    // ==================== UTC Midnight Normalization ====================

    /**
     * Normalize a UTC timestamp to midnight of the same UTC day.
     * Used for CalendarProvider all-day event ORIGINAL_INSTANCE_TIME normalization.
     *
     * @param utcMillis Timestamp in milliseconds (UTC)
     * @return UTC midnight of the same day in milliseconds
     */
    fun normalizeToUtcMidnight(utcMillis: Long): Long = (utcMillis / 86_400_000L) * 86_400_000L

    // ==================== RFC 5545 Duration Parsing ====================

    /**
     * Parse an RFC 5545 duration string to milliseconds.
     *
     * CalendarProvider stores duration instead of endTs for recurring events.
     * Format: P[n]W or P[n]D or PT[n]H[n]M[n]S
     *
     * Examples:
     * - "P1D" → 86,400,000 ms (1 day)
     * - "P1W" → 604,800,000 ms (1 week)
     * - "PT1H30M" → 5,400,000 ms (1.5 hours)
     * - "PT30M" → 1,800,000 ms (30 minutes)
     *
     * @param duration RFC 5545 duration string (e.g., "PT1H30M", "P1D")
     * @return Duration in milliseconds, or null if null/invalid
     */
    fun parseDurationToMillis(duration: String?): Long? {
        if (duration.isNullOrEmpty()) return null
        if (!duration.startsWith("P")) return null

        try {
            var totalMs = 0L
            var remaining = duration.substring(1) // Remove 'P'

            // Handle weeks (P1W)
            val weekMatch = Regex("(\\d+)W").find(remaining)
            if (weekMatch != null) {
                val weeks = weekMatch.groupValues[1].toLong()
                totalMs += weeks * 7 * 24 * 60 * 60 * 1000
                remaining = remaining.replace(weekMatch.value, "")
            }

            // Handle days (P1D or P2DT...)
            val dayMatch = Regex("(\\d+)D").find(remaining)
            if (dayMatch != null) {
                val days = dayMatch.groupValues[1].toLong()
                totalMs += days * 24 * 60 * 60 * 1000
                remaining = remaining.replace(dayMatch.value, "")
            }

            // Handle time component (T...)
            if (remaining.startsWith("T")) {
                remaining = remaining.substring(1) // Remove 'T'

                // Hours
                val hourMatch = Regex("(\\d+)H").find(remaining)
                if (hourMatch != null) {
                    val hours = hourMatch.groupValues[1].toLong()
                    totalMs += hours * 60 * 60 * 1000
                    remaining = remaining.replace(hourMatch.value, "")
                }

                // Minutes
                val minMatch = Regex("(\\d+)M").find(remaining)
                if (minMatch != null) {
                    val minutes = minMatch.groupValues[1].toLong()
                    totalMs += minutes * 60 * 1000
                    remaining = remaining.replace(minMatch.value, "")
                }

                // Seconds
                val secMatch = Regex("(\\d+)S").find(remaining)
                if (secMatch != null) {
                    val seconds = secMatch.groupValues[1].toLong()
                    totalMs += seconds * 1000
                }
            }

            // If we parsed nothing, return null
            if (totalMs == 0L && duration != "PT0M" && duration != "PT0S" && duration != "P0D") {
                // Check if this is actually a valid zero-duration
                if (!duration.contains(Regex("\\d"))) return null
            }

            return totalMs
        } catch (_: Exception) {
            return null
        }
    }

    // ==================== UI Display Formatters ====================

    /**
     * Format timestamp as relative time (e.g., "5 minutes ago", "2 days ago").
     * Used in settings to show last sync time.
     *
     * @param timestampMs Timestamp in milliseconds
     * @return Human-readable relative time string
     */
    fun formatRelativeTime(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
        return DateUtils.getRelativeTimeSpanString(
            timestampMs,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    /**
     * Format reminder minutes for dropdown display with full label.
     *
     * @param minutes Minutes before event (-1 for off, 0 for at event time)
     * @param isAllDay True for all-day events (shows different options)
     * @param use24Hour Whether to use 24-hour format for time-based labels (default: false)
     * @return Full display label (e.g., "15 minutes before", "1 day before")
     */
    fun formatReminderLabel(minutes: Int, isAllDay: Boolean, use24Hour: Boolean = false, resources: Resources): String {
        return if (isAllDay) {
            when (minutes) {
                org.onekash.kashcal.ui.shared.REMINDER_OFF -> resources.getString(R.string.reminder_none)
                540 -> if (use24Hour) resources.getString(R.string.reminder_day_of_event_24h)
                       else resources.getString(R.string.reminder_day_of_event_12h)
                720 -> resources.getQuantityString(R.plurals.reminder_hours_before, 12, 12)
                1440 -> resources.getQuantityString(R.plurals.reminder_days_before, 1, 1)
                2880 -> resources.getQuantityString(R.plurals.reminder_days_before, 2, 2)
                10080 -> resources.getQuantityString(R.plurals.reminder_weeks_before, 1, 1)
                else -> resources.getQuantityString(R.plurals.time_minutes, minutes, minutes)
            }
        } else {
            when (minutes) {
                org.onekash.kashcal.ui.shared.REMINDER_OFF -> resources.getString(R.string.reminder_none)
                0 -> resources.getString(R.string.reminder_at_time_of_event)
                5 -> resources.getQuantityString(R.plurals.reminder_minutes_before, 5, 5)
                10 -> resources.getQuantityString(R.plurals.reminder_minutes_before, 10, 10)
                15 -> resources.getQuantityString(R.plurals.reminder_minutes_before, 15, 15)
                30 -> resources.getQuantityString(R.plurals.reminder_minutes_before, 30, 30)
                60 -> resources.getQuantityString(R.plurals.reminder_hours_before, 1, 1)
                120 -> resources.getQuantityString(R.plurals.reminder_hours_before, 2, 2)
                else -> resources.getQuantityString(R.plurals.time_minutes, minutes, minutes)
            }
        }
    }

    /**
     * Format sync interval for display.
     *
     * @param intervalMs Sync interval in milliseconds
     * @return Human-readable interval (e.g., "1 hour", "Manual only")
     */
    fun formatSyncInterval(intervalMs: Long, resources: Resources): String {
        if (intervalMs == Long.MAX_VALUE) return resources.getString(R.string.sync_manual_only)
        val minutes = (intervalMs / (60 * 1000)).toInt()
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

    /**
     * Format event date and time for display in quick view and lists.
     *
     * Uses correct timezone handling:
     * - All-day events: UTC to preserve calendar date
     * - Timed events: Local timezone for user's perspective
     *
     * Output format:
     * - All-day single day: "Thu, Dec 25 · All day"
     * - All-day multi-day: "Thu, Dec 25 → Fri, Dec 26 · All day"
     * - Timed: "Thu, Dec 25 · 2:00 PM - 3:00 PM"
     *
     * @param startTs Start timestamp in milliseconds
     * @param endTs End timestamp in milliseconds
     * @param isAllDay Whether this is an all-day event
     * @return Formatted date/time string
     */
    fun formatEventDateTime(startTs: Long, endTs: Long, isAllDay: Boolean, resources: Resources): String {
        val startDateStr = formatEventDateShort(startTs, isAllDay)
        val endDateStr = formatEventDateShort(endTs, isAllDay)
        val allDayLabel = resources.getString(R.string.label_all_day)

        return if (isAllDay) {
            val isMultiDay = spansMultipleDays(startTs, endTs, isAllDay = true)
            if (isMultiDay) {
                "$startDateStr \u2192 $endDateStr \u00b7 $allDayLabel"
            } else {
                "$startDateStr \u00b7 $allDayLabel"
            }
        } else {
            val startTime = formatEventTime(startTs, isAllDay)
            val endTime = formatEventTime(endTs, isAllDay)
            "$startDateStr \u00b7 $startTime - $endTime"
        }
    }

    /**
     * Format time from hour and minute values.
     * Used in form displays and pickers.
     *
     * @param hour Hour (0-23)
     * @param minute Minute (0-59)
     * @return Formatted time string (e.g., "2:30 PM")
     */
    fun formatTime(hour: Int, minute: Int, pattern: String = "h:mm a"): String {
        val localTime = java.time.LocalTime.of(hour, minute)
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        return localTime.format(formatter)
    }

    // ==================== Event Past Check ====================

    /**
     * Determine if an event/occurrence has passed.
     *
     * For all-day events: Uses day code comparison (local calendar date).
     * This fixes the bug where all-day events were grayed out at 6 PM for UTC-6 users
     * because endTs (UTC midnight) < current UTC time, even though locally it's still "today".
     *
     * For timed events: Uses timestamp comparison (actual moment in time).
     *
     * @param endTs End timestamp of the occurrence in milliseconds
     * @param endDay End day code (YYYYMMDD) of the occurrence
     * @param isAllDay Whether this is an all-day event
     * @param nowMs Current time in milliseconds (injectable for testing)
     * @param todayDayCode Today's day code in local timezone (injectable for testing)
     * @return true if the event has passed, false otherwise
     */
    fun isEventPast(
        endTs: Long,
        endDay: Int,
        isAllDay: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        todayDayCode: Int = eventTsToDayCode(System.currentTimeMillis(), isAllDay = false)
    ): Boolean {
        return if (isAllDay) {
            // All-day events: compare calendar dates (endDay is already in correct format)
            // Event is past only when local calendar date has moved past the event's end date
            endDay < todayDayCode
        } else {
            // Timed events: compare exact timestamps
            endTs < nowMs
        }
    }
}
