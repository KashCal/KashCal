package org.onekash.icaldav.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * DateTime that preserves timezone information from iCalendar.
 *
 * Handles three iCalendar date/time formats:
 * - UTC: 20231215T140000Z (ends with Z)
 * - Local with TZID: DTSTART;TZID=America/New_York:20231215T140000
 * - Floating: 20231215T140000 (no Z, no TZID - uses device timezone)
 * - Date only: 20231215 (all-day events)
 *
 * Production-tested with various CalDAV servers for reliable timezone handling.
 */
data class ICalDateTime(
    val timestamp: Long,              // Unix timestamp in milliseconds
    val timezone: ZoneId?,            // null for UTC or floating
    val isUtc: Boolean,               // true if originally specified as UTC (Z suffix)
    val isDate: Boolean               // true for DATE (all-day), false for DATE-TIME
) {
    /**
     * Convert to LocalDate (for all-day events or date comparison).
     *
     * For DATE values (isDate=true): Uses UTC to preserve the calendar date.
     * RFC 5545 DATE values represent calendar dates, not moments in time.
     * Using local timezone would shift the date incorrectly:
     *   Jan 23 00:00 UTC → Jan 22 19:00 EST → Jan 22 (WRONG)
     *
     * For DATE-TIME values: Uses stored timezone (or system default for floating).
     */
    fun toLocalDate(): LocalDate {
        // DATE values must use UTC to preserve the calendar date
        val zone = if (isDate) ZoneOffset.UTC else (timezone ?: ZoneId.systemDefault())
        return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    }

    /**
     * Convert to Instant (for precise timestamp operations).
     */
    fun toInstant(): Instant = Instant.ofEpochMilli(timestamp)

    /**
     * Convert to ZonedDateTime with preserved or system timezone.
     *
     * For DATE values: Uses UTC to preserve the calendar date.
     * For DATE-TIME values: Uses stored timezone (or system default for floating).
     */
    fun toZonedDateTime(): ZonedDateTime {
        // DATE values must use UTC to preserve the calendar date
        val zone = if (isDate) ZoneOffset.UTC else (timezone ?: ZoneId.systemDefault())
        return Instant.ofEpochMilli(timestamp).atZone(zone)
    }

    /**
     * Convert to LocalDateTime in the event's timezone.
     */
    fun toLocalDateTime(): LocalDateTime = toZonedDateTime().toLocalDateTime()

    /**
     * Get day code in format YYYYMMDD for calendar grid matching.
     * Critical for RECURRENCE-ID date matching.
     */
    fun toDayCode(): String {
        val local = toLocalDate()
        return "%04d%02d%02d".format(local.year, local.monthValue, local.dayOfMonth)
    }

    /**
     * Format as iCalendar string.
     */
    fun toICalString(): String {
        return if (isDate) {
            // DATE format: 20231215
            DateTimeFormatter.BASIC_ISO_DATE.format(toLocalDate())
        } else if (isUtc) {
            // UTC format: 20231215T140000Z
            val utc = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC)
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(utc)
        } else {
            // Local format: 20231215T140000
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(toZonedDateTime())
        }
    }

    companion object {
        private val UTC_PATTERN = Regex("""(\d{8}T\d{6})Z""")
        private val LOCAL_PATTERN = Regex("""(\d{8}T\d{6})""")
        private val DATE_PATTERN = Regex("""(\d{8})""")

        /**
         * Parse iCalendar datetime string.
         *
         * @param value The datetime string (e.g., "20231215T140000Z", "20231215")
         * @param tzid Optional timezone ID from TZID parameter
         * @return Parsed ICalDateTime
         * @throws IllegalArgumentException if format is invalid
         */
        fun parse(value: String, tzid: String? = null): ICalDateTime {
            val trimmed = value.trim()

            // UTC format: 20231215T140000Z
            if (trimmed.endsWith("Z")) {
                val match = UTC_PATTERN.matchEntire(trimmed)
                    ?: throw IllegalArgumentException("Invalid UTC datetime: $value")
                val dt = LocalDateTime.parse(match.groupValues[1], DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val instant = dt.toInstant(ZoneOffset.UTC)
                return ICalDateTime(
                    timestamp = instant.toEpochMilli(),
                    timezone = null,
                    isUtc = true,
                    isDate = false
                )
            }

            // DATE format: 20231215 (all-day events)
            // RFC 5545: DATE values are calendar dates without time zone.
            // Store as UTC midnight to preserve the calendar date across time zones.
            // Example: "20260123" → Jan 23 00:00:00 UTC (not local midnight)
            // This ensures consistent day calculation regardless of device timezone.
            if (trimmed.length == 8 && DATE_PATTERN.matches(trimmed)) {
                val date = LocalDate.parse(trimmed, DateTimeFormatter.BASIC_ISO_DATE)
                // Use UTC midnight to preserve calendar date
                val instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
                return ICalDateTime(
                    timestamp = instant.toEpochMilli(),
                    timezone = null,  // UTC
                    isUtc = true,     // Stored as UTC
                    isDate = true
                )
            }

            // Local datetime format: 20231215T140000
            if (LOCAL_PATTERN.matches(trimmed)) {
                val dt = LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val zone = tzid?.let { parseTimezone(it) } ?: ZoneId.systemDefault()
                val instant = dt.atZone(zone).toInstant()
                return ICalDateTime(
                    timestamp = instant.toEpochMilli(),
                    timezone = zone,
                    isUtc = false,
                    isDate = false
                )
            }

            throw IllegalArgumentException("Invalid iCalendar datetime format: $value")
        }

        /**
         * Create current UTC timestamp.
         */
        fun now(): ICalDateTime {
            return ICalDateTime(
                timestamp = System.currentTimeMillis(),
                timezone = null,
                isUtc = true,
                isDate = false
            )
        }

        /**
         * Create from Unix timestamp (milliseconds).
         */
        fun fromTimestamp(
            timestamp: Long,
            timezone: ZoneId? = null,
            isDate: Boolean = false
        ): ICalDateTime {
            return ICalDateTime(
                timestamp = timestamp,
                timezone = timezone,
                isUtc = timezone == null,
                isDate = isDate
            )
        }

        /**
         * Create from LocalDate (for all-day events).
         *
         * RFC 5545: DATE values are calendar dates without time zone.
         * Store as UTC midnight to preserve the calendar date across all timezones.
         *
         * @param date The calendar date to store
         * @param timezone Ignored for DATE values (kept for API compatibility)
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromLocalDate(date: LocalDate, timezone: ZoneId = ZoneId.systemDefault()): ICalDateTime {
            // Use UTC midnight to preserve the calendar date (RFC 5545)
            val instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            return ICalDateTime(
                timestamp = instant.toEpochMilli(),
                timezone = null,  // UTC
                isUtc = true,     // Stored as UTC
                isDate = true
            )
        }

        /**
         * Create from ZonedDateTime.
         *
         * For DATE values (isDate=true): Extracts the LocalDate and stores as UTC midnight
         * to preserve the calendar date across timezones.
         *
         * For DATE-TIME values: Uses the exact instant and preserves the timezone.
         */
        fun fromZonedDateTime(zdt: ZonedDateTime, isDate: Boolean = false): ICalDateTime {
            return if (isDate) {
                // For DATE values, extract calendar date and store as UTC midnight
                val instant = zdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
                ICalDateTime(
                    timestamp = instant.toEpochMilli(),
                    timezone = null,  // UTC
                    isUtc = true,     // Stored as UTC
                    isDate = true
                )
            } else {
                ICalDateTime(
                    timestamp = zdt.toInstant().toEpochMilli(),
                    timezone = zdt.zone,
                    isUtc = zdt.zone == ZoneOffset.UTC,
                    isDate = false
                )
            }
        }

        /**
         * Pluggable resolver for non-standard timezone IDs (e.g., Windows timezone names).
         * Set by app layer at startup. Called after ZoneId.of() fails, before properties fallback.
         * Must be thread-safe. Set once at startup.
         */
        @Volatile
        var customTimezoneResolver: ((String) -> ZoneId?)? = null

        /**
         * Lazily loaded case-insensitive alias map from ical4j's msTimezoneNames properties file.
         * Maps Windows timezone names → IANA IDs. Entries with invalid IANA targets are skipped.
         */
        internal val timezoneAliases: Map<String, String> by lazy {
            loadTimezoneAliases()
        }

        private fun loadTimezoneAliases(): Map<String, String> {
            val aliases = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            loadPropertiesFile("net/fortuna/ical4j/transform/compliance/msTimezoneNames", aliases)
            return aliases
        }

        private fun loadPropertiesFile(path: String, target: MutableMap<String, String>) {
            try {
                val props = java.util.Properties()
                ICalDateTime::class.java.classLoader
                    ?.getResourceAsStream(path)
                    ?.use { props.load(it) }
                    ?: return
                for ((key, value) in props) {
                    val tzid = value.toString()
                    try {
                        ZoneId.of(tzid)
                        target[key.toString()] = tzid
                    } catch (_: Exception) { /* skip invalid entries like US/Hwaii */ }
                }
            } catch (_: Exception) { /* classpath not available */ }
        }

        /**
         * Parse timezone ID with multi-layer resolution.
         *
         * Resolution chain:
         * 1. Standard IANA ID (ZoneId.of)
         * 2. Custom resolver (e.g., Android ICU getIDForWindowsID)
         * 3. Properties file aliases (ical4j msTimezoneNames)
         * 4. System default fallback
         */
        private fun parseTimezone(tzid: String): ZoneId {
            // 1. Try standard IANA ID (handles canonical + legacy aliases like US/Eastern)
            try { return ZoneId.of(tzid) } catch (_: Exception) {}

            // 2. Try custom resolver (e.g., Android ICU getIDForWindowsID)
            customTimezoneResolver?.invoke(tzid)?.let { return it }

            // 3. Try properties file aliases (msTimezoneNames — Windows timezone names)
            timezoneAliases[tzid]?.let {
                try { return ZoneId.of(it) } catch (_: Exception) {}
            }

            // 4. Fall back to system default
            return ZoneId.systemDefault()
        }
    }
}
