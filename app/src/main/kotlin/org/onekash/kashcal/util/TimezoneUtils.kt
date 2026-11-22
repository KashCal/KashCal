package org.onekash.kashcal.util

import java.time.ZoneId

/**
 * Resolves an event's stored timezone ID and reads the device zone. Labels, the zone list and
 * search live in [org.onekash.kashcal.util.zone.ZoneCatalog].
 *
 * @see DateTimeUtils for date/time formatting
 */
object TimezoneUtils {

    /** Legacy three-letter ids that name one fixed offset, so they are safe to read. */
    internal val UNAMBIGUOUS_SHORT_IDS = mapOf("EST" to "-05:00", "MST" to "-07:00", "HST" to "-10:00")

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
}
