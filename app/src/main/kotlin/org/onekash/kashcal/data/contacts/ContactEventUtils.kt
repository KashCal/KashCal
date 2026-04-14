package org.onekash.kashcal.data.contacts

import java.util.Calendar
import java.util.TimeZone

/**
 * Data class representing a parsed date from a contact event (birthday or anniversary).
 *
 * @param month Month (1-12)
 * @param day Day of month (1-31)
 * @param year Event year (null if not available)
 */
data class ContactEventDate(
    val month: Int,
    val day: Int,
    val year: Int?
)

/**
 * Shared sync result for contact event repositories (birthday and anniversary).
 */
sealed class ContactEventSyncResult {
    data class Success(val added: Int, val updated: Int, val deleted: Int) : ContactEventSyncResult()
    data class Error(val message: String) : ContactEventSyncResult()
}

/**
 * Utility functions for contact event parsing and formatting.
 *
 * Handles:
 * - Parsing date strings from Android Contacts (multiple formats)
 * - Year calculation from event year and occurrence date
 * - Birthday and anniversary event title formatting with ordinal suffixes
 * - Event year encoding/decoding in event description
 */
object ContactEventUtils {

    // Description field format for storing event year.
    // Retained as "birthYear:" for backward compatibility with existing birthday events.
    // Anniversary events also use this prefix — semantically odd but functionally correct.
    private const val EVENT_YEAR_PREFIX = "birthYear:"

    /**
     * Parse a date string from Android Contacts.
     *
     * Supports formats:
     * - "--MM-DD" (no year, RFC 6350 vCard format)
     * - "YYYY-MM-DD" (full date)
     * - "YYYY/MM/DD" (alternative format)
     * - "MM/DD/YYYY" (US format)
     * - "DD/MM/YYYY" (European format - ambiguous, assumes US)
     *
     * @param dateString The date string from contacts
     * @return Parsed ContactEventDate or null if unparseable
     */
    fun parseContactDate(dateString: String?): ContactEventDate? {
        if (dateString.isNullOrBlank()) return null

        val trimmed = dateString.trim()

        // Format: --MM-DD (no year, RFC 6350)
        if (trimmed.startsWith("--")) {
            val parts = trimmed.substring(2).split("-")
            if (parts.size == 2) {
                val month = parts[0].toIntOrNull()
                val day = parts[1].toIntOrNull()
                if (month != null && day != null && isValidMonthDay(month, day)) {
                    return ContactEventDate(month, day, null)
                }
            }
            return null
        }

        // Format: YYYY-MM-DD or YYYY/MM/DD
        val isoPattern = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")
        isoPattern.matchEntire(trimmed)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            val day = match.groupValues[3].toIntOrNull()
            if (year != null && month != null && day != null && isValidDate(year, month, day)) {
                return ContactEventDate(month, day, year)
            }
        }

