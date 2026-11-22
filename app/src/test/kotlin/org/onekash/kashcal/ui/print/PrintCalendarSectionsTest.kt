package org.onekash.kashcal.ui.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEventType
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup

/**
 * Tests [buildPrintCalendarSections]: every calendar KashCal shows, in the drawer's order, with
 * the contacts calendars named Birthdays and Anniversaries.
 */
class PrintCalendarSectionsTest {

    private val local = Account(id = 1, provider = AccountProvider.LOCAL, email = "local")
    private val icloud = Account(id = 2, provider = AccountProvider.ICLOUD, email = "alex@example.test", displayName = "alex@example.test")
    private val birthdays = Account(id = 4, provider = AccountProvider.CONTACTS, email = "birthdays", displayName = "Birthdays")
    private val anniversaries = Account(id = 5, provider = AccountProvider.CONTACTS, email = "anniversaries", displayName = "Anniversaries")

    private fun calendar(id: Long, accountId: Long, name: String, url: String = "cal://$id", visible: Boolean = true) =
        Calendar(id = id, accountId = accountId, caldavUrl = url, displayName = name, color = id.toInt(), isVisible = visible)

    private val roomGroups = CalendarGroup.fromCalendarsAndAccounts(
        calendars = listOf(
            calendar(10, local.id, "My calendar"),
            calendar(20, icloud.id, "Work"),
            calendar(21, icloud.id, "Home", visible = false),
            calendar(40, birthdays.id, "Contact Birthdays", url = ContactEventType.BIRTHDAY.localCalendarUrl),
            calendar(50, anniversaries.id, "Contact Anniversaries", url = ContactEventType.ANNIVERSARY.localCalendarUrl, visible = false),
        ),
        accounts = listOf(local, icloud, birthdays, anniversaries),
        localLabel = "Offline",
        icsLabel = "Calendar Feeds",
    )

    private fun device(id: Long, account: String, name: String) = DeviceCalendar(
        id = id, displayName = name, color = id.toInt(), accountName = account, accountType = "org.example",
        visible = true, accessLevel = 700,
    )

    private val devices = listOf(
        device(100, "alex@gmail.example", "Kids school"),
        device(101, "alex@gmail.example", "Holidays in United States"),
        device(102, "", "Phone"),
    )

    private fun sections(deviceCalendarsEnabled: Boolean = true, hidden: Set<Long> = setOf(102)) =
        buildPrintCalendarSections(
            calendarGroups = roomGroups,
            deviceCalendarsEnabled = deviceCalendarsEnabled,
            enabledDeviceCalendars = devices,
            hiddenDeviceIds = hidden,
            contactsHeader = "Contacts",
            deviceHeader = "Device calendars",
            deviceLocalLabel = "On this device",
            birthdaysName = "Birthdays",
            anniversariesName = "Anniversaries",
        )

    private fun PrintCalendarSection.names() = groups.flatMap { g -> g.rows.map { it.name } }

    @Test
    fun `sections follow the drawer's order with the Offline calendars last`() {
        assertEquals(
            listOf("alex@example.test", "Contacts", "Device calendars", "Offline"),
            sections().map { it.header },
        )
    }

    @Test
    fun `the contacts calendars are named Birthdays and Anniversaries`() {
        val contacts = sections().first { it.header == "Contacts" }
        // In the drawer's order, which sorts the two contacts accounts by name.
        assertEquals(listOf("Anniversaries", "Birthdays"), contacts.names())
    }

    @Test
    fun `rows carry colour, hidden flag and a key per id space`() {
        val rows = sections().flatMap { s -> s.groups.flatMap { it.rows } }.associateBy { it.key }
        assertTrue(rows.getValue(PrintCalendarKey.Room(21)).hidden)
        assertTrue(rows.getValue(PrintCalendarKey.Room(50)).hidden)
        assertTrue(rows.getValue(PrintCalendarKey.Device(102)).hidden)
        assertEquals(false, rows.getValue(PrintCalendarKey.Room(20)).hidden)
        assertEquals(100, rows.getValue(PrintCalendarKey.Device(100)).color)
        assertEquals(8, rows.size)
    }

    @Test
    fun `device calendars get a sub-header per account, an unnamed one the local label`() {
        val device = sections().first { it.header == "Device calendars" }
        assertEquals(listOf("alex@gmail.example", "On this device"), device.groups.map { it.subheader })
        assertEquals(listOf("Holidays in United States", "Kids school", "Phone"), device.names())
    }

    @Test
    fun `no device section while device calendars are off`() {
        assertEquals(listOf("alex@example.test", "Contacts", "Offline"), sections(deviceCalendarsEnabled = false).map { it.header })
    }

    @Test
    fun `contact calendar names match by calendar address, not by event address`() {
        assertEquals("Birthdays", contactCalendarName(ContactEventType.BIRTHDAY.localCalendarUrl, "Birthdays", "Anniversaries"))
        assertEquals("Anniversaries", contactCalendarName(ContactEventType.ANNIVERSARY.localCalendarUrl, "Birthdays", "Anniversaries"))
        // An event's address names a contact, not a calendar.
        assertEquals(null, contactCalendarName("contact_birthday:abc:3-14", "Birthdays", "Anniversaries"))
        assertEquals(null, contactCalendarName("cal://20", "Birthdays", "Anniversaries"))
    }
}
