package org.onekash.kashcal.ui.screens.settings

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.toDisplaySections

/**
 * One calendar in the Export calendars sheet. [eventCount] is null until the counts have loaded,
 * so the row isn't shown as empty before it is known.
 */
@Immutable
data class ExportCalendarRow(
    val id: Long,
    val name: String,
    val color: Int,
    val eventCount: Int?,
)

/**
 * One headed section of the sheet. [subheader] is the account's login when another section
 * shares [header] (two logins on one server); [key] is unique across the sheet.
 */
@Immutable
data class ExportCalendarSection(
    val header: String,
    val subheader: String?,
    val rows: List<ExportCalendarRow>,
    val key: String,
)

/**
 * Builds the Export calendars sheet's sections from every Room calendar, hidden ones included,
 * in the drawer's order ([toDisplaySections]): each account (Calendar Feeds among them), one
 * [contactsHeader] section for birthdays and anniversaries, then the Offline account. Device
 * calendars aren't Room calendars, so they never appear.
 *
 * @param eventCounts each calendar's event count by id, or null while not loaded; a calendar
 *   missing from a loaded map has none
 * @param accountLogins each account's login by id, shown under a header two accounts share
 */
fun buildExportCalendarSections(
    calendarGroups: List<CalendarGroup>,
    eventCounts: Map<Long, Int>?,
    accountLogins: Map<Long, String>,
    contactsHeader: String,
): List<ExportCalendarSection> {
    fun rows(group: CalendarGroup) = group.calendars.map { calendar ->
        ExportCalendarRow(
            id = calendar.id,
            name = calendar.displayName,
            color = calendar.color,
            eventCount = eventCounts?.let { it[calendar.id] ?: 0 },
        )
    }

    val (regular, contacts, offline) = calendarGroups.toDisplaySections()
    val accountGroups = regular + offline
    val repeatedHeaders = accountGroups.groupingBy { it.accountName }.eachCount().filterValues { it > 1 }.keys

    fun section(group: CalendarGroup) = ExportCalendarSection(
        header = group.accountName,
        subheader = accountLogins[group.accountId]?.takeIf { group.accountName in repeatedHeaders },
        rows = rows(group),
        key = "room_${group.accountId}",
    )

    val sections = regular.mapTo(mutableListOf()) { section(it) }
    if (contacts.isNotEmpty()) {
        sections += ExportCalendarSection(
            header = contactsHeader,
            subheader = null,
            rows = contacts.flatMap { rows(it) },
            key = "contacts",
        )
    }
    offline.mapTo(sections) { section(it) }
    return sections
}