        // Format: MM/DD/YYYY or MM-DD-YYYY (US format)
        val usPattern = Regex("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")
        usPattern.matchEntire(trimmed)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()
            if (year != null && month != null && day != null && isValidDate(year, month, day)) {
                return ContactEventDate(month, day, year)
            }
        }

        return null
    }

    /**
     * Calculate years elapsed from an event year to the occurrence timestamp.
     *
     * @param eventYear The year of the event (birth year, anniversary year, etc.)
     * @param occurrenceTs Occurrence timestamp in milliseconds
     * @return Years elapsed at the time of the occurrence
     */
    fun calculateYearsSince(eventYear: Int, occurrenceTs: Long): Int {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = occurrenceTs
        val occurrenceYear = calendar.get(Calendar.YEAR)
        return occurrenceYear - eventYear
    }

    /**
     * Format ordinal suffix for a number (1st, 2nd, 3rd, 4th, etc.)
     *
     * @param n The number
     * @return Formatted string with ordinal suffix
     */
    fun formatOrdinal(n: Int): String {
        return when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
    }

    /**
     * Format birthday event title with optional age.
     *
     * @param displayName Contact display name
     * @param birthYear Birth year (null if unknown)
     * @param occurrenceTs Occurrence timestamp for age calculation
     * @return Formatted title like "John Smith's 30th Birthday" or "John Smith's Birthday"
     */
    fun formatBirthdayTitle(displayName: String, birthYear: Int?, occurrenceTs: Long): String {
        return if (birthYear != null) {
            val age = calculateYearsSince(birthYear, occurrenceTs)
            if (age > 0 && age < 150) {
                "$displayName's ${formatOrdinal(age)} Birthday"
            } else {
                "$displayName's Birthday"
            }
        } else {
            "$displayName's Birthday"
        }
    }

    /**
     * Format anniversary event title with optional year count.
     *
     * @param displayName Contact display name
     * @param anniversaryYear Anniversary year (null if unknown)
     * @param occurrenceTs Occurrence timestamp for year calculation
     * @return Formatted title like "Alice's 10th Anniversary" or "Alice's Anniversary"
     */
    fun formatAnniversaryTitle(displayName: String, anniversaryYear: Int?, occurrenceTs: Long): String {
        return if (anniversaryYear != null) {
            val years = calculateYearsSince(anniversaryYear, occurrenceTs)
            if (years > 0 && years < 150) {
                "$displayName's ${formatOrdinal(years)} Anniversary"
            } else {
                "$displayName's Anniversary"
            }
        } else {
            "$displayName's Anniversary"
        }
    }

    /**
     * Encode event year into event description.
     *
     * @param eventYear The event year (null if unknown)
     * @return Description string with encoded year, or null
     */
    fun encodeEventYear(eventYear: Int?): String? {
        return eventYear?.let { "$EVENT_YEAR_PREFIX$it" }
    }

    /**
     * Decode event year from event description.
     *
     * @param description Event description that may contain event year
     * @return Extracted year or null
     */
    fun decodeEventYear(description: String?): Int? {
        if (description == null) return null
        val prefix = EVENT_YEAR_PREFIX
        val index = description.indexOf(prefix)
        if (index == -1) return null

        val start = index + prefix.length
        val end = description.indexOfAny(charArrayOf('\n', ' ', '\t'), start).takeIf { it != -1 } ?: description.length
        return description.substring(start, end).toIntOrNull()
    }

    /** RRULE for yearly recurrence (birthdays, anniversaries). */
    const val YEARLY_RRULE = "FREQ=YEARLY;INTERVAL=1"

    /**
     * Calculate the timestamp for a date in a given year.
     *
     * All-day event: returns midnight UTC of the date.
     *
     * @param month Month (1-12)
     * @param day Day (1-31)
     * @param year The year to calculate for
     * @return Timestamp in milliseconds
     */
    fun getEventTimestamp(month: Int, day: Int, year: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month - 1) // Calendar.MONTH is 0-based
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Get the DTSTART timestamp for a contact event (birthday/anniversary).
     *
     * Uses the event's known year if available. If the year is unknown (null),
     * uses (currentYear - 1) to ensure the RRULE FREQ=YEARLY generates
     * occurrences for the current year. Special case: Feb 29 with unknown year
     * uses the nearest past leap year to prevent java.util.Calendar from
     * silently rolling to March 1.
     *
     * @param month Month (1-12)
     * @param day Day (1-31)
     * @param eventYear The known event year, or null if unknown
     * @return Timestamp at UTC midnight for the computed DTSTART date
     */
    fun getStartTimestamp(month: Int, day: Int, eventYear: Int?): Long {
        val year = if (eventYear != null) {
            eventYear
        } else {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            if (month == 2 && day == 29) {
                // Find nearest past leap year to avoid Calendar rolling Feb 29 → Mar 1
                var candidate = currentYear - 1
                while (!isLeapYear(candidate)) {
                    candidate--
                }
                candidate
            } else {
                currentYear - 1
            }
        }
        return getEventTimestamp(month, day, year)
    }

    /**
     * Get the next upcoming event timestamp from today.
     *
     * Uses local timezone for date comparison to correctly determine if
     * today's date has passed. The returned timestamp is still UTC
     * midnight (correct for all-day events per RFC 5545).
     *
     * @param month Month (1-12)
     * @param day Day (1-31)
     * @return Timestamp of the next occurrence (UTC midnight)
     */
    @Deprecated("Use getStartTimestamp() instead — getNextEventTimestamp sets DTSTART to next year for past-month dates, causing RRULE to skip the current year")
    fun getNextEventTimestamp(month: Int, day: Int): Long {
        val now = Calendar.getInstance()  // Local timezone for date comparison
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH) + 1  // Calendar.MONTH is 0-based
        val currentDay = now.get(Calendar.DAY_OF_MONTH)

        // Compare calendar dates in local time (not timestamps)
        // This ensures today's event shows up even late in the day
        val isDateTodayOrLater = when {
            month > currentMonth -> true
            month < currentMonth -> false
            else -> day >= currentDay  // Same month, compare days
        }

        return if (isDateTodayOrLater) {
            getEventTimestamp(month, day, currentYear)
        } else {
            // Date already passed this year, use next year
            getEventTimestamp(month, day, currentYear + 1)
        }
    }

    /**
     * Convert reminder minutes to ISO 8601 duration format.
     * Positive minutes = before event (negative trigger in iCal).
     */
    fun minutesToIsoDuration(minutes: Int): String {
        return when {
            minutes <= 0 -> "PT0M"
            minutes < 60 -> "-PT${minutes}M"
            minutes < 1440 -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0) "-PT${hours}H" else "-PT${hours}H${mins}M"
            }
            minutes < 10080 -> { // Less than 1 week
                val days = minutes / 1440
                val hours = (minutes % 1440) / 60
                if (hours == 0) "-P${days}D" else "-P${days}DT${hours}H"
            }
            else -> {
                val weeks = minutes / 10080
                "-P${weeks}W"
            }
        }
    }

    private fun isValidMonthDay(month: Int, day: Int): Boolean {
        return month in 1..12 && day in 1..31
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (year < 1900 || year > 2100) return false
        if (month < 1 || month > 12) return false
        if (day < 1 || day > 31) return false

        // Basic day-of-month validation
        val maxDays = when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDays
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}
