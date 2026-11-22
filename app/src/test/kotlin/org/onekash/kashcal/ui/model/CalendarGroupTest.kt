package org.onekash.kashcal.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

class CalendarGroupTest {

    private fun account(
        id: Long,
        provider: AccountProvider,
        displayName: String? = null,
        email: String = "test@test.com"
    ) = Account(id = id, provider = provider, email = email, displayName = displayName)

    private fun calendar(id: Long, accountId: Long, displayName: String = "Cal $id") =
        Calendar(id = id, accountId = accountId, caldavUrl = "url_$id", displayName = displayName, color = 0xFF0000)

    private fun group(calendars: List<Calendar>, accounts: List<Account>) =
        CalendarGroup.fromCalendarsAndAccounts(
            calendars,
            accounts,
            localLabel = "Offline",
            icsLabel = "Calendar Feeds"
        )

    // ========== Display Name Mapping ==========

    @Test
    fun `LOCAL account maps to Offline display name`() {
        val accounts = listOf(account(1L, AccountProvider.LOCAL, displayName = "On This Device"))
        val calendars = listOf(calendar(10L, 1L))

        val groups = group(calendars, accounts)

        assertEquals(1, groups.size)
        assertEquals("Offline", groups[0].accountName)
    }

    @Test
    fun `ICLOUD account uses custom displayName`() {
        val accounts = listOf(account(1L, AccountProvider.ICLOUD, displayName = "Work iCloud"))
        val calendars = listOf(calendar(10L, 1L))

        val groups = group(calendars, accounts)

        assertEquals("Work iCloud", groups[0].accountName)
    }

    @Test
    fun `ICS account maps to icsLabel regardless of stored displayName`() {
        // A stored displayName, here "ICS Subscriptions", must not show; the group uses icsLabel.
        val accounts = listOf(account(1L, AccountProvider.ICS, displayName = "ICS Subscriptions"))
        val calendars = listOf(calendar(10L, 1L))

        val groups = group(calendars, accounts)

        assertEquals("Calendar Feeds", groups[0].accountName)
    }

    @Test
    fun `account with null displayName falls back to provider displayName`() {
        val accounts = listOf(account(1L, AccountProvider.CALDAV, displayName = null))
        val calendars = listOf(calendar(10L, 1L))

        val groups = group(calendars, accounts)

        assertEquals("CalDAV", groups[0].accountName)
    }

    // ========== Provider Field ==========

    @Test
    fun `provider field is populated from account`() {
        val accounts = listOf(
            account(1L, AccountProvider.ICLOUD),
            account(2L, AccountProvider.LOCAL),
            account(3L, AccountProvider.CONTACTS, email = "contact_birthdays")
        )
        val calendars = listOf(
            calendar(10L, 1L),
            calendar(20L, 2L),
            calendar(30L, 3L)
        )

        val groups = group(calendars, accounts)

        val providerMap = groups.associate { it.accountName to it.provider }
        assertEquals(AccountProvider.ICLOUD, providerMap["iCloud"])
        assertEquals(AccountProvider.LOCAL, providerMap["Offline"])
        assertEquals(AccountProvider.CONTACTS, providerMap["Contact Birthdays"])
    }

    @Test
    fun `provider is null when account not found`() {
        // A calendar whose accountId has no matching account.
        val accounts = emptyList<Account>()
        val calendars = listOf(calendar(10L, 999L))

        val groups = group(calendars, accounts)

        assertEquals(1, groups.size)
        assertNull(groups[0].provider)
        assertEquals("Unknown", groups[0].accountName)
    }

    // ========== Sort Order ==========

