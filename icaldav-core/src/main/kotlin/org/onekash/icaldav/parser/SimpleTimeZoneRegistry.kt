package org.onekash.icaldav.parser

import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry
import org.onekash.icaldav.model.ICalDateTime
import java.time.ZoneId
import java.time.zone.ZoneRules

/**
 * Resolves timezones without ZoneRulesProvider, so iCalendar parsing works on Android.
 *
 * ical4j 4.x's TimeZoneRegistryImpl uses ZoneRulesProvider, which isn't available on Android
 * via desugaring. This registry instead:
 * - returns null from [getTimeZone], so ical4j uses the data's embedded VTIMEZONE definitions
 * - resolves IDs in [getZoneId] with ZoneId.of, which Android supports, plus alias tables
 * - ignores register and clear
 */
class SimpleTimeZoneRegistry : TimeZoneRegistry {

    /**
     * Returns null, so ical4j handles dates with the embedded VTIMEZONE definitions in the data
     * or falls back to system timezone handling.
     */
    override fun getTimeZone(id: String?): TimeZone? = null

    override fun register(timezone: TimeZone?) {
        // A no-op: this registry keeps no custom timezones.
    }

    override fun register(timezone: TimeZone?, update: Boolean) {
        // A no-op: this registry keeps no custom timezones.
    }

    override fun clear() {
        // A no-op: there is nothing to clear.
    }

    /** Returns an empty map, since zone rules would need ZoneRulesProvider, which Android lacks. */
    override fun getZoneRules(): Map<String, ZoneRules> = emptyMap()

    /**
     * Resolves [tzId] with ZoneId.of (supported on Android via desugaring), then the aliases in
     * [normalizeTimezoneId], then [ICalDateTime.timezoneAliases]. Returns null for a blank or
     * unrecognized ID.
     */
    override fun getZoneId(tzId: String?): ZoneId? {
        if (tzId.isNullOrBlank()) return null
        return try {
            ZoneId.of(tzId)
        } catch (_: Exception) {
            // Hardcoded aliases first, so these names keep resolving to these IDs
            normalizeTimezoneId(tzId)?.let {
                try { ZoneId.of(it) } catch (_: Exception) { null }
            }
            // Then the Windows timezone names from ical4j's properties file
            ?: ICalDateTime.timezoneAliases[tzId]?.let {
                try { ZoneId.of(it) } catch (_: Exception) { null }
            }
        }
    }

    /** Returns [zoneId] unchanged. */
    override fun getTzId(zoneId: String?): String? = zoneId

    /**
     * Maps four US Windows timezone names and GMT, in any case, to IANA IDs. Returns null for any
     * other name, which falls through to [ICalDateTime.timezoneAliases].
     */
    private fun normalizeTimezoneId(tzId: String): String? {
        return when {
            tzId.equals("Pacific Standard Time", ignoreCase = true) -> "America/Los_Angeles"
            tzId.equals("Eastern Standard Time", ignoreCase = true) -> "America/New_York"
            tzId.equals("Central Standard Time", ignoreCase = true) -> "America/Chicago"
            tzId.equals("Mountain Standard Time", ignoreCase = true) -> "America/Denver"
            tzId.equals("GMT", ignoreCase = true) -> "UTC"
            else -> null
        }
    }
}
