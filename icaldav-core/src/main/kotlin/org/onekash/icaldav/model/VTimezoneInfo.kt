package org.onekash.icaldav.model

/**
 * Holds a VTIMEZONE's TZID, its optional TZURL and its standard and daylight UTC offsets.
 *
 * No parser fills it: [fromTzid] and the constructor are the only ways to build one.
 *
 * @property tzid the TZID, for example "America/New_York"
 * @property tzurl the TZURL pointing to an authoritative definition of the zone
 * @property standardOffsetSec standard-time UTC offset in seconds
 * @property daylightOffsetSec daylight-saving-time UTC offset in seconds
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.5">RFC 5545 §3.6.5</a>
 * @see <a href="https://www.calconnect.org/resources/tzurl">CalConnect TZURL Service</a>
 */
data class VTimezoneInfo(
    val tzid: String,
    val tzurl: String? = null,
    val standardOffsetSec: Int? = null,
    val daylightOffsetSec: Int? = null
) {
    /** Returns whether this timezone carries a TZURL. */
    fun hasTzurl(): Boolean = tzurl != null

    /**
     * Returns whether [tzid] looks like an IANA ID: it contains "/" and has no "X-" or "x-" prefix,
     * so "UTC" counts as not IANA.
     */
    fun isIanaTimezone(): Boolean {
        return tzid.contains("/") && !tzid.startsWith("X-") && !tzid.startsWith("x-")
    }

    /** Returns the tzurl.org URL of this zone's definition, whether or not [tzurl] is set. */
    fun getDefaultTzurl(): String {
        return "https://www.tzurl.org/zoneinfo/$tzid.ics"
    }

    companion object {
        /** Creates a VTimezoneInfo with only a TZID. */
        fun fromTzid(tzid: String): VTimezoneInfo {
            return VTimezoneInfo(tzid = tzid)
        }
    }
}
