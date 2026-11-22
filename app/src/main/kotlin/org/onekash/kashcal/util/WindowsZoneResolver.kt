package org.onekash.kashcal.util

import org.onekash.kashcal.util.zone.ZoneCatalog
import java.time.ZoneId

/**
 * Resolves a Windows zone name ("Eastern Standard Time" to America/New_York) with Android ICU,
 * which follows CLDR; null when ICU doesn't know it. Installed as
 * `ICalDateTime.customTimezoneResolver` at startup; the icaldav library's properties file is the
 * fallback where this isn't installed.
 */
fun resolveWindowsZone(tzid: String): ZoneId? = ZoneCatalog.windowsZone(tzid)
