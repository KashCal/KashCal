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
 * Holds an iCalendar DATE or DATE-TIME with the time zone it was written in.
 *
 * [parse] reads four forms:
 * - UTC: 20231215T140000Z
 * - Local with TZID: DTSTART;TZID=America/New_York:20231215T140000
 * - Floating: 20231215T140000, no Z and no TZID; read in the device time zone
 * - DATE: 20231215, for all-day events; stored as UTC midnight
 */
data class ICalDateTime(
    val timestamp: Long,              // Unix timestamp in milliseconds
    val timezone: ZoneId?,            // null for UTC and DATE; a floating time gets the device zone
    val isUtc: Boolean,               // true for a Z time and for DATE values
    val isDate: Boolean               // true for DATE (all-day), false for DATE-TIME
) {
    /**
     * Returns the calendar date: in UTC for a DATE value, else in [timezone] or the system default.
     *
     * A DATE value is a calendar date, not a moment (RFC 5545 §3.3.4), stored as UTC midnight,
     * so it must be read in UTC. Read in the local zone it shifts the day: Jan 23 00:00 UTC is
     * Jan 22 19:00 EST, so Jan 22.
     */
    fun toLocalDate(): LocalDate {
        val zone = if (isDate) ZoneOffset.UTC else (timezone ?: ZoneId.systemDefault())
        return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    }

    fun toInstant(): Instant = Instant.ofEpochMilli(timestamp)

    /** Returns the moment in the same zone as [toLocalDate] uses. */
    fun toZonedDateTime(): ZonedDateTime {
        val zone = if (isDate) ZoneOffset.UTC else (timezone ?: ZoneId.systemDefault())
        return Instant.ofEpochMilli(timestamp).atZone(zone)
    }

    /** Returns the wall-clock time in the zone [toZonedDateTime] uses. */
    fun toLocalDateTime(): LocalDateTime = toZonedDateTime().toLocalDateTime()

    /**
     * Returns [toLocalDate] as YYYYMMDD. `RRuleExpander` matches EXDATEs and RDATEs to
     * occurrences by it, so a zone change here moves which day they hit.
     */
    fun toDayCode(): String {
        val local = toLocalDate()
        return "%04d%02d%02d".format(local.year, local.monthValue, local.dayOfMonth)
    }

    /**
     * Formats the value as DATE, UTC (Z) or local time; the TZID parameter isn't included, so a
     * caller writing a zoned time adds it.
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
         * Parses one of the forms in the class doc, such as "20231215T140000Z" or "20231215".
         *
         * @param tzid TZID parameter for a local time, resolved by [parseTimezone]; ignored for
         *   UTC and DATE values. Without it a local time is read in the device zone.
         * @throws IllegalArgumentException if the format is invalid
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

            // DATE format: 20231215 (all-day events). A DATE has no time zone (RFC 5545), so it is
            // stored as UTC midnight: "20260123" is Jan 23 00:00:00 UTC, not local midnight, and
            // gives the same day in every device zone.
            if (trimmed.length == 8 && DATE_PATTERN.matches(trimmed)) {
                val date = LocalDate.parse(trimmed, DateTimeFormatter.BASIC_ISO_DATE)
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

        /** Returns the current time as a UTC DATE-TIME. */
        fun now(): ICalDateTime {
            return ICalDateTime(
                timestamp = System.currentTimeMillis(),
                timezone = null,
                isUtc = true,
                isDate = false
            )
        }

        /** Creates a value from epoch milliseconds; a null [timezone] makes it UTC. */
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
         * Creates a DATE value for [date], stored as UTC midnight like [parse] does.
         *
         * @param timezone Ignored; kept so existing callers compile.
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromLocalDate(date: LocalDate, timezone: ZoneId = ZoneId.systemDefault()): ICalDateTime {
            val instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            return ICalDateTime(
                timestamp = instant.toEpochMilli(),
                timezone = null,  // UTC
                isUtc = true,     // Stored as UTC
                isDate = true
            )
        }

        /**
         * Creates a value from [zdt]. With [isDate] it keeps only the date in [zdt]'s zone, stored
         * as UTC midnight; otherwise it keeps the instant and the zone.
         */
        fun fromZonedDateTime(zdt: ZonedDateTime, isDate: Boolean = false): ICalDateTime {
            return if (isDate) {
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
         * Resolves a non-standard TZID such as a Windows zone name; [parseTimezone] calls it after
         * `ZoneId.of` fails and before [timezoneAliases]. Must be thread-safe; set once at
         * startup (`KashCalApplication.onCreate` sets one backed by Android ICU).
         */
        @Volatile
        var customTimezoneResolver: ((String) -> ZoneId?)? = null

        /**
         * Maps Windows zone names to IANA IDs, ignoring case, from ical4j's msTimezoneNames
         * file. Loaded lazily; entries whose target `ZoneId.of` rejects are skipped, and a
         * missing file gives an empty map.
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
                    } catch (_: Exception) { /* skip a target ZoneId.of rejects */ }
                }
            } catch (_: Exception) { /* unreadable file: no aliases */ }
        }

        /**
         * Resolves [tzid], trying in order:
         * 1. `ZoneId.of`, which takes IANA IDs and legacy aliases like US/Eastern
         * 2. [customTimezoneResolver]
         * 3. [timezoneAliases]
         * 4. the system default, when none of these resolve it
         */
        private fun parseTimezone(tzid: String): ZoneId {
            try { return ZoneId.of(tzid) } catch (_: Exception) {}

            customTimezoneResolver?.invoke(tzid)?.let { return it }

            timezoneAliases[tzid]?.let {
                try { return ZoneId.of(it) } catch (_: Exception) {}
            }

            return ZoneId.systemDefault()
        }
    }
}
