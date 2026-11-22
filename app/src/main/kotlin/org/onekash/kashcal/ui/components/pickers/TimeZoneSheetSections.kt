package org.onekash.kashcal.ui.components.pickers

import org.onekash.kashcal.util.zone.ZoneCatalog
import java.time.Instant
import java.util.Locale

/** The time zone sheet's sections, in display order; [RESULTS] replaces them while searching. */
internal enum class ZoneSection { THIS_EVENT, DEVICE, RECENT, ALL, RESULTS }

/** What a tap on a row does to the form. */
internal sealed interface ZonePick {
    /** Close the sheet; the form is unchanged. */
    data object Keep : ZonePick

    /** Use the device's zone; [org.onekash.kashcal.ui.components.withZonePick] stores it. */
    data object Device : ZonePick

    /** Use [id], a [ZoneCatalog] list id, and record it as recent. */
    data class Set(val id: String) : ZonePick
}

/** One row of the time zone sheet. */
internal sealed interface ZoneSheetRow {
    val key: String

    data class Header(val section: ZoneSection) : ZoneSheetRow {
        override val key get() = "header-$section"
    }

    /**
     * A zone. [fromCalendar] is the event's stored id when the calendar spelled the zone
     * differently from the list ("US/Pacific" for Los Angeles), shown as a subtitle.
     */
    data class Zone(
        val entry: ZoneCatalog.Entry,
        val section: ZoneSection,
        val selected: Boolean,
        val pick: ZonePick,
        val fromCalendar: String? = null,
    ) : ZoneSheetRow {
        override val key get() = "$section-${entry.id}"
    }

    /** The event's zone the app can't read; the event shows in device time. Always selected. */
    data class Unresolved(val sourceId: String) : ZoneSheetRow {
        val pick: ZonePick get() = ZonePick.Keep
        override val key get() = "unresolved-$sourceId"
    }

    data object Loading : ZoneSheetRow {
        override val key get() = "loading"
    }

    data object Empty : ZoneSheetRow {
        override val key get() = "empty"
    }
}

/**
 * IANA areas: an id in one of these ("Asia/Kolkata") is the region spelling of its list row
 * ("Asia/Calcutta"), not a different name a calendar sent, so it gets no "From the calendar" line.
 */
private val IANA_AREAS = setOf(
    "Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific",
)

/**
 * Builds the time zone sheet's rows.
 *
 * Without a query: This event (the form's [eventZone] when it isn't the device's zone, or the
 * unresolved [sourceZone]; selected), Device time zone (selected when the event uses it), Recent
 * (without zones shown above), then All time zones. A selected row's tap keeps the form; Device
 * on another or an unresolved zone switches the event to the device zone. With a query, ranked
 * results replace the sections.
 *
 * @param entries the catalog list, or null while it loads
 * @param at the event's start, for labels of zones not in [entries]
 */
internal fun timeZoneSheetRows(
    eventZone: String?,
    sourceZone: String?,
    deviceZone: String,
    recents: List<String>,
    entries: List<ZoneCatalog.Entry>?,
    query: String,
    at: Instant,
    locale: Locale,
): List<ZoneSheetRow> {
    fun entryOf(id: String) = entries?.let { ZoneCatalog.entryFor(it, id) } ?: ZoneCatalog.label(id, at, locale)
    // List ids are canonical, so the selected zone is resolved to its row id once.
    val selectedId = (eventZone ?: deviceZone.takeIf { sourceZone == null })?.let { entryOf(it)?.id }
    fun zoneRow(entry: ZoneCatalog.Entry, section: ZoneSection): ZoneSheetRow.Zone {
        val selected = entry.id == selectedId
        return ZoneSheetRow.Zone(entry, section, selected, if (selected) ZonePick.Keep else ZonePick.Set(entry.id))
    }

    if (query.isNotBlank()) {
        if (entries == null) return listOf(ZoneSheetRow.Loading)
        val results = ZoneCatalog.search(entries, query, locale)
        if (results.isEmpty()) return listOf(ZoneSheetRow.Empty)
        return results.map { zoneRow(it, ZoneSection.RESULTS) }
    }

    val rows = mutableListOf<ZoneSheetRow>()
    val shown = mutableListOf<String>()

    // An event in the device's zone is shown by the Device row alone, selected.
    if (eventZone != null && !ZoneCatalog.sameZone(eventZone, deviceZone)) {
        entryOf(eventZone)?.let { entry ->
            rows += ZoneSheetRow.Header(ZoneSection.THIS_EVENT)
            rows += ZoneSheetRow.Zone(entry, ZoneSection.THIS_EVENT, true, ZonePick.Keep, fromCalendarOf(eventZone, entry))
            shown += eventZone
        }
    } else if (sourceZone != null) {
        rows += ZoneSheetRow.Header(ZoneSection.THIS_EVENT)
        rows += ZoneSheetRow.Unresolved(sourceZone)
    }

    entryOf(deviceZone)?.let { entry ->
        val selected = entry.id == selectedId
        rows += ZoneSheetRow.Header(ZoneSection.DEVICE)
        rows += ZoneSheetRow.Zone(entry, ZoneSection.DEVICE, selected, if (selected) ZonePick.Keep else ZonePick.Device)
        shown += deviceZone
    }

    val recentRows = recents
        .filter { recent -> shown.none { ZoneCatalog.sameZone(it, recent) } }
        .mapNotNull { entryOf(it) }
        .distinctBy { it.id }
        .map { ZoneSheetRow.Zone(it, ZoneSection.RECENT, false, ZonePick.Set(it.id)) }
    if (recentRows.isNotEmpty()) {
        rows += ZoneSheetRow.Header(ZoneSection.RECENT)
        rows += recentRows
    }

    rows += ZoneSheetRow.Header(ZoneSection.ALL)
    if (entries == null) {
        rows += ZoneSheetRow.Loading
    } else {
        entries.mapTo(rows) { zoneRow(it, ZoneSection.ALL) }
    }
    return rows
}

/** The stored id to show as "From the calendar", or null when it is just the list's zone. */
private fun fromCalendarOf(stored: String, entry: ZoneCatalog.Entry): String? = when {
    stored == entry.id -> null
    ZoneCatalog.isUtc(stored) -> null
    stored.substringBefore('/') in IANA_AREAS -> null
    else -> stored
}
