package org.onekash.kashcal.util

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Timezone helpers for events and the timezone picker: the picker's zone list and search,
 * abbreviations, offsets from the device zone, resolving and canonicalizing an event's timezone
 * ID, and a device-time preview.
 *
 * @see DateTimeUtils for date/time formatting
 */
object TimezoneUtils {

    private val UNAMBIGUOUS_SHORT_IDS = mapOf("EST" to "-05:00", "MST" to "-07:00", "HST" to "-10:00")

    /**
     * A timezone as the picker shows it.
     *
     * @param zoneId IANA timezone ID, e.g. "America/New_York"
     * @param abbreviation short name from [getAbbreviation], e.g. "EST"
     * @param displayName city name, e.g. "New York"
     * @param offsetFromDevice offset from the device zone, formatted by [getOffsetFromDevice]
     * @param countryName country from ICU's zone region, in the device locale, e.g. "Germany"
     */
    data class TimezoneInfo(
        val zoneId: String,
        val abbreviation: String,
        val displayName: String,
        val offsetFromDevice: String,
        val countryName: String? = null
    )

    // Built once, on first use; offsets are those at build time.
    private val allTimezones: List<TimezoneInfo> by lazy {
        buildTimezoneList()
    }

    // Display names that replace the city taken from the zone ID.
    private val displayNameOverrides = mapOf(
        "America/New_York" to "New York",
        "America/Los_Angeles" to "Los Angeles",
        "America/Chicago" to "Chicago",
        "America/Denver" to "Denver",
        "America/Phoenix" to "Phoenix",
        "Europe/London" to "London",
        "Europe/Paris" to "Paris",
        "Europe/Berlin" to "Berlin",
        "Europe/Moscow" to "Moscow",
        "Asia/Tokyo" to "Tokyo",
        "Asia/Shanghai" to "Shanghai",
        "Asia/Hong_Kong" to "Hong Kong",
        "Asia/Singapore" to "Singapore",
        "Asia/Seoul" to "Seoul",
        "Asia/Dubai" to "Dubai",
        "Asia/Kolkata" to "Mumbai",
        "Australia/Sydney" to "Sydney",
        "Australia/Melbourne" to "Melbourne",
        "Pacific/Auckland" to "Auckland",
        "Pacific/Honolulu" to "Honolulu"
    )

    // Extra city names search matches for a zone, so a user can find a zone by any major city
    // in it, not only the one in its IANA ID.
    private val cityAliases = mapOf(
        // India (all cities use Asia/Kolkata)
        "Asia/Kolkata" to listOf("Mumbai", "New Delhi", "Delhi", "Bangalore", "Bengaluru", "Chennai", "Kolkata", "Hyderabad", "Pune", "Ahmedabad"),
        // USA - Eastern
        "America/New_York" to listOf("New York", "NYC", "Boston", "Philadelphia", "Atlanta", "Miami", "Washington DC", "Detroit"),
        // USA - Central
        "America/Chicago" to listOf("Chicago", "Dallas", "Houston", "Minneapolis", "St Louis", "New Orleans", "Austin", "San Antonio"),
        // USA - Mountain
        "America/Denver" to listOf("Denver", "Salt Lake City", "Albuquerque"),
        // USA - Pacific
        "America/Los_Angeles" to listOf("Los Angeles", "LA", "San Francisco", "Seattle", "San Diego", "Portland", "Las Vegas"),
        // UK/Ireland
        "Europe/London" to listOf("London", "Edinburgh", "Manchester", "Birmingham", "Dublin", "Belfast"),
        // Central Europe
        "Europe/Paris" to listOf("Paris", "Madrid", "Barcelona", "Rome", "Milan", "Amsterdam", "Brussels", "Vienna"),
        "Europe/Berlin" to listOf("Berlin", "Munich", "Frankfurt", "Hamburg", "Zurich", "Prague", "Warsaw", "Stockholm"),
        // China
        "Asia/Shanghai" to listOf("Shanghai", "Beijing", "Shenzhen", "Guangzhou", "Chengdu", "Hangzhou", "Wuhan", "Nanjing"),
        // Japan
        "Asia/Tokyo" to listOf("Tokyo", "Osaka", "Kyoto", "Nagoya", "Yokohama", "Sapporo"),
        // Korea
        "Asia/Seoul" to listOf("Seoul", "Busan", "Incheon"),
        // Australia
        "Australia/Sydney" to listOf("Sydney", "Canberra", "Brisbane"),
        "Australia/Melbourne" to listOf("Melbourne", "Adelaide"),
        // Middle East
        "Asia/Dubai" to listOf("Dubai", "Abu Dhabi", "Doha", "Kuwait City", "Riyadh"),
        // Southeast Asia
        "Asia/Singapore" to listOf("Singapore", "Kuala Lumpur"),
        "Asia/Bangkok" to listOf("Bangkok", "Jakarta", "Hanoi", "Ho Chi Minh City"),
        "Asia/Hong_Kong" to listOf("Hong Kong", "Macau", "Taipei"),
        // South America
        "America/Sao_Paulo" to listOf("Sao Paulo", "Rio de Janeiro", "Brasilia"),
        "America/Argentina/Buenos_Aires" to listOf("Buenos Aires"),
        "America/Mexico_City" to listOf("Mexico City", "Guadalajara", "Monterrey"),
        // Africa
        "Africa/Cairo" to listOf("Cairo"),
        "Africa/Johannesburg" to listOf("Johannesburg", "Cape Town", "Pretoria"),
        "Africa/Lagos" to listOf("Lagos", "Accra"),
        // Canada
        "America/Toronto" to listOf("Toronto", "Montreal", "Ottawa"),
        "America/Vancouver" to listOf("Vancouver", "Calgary", "Edmonton"),
        // Russia
        "Europe/Moscow" to listOf("Moscow", "St Petersburg")
    )

