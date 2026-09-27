package org.onekash.kashcal.util

import android.content.res.Resources
import android.text.format.DateFormat
import android.text.format.DateUtils
import org.onekash.kashcal.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
 * Date and time helpers for events.
 *
 * All-day events are stored as UTC midnight, so their date calculations must use UTC to keep
 * the calendar date. Jan 6 00:00 UTC read in America/New_York (UTC-5) is Jan 5 19:00, the
 * wrong day; read in UTC it is Jan 6.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.4">RFC 5545 DATE</a>
 */
object DateTimeUtils {

    // ==================== Locale-Aware Pattern Helper ====================

    fun localizedPattern(skeleton: String, locale: Locale = Locale.getDefault()): String =
        DateFormat.getBestDateTimePattern(locale, skeleton)

    /**
     * Returns true if [locale] orders day before month in its short date pattern from
     * [DateTimeFormatterBuilder.getLocalizedDateTimePattern] ("dd/MM/yyyy" is DMY; "M/d/yyyy"
     * and "y/MM/dd" are MDY).
     *
     * Returns false (MDY) when the pattern lacks 'd' or 'M' or the lookup throws
     * IllegalArgumentException, so an unrecognized pattern falls back to MDY without failing.
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

    /**
     * Returns true if [locale] puts the year before the month in its localized `yMMMM` pattern
     * (ja, zh, ko and hu give "2026年5月", "2026. május"), or has a year and no 'M'.
     */
    fun isYearFirstLocale(locale: Locale = Locale.getDefault()): Boolean {
        val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMM")
        val yIdx = pattern.indexOf('y')
        val mIdx = pattern.indexOf('M')
        if (yIdx < 0) return false
        return mIdx < 0 || yIdx < mIdx
    }

    // ==================== Time Format Preference ====================

    /**
     * The stored time format setting. [fromString] maps "12h" and "24h"; any other value is
     * SYSTEM.
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
     * Returns the DateTimeFormatter pattern, "h:mm a" or "HH:mm", for [preference], deferring
     * to the device under SYSTEM.
     *
     * @param is24HourDevice the result of `DateFormat.is24HourFormat(context)`
     */
    fun getTimePattern(preference: TimeFormatPreference, is24HourDevice: Boolean): String {
        return when (preference) {
            TimeFormatPreference.TWELVE_HOUR -> "h:mm a"
            TimeFormatPreference.TWENTY_FOUR_HOUR -> "HH:mm"
            TimeFormatPreference.SYSTEM -> if (is24HourDevice) "HH:mm" else "h:mm a"
        }
    }

    /** Returns the pattern for a stored preference string ("system", "12h" or "24h"). */
    fun getTimePattern(preferenceString: String, is24HourDevice: Boolean): String {
        return getTimePattern(TimeFormatPreference.fromString(preferenceString), is24HourDevice)
    }

    /**
     * Returns true when [timeFormat] ("system", "12h" or "24h") calls for 24-hour time,
     * deferring to [is24HourDevice] under "system".
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
     * Returns the seven days in display order, starting from [firstDayOfWeek].
     *
     * @param firstDayOfWeek a Calendar constant (SUNDAY=1, MONDAY=2, SATURDAY=7) or 0 for the
     *   locale default; any other value starts on Sunday
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
            else -> getOrderedDaysOfWeek(Calendar.SUNDAY)
        }
    }

    /**
     * Resolves the first-day-of-week preference to a Calendar constant.
     *
     * @param preference 0 for the locale default, else Calendar.SUNDAY, MONDAY or SATURDAY;
     *   any nonzero value is returned unchanged
     * @return for 0, SUNDAY (1), MONDAY (2) or SATURDAY (7); a locale that starts on another
     *   day resolves to SUNDAY
     */
    fun resolveFirstDayOfWeek(preference: Int): Int {
        return if (preference == 0) {
            val localeFirstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            when (localeFirstDay) {
                DayOfWeek.SUNDAY -> Calendar.SUNDAY
                DayOfWeek.MONDAY -> Calendar.MONDAY
                DayOfWeek.SATURDAY -> Calendar.SATURDAY
                else -> Calendar.SUNDAY // e.g. Friday-first locales
            }
        } else {
            preference
        }
    }

    /**
     * Returns WeekFields for week numbers: the resolved [firstDayOfWeek] preference (see
     * [resolveFirstDayOfWeek]) with the locale's minimalDaysInFirstWeek.
     */
    fun getLocaleWeekFields(firstDayOfWeek: Int): WeekFields {
        val resolved = resolveFirstDayOfWeek(firstDayOfWeek)
        val dow = calendarConstantToDayOfWeek(resolved)
        return WeekFields.of(dow, WeekFields.of(Locale.getDefault()).minimalDaysInFirstWeek)
    }