    @Test
    fun `CONTACTS groups sort after all other groups`() {
        val accounts = listOf(
            account(1L, AccountProvider.CONTACTS, email = "contact_birthdays"),
            account(2L, AccountProvider.ICLOUD, displayName = "iCloud"),
            account(3L, AccountProvider.LOCAL, displayName = "On This Device"),
            account(4L, AccountProvider.CONTACTS, email = "contact_anniversaries")
        )
        val calendars = listOf(
            calendar(10L, 1L, "Contact Birthdays"),
            calendar(20L, 2L, "Work"),
            calendar(30L, 3L, "Local"),
            calendar(40L, 4L, "Contact Anniversaries")
        )

        val groups = group(calendars, accounts)

        // The non-CONTACTS groups (iCloud, Offline) come first, the two CONTACTS groups last.
        assertEquals(4, groups.size)
        assertEquals(AccountProvider.ICLOUD, groups[0].provider)
        assertEquals(AccountProvider.LOCAL, groups[1].provider)
        assertEquals(AccountProvider.CONTACTS, groups[2].provider)
        assertEquals(AccountProvider.CONTACTS, groups[3].provider)
    }

    @Test
    fun `non-CONTACTS groups sort alphabetically`() {
        val accounts = listOf(
            account(1L, AccountProvider.CALDAV, displayName = "Zoho"),
            account(2L, AccountProvider.ICLOUD, displayName = "Apple"),
            account(3L, AccountProvider.LOCAL, displayName = "On This Device")
        )
        val calendars = listOf(
            calendar(10L, 1L),
            calendar(20L, 2L),
            calendar(30L, 3L)
        )

        val groups = group(calendars, accounts)

        assertEquals("Apple", groups[0].accountName)
        assertEquals("Offline", groups[1].accountName)
        assertEquals("Zoho", groups[2].accountName)
    }

    @Test
    fun `CONTACTS groups sort alphabetically among themselves`() {
        // A CONTACTS account stores ContactEventType.calendarDisplayName as its displayName.
        val accounts = listOf(
            account(1L, AccountProvider.CONTACTS, displayName = "Contact Birthdays", email = "contact_birthdays"),
            account(2L, AccountProvider.CONTACTS, displayName = "Contact Anniversaries", email = "contact_anniversaries")
        )
        val calendars = listOf(
            calendar(10L, 1L, "Contact Birthdays"),
            calendar(20L, 2L, "Contact Anniversaries")
        )

        val groups = group(calendars, accounts)

        assertEquals("Contact Anniversaries", groups[0].accountName)
        assertEquals("Contact Birthdays", groups[1].accountName)
    }

    // ========== Empty / Edge Cases ==========

    @Test
    fun `empty calendars returns empty groups`() {
        val groups = group(emptyList(), emptyList())
        assertEquals(0, groups.size)
    }

