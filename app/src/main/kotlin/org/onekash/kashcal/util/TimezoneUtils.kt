package org.onekash.kashcal.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Timezone utilities for event handling and display.
 *
 * Provides:
 * - Timezone search (by city, abbreviation, region)
 * - Abbreviation formatting (EST, PST, JST)
 * - Offset calculation from device timezone
 * - Time conversion between timezones
 *
 * @see DateTimeUtils for date/time formatting
 */
object TimezoneUtils {

    /**
     * Information about a timezone for display in picker.
     *
     * @param zoneId The IANA timezone ID (e.g., "America/New_York")
     * @param abbreviation Short abbreviation (e.g., "EST", "PST")
     * @param displayName Human-readable name (e.g., "New York")
     * @param offsetFromDevice Offset relative to device timezone (e.g., "+5h", "-3h", "Device")
     * @param countryName Country name from ICU (e.g., "United States", "Germany")
     */
    data class TimezoneInfo(
        val zoneId: String,
        val abbreviation: String,
        val displayName: String,
        val offsetFromDevice: String,
        val countryName: String? = null
    )

    // Cache of all available timezones (lazy initialized)
    private val allTimezones: List<TimezoneInfo> by lazy {
        buildTimezoneList()
    }

    // Common timezone display names (city extracted from zone ID)
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

    // City aliases for search - multiple cities can share the same timezone
    // This allows users to search by any major city name, not just the IANA zone city
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
     * Get all available timezones sorted by offset from UTC.
     *
     * @return List of TimezoneInfo for all available zones
     */
    fun getAvailableTimezones(): List<TimezoneInfo> = allTimezones

    /**
     * Search timezones by query string.
     *
     * Searches against:
     * - City/display name (e.g., "tokyo", "new york")
     * - Abbreviation (e.g., "EST", "PST", "JST")
     * - Region/zone ID (e.g., "america", "asia/tok")
     * - City aliases (e.g., "new delhi", "beijing", "dallas")
     *
     * @param query Search query (case-insensitive)
     * @return Matching timezones, limited to top 10 results
     */
    fun searchTimezones(query: String): List<TimezoneInfo> {
        if (query.isBlank()) return emptyList()

        val normalizedQuery = query.trim().lowercase()

        return allTimezones.filter { tz ->
            tz.displayName.lowercase().contains(normalizedQuery) ||
            tz.abbreviation.lowercase().contains(normalizedQuery) ||
            tz.zoneId.lowercase().contains(normalizedQuery) ||
            // Check country name (from ICU getRegion)
            tz.countryName?.lowercase()?.contains(normalizedQuery) == true ||
            // Check city aliases for this timezone
            cityAliases[tz.zoneId]?.any { city ->
                city.lowercase().contains(normalizedQuery)
            } == true
        }.take(10)
    }