    /**
     * Returns the picker's zones sorted by UTC offset: region IDs containing `/`, without the
     * `Etc/` and `SystemV/` zones.
     */
    fun getAvailableTimezones(): List<TimezoneInfo> = allTimezones

    /**
     * Returns the zones whose display name, abbreviation, zone ID, country or city aliases
     * contain [query], ignoring case: "tokyo", "EST", "asia/tok", "germany", "dallas".
     *
     * @return the first 10 matches in UTC-offset order, or none for a blank query
     */
    fun searchTimezones(query: String): List<TimezoneInfo> {
        if (query.isBlank()) return emptyList()

        val normalizedQuery = query.trim().lowercase()

        return allTimezones.filter { tz ->
            tz.displayName.lowercase().contains(normalizedQuery) ||
            tz.abbreviation.lowercase().contains(normalizedQuery) ||
            tz.zoneId.lowercase().contains(normalizedQuery) ||
            tz.countryName?.lowercase()?.contains(normalizedQuery) == true ||
            cityAliases[tz.zoneId]?.any { city ->
                city.lowercase().contains(normalizedQuery)
            } == true
        }.take(10)
    }

    /**
     * Returns the zone's short name at [instant], e.g. "EST", "EDT" or "JST", formatted with
     * `Locale.US`. An invalid [zoneId] gives the first three characters of its last segment,
     * uppercased.
     */
    fun getAbbreviation(zoneId: String, instant: Instant = Instant.now()): String {
        return try {
            val zone = ZoneId.of(zoneId)
            val zdt = instant.atZone(zone)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US)
            zdt.format(formatter)
        } catch (_: Exception) {
            zoneId.substringAfterLast("/").take(3).uppercase()
        }
    }

    /**
     * Formats the zone's offset from the device zone at [instant]: "Device" for the device's
     * zone or a zero difference, "+5h" or "-3h" for whole hours, "+5:30" or "-3:30" otherwise,
     * and "?" for an invalid [zoneId].
     */
    fun getOffsetFromDevice(zoneId: String, instant: Instant = Instant.now()): String {
        val deviceZoneId = ZoneId.systemDefault()

        if (zoneId == deviceZoneId.id) return "Device"

        return try {
            val targetZone = ZoneId.of(zoneId)
            val deviceOffset = deviceZoneId.rules.getOffset(instant).totalSeconds
            val targetOffset = targetZone.rules.getOffset(instant).totalSeconds

            val diffSeconds = targetOffset - deviceOffset
            val diffHours = diffSeconds / 3600
            val diffMinutes = (diffSeconds % 3600) / 60

            when {
                diffSeconds == 0 -> "Device"
                diffMinutes == 0 -> {
                    if (diffHours > 0) "+${diffHours}h" else "${diffHours}h"
                }
                else -> {
                    val sign = if (diffSeconds > 0) "+" else "-"
                    val absHours = kotlin.math.abs(diffHours)
                    val absMinutes = kotlin.math.abs(diffMinutes)
                    "$sign${absHours}:${absMinutes.toString().padStart(2, '0')}"
                }
            }
        } catch (_: Exception) {
            "?"
        }
    }

    /**
     * Converts [epochMs] from [fromZone] to [toZone] (null means the device zone). The
     * conversion keeps the instant, so the result always equals [epochMs]. Throws for an invalid
     * zone ID.
     */
    fun convertTime(epochMs: Long, fromZone: String?, toZone: String?): Long {
        val from = if (fromZone != null) ZoneId.of(fromZone) else ZoneId.systemDefault()
        val to = if (toZone != null) ZoneId.of(toZone) else ZoneId.systemDefault()

        if (from == to) return epochMs

        val instant = Instant.ofEpochMilli(epochMs)
        val sourceZdt = instant.atZone(from)
        val targetZdt = sourceZdt.withZoneSameInstant(to)

        return targetZdt.toInstant().toEpochMilli()
    }

    /** Returns the picker entry for [zoneId], or null when [getAvailableTimezones] lacks it. */
    fun getTimezoneInfo(zoneId: String): TimezoneInfo? {
        return allTimezones.find { it.zoneId == zoneId }
    }

    /** Returns the device's current timezone ID, e.g. "America/New_York". */
    fun getDeviceTimezone(): String = ZoneId.systemDefault().id

    /**
     * Returns the zone for an event timezone ID, or null when the ID is blank or not
     * recognised. Accepts IANA IDs and offset IDs ("UTC+05:00"), plus the legacy fixed-offset
     * names "EST", "MST" and "HST". Other three-letter names are ambiguous ("BST", "IST", "CST"
     * each name several zones), so they are treated as unrecognised rather than guessed.
     */
    fun resolveZoneOrNull(id: String?): ZoneId? {
        if (id.isNullOrBlank()) return null
        return try {
            ZoneId.of(id)
        } catch (_: Exception) {
            UNAMBIGUOUS_SHORT_IDS[id]?.let { ZoneId.of(it) }
        }
    }

    /** Returns the zone for an event timezone ID, falling back to the device's zone. */
    fun resolveZone(id: String?): ZoneId = resolveZoneOrNull(id) ?: ZoneId.systemDefault()

    /** Returns true when `ZoneId.of` accepts [zoneId]: a region ID or an offset ID. */
    fun isValidTimezone(zoneId: String): Boolean {
        return try {
            ZoneId.of(zoneId)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the canonical IANA form of [tzid] from Android's ICU (CLDR) data, e.g.
     * "US/Pacific" → "America/Los_Angeles".
     *
     * Returns [tzid] unchanged when ICU fails or doesn't know it, and null for null.
     */
    fun canonicalizeTimezone(tzid: String?): String? {
        if (tzid == null) return null
        return try {
            val canonical = android.icu.util.TimeZone.getCanonicalID(tzid)
            // getCanonicalID returns "Etc/Unknown" for invalid IDs
            if (canonical != null && canonical != "Etc/Unknown") {
                canonical
            } else {
                tzid  // Fallback to original
            }
        } catch (_: Exception) {
            tzid  // Fallback to original
        }
    }

    /**
     * Formats [eventTimeMs] as device-zone time with its abbreviation, for an event in
     * [eventTimezone] (null means the device zone).
     *
     * @return e.g. "12:00 AM EST (next day)", with " (prev day)" when the device date is
     *   earlier, or null when the event zone is the device zone. Throws for an invalid zone ID.
     */
    fun formatLocalTimePreview(
        eventTimeMs: Long,
        eventTimezone: String?
    ): String? {
        val deviceZone = ZoneId.systemDefault()
        val eventZone = if (eventTimezone != null) ZoneId.of(eventTimezone) else deviceZone

        if (eventZone == deviceZone) return null

        val instant = Instant.ofEpochMilli(eventTimeMs)
        val eventZdt = instant.atZone(eventZone)
        val deviceZdt = eventZdt.withZoneSameInstant(deviceZone)

        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val timeStr = deviceZdt.format(timeFormatter)
        val abbrev = getAbbreviation(deviceZone.id, instant)

        val dayDiff = deviceZdt.toLocalDate().toEpochDay() - eventZdt.toLocalDate().toEpochDay()
        val daySuffix = when {
            dayDiff > 0 -> " (next day)"
            dayDiff < 0 -> " (prev day)"
            else -> ""
        }

        return "$timeStr $abbrev$daySuffix"
    }

    /** Builds the picker's zone list, sorted by the UTC offset at build time. */
    private fun buildTimezoneList(): List<TimezoneInfo> {
        val now = Instant.now()
        val deviceZoneId = ZoneId.systemDefault()

        return ZoneId.getAvailableZoneIds()
            .filter { id ->
                // Drops the Etc/ and SystemV/ zones and IDs with no region, like "UTC" or "EST".
                !id.startsWith("Etc/") &&
                !id.startsWith("SystemV/") &&
                id.contains("/")
            }
            .mapNotNull { id ->
                try {
                    val zone = ZoneId.of(id)
                    val offset = zone.rules.getOffset(now)
                    val abbrev = getAbbreviation(id, now)
                    val displayName = displayNameOverrides[id]
                        ?: id.substringAfterLast("/").replace("_", " ")

                    val offsetStr = if (id == deviceZoneId.id) {
                        "Device"
                    } else {
                        getOffsetFromDevice(id, now)
                    }

                    // getRegion returns an ISO 3166 country code; "001" means World, no country.
                    val countryName = try {
                        val countryCode = android.icu.util.TimeZone.getRegion(id)
                        if (countryCode != null && countryCode != "001") {
                            java.util.Locale("", countryCode).displayCountry
                        } else null
                    } catch (_: Exception) { null }

                    TimezoneInfo(
                        zoneId = id,
                        abbreviation = abbrev,
                        displayName = displayName,
                        offsetFromDevice = offsetStr,
                        countryName = countryName
                    ) to offset.totalSeconds
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.second }
            .map { it.first }
    }
}
