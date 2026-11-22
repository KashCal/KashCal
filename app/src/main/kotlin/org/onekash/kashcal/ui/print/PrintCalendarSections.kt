package org.onekash.kashcal.ui.print

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEventType
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.screens.settings.CalendarNotificationSource
import org.onekash.kashcal.ui.screens.settings.buildCalendarNotificationSections

/** One calendar in the print sheet; [hidden] marks a calendar hidden from KashCal's views. */
@Immutable
data class PrintCalendarRow(val key: PrintCalendarKey, val name: String, val color: Int, val hidden: Boolean)

/** Rows under one optional sub-header, a device account's name. */
@Immutable
data class PrintCalendarGroup(val subheader: String?, val rows: List<PrintCalendarRow>)

/** One headed section of the print sheet's calendar list; [key] is unique across the sheet. */
@Immutable
data class PrintCalendarSection(val header: String, val groups: List<PrintCalendarGroup>, val key: String)

/**
 * Builds the print sheet's calendar sections from the Calendar notifications sheet's
 * ([buildCalendarNotificationSections]), which owns the drawer's order and the device rules,
 * with the contacts calendars named [birthdaysName] and [anniversariesName].
 */
fun buildPrintCalendarSections(
    calendarGroups: List<CalendarGroup>,
    deviceCalendarsEnabled: Boolean,
    enabledDeviceCalendars: List<DeviceCalendar>,
    hiddenDeviceIds: Set<Long>,
    contactsHeader: String,
    deviceHeader: String,
    deviceLocalLabel: String,
    birthdaysName: String,
    anniversariesName: String,
): List<PrintCalendarSection> {
    val urlById = calendarGroups.flatMap { it.calendars }.associate { it.id to it.caldavUrl }
    return buildCalendarNotificationSections(
        calendarGroups = calendarGroups,
        deviceCalendarsEnabled = deviceCalendarsEnabled,
        deviceCalendars = enabledDeviceCalendars,
        enabledDeviceIds = enabledDeviceCalendars.map { it.id }.toSet(),
        hiddenDeviceIds = hiddenDeviceIds,
        mutedDeviceIds = emptySet(),
        contactsHeader = contactsHeader,
        deviceHeader = deviceHeader,
        deviceLocalLabel = deviceLocalLabel,
    ).map { section ->
        PrintCalendarSection(
            header = section.header,
            key = section.key,
            groups = section.groups.map { group ->
                PrintCalendarGroup(
                    subheader = group.subheader,
                    rows = group.rows.map { row ->
                        val key = when (row.source) {
                            CalendarNotificationSource.ROOM -> PrintCalendarKey.Room(row.id)
                            CalendarNotificationSource.DEVICE -> PrintCalendarKey.Device(row.id)
                        }
                        val name = if (row.source == CalendarNotificationSource.ROOM) {
                            urlById[row.id]?.let { contactCalendarName(it, birthdaysName, anniversariesName) }
                        } else {
                            null
                        }
                        PrintCalendarRow(key = key, name = name ?: row.name, color = row.color, hidden = row.hidden)
                    },
                )
            },
        )
    }
}

/**
 * Returns the print name of the contacts calendar at [calendarUrl], or null for any other
 * calendar. It matches the calendar's own address ([ContactEventType.localCalendarUrl]);
 * [ContactEventType.fromCaldavUrl] reads event addresses and would match no calendar.
 */
fun contactCalendarName(calendarUrl: String, birthdaysName: String, anniversariesName: String): String? =
    when (ContactEventType.entries.find { it.localCalendarUrl == calendarUrl }) {
        ContactEventType.BIRTHDAY -> birthdaysName
        ContactEventType.ANNIVERSARY -> anniversariesName
        null -> null
    }