    /**
     * Get timezone abbreviation for display.
     *
     * @param zoneId IANA timezone ID (e.g., "America/New_York")
     * @param instant Point in time for DST calculation (default: now)
     * @return Short abbreviation (e.g., "EST", "EDT", "JST")
     */
    fun getAbbreviation(zoneId: String, instant: Instant = Instant.now()): String {
        return try {
            val zone = ZoneId.of(zoneId)
            val zdt = instant.atZone(zone)
            // Use format pattern to get proper timezone abbreviation
            val formatter = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US)
            zdt.format(formatter)
        } catch (e: Exception) {
            // Fallback for invalid zone IDs
            zoneId.substringAfterLast("/").take(3).uppercase()
        }
    }

    /**
     * Get offset from device timezone as human-readable string.
     *
     * @param zoneId IANA timezone ID
     * @param instant Point in time for calculation (default: now)
     * @return Offset string: "Device" if same, "+5h" if ahead, "-3h" if behind
     */
    fun getOffsetFromDevice(zoneId: String, instant: Instant = Instant.now()): String {
        val deviceZoneId = ZoneId.systemDefault()

        // Same timezone
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
        } catch (e: Exception) {
            "?"
        }
    }

    /**
     * Convert time between timezones.
     *
     * @param epochMs Source timestamp in milliseconds
     * @param fromZone Source timezone ID (null = device timezone)
     * @param toZone Target timezone ID (null = device timezone)
     * @return Timestamp adjusted to represent same instant in target timezone display
     */
    fun convertTime(epochMs: Long, fromZone: String?, toZone: String?): Long {
        val from = if (fromZone != null) ZoneId.of(fromZone) else ZoneId.systemDefault()
        val to = if (toZone != null) ZoneId.of(toZone) else ZoneId.systemDefault()

        // Same timezone, no conversion needed
        if (from == to) return epochMs

        val instant = Instant.ofEpochMilli(epochMs)
        val sourceZdt = instant.atZone(from)
        val targetZdt = sourceZdt.withZoneSameInstant(to)

        return targetZdt.toInstant().toEpochMilli()
    }

    /**
     * Get TimezoneInfo for a specific zone ID.
     *
     * @param zoneId IANA timezone ID
     * @return TimezoneInfo or null if invalid zone
     */
    fun getTimezoneInfo(zoneId: String): TimezoneInfo? {
        return allTimezones.find { it.zoneId == zoneId }
    }

    /**
     * Get the device's current timezone ID.
     *
     * @return Device timezone ID (e.g., "America/New_York")
     */
    fun getDeviceTimezone(): String = ZoneId.systemDefault().id

    /**
     * Check if a timezone ID is valid.
     *
     * @param zoneId Timezone ID to validate
     * @return true if valid IANA timezone ID
     */
    fun isValidTimezone(zoneId: String): Boolean {
        return try {
            ZoneId.of(zoneId)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Canonicalize a timezone ID to its standard IANA form.
     *
     * Converts aliases like "US/Pacific" → "America/Los_Angeles".
     * Returns original if canonicalization fails or returns unknown.
     *
     * Uses Android ICU library (API 24+) for CLDR-standard mappings.
     *
     * @param tzid Raw timezone ID (may be alias)
     * @return Canonical timezone ID, or original if canonicalization fails
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
        } catch (e: Exception) {
            tzid  // Fallback to original
        }
    }

    /**
     * Format local time preview for display.
     *
     * Shows what time it will be in the device timezone when the event
     * occurs in the selected timezone.
     *
     * @param eventTimeMs Event time in milliseconds
     * @param eventTimezone Event's timezone ID (null = device timezone)
     * @return Formatted preview (e.g., "12am EST (next day)") or null if same timezone
     */
    fun formatLocalTimePreview(
        eventTimeMs: Long,
        eventTimezone: String?
    ): String? {
        val deviceZone = ZoneId.systemDefault()
        val eventZone = if (eventTimezone != null) ZoneId.of(eventTimezone) else deviceZone

        // No preview needed if same timezone
        if (eventZone == deviceZone) return null

        val instant = Instant.ofEpochMilli(eventTimeMs)
        val eventZdt = instant.atZone(eventZone)
        val deviceZdt = eventZdt.withZoneSameInstant(deviceZone)

        // Format time in device timezone
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val timeStr = deviceZdt.format(timeFormatter)
        val abbrev = getAbbreviation(deviceZone.id, instant)

        // Check if different day
        val dayDiff = deviceZdt.toLocalDate().toEpochDay() - eventZdt.toLocalDate().toEpochDay()
        val daySuffix = when {
            dayDiff > 0 -> " (next day)"
            dayDiff < 0 -> " (prev day)"
            else -> ""
        }

        return "$timeStr $abbrev$daySuffix"
    }

    /**
     * Build the complete list of available timezones.
     */
    private fun buildTimezoneList(): List<TimezoneInfo> {
        val now = Instant.now()
        val deviceZoneId = ZoneId.systemDefault()

        return ZoneId.getAvailableZoneIds()
            .filter { id ->
                // Filter out deprecated/obscure zones
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

                    // Get country name from ICU (API 24+)
                    // getRegion returns ISO 3166 country code, "001" means World/no country
                    val countryName = try {
                        val countryCode = android.icu.util.TimeZone.getRegion(id)
                        if (countryCode != null && countryCode != "001") {
                            java.util.Locale("", countryCode).displayCountry
                        } else null
                    } catch (e: Exception) { null }

                    TimezoneInfo(
                        zoneId = id,
                        abbreviation = abbrev,
                        displayName = displayName,
                        offsetFromDevice = offsetStr,
                        countryName = countryName
                    ) to offset.totalSeconds
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.second } // Sort by UTC offset
            .map { it.first }
    }
}
