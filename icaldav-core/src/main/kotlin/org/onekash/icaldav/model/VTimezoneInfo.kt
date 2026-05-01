package org.onekash.icaldav.model

/**
 * Information extracted from a VTIMEZONE component.
 *
 * Contains parsed VTIMEZONE data including the optional TZURL property
 * for timezone distribution service integration.
 *
 * @property tzid The TZID (timezone identifier), e.g., "America/New_York"
 * @property tzurl Optional TZURL property pointing to authoritative timezone definition
 * @property standardOffsetSec Standard time UTC offset in seconds (optional, parsed from VTIMEZONE)
 * @property daylightOffsetSec Daylight saving time UTC offset in seconds (optional)
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.5">RFC 5545 Section 3.6.5 - Time Zone Component</a>
 * @see <a href="https://www.calconnect.org/resources/tzurl">CalConnect TZURL Service</a>
 */
data class VTimezoneInfo(
    val tzid: String,
    val tzurl: String? = null,
    val standardOffsetSec: Int? = null,
    val daylightOffsetSec: Int? = null
) {
    /**
     * Check if this timezone has a TZURL for fetching authoritative data.
     */
    fun hasTzurl(): Boolean = tzurl != null

    /**
     * Check if this is a standard IANA timezone ID.
     */
    fun isIanaTimezone(): Boolean {
        return tzid.contains("/") && !tzid.startsWith("X-") && !tzid.startsWith("x-")
    }

    /**
     * Get the TZURL for this timezone from tzurl.org.
     *
     * @return URL to fetch the timezone definition
     */
    fun getDefaultTzurl(): String {
        return "https://www.tzurl.org/zoneinfo/$tzid.ics"
    }

    companion object {
        /**
         * Create VTimezoneInfo from just a TZID.
         */
        fun fromTzid(tzid: String): VTimezoneInfo {
            return VTimezoneInfo(tzid = tzid)
        }
    }
}
