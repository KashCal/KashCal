package org.onekash.icaldav.model

/**
 * VLOCATION component per RFC 9073 Section 6.1.
 * Provides structured location information for events.
 *
 * VLOCATION allows rich venue details including geographic coordinates,
 * structured addresses, and virtual location URLs.
 *
 * Example iCalendar format:
 * ```
 * BEGIN:VLOCATION
 * UID:location-1
 * NAME:Conference Room A
 * DESCRIPTION:Building 1, Floor 3
 * GEO:37.386013;-122.082932
 * LOCATION-TYPE:INDOOR
 * END:VLOCATION
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc9073#section-6.1">RFC 9073 Section 6.1</a>
 */
data class ICalLocation(
    /** Unique identifier for this location */
    val uid: String,

    /** Human-readable name (e.g., "Conference Room A") */
    val name: String? = null,

    /** Full address or description */
    val description: String? = null,

    /** Geographic coordinates */
    val geo: GeoCoordinates? = null,

    /** Location types (INDOOR, OUTDOOR, ONLINE, etc.) */
    val locationTypes: List<LocationType> = emptyList(),

    /** URL for more information or virtual location */
    val url: String? = null,

    /** Structured address components */
    val structuredAddress: StructuredAddress? = null
) {
    /**
     * Check if this is a virtual/online location.
     */
    fun isOnline(): Boolean = locationTypes.contains(LocationType.ONLINE)

    /**
     * Check if this location has coordinates.
     */
    fun hasCoordinates(): Boolean = geo != null

    companion object {
        /**
         * Create a simple location with name.
         */
        fun simple(name: String, uid: String = java.util.UUID.randomUUID().toString()): ICalLocation {
            return ICalLocation(uid = uid, name = name)
        }

        /**
         * Create an online/virtual location.
         */
        fun online(
            name: String,
            url: String,
            uid: String = java.util.UUID.randomUUID().toString()
        ): ICalLocation {
            return ICalLocation(
                uid = uid,
                name = name,
                url = url,
                locationTypes = listOf(LocationType.ONLINE)
            )
        }
    }
}

/**
 * Geographic coordinates for a location.
 */
data class GeoCoordinates(
    val latitude: Double,
    val longitude: Double
) {
    /**
     * Format as iCalendar GEO property value.
     */
    fun toICalString(): String = "$latitude;$longitude"

    companion object {
        /**
         * Parse from iCalendar GEO property value.
         *
         * @param value String in format "latitude;longitude" (e.g., "37.386013;-122.082932")
         * @return Parsed coordinates or null if invalid
         */
        fun parse(value: String?): GeoCoordinates? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(";")
            if (parts.size != 2) return null
            return try {
                GeoCoordinates(
                    latitude = parts[0].trim().toDouble(),
                    longitude = parts[1].trim().toDouble()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * Structured address components.
 * Based on vCard ADR property structure.
 */
data class StructuredAddress(
    /** Street address (e.g., "123 Main Street") */
    val streetAddress: String? = null,

    /** City/locality */
    val locality: String? = null,

    /** State/province/region */
    val region: String? = null,

    /** Postal/ZIP code */
    val postalCode: String? = null,

    /** Country name */
    val country: String? = null
) {
    /**
     * Format as a single-line address string.
     */
    fun toDisplayString(): String {
        return listOfNotNull(streetAddress, locality, region, postalCode, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }
}

/**
 * Location type per RFC 9073.
 */
enum class LocationType {
    /** Indoor physical location */
    INDOOR,

    /** Outdoor physical location */
    OUTDOOR,

    /** Online/virtual location */
    ONLINE,

    /** Parking area */
    PARKING,

    /** Private location (e.g., home office) */
    PRIVATE,

    /** Public venue */
    PUBLIC;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): LocationType? {
            if (value.isNullOrBlank()) return null
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}
