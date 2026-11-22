package org.onekash.kashcal.ui.components

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.util.zone.ZoneCatalog
import java.time.Instant
import java.util.Locale

/** The time zone row's visible text and its screen reader text. */
internal data class TimeZoneRowLabel(val text: String, val description: String)

/**
 * Returns the event form's time zone row for [entry]: the city with the abbreviation, or the
 * offset when the zone has none ("Berlin (CET)", "Tokyo (GMT+09:00)"), or the offset alone for an
 * offset-only id. The sheet the row opens shows the rest. The screen reader text names the zone
 * and the offset instead: "Berlin, Central European Time, GMT+01:00".
 */
internal fun timeZoneRowLabel(resources: Resources, entry: ZoneCatalog.Entry): TimeZoneRowLabel {
    if (entry.city.isEmpty()) return TimeZoneRowLabel(entry.offset, entry.offset)
    val text = resources.getString(R.string.timezone_row_city_zone, entry.city, entry.abbreviation ?: entry.offset)
    val description = entry.zoneName
        ?.let { resources.getString(R.string.cd_timezone_city_name_offset, entry.city, it, entry.offset) }
        ?: resources.getString(R.string.cd_timezone_city_offset, entry.city, entry.offset)
    return TimeZoneRowLabel(text, description)
}

/**
 * Returns the start and end rows' zone suffix at [at]: the abbreviation, else the offset, or
 * null when [timezone] is null, unreadable, or the device's zone ([ZoneCatalog.sameZone]).
 */
internal fun compactZoneLabel(timezone: String?, deviceZone: String, at: Instant): String? {
    if (timezone == null || ZoneCatalog.sameZone(timezone, deviceZone)) return null
    val entry = ZoneCatalog.label(timezone, at, Locale.getDefault()) ?: return null
    return entry.abbreviation ?: entry.offset
}
