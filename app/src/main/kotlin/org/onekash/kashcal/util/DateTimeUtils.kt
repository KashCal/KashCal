package org.onekash.kashcal.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
        pattern: String = "EEE, MMM d, yyyy",
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
        return formatEventDate(timestampMs, isAllDay, "EEE, MMM d", localZone)
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

    // ==================== UI Display Formatters ====================

    /**
     * Format timestamp as relative time (e.g., "5 minutes ago", "2 days ago").
     * Used in settings to show last sync time.
     *
     * @param timestampMs Timestamp in milliseconds
     * @return Human-readable relative time string
     */
    fun formatRelativeTime(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestampMs
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
            hours < 24 -> "$hours hour${if (hours > 1) "s" else ""} ago"
            days < 7 -> "$days day${if (days > 1) "s" else ""} ago"
            else -> {
                val date = eventTsToLocalDate(timestampMs, isAllDay = false)
                val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                date.format(formatter)
            }
        }
    }

    /**
     * Format reminder minutes for dropdown display with full label.
     *
     * @param minutes Minutes before event (-1 for off, 0 for at event time)
     * @param isAllDay True for all-day events (shows different options)
     * @return Full display label (e.g., "15 minutes before", "1 day before")
     */
    fun formatReminderLabel(minutes: Int, isAllDay: Boolean): String {
        return if (isAllDay) {
            when (minutes) {
                org.onekash.kashcal.ui.shared.REMINDER_OFF -> "No reminder"
                540 -> "9 AM day of event"
                720 -> "12 hours before"
                1440 -> "1 day before"
                2880 -> "2 days before"
                10080 -> "1 week before"
                else -> "$minutes minutes"
            }
        } else {
            when (minutes) {
                org.onekash.kashcal.ui.shared.REMINDER_OFF -> "No reminder"
                0 -> "At time of event"
                5 -> "5 minutes before"
                10 -> "10 minutes before"
                15 -> "15 minutes before"
                30 -> "30 minutes before"
                60 -> "1 hour before"
                120 -> "2 hours before"
                else -> "$minutes minutes"
            }
        }
    }

    /**
     * Format reminder minutes for compact display.
     * Delegates to FormConstants.formatReminderShort for single source of truth.
     *
     * @param minutes Minutes before event (-1 for off)
     * @return Short display label (e.g., "15m", "1d", "Off", "15h")
     */
    fun formatReminderShort(minutes: Int): String =
        org.onekash.kashcal.ui.shared.formatReminderShort(minutes)

    /**
     * Format sync interval for display.
     *
     * @param intervalMs Sync interval in milliseconds
     * @return Human-readable interval (e.g., "1 hour", "Manual only")
     */
    fun formatSyncInterval(intervalMs: Long): String {
        return when (intervalMs) {
            Long.MAX_VALUE -> "Manual only"
            1 * 60 * 60 * 1000L -> "1 hour"
            6 * 60 * 60 * 1000L -> "6 hours"
            12 * 60 * 60 * 1000L -> "12 hours"
            24 * 60 * 60 * 1000L -> "24 hours"
            else -> "${intervalMs / (60 * 60 * 1000)} hours"
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
    fun formatEventDateTime(startTs: Long, endTs: Long, isAllDay: Boolean): String {
        val startDateStr = formatEventDateShort(startTs, isAllDay)
        val endDateStr = formatEventDateShort(endTs, isAllDay)

        return if (isAllDay) {
            val isMultiDay = spansMultipleDays(startTs, endTs, isAllDay = true)
            if (isMultiDay) {
                "$startDateStr \u2192 $endDateStr \u00b7 All day"
            } else {
                "$startDateStr \u00b7 All day"
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
    fun formatTime(hour: Int, minute: Int): String {
        val localTime = java.time.LocalTime.of(hour, minute)
        val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        return localTime.format(formatter)
    }
}
