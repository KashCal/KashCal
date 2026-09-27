package org.onekash.icaldav.model

/**
 * Holds one VLOCATION component (RFC 9073 §7.2): a venue or related service such as parking,
 * with optional coordinates, address and URL. The parser and generator don't read or write it.
 *
 * Example:
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
 * @see <a href="https://tools.ietf.org/html/rfc9073#section-7.2">RFC 9073 Section 7.2</a>
 */
data class ICalLocation(
    /** UID property, required by RFC 9073. */
    val uid: String,

    /** NAME property, for example "Conference Room A". */
    val name: String? = null,

    /** DESCRIPTION property, for example a full address. */
    val description: String? = null,

    /** GEO property. */
    val geo: GeoCoordinates? = null,

    /** LOCATION-TYPE values (RFC 9073 §6.1). */
    val locationTypes: List<LocationType> = emptyList(),

    /** URL for more information, or the address of an online location. */
    val url: String? = null,

    /** Postal address parts. */
    val structuredAddress: StructuredAddress? = null
) {
    /** Returns whether [locationTypes] contains ONLINE. */
    fun isOnline(): Boolean = locationTypes.contains(LocationType.ONLINE)

    /** Returns whether [geo] is set. */
    fun hasCoordinates(): Boolean = geo != null

    companion object {
        /** Creates a location with only a name and a UID, random by default. */
        fun simple(name: String, uid: String = java.util.UUID.randomUUID().toString()): ICalLocation {
            return ICalLocation(uid = uid, name = name)
        }

        /** Creates an ONLINE location with a name, URL and a UID, random by default. */
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

/** Latitude and longitude, as in a GEO value. */
data class GeoCoordinates(
    val latitude: Double,
    val longitude: Double
) {
    /** Returns the GEO value, "latitude;longitude". */
    fun toICalString(): String = "$latitude;$longitude"

    companion object {
        /**
         * Parses a GEO value such as "37.386013;-122.082932"; null when [value] is null, blank,
         * not two `;`-separated parts, or not numeric.
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

/** Postal address parts, modelled on the vCard ADR property. */
data class StructuredAddress(
    /** Street address, for example "123 Main Street". */
    val streetAddress: String? = null,

    /** City or locality. */
    val locality: String? = null,

    /** State, province or region. */
    val region: String? = null,

    /** Postal or ZIP code. */
    val postalCode: String? = null,

    /** Country name. */
    val country: String? = null
) {
    /** Joins the non-blank parts with ", ", street first and country last. */
    fun toDisplayString(): String {
        return listOfNotNull(streetAddress, locality, region, postalCode, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }
}

/** LOCATION-TYPE values this library recognizes; RFC 9073 §6.1 draws its values from RFC 4589. */
enum class LocationType {
    /** Indoor physical location. */
    INDOOR,

    /** Outdoor physical location. */
    OUTDOOR,

    /** Online location. */
    ONLINE,

    /** Parking area. */
    PARKING,

    /** Private location, for example a home office. */
    PRIVATE,

    /** Public venue. */
    PUBLIC;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): LocationType? {
            if (value.isNullOrBlank()) return null
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}