    /**
     * Maps a Calendar weekday constant to DayOfWeek. Unknown values fall back
     * to SUNDAY (consistent with [resolveFirstDayOfWeek] for Friday-first locales).
     */
    fun calendarConstantToDayOfWeek(calendarConstant: Int): DayOfWeek = when (calendarConstant) {
        Calendar.SUNDAY -> DayOfWeek.SUNDAY
        Calendar.MONDAY -> DayOfWeek.MONDAY
        Calendar.SATURDAY -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    /**
     * Resolves the first-day-of-week preference to a DayOfWeek, snapping unsupported locales
     * via [calendarConstantToDayOfWeek]. The WKST source for biweekly RRULEs (issue #214).
     */
    fun resolveFirstDayOfWeekAsDow(preference: Int): DayOfWeek =
        calendarConstantToDayOfWeek(resolveFirstDayOfWeek(preference))

    /**
     * Returns the default locale's first day of week.
     *
     * Don't wrap calls in `remember {}`: the result must follow system locale changes.
     */
    fun getLocaleFirstDayOfWeek(): DayOfWeek {
        return WeekFields.of(Locale.getDefault()).firstDayOfWeek
    }

    /**
     * Returns the grid column (0-6) of the month's first day.
     *
     * @param calendar set to the 1st of the month
     * @param firstDayOfWeek a Calendar constant or 0 for the locale default
     */
    fun getFirstDayOffset(calendar: Calendar, firstDayOfWeek: Int): Int {
        val effectiveFirst = resolveFirstDayOfWeek(firstDayOfWeek)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 7=Saturday
        val offset = dayOfWeek - effectiveFirst
        return if (offset < 0) offset + 7 else offset
    }

    /**
     * Returns how many days [date] is past the start of its week: 0 on the first day, 6 on the
     * last. Used for week starts, such as DateFilter's "This Week" and "Next Week".
     *
     * @param firstDayOfWeek a Calendar constant or 0 for the locale default
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
     * Converts an event timestamp to its date: in UTC for an all-day event, which keeps the
     * calendar date, else in [localZone].
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
     * Converts an event timestamp to a ZonedDateTime: in UTC for an all-day event, else in
     * [localZone].
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
     * Returns the whole days from an all-day reminder's firing to the event's date, for the
     * notification subtitle ("Today", "Tomorrow", "In N days").
     *
     * It is a calendar-date difference (event date minus the fire day's local date), not a
     * millisecond duration, so it is timezone-stable and doesn't flip sign.
     *
     * @return 0 when it fires on the event's date ("Today"), 1 the day before ("Tomorrow"), N
     *   for N days before; a reminder firing after the event's date also returns 0.
     */
    fun allDayRelativeDays(
        occurrenceTimeUtcMidnight: Long,
        triggerTime: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val eventDate = eventTsToLocalDate(occurrenceTimeUtcMidnight, isAllDay = true, localZone)
        val fireDate = Instant.ofEpochMilli(triggerTime).atZone(localZone).toLocalDate()
        return ChronoUnit.DAYS.between(fireDate, eventDate).toInt().coerceAtLeast(0)
    }

    /**
     * Returns the instant (epoch ms) an all-day event begins for the user: its stored
     * UTC-midnight date read in UTC, re-anchored to 00:00 in [localZone].
     */
    fun allDayLocalMidnightMs(
        occurrenceTimeUtcMidnight: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val eventDate = eventTsToLocalDate(occurrenceTimeUtcMidnight, isAllDay = true, localZone)
        return eventDate.atStartOfDay(localZone).toInstant().toEpochMilli()
    }

    /**
     * Returns the trigger instant of an all-day reminder: [allDayLocalMidnightMs] plus
     * [signedOffsetMs].
     *
     * The offset is the RFC 5545 VALARM relative trigger from the event's midnight start,
     * negative before and positive after (PT9H is 9 AM on the event day). It is applied as an
     * exact duration (stored == fired == sent), matching the platform, so on a DST transition
     * day the wall-clock time shifts by the transition amount. RFC 5545 §3.3.6 makes days and
     * weeks nominal, but the offset here is exact milliseconds, so a day-based offset such as
     * -P1D lands an hour off its RFC wall-clock time across a DST change.
     *
     * @param occurrenceTimeUtcMidnight the event's start, stored as UTC midnight
     */
    fun allDayReminderTriggerTime(
        occurrenceTimeUtcMidnight: Long,
        signedOffsetMs: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Long {
        return allDayLocalMidnightMs(occurrenceTimeUtcMidnight, localZone) + signedOffsetMs
    }

    /**
     * Converts a timestamp to a YYYYMMDD day code (20260106 for Jan 6, 2026), in UTC for an
     * all-day event, else in [localZone].
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
     * Returns the day code of the last calendar day an event occupies, per RFC 5545 §3.6.1.
     *
     * DTEND is non-inclusive, so a timed event whose endTs is 00:00:00.000 in [localZone] ends
     * on the prior day; otherwise a 20:00 to 00:00 event would span two days (issue #209). An
     * all-day event, or one with endTs at or before startTs, uses endTs's own day: an all-day
     * end is stored as the inclusive last millisecond, 1 ms before the exclusive DTEND.
     */
    fun eventTsToEndDayCode(
        endTs: Long,
        startTs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (isAllDay || endTs <= startTs) {
            return eventTsToDayCode(endTs, isAllDay, localZone)
        }
        val zdt = Instant.ofEpochMilli(endTs).atZone(localZone)
        val date = if (zdt.toLocalTime() == LocalTime.MIDNIGHT) {
            zdt.toLocalDate().minusDays(1)
        } else {
            zdt.toLocalDate()
        }
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    /**
     * Returns true if the event occupies more than one calendar day per RFC 5545 §3.6.1.
     *
     * 09:00 to next-day 00:00 is one day: the end day comes from [eventTsToEndDayCode], which
     * owns the non-inclusive DTEND rule.
     */
    fun spansMultipleDays(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val startDay = eventTsToDayCode(startTs, isAllDay, localZone)
        val endDay = eventTsToEndDayCode(endTs, startTs, isAllDay, localZone)
        return endDay > startDay
    }

    /**
     * Returns how many calendar days the event occupies per RFC 5545 §3.6.1: 1 for a
     * single-day event, 09:00 to next-day 00:00 included. The end day comes from
     * [eventTsToEndDayCode].
     */
    fun calculateTotalDays(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val startDate = eventTsToLocalDate(startTs, isAllDay, localZone)
        val endDate = dayCodeToLocalDate(eventTsToEndDayCode(endTs, startTs, isAllDay, localZone))
        return ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
    }

    private fun dayCodeToLocalDate(dayCode: Int): LocalDate =
        LocalDate.of(dayCode / 10000, (dayCode % 10000) / 100, dayCode % 100)

    /** Returns the 1-based day ("Day 2") of a multi-day event that [selectedTs] falls on. */
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
     * Formats an event's date, in UTC for an all-day event, else in [localZone].
     *
     * Format event dates with this, not SimpleDateFormat, so all-day dates don't shift a day.
     *
     * @param pattern a DateTimeFormatter pattern; defaults to the locale's `yEEEMMMd` pattern
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

    /** Formats an event's date with the locale's `EEEMMMd` pattern ("Thu, Dec 25" in en-US). */
    fun formatEventDateShort(
        timestampMs: Long,
        isAllDay: Boolean,
        localZone: ZoneId = ZoneId.systemDefault()
    ): String {
        return formatEventDate(timestampMs, isAllDay, localizedPattern("EEEMMMd"), localZone)
    }

    /** Formats a timed event's time in [localZone] with [pattern]; an all-day event returns "". */
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
     * Converts a date picker's local-midnight timestamp to UTC midnight of the same date.
     *
     * All-day events are stored as UTC midnight (Jan 6 00:00 UTC for a picked Jan 6 00:00
     * local), matching iCal/CalDAV parsing.
     *
     * @param localZone the date picker's zone
     */
    fun localDateToUtcMidnight(
        localDateMillis: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val localDate = Instant.ofEpochMilli(localDateMillis).atZone(localZone).toLocalDate()
        return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /**
     * Converts a stored UTC-midnight timestamp to local midnight of the same date in
     * [localZone], the inverse of [localDateToUtcMidnight]. Used to show an all-day event's
     * dates in the form's date picker.
     */
    fun utcMidnightToLocalDate(
        utcMidnightMillis: Long,
        localZone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val utcDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return utcDate.atStartOfDay(localZone).toInstant().toEpochMilli()
    }

    /**
     * Returns 23:59:59.999 UTC of the day starting at [utcMidnightMillis], the stored endTs of
     * a single-day all-day event.
     */
    fun utcMidnightToEndOfDay(utcMidnightMillis: Long): Long {
        return utcMidnightMillis + (24 * 60 * 60 * 1000) - 1
    }

    // ==================== UTC Midnight Normalization ====================

    /**
     * Returns UTC midnight of [utcMillis]'s UTC day. Used on all-day device events'
     * occurrence times (ORIGINAL_INSTANCE_TIME, a this-and-future split point).
     */
    fun normalizeToUtcMidnight(utcMillis: Long): Long = (utcMillis / 86_400_000L) * 86_400_000L

    // ==================== RFC 5545 Duration Parsing ====================

    /**
     * Parses an RFC 5545 duration ("P1W", "P1D", "P2DT1H", "PT1H30M") to milliseconds.
     *
     * CalendarProvider stores DURATION instead of an end time for recurring events. Hours,
     * minutes and seconds are read only after a `T`.
     *
     * @return the duration, or null when [duration] is null or empty, doesn't start with `P`,
     *   has no digit, or overflows a Long; any other string yields whatever parts matched,
     *   possibly 0
     */
    fun parseDurationToMillis(duration: String?): Long? {
        if (duration.isNullOrEmpty()) return null
        if (!duration.startsWith("P")) return null

        try {
            var totalMs = 0L
            var remaining = duration.substring(1)

            // Use exact arithmetic throughout: an absurd (malformed) magnitude
            // must overflow into an exception the catch below turns into null,
            // never silently wrap to a garbage negative duration.

            val weekMatch = Regex("(\\d+)W").find(remaining)
            if (weekMatch != null) {
                val weeks = weekMatch.groupValues[1].toLong()
                totalMs = Math.addExact(totalMs, Math.multiplyExact(weeks, 7L * 24 * 60 * 60 * 1000))
                remaining = remaining.replace(weekMatch.value, "")
            }

            val dayMatch = Regex("(\\d+)D").find(remaining)
            if (dayMatch != null) {
                val days = dayMatch.groupValues[1].toLong()
                totalMs = Math.addExact(totalMs, Math.multiplyExact(days, 24L * 60 * 60 * 1000))
                remaining = remaining.replace(dayMatch.value, "")
            }

            if (remaining.startsWith("T")) {
                remaining = remaining.substring(1)

                val hourMatch = Regex("(\\d+)H").find(remaining)
                if (hourMatch != null) {
                    val hours = hourMatch.groupValues[1].toLong()
                    totalMs = Math.addExact(totalMs, Math.multiplyExact(hours, 60L * 60 * 1000))
                    remaining = remaining.replace(hourMatch.value, "")
                }

                val minMatch = Regex("(\\d+)M").find(remaining)
                if (minMatch != null) {
                    val minutes = minMatch.groupValues[1].toLong()
                    totalMs = Math.addExact(totalMs, Math.multiplyExact(minutes, 60L * 1000))
                    remaining = remaining.replace(minMatch.value, "")
                }

                val secMatch = Regex("(\\d+)S").find(remaining)
                if (secMatch != null) {
                    val seconds = secMatch.groupValues[1].toLong()
                    totalMs = Math.addExact(totalMs, Math.multiplyExact(seconds, 1000L))
                }
            }

            // A zero total is kept when the string has any digit; only a digitless one is null.
            if (totalMs == 0L && duration != "PT0M" && duration != "PT0S" && duration != "P0D") {
                if (!duration.contains(Regex("\\d"))) return null
            }

            return totalMs
        } catch (_: Exception) {
            return null
        }
    }

    // ==================== UI Display Formatters ====================

    /**
     * Formats [timestampMs] relative to [now] ("5 minutes ago", "2 days ago"), for each session
     * in the sync history sheet.
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
     * Formats a sync interval ("15 minutes", "1 hour", "1h 30m"); Long.MAX_VALUE is "Manual
     * only".
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
     * Formats an event's date and time on one line, all-day dates in UTC and timed ones in
     * the system zone:
     * - all-day single day: "Thu, Dec 25 · All day"
     * - all-day multi-day: "Thu, Dec 25 → Fri, Dec 26 · All day"
     * - timed: "Thu, Dec 25 · 2:00 PM - 3:00 PM"
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

    /** Formats [hour] (0-23) and [minute] with [pattern] ("2:30 PM"), for the date-time pickers. */
    fun formatTime(hour: Int, minute: Int, pattern: String = "h:mm a"): String {
        val localTime = java.time.LocalTime.of(hour, minute)
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        return localTime.format(formatter)
    }

    // ==================== Event Past Check ====================

    /**
     * Returns true if an occurrence has ended: a timed one when [endTs] is before [nowMs], an
     * all-day one only once [todayDayCode] (local) is past [endDay].
     *
     * An all-day endTs is the end of its UTC day, which a UTC-6 user reaches at 6 PM local,
     * so comparing it with the clock would gray out today's all-day events in the evening.
     *
     * @param endDay the occurrence's end day code (YYYYMMDD)
     */
    fun isEventPast(
        endTs: Long,
        endDay: Int,
        isAllDay: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        todayDayCode: Int = eventTsToDayCode(System.currentTimeMillis(), isAllDay = false)
    ): Boolean {
        return if (isAllDay) {
            endDay < todayDayCode
        } else {
            endTs < nowMs
        }
    }
}