    @Test
    fun `multiple calendars under same account grouped together`() {
        val accounts = listOf(account(1L, AccountProvider.ICLOUD, displayName = "iCloud"))
        val calendars = listOf(
            calendar(10L, 1L, "Work"),
            calendar(20L, 1L, "Personal")
        )

        val groups = group(calendars, accounts)

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].calendars.size)
        // Calendars are sorted by name within the group.
        assertEquals("Personal", groups[0].calendars[0].displayName)
        assertEquals("Work", groups[0].calendars[1].displayName)
    }

    // ========== Device groups ==========

    private fun deviceCalendar(id: Long, accountName: String) =
        org.onekash.kashcal.data.calendar_provider.DeviceCalendar(
            id = id, displayName = "Cal $id", color = 0, accountName = accountName,
            accountType = "org.example", visible = true, accessLevel = 700
        )

    @Test
    fun `a device calendar with no account name goes under the given local label`() {
        val groups = CalendarGroup.fromDeviceCalendars(
            listOf(deviceCalendar(1L, "")), writableOnly = false, localLabel = "Sur l'appareil"
        )

        assertEquals("Sur l'appareil", groups.single().accountName)
    }

    @Test
    fun `a device calendar with no account name defaults to Local`() {
        val groups = CalendarGroup.fromDeviceCalendars(listOf(deviceCalendar(1L, "")))

        assertEquals("Local", groups.single().accountName)
    }

    // ========== Offline calendar ids ==========

    @Test
    fun `offlineCalendarIds lists every calendar of the LOCAL account and nothing else`() {
        val accounts = listOf(
            account(1, AccountProvider.LOCAL, email = "local"),
            account(2, AccountProvider.CONTACTS, email = "contacts"),
            account(3, AccountProvider.CALDAV),
        )
        val calendars = listOf(
            calendar(10, 1).copy(caldavUrl = "local://default"),
            calendar(11, 1).copy(caldavUrl = "local://calendar/5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9"),
            calendar(20, 2).copy(caldavUrl = "local://contact_birthdays"),
            calendar(30, 3),
        )

        assertEquals(setOf(10L, 11L), group(calendars, accounts).offlineCalendarIds())
    }

    // ========== Picker sections ==========

    private val pickerAccounts = listOf(
        account(1, AccountProvider.ICLOUD, displayName = "iCloud"),
        account(2, AccountProvider.CALDAV, displayName = "Zoho"),
        account(3, AccountProvider.LOCAL, email = "local"),
    )
    private val pickerCalendars = listOf(calendar(10, 1), calendar(20, 2), calendar(30, 3))
    private val deviceGroups = CalendarGroup.fromDeviceCalendars(listOf(deviceCalendar(7L, "phone")))

    @Test
    fun `picker sections list accounts by name, then device calendars, then Offline`() {
        val sections = group(pickerCalendars, pickerAccounts).toPickerSections(deviceGroups)

        assertEquals(listOf("iCloud", "Zoho"), sections.accounts.map { it.accountName })
        assertEquals(listOf("phone"), sections.device.map { it.accountName })
        assertEquals(listOf("Offline"), sections.offline.map { it.accountName })
        assertTrue(sections.dividerBeforeDevice)
        assertTrue(sections.dividerBeforeOffline)
    }

    @Test
    fun `without device calendars the only divider is above Offline`() {
        val sections = group(pickerCalendars, pickerAccounts).toPickerSections(emptyList())

        assertFalse(sections.dividerBeforeDevice)
        assertTrue(sections.dividerBeforeOffline)
    }

    @Test
    fun `device groups without calendars are left out`() {
        val empty = CalendarGroup(accountName = "phone", accountId = -1, isDeviceSection = true)
        val sections = group(pickerCalendars, pickerAccounts).toPickerSections(listOf(empty))

        assertTrue(sections.device.isEmpty())
        assertFalse(sections.dividerBeforeDevice)
    }

    @Test
    fun `with only offline calendars there is no divider`() {
        val sections = group(listOf(calendar(30, 3)), pickerAccounts).toPickerSections(emptyList())

        assertTrue(sections.accounts.isEmpty())
        assertFalse(sections.dividerBeforeOffline)
    }

    @Test
    fun `device calendars listed first get no divider, Offline below them does`() {
        val sections = group(listOf(calendar(30, 3)), pickerAccounts).toPickerSections(deviceGroups)

        assertFalse(sections.dividerBeforeDevice)
        assertTrue(sections.dividerBeforeOffline)
    }

    @Test
    fun `device calendars alone get no divider`() {
        val sections = emptyList<CalendarGroup>().toPickerSections(deviceGroups)

        assertFalse(sections.dividerBeforeDevice)
        assertFalse(sections.dividerBeforeOffline)
        assertTrue(sections.offline.isEmpty())
    }

    @Test
    fun `contact groups stay with the accounts, after them`() {
        val accounts =
            pickerAccounts + account(4, AccountProvider.CONTACTS, displayName = "Birthdays")
        val calendars = pickerCalendars + calendar(40, 4)
        val sections = group(calendars, accounts).toPickerSections(emptyList())

        assertEquals(listOf("iCloud", "Zoho", "Birthdays"), sections.accounts.map { it.accountName })
    }
}
