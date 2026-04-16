package org.onekash.kashcal.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ========== Display Name Mapping ==========

    @Test
    fun `LOCAL account maps to Offline display name`() {
        val accounts = listOf(account(1L, AccountProvider.LOCAL, displayName = "On This Device"))
        val calendars = listOf(calendar(10L, 1L))

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        assertEquals(1, groups.size)
        assertEquals("Offline", groups[0].accountName)
    }

    @Test
    fun `ICLOUD account uses custom displayName`() {
        val accounts = listOf(account(1L, AccountProvider.ICLOUD, displayName = "Work iCloud"))
        val calendars = listOf(calendar(10L, 1L))

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        assertEquals("Work iCloud", groups[0].accountName)
    }

    @Test
    fun `account with null displayName falls back to provider displayName`() {
        val accounts = listOf(account(1L, AccountProvider.CALDAV, displayName = null))
        val calendars = listOf(calendar(10L, 1L))

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

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

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        val providerMap = groups.associate { it.accountName to it.provider }
        assertEquals(AccountProvider.ICLOUD, providerMap["iCloud"])
        assertEquals(AccountProvider.LOCAL, providerMap["Offline"])
        assertEquals(AccountProvider.CONTACTS, providerMap["Contact Birthdays"])
    }

    @Test
    fun `provider is null when account not found`() {
        // Calendar with accountId that has no matching account
        val accounts = emptyList<Account>()
        val calendars = listOf(calendar(10L, 999L))

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

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

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        // First two should be non-CONTACTS (iCloud, Offline), last two should be CONTACTS
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

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        assertEquals("Apple", groups[0].accountName)
        assertEquals("Offline", groups[1].accountName)
        assertEquals("Zoho", groups[2].accountName)
    }

    @Test
    fun `CONTACTS groups sort alphabetically among themselves`() {
        // Real data: CONTACTS accounts have displayName set from ContactEventType.calendarDisplayName
        val accounts = listOf(
            account(1L, AccountProvider.CONTACTS, displayName = "Contact Birthdays", email = "contact_birthdays"),
            account(2L, AccountProvider.CONTACTS, displayName = "Contact Anniversaries", email = "contact_anniversaries")
        )
        val calendars = listOf(
            calendar(10L, 1L, "Contact Birthdays"),
            calendar(20L, 2L, "Contact Anniversaries")
        )

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        assertEquals("Contact Anniversaries", groups[0].accountName)
        assertEquals("Contact Birthdays", groups[1].accountName)
    }

    // ========== Empty / Edge Cases ==========

    @Test
    fun `empty calendars returns empty groups`() {
        val groups = CalendarGroup.fromCalendarsAndAccounts(emptyList(), emptyList())
        assertEquals(0, groups.size)
    }

    @Test
    fun `multiple calendars under same account grouped together`() {
        val accounts = listOf(account(1L, AccountProvider.ICLOUD, displayName = "iCloud"))
        val calendars = listOf(
            calendar(10L, 1L, "Work"),
            calendar(20L, 1L, "Personal")
        )

        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts)

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].calendars.size)
        // Calendars sorted alphabetically within group
        assertEquals("Personal", groups[0].calendars[0].displayName)
        assertEquals("Work", groups[0].calendars[1].displayName)
    }
}
